/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.repl

import quasar.Predef._

import quasar.Errors.ETask
import quasar.config.{CoreConfig, FsPath, FsFile}
import quasar.console._
import quasar.effect._
import quasar.fp._
import quasar.fs.mount._
import quasar.main._

import org.jboss.aesh.console.{AeshConsoleCallback, Console, ConsoleOperation, Prompt}
import org.jboss.aesh.console.helper.InterruptHook
import org.jboss.aesh.console.settings.SettingsBuilder
import org.jboss.aesh.edit.actions.Action
import pathy.Path, Path._
import scalaz.{Failure => _, _}, Scalaz._
import scalaz.concurrent.Task

object Main {
  private def consoleIO(console: Console): ConsoleIO ~> Task =
    new (ConsoleIO ~> Task) {
      import ConsoleIO._

      def apply[A](c: ConsoleIO[A]): Task[A] = c match {
        case PrintLn(message) => Task.delay { console.getShell.out.println(message) }
      }
    }

  type DriverEff0[A] = Coproduct[ConsoleIOF, Task, A]
  type DriverEff[A] = Coproduct[ReplFailF, DriverEff0, A]
  type DriverEffM[A] = Free[DriverEff, A]

  private def driver(f: Command => Free[DriverEff, Unit]): Task[Unit] = Task.delay {
    val console =
      new Console(new SettingsBuilder()
        .parseOperators(false)
        .enableExport(false)
        .interruptHook(new InterruptHook {
          def handleInterrupt(console: Console, action: Action) = {
            console.getShell.out.println("exit")
            console.stop
          }
        })
        .create())
    console.setPrompt(new Prompt("💪 $ "))

    val i: DriverEff ~> MainTask = free.interpret3[ReplFailF, ConsoleIOF, Task, MainTask](
      Coyoneda.liftTF[ReplFail, MainTask](Failure.toError[ETask, String]),
      liftMT[Task, MainErrT].compose[ConsoleIOF](Coyoneda.liftTF(consoleIO(console))),
      liftMT[Task, MainErrT])

    console.setConsoleCallback(new AeshConsoleCallback() {
      override def execute(input: ConsoleOperation): Int = {
        val command = Command.parse(input.getBuffer.trim)
        command match {
          case Command.Exit =>
            console.stop()
          case _            =>
            f(command).foldMap(i).run.run.valueOr(
              err => console.getShell.out.println("Quasar error: " + err))
        }
        0
      }
    })

    console.start()

    ()
  }

  type ReplEff0[A] = Coproduct[Task, MountingFileSystem, A]
  type ReplEff1[A] = Coproduct[TimingF, ReplEff0, A]
  type ReplEff2[A] = Coproduct[ReplFailF, ReplEff1, A]
  type ReplEff3[A] = Coproduct[ConsoleIOF, ReplEff2, A]
  type ReplEff[A] = Coproduct[Repl.RunStateF, ReplEff3, A]

  def repl(fs: MountingFileSystem ~> DriverEffM): Task[Command => Free[DriverEff, Unit]] = {
    TaskRef(Repl.RunState(rootDir, DebugLevel.Normal, 10, OutputFormat.Table, Map())).map { ref =>
      def i: ReplEff ~> DriverEffM = free.interpret6[Repl.RunStateF, ConsoleIOF, ReplFailF, TimingF, Task, MountingFileSystem, DriverEffM](
        injectFT[Task, DriverEff].compose[Repl.RunStateF](Coyoneda.liftTF[Repl.RunStateT, Task](AtomicRef.fromTaskRef(ref))),
        injectFT[ConsoleIOF, DriverEff],
        injectFT[ReplFailF, DriverEff],
        injectFT[Task, DriverEff].compose[TimingF](Coyoneda.liftTF(Timing.toTask)),
        injectFT[Task, DriverEff],
        fs)

      (cmd => Repl.command[ReplEff](cmd).foldMap(i))
    }
  }

  private val DF = Failure.Ops[String, DriverEff](implicitly, Inject[ReplFailF, DriverEff])

  private val mt: MainTask ~> DriverEffM =
    new (MainTask ~> DriverEffM) {
      def apply[A](mt: MainTask[A]): DriverEffM[A] =
        free.lift(mt.run).into[DriverEff].flatMap(_.fold(
          err => DF.fail(err),
          _.point[DriverEffM]))
    }

  def main(args: Array[String]): Unit = {
    implicit val cfgOps = CoreConfig

    val main0: MainTask[Unit] = for {
      opts         <- CliOptions.parser.parse(args, CliOptions.default)
                      .cata(_.point[MainTask], MainTask.raiseError("couldn't parse options"))
      cfgPath      <- opts.config.fold(none[FsFile].point[MainTask])(cfg =>
                        FsPath.parseSystemFile(cfg)
                          .toRight(s"Invalid path to config file: $cfg")
                          .map(some))

      config       <- cfgPath.cata(CoreConfig.fromFile, CoreConfig.fromDefaultPaths).leftMap(_.shows)

      // NB: for now, there's no way to add mounts through the REPL, so no point
      // in starting if you can't do anything and can't correct the situation.
      _            <- if (config.mountings.toMap.isEmpty) MainTask.raiseError("No mounts present")
                      else ().point[MainTask]

      coreApi      <- CoreEff.interpreter.liftM[MainErrT]
      ephemeralApi =  CfgsErrsIO.toMainTask(MntCfgsIO.ephemeral) compose coreApi
      _            <- (mountAll[CoreEff](config.mountings) foldMap ephemeralApi).flatMapF(_.point[Task])

      cfgRef       <- TaskRef(config).liftM[MainErrT]
      durableApi   =  CfgsErrsIO.toMainTask(MntCfgsIO.durableFile(cfgRef, cfgPath)) compose coreApi

      r            <- EitherT.right(repl(mt compose (durableApi compose injectNT[MountingFileSystem, CoreEff])))
      _            <- EitherT.right(driver(r))
    } yield ()

    logErrors(main0).run
  }

}

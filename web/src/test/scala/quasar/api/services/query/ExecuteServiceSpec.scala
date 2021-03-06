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

package quasar.api.services.query

import quasar.Predef._
import quasar._, fp._
import quasar.fp.numeric._
import quasar.api.services.Fixture._
import quasar.api.matchers._
import quasar.api.PathUtils
import quasar.api.ApiError
import quasar.api.ApiErrorEntityDecoder._
import quasar.api.ToApiError.ops._
import quasar.fs._
import quasar.fs.PathArbitrary._
import quasar.fs.InMemory._

import argonaut.{Json => AJson, _}, Argonaut._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.{NonNegative, Positive => RPositive}
import eu.timepit.refined.scalacheck.numeric._
import matryoshka.Fix
import org.scalacheck.Arbitrary
import org.http4s._
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.scalaz.ScalazMatchers._
import pathy.Path._
import pathy.scalacheck.{AbsFileOf, RelFileOf}
import pathy.scalacheck.PathyArbitrary._
import rapture.json._, jsonBackends.argonaut._
import scalaz._, Scalaz._
import scalaz.concurrent.Task
import scalaz.stream.Process

class ExecuteServiceSpec extends Specification with FileSystemFixture with ScalaCheck with PathUtils {
  import queryFixture._
  import posixCodec.printPath
  import FileSystemError.executionFailed_

  type FileOf[A] = AbsFileOf[A] \/ RelFileOf[A]

  // Remove if eventually included in upstream scala-pathy
  implicit val arbitraryFileName: Arbitrary[FileName] =
    Arbitrary(Arbitrary.arbitrary[AFile].map(fileName(_)))

  def executeServiceRef(
    mem: InMemState,
    f: FileSystem ~> InMemoryFs
  ): (HttpService, Task[InMemState]) = {
    val (inter, ref) = runInspect(mem).run
    val svc = HttpService.lift(req =>
      execute.service[Eff]
        .toHttpService(effRespOr(inter compose f))
        .apply(req))

    (svc, ref)
  }

  def failingExecPlan[F[_]: Applicative](msg: String, f: FileSystem ~> F): FileSystem ~> F = {
    val qf: QueryFileF ~> F =
      f compose injectNT[QueryFileF, FileSystem]

    val failingQf: QueryFile ~> F = new (QueryFile ~> F) {
      import QueryFile._
      def apply[A](qa: QueryFile[A]) = qa match {
        case ExecutePlan(lp, _) =>
          (Vector[PhaseResult](), executionFailed_(lp, msg).left[AFile]).point[F]

        case otherwise =>
          qf(Coyoneda.lift(otherwise))
      }
    }

    free.transformIn[QueryFileF, FileSystem, F](
      Coyoneda.liftTF(failingQf), f)
  }

  def post[A: EntityDecoder](
    eval: FileSystem ~> InMemoryFs)(
    path: ADir,
    query: Option[Query],
    destination: Option[String],
    state: InMemState,
    status: Status,
    response: A => MatchResult[_],
    stateCheck: Option[InMemState => MatchResult[_]] = None
  ) = {

    val baseURI = pathUri(path)
    val baseReq = Request(uri = baseURI, method = Method.POST)
    val req = query.map { query =>
      val uri = baseURI.+??("offset", query.offset.map(_.shows)).+??("limit", query.limit.map(_.shows))
      val uri1 = query.varNameAndValue.map{ case (name, value) => uri.+?("var."+name,value)}.getOrElse(uri)
      baseReq.copy(uri = uri1).withBody(query.q).run
    }.getOrElse(baseReq)
    val req1 = destination.map(destination =>
      req.copy(headers = Headers(Header("Destination", destination)))
    ).getOrElse(req)
    val (service, ref) = executeServiceRef(state, eval)
    val actualResponse = service(req1).run
    val stateCheck0 = stateCheck.getOrElse((_: InMemState) ==== state)
    response(actualResponse.as[A].run) and (actualResponse.status must_== status) and stateCheck0(ref.run)
  }

  def toLP(q: String, vars: Variables): Fix[LogicalPlan] =
      sql.parse(sql.Query(q)).fold(
        error => scala.sys.error(s"could not compile query: $q due to error: $error"),
        ast => quasar.queryPlan(ast,vars).run.value.toOption.get)

  "Execute" should {
    "execute a simple query" >> {
      "GET" ! prop { filesystem: SingleFileMemState =>
        val query = selectAll(file(filesystem.filename.value))
        get(executeService)(
          path = filesystem.parent,
          query = Some(Query(query)),
          state = filesystem.state,
          status = Status.Ok,
          response = (a: String) => a must_== jsonReadableLine.encode(Process.emitAll(filesystem.contents): Process[Task, Data]).runLog.run.mkString("")
        )
      }
      "POST" ! prop { (filesystem: SingleFileMemState, destination: FPath) => {
        val destinationPath = printPath(destination)
        val expectedDestinationPath = refineTypeAbs(destination).fold(ι, filesystem.parent </> _)
        post[AJson](fileSystem)(
          path = filesystem.parent,
          query = Some(Query(selectAll(file1(filesystem.filename)))),
          destination = Some(destinationPath),
          state = filesystem.state,
          status = Status.Ok,
          response = json => Json(json) must beLike { case json""" { "out": $outValue, "phases": $outPhases }""" =>
            outValue.as[String] must_== printPath(expectedDestinationPath)
          },
          stateCheck = Some(s => s.contents.keys must contain(expectedDestinationPath)))
      }}
    }
    "execute a query with offset and limit and a variable" >> {
      def queryAndExpectedLP(aFile: AFile, varName: AlphaCharacters, var_ : Int): (String,Fix[LogicalPlan]) = {
        val query = selectAllWithVar(file1(fileName(aFile)), varName.value)
        val inlineQuery = selectAllWithVar(aFile, varName.value)
        val lp = toLP(inlineQuery, Variables.fromMap(Map(varName.value -> var_.toString)))
        (query,lp)
      }
      "GET" ! prop { (
        filesystem: SingleFileMemState,
        varName: AlphaCharacters,
        var_ : Int,
        offset: Int Refined NonNegative,
        limit: Int Refined RPositive) =>
          import quasar.std.StdLib.set._

          val (query, lp) = queryAndExpectedLP(filesystem.file, varName, var_)
          val limitedLp =
            Fix(Take(
              Fix(Drop(
                lp,
                LogicalPlan.Constant(Data.Int(offset.get)))),
              LogicalPlan.Constant(Data.Int(limit.get))))
          val limitedContents =
            filesystem.contents
              .drop(offset.get)
              .take(limit.get)

          get(executeService)(
            path = filesystem.parent,
            query = Some(Query(
              query,
              offset = Some(offset),
              limit = Some(Positive(limit.get.toLong).get),
              varNameAndValue = Some((varName.value, var_.toString)))),
            state = filesystem.state.copy(queryResps = Map(limitedLp -> limitedContents)),
            status = Status.Ok,
            response = (a: String) => a must_==
              jsonReadableLine.encode(Process.emitAll(filesystem.contents): Process[Task, Data]).runLog.run
                .drop(offset.get).take(limit.get).mkString(""))
      }
      "POST" ! prop { (filesystem: SingleFileMemState, varName: AlphaCharacters, var_ : Int, offset: Natural, limit: Positive, destination: FPath) =>
        val (query, lp) = queryAndExpectedLP(filesystem.file, varName, var_)
        val destinationPath = printPath(destination)
        val expectedDestinationPath = refineTypeAbs(destination).fold(ι, filesystem.parent </> _)
        post[AJson](fileSystem)(
          path = filesystem.parent,
          query = Some(Query(query, offset = Some(offset), limit = Some(limit), varNameAndValue = Some((varName.value, var_.toString)))),
          destination = Some(destinationPath),
          state = filesystem.state.copy(queryResps = Map(lp -> filesystem.contents)),
          status = Status.Ok,
          response = json => Json(json) must beLike { case json""" { "out": $outValue, "phases": $outPhases }""" =>
            outValue.as[String] must_== printPath(expectedDestinationPath)
          },
          stateCheck = Some(s => s.contents.keys must contain(expectedDestinationPath)))
      }
    }
    "POST (error conditions)" >> {
      "be 404 for missing directory" ! prop { (dir: ADir, destination: AFile, filename: FileName) =>
        post[String](fileSystem)(
          path = dir,
          query = Some(Query(selectAll(file(filename.value)))),
          destination = Some(printPath(destination)),
          state = InMemState.empty,
          status = Status.NotFound,
          response = _ must_== "???"
        )
      }.pendingUntilFixed("SD-773")
      "be 400 with missing query" ! prop { (filesystem: SingleFileMemState, destination: AFile) =>
        post[ApiError](fileSystem)(
          path = filesystem.parent,
          query = None,
          destination = Some(printPath(destination)),
          state = filesystem.state,
          status = Status.BadRequest,
          response = _ must equal(ApiError.fromStatus(
            Status.BadRequest withReason "No SQL^2 query found in message body."))
        )
      }
      "be 400 with missing Destination header" ! prop { filesystem: SingleFileMemState =>
        post[ApiError](fileSystem)(
          path = filesystem.parent,
          query = Some(Query(selectAll(file(filesystem.filename.value)))),
          destination = None,
          state = filesystem.state,
          status = Status.BadRequest,
          response = _ must beHeaderMissingError("Destination")
        )
      }
      "be 400 for query error" ! prop { (filesystem: SingleFileMemState, destination: AFile) =>
        post[ApiError](fileSystem)(
          path = filesystem.parent,
          query = Some(Query("select date where")),
          destination = Some(printPath(destination)),
          state = filesystem.state,
          status = Status.BadRequest,
          response = _ must beApiErrorWithMessage(
            Status.BadRequest withReason "Malformed SQL^2 query.")
        )
      }
      "be 400 for compile error" ! prop { (fs: SingleFileMemState, dst: AFile) =>
        val q = "select sum(1, 2, 3, 4)"

        val err: SemanticError =
          SemanticError.WrongArgumentCount(quasar.std.AggLib.Sum, 1, 4)

        val expr: sql.Expr = sql.parse(sql.Query(q)).valueOr(
          err => scala.sys.error("Parse failed: " + err.toString))

        val phases: PhaseResults =
          queryPlan(expr, Variables.empty).run.written

        post[ApiError](fileSystem)(
          path = fs.parent,
          query = Some(Query(q)),
          destination = Some(printPath(dst)),
          state = fs.state,
          status = Status.BadRequest,
          response = _ must equal(NonEmptyList(err).toApiError :+ ("phases" := phases))
        )
      }
      "be 500 for execution error" >> {
        val q = s"select * from `/foo`"
        val lp = toLP(q, Variables.empty)
        val msg = "EXEC FAILED"
        val err = executionFailed_(lp, msg)

        val expr: sql.Expr = sql.parse(sql.Query(q)).valueOr(
          err => scala.sys.error("Parse failed: " + err.toString))

        val phases: PhaseResults =
          queryPlan(expr, Variables.empty).run.written

        post[ApiError](failingExecPlan(msg, fileSystem))(
          path = rootDir,
          query = Some(Query(q)),
          destination = Some(printPath(rootDir </> file("outA"))),
          state = InMemState.empty,
          status = Status.InternalServerError,
          response = _ must equal(err.toApiError :+ ("phases" := phases))
        )
      }
    }
  }
}

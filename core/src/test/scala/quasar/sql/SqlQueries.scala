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

package quasar.sql

object SqlQueries {
  val q1 = """
select
  l_returnflag,
  l_linestatus,
  sum(l_quantity) as sum_qty,
  sum(l_extendedprice) as sum_base_price,
  sum(l_extendedprice * (1 - l_discount)) as sum_disc_price,
  sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) as sum_charge,
  avg(l_quantity) as avg_qty,
  avg(l_extendedprice) as avg_price,
  avg(l_discount) as avg_disc,
  count(*) as count_order
from
  lineitem
where
  l_shipdate <= date("1998-12-01") - interval("5 day")
group by
  l_returnflag,
  l_linestatus
order by
  l_returnflag,
  l_linestatus;"""

  val q2 = """
select
  s_acctbal,
  s_name,
  n_name,
  p_partkey,
  p_mfgr,
  s_address,
  s_phone,
  s_comment
from
  part,
  supplier,
  partsupp,
  nation,
  region
where
  p_partkey = ps_partkey
  and s_suppkey = ps_suppkey
  and p_size = 10
  and p_type like "%foo"
  and s_nationkey = n_nationkey
  and n_regionkey = r_regionkey
  and r_name = "somename"
  and ps_supplycost = (
    select
      min(ps_supplycost)
    from
      partsupp,
      supplier,
      nation,
      region
    where
      p_partkey = ps_partkey
      and s_suppkey = ps_suppkey
      and s_nationkey = n_nationkey
      and n_regionkey = r_regionkey
      and r_name = "somename"
  )
order by
  s_acctbal desc,
  n_name,
  s_name,
  p_partkey
limit 100;
"""

  val q3 = """
select
  l_orderkey,
  sum(l_extendedprice * (1 - l_discount)) as revenue,
  o_orderdate,
  o_shippriority
from
  customer,
  orders,
  lineitem
where
  c_mktsegment = "somesegment"
  and c_custkey = o_custkey
  and l_orderkey = o_orderkey
  and o_orderdate < date("1999-01-01")
  and l_shipdate > date("1999-01-01")
group by
  l_orderkey,
  o_orderdate,
  o_shippriority
order by
  revenue desc,
  o_orderdate
limit 10;
"""

  val q4 = """
select
  o_orderpriority,
  count(*) as order_count
from
  orders
where
  o_orderdate >= date("1999-01-01")
  and o_orderdate < date("1999-01-01") + interval("3 month")
  and exists (
    select
      *
    from
      lineitem
    where
      l_orderkey = o_orderkey
      and l_commitdate < l_receiptdate
  )
group by
  o_orderpriority
order by
  o_orderpriority;
"""

  val q5 = """
select
  n_name,
  sum(l_extendedprice * (1 - l_discount)) as revenue
from
  customer,
  orders,
  lineitem,
  supplier,
  nation,
  region
where
  c_custkey = o_custkey
  and l_orderkey = o_orderkey
  and l_suppkey = s_suppkey
  and c_nationkey = s_nationkey
  and s_nationkey = n_nationkey
  and n_regionkey = r_regionkey
  and r_name = "foo"
  and o_orderdate >= date("1999-01-01")
  and o_orderdate < date("1999-01-01") + interval("1 day")
group by
  n_name
order by
  revenue desc;
"""

  val q6 = """
select
  sum(l_extendedprice * l_discount) as revenue
from
  lineitem
where
  l_shipdate >= date("1999-01-01")
  and l_shipdate < date("1999-01-01") + interval("1 day")
  and l_discount between 2 - 0.01 and 2 + 0.01
  and l_quantity < 3;
"""

  val q7 = """
select
  supp_nation,
  cust_nation,
  l_year,
  sum(volume) as revenue
from
  (
    select
      n1.n_name as supp_nation,
      n2.n_name as cust_nation,
      date_part("year", l_shipdate) as l_year,
      l_extendedprice * (1 - l_discount) as volume
    from
      supplier,
      lineitem,
      orders,
      customer,
      nation as n1,
      nation as n2
    where
      s_suppkey = l_suppkey
      and o_orderkey = l_orderkey
      and c_custkey = o_custkey
      and s_nationkey = n1.n_nationkey
      and c_nationkey = n2.n_nationkey
      and (
        (n1.n_name = "a" and n2.n_name = "b")
        or (n1.n_name = "b" and n2.n_name = "a")
      )
      and l_shipdate between date("1995-01-01") and date("1996-12-31")
  ) as shipping
group by
  supp_nation,
  cust_nation,
  l_year
order by
  supp_nation,
  cust_nation,
  l_year;
"""

  val q8 = """
select
  o_year,
  sum(case
    when nation = "nation" then volume
    else 0
  end) / sum(volume) as mkt_share
from
  (
    select
      date_part("year", o_orderdate) as o_year,
      l_extendedprice * (1 - l_discount) as volume,
      n2.n_name as nation
    from
      part,
      supplier,
      lineitem,
      orders,
      customer,
      nation as n1,
      nation as n2,
      region
    where
      p_partkey = l_partkey
      and s_suppkey = l_suppkey
      and l_orderkey = o_orderkey
      and o_custkey = c_custkey
      and c_nationkey = n1.n_nationkey
      and n1.n_regionkey = r_regionkey
      and r_name = "rname"
      and s_nationkey = n2.n_nationkey
      and o_orderdate between date("1995-01-01") and date("1996-12-31")
      and p_type = "ptype"
  ) as all_nations
group by
  o_year
order by
  o_year;
"""

  val q9 = """
select
  nation,
  o_year,
  sum(amount) as sum_profit
from
  (
    select
      n_name as nation,
      date_part("year", o_orderdate) as o_year,
      l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity as amount
    from
      part,
      supplier,
      lineitem,
      partsupp,
      orders,
      nation
    where
      s_suppkey = l_suppkey
      and ps_suppkey = l_suppkey
      and ps_partkey = l_partkey
      and p_partkey = l_partkey
      and o_orderkey = l_orderkey
      and s_nationkey = n_nationkey
      and p_name like "%sky%"
  ) as profit
group by
  nation,
  o_year
order by
  nation,
  o_year desc;
"""

  val q10 = """
select
  c_custkey,
  c_name,
  sum(l_extendedprice * (1 - l_discount)) as revenue,
  c_acctbal,
  n_name,
  c_address,
  c_phone,
  c_comment
from
  customer,
  orders,
  lineitem,
  nation
where
  c_custkey = o_custkey
  and l_orderkey = o_orderkey
  and o_orderdate >= date("1999-01-01")
  and o_orderdate < date("1999-01-01") + interval("3 month")
  and l_returnflag = "R"
  and c_nationkey = n_nationkey
group by
  c_custkey,
  c_name,
  c_acctbal,
  c_phone,
  n_name,
  c_address,
  c_comment
order by
  revenue desc
"""

  val q11 = """
select
  ps_partkey,
  sum(ps_supplycost * ps_availqty) as value
from
  partsupp,
  supplier,
  nation
where
  ps_suppkey = s_suppkey
  and s_nationkey = n_nationkey
  and n_name = "nnation"
group by
  ps_partkey having
    sum(ps_supplycost * ps_availqty) > (
      select
        sum(ps_supplycost * ps_availqty) * 300
      from
        partsupp,
        supplier,
        nation
      where
        ps_suppkey = s_suppkey
        and s_nationkey = n_nationkey
        and n_name = "name"
    )
order by
  value desc;
"""

  val q12 = """
select
  l_shipmode,
  sum(case
    when o_orderpriority = "1-URGENT"
      or o_orderpriority = "2-HIGH"
      then 1
    else 0
  end) as high_line_count,
  sum(case
    when o_orderpriority <> "1-URGENT"
      and o_orderpriority <> "2-HIGH"
      then 1
    else 0
  end) as low_line_count
from
  orders,
  lineitem
where
  o_orderkey = l_orderkey
  and l_shipmode in ("mode0", "mode1")
  and l_commitdate < l_receiptdate
  and l_shipdate < l_commitdate
  and l_receiptdate >= date("1998-01-01")
  and l_receiptdate < date("1998-01-01") + interval("1 year")
group by
  l_shipmode
order by
  l_shipmode;
"""

  val q13 = """
select
  c_count,
  count(*) as custdist
from
  (
    select
      c_custkey,
      count(o_orderkey) as c_count
    from
      customer left outer join orders on
        c_custkey = o_custkey
        and o_comment not like "%:1%:2%"
    group by
      c_custkey
  ) as c_orders
group by
  c_count
order by
  custdist desc,
  c_count desc;
"""

  val q14 = """
select
  100.00 * sum(case
    when p_type like "PROMO%"
      then l_extendedprice * (1 - l_discount)
    else 0
  end) / sum(l_extendedprice * (1 - l_discount)) as promo_revenue
from
  lineitem,
  part
where
  l_partkey = p_partkey
  and l_shipdate >= date("1999-01-01")
  and l_shipdate < date("1999-01-01") + interval("1 month");
"""

  val q16 = """
select
  p_brand,
  p_type,
  p_size,
  count(distinct ps_suppkey) as supplier_cnt
from
  partsupp,
  part
where
  p_partkey = ps_partkey
  and p_brand <> "foo"
  and p_type not like "bar%"
  and p_size in (3, 4, 5, 6, 7, 8, 9, 10)
  and ps_suppkey not in (
    select
      s_suppkey
    from
      supplier
    where
      s_comment like "%Customer%Complaints%"
  )
group by
  p_brand,
  p_type,
  p_size
order by
  supplier_cnt desc,
  p_brand,
  p_type,
  p_size;
"""

  val q17 = """
select
  sum(l_extendedprice) / 7.0 as avg_yearly
from
  lineitem,
  part
where
  p_partkey = l_partkey
  and p_brand = "a"
  and p_container = "b"
  and l_quantity < (
    select
      0.2 * avg(l_quantity)
    from
      lineitem
    where
      l_partkey = p_partkey
  );
"""

  val q18 = """
select
  c_name,
  c_custkey,
  o_orderkey,
  o_orderdate,
  o_totalprice,
  sum(l_quantity)
from
  customer,
  orders,
  lineitem
where
  o_orderkey in (
    select
      l_orderkey
    from
      lineitem
    group by
      l_orderkey having
        sum(l_quantity) > 330
  )
  and c_custkey = o_custkey
  and o_orderkey = l_orderkey
group by
  c_name,
  c_custkey,
  o_orderkey,
  o_orderdate,
  o_totalprice
order by
  o_totalprice desc,
  o_orderdate
limit 100;
"""

  val q19 = """
select
  sum(l_extendedprice* (1 - l_discount)) as revenue
from
  lineitem,
  part
where
  (
    p_partkey = l_partkey
    and p_brand = "foo"
    and p_container in ("SM CASE", "SM BOX", "SM PACK", "SM PKG")
    and l_quantity >= 4 and l_quantity <= 4 + 10
    and p_size between 1 and 5
    and l_shipmode in ("AIR", "AIR REG")
    and l_shipinstruct = "DELIVER IN PERSON"
  )
  or
  (
    p_partkey = l_partkey
    and p_brand = "bar"
    and p_container in ("MED BAG", "MED BOX", "MED PKG", "MED PACK")
    and l_quantity >= 5 and l_quantity <= 5 + 10
    and p_size between 1 and 10
    and l_shipmode in ("AIR", "AIR REG")
    and l_shipinstruct = "DELIVER IN PERSON"
  )
  or
  (
    p_partkey = l_partkey
    and p_brand = "baz"
    and p_container in ("LG CASE", "LG BOX", "LG PACK", "LG PKG")
    and l_quantity >= 6 and l_quantity <= 6 + 10
    and p_size between 1 and 15
    and l_shipmode in ("AIR", "AIR REG")
    and l_shipinstruct = "DELIVER IN PERSON"
  );
"""

  val q20 = """
select
  s_name,
  s_address
from
  supplier,
  nation
where
  s_suppkey in (
    select
      ps_suppkey
    from
      partsupp
    where
      ps_partkey in (
        select
          p_partkey
        from
          part
        where
          p_name like "foo%"
      )
      and ps_availqty > (
        select
          0.5 * sum(l_quantity)
        from
          lineitem
        where
          l_partkey = ps_partkey
          and l_suppkey = ps_suppkey
          and l_shipdate >= date("1999-01-01")
          and l_shipdate < date("1999-01-01") + interval("1 year")
      )
  )
  and s_nationkey = n_nationkey
  and n_name = "foo"
order by
  s_name;
"""

  val q21 = """
select
  s_name,
  count(*) as numwait
from
  supplier,
  lineitem as l1,
  orders,
  nation
where
  s_suppkey = l1.l_suppkey
  and o_orderkey = l1.l_orderkey
  and o_orderstatus = "F"
  and l1.l_receiptdate > l1.l_commitdate
  and exists (
    select
      *
    from
      lineitem as l2
    where
      l2.l_orderkey = l1.l_orderkey
      and l2.l_suppkey <> l1.l_suppkey
  )
  and not exists (
    select
      *
    from
      lineitem as l3
    where
      l3.l_orderkey = l1.l_orderkey
      and l3.l_suppkey <> l1.l_suppkey
      and l3.l_receiptdate > l3.l_commitdate
  )
  and s_nationkey = n_nationkey
  and n_name = "foo"
group by
  s_name
order by
  numwait desc,
  s_name
limit 100;
"""

  val q22 = """
select
  cntrycode,
  count(*) as numcust,
  sum(c_acctbal) as totacctbal
from
  (
    select
      substring(c_phone, 1, 2) as cntrycode,
      c_acctbal
    from
      customer
    where
      substring(c_phone, 1, 2) in
        ("11", "22", "33", "44", "55", "66", "77")
      and c_acctbal > (
        select
          avg(c_acctbal)
        from
          customer
        where
          c_acctbal > 0.00
          and substring(c_phone, 1, 2) in
            ("11", "22", "33", "44", "55", "66", "77")
      )
      and not exists (
        select
          *
        from
          orders
        where
          o_custkey = c_custkey
      )
  ) as custsale
group by
  cntrycode
order by
  cntrycode;
"""
}

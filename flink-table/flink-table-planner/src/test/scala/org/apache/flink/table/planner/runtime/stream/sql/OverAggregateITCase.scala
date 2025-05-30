/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.runtime.stream.sql

import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.scala._
import org.apache.flink.table.api.config.ExecutionConfigOptions
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.runtime.utils.{StreamingEnvUtil, StreamingWithStateTestBase, TestData, TestingAppendSink}
import org.apache.flink.table.planner.runtime.utils.BatchTestBase.row
import org.apache.flink.table.planner.runtime.utils.StreamingWithStateTestBase.{HEAP_BACKEND, ROCKSDB_BACKEND, StateBackendMode}
import org.apache.flink.table.planner.runtime.utils.TimeTestUtil.EventTimeProcessOperator
import org.apache.flink.table.planner.runtime.utils.UserDefinedFunctionTestUtils.{CountNullNonNull, CountPairs, LargerThanCount}
import org.apache.flink.table.runtime.typeutils.BigDecimalTypeInfo
import org.apache.flink.testutils.junit.extensions.parameterized.{ParameterizedTestExtension, Parameters}
import org.apache.flink.types.Row

import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.assertj.core.data.Percentage
import org.junit.jupiter.api.{BeforeEach, TestTemplate}
import org.junit.jupiter.api.extension.ExtendWith

import java.time.{Instant, LocalDateTime}
import java.util

import scala.collection.{mutable, Seq}

@ExtendWith(Array(classOf[ParameterizedTestExtension]))
class OverAggregateITCase(mode: StateBackendMode, unboundedOverVersion: Int)
  extends StreamingWithStateTestBase(mode) {

  val data = List(
    (1L, 1, "Hello"),
    (2L, 2, "Hello"),
    (3L, 3, "Hello"),
    (4L, 4, "Hello"),
    (5L, 5, "Hello"),
    (6L, 6, "Hello"),
    (7L, 7, "Hello World"),
    (8L, 8, "Hello World"),
    (20L, 20, "Hello World")
  )

  @BeforeEach
  def setupEnv(): Unit = {
    // unaligned checkpoints are regenerating watermarks after recovery of in-flight data
    // https://issues.apache.org/jira/browse/FLINK-18405
    env.getCheckpointConfig.enableUnalignedCheckpoints(false)
    tEnv.getConfig.set[Integer](ExecutionConfigOptions.UNBOUNDED_OVER_VERSION, unboundedOverVersion)
  }

  @TestTemplate
  def testLagFunction(): Unit = {
    val sqlQuery = "SELECT a, b, c, " +
      "  LAG(b) OVER(PARTITION BY a ORDER BY rowtime)," +
      "  LAG(b, 2) OVER(PARTITION BY a ORDER BY rowtime)," +
      "  LAG(b, 2, CAST(10086 AS BIGINT)) OVER(PARTITION BY a ORDER BY rowtime)" +
      "FROM T1"

    val data: Seq[Either[(Long, (Int, Long, String)), Long]] = Seq(
      Left(14000001L, (1, 1L, "Hi")),
      Left(14000005L, (1, 2L, "Hi")),
      Left(14000002L, (1, 3L, "Hello")),
      Left(14000003L, (1, 4L, "Hello")),
      Left(14000003L, (1, 5L, "Hello")),
      Right(14000020L),
      Left(14000021L, (1, 6L, "Hello world")),
      Left(14000022L, (1, 7L, "Hello world")),
      Right(14000030L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Int, Long, String)]],
        new EventTimeProcessOperator[(Int, Long, String)]
      )
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      s"1,1,Hi,null,null,10086",
      s"1,3,Hello,1,null,10086",
      s"1,4,Hello,4,3,3",
      s"1,5,Hello,4,3,3",
      s"1,2,Hi,5,4,4",
      s"1,6,Hello world,2,5,5",
      s"1,7,Hello world,6,2,2"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testLeadFunction(): Unit = {
    val sqlQuery = "SELECT a, b, c, " +
      "  LEAD(b) OVER(PARTITION BY a ORDER BY rowtime)," +
      "  LEAD(b, 2) OVER(PARTITION BY a ORDER BY rowtime)," +
      "  LEAD(b, 2, CAST(10086 AS BIGINT)) OVER(PARTITION BY a ORDER BY rowtime)" +
      "FROM T1"

    val data: Seq[Either[(Long, (Int, Long, String)), Long]] = Seq(
      Left(14000001L, (1, 1L, "Hi")),
      Left(14000003L, (1, 5L, "Hello")),
      Right(14000020L),
      Left(14000021L, (1, 6L, "Hello world")),
      Left(14000022L, (1, 7L, "Hello world")),
      Right(14000030L)
    )
    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Int, Long, String)]],
        new EventTimeProcessOperator[(Int, Long, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)
    tEnv.createTemporaryView("T1", t1)
    val sink = new TestingAppendSink

    assertThatThrownBy(() => tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink))
      .hasMessageContaining("LEAD Function is not supported in stream mode")

    env.execute()
  }

  @TestTemplate
  def testRowNumberOnOver(): Unit = {
    val t = failingDataSource(TestData.tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)
    val sqlQuery = "SELECT a, ROW_NUMBER() OVER (PARTITION BY a ORDER BY proctime()) FROM MyTable"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "1,1",
      "2,1",
      "2,2",
      "3,1",
      "3,2",
      "3,3",
      "4,1",
      "4,2",
      "4,3",
      "4,4",
      "5,1",
      "5,2",
      "5,3",
      "5,4",
      "5,5")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testDenseRankOnOver(): Unit = {
    val t = failingDataSource(TestData.tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)
    val sqlQuery = "SELECT a, DENSE_RANK() OVER (PARTITION BY a ORDER BY proctime) FROM MyTable"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "1,1",
      "2,1",
      "2,2",
      "3,1",
      "3,2",
      "3,3",
      "4,1",
      "4,2",
      "4,3",
      "4,4",
      "5,1",
      "5,2",
      "5,3",
      "5,4",
      "5,5")
    assertThat(expected.sorted).isEqualTo(sink.getAppendResults.sorted)
  }

  @TestTemplate
  def testRankOnOver(): Unit = {
    val t = failingDataSource(TestData.tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)
    val sqlQuery = "SELECT a, RANK() OVER (PARTITION BY a ORDER BY proctime) FROM MyTable"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "1,1",
      "2,1",
      "2,2",
      "3,1",
      "3,2",
      "3,3",
      "4,1",
      "4,2",
      "4,3",
      "4,4",
      "5,1",
      "5,2",
      "5,3",
      "5,4",
      "5,5")
    assertThat(expected.sorted).isEqualTo(sink.getAppendResults.sorted)
  }

  @TestTemplate
  def testProcTimeBoundedPartitionedRowsOver(): Unit = {
    val t = failingDataSource(TestData.tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery = "SELECT a, " +
      "  SUM(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime ROWS BETWEEN 4 PRECEDING AND CURRENT ROW), " +
      "  MIN(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) " +
      "FROM MyTable"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "1,0,0",
      "2,1,1",
      "2,3,1",
      "3,3,3",
      "3,7,3",
      "3,12,3",
      "4,6,6",
      "4,13,6",
      "4,21,6",
      "4,30,6",
      "5,10,10",
      "5,21,10",
      "5,33,10",
      "5,46,10",
      "5,60,10")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeBoundedPartitionedRowsOverWithBulitinProctime(): Unit = {
    val t = failingDataSource(TestData.tupleData5).toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery = "SELECT a, " +
      "  SUM(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime() ROWS BETWEEN 4 PRECEDING AND CURRENT ROW), " +
      "  MIN(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime() ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) " +
      "FROM MyTable"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "1,0,0",
      "2,1,1",
      "2,3,1",
      "3,3,3",
      "3,7,3",
      "3,12,3",
      "4,6,6",
      "4,13,6",
      "4,21,6",
      "4,30,6",
      "5,10,10",
      "5,21,10",
      "5,33,10",
      "5,46,10",
      "5,60,10")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeBoundedPartitionedRowsOverWithBuiltinProctime(): Unit = {
    val t = failingDataSource(TestData.tupleData5).toTable(tEnv, 'a, 'b, 'c, 'd, 'e)
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery = "SELECT a, " +
      "  SUM(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime() ROWS BETWEEN 4 PRECEDING AND CURRENT ROW), " +
      "  MIN(c) OVER (" +
      "    PARTITION BY a ORDER BY proctime() ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) " +
      "FROM MyTable"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "1,0,0",
      "2,1,1",
      "2,3,1",
      "3,3,3",
      "3,7,3",
      "3,12,3",
      "4,6,6",
      "4,13,6",
      "4,21,6",
      "4,30,6",
      "5,10,10",
      "5,21,10",
      "5,33,10",
      "5,46,10",
      "5,60,10")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeBoundedNonPartitionedRowsOver(): Unit = {
    val t = failingDataSource(TestData.tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery = "SELECT a, " +
      " first_value(d) OVER (" +
      "    ORDER BY proctime ROWS BETWEEN 10 PRECEDING AND CURRENT ROW), " +
      " last_value(d) OVER (" +
      "    ORDER BY proctime ROWS BETWEEN 10 PRECEDING AND CURRENT ROW), " +
      "  SUM(c) OVER (" +
      "    ORDER BY proctime ROWS BETWEEN 10 PRECEDING AND CURRENT ROW), " +
      "  MIN(c) OVER (" +
      "    ORDER BY proctime ROWS BETWEEN 10 PRECEDING AND CURRENT ROW) " +
      "FROM MyTable"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "1,Hallo,Hallo,0,0",
      "2,Hallo,Hallo Welt,1,0",
      "2,Hallo,Hallo Welt wie,3,0",
      "3,Hallo,Hallo Welt wie gehts?,6,0",
      "3,Hallo,ABC,10,0",
      "3,Hallo,BCD,15,0",
      "4,Hallo,CDE,21,0",
      "4,Hallo,DEF,28,0",
      "4,Hallo,EFG,36,0",
      "4,Hallo,FGH,45,0",
      "5,Hallo,GHI,55,0",
      "5,Hallo Welt,HIJ,66,1",
      "5,Hallo Welt wie,IJK,77,2",
      "5,Hallo Welt wie gehts?,JKL,88,3",
      "5,ABC,KLM,99,4"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeUnboundedPartitionedRangeOver(): Unit = {
    val t1 = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'proctime.proctime)

    tEnv.createTemporaryView("T1", t1)

    val sqlQuery = "SELECT " +
      "c, " +
      "first_value(b) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED preceding)," +
      "last_value(b) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED preceding)," +
      "count(a) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED preceding), " +
      "sum(a) OVER (PARTITION BY c ORDER BY proctime RANGE UNBOUNDED preceding) " +
      "from T1"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello World,7,7,1,7",
      "Hello World,7,8,2,15",
      "Hello World,7,20,3,35",
      "Hello,1,1,1,1",
      "Hello,1,2,2,3",
      "Hello,1,3,3,6",
      "Hello,1,4,4,10",
      "Hello,1,5,5,15",
      "Hello,1,6,6,21"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeUnboundedPartitionedRowsOver(): Unit = {
    val t1 = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'proctime.proctime)

    tEnv.createTemporaryView("T1", t1)

    val sql =
      """
        |SELECT c, sum1, maxnull
        |FROM (
        | SELECT c,
        |  max(cast(null as varchar)) OVER
        |   (PARTITION BY c ORDER BY proctime ROWS BETWEEN UNBOUNDED preceding AND CURRENT ROW)
        |   as maxnull,
        |  sum(1) OVER
        |   (PARTITION BY c ORDER BY proctime ROWS BETWEEN UNBOUNDED preceding AND CURRENT ROW)
        |   as sum1
        | FROM T1
        |)
      """.stripMargin

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sql).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello World,1,null",
      "Hello World,2,null",
      "Hello World,3,null",
      "Hello,1,null",
      "Hello,2,null",
      "Hello,3,null",
      "Hello,4,null",
      "Hello,5,null",
      "Hello,6,null"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)

  }

  @TestTemplate
  def testProcTimeUnboundedNonPartitionedRangeOver(): Unit = {
    val t1 = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'proctime.proctime)

    tEnv.createTemporaryView("T1", t1)

    val sqlQuery = "SELECT " +
      "c, " +
      "count(a) OVER (ORDER BY proctime  RANGE UNBOUNDED preceding), " +
      "sum(a) OVER (ORDER BY proctime RANGE UNBOUNDED preceding) " +
      "from T1"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello World,7,28",
      "Hello World,8,36",
      "Hello World,9,56",
      "Hello,1,1",
      "Hello,2,3",
      "Hello,3,6",
      "Hello,4,10",
      "Hello,5,15",
      "Hello,6,21")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeUnboundedNonPartitionedRowsOver(): Unit = {
    val t1 = failingDataSource(data).toTable(tEnv, 'a, 'b, 'c, 'proctime.proctime)

    tEnv.createTemporaryView("T1", t1)

    val sqlQuery = "SELECT " +
      "listagg(distinct c, '|') " +
      "  OVER (ORDER BY proctime ROWS BETWEEN UNBOUNDED preceding AND CURRENT ROW), " +
      "count(a) " +
      "  OVER (ORDER BY proctime ROWS BETWEEN UNBOUNDED preceding AND CURRENT ROW) " +
      "from T1"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello,1",
      "Hello,2",
      "Hello,3",
      "Hello,4",
      "Hello,5",
      "Hello,6",
      "Hello|Hello World,7",
      "Hello|Hello World,8",
      "Hello|Hello World,9")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeBoundedPartitionedRangeOver(): Unit = {
    val data: Seq[Either[(Long, (Long, Int, String)), Long]] = Seq(
      Left((1500L, (1L, 15, "Hello"))),
      Left((1600L, (1L, 16, "Hello"))),
      Left((1000L, (1L, 1, "Hello"))),
      Left((2000L, (2L, 2, "Hello"))),
      Right(1000L),
      Left((2000L, (2L, 2, "Hello"))),
      Left((2000L, (2L, 3, "Hello"))),
      Left((3000L, (3L, 3, "Hello"))),
      Right(2000L),
      Left((4000L, (4L, 4, "Hello"))),
      Right(3000L),
      Left((5000L, (5L, 5, "Hello"))),
      Right(5000L),
      Left((6000L, (6L, 6, "Hello"))),
      Left((6500L, (6L, 65, "Hello"))),
      Right(7000L),
      Left((9000L, (6L, 9, "Hello"))),
      Left((9500L, (6L, 18, "Hello"))),
      Left((9000L, (6L, 9, "Hello"))),
      Right(10000L),
      Left((10000L, (7L, 7, "Hello World"))),
      Left((11000L, (7L, 17, "Hello World"))),
      Left((11000L, (7L, 77, "Hello World"))),
      Right(12000L),
      Left((14000L, (7L, 18, "Hello World"))),
      Right(14000L),
      Left((15000L, (8L, 8, "Hello World"))),
      Right(17000L),
      Left((20000L, (20L, 20, "Hello World"))),
      Right(19000L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Long, Int, String)]],
        new EventTimeProcessOperator[(Long, Int, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)
    tEnv.createTemporarySystemFunction("LTCNT", classOf[LargerThanCount])

    val sqlQuery = "SELECT " +
      "  c, b, " +
      "  LTCNT(a, CAST('4' AS BIGINT)) OVER (PARTITION BY c ORDER BY rowtime RANGE " +
      "    BETWEEN INTERVAL '1' SECOND PRECEDING AND CURRENT ROW), " +
      "  COUNT(a) OVER (PARTITION BY c ORDER BY rowtime RANGE " +
      "    BETWEEN INTERVAL '1' SECOND PRECEDING AND CURRENT ROW), " +
      "  SUM(a) OVER (PARTITION BY c ORDER BY rowtime RANGE " +
      "    BETWEEN INTERVAL '1' SECOND PRECEDING AND CURRENT ROW)" +
      " FROM T1"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello,1,0,1,1",
      "Hello,15,0,2,2",
      "Hello,16,0,3,3",
      "Hello,2,0,6,9",
      "Hello,3,0,6,9",
      "Hello,2,0,6,9",
      "Hello,3,0,4,9",
      "Hello,4,0,2,7",
      "Hello,5,1,2,9",
      "Hello,6,2,2,11",
      "Hello,65,2,2,12",
      "Hello,9,2,2,12",
      "Hello,9,2,2,12",
      "Hello,18,3,3,18",
      "Hello World,17,3,3,21",
      "Hello World,7,1,1,7",
      "Hello World,77,3,3,21",
      "Hello World,18,1,1,7",
      "Hello World,8,2,2,15",
      "Hello World,20,1,1,20"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeBoundedPartitionedRowsOver(): Unit = {
    val data: Seq[Either[(Long, (Long, Int, String)), Long]] = Seq(
      Left((1L, (1L, 1, "Hello"))),
      Left((2L, (2L, 2, "Hello"))),
      Left((1L, (1L, 1, "Hello"))),
      Left((2L, (2L, 2, "Hello"))),
      Left((2L, (2L, 2, "Hello"))),
      Left((1L, (1L, 1, "Hello"))),
      Left((3L, (7L, 7, "Hello World"))),
      Left((1L, (7L, 7, "Hello World"))),
      Left((1L, (7L, 7, "Hello World"))),
      Right(2L),
      Left((3L, (3L, 3, "Hello"))),
      Left((4L, (4L, 4, "Hello"))),
      Left((5L, (5L, 5, "Hello"))),
      Left((6L, (6L, 6, "Hello"))),
      Left((20L, (20L, 20, "Hello World"))),
      Right(6L),
      Left((8L, (8L, 8, "Hello World"))),
      Left((7L, (7L, 7, "Hello World"))),
      Right(20L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Long, Int, String)]],
        new EventTimeProcessOperator[(Long, Int, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)
    tEnv.createTemporarySystemFunction("LTCNT", classOf[LargerThanCount])

    val sqlQuery = "SELECT " +
      " c, a, " +
      "  LTCNT(a, CAST('4' AS BIGINT)) " +
      "    OVER (PARTITION BY c ORDER BY rowtime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), " +
      "  COUNT(1) " +
      "    OVER (PARTITION BY c ORDER BY rowtime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW), " +
      "  SUM(a) " +
      "    OVER (PARTITION BY c ORDER BY rowtime ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) " +
      "FROM T1"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello,1,0,1,1",
      "Hello,1,0,2,2",
      "Hello,1,0,3,3",
      "Hello,2,0,3,4",
      "Hello,2,0,3,5",
      "Hello,2,0,3,6",
      "Hello,3,0,3,7",
      "Hello,4,0,3,9",
      "Hello,5,1,3,12",
      "Hello,6,2,3,15",
      "Hello World,7,1,1,7",
      "Hello World,7,2,2,14",
      "Hello World,7,3,3,21",
      "Hello World,7,3,3,21",
      "Hello World,8,3,3,22",
      "Hello World,20,3,3,35"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeBoundedNonPartitionedRangeOver(): Unit = {
    val data: Seq[Either[(Long, (Long, Int, String)), Long]] = Seq(
      Left((1500L, (1L, 15, "Hello"))),
      Left((1600L, (1L, 16, "Hello"))),
      Left((1000L, (1L, 1, "Hello"))),
      Left((2000L, (2L, 2, "Hello"))),
      Right(1000L),
      Left((2000L, (2L, 2, "Hello"))),
      Left((2000L, (2L, 3, "Hello"))),
      Left((3000L, (3L, 3, "Hello"))),
      Right(2000L),
      Left((4000L, (4L, 4, "Hello"))),
      Right(3000L),
      Left((5000L, (5L, 5, "Hello"))),
      Right(5000L),
      Left((6000L, (6L, 6, "Hello"))),
      Left((6500L, (6L, 65, "Hello"))),
      Right(7000L),
      Left((9000L, (6L, 9, "Hello"))),
      Left((9500L, (6L, 18, "Hello"))),
      Left((9000L, (6L, 9, "Hello"))),
      Right(10000L),
      Left((10000L, (7L, 7, "Hello World"))),
      Left((11000L, (7L, 17, "Hello World"))),
      Left((11000L, (7L, 77, "Hello World"))),
      Right(12000L),
      Left((14000L, (7L, 18, "Hello World"))),
      Right(14000L),
      Left((15000L, (8L, 8, "Hello World"))),
      Right(17000L),
      Left((20000L, (20L, 20, "Hello World"))),
      Right(19000L)
    )

    val source = failingDataSource(data)

    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Long, Int, String)]],
        new EventTimeProcessOperator[(Long, Int, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)

    val sqlQuery = "SELECT " +
      "  c, b, " +
      "  COUNT(a) " +
      "    OVER (ORDER BY rowtime RANGE BETWEEN INTERVAL '1' SECOND PRECEDING AND CURRENT ROW), " +
      "  SUM(a) " +
      "    OVER (ORDER BY rowtime RANGE BETWEEN INTERVAL '1' SECOND PRECEDING AND CURRENT ROW) " +
      " FROM T1"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello,1,1,1",
      "Hello,15,2,2",
      "Hello,16,3,3",
      "Hello,2,6,9",
      "Hello,3,6,9",
      "Hello,2,6,9",
      "Hello,3,4,9",
      "Hello,4,2,7",
      "Hello,5,2,9",
      "Hello,6,2,11",
      "Hello,65,2,12",
      "Hello,9,2,12",
      "Hello,9,2,12",
      "Hello,18,3,18",
      "Hello World,7,4,25",
      "Hello World,17,3,21",
      "Hello World,77,3,21",
      "Hello World,18,1,7",
      "Hello World,8,2,15",
      "Hello World,20,1,20"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeBoundedNonPartitionedRowsOver(): Unit = {
    val data: Seq[Either[(Long, (Long, Int, String)), Long]] = Seq(
      Left((2L, (2L, 2, "Hello"))),
      Left((2L, (2L, 2, "Hello"))),
      Left((1L, (1L, 1, "Hello"))),
      Left((1L, (1L, 1, "Hello"))),
      Left((2L, (2L, 2, "Hello"))),
      Left((1L, (1L, 1, "Hello"))),
      Left((20L, (20L, 20, "Hello World"))), // early row
      Right(3L),
      Left((2L, (2L, 2, "Hello"))), // late row
      Left((3L, (3L, 3, "Hello"))),
      Left((4L, (4L, 4, "Hello"))),
      Left((5L, (5L, 5, "Hello"))),
      Left((6L, (6L, 6, "Hello"))),
      Left((7L, (7L, 7, "Hello World"))),
      Right(7L),
      Left((9L, (9L, 9, "Hello World"))),
      Left((8L, (8L, 8, "Hello World"))),
      Left((8L, (8L, 8, "Hello World"))),
      Right(20L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Long, Int, String)]],
        new EventTimeProcessOperator[(Long, Int, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)

    val sqlQuery = "SELECT " +
      "c, a, " +
      "  COUNT(a) OVER (ORDER BY rowtime ROWS BETWEEN 2 preceding AND CURRENT ROW), " +
      "  SUM(a) OVER (ORDER BY rowtime ROWS BETWEEN 2 preceding AND CURRENT ROW) " +
      "FROM T1"

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      "Hello,1,1,1",
      "Hello,1,2,2",
      "Hello,1,3,3",
      "Hello,2,3,4",
      "Hello,2,3,5",
      "Hello,2,3,6",
      "Hello,3,3,7",
      "Hello,4,3,9",
      "Hello,5,3,12",
      "Hello,6,3,15",
      "Hello World,7,3,18",
      "Hello World,8,3,21",
      "Hello World,8,3,23",
      "Hello World,9,3,25",
      "Hello World,20,3,37"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeUnBoundedPartitionedRangeOver(): Unit = {
    val sqlQuery = "SELECT a, b, c, " +
      "  LTCNT(b, CAST('4' AS BIGINT)) OVER(" +
      "    PARTITION BY a ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  SUM(b) OVER (" +
      "    PARTITION BY a ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  COUNT(b) OVER (" +
      "    PARTITION BY a ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  AVG(b) OVER (" +
      "    PARTITION BY a ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  MAX(b) OVER (" +
      "    PARTITION BY a ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  MIN(b) OVER (" +
      "    PARTITION BY a ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) " +
      "FROM T1"

    val data: Seq[Either[(Long, (Int, Long, String)), Long]] = Seq(
      Left(14000005L, (1, 1L, "Hi")),
      Left(14000000L, (2, 1L, "Hello")),
      Left(14000002L, (1, 1L, "Hello")),
      Left(14000002L, (1, 2L, "Hello")),
      Left(14000002L, (1, 3L, "Hello world")),
      Left(14000003L, (2, 2L, "Hello world")),
      Left(14000003L, (2, 3L, "Hello world")),
      Right(14000020L),
      Left(14000021L, (1, 4L, "Hello world")),
      Left(14000022L, (1, 5L, "Hello world")),
      Left(14000022L, (1, 6L, "Hello world")),
      Left(14000022L, (1, 7L, "Hello world")),
      Left(14000023L, (2, 4L, "Hello world")),
      Left(14000023L, (2, 5L, "Hello world")),
      Right(14000030L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Int, Long, String)]],
        new EventTimeProcessOperator[(Int, Long, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)
    tEnv.createTemporarySystemFunction("LTCNT", new LargerThanCount)

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      s"1,1,Hello,0,6,3,${6 / 3},3,1",
      s"1,2,Hello,0,6,3,${6 / 3},3,1",
      s"1,3,Hello world,0,6,3,${6 / 3},3,1",
      s"1,1,Hi,0,7,4,${7 / 4},3,1",
      s"2,1,Hello,0,1,1,${1 / 1},1,1",
      s"2,2,Hello world,0,6,3,${6 / 3},3,1",
      s"2,3,Hello world,0,6,3,${6 / 3},3,1",
      s"1,4,Hello world,0,11,5,${11 / 5},4,1",
      s"1,5,Hello world,3,29,8,${29 / 8},7,1",
      s"1,6,Hello world,3,29,8,${29 / 8},7,1",
      s"1,7,Hello world,3,29,8,${29 / 8},7,1",
      s"2,4,Hello world,1,15,5,${15 / 5},5,1",
      s"2,5,Hello world,1,15,5,${15 / 5},5,1"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeUnBoundedPartitionedRowsOver(): Unit = {
    val sqlQuery = "SELECT a, b, c, " +
      "LTCNT(b, CAST('4' AS BIGINT)) over(" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "SUM(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "count(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "avg(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "max(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "min(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row) " +
      "from T1"

    val data: Seq[Either[(Long, (Int, Long, String)), Long]] = Seq(
      Left(14000005L, (1, 1L, "Hi")),
      Left(14000000L, (2, 1L, "Hello")),
      Left(14000002L, (3, 1L, "Hello")),
      Left(14000003L, (1, 2L, "Hello")),
      Left(14000004L, (1, 3L, "Hello world")),
      Left(14000007L, (3, 2L, "Hello world")),
      Left(14000008L, (2, 2L, "Hello world")),
      Right(14000010L),
      Left(14000012L, (1, 5L, "Hello world")),
      Left(14000021L, (1, 6L, "Hello world")),
      Left(14000023L, (2, 5L, "Hello world")),
      Right(14000020L),
      Left(14000024L, (3, 5L, "Hello world")),
      Left(14000026L, (1, 7L, "Hello world")),
      Left(14000025L, (1, 8L, "Hello world")),
      Left(14000022L, (1, 9L, "Hello world")),
      Right(14000030L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Int, Long, String)]],
        new EventTimeProcessOperator[(Int, Long, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)
    tEnv.createTemporarySystemFunction("LTCNT", classOf[LargerThanCount])

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = mutable.MutableList(
      s"1,2,Hello,0,2,1,${2 / 1},2,2",
      s"1,3,Hello world,0,5,2,${5 / 2},3,2",
      s"1,1,Hi,0,6,3,${6 / 3},3,1",
      s"2,1,Hello,0,1,1,${1 / 1},1,1",
      s"2,2,Hello world,0,3,2,${3 / 2},2,1",
      s"3,1,Hello,0,1,1,${1 / 1},1,1",
      s"3,2,Hello world,0,3,2,${3 / 2},2,1",
      s"1,5,Hello world,1,11,4,${11 / 4},5,1",
      s"1,6,Hello world,2,17,5,${17 / 5},6,1",
      s"1,9,Hello world,3,26,6,${26 / 6},9,1",
      s"1,8,Hello world,4,34,7,${34 / 7},9,1",
      s"1,7,Hello world,5,41,8,${41 / 8},9,1",
      s"2,5,Hello world,1,8,3,${8 / 3},5,1",
      s"3,5,Hello world,1,8,3,${8 / 3},5,1"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeUnBoundedNonPartitionedRangeOver(): Unit = {
    val sqlQuery = "SELECT a, b, c, " +
      "  SUM(b) OVER (ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  COUNT(b) OVER (ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  AVG(b) OVER (ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  MAX(b) OVER (ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  MIN(b) OVER (ORDER BY rowtime RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) " +
      "FROM T1"

    val data: Seq[Either[(Long, (Int, Long, String)), Long]] = Seq(
      Left(14000005L, (1, 1L, "Hi")),
      Left(14000000L, (2, 1L, "Hello")),
      Left(14000002L, (1, 1L, "Hello")),
      Left(14000002L, (1, 2L, "Hello")),
      Left(14000002L, (1, 3L, "Hello world")),
      Left(14000003L, (2, 2L, "Hello world")),
      Left(14000003L, (2, 3L, "Hello world")),
      Right(14000020L),
      Left(14000021L, (1, 4L, "Hello world")),
      Left(14000022L, (1, 5L, "Hello world")),
      Left(14000022L, (1, 6L, "Hello world")),
      Left(14000022L, (1, 7L, "Hello world")),
      Left(14000023L, (2, 4L, "Hello world")),
      Left(14000023L, (2, 5L, "Hello world")),
      Right(14000030L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Int, Long, String)]],
        new EventTimeProcessOperator[(Int, Long, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink).setParallelism(1)
    env.execute()

    val expected = List(
      s"2,1,Hello,1,1,${1 / 1},1,1",
      s"1,1,Hello,7,4,${7 / 4},3,1",
      s"1,2,Hello,7,4,${7 / 4},3,1",
      s"1,3,Hello world,7,4,${7 / 4},3,1",
      s"2,2,Hello world,12,6,${12 / 6},3,1",
      s"2,3,Hello world,12,6,${12 / 6},3,1",
      s"1,1,Hi,13,7,${13 / 7},3,1",
      s"1,4,Hello world,17,8,${17 / 8},4,1",
      s"1,5,Hello world,35,11,${35 / 11},7,1",
      s"1,6,Hello world,35,11,${35 / 11},7,1",
      s"1,7,Hello world,35,11,${35 / 11},7,1",
      s"2,4,Hello world,44,13,${44 / 13},7,1",
      s"2,5,Hello world,44,13,${44 / 13},7,1"
    )

    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testRowTimeUnBoundedNonPartitionedRowsOver(): Unit = {
    val sqlQuery = "SELECT a, b, c, " +
      "  SUM(b) OVER (ORDER BY rowtime ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  COUNT(b) OVER (ORDER BY rowtime ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  AVG(b) OVER (ORDER BY rowtime ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  MAX(b) OVER (ORDER BY rowtime ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW), " +
      "  MIN(b) OVER (ORDER BY rowtime ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) " +
      "FROM T1"

    val data: Seq[Either[(Long, (Int, Long, String)), Long]] = Seq(
      Left(14000005L, (1, 1L, "Hi")),
      Left(14000000L, (2, 2L, "Hello")),
      Left(14000002L, (3, 5L, "Hello")),
      Left(14000003L, (1, 3L, "Hello")),
      Left(14000004L, (3, 7L, "Hello world")),
      Left(14000007L, (4, 9L, "Hello world")),
      Left(14000008L, (5, 8L, "Hello world")),
      Right(14000010L),
      // this element will be discard because it is late
      Left(14000008L, (6, 8L, "Hello world")),
      Right(14000020L),
      Left(14000021L, (6, 8L, "Hello world")),
      Right(14000030L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Int, Long, String)]],
        new EventTimeProcessOperator[(Int, Long, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink).setParallelism(1)
    env.execute()

    val expected = mutable.MutableList(
      s"2,2,Hello,2,1,${2 / 1},2,2",
      s"3,5,Hello,7,2,${7 / 2},5,2",
      s"1,3,Hello,10,3,${10 / 3},5,2",
      s"3,7,Hello world,17,4,${17 / 4},7,2",
      s"1,1,Hi,18,5,${18 / 5},7,1",
      s"4,9,Hello world,27,6,${27 / 6},9,1",
      s"5,8,Hello world,35,7,${35 / 7},9,1",
      s"6,8,Hello world,43,8,${43 / 8},9,1"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  /** test sliding event-time unbounded window with partition by * */
  @TestTemplate
  def testRowTimeUnBoundedPartitionedRowsOver2(): Unit = {
    val sqlQuery = "SELECT a, b, c, " +
      "SUM(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "count(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "avg(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "max(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row), " +
      "min(b) over (" +
      "partition by a order by rowtime rows between unbounded preceding and current row) " +
      "from T1"

    val data: Seq[Either[(Long, (Int, Long, String)), Long]] = Seq(
      Left(14000005L, (1, 1L, "Hi")),
      Left(14000000L, (2, 1L, "Hello")),
      Left(14000002L, (3, 1L, "Hello")),
      Left(14000003L, (1, 2L, "Hello")),
      Left(14000004L, (1, 3L, "Hello world")),
      Left(14000007L, (3, 2L, "Hello world")),
      Left(14000008L, (2, 2L, "Hello world")),
      Right(14000010L),
      // the next 3 elements are late
      Left(14000008L, (1, 4L, "Hello world")),
      Left(14000008L, (2, 3L, "Hello world")),
      Left(14000008L, (3, 3L, "Hello world")),
      Left(14000012L, (1, 5L, "Hello world")),
      Right(14000020L),
      Left(14000021L, (1, 6L, "Hello world")),
      // the next 3 elements are late
      Left(14000019L, (1, 6L, "Hello world")),
      Left(14000018L, (2, 4L, "Hello world")),
      Left(14000018L, (3, 4L, "Hello world")),
      Left(14000022L, (2, 5L, "Hello world")),
      Left(14000022L, (3, 5L, "Hello world")),
      Left(14000024L, (1, 7L, "Hello world")),
      Left(14000023L, (1, 8L, "Hello world")),
      Left(14000021L, (1, 9L, "Hello world")),
      Right(14000030L)
    )

    val source = failingDataSource(data)
    val t1 = source
      .transform(
        "TimeAssigner",
        implicitly[TypeInformation[(Int, Long, String)]],
        new EventTimeProcessOperator[(Int, Long, String)])
      .setParallelism(source.getParallelism)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.createTemporaryView("T1", t1)

    val sink = new TestingAppendSink
    tEnv.sqlQuery(sqlQuery).toDataStream.addSink(sink)
    env.execute()

    val expected = List(
      s"1,2,Hello,2,1,${2 / 1},2,2",
      s"1,3,Hello world,5,2,${5 / 2},3,2",
      s"1,1,Hi,6,3,${6 / 3},3,1",
      s"2,1,Hello,1,1,${1 / 1},1,1",
      s"2,2,Hello world,3,2,${3 / 2},2,1",
      s"3,1,Hello,1,1,${1 / 1},1,1",
      s"3,2,Hello world,3,2,${3 / 2},2,1",
      s"1,5,Hello world,11,4,${11 / 4},5,1",
      s"1,6,Hello world,17,5,${17 / 5},6,1",
      s"1,9,Hello world,26,6,${26 / 6},9,1",
      s"1,8,Hello world,34,7,${34 / 7},9,1",
      s"1,7,Hello world,41,8,${41 / 8},9,1",
      s"2,5,Hello world,8,3,${8 / 3},5,1",
      s"3,5,Hello world,8,3,${8 / 3},5,1"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeDistinctUnboundedPartitionedRowsOver(): Unit = {
    val t = failingDataSource(TestData.tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery = "SELECT a, " +
      "  COUNT(e) OVER (" +
      "    PARTITION BY a ORDER BY proctime RANGE UNBOUNDED preceding), " +
      "  SUM(DISTINCT e) OVER (" +
      "    PARTITION BY a ORDER BY proctime RANGE UNBOUNDED preceding), " +
      "  MIN(DISTINCT e) OVER (" +
      "    PARTITION BY a ORDER BY proctime RANGE UNBOUNDED preceding) " +
      "FROM MyTable"

    val result = tEnv.sqlQuery(sqlQuery).toDataStream
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = List(
      "1,1,1,1",
      "2,1,2,2",
      "2,2,3,1",
      "3,1,2,2",
      "3,2,2,2",
      "3,3,5,2",
      "4,1,2,2",
      "4,2,3,1",
      "4,3,3,1",
      "4,4,3,1",
      "5,1,1,1",
      "5,2,4,1",
      "5,3,4,1",
      "5,4,6,1",
      "5,5,6,1")
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testTimestampRowTimeDistinctUnboundedPartitionedRangeOverWithNullValues(): Unit = {
    val rows = Seq(
      row(LocalDateTime.parse("1970-01-01T00:00:01"), 1, null),
      row(LocalDateTime.parse("1970-01-01T00:00:02"), 1, null),
      row(LocalDateTime.parse("1970-01-01T00:00:03"), 2, null),
      row(LocalDateTime.parse("1970-01-01T00:00:04"), 1, "Hello"),
      row(LocalDateTime.parse("1970-01-01T00:00:05"), 1, "Hello"),
      row(LocalDateTime.parse("1970-01-01T00:00:06"), 2, "Hello"),
      row(LocalDateTime.parse("1970-01-01T00:00:07"), 1, "Hello World"),
      row(LocalDateTime.parse("1970-01-01T00:00:08"), 2, "Hello World"),
      row(LocalDateTime.parse("1970-01-01T00:00:09"), 2, "Hello World"),
      row(LocalDateTime.parse("1970-01-01T00:00:10"), 1, null)
    )

    val tableId = TestValuesTableFactory.registerData(rows)

    // for sum aggregation ensure that every time the order of each element is consistent
    env.setParallelism(1)
    tEnv.executeSql(s"""
                       |CREATE TABLE MyTable (
                       |  rowtime TIMESTAMP(3),
                       |  b INT,
                       |  c STRING,
                       |  WATERMARK FOR rowtime AS rowtime
                       |) WITH (
                       |  'connector' = 'values',
                       |  'data-id' = '$tableId',
                       |  'bounded' = 'true'
                       |)
                       |""".stripMargin)

    tEnv.createTemporaryFunction("CntNullNonNull", new CountNullNonNull)

    val sqlQuery = "SELECT " +
      "  c, " +
      "  b, " +
      "  COUNT(DISTINCT c) " +
      "    OVER (PARTITION BY b ORDER BY rowtime RANGE UNBOUNDED preceding), " +
      "  CntNullNonNull(DISTINCT c) " +
      "    OVER (PARTITION BY b ORDER BY rowtime RANGE UNBOUNDED preceding)" +
      "FROM " +
      "  MyTable"

    val result = tEnv.sqlQuery(sqlQuery).toDataStream
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = List(
      "null,1,0,0|1",
      "null,1,0,0|1",
      "null,2,0,0|1",
      "null,1,2,2|1",
      "Hello,1,1,1|1",
      "Hello,1,1,1|1",
      "Hello,2,1,1|1",
      "Hello World,1,2,2|1",
      "Hello World,2,2,2|1",
      "Hello World,2,2,2|1"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testTimestampLtzRowTimeDistinctUnboundedPartitionedRangeOverWithNullValues(): Unit = {
    val rows = Seq(
      row(Instant.ofEpochSecond(1L), 1, null),
      row(Instant.ofEpochSecond(2L), 1, null),
      row(Instant.ofEpochSecond(3L), 2, null),
      row(Instant.ofEpochSecond(4L), 1, "Hello"),
      row(Instant.ofEpochSecond(5L), 1, "Hello"),
      row(Instant.ofEpochSecond(6L), 2, "Hello"),
      row(Instant.ofEpochSecond(7L), 1, "Hello World"),
      row(Instant.ofEpochSecond(8L), 2, "Hello World"),
      row(Instant.ofEpochSecond(9L), 2, "Hello World"),
      row(Instant.ofEpochSecond(10L), 1, null)
    )

    val tableId = TestValuesTableFactory.registerData(rows)

    // for sum aggregation ensure that every time the order of each element is consistent
    env.setParallelism(1)
    tEnv.executeSql(s"""
                       |CREATE TABLE MyTable (
                       |  rowtime TIMESTAMP_LTZ(3),
                       |  b INT,
                       |  c STRING,
                       |  WATERMARK FOR rowtime AS rowtime
                       |) WITH (
                       |  'connector' = 'values',
                       |  'data-id' = '$tableId',
                       |  'bounded' = 'true'
                       |)
                       |""".stripMargin)

    tEnv.createTemporaryFunction("CntNullNonNull", new CountNullNonNull)

    val sqlQuery = "SELECT " +
      "  c, " +
      "  b, " +
      "  COUNT(DISTINCT c) " +
      "    OVER (PARTITION BY b ORDER BY rowtime RANGE UNBOUNDED preceding), " +
      "  CntNullNonNull(DISTINCT c) " +
      "    OVER (PARTITION BY b ORDER BY rowtime RANGE UNBOUNDED preceding)" +
      "FROM " +
      "  MyTable"

    val result = tEnv.sqlQuery(sqlQuery).toDataStream
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = List(
      "null,1,0,0|1",
      "null,1,0,0|1",
      "null,2,0,0|1",
      "null,1,2,2|1",
      "Hello,1,1,1|1",
      "Hello,1,1,1|1",
      "Hello,2,1,1|1",
      "Hello World,1,2,2|1",
      "Hello World,2,2,2|1",
      "Hello World,2,2,2|1"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeDistinctBoundedPartitionedRowsOver(): Unit = {
    val t = failingDataSource(TestData.tupleData5)
      .toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)

    val sqlQuery = "SELECT a, " +
      "  SUM(DISTINCT e) OVER (" +
      "    PARTITION BY a ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW), " +
      "  MIN(DISTINCT e) OVER (" +
      "    PARTITION BY a ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW), " +
      "  COLLECT(DISTINCT e) OVER (" +
      "    PARTITION BY a ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) " +
      "FROM MyTable"

    val result = tEnv.sqlQuery(sqlQuery).toDataStream
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = List(
      "1,1,1,{1=1}",
      "2,2,2,{2=1}",
      "2,3,1,{1=1, 2=1}",
      "3,2,2,{2=1}",
      "3,2,2,{2=1}",
      "3,5,2,{2=1, 3=1}",
      "4,2,2,{2=1}",
      "4,3,1,{1=1, 2=1}",
      "4,3,1,{1=1, 2=1}",
      "4,3,1,{1=1, 2=1}",
      "5,1,1,{1=1}",
      "5,4,1,{1=1, 3=1}",
      "5,4,1,{1=1, 3=1}",
      "5,6,1,{1=1, 2=1, 3=1}",
      "5,5,2,{2=1, 3=1}"
    )
    assertThat(sink.getAppendResults.sorted).isEqualTo(expected.sorted)
  }

  @TestTemplate
  def testProcTimeDistinctPairWithNulls(): Unit = {

    val data = List(
      ("A", null),
      ("A", null),
      ("B", null),
      (null, "Hello"),
      ("A", "Hello"),
      ("A", "Hello"),
      (null, "Hello World"),
      (null, "Hello World"),
      ("A", "Hello World"),
      ("B", "Hello World")
    )

    env.setParallelism(1)

    val table = StreamingEnvUtil.fromCollection(env, data).toTable(tEnv, 'a, 'b, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", table)
    tEnv.createTemporarySystemFunction("PairCount", classOf[CountPairs])

    val sqlQuery = "SELECT a, b, " +
      "  PairCount(a, b) OVER (ORDER BY proctime RANGE UNBOUNDED preceding), " +
      "  PairCount(DISTINCT a, b) OVER (ORDER BY proctime RANGE UNBOUNDED preceding) " +
      "FROM MyTable"

    val result = tEnv.sqlQuery(sqlQuery).toDataStream
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = List(
      "A,null,1,1",
      "A,null,2,1",
      "B,null,3,2",
      "null,Hello,4,3",
      "A,Hello,5,4",
      "A,Hello,6,4",
      "null,Hello World,7,5",
      "null,Hello World,8,5",
      "A,Hello World,9,6",
      "B,Hello World,10,7"
    )
    assertThat(sink.getAppendResults).isEqualTo(expected)
  }

  @TestTemplate
  def testDecimalSum0(): Unit = {
    val data = new mutable.MutableList[Row]
    data.+=(Row.of(BigDecimal(1.11).bigDecimal))
    data.+=(Row.of(BigDecimal(2.22).bigDecimal))
    data.+=(Row.of(BigDecimal(3.33).bigDecimal))
    data.+=(Row.of(BigDecimal(4.44).bigDecimal))

    env.setParallelism(1)
    val rowType = new RowTypeInfo(BigDecimalTypeInfo.of(38, 18))
    val t = failingDataSource(data)(rowType).toTable(tEnv, 'd, 'proctime.proctime)
    tEnv.createTemporaryView("T", t)

    val sqlQuery = "select sum(d) over (ORDER BY proctime rows between unbounded preceding " +
      "and current row) from T"

    val result = tEnv.sqlQuery(sqlQuery).toDataStream
    val sink = new TestingAppendSink
    result.addSink(sink)
    env.execute()

    val expected = List(
      "1.110000000000000000",
      "3.330000000000000000",
      "6.660000000000000000",
      "11.100000000000000000")
    assertThat(sink.getAppendResults).isEqualTo(expected)
  }

  @TestTemplate
  def testPercentile(): Unit = {
    val sql =
      """
        |SELECT
        |  PERCENTILE(b, 0.5)
        |  OVER (ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) AS `swo`,
        |  PERCENTILE(b, 0.5, a) 
        |  OVER (ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) AS `sw`,
        |  PERCENTILE(b, ARRAY[0.5, 0.9, 0.3])
        |  OVER (ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) AS `mwo`,
        |  PERCENTILE(b, ARRAY[0.5, 0.9, 0.3], a)
        |  OVER (ORDER BY proctime ROWS BETWEEN 3 PRECEDING AND CURRENT ROW) AS `mw`
        |FROM MyTable
      """.stripMargin
    val outer =
      s"""
         |SELECT
         | `swo`,
         | `sw`,
         | `mwo`[1], `mwo`[2], `mwo`[3],
         | `mw`[1], `mw`[2], `mw`[3]
         |FROM ($sql)
    """.stripMargin

    val t =
      failingDataSource(TestData.tupleData5).toTable(tEnv, 'a, 'b, 'c, 'd, 'e, 'proctime.proctime)
    tEnv.createTemporaryView("MyTable", t)

    val sink = new TestingAppendSink()
    tEnv.sqlQuery(outer).toDataStream.addSink(sink).setParallelism(1)
    env.execute()

    val expected = List(
      List(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
      List(1.5, 2.0, 1.5, 1.9, 1.3, 2.0, 2.0, 1.6),
      List(2.0, 2.0, 2.0, 2.8, 1.6, 2.0, 3.0, 2.0),
      List(2.5, 3.0, 2.5, 3.7, 1.9, 3.0, 4.0, 2.1),
      List(3.5, 4.0, 3.5, 4.7, 2.9, 4.0, 5.0, 3.0),
      List(4.5, 5.0, 4.5, 5.7, 3.9, 5.0, 6.0, 4.0),
      List(5.5, 6.0, 5.5, 6.7, 4.9, 6.0, 7.0, 5.0),
      List(6.5, 7.0, 6.5, 7.7, 5.9, 7.0, 8.0, 6.0),
      List(7.5, 8.0, 7.5, 8.7, 6.9, 8.0, 9.0, 7.0),
      List(8.5, 8.5, 8.5, 9.7, 7.9, 8.5, 10.0, 8.0),
      List(9.5, 10.0, 9.5, 10.7, 8.9, 10.0, 11.0, 9.0),
      List(10.5, 11.0, 10.5, 11.7, 9.9, 11.0, 12.0, 10.0),
      List(11.5, 12.0, 11.5, 12.7, 10.9, 12.0, 13.0, 11.0),
      List(12.5, 12.5, 12.5, 13.7, 11.9, 12.5, 14.0, 12.0),
      List(13.5, 13.5, 13.5, 14.7, 12.9, 13.5, 15.0, 13.0)
    )
    val ERROR_RATE = Percentage.withPercentage(1e-6)

    val result = sink.getAppendResults
    for (i <- result.indices) {
      val actual = result(i).split(",")
      for (j <- expected(i).indices) {
        assertThat(actual(j).toDouble).isCloseTo(expected(i)(j), ERROR_RATE)
      }
    }
  }
}

object OverAggregateITCase {
  @Parameters(name = "StateBackend={0}, unboundedOverVersion2={1}")
  def parameters(): util.Collection[Array[Any]] = {
    scala.collection.JavaConverters.seqAsJavaList(
      Seq[Array[Any]](
        Array(HEAP_BACKEND, 1),
        Array(HEAP_BACKEND, 2),
        Array(ROCKSDB_BACKEND, 1),
        Array(ROCKSDB_BACKEND, 2)
      ))
  }
}

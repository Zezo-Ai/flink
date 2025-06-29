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
package org.apache.flink.table.planner.utils

import org.apache.flink.FlinkVersion
import org.apache.flink.api.common.serialization.SerializerConfigImpl
import org.apache.flink.api.common.typeinfo.{AtomicType, BasicArrayTypeInfo, BasicTypeInfo, TypeInformation}
import org.apache.flink.api.common.typeutils.TypeSerializer
import org.apache.flink.api.dag.Transformation
import org.apache.flink.api.java.typeutils.{PojoTypeInfo, RowTypeInfo, TupleTypeInfo}
import org.apache.flink.configuration.{BatchExecutionOptions, ConfigOption, ConfigOptions}
import org.apache.flink.legacy.table.factories.StreamTableSourceFactory
import org.apache.flink.legacy.table.sources.StreamTableSource
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonParseException
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.streaming.api.environment
import org.apache.flink.streaming.api.environment.{LocalStreamEnvironment, StreamExecutionEnvironment}
import org.apache.flink.streaming.api.legacy.io.CollectionInputFormat
import org.apache.flink.table.api._
import org.apache.flink.table.api.bridge.java.{StreamTableEnvironment => JavaStreamTableEnv}
import org.apache.flink.table.api.bridge.scala.{StreamTableEnvironment => ScalaStreamTableEnv}
import org.apache.flink.table.api.config.{ExecutionConfigOptions, OptimizerConfigOptions}
import org.apache.flink.table.api.config.OptimizerConfigOptions.AdaptiveBroadcastJoinStrategy.NONE
import org.apache.flink.table.api.internal.{StatementSetImpl, TableEnvironmentImpl, TableEnvironmentInternal, TableImpl}
import org.apache.flink.table.api.typeutils.CaseClassTypeInfo
import org.apache.flink.table.catalog._
import org.apache.flink.table.connector.ChangelogMode
import org.apache.flink.table.connector.source.{DynamicTableSource, InputFormatProvider, ScanTableSource}
import org.apache.flink.table.data.{DecimalDataUtils, RowData}
import org.apache.flink.table.delegation.{Executor, ExecutorFactory}
import org.apache.flink.table.descriptors.ConnectorDescriptorValidator.CONNECTOR_TYPE
import org.apache.flink.table.descriptors.DescriptorProperties
import org.apache.flink.table.expressions.Expression
import org.apache.flink.table.factories._
import org.apache.flink.table.functions._
import org.apache.flink.table.legacy.api.TableSchema
import org.apache.flink.table.legacy.descriptors.Schema.SCHEMA
import org.apache.flink.table.legacy.sources.TableSource
import org.apache.flink.table.module.ModuleManager
import org.apache.flink.table.operations.{ModifyOperation, QueryOperation}
import org.apache.flink.table.planner.calcite.CalciteConfig
import org.apache.flink.table.planner.delegation.PlannerBase
import org.apache.flink.table.planner.factories.TestValuesTableFactory
import org.apache.flink.table.planner.functions.sql.FlinkSqlOperatorTable
import org.apache.flink.table.planner.operations.{InternalDataStreamQueryOperation, PlannerQueryOperation, RichTableSourceQueryOperation}
import org.apache.flink.table.planner.plan.nodes.calcite.LogicalWatermarkAssigner
import org.apache.flink.table.planner.plan.nodes.exec.{ExecNodeContext, ExecNodeGraph, ExecNodeGraphGenerator}
import org.apache.flink.table.planner.plan.nodes.exec.utils.ExecNodePlanDumper
import org.apache.flink.table.planner.plan.nodes.physical.FlinkPhysicalRel
import org.apache.flink.table.planner.plan.optimize.program._
import org.apache.flink.table.planner.plan.stats.FlinkStatistic
import org.apache.flink.table.planner.plan.utils.FlinkRelOptUtil
import org.apache.flink.table.planner.utils.PlanKind.PlanKind
import org.apache.flink.table.planner.utils.TableTestUtil.{replaceNodeIdInOperator, replaceStageId, replaceStreamNodeId}
import org.apache.flink.table.planner.utils.TestSimpleDynamicTableSourceFactory.{BOUNDED, IDENTIFIER}
import org.apache.flink.table.resource.ResourceManager
import org.apache.flink.table.types._
import org.apache.flink.table.types.logical.{LegacyTypeInformationType, LogicalTypeRoot}
import org.apache.flink.table.types.utils.{LegacyTypeInfoDataTypeConverter, TypeConversions}
import org.apache.flink.table.types.utils.TypeConversions.fromLegacyInfoToDataType
import org.apache.flink.table.typeutils.{FieldInfoUtils, TimeIndicatorTypeInfo}
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension
import org.apache.flink.types.Row
import org.apache.flink.util.{FlinkUserCodeClassLoaders, MutableURLClassLoader}
import org.apache.flink.util.jackson.JacksonMapperFactory

import _root_.java.math.{BigDecimal => JBigDecimal}
import _root_.java.util
import _root_.scala.collection.JavaConversions._
import _root_.scala.io.Source
import org.apache.calcite.avatica.util.TimeUnit
import org.apache.calcite.rel.RelNode
import org.apache.calcite.sql.{SqlExplainLevel, SqlIntervalQualifier}
import org.apache.calcite.sql.parser.SqlParserPos
import org.assertj.core.api.Assertions.{assertThat, assertThatExceptionOfType, fail}
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.{BeforeEachCallback, ExtendWith, ExtensionContext, RegisterExtension}
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.platform.commons.support.AnnotationSupport

import java.io.{File, IOException}
import java.net.URL
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import java.util.Collections

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/** Test base for testing Table API / SQL plans. */
abstract class TableTestBase {

  // used for get test case method name
  @RegisterExtension
  val testName: TestName = new TestName

  @TempDir
  var tempFolder: Path = _

  def methodName: String = testName.getMethodName

  def streamTestUtil(tableConfig: TableConfig = TableConfig.getDefault): StreamTableTestUtil =
    StreamTableTestUtil(this, tableConfig = tableConfig)

  def scalaStreamTestUtil(): ScalaStreamTableTestUtil = ScalaStreamTableTestUtil(this)

  def javaStreamTestUtil(): JavaStreamTableTestUtil = JavaStreamTableTestUtil(this)

  def batchTestUtil(tableConfig: TableConfig = TableConfig.getDefault): BatchTableTestUtil =
    BatchTableTestUtil(this, tableConfig = tableConfig)

  def scalaBatchTestUtil(): ScalaBatchTableTestUtil = ScalaBatchTableTestUtil(this)

  def javaBatchTestUtil(): JavaBatchTableTestUtil = JavaBatchTableTestUtil(this)

  def verifyTableEquals(expected: Table, actual: Table): Unit = {
    val expectedString = FlinkRelOptUtil.toString(TableTestUtil.toRelNode(expected))
    val actualString = FlinkRelOptUtil.toString(TableTestUtil.toRelNode(actual))
    assertEquals(
      LogicalPlanFormatUtils.formatTempTableId(expectedString),
      LogicalPlanFormatUtils.formatTempTableId(actualString),
      "Logical plans do not match")
  }
}

class TestName extends BeforeEachCallback {

  private val bracketsRegex = """\[.*\]""".r

  private var methodName: String = _

  def getMethodName: String = methodName

  override def beforeEach(context: ExtensionContext): Unit = {
    if (hasParameterizedTestExtension(context)) {
      val displayName = context.getDisplayName match {
        case bracketsRegex(_*) => context.getDisplayName
        case _ => s"[${context.getDisplayName}]"
      }
      methodName = s"${context.getTestMethod.get().getName}$displayName"
    } else {
      if (
        AnnotationSupport.isAnnotated(context.getTestMethod, classOf[ParameterizedTest])
        || AnnotationSupport.isAnnotated(context.getTestMethod, classOf[TestTemplate])
      ) {
        methodName = s"${context.getTestMethod.get().getName}[${context.getDisplayName}]"
      } else {
        methodName = context.getTestMethod.get().getName
      }
    }
  }

  private def hasParameterizedTestExtension(context: ExtensionContext): Boolean = {
    Option(AnnotationSupport.findAnnotation(context.getTestClass, classOf[ExtendWith]).orElse(null))
      .map(_.value)
      .exists(_.contains(classOf[ParameterizedTestExtension]))
  }
}

abstract class TableTestUtilBase(test: TableTestBase, isStreamingMode: Boolean) {
  protected lazy val diffRepository: DiffRepository = DiffRepository.lookup(test.getClass)

  protected val setting: EnvironmentSettings = if (isStreamingMode) {
    EnvironmentSettings.newInstance().inStreamingMode().build()
  } else {
    EnvironmentSettings.newInstance().inBatchMode().build()
  }

  // a counter for unique table names
  private var counter = 0L

  private def getNextId: Long = {
    counter += 1
    counter
  }

  protected def getTableEnv: TableEnvironment

  protected def isBounded: Boolean = !isStreamingMode

  def getPlanner: PlannerBase = {
    getTableEnv.asInstanceOf[TableEnvironmentImpl].getPlanner.asInstanceOf[PlannerBase]
  }

  /** Creates a table with the given DDL SQL string. */
  def addTable(ddl: String): Unit = {
    getTableEnv.executeSql(ddl)
  }

  /**
   * Create a [[DataStream]] with the given schema, and registers this DataStream under given name
   * into the TableEnvironment's catalog.
   *
   * @param name
   *   table name
   * @param fields
   *   field names
   * @tparam T
   *   field types
   * @return
   *   returns the registered [[Table]].
   */
  def addDataStream[T: TypeInformation](name: String, fields: Expression*): Table = {
    val env = new LocalStreamEnvironment()
    val typeInfo = implicitly[TypeInformation[T]]
    val dataStream = env.fromCollection[T](Collections.emptyList[T](), typeInfo)
    val tableEnv = getTableEnv
    TableTestUtil.createTemporaryView(tableEnv, name, dataStream, Some(fields.toArray))
    tableEnv.from(name)
  }

  /**
   * Create a [[TestTableSource]] with the given schema, and registers this TableSource under a
   * unique name into the TableEnvironment's catalog.
   *
   * @param fields
   *   field names
   * @tparam T
   *   field types
   * @return
   *   returns the registered [[Table]].
   */
  def addTableSource[T: TypeInformation](fields: Expression*): Table = {
    addTableSource[T](s"Table$getNextId", fields: _*)
  }

  /**
   * Create a [[TestSimpleDynamicTableSource]] with the given schema, and registers this TableSource
   * under given name into the TableEnvironment's catalog.
   *
   * @param name
   *   table name
   * @param fields
   *   field names
   * @tparam T
   *   field types
   * @return
   *   returns the registered [[Table]].
   */
  def addTableSource[T: TypeInformation](name: String, fields: Expression*): Table = {
    val typeInfo: TypeInformation[T] = implicitly[TypeInformation[T]]

    val tableSchema = if (fields.isEmpty) {
      val fieldTypes: Array[TypeInformation[_]] = typeInfo match {
        case tt: TupleTypeInfo[_] => (0 until tt.getArity).map(tt.getTypeAt).toArray
        case ct: CaseClassTypeInfo[_] => (0 until ct.getArity).map(ct.getTypeAt).toArray
        case at: AtomicType[_] => Array[TypeInformation[_]](at)
        case pojo: PojoTypeInfo[_] => (0 until pojo.getArity).map(pojo.getTypeAt).toArray
        case _ => throw new TableException(s"Unsupported type info: $typeInfo")
      }
      val types = fieldTypes.map(TypeConversions.fromLegacyInfoToDataType)
      val names = FieldInfoUtils.getFieldNames(typeInfo)
      TableSchema.builder().fields(names, types).build().toSchema
    } else {
      val typeInfoSchema = FieldInfoUtils.getFieldsInfo(typeInfo, fields.toArray)

      val schemaBuilder = Schema.newBuilder()
      val fieldNames = typeInfoSchema.getFieldNames
      val fieldTypes = typeInfoSchema.getFieldTypes
      val fieldIndices = typeInfoSchema.getIndices

      (0 until fieldIndices.length).foreach(
        idx => {
          val fieldIndex = fieldIndices(idx)
          if (
            fieldIndex == TimeIndicatorTypeInfo.PROCTIME_STREAM_MARKER ||
            fieldIndex == TimeIndicatorTypeInfo.PROCTIME_BATCH_MARKER
          ) {
            schemaBuilder.columnByExpression(fieldNames(idx), "PROCTIME()")
          } else if (
            fieldIndex == TimeIndicatorTypeInfo.ROWTIME_STREAM_MARKER ||
            fieldIndex == TimeIndicatorTypeInfo.ROWTIME_BATCH_MARKER
          ) {
            schemaBuilder.column(fieldNames(idx), fieldTypes(idx))
            schemaBuilder.watermark(fieldNames(idx), s"${fieldNames(idx)}")
          } else {
            // Fallback to original behavior to keep compatibility.
            // See more at FieldInfoUtils#toResolvedSchema.
            schemaBuilder.column(fieldNames(idx), resolveLegacyTypeInfo(fieldTypes(idx)))
          }
        })
      schemaBuilder.build()
    }

    addTableSource(name, tableSchema)
  }

  /**
   * This is a temporary solution to maintain compatibility with the old type system in tests. See
   * more at [[LegacyTypeInfoDataTypeConverter]].
   *
   * Currently, we support to resolve the following common legacy types in tests:
   *
   *   - CaseClassTypeInfo
   *   - BasicArrayTypeInfo
   *   - java.math.BigDecimal
   */
  private def resolveLegacyTypeInfo(dataType: DataType): DataType = {
    val visitor = new DataTypeVisitor[DataType] {
      override def visit(atomicDataType: AtomicDataType): DataType = {
        if (!atomicDataType.getLogicalType.isInstanceOf[LegacyTypeInformationType[_]]) {
          return atomicDataType
        }

        val legacyType = atomicDataType.getLogicalType.asInstanceOf[LegacyTypeInformationType[_]]

        // resolve CaseClassTypeInfo
        if (legacyType.getTypeInformation.isInstanceOf[CaseClassTypeInfo[_]]) {
          val innerType = atomicDataType.getLogicalType
            .asInstanceOf[LegacyTypeInformationType[_]]
            .getTypeInformation
            .asInstanceOf[CaseClassTypeInfo[_]]

          val innerFieldNames = innerType.getFieldNames
          val innerFieldTypes = innerType.getFieldTypes

          val rowFields = ArrayBuffer[DataTypes.Field]()
          (0 until innerFieldNames.length).foreach(
            i => {
              rowFields += DataTypes.FIELD(
                innerFieldNames(i),
                resolveLegacyTypeInfo(fromLegacyInfoToDataType(innerFieldTypes(i))))
            })
          DataTypes.ROW(rowFields: _*)
        }
        // resolve BasicArrayTypeInfo
        else if (legacyType.getTypeInformation.isInstanceOf[BasicArrayTypeInfo[_, _]]) {
          val arrayType = legacyType.getTypeInformation.asInstanceOf[BasicArrayTypeInfo[_, _]]
          DataTypes.ARRAY(
            resolveLegacyTypeInfo(fromLegacyInfoToDataType(arrayType.getComponentInfo)))
        }
        // resolve java.math.BigDecimal
        else if (
          legacyType.getTypeInformation
            .isInstanceOf[BasicTypeInfo[_]] && legacyType.getTypeRoot == LogicalTypeRoot.DECIMAL
        ) {
          new AtomicDataType(DecimalDataUtils.DECIMAL_SYSTEM_DEFAULT)
        } else {
          throw new UnsupportedOperationException(s"Unsupported legacy type info: $legacyType")
        }
      }

      override def visit(collectionDataType: CollectionDataType): DataType = {
        val elementType = collectionDataType.getElementDataType.accept(this)
        new CollectionDataType(
          DataTypes.ARRAY(elementType).getLogicalType,
          collectionDataType.getElementDataType.accept(this))
      }

      override def visit(fieldsDataType: FieldsDataType): DataType = {
        fieldsDataType
      }

      override def visit(keyValueDataType: KeyValueDataType): DataType = {
        val keyType = keyValueDataType.getKeyDataType.accept(this)
        val valueType = keyValueDataType.getValueDataType.accept(this)
        new KeyValueDataType(DataTypes.MAP(keyType, valueType).getLogicalType, keyType, valueType)
      }
    }

    dataType.accept(visitor)

  }

  /**
   * Create a [[TestSimpleDynamicTableSource]] with the given schema, and registers this TableSource
   * under given name into the TableEnvironment's catalog.
   *
   * @param name
   *   table name
   * @param types
   *   field types
   * @param fields
   *   field names
   * @return
   *   returns the registered [[Table]].
   */
  def addTableSource(
      name: String,
      types: Array[AbstractDataType[_]],
      fields: Array[String]): Table = {
    val schema = Schema.newBuilder().fromFields(fields, types).build()
    addTableSource(name, schema)
  }

  /**
   * Register this TableSource under given name into the TableEnvironment's catalog.
   *
   * @param name
   *   table name
   * @param schema
   *   table schema
   * @return
   *   returns the registered [[Table]].
   */
  def addTableSource(
      name: String,
      schema: Schema
  ): Table = {
    val options = new util.HashMap[String, String]()
    options.put("connector", IDENTIFIER)
    options.put(BOUNDED.key(), isBounded.toString)

    val table = CatalogTable.newBuilder().schema(schema).options(options).build()
    val catalogManager = getTableEnv
      .asInstanceOf[TableEnvironmentInternal]
      .getCatalogManager

    catalogManager.createTable(
      table,
      ObjectIdentifier.of(
        catalogManager.getCurrentCatalog,
        catalogManager.getCurrentDatabase,
        name),
      false)

    getTableEnv.from(name)
  }

  /** Registers a [[UserDefinedFunction]] according to FLIP-65. */
  def addTemporarySystemFunction(name: String, function: UserDefinedFunction): Unit = {
    getTableEnv.createTemporarySystemFunction(name, function)
  }

  /** Registers a [[UserDefinedFunction]] class according to FLIP-65. */
  def addTemporarySystemFunction(name: String, function: Class[_ <: UserDefinedFunction]): Unit = {
    getTableEnv.createTemporarySystemFunction(name, function)
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given SELECT query. Note: An exception will be thrown if the given query can't be
   * translated to exec plan.
   */
  def verifyPlan(query: String): Unit = {
    doVerifyPlan(
      query,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL, PlanKind.OPT_EXEC),
      withQueryBlockAlias = false)
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given SELECT query. The plans will contain the extra [[ExplainDetail]]s. Note: An exception
   * will be thrown if the given query can't be translated to exec plan.
   */
  def verifyPlan(query: String, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlan(
      query,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL, PlanKind.OPT_EXEC),
      withQueryBlockAlias = false)
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given INSERT statement.
   */
  def verifyPlanInsert(insert: String): Unit = {
    doVerifyPlanInsert(
      insert,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL, PlanKind.OPT_EXEC))
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given INSERT statement. The plans will contain the extra [[ExplainDetail]]s.
   */
  def verifyPlanInsert(insert: String, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlanInsert(
      insert,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL, PlanKind.OPT_EXEC))
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given [[Table]]. Note: An exception will be thrown if the given sql can't be translated to
   * exec plan.
   */
  def verifyPlan(table: Table): Unit = {
    doVerifyPlan(
      table,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL, PlanKind.OPT_EXEC))
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given [[Table]]. The plans will contain the extra [[ExplainDetail]]s. Note: An exception
   * will be thrown if the given sql can't be translated to exec plan.
   */
  def verifyPlan(table: Table, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlan(
      table,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL, PlanKind.OPT_EXEC))
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given [[Table]] with the given sink table name. Note: An exception will be thrown if the
   * given sql can't be translated to exec plan.
   */
  def verifyPlanInsert(table: Table, targetPath: String): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsert(targetPath, table)
    verifyPlan(stmtSet)
  }

  /**
   * Verify the AST (abstract syntax tree), the optimized rel plan and the optimized exec plan for
   * the given [[Table]] with the given sink table name. The plans will contain the extra
   * [[ExplainDetail]]s. Note: An exception will be thrown if the given sql can't be translated to
   * exec plan.
   */
  def verifyPlanInsert(table: Table, targetPath: String, extraDetails: ExplainDetail*): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsert(targetPath, table)
    verifyPlan(stmtSet, extraDetails: _*)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan and the optimized exec plan
   * for the given [[StatementSet]]. The plans will contain the extra [[ExplainDetail]]s. Note: An
   * exception will be thrown if the given sql can't be translated to exec plan.
   */
  def verifyPlan(stmtSet: StatementSet, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlan(
      stmtSet,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL, PlanKind.OPT_EXEC),
      () => Unit,
      withQueryBlockAlias = false,
      withDuplicateChanges = false
    )
  }

  /** Verify the AST (abstract syntax tree). */
  def verifyAstPlan(stmtSet: StatementSet): Unit = {
    doVerifyPlan(
      stmtSet,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST),
      () => Unit,
      withQueryBlockAlias = false,
      withDuplicateChanges = false)
  }

  /** Verify the AST (abstract syntax tree). The plans will contain the extra [[ExplainDetail]]s. */
  def verifyAstPlan(stmtSet: StatementSet, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlan(
      stmtSet,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST),
      () => Unit,
      withQueryBlockAlias = false,
      withDuplicateChanges = false)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given SELECT query.
   */
  def verifyRelPlan(query: String): Unit = {
    doVerifyPlan(
      query,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL),
      withQueryBlockAlias = false)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given SELECT query.
   * The plans will contain the extra [[ExplainDetail]]s.
   */
  def verifyRelPlan(query: String, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlan(
      query,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL),
      withQueryBlockAlias = false)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given INSERT
   * statement.
   */
  def verifyRelPlanInsert(insert: String): Unit = {
    doVerifyPlanInsert(
      insert,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL))
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given INSERT
   * statement. The plans will contain the extra [[ExplainDetail]]s.
   */
  def verifyRelPlanInsert(insert: String, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlanInsert(
      insert,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL))
  }

  /** Verify the AST (abstract syntax tree) and the optimized rel plan for the given [[Table]]. */
  def verifyRelPlan(table: Table): Unit = {
    doVerifyPlan(
      table,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL))
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given [[Table]]. The
   * plans will contain the extra [[ExplainDetail]]s.
   */
  def verifyRelPlan(table: Table, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlan(
      table,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL))
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given [[Table]] with
   * the given sink table name.
   */
  def verifyRelPlanInsert(table: Table, targetPath: String): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsert(targetPath, table)
    verifyRelPlan(stmtSet)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given [[Table]] with
   * the given sink table name. The plans will contain the extra [[ExplainDetail]]s.
   */
  def verifyRelPlanInsert(table: Table, targetPath: String, extraDetails: ExplainDetail*): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsert(targetPath, table)
    verifyRelPlan(stmtSet, extraDetails: _*)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given
   * [[StatementSet]].
   */
  def verifyRelPlan(stmtSet: StatementSet): Unit = {
    doVerifyPlan(
      stmtSet,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL),
      () => Unit,
      withQueryBlockAlias = false,
      withDuplicateChanges = false
    )
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given
   * [[StatementSet]]. The plans will contain the extra [[ExplainDetail]]s.
   */
  def verifyRelPlan(stmtSet: StatementSet, extraDetails: ExplainDetail*): Unit = {
    doVerifyPlan(
      stmtSet,
      extraDetails.toArray,
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL),
      () => Unit,
      withQueryBlockAlias = false,
      withDuplicateChanges = false)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given SELECT query.
   * The rel plans will contain the output type ([[org.apache.calcite.rel.type.RelDataType]]).
   */
  def verifyRelPlanWithType(query: String): Unit = {
    doVerifyPlan(
      query,
      Array.empty[ExplainDetail],
      withRowType = true,
      Array(PlanKind.AST, PlanKind.OPT_REL),
      withQueryBlockAlias = false)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given [[Table]]. The
   * rel plans will contain the output type ([[org.apache.calcite.rel.type.RelDataType]]).
   */
  def verifyRelPlanWithType(table: Table): Unit = {
    doVerifyPlan(
      table,
      Array.empty[ExplainDetail],
      withRowType = true,
      Array(PlanKind.AST, PlanKind.OPT_REL))
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized rel plan for the given
   * [[StatementSet]]. The rel plans will contain the output type
   * ([[org.apache.calcite.rel.type.RelDataType]]).
   */
  def verifyRelPlanWithType(stmtSet: StatementSet): Unit = {
    doVerifyPlan(
      stmtSet,
      Array.empty[ExplainDetail],
      withRowType = true,
      Array(PlanKind.AST, PlanKind.OPT_REL),
      () => Unit,
      withQueryBlockAlias = false,
      withDuplicateChanges = false
    )
  }

  /**
   * Verify whether the optimized rel plan for the given SELECT query does not contain the
   * `notExpected` strings.
   */
  def verifyRelPlanExpected(query: String, notExpected: String*): Unit = {
    verifyRelPlanExpected(getTableEnv.sqlQuery(query), notExpected: _*)
  }

  /**
   * Verify whether the optimized rel plan for the given [[Table]] does not contain the
   * `notExpected` strings.
   */
  def verifyRelPlanExpected(table: Table, expected: String*): Unit = {
    require(expected.nonEmpty)
    val relNode = TableTestUtil.toRelNode(table)
    val optimizedRel = getPlanner.optimize(relNode)
    val optimizedPlan = getOptimizedRelPlan(
      Array(optimizedRel),
      Array.empty,
      withRowType = false,
      withDuplicateChanges = false)
    val result = expected.forall(optimizedPlan.contains(_))
    val message = s"\nactual plan:\n$optimizedPlan\nexpected:\n${expected.mkString(", ")}"
    assertTrue(result, message)
  }

  /**
   * Verify whether the optimized rel plan for the given SELECT query does not contain the
   * `notExpected` strings.
   */
  def verifyRelPlanNotExpected(query: String, notExpected: String*): Unit = {
    verifyRelPlanNotExpected(getTableEnv.sqlQuery(query), notExpected: _*)
  }

  /**
   * Verify whether the optimized rel plan for the given [[Table]] does not contain the
   * `notExpected` strings.
   */
  def verifyRelPlanNotExpected(table: Table, notExpected: String*): Unit = {
    require(notExpected.nonEmpty)
    val relNode = TableTestUtil.toRelNode(table)
    val optimizedRel = getPlanner.optimize(relNode)
    val optimizedPlan = getOptimizedRelPlan(
      Array(optimizedRel),
      Array.empty,
      withRowType = false,
      withDuplicateChanges = false)
    val result = notExpected.forall(!optimizedPlan.contains(_))
    val message = s"\nactual plan:\n$optimizedPlan\nnot expected:\n${notExpected.mkString(", ")}"
    assertTrue(result, message)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized exec plan for the given SELECT query.
   * Note: An exception will be thrown if the given sql can't be translated to exec plan.
   */
  def verifyExecPlan(query: String): Unit = {
    doVerifyPlan(
      query,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_EXEC),
      withQueryBlockAlias = false)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized exec plan for the given INSERT
   * statement. Note: An exception will be thrown if the given sql can't be translated to exec plan.
   */
  def verifyExecPlanInsert(insert: String): Unit = {
    doVerifyPlanInsert(
      insert,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_EXEC))
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized exec plan for the given [[Table]].
   * Note: An exception will be thrown if the given sql can't be translated to exec plan.
   */
  def verifyExecPlan(table: Table): Unit = {
    doVerifyPlan(
      table,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_EXEC))
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized exec plan for the given [[Table]] with
   * the given sink table name. Note: An exception will be thrown if the given sql can't be
   * translated to exec plan.
   */
  def verifyExecPlanInsert(table: Table, targetPath: String): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsert(targetPath, table)
    verifyExecPlan(stmtSet)
  }

  /**
   * Verify the AST (abstract syntax tree) and the optimized exec plan for the given
   * [[StatementSet]]. Note: An exception will be thrown if the given sql can't be translated to
   * exec plan.
   */
  def verifyExecPlan(stmtSet: StatementSet): Unit = {
    doVerifyPlan(
      stmtSet,
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_EXEC),
      () => Unit,
      withQueryBlockAlias = false,
      withDuplicateChanges = false
    )
  }

  /** Verify the explain result for the given SELECT query. See more about [[Table#explain()]]. */
  def verifyExplain(query: String): Unit = verifyExplain(getTableEnv.sqlQuery(query))

  /**
   * Verify the explain result for the given SELECT query. The explain result will contain the extra
   * [[ExplainDetail]]s. See more about [[Table#explain()]].
   */
  def verifyExplain(query: String, extraDetails: ExplainDetail*): Unit = {
    val table = getTableEnv.sqlQuery(query)
    verifyExplain(table, extraDetails: _*)
  }

  /**
   * Verify the explain result for the given INSERT statement. See more about
   * [[StatementSet#explain()]].
   */
  def verifyExplainInsert(insert: String): Unit = {
    val statSet = getTableEnv.createStatementSet()
    statSet.addInsertSql(insert)
    verifyExplain(statSet)
  }

  /**
   * Verify the explain result for the given INSERT statement. The explain result will contain the
   * extra [[ExplainDetail]]s. See more about [[StatementSet#explain()]].
   */
  def verifyExplainInsert(insert: String, extraDetails: ExplainDetail*): Unit = {
    val statSet = getTableEnv.createStatementSet()
    statSet.addInsertSql(insert)
    verifyExplain(statSet, extraDetails: _*)
  }

  /** Verify the explain result for the given sql clause which represents a [[ModifyOperation]]. */
  def verifyExplainSql(sql: String): Unit = {
    val operations = getTableEnv.asInstanceOf[TableEnvironmentImpl].getParser.parse(sql)
    val relNode =
      TableTestUtil.toRelNode(getTableEnv, operations.get(0).asInstanceOf[ModifyOperation])
    assertPlanEquals(
      Array(relNode),
      Array.empty[ExplainDetail],
      withRowType = false,
      Array(PlanKind.AST, PlanKind.OPT_REL),
      () => assertEqualsOrExpand("sql", sql))
  }

  /** Verify the explain result for the given [[Table]]. See more about [[Table#explain()]]. */
  def verifyExplain(table: Table): Unit = {
    doVerifyExplain(table.explain())
  }

  /**
   * Verify the explain result for the given [[Table]]. The explain result will contain the extra
   * [[ExplainDetail]]s. See more about [[Table#explain()]].
   */
  def verifyExplain(table: Table, extraDetails: ExplainDetail*): Unit = {
    doVerifyExplain(table.explain(extraDetails: _*), extraDetails: _*)
  }

  /** Verify the expected exception for the given sql with the given message and exception class. */
  def verifyExpectdException(
      sql: String,
      message: String,
      clazz: Class[_ <: Throwable] = classOf[ValidationException]): Unit = {
    assertThatExceptionOfType(clazz)
      .isThrownBy(() => verifyExplain(sql))
      .withMessageContaining(message)
  }

  /**
   * Verify the explain result for the given [[Table]] with the given sink table name. See more
   * about [[StatementSet#explain()]].
   */
  def verifyExplainInsert(table: Table, targetPath: String): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsert(targetPath, table)
    verifyExplain(stmtSet)
  }

  /**
   * Verify the explain result for the given [[Table]] with the given sink table name. The explain
   * result will contain the extra [[ExplainDetail]]s. See more about [[StatementSet#explain()]].
   */
  def verifyExplainInsert(table: Table, targetPath: String, extraDetails: ExplainDetail*): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsert(targetPath, table)
    verifyExplain(stmtSet, extraDetails: _*)
  }

  /**
   * Verify the explain result for the given [[StatementSet]]. See more about
   * [[StatementSet#explain()]].
   */
  def verifyExplain(stmtSet: StatementSet): Unit = {
    doVerifyExplain(stmtSet.explain())
  }

  /**
   * Verify the explain result for the given [[StatementSet]]. The explain result will contain the
   * extra [[ExplainDetail]]s. See more about [[StatementSet#explain()]].
   */
  def verifyExplain(stmtSet: StatementSet, extraDetails: ExplainDetail*): Unit = {
    doVerifyExplain(stmtSet.explain(extraDetails: _*), extraDetails: _*)
  }

  final val PLAN_TEST_FORCE_OVERWRITE = "PLAN_TEST_FORCE_OVERWRITE"

  /** Verify the json plan for the given insert statement. */
  def verifyJsonPlan(insert: String): Unit = {
    ExecNodeContext.resetIdCounter()
    val jsonPlan = getTableEnv.compilePlanSql(insert).asJsonString()
    doVerifyJsonPlan(jsonPlan)
  }

  /** Verify the json plan for the given [[StatementSet]]. */
  def verifyJsonPlan(stmtSet: StatementSet): Unit = {
    ExecNodeContext.resetIdCounter()
    val jsonPlan = stmtSet.compilePlan().asJsonString()
    doVerifyJsonPlan(jsonPlan)
  }

  /** Verify the serialized JSON of [[CompiledPlan]] for the given insert statement. */
  def doVerifyJsonPlan(jsonPlan: String): Unit = {
    val jsonPlanWithoutFlinkVersion = TableTestUtil.replaceFlinkVersion(jsonPlan)
    // add the postfix to the path to avoid conflicts
    // between the test class name and the result file name
    val clazz = test.getClass
    val testClassDirPath = clazz.getName.replaceAll("\\.", "/") + "_jsonplan"
    val testMethodFileName = test.methodName + ".out"
    val resourceTestFilePath = s"/$testClassDirPath/$testMethodFileName"
    val plannerDirPath = clazz.getResource("/").getFile.replace("/target/test-classes/", "")
    val file = new File(s"$plannerDirPath/src/test/resources$resourceTestFilePath")
    val path = file.toPath
    if (!file.exists() || "true".equalsIgnoreCase(System.getenv(PLAN_TEST_FORCE_OVERWRITE))) {
      Files.deleteIfExists(path)
      file.getParentFile.mkdirs()
      assertTrue(file.createNewFile())
      val prettyJson = TableTestUtil.getPrettyJson(jsonPlanWithoutFlinkVersion)
      Files.write(path, prettyJson.getBytes)
      fail(s"$testMethodFileName regenerated.")
    } else {
      val expected = String.join("\n", Files.readAllLines(path))
      assertThat(
        TableTestUtil.replaceExecNodeId(TableTestUtil.getPrettyJson(jsonPlanWithoutFlinkVersion)))
        .isEqualTo(TableTestUtil.replaceExecNodeId(TableTestUtil.getPrettyJson(expected)))
      // check json serde round trip as well
      val expectedWithFlinkVersion = JsonTestUtils.writeToString(
        JsonTestUtils
          .setFlinkVersion(JsonTestUtils.readFromString(expected), FlinkVersion.current()))
      assertThat(
        TableTestUtil.replaceExecNodeId(
          TableTestUtil.getFormattedJson(getPlanner
            .loadPlan(PlanReference.fromJsonString(expectedWithFlinkVersion))
            .asJsonString())))
        .isEqualTo(
          TableTestUtil.replaceExecNodeId(TableTestUtil.getFormattedJson(expectedWithFlinkVersion)))
    }
  }

  /**
   * Verify the given query and the expected plans translated from the SELECT query.
   *
   * @param query
   *   the SELECT query to check
   * @param extraDetails
   *   the extra [[ExplainDetail]]s the plans should contain
   * @param withRowType
   *   whether the rel plans contain the output type
   * @param expectedPlans
   *   the expected [[PlanKind]]s to check
   */
  def doVerifyPlan(
      query: String,
      extraDetails: Array[ExplainDetail],
      withRowType: Boolean,
      expectedPlans: Array[PlanKind],
      withQueryBlockAlias: Boolean): Unit = {
    val table = getTableEnv.sqlQuery(query)
    val relNode = TableTestUtil.toRelNode(table)

    assertPlanEquals(
      Array(relNode),
      extraDetails,
      withRowType,
      expectedPlans,
      () => assertEqualsOrExpand("sql", query),
      withQueryBlockAlias)
  }

  /**
   * Verify the given query and the expected plans translated from the INSERT statement.
   *
   * @param insert
   *   the INSERT statement to check
   * @param extraDetails
   *   the extra [[ExplainDetail]]s the plans should contain
   * @param withRowType
   *   whether the rel plans contain the output type
   * @param expectedPlans
   *   the expected [[PlanKind]]s to check
   */
  def doVerifyPlanInsert(
      insert: String,
      extraDetails: Array[ExplainDetail],
      withRowType: Boolean,
      expectedPlans: Array[PlanKind]): Unit = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsertSql(insert)
    doVerifyPlan(
      stmtSet,
      extraDetails,
      withRowType,
      expectedPlans,
      () => assertEqualsOrExpand("sql", insert),
      withQueryBlockAlias = false,
      withDuplicateChanges = false)
  }

  /**
   * Generate the stream graph from the INSERT statement.
   *
   * @param insert
   *   the INSERT statement to check
   */
  def generateTransformations(insert: String): util.List[Transformation[_]] = {
    val stmtSet = getTableEnv.createStatementSet()
    stmtSet.addInsertSql(insert)

    val testStmtSet = stmtSet.asInstanceOf[StatementSetImpl[_]]
    val operations = testStmtSet.getOperations;
    getPlanner.translate(operations)
  }

  /**
   * Verify the expected plans translated from the given [[Table]].
   *
   * @param table
   *   the [[Table]] to check
   * @param extraDetails
   *   the extra [[ExplainDetail]]s the plans should contain
   * @param withRowType
   *   whether the rel plans contain the output type
   * @param expectedPlans
   *   the expected [[PlanKind]]s to check
   */
  def doVerifyPlan(
      table: Table,
      extraDetails: Array[ExplainDetail],
      withRowType: Boolean = false,
      expectedPlans: Array[PlanKind]): Unit = {
    val relNode = TableTestUtil.toRelNode(table)
    assertPlanEquals(Array(relNode), extraDetails, withRowType, expectedPlans, () => {})
  }

  /**
   * Verify the expected plans translated from the given [[StatementSet]].
   *
   * @param stmtSet
   *   the [[StatementSet]] to check
   * @param extraDetails
   *   the extra [[ExplainDetail]]s the plans should contain
   * @param withRowType
   *   whether the rel plans contain the output type
   * @param expectedPlans
   *   the expected [[PlanKind]]s to check
   * @param assertSqlEqualsOrExpandFunc
   *   the function to check whether the sql equals to the expected if the `stmtSet` is only
   *   translated from sql
   */
  def doVerifyPlan(
      stmtSet: StatementSet,
      extraDetails: Array[ExplainDetail],
      withRowType: Boolean,
      expectedPlans: Array[PlanKind],
      assertSqlEqualsOrExpandFunc: () => Unit,
      withQueryBlockAlias: Boolean,
      withDuplicateChanges: Boolean): Unit = {
    val testStmtSet = stmtSet.asInstanceOf[StatementSetImpl[_]]

    val relNodes = testStmtSet.getOperations.map(getPlanner.translateToRel)
    if (relNodes.isEmpty) {
      throw new TableException(
        "No output table have been created yet. " +
          "A program needs at least one output table that consumes data.\n" +
          "Please create output table(s) for your program")
    }

    assertPlanEquals(
      relNodes.toArray,
      extraDetails,
      withRowType,
      expectedPlans,
      assertSqlEqualsOrExpandFunc,
      withQueryBlockAlias,
      withDuplicateChanges)
  }

  /**
   * Verify the expected plans translated from the given [[RelNode]]s.
   *
   * @param relNodes
   *   the original (un-optimized) [[RelNode]]s to check
   * @param extraDetails
   *   the extra [[ExplainDetail]]s the plans should contain
   * @param withRowType
   *   whether the rel plans contain the output type
   * @param expectedPlans
   *   the expected [[PlanKind]]s to check
   * @param assertSqlEqualsOrExpandFunc
   *   the function to check whether the sql equals to the expected if the `relNodes` are translated
   *   from sql
   * @param withQueryBlockAlias
   *   whether the rel plans contains the query block alias, default is false
   */
  def assertPlanEquals(
      relNodes: Array[RelNode],
      extraDetails: Array[ExplainDetail],
      withRowType: Boolean,
      expectedPlans: Array[PlanKind],
      assertSqlEqualsOrExpandFunc: () => Unit,
      withQueryBlockAlias: Boolean = false,
      withDuplicateChanges: Boolean = false): Unit = {

    // build ast plan
    val astBuilder = new StringBuilder
    relNodes.foreach {
      sink =>
        astBuilder
          .append(System.lineSeparator)
          .append(
            FlinkRelOptUtil
              .toString(
                sink,
                SqlExplainLevel.EXPPLAN_ATTRIBUTES,
                withRowType = withRowType,
                withQueryBlockAlias = withQueryBlockAlias))
    }
    val astPlan = astBuilder.toString()

    // build optimized rel plan
    val optimizedRels = getPlanner.optimize(relNodes)
    val optimizedRelPlan = System.lineSeparator +
      getOptimizedRelPlan(
        optimizedRels.toArray,
        extraDetails,
        withRowType = withRowType,
        withDuplicateChanges = withDuplicateChanges)

    // build optimized exec plan if `expectedPlans` contains OPT_EXEC
    val optimizedExecPlan = if (expectedPlans.contains(PlanKind.OPT_EXEC)) {
      val execGraph = getPlanner.translateToExecNodeGraph(optimizedRels, isCompiled = false)
      System.lineSeparator + ExecNodePlanDumper.dagToString(execGraph)
    } else {
      ""
    }

    // check whether the sql equals to the expected if the `relNodes` are translated from sql
    assertSqlEqualsOrExpandFunc()
    // check ast plan
    if (expectedPlans.contains(PlanKind.AST)) {
      assertEqualsOrExpand("ast", astPlan)
    }
    // check optimized rel plan
    if (expectedPlans.contains(PlanKind.OPT_REL)) {
      assertEqualsOrExpand("optimized rel plan", optimizedRelPlan, expand = false)
    }
    // check optimized rel plan with available advice
    if (expectedPlans.contains(PlanKind.OPT_REL_WITH_ADVICE)) {
      assertEqualsOrExpand("optimized rel plan with advice", optimizedRelPlan, expand = false)
    }
    // check optimized exec plan
    if (expectedPlans.contains(PlanKind.OPT_EXEC)) {
      assertEqualsOrExpand("optimized exec plan", optimizedExecPlan, expand = false)
    }
  }

  private def doVerifyExplain(explainResult: String, extraDetails: ExplainDetail*): Unit = {
    def replace(result: String, explainDetail: ExplainDetail): String = {
      val replaced = explainDetail match {
        case ExplainDetail.ESTIMATED_COST => replaceEstimatedCost(result)
        case ExplainDetail.JSON_EXECUTION_PLAN =>
          replaceNodeIdInOperator(replaceStreamNodeId(replaceStageId(result)))
        case _ => result
      }
      replaced
    }
    var replacedResult = explainResult
    extraDetails.foreach(detail => replacedResult = replace(replacedResult, detail))
    assertEqualsOrExpand("explain", TableTestUtil.replaceStageId(replacedResult), expand = false)
  }

  protected def getOptimizedRelPlan(
      optimizedRels: Array[RelNode],
      extraDetails: Array[ExplainDetail],
      withRowType: Boolean,
      withDuplicateChanges: Boolean): String = {
    require(optimizedRels.nonEmpty)
    val explainLevel = if (extraDetails.contains(ExplainDetail.ESTIMATED_COST)) {
      SqlExplainLevel.ALL_ATTRIBUTES
    } else {
      SqlExplainLevel.EXPPLAN_ATTRIBUTES
    }
    val withChangelogTraits = extraDetails.contains(ExplainDetail.CHANGELOG_MODE)

    val withAdvice = extraDetails.contains(ExplainDetail.PLAN_ADVICE)
    val optimizedPlan = optimizedRels.head match {
      case _: RelNode =>
        if (withAdvice) {
          FlinkRelOptUtil.toString(
            optimizedRels,
            detailLevel = explainLevel,
            withChangelogTraits = withChangelogTraits,
            withAdvice = true)
        } else {
          optimizedRels
            .map {
              rel =>
                FlinkRelOptUtil.toString(
                  rel,
                  detailLevel = explainLevel,
                  withChangelogTraits = withChangelogTraits,
                  withRowType = withRowType,
                  withDuplicateChangesTrait = withDuplicateChanges)
            }
            .mkString("\n")
        }

      case o =>
        throw new TableException(
          "The expected optimized plan is RelNode plan, " +
            s"actual plan is ${o.getClass.getSimpleName} plan.")
    }
    replaceEstimatedCost(optimizedPlan)
  }

  /** Replace the estimated costs for the given plan, because it may be unstable. */
  protected def replaceEstimatedCost(s: String): String = {
    var str = s.replaceAll("\\r\\n", "\n")
    val scientificFormRegExpr = "[+-]?[\\d]+([\\.][\\d]*)?([Ee][+-]?[0-9]{0,2})?"
    str = str.replaceAll(s"rowcount = $scientificFormRegExpr", "rowcount = ")
    str = str.replaceAll(s"$scientificFormRegExpr rows", "rows")
    str = str.replaceAll(s"$scientificFormRegExpr cpu", "cpu")
    str = str.replaceAll(s"$scientificFormRegExpr io", "io")
    str = str.replaceAll(s"$scientificFormRegExpr network", "network")
    str = str.replaceAll(s"$scientificFormRegExpr memory", "memory")
    str
  }

  def assertEqualsOrExpand(tag: String, actual: String, expand: Boolean = true): Unit = {
    val expected = s"$${$tag}"
    if (!expand) {
      diffRepository.assertEquals(test.methodName, tag, expected, actual)
      return
    }
    val expanded = diffRepository.expand(test.methodName, tag, expected)
    if (expanded != null && !expanded.equals(expected)) {
      // expected does exist, check result
      diffRepository.assertEquals(test.methodName, tag, expected, actual)
    } else {
      // expected does not exist, update
      diffRepository.expand(test.methodName, tag, actual)
    }
  }
}

abstract class TableTestUtil(
    test: TableTestBase,
    // determines if the table environment should work in a batch or streaming mode
    isStreamingMode: Boolean,
    catalogManager: Option[CatalogManager] = None,
    val tableConfig: TableConfig)
  extends TableTestUtilBase(test, isStreamingMode) {
  protected val testingTableEnv: TestingTableEnvironment =
    TestingTableEnvironment.create(setting, catalogManager, tableConfig)
  val tableEnv: TableEnvironment = testingTableEnv
  tableEnv.getConfig.set(
    BatchExecutionOptions.ADAPTIVE_AUTO_PARALLELISM_ENABLED,
    Boolean.box(false))
  tableEnv.getConfig.set(
    OptimizerConfigOptions.TABLE_OPTIMIZER_ADAPTIVE_BROADCAST_JOIN_STRATEGY,
    NONE)
  tableEnv.getConfig.set(
    OptimizerConfigOptions.TABLE_OPTIMIZER_ADAPTIVE_SKEWED_JOIN_OPTIMIZATION_STRATEGY,
    OptimizerConfigOptions.AdaptiveSkewedJoinOptimizationStrategy.NONE)

  private val env: StreamExecutionEnvironment = getPlanner.getExecEnv

  override def getTableEnv: TableEnvironment = tableEnv

  def getStreamEnv: StreamExecutionEnvironment = env

  /**
   * Create a [[TestTableSource]] with the given schema, table stats and unique keys, and registers
   * this TableSource under given name into the TableEnvironment's catalog.
   *
   * @param name
   *   table name
   * @param types
   *   field types
   * @param fields
   *   field names
   * @param statistic
   *   statistic of current table
   * @return
   *   returns the registered [[Table]].
   */
  def addTableSource(
      name: String,
      types: Array[TypeInformation[_]],
      fields: Array[String],
      statistic: FlinkStatistic = FlinkStatistic.UNKNOWN): Table = {
    val schema = new TableSchema(fields, types)
    val tableSource = new TestTableSource(isBounded, schema)
    addTableSource(name, tableSource, statistic)
  }

  /**
   * Register this TableSource under given name into the TableEnvironment's catalog.
   *
   * @param name
   *   table name
   * @param tableSource
   *   table source
   * @param statistic
   *   statistic of current table
   * @return
   *   returns the registered [[Table]].
   */
  def addTableSource(
      name: String,
      tableSource: TableSource[_],
      statistic: FlinkStatistic): Table = {
    // TODO RichTableSourceQueryOperation should be deleted and use registerTableSourceInternal
    //  method instead of createTemporaryView method here after unique key in TableSchema is ready
    //  and setting catalog statistic to TableSourceTable in DatabaseCalciteSchema is ready
    val identifier = ObjectIdentifier.of(
      testingTableEnv.getCurrentCatalog,
      testingTableEnv.getCurrentDatabase,
      name)
    val operation = new RichTableSourceQueryOperation(identifier, tableSource, statistic)
    val table = testingTableEnv.createTable(operation)
    testingTableEnv.createTemporaryView(name, table)
    testingTableEnv.from(name)
  }
}

abstract class ScalaTableTestUtil(test: TableTestBase, isStreamingMode: Boolean)
  extends TableTestUtilBase(test, isStreamingMode) {
  // env
  val env = new LocalStreamEnvironment()
  // scala tableEnv
  val tableEnv: ScalaStreamTableEnv = ScalaStreamTableEnv.create(env, setting)

  override def getTableEnv: TableEnvironment = tableEnv
}

abstract class JavaTableTestUtil(test: TableTestBase, isStreamingMode: Boolean)
  extends TableTestUtilBase(test, isStreamingMode) {
  // java env
  val env = new LocalStreamEnvironment()
  // java tableEnv
  val tableEnv: JavaStreamTableEnv = JavaStreamTableEnv.create(env, setting)

  override def getTableEnv: TableEnvironment = tableEnv
}

/** Utility for stream table test. */
case class StreamTableTestUtil(
    test: TableTestBase,
    catalogManager: Option[CatalogManager] = None,
    override val tableConfig: TableConfig = TableConfig.getDefault)
  extends TableTestUtil(test, isStreamingMode = true, catalogManager, tableConfig) {

  /**
   * Register a table with specific row time field and offset.
   *
   * @param tableName
   *   table name
   * @param sourceTable
   *   table to register
   * @param rowtimeField
   *   row time field
   * @param offset
   *   offset to the row time field value
   */
  def addTableWithWatermark(
      tableName: String,
      sourceTable: Table,
      rowtimeField: String,
      offset: Long): Unit = {
    val sourceRel = TableTestUtil.toRelNode(sourceTable)
    val rowtimeFieldIdx = sourceRel.getRowType.getFieldNames.indexOf(rowtimeField)
    if (rowtimeFieldIdx < 0) {
      throw new TableException(s"$rowtimeField does not exist, please check it")
    }
    val rexBuilder = sourceRel.getCluster.getRexBuilder
    val inputRef = rexBuilder.makeInputRef(sourceRel, rowtimeFieldIdx)
    val offsetLiteral = rexBuilder.makeIntervalLiteral(
      JBigDecimal.valueOf(offset),
      new SqlIntervalQualifier(TimeUnit.MILLISECOND, null, SqlParserPos.ZERO))
    val expr = rexBuilder.makeCall(FlinkSqlOperatorTable.MINUS, inputRef, offsetLiteral)
    val watermarkAssigner = new LogicalWatermarkAssigner(
      sourceRel.getCluster,
      sourceRel.getTraitSet,
      sourceRel,
      Collections.emptyList(),
      rowtimeFieldIdx,
      expr
    )
    val queryOperation = new PlannerQueryOperation(
      watermarkAssigner,
      () =>
        throw new TableException("Cannot convert a LogicalWatermarkAssigner back to a SQL string."))
    testingTableEnv.createTemporaryView(tableName, testingTableEnv.createTable(queryOperation))
  }

  def buildStreamProgram(firstProgramNameToRemove: String): Unit = {
    val program = FlinkStreamProgram.buildProgram(tableEnv.getConfig)
    var startRemove = false
    program.getProgramNames.foreach {
      name =>
        if (name.equals(firstProgramNameToRemove)) {
          startRemove = true
        }
        if (startRemove) {
          program.remove(name)
        }
    }
    replaceStreamProgram(program)
  }

  def replaceStreamProgram(program: FlinkChainedProgram[StreamOptimizeContext]): Unit = {
    var calciteConfig = TableConfigUtils.getCalciteConfig(tableEnv.getConfig)
    calciteConfig = CalciteConfig
      .createBuilder(calciteConfig)
      .replaceStreamProgram(program)
      .build()
    tableEnv.getConfig.setPlannerConfig(calciteConfig)
  }

  def getStreamProgram(): FlinkChainedProgram[StreamOptimizeContext] = {
    val tableConfig = tableEnv.getConfig
    val calciteConfig = TableConfigUtils.getCalciteConfig(tableConfig)
    calciteConfig.getStreamProgram.getOrElse(FlinkStreamProgram.buildProgram(tableConfig))
  }

  def enableMiniBatch(): Unit = {
    tableEnv.getConfig.set(ExecutionConfigOptions.TABLE_EXEC_MINIBATCH_ENABLED, Boolean.box(true))
    tableEnv.getConfig.set(
      ExecutionConfigOptions.TABLE_EXEC_MINIBATCH_ALLOW_LATENCY,
      Duration.ofSeconds(1))
    tableEnv.getConfig.set(ExecutionConfigOptions.TABLE_EXEC_MINIBATCH_SIZE, Long.box(3L))
  }

}

/** Utility for stream scala table test. */
case class ScalaStreamTableTestUtil(test: TableTestBase) extends ScalaTableTestUtil(test, true) {}

/** Utility for stream java table test. */
case class JavaStreamTableTestUtil(test: TableTestBase) extends JavaTableTestUtil(test, true) {}

/** Utility for batch table test. */
case class BatchTableTestUtil(
    test: TableTestBase,
    catalogManager: Option[CatalogManager] = None,
    override val tableConfig: TableConfig = TableConfig.getDefault)
  extends TableTestUtil(test, isStreamingMode = false, catalogManager, tableConfig) {

  def buildBatchProgram(firstProgramNameToRemove: String): Unit = {
    val program = FlinkBatchProgram.buildProgram(tableEnv.getConfig)
    var startRemove = false
    program.getProgramNames.foreach {
      name =>
        if (name.equals(firstProgramNameToRemove)) {
          startRemove = true
        }
        if (startRemove) {
          program.remove(name)
        }
    }
    replaceBatchProgram(program)
  }

  def replaceBatchProgram(program: FlinkChainedProgram[BatchOptimizeContext]): Unit = {
    var calciteConfig = TableConfigUtils.getCalciteConfig(tableEnv.getConfig)
    calciteConfig = CalciteConfig
      .createBuilder(calciteConfig)
      .replaceBatchProgram(program)
      .build()
    tableEnv.getConfig.setPlannerConfig(calciteConfig)
  }

  def getBatchProgram(): FlinkChainedProgram[BatchOptimizeContext] = {
    val tableConfig = tableEnv.getConfig
    val calciteConfig = TableConfigUtils.getCalciteConfig(tableConfig)
    calciteConfig.getBatchProgram.getOrElse(FlinkBatchProgram.buildProgram(tableConfig))
  }

}

/** Utility for batch scala table test. */
case class ScalaBatchTableTestUtil(test: TableTestBase) extends ScalaTableTestUtil(test, false) {}

/** Utility for batch java table test. */
case class JavaBatchTableTestUtil(test: TableTestBase) extends JavaTableTestUtil(test, false) {}

/** Batch/Stream [[TableSource]] for testing. */
class TestTableSource(override val isBounded: Boolean, schema: TableSchema)
  extends StreamTableSource[Row] {

  override def getDataStream(execEnv: environment.StreamExecutionEnvironment): DataStream[Row] = {
    execEnv.fromData(List[Row]().asJava, getReturnType)
  }

  override def getReturnType: TypeInformation[Row] = {
    val logicalTypes = schema.getFieldTypes
    new RowTypeInfo(logicalTypes, schema.getFieldNames)
  }

  override def getTableSchema: TableSchema = schema
}

class TestTableSourceFactory extends StreamTableSourceFactory[Row] {
  override def createStreamTableSource(
      properties: util.Map[String, String]): StreamTableSource[Row] = {
    val dp = new DescriptorProperties
    dp.putProperties(properties)
    val tableSchema = dp.getTableSchema(SCHEMA)
    val isBounded = dp.getOptionalBoolean("is-bounded").orElse(false)
    new TestTableSource(isBounded, tableSchema)
  }

  override def requiredContext(): util.Map[String, String] = {
    val context = new util.HashMap[String, String]()
    context.put(CONNECTOR_TYPE, "TestTableSource")
    context
  }

  override def supportedProperties(): util.List[String] = {
    val properties = new util.ArrayList[String]()
    properties.add("*")
    properties
  }
}

/**
 * Different with table in [[TestValuesTableFactory]], this table source does not support all
 * features like agg pushdown, filter pushdown, etc.
 */
class TestSimpleDynamicTableSource(bounded: Boolean, producedDataType: DataType)
  extends ScanTableSource {

  override def getChangelogMode: ChangelogMode = ChangelogMode.insertOnly()

  override def getScanRuntimeProvider(
      runtimeProviderContext: ScanTableSource.ScanContext): ScanTableSource.ScanRuntimeProvider = {

    val dataType: TypeInformation[RowData] =
      runtimeProviderContext.createTypeInformation(producedDataType)
    val serializer: TypeSerializer[RowData] = dataType.createSerializer(new SerializerConfigImpl)
    // use InputFormatProvider here to ensure the source is not chainable
    InputFormatProvider.of(new CollectionInputFormat[RowData](Collections.emptyList(), serializer))
  }

  override def copy(): DynamicTableSource = {
    new TestSimpleDynamicTableSource(bounded, producedDataType)
  }

  override def asSummaryString(): String = {
    "TestSimpleDynamicTableSource"
  }
}

class TestSimpleDynamicTableSourceFactory extends DynamicTableSourceFactory {

  override def createDynamicTableSource(
      context: DynamicTableFactory.Context): DynamicTableSource = {
    val helper = FactoryUtil.createTableFactoryHelper(this, context);
    helper.validate()
    val isBounded = helper.getOptions.get(BOUNDED)
    val producedDataType = context.getPhysicalRowDataType
    new TestSimpleDynamicTableSource(isBounded, producedDataType)
  }

  override def factoryIdentifier(): String = IDENTIFIER

  override def requiredOptions(): util.Set[ConfigOption[_]] = {
    Collections.emptySet()
  }

  override def optionalOptions(): util.Set[ConfigOption[_]] = {
    Collections.singleton(BOUNDED)
  }
}

object TestSimpleDynamicTableSourceFactory {
  val IDENTIFIER = "test-simple-table-source"

  val BOUNDED: ConfigOption[java.lang.Boolean] =
    ConfigOptions.key("bounded").booleanType().defaultValue(true)
}

class TestingTableEnvironment private (
    catalogManager: CatalogManager,
    moduleManager: ModuleManager,
    resourceManager: ResourceManager,
    tableConfig: TableConfig,
    executor: Executor,
    functionCatalog: FunctionCatalog,
    planner: PlannerBase,
    isStreamingMode: Boolean)
  extends TableEnvironmentImpl(
    catalogManager,
    moduleManager,
    resourceManager,
    tableConfig,
    executor,
    functionCatalog,
    planner,
    isStreamingMode) {

  def getResourceManager: ResourceManager = resourceManager

  // just for testing, remove this method while
  // `<T, ACC> void registerFunction(String name, AggregateFunction<T, ACC> aggregateFunction);`
  // is added into TableEnvironment
  def registerFunction[T: TypeInformation](name: String, tf: TableFunction[T]): Unit = {
    val typeInfo = UserDefinedFunctionHelper
      .getReturnTypeOfTableFunction(tf, implicitly[TypeInformation[T]])
    functionCatalog.registerTempSystemTableFunction(
      name,
      tf,
      typeInfo
    )
  }

  // just for testing, remove this method while
  // `<T> void registerFunction(String name, TableFunction<T> tableFunction);`
  // is added into TableEnvironment
  def registerFunction[T: TypeInformation, ACC: TypeInformation](
      name: String,
      f: AggregateFunction[T, ACC]): Unit = {
    registerImperativeAggregateFunction(name, f)
  }

  // just for testing, remove this method while
  // `<T, ACC> void registerFunction(String name, TableAggregateFunction<T, ACC> tableAggFunc);`
  // is added into TableEnvironment
  def registerFunction[T: TypeInformation, ACC: TypeInformation](
      name: String,
      f: TableAggregateFunction[T, ACC]): Unit = {
    registerImperativeAggregateFunction(name, f)
  }

  private def registerImperativeAggregateFunction[T: TypeInformation, ACC: TypeInformation](
      name: String,
      f: ImperativeAggregateFunction[T, ACC]): Unit = {
    val typeInfo = UserDefinedFunctionHelper
      .getReturnTypeOfAggregateFunction(f, implicitly[TypeInformation[T]])
    val accTypeInfo = UserDefinedFunctionHelper
      .getAccumulatorTypeOfAggregateFunction(f, implicitly[TypeInformation[ACC]])
    functionCatalog.registerTempSystemAggregateFunction(
      name,
      f,
      typeInfo,
      accTypeInfo
    )
  }

  override def createTable(tableOperation: QueryOperation): TableImpl = {
    super.createTable(tableOperation)
  }

  override def createStatementSet(): StatementSet = super.createStatementSet()
}

object TestingTableEnvironment {

  def create(
      settings: EnvironmentSettings,
      catalogManager: Option[CatalogManager] = None,
      tableConfig: TableConfig): TestingTableEnvironment = {

    val userClassLoader: MutableURLClassLoader =
      FlinkUserCodeClassLoaders.create(
        new Array[URL](0),
        settings.getUserClassLoader,
        settings.getConfiguration)

    val executorFactory = FactoryUtil.discoverFactory(
      userClassLoader,
      classOf[ExecutorFactory],
      ExecutorFactory.DEFAULT_IDENTIFIER)

    val executor = executorFactory.create(settings.getConfiguration)

    tableConfig.setRootConfiguration(executor.getConfiguration)
    tableConfig.addConfiguration(settings.getConfiguration)

    val resourceManager = new ResourceManager(settings.getConfiguration, userClassLoader)
    val moduleManager = new ModuleManager

    val catalogMgr = catalogManager match {
      case Some(c) => c
      case _ =>
        CatalogManager.newBuilder
          .classLoader(userClassLoader)
          .config(tableConfig)
          .defaultCatalog(
            settings.getBuiltInCatalogName,
            new GenericInMemoryCatalog(
              settings.getBuiltInCatalogName,
              settings.getBuiltInDatabaseName))
          .catalogStoreHolder(
            CatalogStoreHolder
              .newBuilder()
              .catalogStore(new GenericInMemoryCatalogStore)
              .config(tableConfig)
              .classloader(userClassLoader)
              .build())
          .build
    }

    val functionCatalog =
      new FunctionCatalog(settings.getConfiguration, resourceManager, catalogMgr, moduleManager)

    val planner = PlannerFactoryUtil
      .createPlanner(
        executor,
        tableConfig,
        userClassLoader,
        moduleManager,
        catalogMgr,
        functionCatalog)
      .asInstanceOf[PlannerBase]

    new TestingTableEnvironment(
      catalogMgr,
      moduleManager,
      resourceManager,
      tableConfig,
      executor,
      functionCatalog,
      planner,
      settings.isStreamingMode)
  }
}

/** [[PlanKind]] defines the types of plans to check in test cases. */
object PlanKind extends Enumeration {
  type PlanKind = Value

  /** Abstract Syntax Tree */
  val AST: Value = Value("AST")

  /** Optimized Rel Plan */
  val OPT_REL: Value = Value("OPT_REL")

  /** Optimized Rel Plan with Available Advice */
  val OPT_REL_WITH_ADVICE: Value = Value("OPT_REL_WITH_ADVICE")

  /** Optimized Execution Plan */
  val OPT_EXEC: Value = Value("OPT_EXEC")
}

object TableTestUtil {

  private val objectMapper = JacksonMapperFactory.createObjectMapper()

  val STREAM_SETTING: EnvironmentSettings =
    EnvironmentSettings.newInstance().inStreamingMode().build()
  val BATCH_SETTING: EnvironmentSettings = EnvironmentSettings.newInstance().inBatchMode().build()

  /** Convert operation tree in the given table to a RelNode tree. */
  def toRelNode(table: Table): RelNode = {
    table
      .asInstanceOf[TableImpl]
      .getTableEnvironment
      .asInstanceOf[TableEnvironmentImpl]
      .getPlanner
      .asInstanceOf[PlannerBase]
      .createRelBuilder
      .queryOperation(table.getQueryOperation)
      .build()
  }

  /** Convert modify operation to a RelNode tree. */
  def toRelNode(tEnv: TableEnvironment, modifyOperation: ModifyOperation): RelNode = {
    val planner = tEnv.asInstanceOf[TableEnvironmentImpl].getPlanner.asInstanceOf[PlannerBase]
    planner.translateToRel(modifyOperation)
  }

  /** Convert a sql query to a ExecNodeGraph. */
  def toExecNodeGraph(tEnv: TableEnvironment, sqlQuery: String): ExecNodeGraph = {
    val planner = tEnv.asInstanceOf[TableEnvironmentImpl].getPlanner.asInstanceOf[PlannerBase]
    val optimizedRel =
      planner.optimize(toRelNode(tEnv.sqlQuery(sqlQuery))).asInstanceOf[FlinkPhysicalRel]
    val generator = new ExecNodeGraphGenerator
    generator.generate(Collections.singletonList(optimizedRel), false)
  }

  def createTemporaryView[T](
      tEnv: TableEnvironment,
      name: String,
      dataStream: DataStream[T],
      fields: Option[Array[Expression]] = None,
      fieldNullables: Option[Array[Boolean]] = None,
      statistic: Option[FlinkStatistic] = None): Unit = {
    val planner = tEnv.asInstanceOf[TableEnvironmentImpl].getPlanner.asInstanceOf[PlannerBase]
    val execEnv = planner.getExecEnv
    val streamType = dataStream.getType
    // get field names and types for all non-replaced fields
    val typeInfoSchema = fields
      .map(
        (f: Array[Expression]) => {
          val fieldsInfo = FieldInfoUtils.getFieldsInfo(streamType, f)
          fieldsInfo
        })
      .getOrElse(FieldInfoUtils.getFieldsInfo(streamType))

    val fieldCnt = typeInfoSchema.getFieldTypes.length
    val dataStreamQueryOperation = new InternalDataStreamQueryOperation(
      ObjectIdentifier.of(tEnv.getCurrentCatalog, tEnv.getCurrentDatabase, name),
      dataStream,
      typeInfoSchema.getIndices,
      typeInfoSchema.toResolvedSchema,
      fieldNullables.getOrElse(Array.fill(fieldCnt)(true)),
      statistic.getOrElse(FlinkStatistic.UNKNOWN)
    )
    val table = tEnv.asInstanceOf[TableEnvironmentImpl].createTable(dataStreamQueryOperation)
    // the table name is UUID generated which should be quoted to parse safely
    tEnv.createTemporaryView(s"`$name`", table)
  }

  def readFromResource(path: String): String = {
    val basePath = getClass.getResource("/").getFile
    val fullPath = if (path.startsWith("/")) {
      s"$basePath${path.substring(1)}"
    } else {
      s"$basePath$path"
    }
    val source = Source.fromFile(fullPath)
    val str = source.mkString
    source.close()
    str
  }

  def readFromFile(path: String): Seq[String] = {
    val file = new File(path)
    if (file.isDirectory) {
      file.listFiles().foldLeft(Seq.empty[String]) {
        (lines, p) => lines ++ readFromFile(p.getAbsolutePath)
      }
    } else if (file.isHidden) {
      Seq.empty[String]
    } else {
      Files.readAllLines(Paths.get(file.toURI)).toSeq
    }
  }

  @throws[IOException]
  def getFormattedJson(json: String): String = {
    val parser = objectMapper.getFactory.createParser(json)
    val jsonNode: JsonNode = parser.readValueAsTree[JsonNode]
    jsonNode.toString
  }

  @throws[IOException]
  def getPrettyJson(json: String): String = {
    val parser = objectMapper.getFactory.createParser(json)
    val jsonNode: JsonNode = parser.readValueAsTree[JsonNode]
    jsonNode.toPrettyString
  }

  @throws[IOException]
  def isValidJson(json: String): Boolean = {
    try {
      val parser = objectMapper.getFactory.createParser(json)
      while (parser.nextToken() != null) {
        // Do nothing, just parse the JSON string
      }
      true
    } catch {
      case _: JsonParseException => false
    }
  }

  /**
   * Stage {id} is ignored, because id keeps incrementing in test class while
   * StreamExecutionEnvironment is up
   */
  def replaceStageId(s: String): String = {
    s.replaceAll("\\r\\n", "\n").replaceAll("Stage \\d+", "")
  }

  /**
   * Stream node {id} is ignored, because id keeps incrementing in test class while
   * StreamExecutionEnvironment is up
   */
  def replaceStreamNodeId(s: String): String = {
    s.replaceAll("\"id\"\\s*:\\s*\\d+", "\"id\" : ").trim
  }

  /** ExecNode {id} is ignored, because id keeps incrementing in test class. */
  def replaceExecNodeId(s: String): String = {
    s.replaceAll("\"id\"\\s*:\\s*\\d+", "\"id\" : 0")
      .replaceAll("\"source\"\\s*:\\s*\\d+", "\"source\" : 0")
      .replaceAll("\"target\"\\s*:\\s*\\d+", "\"target\" : 0")
  }

  /** Ignore flink version value. */
  def replaceFlinkVersion(s: String): String = {
    s.replaceAll("\"flinkVersion\"\\s*:\\s*\"[\\w.-]*\"", "\"flinkVersion\" : \"\"")
  }

  /** Ignore exec node in operator name and description. */
  def replaceNodeIdInOperator(s: String): String = {
    s.replaceAll("\"contents\"\\s*:\\s*\"\\[\\d+\\]:", "\"contents\" : \"[]:")
      // for sink v2.
      .replaceAll("\"contents\"\\s*:\\s*\"(\\w+)\\[\\d+\\]:", "\"contents\" : \"$1[]:")
      .replaceAll("(\"type\"\\s*:\\s*\".*?)\\[\\d+\\]", "$1[]")
  }
}

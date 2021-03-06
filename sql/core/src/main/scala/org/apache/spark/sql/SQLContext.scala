/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import java.beans.Introspector
import java.util.Properties

import scala.collection.immutable
import scala.language.implicitConversions
import scala.reflect.runtime.universe.TypeTag

import org.apache.spark.SparkContext
import org.apache.spark.annotation.{AlphaComponent, DeveloperApi, Experimental}
import org.apache.spark.api.java.{JavaSparkContext, JavaRDD}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.optimizer.{DefaultOptimizer, Optimizer}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.execution._
import org.apache.spark.sql.json._
import org.apache.spark.sql.sources.{LogicalRelation, BaseRelation, DDLParser, DataSourceStrategy}
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

/**
 * :: AlphaComponent ::
 * The entry point for running relational queries using Spark.  Allows the creation of [[DataFrame]]
 * objects and the execution of SQL queries.
 *
 * @groupname userf Spark SQL Functions
 * @groupname Ungrouped Support functions for language integrated queries.
 */
@AlphaComponent
class SQLContext(@transient val sparkContext: SparkContext)
  extends org.apache.spark.Logging
  with Serializable {

  self =>

  def this(sparkContext: JavaSparkContext) = this(sparkContext.sc)

  // Note that this is a lazy val so we can override the default value in subclasses.
  protected[sql] lazy val conf: SQLConf = new SQLConf

  /** Set Spark SQL configuration properties. */
  def setConf(props: Properties): Unit = conf.setConf(props)

  /** Set the given Spark SQL configuration property. */
  def setConf(key: String, value: String): Unit = conf.setConf(key, value)

  /** Return the value of Spark SQL configuration property for the given key. */
  def getConf(key: String): String = conf.getConf(key)

  /**
   * Return the value of Spark SQL configuration property for the given key. If the key is not set
   * yet, return `defaultValue`.
   */
  def getConf(key: String, defaultValue: String): String = conf.getConf(key, defaultValue)

  /**
   * Return all the configuration properties that have been set (i.e. not the default).
   * This creates a new copy of the config properties in the form of a Map.
   */
  def getAllConfs: immutable.Map[String, String] = conf.getAllConfs

  @transient
  protected[sql] lazy val catalog: Catalog = new SimpleCatalog(true)

  @transient
  protected[sql] lazy val functionRegistry: FunctionRegistry = new SimpleFunctionRegistry

  @transient
  protected[sql] lazy val analyzer: Analyzer =
    new Analyzer(catalog, functionRegistry, caseSensitive = true)

  @transient
  protected[sql] lazy val optimizer: Optimizer = DefaultOptimizer

  @transient
  protected[sql] val ddlParser = new DDLParser

  @transient
  protected[sql] val sqlParser = {
    val fallback = new catalyst.SqlParser
    new SparkSQLParser(fallback(_))
  }

  protected[sql] def parseSql(sql: String): LogicalPlan = {
    ddlParser(sql, false).getOrElse(sqlParser(sql))
  }

  protected[sql] def executeSql(sql: String): this.QueryExecution = executePlan(parseSql(sql))

  protected[sql] def executePlan(plan: LogicalPlan) = new this.QueryExecution(plan)

  sparkContext.getConf.getAll.foreach {
    case (key, value) if key.startsWith("spark.sql") => setConf(key, value)
    case _ =>
  }

  protected[sql] val cacheManager = new CacheManager(this)

  /**
   * A collection of methods that are considered experimental, but can be used to hook into
   * the query planner for advanced functionalities.
   */
  val experimental: ExperimentalMethods = new ExperimentalMethods(this)

  /**
   * A collection of methods for registering user-defined functions (UDF).
   *
   * The following example registers a Scala closure as UDF:
   * {{{
   *   sqlContext.udf.register("myUdf", (arg1: Int, arg2: String) => arg2 + arg1)
   * }}}
   *
   * The following example registers a UDF in Java:
   * {{{
   *   sqlContext.udf().register("myUDF",
   *       new UDF2<Integer, String, String>() {
   *           @Override
   *           public String call(Integer arg1, String arg2) {
   *               return arg2 + arg1;
   *           }
   *      }, DataTypes.StringType);
   * }}}
   *
   * Or, to use Java 8 lambda syntax:
   * {{{
   *   sqlContext.udf().register("myUDF",
   *       (Integer arg1, String arg2) -> arg2 + arg1),
   *       DataTypes.StringType);
   * }}}
   */
  val udf: UDFRegistration = new UDFRegistration(this)

  /** Returns true if the table is currently cached in-memory. */
  def isCached(tableName: String): Boolean = cacheManager.isCached(tableName)

  /** Caches the specified table in-memory. */
  def cacheTable(tableName: String): Unit = cacheManager.cacheTable(tableName)

  /** Removes the specified table from the in-memory cache. */
  def uncacheTable(tableName: String): Unit = cacheManager.uncacheTable(tableName)

  /**
   * Creates a DataFrame from an RDD of case classes.
   *
   * @group userf
   */
  implicit def createDataFrame[A <: Product: TypeTag](rdd: RDD[A]): DataFrame = {
    SparkPlan.currentContext.set(self)
    val attributeSeq = ScalaReflection.attributesFor[A]
    val schema = StructType.fromAttributes(attributeSeq)
    val rowRDD = RDDConversions.productToRowRdd(rdd, schema)
    new DataFrame(this, LogicalRDD(attributeSeq, rowRDD)(self))
  }

  /**
   * Convert a [[BaseRelation]] created for external data sources into a [[DataFrame]].
   */
  def baseRelationToDataFrame(baseRelation: BaseRelation): DataFrame = {
    new DataFrame(this, LogicalRelation(baseRelation))
  }

  /**
   * :: DeveloperApi ::
   * Creates a [[DataFrame]] from an [[RDD]] containing [[Row]]s by applying a schema to this RDD.
   * It is important to make sure that the structure of every [[Row]] of the provided RDD matches
   * the provided schema. Otherwise, there will be runtime exception.
   * Example:
   * {{{
   *  import org.apache.spark.sql._
   *  val sqlContext = new org.apache.spark.sql.SQLContext(sc)
   *
   *  val schema =
   *    StructType(
   *      StructField("name", StringType, false) ::
   *      StructField("age", IntegerType, true) :: Nil)
   *
   *  val people =
   *    sc.textFile("examples/src/main/resources/people.txt").map(
   *      _.split(",")).map(p => Row(p(0), p(1).trim.toInt))
   *  val dataFrame = sqlContext. applySchema(people, schema)
   *  dataFrame.printSchema
   *  // root
   *  // |-- name: string (nullable = false)
   *  // |-- age: integer (nullable = true)
   *
   *  dataFrame.registerTempTable("people")
   *  sqlContext.sql("select name from people").collect.foreach(println)
   * }}}
   *
   * @group userf
   */
  @DeveloperApi
  def applySchema(rowRDD: RDD[Row], schema: StructType): DataFrame = {
    // TODO: use MutableProjection when rowRDD is another DataFrame and the applied
    // schema differs from the existing schema on any field data type.
    val logicalPlan = LogicalRDD(schema.toAttributes, rowRDD)(self)
    new DataFrame(this, logicalPlan)
  }

  /**
   * Applies a schema to an RDD of Java Beans.
   *
   * WARNING: Since there is no guaranteed ordering for fields in a Java Bean,
   *          SELECT * queries will return the columns in an undefined order.
   */
  def applySchema(rdd: RDD[_], beanClass: Class[_]): DataFrame = {
    val attributeSeq = getSchema(beanClass)
    val className = beanClass.getName
    val rowRdd = rdd.mapPartitions { iter =>
      // BeanInfo is not serializable so we must rediscover it remotely for each partition.
      val localBeanInfo = Introspector.getBeanInfo(
        Class.forName(className, true, Utils.getContextOrSparkClassLoader))
      val extractors =
        localBeanInfo.getPropertyDescriptors.filterNot(_.getName == "class").map(_.getReadMethod)

      iter.map { row =>
        new GenericRow(
          extractors.zip(attributeSeq).map { case (e, attr) =>
            DataTypeConversions.convertJavaToCatalyst(e.invoke(row), attr.dataType)
          }.toArray[Any]
        ) : Row
      }
    }
    new DataFrame(this, LogicalRDD(attributeSeq, rowRdd)(this))
  }

  /**
   * Applies a schema to an RDD of Java Beans.
   *
   * WARNING: Since there is no guaranteed ordering for fields in a Java Bean,
   *          SELECT * queries will return the columns in an undefined order.
   */
  def applySchema(rdd: JavaRDD[_], beanClass: Class[_]): DataFrame = {
    applySchema(rdd.rdd, beanClass)
  }

  /**
   * Loads a Parquet file, returning the result as a [[DataFrame]].
   *
   * @group userf
   */
  def parquetFile(path: String): DataFrame =
    new DataFrame(this, parquet.ParquetRelation(path, Some(sparkContext.hadoopConfiguration), this))

  /**
   * Loads a JSON file (one object per line), returning the result as a [[DataFrame]].
   * It goes through the entire dataset once to determine the schema.
   *
   * @group userf
   */
  def jsonFile(path: String): DataFrame = jsonFile(path, 1.0)

  /**
   * :: Experimental ::
   * Loads a JSON file (one object per line) and applies the given schema,
   * returning the result as a [[DataFrame]].
   *
   * @group userf
   */
  @Experimental
  def jsonFile(path: String, schema: StructType): DataFrame = {
    val json = sparkContext.textFile(path)
    jsonRDD(json, schema)
  }

  /**
   * :: Experimental ::
   */
  @Experimental
  def jsonFile(path: String, samplingRatio: Double): DataFrame = {
    val json = sparkContext.textFile(path)
    jsonRDD(json, samplingRatio)
  }

  /**
   * Loads an RDD[String] storing JSON objects (one object per record), returning the result as a
   * [[DataFrame]].
   * It goes through the entire dataset once to determine the schema.
   *
   * @group userf
   */
  def jsonRDD(json: RDD[String]): DataFrame = jsonRDD(json, 1.0)

  /**
   * :: Experimental ::
   * Loads an RDD[String] storing JSON objects (one object per record) and applies the given schema,
   * returning the result as a [[DataFrame]].
   *
   * @group userf
   */
  @Experimental
  def jsonRDD(json: RDD[String], schema: StructType): DataFrame = {
    val columnNameOfCorruptJsonRecord = conf.columnNameOfCorruptRecord
    val appliedSchema =
      Option(schema).getOrElse(
        JsonRDD.nullTypeToStringType(
          JsonRDD.inferSchema(json, 1.0, columnNameOfCorruptJsonRecord)))
    val rowRDD = JsonRDD.jsonStringToRow(json, appliedSchema, columnNameOfCorruptJsonRecord)
    applySchema(rowRDD, appliedSchema)
  }

  /**
   * :: Experimental ::
   */
  @Experimental
  def jsonRDD(json: RDD[String], samplingRatio: Double): DataFrame = {
    val columnNameOfCorruptJsonRecord = conf.columnNameOfCorruptRecord
    val appliedSchema =
      JsonRDD.nullTypeToStringType(
        JsonRDD.inferSchema(json, samplingRatio, columnNameOfCorruptJsonRecord))
    val rowRDD = JsonRDD.jsonStringToRow(json, appliedSchema, columnNameOfCorruptJsonRecord)
    applySchema(rowRDD, appliedSchema)
  }

  /**
   * Registers the given RDD as a temporary table in the catalog.  Temporary tables exist only
   * during the lifetime of this instance of SQLContext.
   *
   * @group userf
   */
  def registerRDDAsTable(rdd: DataFrame, tableName: String): Unit = {
    catalog.registerTable(Seq(tableName), rdd.logicalPlan)
  }

  /**
   * Drops the temporary table with the given table name in the catalog. If the table has been
   * cached/persisted before, it's also unpersisted.
   *
   * @param tableName the name of the table to be unregistered.
   *
   * @group userf
   */
  def dropTempTable(tableName: String): Unit = {
    cacheManager.tryUncacheQuery(table(tableName))
    catalog.unregisterTable(Seq(tableName))
  }

  /**
   * Executes a SQL query using Spark, returning the result as a [[DataFrame]]. The dialect that is
   * used for SQL parsing can be configured with 'spark.sql.dialect'.
   *
   * @group userf
   */
  def sql(sqlText: String): DataFrame = {
    if (conf.dialect == "sql") {
      new DataFrame(this, parseSql(sqlText))
    } else {
      sys.error(s"Unsupported SQL dialect: ${conf.dialect}")
    }
  }

  /** Returns the specified table as a [[DataFrame]]. */
  def table(tableName: String): DataFrame =
    new DataFrame(this, catalog.lookupRelation(Seq(tableName)))

  protected[sql] class SparkPlanner extends SparkStrategies {
    val sparkContext: SparkContext = self.sparkContext

    val sqlContext: SQLContext = self

    def codegenEnabled = self.conf.codegenEnabled

    def numPartitions = self.conf.numShufflePartitions

    def strategies: Seq[Strategy] =
      experimental.extraStrategies ++ (
      DataSourceStrategy ::
      DDLStrategy ::
      TakeOrdered ::
      HashAggregation ::
      LeftSemiJoin ::
      HashJoin ::
      InMemoryScans ::
      ParquetOperations ::
      BasicOperators ::
      CartesianProduct ::
      BroadcastNestedLoopJoin :: Nil)

    /**
     * Used to build table scan operators where complex projection and filtering are done using
     * separate physical operators.  This function returns the given scan operator with Project and
     * Filter nodes added only when needed.  For example, a Project operator is only used when the
     * final desired output requires complex expressions to be evaluated or when columns can be
     * further eliminated out after filtering has been done.
     *
     * The `prunePushedDownFilters` parameter is used to remove those filters that can be optimized
     * away by the filter pushdown optimization.
     *
     * The required attributes for both filtering and expression evaluation are passed to the
     * provided `scanBuilder` function so that it can avoid unnecessary column materialization.
     */
    def pruneFilterProject(
        projectList: Seq[NamedExpression],
        filterPredicates: Seq[Expression],
        prunePushedDownFilters: Seq[Expression] => Seq[Expression],
        scanBuilder: Seq[Attribute] => SparkPlan): SparkPlan = {

      val projectSet = AttributeSet(projectList.flatMap(_.references))
      val filterSet = AttributeSet(filterPredicates.flatMap(_.references))
      val filterCondition = prunePushedDownFilters(filterPredicates).reduceLeftOption(And)

      // Right now we still use a projection even if the only evaluation is applying an alias
      // to a column.  Since this is a no-op, it could be avoided. However, using this
      // optimization with the current implementation would change the output schema.
      // TODO: Decouple final output schema from expression evaluation so this copy can be
      // avoided safely.

      if (AttributeSet(projectList.map(_.toAttribute)) == projectSet &&
          filterSet.subsetOf(projectSet)) {
        // When it is possible to just use column pruning to get the right projection and
        // when the columns of this projection are enough to evaluate all filter conditions,
        // just do a scan followed by a filter, with no extra project.
        val scan = scanBuilder(projectList.asInstanceOf[Seq[Attribute]])
        filterCondition.map(Filter(_, scan)).getOrElse(scan)
      } else {
        val scan = scanBuilder((projectSet ++ filterSet).toSeq)
        Project(projectList, filterCondition.map(Filter(_, scan)).getOrElse(scan))
      }
    }
  }

  @transient
  protected[sql] val planner = new SparkPlanner

  @transient
  protected[sql] lazy val emptyResult = sparkContext.parallelize(Seq.empty[Row], 1)

  /**
   * Prepares a planned SparkPlan for execution by inserting shuffle operations as needed.
   */
  @transient
  protected[sql] val prepareForExecution = new RuleExecutor[SparkPlan] {
    val batches =
      Batch("Add exchange", Once, AddExchange(self)) :: Nil
  }

  /**
   * :: DeveloperApi ::
   * The primary workflow for executing relational queries using Spark.  Designed to allow easy
   * access to the intermediate phases of query execution for developers.
   */
  @DeveloperApi
  protected class QueryExecution(val logical: LogicalPlan) {

    lazy val analyzed: LogicalPlan = ExtractPythonUdfs(analyzer(logical))
    lazy val withCachedData: LogicalPlan = cacheManager.useCachedData(analyzed)
    lazy val optimizedPlan: LogicalPlan = optimizer(withCachedData)

    // TODO: Don't just pick the first one...
    lazy val sparkPlan: SparkPlan = {
      SparkPlan.currentContext.set(self)
      planner(optimizedPlan).next()
    }
    // executedPlan should not be used to initialize any SparkPlan. It should be
    // only used for execution.
    lazy val executedPlan: SparkPlan = prepareForExecution(sparkPlan)

    /** Internal version of the RDD. Avoids copies and has no schema */
    lazy val toRdd: RDD[Row] = executedPlan.execute()

    protected def stringOrError[A](f: => A): String =
      try f.toString catch { case e: Throwable => e.toString }

    def simpleString: String =
      s"""== Physical Plan ==
         |${stringOrError(executedPlan)}
      """.stripMargin.trim

    override def toString: String =
      // TODO previously will output RDD details by run (${stringOrError(toRdd.toDebugString)})
      // however, the `toRdd` will cause the real execution, which is not what we want.
      // We need to think about how to avoid the side effect.
      s"""== Parsed Logical Plan ==
         |${stringOrError(logical)}
         |== Analyzed Logical Plan ==
         |${stringOrError(analyzed)}
         |== Optimized Logical Plan ==
         |${stringOrError(optimizedPlan)}
         |== Physical Plan ==
         |${stringOrError(executedPlan)}
         |Code Generation: ${stringOrError(executedPlan.codegenEnabled)}
         |== RDD ==
      """.stripMargin.trim
  }

  /**
   * Parses the data type in our internal string representation. The data type string should
   * have the same format as the one generated by `toString` in scala.
   * It is only used by PySpark.
   */
  protected[sql] def parseDataType(dataTypeString: String): DataType = {
    DataType.fromJson(dataTypeString)
  }

  /**
   * Apply a schema defined by the schemaString to an RDD. It is only used by PySpark.
   */
  protected[sql] def applySchemaToPythonRDD(
      rdd: RDD[Array[Any]],
      schemaString: String): DataFrame = {
    val schema = parseDataType(schemaString).asInstanceOf[StructType]
    applySchemaToPythonRDD(rdd, schema)
  }

  /**
   * Apply a schema defined by the schema to an RDD. It is only used by PySpark.
   */
  protected[sql] def applySchemaToPythonRDD(
      rdd: RDD[Array[Any]],
      schema: StructType): DataFrame = {

    def needsConversion(dataType: DataType): Boolean = dataType match {
      case ByteType => true
      case ShortType => true
      case FloatType => true
      case DateType => true
      case TimestampType => true
      case ArrayType(_, _) => true
      case MapType(_, _, _) => true
      case StructType(_) => true
      case udt: UserDefinedType[_] => needsConversion(udt.sqlType)
      case other => false
    }

    val convertedRdd = if (schema.fields.exists(f => needsConversion(f.dataType))) {
      rdd.map(m => m.zip(schema.fields).map {
        case (value, field) => EvaluatePython.fromJava(value, field.dataType)
      })
    } else {
      rdd
    }

    val rowRdd = convertedRdd.mapPartitions { iter =>
      iter.map { m => new GenericRow(m): Row}
    }

    new DataFrame(this, LogicalRDD(schema.toAttributes, rowRdd)(self))
  }

  /**
   * Returns a Catalyst Schema for the given java bean class.
   */
  protected def getSchema(beanClass: Class[_]): Seq[AttributeReference] = {
    // TODO: All of this could probably be moved to Catalyst as it is mostly not Spark specific.
    val beanInfo = Introspector.getBeanInfo(beanClass)

    // Note: The ordering of elements may differ from when the schema is inferred in Scala.
    //       This is because beanInfo.getPropertyDescriptors gives no guarantees about
    //       element ordering.
    val fields = beanInfo.getPropertyDescriptors.filterNot(_.getName == "class")
    fields.map { property =>
      val (dataType, nullable) = property.getPropertyType match {
        case c: Class[_] if c.isAnnotationPresent(classOf[SQLUserDefinedType]) =>
          (c.getAnnotation(classOf[SQLUserDefinedType]).udt().newInstance(), true)
        case c: Class[_] if c == classOf[java.lang.String] => (StringType, true)
        case c: Class[_] if c == java.lang.Short.TYPE => (ShortType, false)
        case c: Class[_] if c == java.lang.Integer.TYPE => (IntegerType, false)
        case c: Class[_] if c == java.lang.Long.TYPE => (LongType, false)
        case c: Class[_] if c == java.lang.Double.TYPE => (DoubleType, false)
        case c: Class[_] if c == java.lang.Byte.TYPE => (ByteType, false)
        case c: Class[_] if c == java.lang.Float.TYPE => (FloatType, false)
        case c: Class[_] if c == java.lang.Boolean.TYPE => (BooleanType, false)

        case c: Class[_] if c == classOf[java.lang.Short] => (ShortType, true)
        case c: Class[_] if c == classOf[java.lang.Integer] => (IntegerType, true)
        case c: Class[_] if c == classOf[java.lang.Long] => (LongType, true)
        case c: Class[_] if c == classOf[java.lang.Double] => (DoubleType, true)
        case c: Class[_] if c == classOf[java.lang.Byte] => (ByteType, true)
        case c: Class[_] if c == classOf[java.lang.Float] => (FloatType, true)
        case c: Class[_] if c == classOf[java.lang.Boolean] => (BooleanType, true)
        case c: Class[_] if c == classOf[java.math.BigDecimal] => (DecimalType(), true)
        case c: Class[_] if c == classOf[java.sql.Date] => (DateType, true)
        case c: Class[_] if c == classOf[java.sql.Timestamp] => (TimestampType, true)
      }
      AttributeReference(property.getName, dataType, nullable)()
    }
  }
}

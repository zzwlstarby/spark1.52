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

package org.apache.spark.sql.hive.execution

import org.apache.spark.sql.{SQLContext, QueryTest}
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.hive.test.TestHive._
import org.apache.spark.sql.test.SQLTestUtils

/**
 * A set of tests that validates support for Hive Explain command.
  * 验证支持Hive Explain命令的一组测试。
 */
class HiveExplainSuite extends QueryTest with SQLTestUtils {
  override def _sqlContext: SQLContext = TestHive
  private val sqlContext = _sqlContext
  //解释命令扩展命令 explain
  test("explain extended command") {
    checkExistence(sql(" explain   select * from src where key=123 "), true,
                    //物理计划
                   "== Physical Plan ==")
    checkExistence(sql(" explain   select * from src where key=123 "), false,
                    //解析逻辑计划
                   "== Parsed Logical Plan ==",
                    //分析逻辑计划
                   "== Analyzed Logical Plan ==",
                    //优化逻辑计划
                   "== Optimized Logical Plan ==")
    println("===explain begin===")
    sql(" explain   extended select * from src where key=123 ").show(false)
    println("===explain end===")
    checkExistence(sql(" explain   extended select * from src where key=123 "), true,
                    //解析逻辑计划
                   "== Parsed Logical Plan ==",
                    //分析逻辑计划
                   "== Analyzed Logical Plan ==",
                    //优化逻辑计划
                   "== Optimized Logical Plan ==",
                  //优化逻辑计划
                   "== Physical Plan ==",
                  //代码生成
                   "Code Generation")
  }
  //解释create table命令
  test("explain create table command") {
    checkExistence(sql("explain create table temp__b as select * from src limit 2"), true,
                  //物理计划
                   "== Physical Plan ==",
                    //插入Hive表
                   "InsertIntoHiveTable",
                    //
                   "Limit",
                   "src")

    checkExistence(sql("explain extended create table temp__b as select * from src limit 2"), true,
      //物理计划
      "== Parsed Logical Plan ==",
      //分析逻辑计划
      "== Analyzed Logical Plan ==",
      //优化逻辑计划
      "== Optimized Logical Plan ==",
      //物理计划
      "== Physical Plan ==",
      //创建表select
      "CreateTableAsSelect",
      //插入hive表
      "InsertIntoHiveTable",
      //
      "Limit",
      "src")

    checkExistence(sql(
      """
        | EXPLAIN EXTENDED CREATE TABLE temp__b
        | ROW FORMAT SERDE "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"
        | WITH SERDEPROPERTIES("serde_p1"="p1","serde_p2"="p2")
        | STORED AS RCFile
        | TBLPROPERTIES("tbl_p1"="p11", "tbl_p2"="p22")
        | AS SELECT * FROM src LIMIT 2
      """.stripMargin), true,
      //解析逻辑计划
      "== Parsed Logical Plan ==",
      //分析逻辑计划
      "== Analyzed Logical Plan ==",
      //优化逻辑计划
      "== Optimized Logical Plan ==",
      //物理计划
      "== Physical Plan ==",
      //创建表select
      "CreateTableAsSelect",
      "InsertIntoHiveTable",
      "Limit",
      "src")
  }
  //CTAS的EXPLAIN输出仅显示分析的计划 CTAS(create table .. as select)
  test("SPARK-6212: The EXPLAIN output of CTAS only shows the analyzed plan") {
    withTempTable("jt") {
      val rdd = sparkContext.parallelize((1 to 10).map(i => s"""{"a":$i, "b":"str$i"}"""))
      read.json(rdd).registerTempTable("jt")
      val outputs = sql(
        s"""
           |EXPLAIN EXTENDED
           |CREATE TABLE t1
           |AS
           |SELECT * FROM jt
      """.stripMargin).collect().map(_.mkString).mkString

      val shouldContain =
        "== Parsed Logical Plan ==" :: "== Analyzed Logical Plan ==" :: "Subquery" ::
        "== Optimized Logical Plan ==" :: "== Physical Plan ==" ::
        "CreateTableAsSelect" :: "InsertIntoHiveTable" :: "jt" :: Nil
      for (key <- shouldContain) {
        assert(outputs.contains(key), s"$key doesn't exist in result")
      }

      val physicalIndex = outputs.indexOf("== Physical Plan ==")
      assert(!outputs.substring(physicalIndex).contains("Subquery"),
        "Physical Plan should not contain Subquery since it's eliminated by optimizer")
    }
  }
}

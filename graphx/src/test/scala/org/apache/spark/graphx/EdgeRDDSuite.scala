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

package org.apache.spark.graphx

import org.apache.spark.SparkFunSuite
import org.apache.spark.storage.StorageLevel

class EdgeRDDSuite extends SparkFunSuite with LocalSparkContext {

  test("cache, getStorageLevel") {
    // test to see if getStorageLevel returns correct value after caching
    //检查缓存后getStorageLevel是否返回正确的值
    withSpark { sc =>
      val verts = sc.parallelize(List((0L, 0), (1L, 1), (1L, 2), (2L, 3), (2L, 3), (2L, 3)))
      //根据边构建图
      val edges = EdgeRDD.fromEdges(sc.parallelize(List.empty[Edge[Int]]))
      assert(edges.getStorageLevel == StorageLevel.NONE)
      edges.cache()
      assert(edges.getStorageLevel == StorageLevel.MEMORY_ONLY)
    }
  }

}

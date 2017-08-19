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

package org.apache.spark.deploy

import java.io.{File, FileInputStream, FileOutputStream}
import java.util.jar.{JarEntry, JarOutputStream}
import java.util.jar.Attributes.Name
import java.util.jar.Manifest

import scala.collection.mutable.ArrayBuffer

import com.google.common.io.{Files, ByteStreams}

import org.apache.commons.io.FileUtils

import org.apache.ivy.core.settings.IvySettings

import org.apache.spark.TestUtils.{createCompiledClass, JavaSourceFromString}
import org.apache.spark.deploy.SparkSubmitUtils.MavenCoordinate

private[deploy] object IvyTestUtils {

  /**
   * Create the path for the jar and pom from the maven coordinate. Extension should be `jar`
   * or `pom`.
    * 从maven坐标创建jar和pom的路径,扩展应该是`jar`或`pom`
   */
  private[deploy] def pathFromCoordinate(
      artifact: MavenCoordinate,
      prefix: File,
      ext: String,
      useIvyLayout: Boolean): File = {
    val groupDirs = artifact.groupId.replace(".", File.separator)
    val artifactDirs = artifact.artifactId
    val artifactPath =
      if (!useIvyLayout) {
        Seq(groupDirs, artifactDirs, artifact.version).mkString(File.separator)
      } else {
        Seq(artifact.groupId, artifactDirs, artifact.version, ext + "s").mkString(File.separator)
      }
    new File(prefix, artifactPath)
  }

  /** Returns the artifact naming based on standard ivy or maven format.
    * 返回基于标准ivy或maven格式的工件命名
    * */
  private[deploy] def artifactName(
      artifact: MavenCoordinate,
      useIvyLayout: Boolean,
      ext: String = ".jar"): String = {
    if (!useIvyLayout) {
      s"${artifact.artifactId}-${artifact.version}$ext"
    } else {
      s"${artifact.artifactId}$ext"
    }
  }

  /**
    * Returns the directory for the given groupId based on standard ivy or maven format.
    * 根据标准ivy或maven格式返回给定groupId的目录
    * */
  private def getBaseGroupDirectory(artifact: MavenCoordinate, useIvyLayout: Boolean): String = {
    if (!useIvyLayout) {
      artifact.groupId.replace(".", File.separator)
    } else {
      artifact.groupId
    }
  }

  /**
    * Write the contents to a file to the supplied directory.
    * 将内容写入文件到提供的目录
    *  */
  private[deploy] def writeFile(dir: File, fileName: String, contents: String): File = {
    val outputFile = new File(dir, fileName)
    val outputStream = new FileOutputStream(outputFile)
    outputStream.write(contents.toCharArray.map(_.toByte))
    outputStream.close()
    outputFile
  }

  /**
    * Create an example Python file.
    * 创建一个示例Python文件
    * */
  private def createPythonFile(dir: File): File = {
    val contents =
      """def myfunc(x):
        |   return x + 1
      """.stripMargin
    writeFile(dir, "mylib.py", contents)
  }

  /**
    * Create an example R package that calls the given Java class.
    * 创建一个调用给定Java类的示例R包
    * */
  private def createRFiles(
      dir: File,
      className: String,
      packageName: String): Seq[(String, File)] = {
    val rFilesDir = new File(dir, "R" + File.separator + "pkg")
    Files.createParentDirs(new File(rFilesDir, "R" + File.separator + "mylib.R"))
    val contents =
      s"""myfunc <- function(x) {
        |  SparkR:::callJStatic("$packageName.$className", "myFunc", x)
        |}
      """.stripMargin
    val source = writeFile(new File(rFilesDir, "R"), "mylib.R", contents)
    val description =
      """Package: sparkPackageTest
        |Type: Package
        |Title: Test for building an R package
        |Version: 0.1
        |Date: 2015-07-08
        |Author: Burak Yavuz
        |Imports: methods, SparkR
        |Depends: R (>= 3.1), methods, SparkR
        |Suggests: testthat
        |Description: Test for building an R package within a jar
        |License: Apache License (== 2.0)
        |Collate: 'mylib.R'
      """.stripMargin
    val descFile = writeFile(rFilesDir, "DESCRIPTION", description)
    val namespace =
      """import(SparkR)
        |export("myfunc")
      """.stripMargin
    val nameFile = writeFile(rFilesDir, "NAMESPACE", namespace)
    Seq(("R/pkg/R/mylib.R", source), ("R/pkg/DESCRIPTION", descFile), ("R/pkg/NAMESPACE", nameFile))
  }

  /** Create a simple testable Class.
    * 创建一个简单的可测试类
    * */
  private def createJavaClass(dir: File, className: String, packageName: String): File = {
    val contents =
      s"""package $packageName;
        |
        |import java.lang.Integer;
        |
        |public class $className implements java.io.Serializable {
        | public static Integer myFunc(Integer x) {
        |   return x + 1;
        | }
        |}
      """.stripMargin
    val sourceFile =
      new JavaSourceFromString(new File(dir, className).getAbsolutePath, contents)
    createCompiledClass(className, dir, sourceFile, Seq.empty)
  }

  private def createDescriptor(
      tempPath: File,
      artifact: MavenCoordinate,
      dependencies: Option[Seq[MavenCoordinate]],
      useIvyLayout: Boolean): File = {
    if (useIvyLayout) {
      val ivyXmlPath = pathFromCoordinate(artifact, tempPath, "ivy", true)
      Files.createParentDirs(new File(ivyXmlPath, "dummy"))
      createIvyDescriptor(ivyXmlPath, artifact, dependencies)
    } else {
      val pomPath = pathFromCoordinate(artifact, tempPath, "pom", useIvyLayout)
      Files.createParentDirs(new File(pomPath, "dummy"))
      createPom(pomPath, artifact, dependencies)
    }
  }

  /**
    * Helper method to write artifact information in the pom.
    * 帮助者在pom中编写工件信息
    * */
  private def pomArtifactWriter(artifact: MavenCoordinate, tabCount: Int = 1): String = {
    var result = "\n" + "  " * tabCount + s"<groupId>${artifact.groupId}</groupId>"
    result += "\n" + "  " * tabCount + s"<artifactId>${artifact.artifactId}</artifactId>"
    result += "\n" + "  " * tabCount + s"<version>${artifact.version}</version>"
    result
  }

  /**
    * Create a pom file for this artifact.
    * 为此工件创建一个pom文件
    * */
  private def createPom(
      dir: File,
      artifact: MavenCoordinate,
      dependencies: Option[Seq[MavenCoordinate]]): File = {
    var content = """
                    |<?xml version="1.0" encoding="UTF-8"?>
                    |<project xmlns="http://maven.apache.org/POM/4.0.0"
                    |       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    |       xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                    |       http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    |   <modelVersion>4.0.0</modelVersion>
                  """.stripMargin.trim
    content += pomArtifactWriter(artifact)
    content += dependencies.map { deps =>
      val inside = deps.map { dep =>
        "\t<dependency>" + pomArtifactWriter(dep, 3) + "\n\t</dependency>"
      }.mkString("\n")
      "\n  <dependencies>\n" + inside + "\n  </dependencies>"
    }.getOrElse("")
    content += "\n</project>"
    writeFile(dir, artifactName(artifact, false, ".pom"), content.trim)
  }

  /** Helper method to write artifact information in the ivy.xml.
    * 帮助程序在ivy.xml中编写工件信息*/
  private def ivyArtifactWriter(artifact: MavenCoordinate): String = {
    s"""<dependency org="${artifact.groupId}" name="${artifact.artifactId}"
       |            rev="${artifact.version}" force="true"
       |            conf="compile->compile(*),master(*);runtime->runtime(*)"/>""".stripMargin
  }

  /** Create a pom file for this artifact.
    * 为此工件创建一个pom文件*/
  private def createIvyDescriptor(
      dir: File,
      artifact: MavenCoordinate,
      dependencies: Option[Seq[MavenCoordinate]]): File = {
    var content = s"""
        |<?xml version="1.0" encoding="UTF-8"?>
        |<ivy-module version="2.0" xmlns:m="http://ant.apache.org/ivy/maven">
        |  <info organisation="${artifact.groupId}"
        |        module="${artifact.artifactId}"
        |        revision="${artifact.version}"
        |        status="release" publication="20150405222456" />
        |  <configurations>
        |    <conf name="default" visibility="public" description="" extends="runtime,master"/>
        |    <conf name="compile" visibility="public" description=""/>
        |    <conf name="master" visibility="public" description=""/>
        |    <conf name="runtime" visibility="public" description="" extends="compile"/>
        |    <conf name="pom" visibility="public" description=""/>
        |  </configurations>
        |  <publications>
        |     <artifact name="${artifactName(artifact, true, "")}" type="jar" ext="jar"
        |               conf="master"/>
        |  </publications>
      """.stripMargin.trim
    content += dependencies.map { deps =>
      val inside = deps.map(ivyArtifactWriter).mkString("\n")
      "\n  <dependencies>\n" + inside + "\n  </dependencies>"
    }.getOrElse("")
    content += "\n</ivy-module>"
    writeFile(dir, "ivy.xml", content.trim)
  }

  /**
    * Create the jar for the given maven coordinate, using the supplied files.
    * 使用提供的文件创建给定的maven坐标的jar
    * */
  private[deploy] def packJar(
      dir: File,
      artifact: MavenCoordinate,
      files: Seq[(String, File)],
      useIvyLayout: Boolean,
      withR: Boolean,
      withManifest: Option[Manifest] = None): File = {
    val jarFile = new File(dir, artifactName(artifact, useIvyLayout))
    val jarFileStream = new FileOutputStream(jarFile)
    val manifest = withManifest.getOrElse {
      val mani = new Manifest()
      if (withR) {
        val attr = mani.getMainAttributes
        attr.put(Name.MANIFEST_VERSION, "1.0")
        attr.put(new Name("Spark-HasRPackage"), "true")
      }
      mani
    }
    val jarStream = new JarOutputStream(jarFileStream, manifest)

    for (file <- files) {
      val jarEntry = new JarEntry(file._1)
      jarStream.putNextEntry(jarEntry)

      val in = new FileInputStream(file._2)
      ByteStreams.copy(in, jarStream)
      in.close()
    }
    jarStream.close()
    jarFileStream.close()

    jarFile
  }

  /**
   * Creates a jar and pom file, mocking a Maven repository. The root path can be supplied with
   * `tempDir`, dependencies can be created into the same repo, and python files can also be packed
   * inside the jar.
   * 创建一个jar和pom文件,模拟一个Maven仓库。 可以提供根路径tempDir,依赖关系可以被创建到同一个repo中,python文件也可以打包jar
   * @param artifact The maven coordinate to generate the jar and pom for.
   * @param dependencies List of dependencies this artifact might have to also create jars and poms.
   * @param tempDir The root folder of the repository
   * @param useIvyLayout whether to mock the Ivy layout for local repository testing
   * @param withPython Whether to pack python files inside the jar for extensive testing.
   * @return Root path of the repository
   */
  private def createLocalRepository(
      artifact: MavenCoordinate,
      dependencies: Option[Seq[MavenCoordinate]] = None,
      tempDir: Option[File] = None,
      useIvyLayout: Boolean = false,
      withPython: Boolean = false,
      withR: Boolean = false): File = {
    // Where the root of the repository exists, and what Ivy will search in
    //存储库的根目录存在，以及Ivy搜索的内容
    val tempPath = tempDir.getOrElse(Files.createTempDir())
    // Create directory if it doesn't exist
    //创建目录如果不存在
    Files.createParentDirs(tempPath)
    // Where to create temporary class files and such
    //在哪里创建临时类文件等
    val root = new File(tempPath, tempPath.hashCode().toString)
    Files.createParentDirs(new File(root, "dummy"))
    try {
      val jarPath = pathFromCoordinate(artifact, tempPath, "jar", useIvyLayout)
      Files.createParentDirs(new File(jarPath, "dummy"))
      val className = "MyLib"

      val javaClass = createJavaClass(root, className, artifact.groupId)
      // A tuple of files representation in the jar, and the file
      // 在jar中的文件表示的文件和文件
      val javaFile = (artifact.groupId.replace(".", "/") + "/" + javaClass.getName, javaClass)
      val allFiles = ArrayBuffer[(String, File)](javaFile)
      if (withPython) {
        val pythonFile = createPythonFile(root)
        allFiles.append((pythonFile.getName, pythonFile))
      }
      if (withR) {
        val rFiles = createRFiles(root, className, artifact.groupId)
        allFiles.append(rFiles: _*)
      }
      val jarFile = packJar(jarPath, artifact, allFiles, useIvyLayout, withR)
      assert(jarFile.exists(), "Problem creating Jar file")
      val descriptor = createDescriptor(tempPath, artifact, dependencies, useIvyLayout)
      assert(descriptor.exists(), "Problem creating Pom file")
    } finally {
      FileUtils.deleteDirectory(root)
    }
    tempPath
  }

  /**
   * Creates a suite of jars and poms, with or without dependencies, mocking a maven repository.
    * 创建一个jars和poms,有或没有依赖，模拟一个maven仓库。
   * @param artifact The main maven coordinate to generate the jar and pom for.
   * @param dependencies List of dependencies this artifact might have to also create jars and poms.
   * @param rootDir The root folder of the repository (like `~/.m2/repositories`)
   * @param useIvyLayout whether to mock the Ivy layout for local repository testing
   * @param withPython Whether to pack python files inside the jar for extensive testing.
   * @return Root path of the repository. Will be `rootDir` if supplied.
   */
  private[deploy] def createLocalRepositoryForTests(
      artifact: MavenCoordinate,
      dependencies: Option[String],
      rootDir: Option[File],
      useIvyLayout: Boolean = false,
      withPython: Boolean = false,
      withR: Boolean = false): File = {
    val deps = dependencies.map(SparkSubmitUtils.extractMavenCoordinates)
    val mainRepo = createLocalRepository(artifact, deps, rootDir, useIvyLayout, withPython, withR)
    deps.foreach { seq => seq.foreach { dep =>
      createLocalRepository(dep, None, Some(mainRepo), useIvyLayout, withPython = false)
    }}
    mainRepo
  }

  /**
   * Creates a repository for a test, and cleans it up afterwards.
    * 创建一个测试库，然后进行清理。
   *
   * @param artifact The main maven coordinate to generate the jar and pom for.
   * @param dependencies List of dependencies this artifact might have to also create jars and poms.
   * @param rootDir The root folder of the repository (like `~/.m2/repositories`)
   * @param useIvyLayout whether to mock the Ivy layout for local repository testing
   * @param withPython Whether to pack python files inside the jar for extensive testing.
   * @return Root path of the repository. Will be `rootDir` if supplied.
   */
  private[deploy] def withRepository(
      artifact: MavenCoordinate,
      dependencies: Option[String],
      rootDir: Option[File],
      useIvyLayout: Boolean = false,
      withPython: Boolean = false,
      withR: Boolean = false,
      ivySettings: IvySettings = new IvySettings)(f: String => Unit): Unit = {
    val deps = dependencies.map(SparkSubmitUtils.extractMavenCoordinates)
    purgeLocalIvyCache(artifact, deps, ivySettings)
    val repo = createLocalRepositoryForTests(artifact, dependencies, rootDir, useIvyLayout,
      withPython, withR)
    try {
      f(repo.toURI.toString)
    } finally {
      // Clean up
      if (repo.toString.contains(".m2") || repo.toString.contains(".ivy2")) {
        val groupDir = getBaseGroupDirectory(artifact, useIvyLayout)
        FileUtils.deleteDirectory(new File(repo, groupDir + File.separator + artifact.artifactId))
        deps.foreach { _.foreach { dep =>
            FileUtils.deleteDirectory(new File(repo, getBaseGroupDirectory(dep, useIvyLayout)))
          }
        }
      } else {
        FileUtils.deleteDirectory(repo)
      }
      purgeLocalIvyCache(artifact, deps, ivySettings)
    }
  }

  /** Deletes the test packages from the ivy cache
    * 从ivy缓存中删除测试包 */
  private def purgeLocalIvyCache(
      artifact: MavenCoordinate,
      dependencies: Option[Seq[MavenCoordinate]],
      ivySettings: IvySettings): Unit = {
    // delete the artifact from the cache as well if it already exists
    //如果它已经存在,也可以从缓存中删除工件
    FileUtils.deleteDirectory(new File(ivySettings.getDefaultCache, artifact.groupId))
    dependencies.foreach { _.foreach { dep =>
        FileUtils.deleteDirectory(new File(ivySettings.getDefaultCache, dep.groupId))
      }
    }
  }
}

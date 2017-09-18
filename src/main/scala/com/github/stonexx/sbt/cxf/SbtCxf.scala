package com.github.stonexx.sbt.cxf

import java.io.File
import java.net.MalformedURLException

import sbt.Keys._
import sbt._
import sbt.classpath.ClasspathUtilities

import scala.util.Try

object Import {

  val cxf = config("cxf")

  object CxfKeys {

    val wsdl2java = TaskKey[Seq[File]]("cxf-wsdl2java", "Generates java files from wsdls")
    val wsdls     = SettingKey[Seq[Wsdl]]("cxf-wsdls", "wsdls to generate java files from")

  }

  case class Wsdl(key: String, uri: String, args: Seq[String] = Nil) {
    def outputDirectory(basedir: File): File = new File(basedir, key).getAbsoluteFile
  }

}

object SbtCxf extends AutoPlugin {

  override def requires = sbt.plugins.JvmPlugin
  override def trigger = AllRequirements

  val autoImport = Import

  import autoImport._
  import CxfKeys._

  override def projectSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations += cxf,
    version in cxf := "3.1.7",
    libraryDependencies ++= {
      val cxfVersion = (version in cxf).value
      Seq(
      "org.apache.cxf" % "cxf-tools-wsdlto-core" % cxfVersion % cxf,
      "org.apache.cxf" % "cxf-tools-wsdlto-databinding-jaxb" % cxfVersion % cxf,
      "org.apache.cxf" % "cxf-tools-wsdlto-frontend-jaxws" % cxfVersion % cxf
      )
    },
    wsdls := Nil,
    managedClasspath in cxf := Classpaths.managedJars(cxf, (classpathTypes in cxf).value, update.value),
    sourceManaged in cxf := sourceManaged(_ / "cxf").value,
    managedSourceDirectories in Compile ++= wsdls.value.map(_.outputDirectory((sourceManaged in cxf).value) / "main"),
    clean in cxf := IO.delete((sourceManaged in cxf).value),
    wsdl2java := {
      val classpath = (managedClasspath in cxf).value.files
      (for (wsdl <- wsdls.value) yield {
        val output = wsdl.outputDirectory((sourceManaged in cxf).value)
        val mainOutput = output / "main"
        val cacheOutput = output / "cache"

        val wsdlFile = Try(url(wsdl.uri)).map(wsdlUrl => IO.urlAsFile(wsdlUrl).getOrElse {
          val wsdlFile = cacheOutput / "wsdl"
          if (!wsdlFile.exists) IO.download(wsdlUrl, wsdlFile)
          wsdlFile
        }).recover {
          case _: MalformedURLException => file(wsdl.uri)
        }.get

        val cachedFn = FileFunction.cached(cacheOutput, FilesInfo.lastModified, FilesInfo.exists) { _ =>
          val args = Seq("-d", mainOutput.getAbsolutePath) ++ wsdl.args :+ wsdl.uri
          callWsdl2java(streams.value, wsdl.key, mainOutput, args, classpath)
          (mainOutput ** "*.java").get.toSet
        }
        cachedFn(Set(wsdlFile))
      }).flatten
    },
    sourceGenerators in Compile += wsdl2java.taskValue
  )

  private def callWsdl2java(streams: TaskStreams, id: String, output: File, args: Seq[String], classpath: Seq[File]) {
    // TODO: Use the built-in logging mechanism from SBT when I figure out how that work - trygve
    streams.log.info("WSDL: id=" + id + ", args=" + args)

    streams.log.debug("Removing output directory... " + output)
    IO.delete(output)

    streams.log.info("Compiling WSDL...")
    val start = System.currentTimeMillis()
    val classLoader = ClasspathUtilities.toLoader(classpath)
    val WSDLToJava = classLoader.loadClass("org.apache.cxf.tools.wsdlto.WSDLToJava")
    val ToolContext = classLoader.loadClass("org.apache.cxf.tools.common.ToolContext")
    val constructor = WSDLToJava.getConstructor(classOf[Array[String]])
    val run = WSDLToJava.getMethod("run", ToolContext)
    val oldContextClassLoader = Thread.currentThread.getContextClassLoader
    try {
      // to satisfy the jaxb reflection madness classLoader requirements
      Thread.currentThread.setContextClassLoader(classLoader)
      val instance = constructor.newInstance(args.toArray)
      run.invoke(instance, ToolContext.newInstance().asInstanceOf[AnyRef])
    } catch {
      case e: Throwable =>
        // TODO: Figure out if there is a better way to signal errors to SBT.
        // Some of the CXF exceptions contain output that's proper to show to
        // the user as it explains the error that occurred.
        e.printStackTrace()
        throw e
    } finally {
      val end = System.currentTimeMillis()
      streams.log.info("Compiled WSDL in " + (end - start) + "ms.")
      Thread.currentThread.setContextClassLoader(oldContextClassLoader)
    }
  }

}

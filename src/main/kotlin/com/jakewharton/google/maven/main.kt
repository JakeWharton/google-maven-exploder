@file:JvmName("Exploder")

package com.jakewharton.google.maven

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
import okio.buffer
import okio.sink
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.TraceClassVisitor
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringReader
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext

private val baseUrl = HttpUrl.get("https://dl.google.com/dl/android/maven2/")
private val client = OkHttpClient.Builder()
    .addNetworkInterceptor(HttpLoggingInterceptor(::println).setLevel(BASIC))
    .build()
private val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }

suspend fun main() {
  val outputDir = File(System.getProperty("user.dir"))

  val masterIndexXml = client.getString(baseUrl.resolve("master-index.xml")!!)
  val groupIds = parseMasterIndex(masterIndexXml)
      .filter { it.name.startsWith("androidx.") }

  val versionedCoordinates = groupIds
      .mapStupidlyParallel {
        val groupIndexXml = client.getString(it.indexUrl(baseUrl))
        parseGroupIndex(it, groupIndexXml)
      }
      .flatten()

  val versionedCoordinatesWithPackaging = versionedCoordinates
      .mapStupidlyParallel {
        val pomXml = try {
          client.getString(it.pomUrl(baseUrl))
        } catch (e: HttpException) {
          return@mapStupidlyParallel null
        }
        val packaging = parsePomPackaging(pomXml)
        it.copy(classifier = Classifier(packaging))
      }
      .filterNotNull()

  val binaryFiles = versionedCoordinatesWithPackaging.mapStupidlyParallel {
    val binaryFile = outputDir.resolve("${it.groupId}/${it.artifactId}/${it.version}/${it.artifactId}-${it.version}.${it.classifier}")
    binaryFile.parentFile.mkdirs()

    val binaryUrl = it.binaryUrl(baseUrl)
    client.get(binaryUrl).source().use { httpSource ->
      binaryFile.sink().buffer().use { fileSink ->
        httpSource.readAll(fileSink)
      }
    }

    binaryFile
  }

  val fileIoContext = newFixedThreadPoolContext(16, "exploder")
  val classFiles = binaryFiles.mapStupidlyParallel(fileIoContext) { binaryFile ->
    println("Unzipping $binaryFile")

    val outputRoot = binaryFile.parentFile.toPath()
    FileSystems.newFileSystem(binaryFile.toPath(), null).use {
      val inputRoot = it.rootDirectories.single()
      Files.walkFileTree(inputRoot, object : SimpleFileVisitor<Path>() {
        override fun visitFile(inputPath: Path, attrs: BasicFileAttributes): FileVisitResult {
          val relativePath = inputRoot.relativize(inputPath).toString()
          val outputPath = outputRoot.resolve(relativePath)
          Files.createDirectories(outputPath.parent)
          Files.copy(inputPath, outputPath)
          return CONTINUE
        }
      })
    }
    binaryFile.delete()

    val classesJar = outputRoot.resolve("classes.jar")
    if (Files.exists(classesJar)) {
      println("Unzipping $classesJar")

      val classesRoot = outputRoot.resolve("classes/")
      FileSystems.newFileSystem(classesJar, null).use {
        val inputRoot = it.rootDirectories.single()
        Files.walkFileTree(inputRoot, object : SimpleFileVisitor<Path>() {
          override fun visitFile(inputPath: Path, attrs: BasicFileAttributes): FileVisitResult {
            val relativePath = inputRoot.relativize(inputPath).toString()
            val outputPath = classesRoot.resolve(relativePath)
            Files.createDirectories(outputPath.parent)
            Files.copy(inputPath, outputPath)
            return CONTINUE
          }
        })
      }
      Files.delete(classesJar)
    }

    val classes = mutableListOf<File>()
    Files.walkFileTree(outputRoot, object : SimpleFileVisitor<Path>() {
      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (file.fileName.toString().endsWith(".class")) {
          classes += file.toFile()
        }
        return CONTINUE
      }
    })
    classes
  }.flatten()

  classFiles.forEachStupidlyParallel(fileIoContext) { classFile ->
    println("Dumping bytecode $classFile")

    val bytes = classFile.readBytes()
    classFile.resolveSibling(classFile.nameWithoutExtension + ".bytecode").writer().use {
      val reader = ClassReader(bytes)
      reader.accept(TraceClassVisitor(PrintWriter(it)), 0)
    }
  }

  // Shut down OkHttpClient resources so that the JVM can exit cleanly.
  client.dispatcher().executorService().shutdown()
  client.connectionPool().evictAll()
}

private suspend fun OkHttpClient.get(url: HttpUrl): ResponseBody {
  return newCall(Request.Builder().url(url).build()).awaitBody()
}
private suspend fun OkHttpClient.getString(url: HttpUrl): String = get(url).string()

inline class GroupId(val name: String) {
  override fun toString() = name
}

fun GroupId.indexUrl(baseUrl: HttpUrl) = baseUrl.resolve(name.replace('.', '/') + "/group-index.xml")!!
operator fun GroupId.plus(artifactId: ArtifactId) = Coordinate(this, artifactId)

inline class ArtifactId(val name: String) {
  override fun toString() = name
}

data class Coordinate(val groupId: GroupId, val artifactId: ArtifactId) {
  override fun toString() = "$groupId:$artifactId"
}

operator fun Coordinate.plus(version: Version) = VersionedCoordinate(groupId, artifactId, version)

inline class Version(val name: String) {
  override fun toString() = name
}

data class VersionedCoordinate(
  val groupId: GroupId,
  val artifactId: ArtifactId,
  val version: Version,
  val classifier: Classifier = Classifier("jar")
) {
  override fun toString() = listOfNotNull(groupId, artifactId, version, classifier).joinToString(":")
}

fun VersionedCoordinate.pomUrl(baseUrl: HttpUrl) = baseUrl.resolve(groupId.name.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom")!!
fun VersionedCoordinate.binaryUrl(baseUrl: HttpUrl) = baseUrl.resolve(groupId.name.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + "." + classifier)!!

inline class Classifier(val name: String) {
  override fun toString() = name
}

private fun parseMasterIndex(xml: String): List<GroupId> {
  val parser = factory.newPullParser()
  parser.setInput(StringReader(xml))

  val nodes = mutableListOf<GroupId>()
  while (true) {
    when (parser.next()) {
      XmlPullParser.END_DOCUMENT -> return nodes
      XmlPullParser.START_TAG -> {
        if (parser.name != "metadata") {
          nodes += GroupId(parser.name)
        }
      }
    }
  }
}

private fun parseGroupIndex(groupId: GroupId, xml: String): List<VersionedCoordinate> {
  val parser = factory.newPullParser()
  parser.setInput(StringReader(xml))

  val versionedCoordinates = mutableListOf<VersionedCoordinate>()
  while (true) {
    when (parser.next()) {
      XmlPullParser.END_DOCUMENT -> return versionedCoordinates
      XmlPullParser.START_TAG -> {
        if (parser.name != groupId.name) {
          val artifactId = ArtifactId(parser.name)
          val coordinate = groupId + artifactId

          val versions = parser.getAttributeValue(null, "versions").split(',').map(::Version)
          versionedCoordinates.addAll(versions.map { version -> coordinate + version })
        }
      }
    }
  }
}

private fun parsePomPackaging(xml: String): String {
  val parser = factory.newPullParser()
  parser.setInput(StringReader(xml))

  var text = false
  while (true) {
    when (parser.next()) {
      XmlPullParser.END_DOCUMENT -> return "jar"
      XmlPullParser.START_TAG -> {
        if (parser.name == "packaging") {
          text = true
        }
      }
      XmlPullParser.TEXT -> {
        if (text) {
          return parser.text
        }
      }
    }
  }
}

private suspend fun <T> Collection<T>.forEachStupidlyParallel(
    coroutineContext: CoroutineContext = Dispatchers.IO,
    body: suspend (T) -> Unit
) = mapStupidlyParallel(coroutineContext, body)

private suspend fun <T, R> Collection<T>.mapStupidlyParallel(
  coroutineContext: CoroutineContext = Dispatchers.IO,
  body: suspend (T) -> R
): List<R> {
  val array = arrayOfNulls<Any>(size)
  coroutineScope {
    forEachIndexed { index, value ->
      launch(coroutineContext) {
        array[index] = body(value)
      }
    }
  }
  @Suppress("UNCHECKED_CAST") // Array is populated by `body` which returns `R`s
  return array.toList() as List<R>
}

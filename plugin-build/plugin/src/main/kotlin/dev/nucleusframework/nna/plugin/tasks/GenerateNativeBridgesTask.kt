package dev.nucleusframework.nna.plugin.tasks

import dev.nucleusframework.nna.plugin.analysis.PsiParseWorkAction
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Generates native bridges, JVM proxies and GraalVM metadata from the native sources.
 *
 * Cacheable: outputs depend only on the (relative) source contents, the PSI classpath and the
 * configuration inputs. Between non-cached executions, the worker keeps a per-source-file IR cache in
 * [taskCacheDir] so that only changed source files are re-parsed and only changed outputs are rewritten.
 */
@CacheableTask
abstract class GenerateNativeBridgesTask : DefaultTask() {

    @get:Inject
    abstract val taskWorkerExecutor: WorkerExecutor

    @get:Inject
    abstract val taskObjectFactory: ObjectFactory

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val taskNativeSources: ConfigurableFileCollection

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val taskCommonSources: ConfigurableFileCollection

    @get:Classpath
    abstract val taskPsiClasspath: ConfigurableFileCollection

    @get:Input
    abstract val taskLibName: Property<String>

    @get:Input
    abstract val taskJvmPackage: Property<String>

    /**
     * JDK include directory path (containing jni.h), used to write the cinterop def
     * file for the native bridge cinterop.
     */
    @get:Optional
    @get:Input
    abstract val taskJdkIncludeDir: Property<String>

    @get:OutputDirectory
    abstract val taskOutputDir: DirectoryProperty

    /** JNI mode: bridge variant for androidNative targets (built-in `platform.android` bindings). */
    @get:OutputDirectory
    abstract val taskAndroidNativeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val taskJvmOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val taskJvmResourcesDir: DirectoryProperty

    /**
     * Cinterop def file directory (JNI mode: knejni.def pointing at the JDK headers).
     * Only populated in JNI mode; declared optional since FFM mode never writes it.
     */
    @get:Optional
    @get:OutputDirectory
    abstract val taskCinteropDir: DirectoryProperty

    /** Incremental parse state; never stored in the build cache and wiped when outputs come from it. */
    @get:LocalState
    abstract val taskCacheDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val ktFiles = taskNativeSources.orderedKotlinFiles()
        if (ktFiles.isEmpty()) {
            listOf(taskOutputDir, taskAndroidNativeOutputDir, taskJvmOutputDir, taskJvmResourcesDir).forEach {
                it.get().asFile.apply { deleteRecursively(); mkdirs() }
            }
            logger.lifecycle("kne: No Kotlin sources found, skipping."); return
        }
        writeCinteropDefIfNeeded()
        val commonKtFiles = taskCommonSources.orderedKotlinFiles()

        val pluginLocation = PsiParseWorkAction::class.java.protectionDomain?.codeSource?.location
            ?.let { File(it.toURI()) }
        val pluginJar = taskObjectFactory.fileCollection().apply { pluginLocation?.let { from(it) } }

        val workQueue = taskWorkerExecutor.classLoaderIsolation {
            classpath.from(taskPsiClasspath)
            classpath.from(pluginJar)
        }

        workQueue.submit(PsiParseWorkAction::class.java) {
            sourceFiles.from(commonKtFiles + ktFiles)
            commonSourceFiles.from(commonKtFiles)
            libName.set(taskLibName)
            jvmPackage.set(taskJvmPackage)
            nativeBridgesDir.set(taskOutputDir)
            androidNativeBridgesDir.set(taskAndroidNativeOutputDir)
            jvmProxiesDir.set(taskJvmOutputDir)
            jvmResourcesDir.set(taskJvmResourcesDir)
            cacheFile.set(taskCacheDir.file("parse-cache.bin"))
            cacheKey.set(cacheKey(pluginLocation))
        }
    }

    /**
     * Emit the cinterop def file that exposes jni.h to the native bridge
     * (package `dev.nucleusframework.knejni`).
     */
    private fun writeCinteropDefIfNeeded() {
        val cinteropDir = taskCinteropDir.orNull?.asFile ?: return
        val defFile = cinteropDir.resolve("knejni.def")
        val includeDir = File(taskJdkIncludeDir.get())
        val platformDirName = when {
            System.getProperty("os.name").startsWith("Mac") -> "darwin"
            System.getProperty("os.name").startsWith("Windows") -> "win32"
            else -> "linux"
        }
        val opts = buildList {
            // .def file values are parsed with java.util.Properties semantics,
            // which treat '\' as an escape prefix and mangle backslash paths.
            // Emit forward slashes so clang receives the paths verbatim.
            add("-I${includeDir.absolutePath.replace('\\', '/')}")
            add("-I${includeDir.resolve(platformDirName).absolutePath.replace('\\', '/')}")
        }
        val content = buildString {
            appendLine("headers = jni.h")
            appendLine("headerFilter = jni.*")
            appendLine("package = dev.nucleusframework.knejni")
            appendLine()
            appendLine("compilerOpts = ${opts.joinToString(" ")}")
        }
        cinteropDir.mkdirs()
        defFile.writeText(content)
        logger.lifecycle("kne: Wrote JNI cinterop def file: $defFile")
    }

    /**
     * Kotlin files in a stable order: roots in declaration order, files sorted by relative path within
     * each root. The merge order decides which duplicate declaration wins and the package order, so
     * it must not depend on file system traversal order.
     */
    private fun FileCollection.orderedKotlinFiles(): List<File> = files.flatMap { root ->
        when {
            root.isFile -> listOf(root).filter { it.extension == "kt" }
            root.isDirectory -> root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
            else -> emptyList()
        }
    }.distinct()

    /** Identifies the plugin build and PSI classpath that produce the incremental cache. */
    private fun cacheKey(pluginLocation: File?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: Any?) = digest.update("$value\n".toByteArray())
        update(CACHE_FORMAT_VERSION)
        val pluginFiles = when {
            pluginLocation == null -> emptySequence()
            pluginLocation.isDirectory -> pluginLocation.walkTopDown().filter { it.isFile }.sortedBy { it.path }
            else -> sequenceOf(pluginLocation)
        }
        (pluginFiles + taskPsiClasspath.files.asSequence()).forEach { update("${it.path}:${it.length()}:${it.lastModified()}") }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Bump when the cache layout or the parsing semantics change. */
        const val CACHE_FORMAT_VERSION = 2
    }
}

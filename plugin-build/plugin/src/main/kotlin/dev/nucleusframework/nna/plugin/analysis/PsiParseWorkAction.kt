package dev.nucleusframework.nna.plugin.analysis

import dev.nucleusframework.nna.plugin.codegen.GeneratedOutputs
import dev.nucleusframework.nna.plugin.codegen.JniNativeBridgeGenerator
import dev.nucleusframework.nna.plugin.codegen.JniProxyGenerator
import dev.nucleusframework.nna.plugin.codegen.generateGraalVmMetadataJni
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneType
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * Gradle Worker Action that runs PSI parsing + code generation inside an isolated classloader.
 * Both parsing AND generation run in the worker to avoid serialization issues with KneType singletons.
 *
 * The work is incremental across executions thanks to a [ParseCache] stored in [Params.cacheFile]:
 * unchanged source files reuse their cached IR fragments, code generation is skipped when the
 * merged module is unchanged, and only output files whose content changed are rewritten.
 */
abstract class PsiParseWorkAction : WorkAction<PsiParseWorkAction.Params> {

    interface Params : WorkParameters {
        /** Common sources first, then native sources, in deterministic merge order. */
        val sourceFiles: ConfigurableFileCollection
        /** Subset of [sourceFiles] that comes from commonMain. */
        val commonSourceFiles: ConfigurableFileCollection
        val libName: Property<String>
        val jvmPackage: Property<String>
        val nativeBridgesDir: DirectoryProperty
        /** JNI mode: bridge variant importing the built-in `platform.android` JNI bindings (androidNative targets). */
        val androidNativeBridgesDir: DirectoryProperty
        val jvmProxiesDir: DirectoryProperty
        val jvmResourcesDir: DirectoryProperty
        val cacheFile: RegularFileProperty
        /** Identifies the plugin/parser version; a different key invalidates the cache. */
        val cacheKey: Property<String>
    }

    private val logger = Logging.getLogger(PsiParseWorkAction::class.java)

    override fun execute() {
        val commonFiles = parameters.commonSourceFiles.files
        val sources = parameters.sourceFiles.files.map { PsiSourceParser.Source(it, isCommon = it in commonFiles) }
        val libName = parameters.libName.get()
        val cacheFile = parameters.cacheFile.get().asFile
        val cacheKey = parameters.cacheKey.get()
        val previous = ParseCache.read(cacheFile, cacheKey)

        val result = PsiSourceParser().parseIncremental(sources, libName, previous?.files.orEmpty())
        val module = result.module
        logger.lifecycle("kne: Parsed ${result.parsedFiles} of ${result.files.size} source file(s) [PSI]")

        // Auto-detect package from sources if not explicitly configured
        val jvmPackage = parameters.jvmPackage.get().ifEmpty {
            module.packages.firstOrNull() ?: ""
        }

        // The generated code only references kotlinx.coroutines when the API uses suspend/Flow
        // (imports are emitted conditionally). The plugin never injects dependencies, so remind
        // the user to declare coroutines explicitly where the generated code is consumed.
        val usesSuspendOrFlow = module.classes.any { c ->
            c.methods.any { it.isSuspend || it.returnType is KneType.FLOW } ||
                c.companionMethods.any { it.isSuspend || it.returnType is KneType.FLOW }
        } || module.functions.any { it.isSuspend || it.returnType is KneType.FLOW }
        if (usesSuspendOrFlow) {
            logger.lifecycle(
                "kne: API uses suspend/Flow — the generated code needs kotlinx-coroutines; " +
                    "declare it explicitly on the source sets consuming the generated code " +
                    "(nativeMain / jvmMain / androidMain), e.g. \"org.jetbrains.kotlinx:kotlinx-coroutines-core\""
            )
        }

        val roots = mapOf(
            NATIVE_BRIDGES to parameters.nativeBridgesDir.get().asFile,
            ANDROID_BRIDGES to parameters.androidNativeBridgesDir.get().asFile,
            JVM_PROXIES to parameters.jvmProxiesDir.get().asFile,
            JVM_RESOURCES to parameters.jvmResourcesDir.get().asFile,
        )
        val snapshot = previous?.codegen
        val codegen = if (snapshot != null && snapshot.matches(module, jvmPackage) && GeneratedOutputs.hashes(roots) == snapshot.outputHashes) {
            logger.lifecycle("kne: API unchanged, generated sources are up to date")
            snapshot
        } else {
            val sync = GeneratedOutputs.sync(roots, generate(module, jvmPackage))
            logger.lifecycle("kne: Updated ${sync.written} and deleted ${sync.deleted} generated file(s)")
            CodegenSnapshot(module, module.packages.toList(), jvmPackage, sync.hashes)
        }

        try {
            ParseCache(cacheKey, result.files, codegen).write(cacheFile)
        } catch (e: Exception) {
            // The cache is an optimisation only; the next run falls back to a full parse
            logger.warn("kne: Could not write incremental cache: ${e.message}")
            cacheFile.delete()
        }
    }

    /** Generates every output file from the module, grouped by output root. */
    private fun generate(module: KneModule, jvmPackage: String): Map<String, Map<String, String>> {
        val outputs = mapOf(
            NATIVE_BRIDGES to LinkedHashMap<String, String>(),
            ANDROID_BRIDGES to LinkedHashMap(),
            JVM_PROXIES to LinkedHashMap(),
            JVM_RESOURCES to LinkedHashMap(),
        )
        val hasApi = module.classes.isNotEmpty() || module.enums.isNotEmpty() || module.functions.isNotEmpty()
        if (!hasApi) return outputs

        // JNI backend (the only backend): RegisterNatives-based bridges + external fun proxies.
        // The JVM package must be forwarded: proxies are generated flat inside it, so every
        // native-side FindClass / method descriptor is derived from that package.
        // The bridge sources are split: a shared runtime file plus one file per business class.
        JniNativeBridgeGenerator.desktop().generate(module, jvmPackage).forEach { (filename, content) ->
            outputs.getValue(NATIVE_BRIDGES)[filename] = content
        }

        // androidNative targets have no knejni cinterop — they use the JNI bindings shipped
        // with Kotlin/Native's `platform.android`. The generator emits each variant for its
        // own binding surface (import package + function-table struct name); no post-processing.
        JniNativeBridgeGenerator.android().generate(module, jvmPackage).forEach { (filename, content) ->
            outputs.getValue(ANDROID_BRIDGES)[filename] = content
        }

        if (jvmPackage.isNotEmpty()) {
            val pkgPath = jvmPackage.replace('.', '/')
            JniProxyGenerator().generate(module, jvmPackage).forEach { (filename, content) ->
                outputs.getValue(JVM_PROXIES)["$pkgPath/$filename"] = content
            }
            generateGraalVmMetadataJni(outputs.getValue(JVM_RESOURCES), module, jvmPackage)
        }
        return outputs
    }

    /**
     * Generates GraalVM reachability metadata under META-INF/native-image/kne/.
     * Includes reflection config, resource config, and FFM foreign downcall/upcall descriptors.
     */
    private companion object {
        const val NATIVE_BRIDGES = "nativeBridges"
        const val ANDROID_BRIDGES = "androidNativeBridges"
        const val JVM_PROXIES = "jvmProxies"
        const val JVM_RESOURCES = "jvmResources"
    }
}

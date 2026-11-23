package dev.nucleusframework.nna.plugin

import org.gradle.api.provider.Property

/**
 * DSL extension for the kotlin-native-export plugin.
 *
 * Minimal configuration needed — the plugin derives everything else
 * from the nativeMain source set automatically.
 *
 * The plugin is generation-only: it does not build or bundle native libraries.
 * Consumers register their own shared lib and loading configuration.
 */
abstract class KotlinNativeExportExtension {

    /**
     * Library name baked into the generated code: `KneRuntime.LIB_NAME`
     * (the `System.loadLibrary` argument), GraalVM metadata directory, and
     * generated host-object names. Must match the basename of the shared lib
     * the consumer builds (e.g. "myexample" → libmyexample.so).
     */
    abstract val nativeLibName: Property<String>

    /**
     * The Kotlin package that the JVM proxy classes will be generated in.
     * Defaults to matching the native package.
     */
    abstract val nativePackage: Property<String>
}

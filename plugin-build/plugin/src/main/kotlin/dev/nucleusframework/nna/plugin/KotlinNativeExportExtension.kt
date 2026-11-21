package dev.nucleusframework.nna.plugin

import org.gradle.api.provider.Property

/**
 * DSL extension for the kotlin-native-export plugin.
 *
 * Minimal configuration needed — the plugin derives everything else
 * from the nativeMain source set automatically.
 *
 * Convention follows swift-java / swift-export patterns:
 * - Both debug and release variants are built
 * - [buildType] selects which variant the test/run tasks link against
 * - java.library.path includes both debug + release dirs (like swift-java)
 */
abstract class KotlinNativeExportExtension {

    /** Name of the native shared library to produce (e.g. "myexample" → libmyexample.so). */
    abstract val nativeLibName: Property<String>

    /**
     * The Kotlin package that the JVM proxy classes will be generated in.
     * Defaults to matching the native package.
     */
    abstract val nativePackage: Property<String>

    /**
     * Build type for the native shared library that tests and JVM tasks depend on.
     * Accepted values: "debug", "release" (case-insensitive).
     * Defaults to "release" (smaller .so, optimised).
     *
     * Both variants are always configured as binaries on the native target,
     * but only the selected one is linked before tests run.
     */
    abstract val buildType: Property<String>

    /**
     * Bridge backend used between the JVM and the Kotlin/Native shared library.
     * Accepted values: "ffm", "jni" (case-insensitive).
     * Defaults to "ffm".
     *
     * - "ffm": Java FFM (Foreign Function & Memory API, JEP 454). Requires JDK 22+.
     * - "jni": classic Java Native Interface via `external fun` + RegisterNatives.
     *   Works on JDK 8+ and Android JVMs (no `java.lang.foreign` APIs are used).
     *   In this mode the plugin also registers a `jni.h` cinterop against the
     *   local JDK include directory, so the native bridge can talk to `JNIEnv`.
     *
     * Can be overridden per-build with the Gradle property `kne.bridgeMode`
     * (e.g. `-Pkne.bridgeMode=jni`).
     */
    abstract val bridgeMode: Property<String>
}

package dev.nucleusframework.nna.plugin

import dev.nucleusframework.nna.plugin.tasks.GenerateNativeBridgesTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.konan.target.Family
import java.io.File

/**
 * Main entry point for the kotlin-native-export Gradle plugin.
 *
 * Pipeline (mirrors swift-java's build plugin approach):
 *  1. Scan nativeMain sources → extract public Kotlin API
 *  2. Generate @CName bridge code → added to nativeMain compilation
 *  3. Generate JVM proxy classes → added to jvmMain compilation
 *
 * The plugin is generation-only: it registers no native binaries and bundles
 * no native libraries. Consumers register and load their own shared lib
 * (e.g. `target.binaries.sharedLib(...)` plus `java.library.path` / jniLibs
 * configuration).
 */
class KotlinNativeExportPlugin : Plugin<Project> {

    override fun apply(project: Project) {

        val extension = project.extensions.create<KotlinNativeExportExtension>("kotlinNativeExport")

        extension.nativeLibName.convention("nativelib")
        extension.nativePackage.convention("")

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate { configureKmp(project, extension) }
        }
    }

    private fun configureKmp(project: Project, extension: KotlinNativeExportExtension) {
        val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()

        val libName = extension.nativeLibName.get()
        val pkg = extension.nativePackage.get()

        // JNI is the only bridge backend; reject the legacy override with an actionable error.
        if (project.hasProperty(BRIDGE_MODE_PROPERTY)) {
            throw GradleException(
                "JNI is the only bridge backend: remove '-P$BRIDGE_MODE_PROPERTY=...' and any " +
                    "bridgeMode entry from kotlinNativeExport { } — the setting no longer exists."
            )
        }

        // The JNI backend needs the JDK's jni.h for its cinterop.
        val jdkIncludeDir = resolveJdkIncludeDir()
        if (jdkIncludeDir == null) {
            throw GradleException(
                "kotlinNativeExport requires a JDK with include/jni.h " +
                    "(looked at java.home=${System.getProperty("java.home")} and JAVA_HOME=${System.getenv("JAVA_HOME")})"
            )
        }

        // read the jvm target name
        // JVM target should exist otherwise the plugin is of no use
        val jvmTarget = kotlin.targets.filterIsInstance<KotlinJvmTarget>()
            .firstOrNull() ?: throw GradleException("Sorry this plugin required jvm target to work with")

        val jvmMainSourceSetName = jvmTarget.name + "Main"
        val jvmTestSourceSetName = jvmTarget.name + "Test"
        val jvmMainTaskName = jvmMainSourceSetName.replaceFirstChar { it.titlecase() }
        val jvmTaskName = jvmTarget.name.replaceFirstChar { it.titlecase() }

        val nativeBridgesDir = project.layout.buildDirectory.dir("generated/kne/nativeBridges")
        val androidNativeBridgesDir = project.layout.buildDirectory.dir("generated/kne/androidNativeBridges")
        val jvmProxiesDir = project.layout.buildDirectory.dir("generated/kne/jvmProxies")
        val jvmResourcesDir = project.layout.buildDirectory.dir("generated/kne/jvmResources")
        val cinteropDefDir = project.layout.buildDirectory.dir("generated/kne/cinterop")
        val incrementalCacheDir = project.layout.buildDirectory.dir("kne/incremental")

        // Detect the first native target and its source sets.
        // Convention: use src/nativeMain if it exists (shared native source set),
        // otherwise fall back to the first native target's main source set (e.g. linuxX64Main).
        val nativeTarget = kotlin.targets
            .filterIsInstance<KotlinNativeTarget>()
            .firstOrNull()


        val nativeTargetTaskName = nativeTarget?.name?.replaceFirstChar { it.titlecase() } ?: "Native"

        val userNativeSrcDirs = mutableListOf<File>()

        // check if native main dir present
        val nativeMainDir = project.projectDir.resolve("src/nativeMain/kotlin")
        if (nativeMainDir.exists()) userNativeSrcDirs.add(nativeMainDir)

        // if native target present use that too
        if (nativeTarget != null) {
            project.logger.log(
                LogLevel.INFO,
                "NATIVE TARGET FOUND :${nativeTarget.konanTarget.name} ALIAS:${nativeTarget.name}"
            )
            val targetMainDir = project.projectDir.resolve("src/${nativeTarget.name}Main/kotlin")
            if (targetMainDir.exists()) userNativeSrcDirs.add(targetMainDir)
        }

        val userNativeSources = project.files(userNativeSrcDirs)

        // Collect commonMain sources for data class discovery
        val commonMainDir = project.projectDir.resolve("src/commonMain/kotlin")
        val commonSources = project.files(if (commonMainDir.exists()) commonMainDir else null)

        // ── PSI parser classpath (kotlin-compiler-embeddable for isolated Worker classloader) ──
        val knePsiScope = project.configurations.dependencyScope("knePsiScope").get()
        val psiClasspath = project.configurations.resolvable("knePsiClasspath") {
            extendsFrom(knePsiScope)
            description = "Classpath for KNE PSI resolution"
        }
        // using the project kotlin version for embedded kotlin
        val kotlinVersion = project.extensions.findByType<KotlinProjectExtension>()?.coreLibrariesVersion
        project.dependencies.add(knePsiScope.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
        // KotlinPoet for the generators' isolated Worker classloader. The version is hardcoded
        // because the plugin cannot see the consumer's version catalog; keep it in sync with
        // the `kotlinpoet` entry in the root gradle/libs.versions.toml.
        project.dependencies.add(knePsiScope.name, "com.squareup:kotlinpoet:$KOTLINPOET_VERSION")

        // ── Code-generation tasks ────────────────────────────────────────────

        // Single task generates both native bridges and JVM proxies (PSI parsing + codegen in isolated worker)
        val generateBridges = project.tasks.register<GenerateNativeBridgesTask>("generateKneNativeBridges") {
            group = "kne"
            description = "Generate Kotlin/Native JNI bridges and JVM proxy classes"
            taskNativeSources.from(userNativeSources)
            taskCommonSources.from(commonSources)
            taskLibName.set(libName)
            taskJvmPackage.set(pkg)
            jdkIncludeDir?.let { taskJdkIncludeDir.set(it.absolutePath) }
            taskOutputDir.set(nativeBridgesDir)
            taskAndroidNativeOutputDir.set(androidNativeBridgesDir)
            taskJvmOutputDir.set(jvmProxiesDir)
            taskJvmResourcesDir.set(jvmResourcesDir)
            taskCinteropDir.set(cinteropDefDir)
            taskCacheDir.set(incrementalCacheDir)
            taskPsiClasspath.from(psiClasspath)
        }
        // Keep old task name as alias
        project.tasks.register("generateKneJvmProxies") { dependsOn(generateBridges) }

        // NOTE: the plugin deliberately does NOT inject dependencies (e.g. kotlinx-coroutines)
        // into the consumer's source sets — silently added versions are hard to debug and can
        // conflict with the versions the project already uses. The generated code only needs
        // kotlinx-coroutines when the API declares suspend/Flow members (its imports are
        // generated conditionally, and the worker logs a reminder in that case). Consumers
        // declare it explicitly, e.g.:
        //   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:<version>")
        // on nativeMain / jvmMain / androidMain (and on test source sets if their code uses it).

        // ── register the jni.h cinterop used by the native bridge ──
        // The generated native code imports `dev.nucleusframework.knejni.*` to talk to JNIEnv.
        // Desktop native targets compile the bridge against the local JDK's jni.h.
        // androidNative targets are excluded: Kotlin/Native ships JNI bindings built in
        // (`platform.android`), and their bridge variant imports those instead.
        kotlin.targets.filterIsInstance<KotlinNativeTarget>()
            .filter { it.konanTarget.family != Family.ANDROID }
            .forEach { target ->
                val defFilePath = cinteropDefDir.get().asFile.resolve("knejni.def")
                target.compilations.getByName("main") {
                    cinterops {
                        create("knejni") {
                            defFile = defFilePath
                        }
                    }
                }
            }
        // The def file is produced by the generation task
        project.tasks.configureEach {
            if (name.startsWith("cinteropKnejni")) dependsOn(generateBridges)
        }

        // Wire generated bridges. androidNative targets must not see the desktop bridge (they have
        // no knejni cinterop — their variant imports the built-in `platform.android`), so when they
        // exist each family compiles its own variant from its own per-target source set; desktop
        // -only projects keep the old nativeMain-first wiring.
        val androidNativeTargets = kotlin.targets.filterIsInstance<KotlinNativeTarget>()
            .filter { it.konanTarget.family == Family.ANDROID }
        if (androidNativeTargets.isEmpty()) {
            val nativeSourceSet = kotlin.sourceSets.findByName("nativeMain")
                ?: nativeTarget?.let { kotlin.sourceSets.findByName("${it.name}Main") }
            nativeSourceSet?.kotlin?.srcDir(nativeBridgesDir)
        } else {
            kotlin.targets.filterIsInstance<KotlinNativeTarget>()
                .filter { it.konanTarget.family != Family.ANDROID }
                .forEach { target ->
                    kotlin.sourceSets.findByName("${target.name}Main")?.kotlin?.srcDir(nativeBridgesDir)
                }
            androidNativeTargets.forEach { target ->
                kotlin.sourceSets.findByName("${target.name}Main")?.kotlin?.srcDir(androidNativeBridgesDir)
            }
        }

        // Wire generated JVM proxies into jvmMain
        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.kotlin?.srcDir(jvmProxiesDir)
        // AGP Android (ART) target: the same external-fun proxies run there (minSdk 26+;
        // Cleaner). GraalVM metadata stays JVM-desktop-only. The consumer declares coroutines
        // on androidMain themselves — see the no-injection note above.
        kotlin.sourceSets.findByName("androidMain")?.kotlin?.srcDir(jvmProxiesDir)

        // Wire generated GraalVM metadata into JVM resources
        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.resources?.srcDir(jvmResourcesDir)
        // ProcessResources consumes the generated metadata dir, so it must wait for
        // generation (the old native-lib bundling chain satisfied this transitively).
        project.tasks.configureEach {
            if (name == "${jvmTarget.name}ProcessResources" || name == "process${jvmMainTaskName}Resources") {
                dependsOn(generateBridges)
            }
        }

        // Ensure every consumer compilation waits for generation: native compiles, JVM compiles,
        // and AGP Android (androidLibrary) compiles. Lazy so late-registered AGP tasks are covered.
        project.tasks.configureEach {
            val isCompile = name.startsWith("compile") &&
                (name.contains("Native", ignoreCase = true) ||
                    name.contains("Android", ignoreCase = true) ||
                    name.contains(nativeTargetTaskName, ignoreCase = true) ||
                    name == "compileKotlin$jvmTaskName" ||
                    name == "compileKotlin$jvmMainTaskName")
            if (isCompile) dependsOn(generateBridges)
        }
    }

    /**
     * Locates a JDK whose `include/jni.h` exists (needed by the JNI-mode cinterop).
     * Checks the Gradle JVM's java.home first, then the JAVA_HOME environment variable.
     */
    private fun resolveJdkIncludeDir(): File? =
        sequenceOf(
            System.getProperty("java.home")?.let(::File),
            System.getenv("JAVA_HOME")?.let(::File),
        )
            .filterNotNull()
            .map { it.resolve("include") }
            .firstOrNull { it.resolve("jni.h").isFile }

    companion object {
        /** Legacy backend-selection property, rejected at configuration time since the FFM removal. */
        private const val BRIDGE_MODE_PROPERTY = "kne.bridgeMode"

        /** KotlinPoet version resolved into the Worker classpath; keep in sync with the version catalog. */
        private const val KOTLINPOET_VERSION = "2.4.0"
    }
}

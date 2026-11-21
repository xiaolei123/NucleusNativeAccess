package dev.nucleusframework.nna.plugin

import dev.nucleusframework.nna.plugin.tasks.GenerateNativeBridgesTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.io.File

/**
 * Main entry point for the kotlin-native-export Gradle plugin.
 *
 * Pipeline (mirrors swift-java's build plugin approach):
 *  1. Scan nativeMain sources → extract public Kotlin API
 *  2. Generate @CName bridge code → added to nativeMain compilation
 *  3. Generate FFM proxy classes → added to jvmMain compilation
 *  4. Configure sharedLib binary on native targets (both debug + release)
 *  5. Set java.library.path on JVM tasks (both dirs, like swift-java)
 *
 * Convention: both debug and release shared libs are always available as
 * binaries. [KotlinNativeExportExtension.buildType] selects which one the
 * test task links against. java.library.path includes both directories so
 * either variant can be picked up at runtime (same pattern as swift-java).
 */
class KotlinNativeExportPlugin : Plugin<Project> {

    override fun apply(project: Project) {

        val extension = project.extensions.create<KotlinNativeExportExtension>("kotlinNativeExport")

        extension.nativeLibName.convention("nativelib")
        extension.nativePackage.convention("")
        extension.buildType.convention("release")
        extension.bridgeMode.convention("ffm")

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate { configureKmp(project, extension) }
        }
    }

    private fun configureKmp(project: Project, extension: KotlinNativeExportExtension) {
        val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()

        val libName = extension.nativeLibName.get()
        val pkg = extension.nativePackage.get()

        // Bridge backend: extension value, overridable per-build via -Pkne.bridgeMode=...
        val requestedMode = project.findProperty(BRIDGE_MODE_PROPERTY)?.toString() ?: extension.bridgeMode.get()
        val bridgeMode = requestedMode.lowercase()
        if (bridgeMode !in BRIDGE_MODES) {
            throw GradleException(
                "kotlinNativeExport.bridgeMode (or -P$BRIDGE_MODE_PROPERTY) must be one of ${BRIDGE_MODES.joinToString(", ")}, got '$requestedMode'"
            )
        }

        // In JNI mode the native bridge needs the JDK's jni.h for its cinterop.
        val jdkIncludeDir = resolveJdkIncludeDir()
        if (bridgeMode == "jni" && jdkIncludeDir == null) {
            throw GradleException(
                "kotlinNativeExport bridgeMode=jni requires a JDK with include/jni.h " +
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

        // ── Code-generation tasks ────────────────────────────────────────────

        // Single task generates both native bridges and JVM proxies (PSI parsing + codegen in isolated worker)
        val generateBridges = project.tasks.register<GenerateNativeBridgesTask>("generateKneNativeBridges") {
            group = "kne"
            description = if (bridgeMode == "jni") {
                "Generate Kotlin/Native JNI bridges and JVM proxy classes"
            } else {
                "Generate Kotlin/Native bridges and JVM FFM proxies"
            }
            taskNativeSources.from(userNativeSources)
            taskCommonSources.from(commonSources)
            taskLibName.set(libName)
            taskJvmPackage.set(pkg)
            taskBridgeMode.set(bridgeMode)
            jdkIncludeDir?.let { taskJdkIncludeDir.set(it.absolutePath) }
            taskOutputDir.set(nativeBridgesDir)
            taskJvmOutputDir.set(jvmProxiesDir)
            taskJvmResourcesDir.set(jvmResourcesDir)
            taskCinteropDir.set(cinteropDefDir)
            taskCacheDir.set(incrementalCacheDir)
            taskPsiClasspath.from(psiClasspath)
        }
        // Keep old task name as alias
        project.tasks.register("generateKneJvmProxies") { dependsOn(generateBridges) }

        // Required for generated suspend/Flow bridges. Always added by Maven
        // coordinates — never look up the consumer's version catalog. Alias
        // names in libs.versions.toml are project-local and must not be required.
        val coroutinesVersion = DEFAULT_COROUTINES_VERSION

        nativeTarget?.let { target ->
            kotlin.sourceSets.findByName("${target.name}Main")?.dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            }
        }
        kotlin.sourceSets.findByName("nativeMain")?.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }

        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$coroutinesVersion")
        }
        kotlin.sourceSets.findByName(jvmTestSourceSetName)?.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }

        // ── JNI mode: register the jni.h cinterop used by the native bridge ──
        // The generated native code imports `dev.nucleusframework.knejni.*` to talk
        // to JNIEnv; in FFM mode no cinterop is registered and no import is generated.
        if (bridgeMode == "jni") {
            nativeTarget?.let { target ->
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
        }

        // Wire generated bridges into the native source set (try nativeMain, fall back to <target>Main)
        val nativeSourceSet = kotlin.sourceSets.findByName("nativeMain")
            ?: nativeTarget?.let { kotlin.sourceSets.findByName("${it.name}Main") }
        nativeSourceSet?.kotlin?.srcDir(nativeBridgesDir)

        // Wire generated JVM proxies into jvmMain
        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.kotlin?.srcDir(jvmProxiesDir)

        // Wire generated GraalVM metadata into JVM resources
        kotlin.sourceSets.findByName(jvmMainSourceSetName)?.resources?.srcDir(jvmResourcesDir)

        // Ensure compilation waits for generation
        project.tasks.filter { task ->
            val name = task.name
            val isCompileKotlinOrNative = task.name.startsWith("compileKotlin") &&
                (task.name.contains("Native", ignoreCase = true) || task.name.contains(
                    nativeTargetTaskName,
                    ignoreCase = true
                ))

            val isJvmTask = name == "compileKotlin$jvmTaskName" || name == "compileKotlin$jvmMainTaskName"
            isJvmTask || isCompileKotlinOrNative
        }.forEach { task ->
            task.dependsOn(generateBridges)
        }

        // ── Native binaries (both debug + release, like swift-java) ──────────

        kotlin.targets.filterIsInstance<KotlinNativeTarget>().forEach { target ->
            target.binaries.sharedLib(
                namePrefix = libName,
                buildTypes = listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE),
            )
        }

        // ── Bundle native libs (zero-config deployment: JAR resources + jniLibs layout) ──
        //
        // Output layout — doubles as the JAR resource root and an AGP `jniLibs.srcDir` target:
        //   generated/kne/nativeLib/jniLibs/<platform>/<lib>
        // where <platform> is a standard Android ABI name (arm64-v8a, ...) for androidNative
        // targets and {os}-{arch} (win32-x64, darwin-aarch64, ...) for desktop targets.

        val nativeTargets = kotlin.targets.filterIsInstance<KotlinNativeTarget>()
        if (nativeTargets.isNotEmpty()) {
            val libCap = libName.replaceFirstChar { it.uppercaseChar() }
            val nativeLibResourceDir = project.layout.buildDirectory.dir("generated/kne/nativeLib")
            val buildDir = project.layout.buildDirectory

            val copyNativeLib = project.tasks.register("copyKneNativeLib") {
                group = "kne"
                description = "Copy native shared libraries into the jniLibs-layout resource dir for JAR/APK bundling"
                dependsOn(nativeTargets.map { target ->
                    "link${libCap}ReleaseShared${target.name.replaceFirstChar { it.uppercaseChar() }}"
                })
                doLast {
                    // Clean the whole output dir so stale layouts (e.g. entries copied under a
                    // previous layout scheme, or libs of targets that no longer exist) never
                    // leak into the resources/JAR.
                    nativeLibResourceDir.get().asFile.apply { deleteRecursively(); mkdirs() }
                    nativeTargets.forEach { target ->
                        val platform = mapTargetToPlatform(target.konanTarget.name)
                        val releaseDir = buildDir
                            .dir("bin/${target.name}/${libName}ReleaseShared").get().asFile
                        val nativeFile = releaseDir.listFiles()?.firstOrNull { f ->
                            f.extension in listOf("so", "dylib", "dll")
                        } ?: run {
                            logger.lifecycle("kne: No shared library built for ${target.name}, skipping jniLibs/$platform")
                            return@forEach
                        }
                        // AGP's jniLibs requires the Android ABI naming (lib<name>.so); Kotlin/Native
                        // already emits that prefix, normalize just in case.
                        val destName = if (platform in ANDROID_ABIS && !nativeFile.name.startsWith("lib")) {
                            "lib$libName.${nativeFile.extension}"
                        } else {
                            nativeFile.name
                        }
                        val destDir = nativeLibResourceDir.get().asFile.resolve("jniLibs/$platform")
                        destDir.mkdirs()
                        nativeFile.copyTo(destDir.resolve(destName), overwrite = true)
                        logger.lifecycle("kne: Bundled ${nativeFile.name} → jniLibs/$platform/$destName")
                    }
                }
            }

            // Wire native lib resource dir into JVM resources
            kotlin.sourceSets.findByName(jvmMainSourceSetName)?.resources?.srcDir(nativeLibResourceDir)

            // Ensure processResources waits for the native lib copy
            project.tasks.configureEach {
                if (name == "${jvmTarget.name}ProcessResources" || name == "process${jvmMainTaskName}Resources") {
                    dependsOn(copyNativeLib)
                }
            }
        }

        // ── JVM test configuration ───────────────────────────────────────────

        configureJvmTestPaths(project, kotlin, extension)
    }

    /**
     * Map a Kotlin/Native target to its `jniLibs` platform directory name: standard Android
     * ABI names for androidNative targets (so the layout wires straight into AGP's
     * `jniLibs.srcDir`), `{os}-{arch}` for desktop platforms. Accepts both the Kotlin target
     * alias (`androidNativeArm64`) and the konan preset name (`android_arm64`).
     */
    private fun mapTargetToPlatform(targetName: String): String {

        val isArm = targetName.contains("Arm")
        val isArch = targetName.contains("aarch")
        val isArm64 = targetName.contains("arm64") || targetName.contains("Arm64")

        return when {
            targetName.startsWith("androidNativeArm64") || targetName.startsWith("android_arm64") -> "arm64-v8a"
            targetName.startsWith("androidNativeX64") || targetName.startsWith("android_x64") -> "x86_64"
            targetName.startsWith("androidNativeArm32") || targetName.startsWith("android_arm32") -> "armeabi-v7a"
            targetName.startsWith("androidNativeX86") || targetName.startsWith("android_x86") -> "x86"
            targetName.startsWith("linux") -> if (isArm || isArch) "linux-aarch64" else "linux-x64"
            targetName.startsWith("macos") -> if (isArm || isArm64) "darwin-aarch64" else "darwin-x64"
            targetName.startsWith("mingw") -> if (isArm) "win32-arm64" else "win32-x64"
            else -> "unknown"
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

    /**
     * Mirrors swift-java's convention: include both debug + release library
     * directories in java.library.path so either variant is found at runtime.
     * The [KotlinNativeExportExtension.buildType] determines which link task
     * the test task depends on (only that variant is guaranteed to be built).
     */
    private fun configureJvmTestPaths(
        project: Project,
        kotlin: KotlinMultiplatformExtension,
        extension: KotlinNativeExportExtension,
    ) {
        val nativeTarget = kotlin.targets
            .filterIsInstance<KotlinNativeTarget>()
            .firstOrNull() ?: return

        val libName = extension.nativeLibName.get()
        val targetName = nativeTarget.name
        val targetCap = targetName.replaceFirstChar { it.uppercaseChar() }
        val libCap = libName.replaceFirstChar { it.uppercaseChar() }

        // Both variant output dirs (convention: build/bin/<target>/<lib><Variant>Shared/)
        val debugDir = project.layout.buildDirectory
            .dir("bin/$targetName/${libName}DebugShared").get().asFile
        val releaseDir = project.layout.buildDirectory
            .dir("bin/$targetName/${libName}ReleaseShared").get().asFile

        // java.library.path includes both, separated by the platform path separator
        val libraryPath = listOf(releaseDir, debugDir)
            .joinToString(File.pathSeparator) { it.absolutePath.replace("\\", "/") }

        // Link task for the configured build type
        val buildType = extension.buildType.get().replaceFirstChar { it.uppercaseChar() }
        val linkTaskName = "link${libCap}${buildType}Shared$targetCap"

        project.tasks.withType<Test>().configureEach {
            dependsOn(project.tasks.matching { it.name == linkTaskName })
            useJUnitPlatform()
            jvmArgs(
                "-Djava.library.path=$libraryPath",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }

    companion object {
        private const val DEFAULT_COROUTINES_VERSION = "1.11.0"
        private const val BRIDGE_MODE_PROPERTY = "kne.bridgeMode"
        private val BRIDGE_MODES = listOf("ffm", "jni")

        /** Standard Android ABI directory names used under `jniLibs/`. */
        private val ANDROID_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}

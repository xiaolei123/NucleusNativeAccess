import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import java.io.File

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.12.1"
    id("com.android.kotlin.multiplatform.library") version "9.5.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose.hot-reload") version "1.3.0-beta01"
    id("io.github.kdroidfilter.nucleus") version "1.7.2"
    id("dev.nucleusframework.nna")
}

kotlin {
    jvmToolchain(25)

    // Native target: use the real platform name (KMP convention)
    val hostOs = System.getProperty("os.name")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosArm64()
        hostOs == "Linux" -> linuxX64()
        hostOs.startsWith("Windows") -> mingwX64()
        else -> error("Unsupported host OS: $hostOs")
    }

    android {
        namespace = "com.calculator"
        compileSdk = 36
        minSdk = 21
        // targetSdk 是 application 专属，library 不支持 → 删掉
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        androidResources { enable = true }   // 用到 Compose 资源时必须显式开
    }
    // Android device target: the .so lands in jniLibs/arm64-v8a/ for APK bundling
    for (target in listOf(
        androidNativeArm64(),
        androidNativeArm32(),
        androidNativeX64(),
    )) {
        target.binaries {
            sharedLib {
                baseName = "calculator"
            }
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        // The plugin does not inject dependencies: the API uses suspend/Flow, so the generated
        // bridges and proxies reference kotlinx.coroutines — declare it on every consumer.
        nativeMain.dependencies {

        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
                implementation("io.github.kdroidfilter:nucleus.graalvm-runtime:1.7.2")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
            }
        }
    }

    // ── Native lib wiring (portable block) ──────────────────────────────────
    // The nna plugin is generation-only, so the consumer builds and loads its own
    // native lib. This single block does everything: registers the sharedLib,
    // wires JVM tests (java.library.path), and bundles the lib into the JAR for
    // KneRuntime's tier-3 extraction (classpath jniLibs/<platform>/).
    //
    // To reuse in another project: paste into `kotlin { }` where the desktop
    // Kotlin/Native target is in scope as `nativeTarget`, add the imports
    //   org.gradle.api.tasks.Copy, org.gradle.api.tasks.testing.Test,
    //   org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType, java.io.File
    // and change only `lib` (must match kotlinNativeExport.nativeLibName).
    val lib = "calculator"
    val libCap = lib.replaceFirstChar { it.uppercaseChar() }
    val targetCap = nativeTarget.name.replaceFirstChar { it.uppercaseChar() }
    // Platform dir name must match KneRuntime.detectPlatform().
    val platform = when (nativeTarget.konanTarget.name) {
        "macos_arm64" -> "darwin-aarch64"
        "macos_x64" -> "darwin-x64"
        "linux_x64" -> "linux-x64"
        "linux_arm64" -> "linux-aarch64"
        "mingw_x64" -> "win32-x64"
        "mingw_arm64" -> "win32-arm64"
        else -> error("kne wiring: unsupported desktop target '${nativeTarget.konanTarget.name}'")
    }

    nativeTarget.binaries.sharedLib(
        namePrefix = lib,
        buildTypes = listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE),
    )

    tasks.withType<Test>().configureEach {
        dependsOn(tasks.named("link${libCap}ReleaseShared$targetCap"))
        useJUnitPlatform()
        val libDirs = listOf("Debug", "Release").map { variant ->
            layout.buildDirectory.dir("bin/${nativeTarget.name}/$lib${variant}Shared").get().asFile
        }
        jvmArgs(
            "-Djava.library.path=" + libDirs.joinToString(File.pathSeparator) { it.absolutePath },
            "--enable-native-access=ALL-UNNAMED",
        )
    }

    val libBundle = layout.buildDirectory.dir("generated/${lib}NativeLib")
    val copyNativeLib = tasks.register<Copy>("copy${libCap}NativeLib") {
        group = "build"
        description = "Bundle the desktop shared lib as jniLibs/<platform>/ resources"
        dependsOn(tasks.named("link${libCap}ReleaseShared$targetCap"))
        doFirst { libBundle.get().asFile.apply { deleteRecursively(); mkdirs() } }
        from(layout.buildDirectory.dir("bin/${nativeTarget.name}/${lib}ReleaseShared")) {
            include("*.so", "*.dylib", "*.dll") // top level only — skips macOS .dSYM bundles
        }
        into(libBundle.map { it.dir("jniLibs/$platform") })
    }
    sourceSets {
        jvmMain { resources.srcDir(libBundle) }
    }
    tasks.configureEach {
        if (name.endsWith("ProcessResources") && !name.contains("Test", ignoreCase = true)) {
            dependsOn(copyNativeLib)
        }
    }

    // ── Android: bundle androidNative .so into the AAR's jniLibs ────────────
    // androidNative libs are linked by their own tasks, but AGP only packs jniLibs
    // it knows about. Registering the copy as a *generated* source dir makes AGP
    // run it and include jniLibs/<abi>/*.so in the AAR.
    val androidTargets = targets.filterIsInstance<KotlinNativeTarget>()
        .filter { it.konanTarget.family == Family.ANDROID }
    if (androidTargets.isNotEmpty()) {
        val abiOf = mapOf(
            "android_arm64" to "arm64-v8a",
            "android_arm32" to "armeabi-v7a",
            "android_x64" to "x86_64",
            "android_x86" to "x86",
        )
        val copyJniLibs = tasks.register<Copy>("copy${libCap}JniLibs") {
            group = "build"
            description = "Bundle androidNative shared libs as jniLibs/<abi>/ for the AAR"
            androidTargets.forEach { target ->
                val abi = abiOf[target.konanTarget.name]
                    ?: error("kne wiring: unknown Android target '${target.konanTarget.name}'")
                val cap = target.name.replaceFirstChar { it.uppercaseChar() }
                dependsOn(tasks.matching { it.name.startsWith("link") && it.name.endsWith("Shared$cap") })
                from(layout.buildDirectory.dir("bin/${target.name}/releaseShared")) {
                    include("*.so")
                    into(abi)
                }
            }
            into(layout.buildDirectory.dir("generated/${lib}JniLibs"))
        }
        // NB: inside `kotlin { }` the bare `extensions` is the KMP extension's own
        // container, so the AGP extension must be looked up on the project.
        project.extensions.configure<KotlinMultiplatformAndroidComponentsExtension>("androidComponents") {
            onVariants { variant ->
                variant.sources.jniLibs?.addGeneratedSourceDirectory(copyJniLibs) { it.destinationDirectory }
            }
        }
    }
}

composeCompiler {
    targetKotlinPlatforms.set(setOf(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm))
}

kotlinNativeExport {
    nativeLibName = "calculator"
    nativePackage = "com.example.calculator"
}

nucleus.application {
    mainClass = "com.example.calculator.MainKt"
    jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "native-calculator"
        march = "compatibility"
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
            "--enable-native-access=ALL-UNNAMED",
        )
    }

    nativeDistributions {
        targetFormats(TargetFormat.Deb, TargetFormat.Nsis, TargetFormat.Dmg)
        appName = "Native Calculator"
        packageName = "com.example.nativecalculator"
        packageVersion = "1.0.0"
        description = "Compose Desktop calculator powered by Kotlin/Native via JNI"
        homepage = "https://github.com/kdroidFilter/KotlinNativeExport"

        linux {
            debMaintainer = "dev@example.com"
        }

        windows {
            upgradeUuid = "a1b2c3d4-5678-9012-abcd-ef0123456789"
            nsis {
                oneClick = false
                allowElevation = true
                perMachine = true
                allowToChangeInstallationDirectory = true
                createDesktopShortcut = true
                createStartMenuShortcut = true
                runAfterFinish = true
            }
        }

        macOS {
            bundleID = "com.example.nativecalculator"
            appCategory = "public.app-category.utilities"
            dockName = "NativeCalc"
            dmg {
                title = "Native Calculator"
                iconSize = 128
            }
        }
    }
}

import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
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

    // ── Native lib + JVM test wiring (desktop target only; androidNative libs are
    // registered separately above) ──
    // The nna plugin is generation-only: build the shared lib here and point the
    // JVM tests at it via java.library.path (the generated KneRuntime loads it via
    // System.loadLibrary).
    nativeTarget.binaries.sharedLib(
        namePrefix = "calculator",
        buildTypes = listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE),
    )
    val libCap = "calculator".replaceFirstChar { it.uppercaseChar() }
    val targetCap = nativeTarget.name.replaceFirstChar { it.uppercaseChar() }
    val libDirs = listOf("Debug", "Release").map { variant ->
        layout.buildDirectory.dir("bin/${nativeTarget.name}/calculator${variant}Shared").get().asFile
    }
    tasks.withType<Test>().configureEach {
        dependsOn(tasks.named("link${libCap}ReleaseShared$targetCap"))
        useJUnitPlatform()
        jvmArgs(
            "-Djava.library.path=" + libDirs.joinToString(File.pathSeparator) { it.absolutePath },
            "--enable-native-access=ALL-UNNAMED",
        )
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

import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.12.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose.hot-reload") version "1.3.0-beta01"
    id("io.github.kdroidfilter.nucleus") version "1.7.2"
    id("dev.nucleusframework.nna")
}

val hostOs: String = System.getProperty("os.name")
val hostTarget = when {
    hostOs == "Linux" -> "linuxX64"
    hostOs == "Mac OS X" -> "macosArm64"
    hostOs.startsWith("Windows") -> "mingwX64"
    else -> error("Unsupported host OS: $hostOs")
}

kotlin {
    jvmToolchain(25)

    // Suppress expect/actual classes beta warning
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    val nativeTarget = when (hostTarget) {
        "linuxX64" -> linuxX64()
        "macosArm64" -> macosArm64()
        else -> mingwX64 {
            binaries.all {
                linkerOpts("-lole32", "-lshell32", "-luser32", "-lgdi32", "-ladvapi32", "-luxtheme")
            }
        }
    }

    // cinterop with libnotify (real Linux native notifications via D-Bus)
    if (hostOs == "Linux") {
        nativeTarget.compilations["main"].cinterops {
            create("libnotify") {
                defFile(project.file("src/nativeInterop/cinterop/libnotify.def"))
            }
            create("systray") {
                defFile(project.file("src/nativeInterop/cinterop/systray.def"))
            }
            create("gio") {
                defFile(project.file("src/nativeInterop/cinterop/gio.def"))
            }

        }
    }

    // cinterop with Win32 Shell API (tray icon + toast notifications)
    if (hostOs.startsWith("Windows")) {
        nativeTarget.compilations["main"].cinterops {
            create("wintray") {
                defFile(project.file("src/nativeInterop/cinterop/wintray.def"))
            }
        }
    }

    jvm()

    sourceSets {
        // The plugin does not inject dependencies: the native API has suspend/Flow members, so
        // the generated bridge references kotlinx.coroutines — declare it on the native side.
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("io.github.kdroidfilter:nucleus.graalvm-runtime:1.7.2")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    // ── Native lib + JVM test wiring ──
    // The nna plugin is generation-only: build the shared lib here and point the
    // JVM tests at it via java.library.path (the generated KneRuntime loads it via
    // System.loadLibrary).
    nativeTarget.binaries.sharedLib(
        namePrefix = "systeminfo",
        buildTypes = listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE),
    )
    val libCap = "systeminfo".replaceFirstChar { it.uppercaseChar() }
    val targetCap = nativeTarget.name.replaceFirstChar { it.uppercaseChar() }
    val libDirs = listOf("Debug", "Release").map { variant ->
        layout.buildDirectory.dir("bin/${nativeTarget.name}/systeminfo${variant}Shared").get().asFile
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
    nativeLibName = "systeminfo"
    nativePackage = "com.example.systeminfo"
}

nucleus.application {
    mainClass = "com.example.systeminfo.MainKt"
    jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "system-info"
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
        appName = "Native System Info"
        packageName = "com.example.systeminfo"
        packageVersion = "1.0.0"
        description = "System info & notifications via Kotlin/Native + JNI"

        linux { debMaintainer = "dev@example.com" }
        macOS { bundleID = "com.example.systeminfo"; dockName = "SystemInfo" }
    }
}

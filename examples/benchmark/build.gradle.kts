import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import java.io.File

plugins {
    kotlin("multiplatform")
    id("dev.nucleusframework.nna")
}

kotlin {
    jvmToolchain(25)

    val hostOs = System.getProperty("os.name")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosArm64()
        hostOs == "Linux" -> linuxX64()
        hostOs.startsWith("Windows") -> mingwX64()
        else -> error("Unsupported host OS: $hostOs")
    }

    jvm()

    sourceSets {
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
        namePrefix = "benchmark",
        buildTypes = listOf(NativeBuildType.DEBUG, NativeBuildType.RELEASE),
    )
    val libCap = "benchmark".replaceFirstChar { it.uppercaseChar() }
    val targetCap = nativeTarget.name.replaceFirstChar { it.uppercaseChar() }
    val libDirs = listOf("Debug", "Release").map { variant ->
        layout.buildDirectory.dir("bin/${nativeTarget.name}/benchmark${variant}Shared").get().asFile
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

kotlinNativeExport {
    nativeLibName = "benchmark"
    nativePackage = "com.example.benchmark"
}

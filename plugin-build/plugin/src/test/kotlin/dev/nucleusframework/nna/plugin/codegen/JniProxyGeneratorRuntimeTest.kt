package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneModule
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JNI-mode runtime generation through the KotlinPoet pipeline. The heavier end-to-end worker
 * coverage lives in the examples' JNI test suites; this keeps the KotlinPoet path covered
 * for the runtime file the JVM side depends on.
 */
class JniProxyGeneratorRuntimeTest {

    @Test
    fun `cache staleness check compares content, not only size`() {
        val runtime = generateRuntime()

        // A same-size library with different symbols must refresh the cache (issue #29).
        assertTrue(
            "Generated runtime must compare cached bytes with resource bytes",
            runtime.contains("!Files.readAllBytes(target).contentEquals(bytes)"),
        )
    }

    @Test
    fun `runtime registers natives and rejects FFM imports`() {
        val runtime = generateRuntime()
        assertTrue("missing initNatives", runtime.contains("initNatives"))
        assertTrue("no java.lang.foreign import allowed", !runtime.contains("import java.lang.foreign"))
    }

    private fun generateRuntime(): String {
        val module = KneModule(
            libName = "demo",
            packages = setOf("demo"),
            classes = emptyList(),
            dataClasses = emptyList(),
            enums = emptyList(),
            functions = emptyList(),
        )
        return JniProxyGenerator().generate(module, "demo").getValue("KneRuntime.kt")
    }
}

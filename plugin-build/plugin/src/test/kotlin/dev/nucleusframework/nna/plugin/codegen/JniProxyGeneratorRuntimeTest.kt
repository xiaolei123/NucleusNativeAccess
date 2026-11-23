package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneConstructor
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneType
import org.junit.Assert.assertFalse
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

    /**
     * `java.lang.ref.Cleaner` only exists on Android API 33+/JDK 9+. The generated code must use
     * `PhantomReference` + `ReferenceQueue` instead, which has been available since API 1.
     *
     * The runtime KDoc is allowed to *mention* Cleaner to explain the choice, so this asserts on
     * actual usage (import / `Cleaner.create()`), not on the word.
     */
    @Test
    fun `runtime uses PhantomReference reaper instead of Cleaner`() {
        val runtime = generateRuntime()

        assertFalse(
            "java.lang.ref.Cleaner is Android API 33+ only",
            runtime.contains("import java.lang.ref.Cleaner"),
        )
        assertFalse("must not construct a Cleaner", runtime.contains("Cleaner.create()"))
        assertTrue("missing PhantomReference", runtime.contains("PhantomReference"))
        assertTrue("missing ReferenceQueue", runtime.contains("ReferenceQueue"))
        // The thread name carries the library name so a thread dump identifies the module.
        assertTrue("reaper thread must be named after the library", runtime.contains("\"kne-reaper-demo\""))
        assertTrue("missing register entry point", runtime.contains("fun register"))
    }

    /** One reaper thread per module: no per-proxy `Cleaner.create()` in any generated class. */
    @Test
    fun `proxies delegate disposal to the shared runtime`() {
        val files = JniProxyGenerator().generate(module(), "demo")
        val proxy = files.getValue("Calc.kt")

        assertFalse("proxy must not create its own Cleaner", proxy.contains("Cleaner.create()"))
        assertFalse("proxy must not reference java.lang.ref.Cleaner", proxy.contains("Cleaner"))
        assertTrue(
            "proxy must arm disposal through the shared runtime",
            proxy.contains("KneRuntime.register(obj) { lease.close() }"),
        )
        // Capturing `obj` in the action would keep the proxy strongly reachable forever.
        assertFalse(
            "disposal action must not capture the proxy instance",
            proxy.contains("{ obj."),
        )
    }

    private fun generateRuntime(): String =
        JniProxyGenerator().generate(module(), "demo").getValue("KneRuntime.kt")

    private fun module(): KneModule {
        val calc = KneClass(
            simpleName = "Calc",
            fqName = "demo.Calc",
            constructor = KneConstructor(listOf(KneParam("initial", KneType.INT))),
            methods = listOf(
                KneFunction("add", listOf(KneParam("value", KneType.INT)), KneType.INT),
            ),
            properties = emptyList(),
        )
        return KneModule(
            libName = "demo",
            packages = setOf("demo"),
            classes = listOf(calc),
            dataClasses = emptyList(),
            enums = emptyList(),
            functions = emptyList(),
        )
    }
}

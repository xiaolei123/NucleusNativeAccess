package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneEnumEntry
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JNI type-mapping tests: descriptors, external param types, and nullability conventions. */
class JniTypesTest {

    @Test
    fun `primitive descriptors`() {
        assertEquals("I", JniTypes.jniDescriptor(KneType.INT))
        assertEquals("J", JniTypes.jniDescriptor(KneType.LONG))
        assertEquals("D", JniTypes.jniDescriptor(KneType.DOUBLE))
        assertEquals("F", JniTypes.jniDescriptor(KneType.FLOAT))
        assertEquals("Z", JniTypes.jniDescriptor(KneType.BOOLEAN))
        assertEquals("B", JniTypes.jniDescriptor(KneType.BYTE))
        assertEquals("S", JniTypes.jniDescriptor(KneType.SHORT))
    }

    @Test
    fun `string and reference descriptors`() {
        assertEquals("Ljava/lang/String;", JniTypes.jniDescriptor(KneType.STRING))
        assertEquals("[B", JniTypes.jniDescriptor(KneType.BYTE_ARRAY))
        assertEquals("Ljava/lang/Object;", JniTypes.jniDescriptor(KneType.FUNCTION(emptyList(), KneType.UNIT)))
        assertEquals("J", JniTypes.jniDescriptor(KneType.OBJECT("demo.Calc", "Calc"))) // opaque handle
        assertEquals("I", JniTypes.jniDescriptor(KneType.ENUM("demo.Op", "Op"))) // ordinal
    }

    @Test
    fun `array descriptors`() {
        assertEquals("[I", JniTypes.arrayDescriptor(KneType.INT))
        assertEquals("[J", JniTypes.arrayDescriptor(KneType.LONG))
        assertEquals("[Ljava/lang/String;", JniTypes.arrayDescriptor(KneType.STRING))
        assertEquals("[I", JniTypes.arrayDescriptor(KneType.ENUM("demo.Op", "Op")))
        assertEquals("[J", JniTypes.arrayDescriptor(KneType.OBJECT("demo.Calc", "Calc")))
        assertEquals("[Ljava/lang/Object;", JniTypes.arrayDescriptor(KneType.DATA_CLASS("demo.Point", "Point", emptyList())))
    }

    @Test
    fun `nullable primitives travel as widened sentinels`() {
        assertEquals("Long", JniTypes.externalParamType(KneType.NULLABLE(KneType.INT)))
        assertEquals("Long", JniTypes.externalParamType(KneType.NULLABLE(KneType.LONG)))
        assertEquals("Int", JniTypes.externalParamType(KneType.NULLABLE(KneType.SHORT)))
        assertEquals("Int", JniTypes.externalParamType(KneType.NULLABLE(KneType.BYTE)))
        assertEquals("Long", JniTypes.externalParamType(KneType.NULLABLE(KneType.FLOAT)))
        assertEquals("Long", JniTypes.externalParamType(KneType.NULLABLE(KneType.DOUBLE)))
        assertEquals("Int", JniTypes.externalParamType(KneType.NULLABLE(KneType.BOOLEAN)))
    }

    @Test
    fun `nullable strings and enums use natural null or -1`() {
        assertEquals("String?", JniTypes.externalParamType(KneType.NULLABLE(KneType.STRING)))
        assertEquals("Int", JniTypes.externalParamType(KneType.NULLABLE(KneType.ENUM("demo.Op", "Op"))))
        assertEquals("Long", JniTypes.externalParamType(KneType.NULLABLE(KneType.OBJECT("demo.Calc", "Calc"))))
    }

    @Test
    fun `collections map to typed arrays`() {
        assertEquals("IntArray", JniTypes.externalParamType(KneType.LIST(KneType.INT)))
        assertEquals("LongArray", JniTypes.externalParamType(KneType.SET(KneType.LONG)))
        assertEquals("Array<String?>", JniTypes.externalParamType(KneType.LIST(KneType.STRING)))
        assertEquals("IntArray", JniTypes.externalParamType(KneType.LIST(KneType.ENUM("demo.Op", "Op"))))
        assertEquals("LongArray", JniTypes.externalParamType(KneType.LIST(KneType.OBJECT("demo.Calc", "Calc"))))
    }

    @Test
    fun `method descriptor includes leading handle and DC flattening`() {
        val point = KneType.DATA_CLASS(
            "demo.Point", "Point",
            listOf(KneParam("x", KneType.INT), KneParam("label", KneType.STRING)),
        )
        val params = listOf(
            KneParam("self", KneType.LONG),
            KneParam("p", point),
        )
        val descriptor = JniTypes.methodDescriptor(params, KneType.INT, "", leadingHandle = true)
        // handle(J) + self(J) + x(I) + label(String) → (JJILjava/lang/String;)I
        assertEquals("(JJILjava/lang/String;)I", descriptor)
    }

    @Test
    fun `nullable DC param adds isNull flag`() {
        val point = KneType.DATA_CLASS("demo.Point", "Point", listOf(KneParam("x", KneType.INT)))
        val params = listOf(KneParam("p", KneType.NULLABLE(point)))
        val descriptor = JniTypes.methodDescriptor(params, KneType.UNIT, "", leadingHandle = false)
        // isNull(I) + x(I) → (II)V
        assertEquals("(II)V", descriptor)
    }

    @Test
    fun `data class return materialises as its JVM class`() {
        val point = KneType.DATA_CLASS("demo.Point", "Point", emptyList())
        assertEquals("Ldemo/Point;", JniTypes.returnDescriptor(point))
        assertEquals("Ldemo/Point;", JniTypes.returnDescriptor(KneType.NULLABLE(point)))
        assertEquals("I", JniTypes.returnDescriptor(KneType.INT))
        assertEquals("V", JniTypes.returnDescriptor(KneType.UNIT))
    }
}

/** JNI proxy generator snapshot tests (mirroring the FFM generator tests). */
class JniProxyGeneratorTest {

    private fun generate(): Map<String, String> =
        JniProxyGenerator().generate(
            KneModule(
                libName = "demo",
                packages = setOf("demo"),
                classes = emptyList(),
                dataClasses = emptyList(),
                enums = listOf(
                    KneEnum(
                        simpleName = "Operation",
                        fqName = "demo.Operation",
                        entries = listOf(KneEnumEntry("ADD"), KneEnumEntry("SUBTRACT")),
                    ),
                ),
                functions = emptyList(),
            ),
            "demo",
        )

    @Test
    fun `runtime uses System loadLibrary and no FFM imports`() {
        val files = generate()
        val runtime = files.getValue("KneRuntime.kt")
        assertTrue("missing System.loadLibrary:\n\$runtime", runtime.contains("System.loadLibrary"))
        assertTrue("missing initNatives:\n\$runtime", runtime.contains("initNatives"))
        assertTrue("missing Lease:\n\$runtime", runtime.contains("class Lease"))
        assertFalse("found FFM import:\n\$runtime", runtime.contains("import java.lang.foreign"))
        assertFalse("found MethodHandle:\n\$runtime", runtime.contains("import java.lang.invoke.MethodHandle"))
    }

    @Test
    fun `enum proxy is verbatim`() {
        val files = generate()
        val enum = files.getValue("Operation.kt")
        assertTrue(enum.contains("enum class Operation {"))
        assertTrue(enum.contains("ADD,"))
        assertFalse(enum.contains("external"))
    }

    @Test
    fun `exception class matches the shared shape`() {
        val files = generate()
        val exception = files.getValue("KotlinNativeException.kt")
        // KotlinPoet renders the ctor parameter on its own line with an explicit `public`.
        assertTrue(exception.contains("class KotlinNativeException("))
        assertTrue(exception.contains("message: String"))
        assertTrue(exception.contains(": RuntimeException(message)"))
    }
}

package dev.nucleusframework.nna.plugin.codegen.poet

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the KotlinPoet type mapping for every [KneType] variant: public API view, external-fun
 * wire view, and the expanded param shapes (flattened data classes, split maps).
 */
class KnePoetTypesTest {

    private val poet = KnePoetTypes(jvmPackage = "com.example.api")

    private val calc = KneType.OBJECT(fqName = "demo.sub.Calc", simpleName = "Calc")
    private val point = KneType.DATA_CLASS(
        fqName = "demo.sub.Point",
        simpleName = "Point",
        fields = listOf(KneParam("x", KneType.INT), KneParam("y", KneType.INT)),
    )

    // ── public API view ───────────────────────────────────────────────────────

    @Test
    fun `public primitives map to kotlin class names`() {
        assertEquals(KnePoetTypes.INT, poet.publicType(KneType.INT))
        assertEquals(KnePoetTypes.LONG, poet.publicType(KneType.LONG))
        assertEquals(KnePoetTypes.DOUBLE, poet.publicType(KneType.DOUBLE))
        assertEquals(KnePoetTypes.FLOAT, poet.publicType(KneType.FLOAT))
        assertEquals(KnePoetTypes.BOOLEAN, poet.publicType(KneType.BOOLEAN))
        assertEquals(KnePoetTypes.BYTE, poet.publicType(KneType.BYTE))
        assertEquals(KnePoetTypes.SHORT, poet.publicType(KneType.SHORT))
        assertEquals(KnePoetTypes.STRING, poet.publicType(KneType.STRING))
        assertEquals(KnePoetTypes.UNIT, poet.publicType(KneType.UNIT))
        assertEquals(KnePoetTypes.BYTE_ARRAY, poet.publicType(KneType.BYTE_ARRAY))
    }

    @Test
    fun `public object enum and data class types map into the jvm package`() {
        assertEquals(ClassName("com.example.api", "Calc"), poet.publicType(calc))
        assertEquals(
            ClassName("com.example.api", "Color"),
            poet.publicType(KneType.ENUM("demo.sub.Color", "Color")),
        )
        assertEquals(ClassName("com.example.api", "Point"), poet.publicType(point))
        assertEquals(
            ClassName("com.example.api", "Api"),
            poet.publicType(KneType.INTERFACE("demo.sub.Api", "Api")),
        )
    }

    @Test
    fun `public collections map parameterized`() {
        assertEquals("kotlin.collections.List<kotlin.Int>", poet.publicType(KneType.LIST(KneType.INT)).toString())
        assertEquals("kotlin.collections.Set<kotlin.String>", poet.publicType(KneType.SET(KneType.STRING)).toString())
        assertEquals(
            "kotlin.collections.Map<kotlin.String, kotlin.Int>",
            poet.publicType(KneType.MAP(KneType.STRING, KneType.INT)).toString(),
        )
        assertEquals(
            "kotlinx.coroutines.flow.Flow<kotlin.Int>",
            poet.publicType(KneType.FLOW(KneType.INT)).toString(),
        )
        assertEquals(
            "kotlin.collections.List<com.example.api.Point>",
            poet.publicType(KneType.LIST(point)).toString(),
        )
    }

    @Test
    fun `public nullable types render with the question mark`() {
        assertEquals("kotlin.Int?", poet.publicType(KneType.NULLABLE(KneType.INT)).toString())
        assertEquals("kotlin.String?", poet.publicType(KneType.NULLABLE(KneType.STRING)).toString())
        assertEquals("com.example.api.Calc?", poet.publicType(KneType.NULLABLE(calc)).toString())
        assertEquals(
            "kotlin.collections.List<kotlin.Int>?",
            poet.publicType(KneType.NULLABLE(KneType.LIST(KneType.INT))).toString(),
        )
    }

    @Test
    fun `public function types render as lambdas`() {
        val fn = KneType.FUNCTION(listOf(KneType.INT, KneType.STRING), KneType.UNIT)
        assertEquals("(kotlin.Int, kotlin.String) -> kotlin.Unit", poet.publicType(fn).toString())
    }

    @Test
    fun `public nullable function types render with outer parentheses`() {
        val fn = KneType.FUNCTION(listOf(KneType.INT), KneType.UNIT)
        val rendered: TypeName = poet.publicType(KneType.NULLABLE(fn))
        assertEquals("((kotlin.Int) -> kotlin.Unit)?", rendered.toString())
    }

    // ── external-fun wire view ────────────────────────────────────────────────

    @Test
    fun `external wire types widen nullables with sentinels`() {
        // Nullable objects/enums travel as handles/ordinals — no nullability on the wire.
        assertEquals(KnePoetTypes.LONG, poet.externalParamType(KneType.NULLABLE(calc)))
        assertEquals(KnePoetTypes.INT, poet.externalParamType(KneType.NULLABLE(KneType.ENUM("d.E", "E"))))
        // Nullable primitives widen: boolean->int, int/long->long, short/byte->int, float/double->long.
        assertEquals(KnePoetTypes.INT, poet.externalParamType(KneType.NULLABLE(KneType.BOOLEAN)))
        assertEquals(KnePoetTypes.LONG, poet.externalParamType(KneType.NULLABLE(KneType.INT)))
        assertEquals(KnePoetTypes.LONG, poet.externalParamType(KneType.NULLABLE(KneType.LONG)))
        assertEquals(KnePoetTypes.INT, poet.externalParamType(KneType.NULLABLE(KneType.SHORT)))
        assertEquals(KnePoetTypes.INT, poet.externalParamType(KneType.NULLABLE(KneType.BYTE)))
        assertEquals(KnePoetTypes.LONG, poet.externalParamType(KneType.NULLABLE(KneType.FLOAT)))
        assertEquals(KnePoetTypes.LONG, poet.externalParamType(KneType.NULLABLE(KneType.DOUBLE)))
        // Nullable strings/arrays keep JNI's natural NULL.
        assertEquals("kotlin.String?", poet.externalParamType(KneType.NULLABLE(KneType.STRING)).toString())
        assertEquals("kotlin.ByteArray?", poet.externalParamType(KneType.NULLABLE(KneType.BYTE_ARRAY)).toString())
        assertEquals(
            "kotlin.IntArray?",
            poet.externalParamType(KneType.NULLABLE(KneType.LIST(KneType.INT))).toString(),
        )
        // Dispatchers are opaque objects.
        assertEquals("kotlin.Any?", poet.externalParamType(KneType.FUNCTION(emptyList(), KneType.UNIT)).toString())
    }

    @Test
    fun `array param types follow the element kind`() {
        assertEquals(KnePoetTypes.INT_ARRAY, poet.arrayParamType(KneType.INT))
        assertEquals(KnePoetTypes.LONG_ARRAY, poet.arrayParamType(calc))
        assertEquals(KnePoetTypes.BYTE_ARRAY, poet.arrayParamType(KneType.BYTE))
        assertEquals("kotlin.Array<kotlin.String?>", poet.arrayParamType(KneType.STRING).toString())
        assertEquals("kotlin.Array<kotlin.ByteArray?>", poet.arrayParamType(KneType.BYTE_ARRAY).toString())
        assertEquals("kotlin.Array<kotlin.Any?>", poet.arrayParamType(point).toString())
        assertEquals("kotlin.IntArray", poet.arrayParamType(KneType.ENUM("d.E", "E")).toString())
    }

    @Test
    fun `expand external params flattens data classes with isNull prefix`() {
        val params = poet.expandExternalParams("p", KneType.NULLABLE(point))
        assertEquals(
            listOf(
                "p_isNull" to KnePoetTypes.INT,
                "p_x" to KnePoetTypes.INT,
                "p_y" to KnePoetTypes.INT,
            ),
            params,
        )
    }

    @Test
    fun `expand external params flattens nested data classes`() {
        val outer = KneType.DATA_CLASS(
            fqName = "demo.sub.Outer",
            simpleName = "Outer",
            fields = listOf(KneParam("inner", point)),
        )
        val params = poet.expandExternalParams("o", outer)
        assertEquals(
            listOf(
                "o_inner_x" to KnePoetTypes.INT,
                "o_inner_y" to KnePoetTypes.INT,
            ),
            params,
        )
    }

    @Test
    fun `expand external params expands maps into keys and values`() {
        val map = KneType.MAP(KneType.STRING, KneType.INT)
        assertEquals(
            listOf(
                "m_keys" to poet.arrayParamType(KneType.STRING),
                "m_values" to KnePoetTypes.INT_ARRAY,
            ),
            poet.expandExternalParams("m", map),
        )
        val nullableMap = KneType.NULLABLE(map)
        assertEquals(
            listOf(
                "m_keys" to poet.arrayParamType(KneType.STRING).copy(nullable = true),
                "m_values" to KnePoetTypes.INT_ARRAY.copy(nullable = true),
            ),
            poet.expandExternalParams("m", nullableMap),
        )
    }

    @Test
    fun `expand external params passes plain values through`() {
        assertEquals(listOf("n" to KnePoetTypes.INT), poet.expandExternalParams("n", KneType.INT))
        assertEquals(listOf("b" to KnePoetTypes.BYTE_ARRAY), poet.expandExternalParams("b", KneType.BYTE_ARRAY))
    }

    // ── cinterop references ───────────────────────────────────────────────────

    @Test
    fun `pointer helpers render cinterop pointers`() {
        assertEquals("kotlinx.cinterop.CPointer<kotlinx.cinterop.IntVar>?", KneCinteropTypes.pointerTo(KneCinteropTypes.INT_VAR).toString())
        assertEquals("kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>", KneCinteropTypes.nonNullPointerTo(KneCinteropTypes.BYTE_VAR).toString())
    }

    @Test
    fun `binding flavors expose their cinterop surfaces`() {
        assertEquals("dev.nucleusframework.knejni", JniBindingFlavor.DESKTOP.packageName)
        assertEquals("JNINativeInterface_", JniBindingFlavor.DESKTOP.jniInterfaceStruct)
        assertEquals("dev.nucleusframework.knejni.JNIEnvVar", JniBindingFlavor.DESKTOP.envVar.toString())
        assertEquals("dev.nucleusframework.knejni.JNINativeInterface_", JniBindingFlavor.DESKTOP.jniInterface.toString())

        assertEquals("platform.android", JniBindingFlavor.ANDROID.packageName)
        assertEquals("JNINativeInterface", JniBindingFlavor.ANDROID.jniInterfaceStruct)
        assertEquals("platform.android.JNIEnvVar", JniBindingFlavor.ANDROID.envVar.toString())
        assertEquals("platform.android.JNINativeInterface", JniBindingFlavor.ANDROID.jniInterface.toString())
        assertEquals("platform.android.jobject", JniBindingFlavor.ANDROID.jobject.toString())
    }
}

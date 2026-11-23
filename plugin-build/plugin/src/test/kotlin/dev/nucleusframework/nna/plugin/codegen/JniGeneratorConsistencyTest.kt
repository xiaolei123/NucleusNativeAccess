package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneConstructor
import dev.nucleusframework.nna.plugin.ir.KneDataClass
import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneEnumEntry
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneProperty
import dev.nucleusframework.nna.plugin.ir.KneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-generator consistency: what the native side statically binds via `@CName` must exist as an
 * `external fun` on the JVM proxy class the symbol was derived from.
 *
 * Regressions this guards:
 *  - member-name casing drift (`_nativeGetCurrent` vs `_nativeGetcurrent`),
 *  - binding on the Kotlin nested name (`pkg/Outer/Inner`) instead of the flat proxy class,
 *  - mangling drift between the generator's `jniSymbol` and the JNI spec rule the JVM applies,
 *  - statically binding a class that declares no such method (enums, sealed bases, `List<DC>`
 *    element builders), which turns into an `UnsatisfiedLinkError` on first call.
 */
class JniGeneratorConsistencyTest {

    private val pointType = KneType.DATA_CLASS(
        fqName = "demo.Point",
        simpleName = "Point",
        fields = listOf(KneParam("x", KneType.INT), KneParam("y", KneType.INT)),
    )

    private fun module(): KneModule {
        val calc = KneClass(
            simpleName = "Calc",
            fqName = "demo.Calc",
            constructor = KneConstructor(listOf(KneParam("initial", KneType.INT))),
            methods = listOf(
                KneFunction("add", listOf(KneParam("value", KneType.INT)), KneType.INT),
                KneFunction("sumPoints", listOf(KneParam("points", KneType.LIST(pointType))), KneType.INT),
                KneFunction("point", listOf(KneParam("p", KneType.NULLABLE(pointType))), KneType.INT),
            ),
            properties = listOf(
                KneProperty("current", KneType.INT, mutable = false),
                KneProperty("label", KneType.STRING, mutable = true),
            ),
            companionMethods = listOf(
                KneFunction("create", listOf(KneParam("value", KneType.INT)), KneType.INT),
            ),
            companionProperties = listOf(
                KneProperty("instanceCount", KneType.INT, mutable = true),
            ),
        )
        val nested = KneClass(
            simpleName = "Calc_Adder",
            fqName = "demo.Calc.Adder",
            constructor = KneConstructor(emptyList()),
            methods = listOf(KneFunction("plus", listOf(KneParam("v", KneType.INT)), KneType.INT)),
            properties = emptyList(),
        )
        val sealedBase = KneClass(
            simpleName = "Outcome",
            fqName = "demo.Outcome",
            constructor = KneConstructor(emptyList()),
            methods = emptyList(),
            properties = emptyList(),
            isSealed = true,
            sealedSubclasses = listOf("demo.Outcome.Ok"),
        )
        return KneModule(
            libName = "demo",
            packages = setOf("demo"),
            classes = listOf(calc, nested, sealedBase),
            dataClasses = listOf(KneDataClass("Point", "demo.Point", listOf(KneParam("x", KneType.INT), KneParam("y", KneType.INT)))),
            enums = listOf(KneEnum("Operation", "demo.Operation", listOf(KneEnumEntry("ADD"), KneEnumEntry("SUB")))),
            functions = listOf(
                KneFunction("topLevel", listOf(KneParam("v", KneType.INT)), KneType.INT),
                KneFunction(
                    "extended",
                    listOf(KneParam("v", KneType.INT)),
                    KneType.INT,
                    isExtension = true,
                    receiverType = KneType.OBJECT("demo.Calc", "Calc"),
                ),
            ),
        )
    }

    @Test
    fun `every registered native exists as an external fun on the resolved class`() {
        val native = JniNativeBridgeGenerator.desktop().generate(module(), "demo").values.joinToString("\n")
        val proxies = JniProxyGenerator().generate(module(), "demo")

        // The bootstrap's own dlsym entry is not a native binding: exclude it from the parity set.
        val symbols = parseCnameSymbols(native) - setOf(expectedSymbol("demo/KneRuntime", "initNatives"))
        val declared = parseJvmNatives(proxies)

        assertTrue("native generator emitted no static symbols", symbols.isNotEmpty())

        // Every JVM external must have a static symbol on the same flat class, and vice versa.
        val expected = declared.flatMap { (jvmClass, methodNames) ->
            methodNames.map { expectedSymbol("demo/$jvmClass", it) }
        }.toSet()
        val missing = expected - symbols
        assertTrue(
            "JVM externals without a static symbol (UnsatisfiedLinkError on call): $missing",
            missing.isEmpty(),
        )
        val unbound = symbols - expected
        assertTrue(
            "static symbols with no JVM external (dead entries): $unbound",
            unbound.isEmpty(),
        )
    }

    @Test
    fun `registered classes use the flat proxy class name`() {
        val native = JniNativeBridgeGenerator.desktop().generate(module(), "demo").values.joinToString("\n")
        val symbols = parseCnameSymbols(native)

        // `demo.Calc.Adder` binds on the flat proxy class `demo.Calc_Adder`: the class part of
        // the symbol escapes the flat name's underscore (`Calc_1Adder`).
        assertTrue(
            "nested class must be addressed by its flattened proxy name",
            symbols.contains(expectedSymbol("demo/Calc_Adder", "_nativePlus")),
        )
        assertTrue(
            "no symbol may carry an unescaped underscore (nested-path or raw-name mistake)",
            symbols.none { it.contains("Calc_Adder") },
        )
        assertTrue(
            "proxies live in the configured package",
            symbols.all { it.startsWith("Java_demo_") },
        )
    }

    @Test
    fun `classes without jvm natives are only cached, never registered`() {
        val native = JniNativeBridgeGenerator.desktop().generate(module(), "demo").values.joinToString("\n")
        val symbols = parseCnameSymbols(native)

        // Enums and sealed bases have no `external fun` on the JVM side: a static symbol for them
        // would never resolve, so the bootstrap must only cache their class refs.
        assertTrue("enum must not be statically bound", symbols.none { it.startsWith("Java_demo_Operation") })
        assertTrue("sealed base must not be statically bound", symbols.none { it.startsWith("Java_demo_Outcome") })

        // They still need a cached class ref for NewObjectA / ordinal lookups.
        assertTrue("enum class ref must still be cached", native.contains("\"demo/Operation\""))
    }

    @Test
    fun `bootstrap rethrows instead of silently swallowing failures`() {
        val native = JniNativeBridgeGenerator.desktop().generate(module(), "demo").values.joinToString("\n")
        assertTrue("bootstrap must rethrow registration failures", native.contains("throw e"))
        assertTrue("bootstrap must print what failed", native.contains("BOOTSTRAP FAILED"))
    }

    @Test
    fun `nullable data class param ships its isNull flag with 1 meaning null`() {
        val native = JniNativeBridgeGenerator.desktop().generate(module(), "demo").values.joinToString("\n")
        val proxies = JniProxyGenerator().generate(module(), "demo")

        // Native reads the flag as `_isNull`, so the JVM side must send 1 for null.
        assertTrue(
            "nullable DC call site must send 1 when the value is null",
            proxies.getValue("Calc.kt").contains("if (p == null) 1 else 0"),
        )
        assertTrue(
            "nullable DC body must branch on the flag",
            native.contains("if (p_isNull != 0) null else"),
        )
    }

    @Test
    fun `function-returning methods get an invoke bridge on both sides`() {
        val module = module()
        val withLambda = module.copy(
            classes = module.classes.map { cls ->
                if (cls.simpleName != "Calc") cls
                else cls.copy(
                    methods = cls.methods + KneFunction(
                        "notifier",
                        emptyList(),
                        KneType.FUNCTION(listOf(KneType.INT), KneType.UNIT),
                    ),
                )
            },
        )
        val native = JniNativeBridgeGenerator.desktop().generate(withLambda, "demo").values.joinToString("\n")
        val proxies = JniProxyGenerator().generate(withLambda, "demo")

        val id = JniTypes.functionId(KneType.FUNCTION(listOf(KneType.INT), KneType.UNIT))
        assertTrue(
            "invoke bridge must be statically bound on KneNativeBindings",
            parseCnameSymbols(native).contains(expectedSymbol("demo/KneNativeBindings", "_nativeInvokeFn$id")),
        )
        assertTrue("JVM side must declare the invoke bridge", proxies.getValue("KneRuntime.kt").contains("external fun _nativeInvokeFn$id"))
        // The JVM proxy wraps the returned handle in a real lambda instead of casting it.
        assertTrue(
            "returned lambda must be wrapped, not cast",
            proxies.getValue("Calc.kt").contains("_nativeInvokeFn$id(_r, _p0)"),
        )
    }

    @Test
    fun `extension functions register on the flat KneExt with matching parameter order`() {
        val native = JniNativeBridgeGenerator.desktop().generate(module(), "demo").values.joinToString("\n")
        val proxies = JniProxyGenerator().generate(module(), "demo")

        assertTrue("extension host must be the flat KneExt class", native.contains("\"demo/KneExt\""))
        assertTrue(
            "extension entry must be statically bound on KneExt",
            parseCnameSymbols(native).contains(expectedSymbol("demo/KneExt", "_nativeExtExtended")),
        )
        assertTrue(
            "JVM declaration must take the receiver handle first",
            proxies.getValue("Extensions.kt").contains("_nativeExtExtended(receiverHandle: Long, v: Int)"),
        )
    }

    @Test
    fun `android variant registers the same natives against the platform android surface`() {
        val desktop = JniNativeBridgeGenerator.desktop().generate(module(), "demo").values.joinToString("\n")
        val android = JniNativeBridgeGenerator.android().generate(module(), "demo").values.joinToString("\n")

        // Import surface follows the binding flavor (per-type imports, no star imports).
        assertTrue("desktop must bind the knejni cinterop surface", desktop.contains("import dev.nucleusframework.knejni."))
        assertTrue("desktop must not touch the android surface", !desktop.contains("platform.android"))
        assertTrue("android must bind the platform.android surface", android.contains("import platform.android."))
        assertTrue("android must not touch the knejni surface", !android.contains("knejni"))

        // Function-table struct: the NDK header has no trailing underscore.
        assertTrue(desktop.contains("JNINativeInterface_"))
        assertTrue("NDK struct has no trailing underscore", !android.contains("JNINativeInterface_"))
        assertTrue(android.contains("import platform.android.JNINativeInterface\n"))

        // Registration parity: both variants statically bind exactly the same natives on the same
        // classes, so one JVM proxy set serves both native families.
        assertEquals(parseCnameSymbols(desktop), parseCnameSymbols(android))
    }

    @Test
    fun `bridge sources are split per class with the runtime in native_bridges`() {
        val files = JniNativeBridgeGenerator.desktop().generate(module(), "demo")

        // Common runtime file: helpers, top-level function entries and the bootstrap.
        val common = files.getValue(JniNativeBridgeGenerator.COMMON_FILE_BASE + ".kt")
        assertTrue("bootstrap must live in the common file", common.contains("fun native_initNatives"))
        assertTrue("runtime helpers must live in the common file", common.contains("fun _nativeFns("))
        assertTrue("top-level function entries live in the common file", common.contains("native_topLevel("))

        // Per-class files hold only that class's entry points; the extension fn goes with the receiver.
        val calcFile = files.getValue("native_Calc_bridges.kt")
        assertTrue("class ctor entries live in the class file", calcFile.contains("native_Calc_new("))
        assertTrue("extension entries live in the receiver's file", calcFile.contains("native_Calc_ext_extended("))
        assertTrue("class files must not host the bootstrap", !calcFile.contains("native_initNatives"))
        assertTrue("class files must not host runtime helpers", !calcFile.contains("fun _nativeFns("))

        // Nested classes get their own file under the flattened simple name.
        val nestedFile = files.getValue("native_Calc_Adder_bridges.kt")
        assertTrue("nested class entries live in their own file", nestedFile.contains("native_Calc_Adder_plus("))

        // The common file hosts the bootstrap entry, the top-level function entries (<Lib> host)
        // and the shared KneNativeBindings natives; every class member's @CName lives in its
        // per-class file.
        val commonSymbols = parseCnameSymbols(common)
        assertTrue(
            "common file must host only bootstrap, top-level and KneNativeBindings entries",
            commonSymbols.all {
                it == expectedSymbol("demo/KneRuntime", "initNatives") ||
                    it.startsWith("Java_demo_Demo__1") ||
                    it.startsWith("Java_demo_KneNativeBindings__1")
            },
        )
        assertTrue(
            "class entries are statically bound in the class file",
            parseCnameSymbols(calcFile).contains(expectedSymbol("demo/Calc", "_nativeNew")),
        )
    }

    // ── parsing helpers ──────────────────────────────────────────────────────

    /** All `@CName("...")` static JNI symbols emitted in [native]. */
    private fun parseCnameSymbols(native: String): Set<String> =
        Regex("""@CName\("([^"]+)"\)""").findAll(native).map { it.groupValues[1] }.toSet()

    /**
     * The JNI static symbol for a native on [classPath] named [jvmName], derived here straight
     * from the spec's "Identify a Native Method" rule: every `_` inside a name escapes to `_1`,
     * package separators and the class↔method boundary stay bare `_`. Mirrors the generator's
     * `jniSymbol` — a divergence between the two is exactly what these tests exist to catch.
     */
    private fun expectedSymbol(classPath: String, jvmName: String): String {
        val slash = classPath.lastIndexOf('/')
        val pkg = if (slash == -1) "" else classPath.substring(0, slash).replace('/', '_')
        val simple = if (slash == -1) classPath else classPath.substring(slash + 1)
        val cls = if (pkg.isEmpty()) simple.replace("_", "_1") else "${pkg}_${simple.replace("_", "_1")}"
        return "Java_${cls}_${jvmName.replace("_", "_1")}"
    }

    /** JVM class simple name → the `_native*` external funs its generated file declares. */
    private fun parseJvmNatives(proxies: Map<String, String>): Map<String, Set<String>> {
        val externalRegex = Regex("""external fun (_native\w+)""")
        val out = LinkedHashMap<String, MutableSet<String>>()
        proxies.forEach { (fileName, content) ->
            val names = externalRegex.findAll(content).map { it.groupValues[1] }.toMutableSet()
            if (names.isEmpty()) return@forEach
            val primary = fileName.removeSuffix(".kt")
            // Some generated files host the externals inside a differently-named object:
            //  - KneRuntime.kt hosts KneNativeBindings (dispose / cancel / credit / invoke bridges);
            //    only the latter are registered, so keep them under their real class name.
            //  - Extensions.kt hosts the flat `KneExt` object the native side FindClass-resolves.
            val target = when (primary) {
                "KneRuntime" -> "KneNativeBindings"
                "Extensions" -> "KneExt"
                else -> primary
            }
            out.getOrPut(target) { mutableSetOf() }.addAll(names)
        }
        return out
    }
}

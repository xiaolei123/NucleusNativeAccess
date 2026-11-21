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
 * Cross-generator consistency: what the native side registers via `RegisterNatives` must exist as an
 * `external fun` on the JVM proxy, on the class `FindClass` actually resolved.
 *
 * Regressions this guards:
 *  - member-name casing drift (`_kneGetCurrent` vs `_kneGetcurrent`),
 *  - registering on the Kotlin nested name (`pkg/Outer/Inner`) instead of the flat proxy class,
 *  - natives registered on a class that declares no such method (enums, sealed bases, `List<DC>`
 *    element builders), which makes `RegisterNatives` fail and leaves every other native unbound.
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
        val native = JniNativeBridgeGenerator().generate(module(), "demo")
        val proxies = JniProxyGenerator().generate(module(), "demo")

        val registrations = parseRegistrations(native)
        val declared = parseJvmNatives(proxies)

        assertTrue("native generator registered nothing", registrations.isNotEmpty())

        registrations.forEach { (className, methodNames) ->
            val jvmClass = className.substringAfterLast('/')
            val jvmMethods = declared[jvmClass]
            assertTrue("no JVM proxy declares class $jvmClass (native FindClass target $className)", jvmMethods != null)
            val missing = methodNames - jvmMethods!!
            assertTrue(
                "$className registers natives the JVM side does not declare: $missing",
                missing.isEmpty(),
            )
            val unregistered = jvmMethods - methodNames
            assertTrue(
                "$jvmClass declares natives that are never registered (UnsatisfiedLinkError): $unregistered",
                unregistered.isEmpty(),
            )
        }
    }

    @Test
    fun `registered classes use the flat proxy class name`() {
        val native = JniNativeBridgeGenerator().generate(module(), "demo")
        val targets = parseRegistrations(native).keys

        assertTrue("nested class must be addressed by its flattened proxy name", targets.any { it.endsWith("/Calc_Adder") })
        assertTrue("nested class must not be addressed by the Kotlin nested path", targets.none { it.contains("/Calc/") })
        assertTrue("proxies live in the configured package", targets.all { it.startsWith("demo/") || it.startsWith("java/") })
    }

    @Test
    fun `classes without jvm natives are only cached, never registered`() {
        val native = JniNativeBridgeGenerator().generate(module(), "demo")
        val targets = parseRegistrations(native).keys

        // Enums and sealed bases have no `external fun` on the JVM side, so binding anything to
        // them would fail RegisterNatives and abort the whole bootstrap.
        assertTrue("enum must not be a RegisterNatives target", targets.none { it.endsWith("/Operation") })
        assertTrue("sealed base must not be a RegisterNatives target", targets.none { it.endsWith("/Outcome") })

        // They still need a cached class ref for NewObjectA / ordinal lookups.
        assertTrue("enum class ref must still be cached", native.contains("\"demo/Operation\""))
    }

    @Test
    fun `bootstrap rethrows instead of silently swallowing failures`() {
        val native = JniNativeBridgeGenerator().generate(module(), "demo")
        assertTrue("bootstrap must rethrow registration failures", native.contains("throw e"))
        assertTrue("bootstrap must print what failed", native.contains("BOOTSTRAP FAILED"))
    }

    @Test
    fun `nullable data class param ships its isNull flag with 1 meaning null`() {
        val native = JniNativeBridgeGenerator().generate(module(), "demo")
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
        val native = JniNativeBridgeGenerator().generate(withLambda, "demo")
        val proxies = JniProxyGenerator().generate(withLambda, "demo")

        val id = JniTypes.functionId(KneType.FUNCTION(listOf(KneType.INT), KneType.UNIT))
        assertTrue("native must register the invoke bridge", native.contains("\"_kneInvokeFn$id\""))
        assertTrue("JVM side must declare the invoke bridge", proxies.getValue("KneRuntime.kt").contains("external fun _kneInvokeFn$id"))
        // The JVM proxy wraps the returned handle in a real lambda instead of casting it.
        assertTrue(
            "returned lambda must be wrapped, not cast",
            proxies.getValue("Calc.kt").contains("_kneInvokeFn$id(_r, _p0)"),
        )
    }

    @Test
    fun `extension functions register on the flat KneExt with matching parameter order`() {
        val native = JniNativeBridgeGenerator().generate(module(), "demo")
        val proxies = JniProxyGenerator().generate(module(), "demo")

        assertTrue("extension host must be the flat KneExt class", native.contains("\"demo/KneExt\""))
        assertTrue(
            "native registration must take the receiver handle first",
            parseRegistrations(native).getValue("demo/KneExt").contains("_kneExtExtended"),
        )
        assertTrue(
            "JVM declaration must take the receiver handle first",
            proxies.getValue("Extensions.kt").contains("_kneExtExtended(receiverHandle: Long, v: Int)"),
        )
    }

    // ── parsing helpers ──────────────────────────────────────────────────────

    /** `FindClass` class → the native method names its `RegisterNatives` block binds. */
    private fun parseRegistrations(native: String): Map<String, Set<String>> {
        val classRegex = Regex("""FindClass!!\.invoke\(env, "([^"]+)"""")
        val methodRegex = Regex("""methods\[\d+]\.name = "([^"]+)"""")
        val out = LinkedHashMap<String, MutableSet<String>>()
        // The bootstrap emits one `run { ... }` block per registered class.
        native.split(Regex("""(?:^|\s)run \{""")).forEach { block ->
            val className = classRegex.find(block)?.groupValues?.get(1) ?: return@forEach
            val names = methodRegex.findAll(block).map { it.groupValues[1] }.toMutableSet()
            if (names.isNotEmpty()) out.getOrPut(className) { mutableSetOf() }.addAll(names)
        }
        return out
    }

    /** JVM class simple name → the `_kne*` external funs its generated file declares. */
    private fun parseJvmNatives(proxies: Map<String, String>): Map<String, Set<String>> {
        val externalRegex = Regex("""external fun (_kne\w+)""")
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

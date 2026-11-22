package dev.nucleusframework.nna.plugin.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.joinToCode
import dev.nucleusframework.nna.plugin.codegen.poet.JniBindingFlavor
import dev.nucleusframework.nna.plugin.codegen.poet.KNE_INDENT
import dev.nucleusframework.nna.plugin.codegen.poet.KneCinteropTypes
import dev.nucleusframework.nna.plugin.codegen.poet.KnePoetTypes as PT
import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneProperty
import dev.nucleusframework.nna.plugin.ir.KneType

/**
 * Generates Kotlin/Native JNI bridge code structured through KotlinPoet, consumed through the
 * binding flavor's cinterop ([JniBindingFlavor.DESKTOP] `dev.nucleusframework.knejni` over the
 * local JDK's jni.h, [JniBindingFlavor.ANDROID] `platform.android` over the NDK header — both
 * compiled with `language = C++`, so `env.pointed.value.pointed.Xxx` mirrors the familiar
 * Android JNI access pattern).
 *
 * Unlike the FFM backend there is exactly ONE exported symbol: the JNI bootstrap
 * `Java_<mangled>_KneRuntime_initNatives` (`@CName`). Every other native function is
 * registered on the JVM side via `RegisterNatives` during bootstrap, bound to the
 * generated JVM `external fun` declarations by name + signature.
 *
 * Marshalling summary:
 *  - object handles stay opaque `Long` (StableRef)
 *  - strings cross as jstring (modified UTF-8 codec included below)
 *  - collections cross as jarray types (Get/SetXxxArrayRegion); Maps as parallel key/value arrays
 *  - data class returns / suspend / flow payloads are materialised on the JVM via NewObjectA
 *  - data class params are flattened into individual field params
 *  - exceptions: native Kotlin errors are rethrown via ThrowNew(KotlinNativeException);
 *    JVM exceptions thrown inside a callback stay pending and propagate when the native
 *    method returns (original type preserved) — a private sentinel exception unwinds the
 *    native frames without touching JNIEnv while the exception is pending
 *
 * KotlinPoet notes the emitters rely on:
 *  - imports are resolved from `%T`/`%M` references; the flavor decides which package the JNI
 *    surface types (`JNIEnvVar`, `JNINativeInterface_`, `jobject`, …) are imported from, which
 *    replaces the pre-KotlinPoet `replaceFirst` post-processing of the desktop variant.
 *  - `allocArray`/`alloc` resolve as members of the `memScoped` receiver and need no import;
 *    the top-level cinterop extensions used inside bodies (`toCPointer`, `asStableRef`,
 *    `asCPointer`, `cstr`, `pointed`, `reinterpret`, `memScoped`, `staticCFunction`) are
 *    referenced through `%M` so explicit imports replace the previous star imports.
 *  - a few generated strings that previously used `$` templates now use concatenation
 *    (`"GetJavaVM failed: " + rc`) — identical behaviour, escape-free templates.
 */
class JniNativeBridgeGenerator private constructor(private val flavor: JniBindingFlavor) {

    companion object {
        /** Elements a native flow may emit ahead of the JVM collector (credit-based backpressure). */
        const val FLOW_INITIAL_CREDITS = 64

        /** File base name (no extension) of the shared runtime bridge file. */
        const val COMMON_FILE_BASE = "kne_bridges"

        /** Desktop bridge variant: compiled against the generated knejni cinterop. */
        fun desktop(): JniNativeBridgeGenerator = JniNativeBridgeGenerator(JniBindingFlavor.DESKTOP)

        /** androidNative bridge variant: compiled against Kotlin/Native's built-in Android JNI bindings. */
        fun android(): JniNativeBridgeGenerator = JniNativeBridgeGenerator(JniBindingFlavor.ANDROID)

        private val UBYTE = ClassName("kotlin", "UByte")
        private val CHAR = ClassName("kotlin", "Char")
        private val EMPTY_CODE: CodeBlock = CodeBlock.builder().build()
        private val THREAD_LOCAL = ClassName("kotlin.native.concurrent", "ThreadLocal")
        private val C_NAME = ClassName("kotlin.experimental", "CName")
        private val EXPERIMENTAL_FOREIGN_API = ClassName("kotlinx.cinterop", "ExperimentalForeignApi")
        private val EXPERIMENTAL_NATIVE_API = ClassName("kotlin.experimental", "ExperimentalNativeApi")
        private val SUPPRESS = ClassName("kotlin", "Suppress")
        private val OPT_IN = ClassName("kotlin", "OptIn")
        private val JOB = ClassName("kotlinx.coroutines", "Job")
        private val COROUTINE_SCOPE = ClassName("kotlinx.coroutines", "CoroutineScope")
        private val M_DISPATCHERS = MemberName("kotlinx.coroutines", "Dispatchers")
        private val M_LAUNCH = MemberName("kotlinx.coroutines", "launch")
        private val COROUTINE_START = ClassName("kotlinx.coroutines", "CoroutineStart")
        private val CANCELLATION_EXCEPTION = ClassName("kotlinx.coroutines", "CancellationException")
        private val CHANNEL = ClassName("kotlinx.coroutines.channels", "Channel")
        private val RUNTIME_EXCEPTION = ClassName("kotlin", "RuntimeException")

        // cinterop members referenced inside generated bodies
        private val M_MEM_SCOPED = MemberName("kotlinx.cinterop", "memScoped")
        private val M_TO_C_POINTER = MemberName("kotlinx.cinterop", "toCPointer")
        private val M_AS_STABLE_REF = MemberName("kotlinx.cinterop", "asStableRef")
        private val M_TO_LONG = MemberName("kotlinx.cinterop", "toLong")
        private val M_CSTR = MemberName("kotlinx.cinterop", "cstr")
        private val M_POINTED = MemberName("kotlinx.cinterop", "pointed")
        private val M_REINTERPRET = MemberName("kotlinx.cinterop", "reinterpret")
        private val M_STATIC_C_FUNCTION = MemberName("kotlinx.cinterop", "staticCFunction")

        private val C_ARRAY_POINTER = ClassName("kotlinx.cinterop", "CArrayPointer")
        private val M_ALLOC = MemberName("kotlinx.cinterop", "alloc")
        private val M_ALLOC_ARRAY = MemberName("kotlinx.cinterop", "allocArray")
        private val M_PTR = MemberName("kotlinx.cinterop", "ptr")
        private val M_VALUE = MemberName("kotlinx.cinterop", "value")
        private val M_INVOKE = MemberName("kotlinx.cinterop", "invoke")
        private val M_GET = MemberName("kotlinx.cinterop", "get")
        private val M_SET = MemberName("kotlinx.cinterop", "set")
        private val C_POINTER = KneCinteropTypes.C_POINTER
        private val C_POINTER_VAR = ClassName("kotlinx.cinterop", "CPointerVar")
        private val BYTE_VAR = KneCinteropTypes.BYTE_VAR
        private val SHORT_VAR = KneCinteropTypes.SHORT_VAR
        private val INT_VAR = KneCinteropTypes.INT_VAR
        private val LONG_VAR = KneCinteropTypes.LONG_VAR
        private val FLOAT_VAR = KneCinteropTypes.FLOAT_VAR
        private val DOUBLE_VAR = KneCinteropTypes.DOUBLE_VAR
        private val UBYTE_VAR = ClassName("kotlinx.cinterop", "UByteVar")
        private val C_OPAQUE = ClassName("kotlinx.cinterop", "COpaque")
        private val STABLE_REF = KneCinteropTypes.STABLE_REF
    }

    // Registration tables filled while emitting, rendered by the bootstrap at the end.
    private data class RegMethod(val jvmName: String, val sig: String, val fnName: String)
    private data class RegClass(val className: String, val methods: MutableList<RegMethod>)
    private val registrations = LinkedHashMap<String, RegClass>()

    /** Classes the native side only needs a global ref for (no natives to bind). */
    private val cachedClasses = LinkedHashSet<String>()

    private fun registerMethod(className: String, jvmName: String, sig: String, fnName: String) {
        registrations.getOrPut(className) { RegClass(className, mutableListOf()) }
            .methods.add(RegMethod(jvmName, sig, fnName))
    }

    /** Caches a class ref at bootstrap time without registering any native method on it. */
    private fun cacheClass(className: String) {
        cachedClasses.add(className)
    }

    /**
     * JVM package the proxy classes are generated into (set by [generate]).
     *
     * Native type references keep the Kotlin dotted `fqName` (nested classes stay nested), but the
     * JVM side is generated flat: every proxy/data class/enum lives directly in this package under
     * its [simpleName]. Registration and every JNI descriptor must therefore use this mapping, or
     * `FindClass` / `RegisterNatives` / `GetMethodID` all miss.
     */
    private var jvmPackage: String = ""

    /** JVM binary class path (`a/b/Outer_Inner`) for a declaration with [simpleName]. */
    private fun jvmClassPath(simpleName: String): String {
        val pkg = jvmPackage.replace('.', '/')
        return if (pkg.isEmpty()) simpleName else "$pkg/$simpleName"
    }

    /** JVM binary class path a reference to [this] type resolves to (objects/enums/DCs are flat). */
    private fun KneType.jvmClassPath(): String = when (this) {
        is KneType.OBJECT -> jvmClassPath(simpleName)
        is KneType.INTERFACE -> jvmClassPath(simpleName)
        is KneType.ENUM -> jvmClassPath(simpleName)
        is KneType.DATA_CLASS -> jvmClassPath(simpleName)
        else -> error("not a class type: $this")
    }

    /** Kotlin ClassName of a user type as it is compiled on the native side (nested names kept). */
    private fun kotlinType(fqName: String): ClassName = ClassName.bestGuess(fqName)

    /** Public-style Kotlin type as it compiles on the NATIVE side (nested user names kept). */
    private fun nativePublic(type: KneType): TypeName = when (type) {
        KneType.INT -> PT.INT
        KneType.LONG -> PT.LONG
        KneType.DOUBLE -> PT.DOUBLE
        KneType.FLOAT -> PT.FLOAT
        KneType.BOOLEAN -> PT.BOOLEAN
        KneType.BYTE -> PT.BYTE
        KneType.SHORT -> PT.SHORT
        KneType.STRING -> PT.STRING
        KneType.UNIT -> PT.UNIT
        KneType.BYTE_ARRAY -> PT.BYTE_ARRAY
        is KneType.OBJECT -> kotlinType(type.fqName)
        is KneType.INTERFACE -> kotlinType(type.fqName)
        is KneType.ENUM -> kotlinType(type.fqName)
        is KneType.DATA_CLASS -> kotlinType(type.fqName)
        is KneType.FUNCTION -> LambdaTypeName.get(
            parameters = type.paramTypes.map { nativePublic(it) }.toTypedArray(),
            returnType = nativePublic(type.returnType),
        )
        is KneType.LIST -> PT.LIST.parameterizedBy(nativePublic(type.elementType))
        is KneType.SET -> PT.SET.parameterizedBy(nativePublic(type.elementType))
        is KneType.MAP -> PT.MAP.parameterizedBy(nativePublic(type.keyType), nativePublic(type.valueType))
        is KneType.FLOW -> PT.FLOW.parameterizedBy(nativePublic(type.elementType))
        is KneType.NULLABLE -> nativePublic(type.inner).copy(nullable = true)
    }

    // ── JNI type mapping (native side) ───────────────────────────────────────

    /** Kotlin/Native param type for a JNI payload. */
    private fun nativeParamType(type: KneType): TypeName = when (type) {
        KneType.INT -> PT.INT
        KneType.LONG -> PT.LONG
        KneType.DOUBLE -> PT.DOUBLE
        KneType.FLOAT -> PT.FLOAT
        KneType.BOOLEAN -> UBYTE
        KneType.BYTE -> PT.BYTE
        KneType.SHORT -> PT.SHORT
        KneType.STRING -> flavor.jobject.copy(nullable = true)
        KneType.UNIT -> PT.UNIT
        is KneType.OBJECT -> PT.LONG
        is KneType.INTERFACE -> PT.LONG
        is KneType.ENUM -> PT.INT
        KneType.BYTE_ARRAY -> flavor.jobject.copy(nullable = true)
        is KneType.FUNCTION -> flavor.jobject.copy(nullable = true)
        is KneType.FLOW -> PT.UNIT
        is KneType.MAP -> flavor.jobject.copy(nullable = true) // unused; expanded to keys/values
        is KneType.LIST -> flavor.jobject.copy(nullable = true)
        is KneType.SET -> flavor.jobject.copy(nullable = true)
        is KneType.DATA_CLASS -> error("data class params must be flattened")
        is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> flavor.jobject.copy(nullable = true)
            is KneType.LIST, is KneType.SET, is KneType.MAP -> flavor.jobject.copy(nullable = true)
            is KneType.FUNCTION -> flavor.jobject.copy(nullable = true)
            is KneType.OBJECT, is KneType.INTERFACE -> PT.LONG
            KneType.BOOLEAN, is KneType.ENUM -> PT.INT
            KneType.SHORT, KneType.BYTE -> PT.INT
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> PT.LONG
            else -> error("unsupported nullable native param: $type")
        }
    }

    /** Expanded native param list (DC → flattened fields, Map → keys/values, ByteArray → single array). */
    private fun expandNativeParams(name: String, type: KneType): List<Pair<String, TypeName>> = when {
        JniTypes.extractDataClass(type) != null -> {
            val dc = JniTypes.extractDataClass(type)!!
            val fields = JniTypes.flattenDataClassFields(dc, name).flatMap { (n, t) ->
                when {
                    t is KneType.MAP -> listOf(
                        "${n}_keys" to flavor.jobject.copy(nullable = true),
                        "${n}_values" to flavor.jobject.copy(nullable = true),
                    )
                    else -> listOf(n to nativeParamType(t))
                }
            }
            if (type is KneType.NULLABLE) listOf("${name}_isNull" to PT.INT) + fields else fields
        }
        type is KneType.MAP || (type is KneType.NULLABLE && type.inner is KneType.MAP) -> listOf(
            "${name}_keys" to flavor.jobject.copy(nullable = true),
            "${name}_values" to flavor.jobject.copy(nullable = true),
        )
        else -> listOf(name to nativeParamType(type))
    }

    private fun expandNativeParams(params: List<KneParam>): List<Pair<String, TypeName>> =
        params.flatMap { expandNativeParams(it.name, it.type) }

    /** `env`/`thiz` parameter pair every registered native receives. */
    private fun jniPrefixParams(): List<ParameterSpec> = listOf(
        ParameterSpec.builder("env", KneCinteropTypes.pointerTo(flavor.envVar)).build(),
        ParameterSpec.builder("thiz", flavor.jobject.copy(nullable = true)).build(),
    )

    /** The expanded native params as ParameterSpecs (optionally with a leading handle). */
    private fun nativeParamSpecs(params: List<KneParam>, leadingHandle: Boolean = false): List<ParameterSpec> {
        val specs = mutableListOf<ParameterSpec>()
        if (leadingHandle) specs.add(ParameterSpec.builder("handle", PT.LONG).build())
        expandNativeParams(params).forEach { (name, type) -> specs.add(ParameterSpec.builder(name, type).build()) }
        return specs
    }

    /** StableRef unwrap: `<expr>.toCPointer<COpaque>()!!.asStableRef<fq>().get()`. */
    private fun stableRefGet(handleExpr: String, fqName: String): CodeBlock =
        stableRefGetTyped(CodeBlock.of("%L", handleExpr), kotlinType(fqName))

    /**
     * StableRef unwrap with an explicit type reference (function types cannot be bestGuess'd).
     * `asStableRef` is a top-level kotlinx.cinterop extension, so it MUST be a %M reference: in
     * the pre-split single file the runtime surface pulled its import in as a side effect, but
     * per-class files have no such anchor. `toCPointer` is likewise %M'd; `asCPointer`/`get` in
     * [stableRefCreate]/the tail are StableRef members and resolve without an import.
     */
    private fun stableRefGetTyped(handleExpr: CodeBlock, type: TypeName): CodeBlock =
        CodeBlock.of("%L.%M<%T>()!!.%M<%T>().get()", handleExpr, M_TO_C_POINTER, C_OPAQUE, M_AS_STABLE_REF, type)

    /** StableRef pack: `StableRef.create(expr).asCPointer().toLong()`. */
    private fun stableRefCreate(expr: CodeBlock): CodeBlock =
        CodeBlock.of("%T.create(%L).asCPointer().%M()", STABLE_REF, expr, M_TO_LONG)

    // ── main entry ───────────────────────────────────────────────────────────

    /**
     * Generates the native bridge sources split into files: a shared runtime file
     * (`kne_bridges.kt` — the `_kne*` helpers, suspend/flow delivery, callback invoke helpers,
     * common natives, top-level function entries and the bootstrap) plus one
     * `kne_<Class>_bridges.kt` per business class holding that class's JNI entry points.
     * Extension functions are emitted into the receiver class's file (common file when the
     * receiver has no file of its own, e.g. interfaces or common classes).
     *
     * Every emitted declaration is `internal` except the dlsym-resolved `kne_initNatives`,
     * because the files cross-reference each other (per-class entries are bound by the
     * bootstrap, entry bodies call the shared helpers) and Kotlin top-level `private`
     * is file-scoped.
     *
     * @param jvmPackage package the JVM proxies are generated into; every registered class name and
     *   JNI descriptor is derived from it (see [jvmClassPath]). Falls back to the module's own
     *   package when omitted.
     * @return file name (inside the nativeBridges output root) → source text.
     */
    fun generate(module: KneModule, jvmPackage: String = ""): Map<String, String> {
        this.jvmPackage = jvmPackage.ifEmpty { module.packages.firstOrNull() ?: "" }
        val hasSuspend = module.classes.any { cls -> cls.methods.any { it.isSuspend } || cls.companionMethods.any { it.isSuspend } } ||
            module.functions.any { it.isSuspend }
        val hasFlow = module.classes.any { cls -> cls.methods.any { it.returnType is KneType.FLOW } } ||
            module.functions.any { it.returnType is KneType.FLOW }

        val common = newFileSpec(COMMON_FILE_BASE)
        // One file per business class, created upfront so extension functions can route into
        // the receiver's file. Emission order below is unchanged, so the registration table —
        // and therefore the bootstrap's RegisterNatives order — stays exactly as before.
        val bridgeClasses = module.classes.filter { !it.isCommon }
        val fileBases = bridgeFileNames(bridgeClasses)
        val classFiles = bridgeClasses.associateWith { cls -> newFileSpec(fileBases.getValue(cls)) }

        // ── static JNI runtime surface ──
        buildRuntimeSurface(common)

        if (hasFlow) {
            common.addType(buildFlowJobClass())
        }

        // ── per-class native functions ────────────────────────────────────────
        bridgeClasses.forEach { cls -> appendClass(classFiles.getValue(cls), cls) }

        // ── enums ─────────────────────────────────────────────────────────────
        module.enums.forEach { enum -> appendEnum(enum) }

        // ── top-level functions (registered on the <Lib> host; kept in the common file) ──
        module.functions.filter { !it.isExtension }.forEach { fn -> appendTopLevelFunction(common, fn, module) }

        // ── extension functions (registered on KneExt; emitted into the receiver's file) ──
        module.functions.filter { it.isExtension && it.receiverType != null }.forEach { fn ->
            appendExtensionFunction(receiverFileFor(fn, classFiles, common), fn, module)
        }

        // ── sealed ordinal dispatch ───────────────────────────────────────────
        module.classes.filter { it.isSealed && !it.isCommon }.forEach { cls ->
            appendSealedOrdinalBridge(cls)
        }

        // ── List<DC> param element types (already read element-wise; registers no natives) ──
        collectDcListParamTypes(module).forEach { dc -> cacheClass(jvmClassPath(dc.simpleName)) }

        // ── JVM-side value construction / reading helpers ─────────────────────
        buildExtraArrayHelpers(common)
        buildReadHelpers(common)
        buildJvmMapHelpers(common, module)
        collectJvmMaterialisedDcs(module).forEach { dc -> buildJvmDcConstructor(common, dc) }
        buildJvmListElemHelpers(common, module)
        collectReferencedEnumsForDcFields(module).forEach { e -> buildJvmEnumConstant(common, e) }
        collectReferencedProxyClasses(module).forEach { cls -> buildJvmProxyFromHandle(common, cls) }
        collectJvmReadDcs(module).forEach { dc -> buildJvmDcReader(common, dc) }

        // Classes materialised from native via NewObjectA / GetMethodID need a global ref too.
        collectJvmMaterialisedDcs(module).forEach { dc -> cacheClass(jvmClassPath(dc.simpleName)) }
        collectReferencedEnumsForDcFields(module).forEach { e -> cacheClass(jvmClassPath(e.second)) }
        collectReferencedProxyClasses(module).forEach { cls -> cacheClass(jvmClassPath(cls.simpleName)) }

        // ── suspend / flow payload delivery (concrete, per type) ───────────────
        if (hasSuspend || hasFlow) {
            // Deliberately NOT cached: local/global ref addresses are recycled by the JVM once a
            // dispatcher becomes unreachable, so any cache keyed on the callback object (or on
            // GetObjectClass of it) can return a jmethodID belonging to a *different* dispatcher
            // class with the same "(I)V" shape. That silently routed Ints through the enum
            // dispatcher and vice versa. GetMethodID is a cheap class-table lookup in HotSpot.
            common.addFunction(buildCompletionMid())
        }
        suspendReturnTypes(module).forEach { t -> common.addFunction(buildSuspendOkHelper(t)) }
        flowElemTypes(module).forEach { t -> common.addFunction(buildFlowNextHelper(t)) }
        if (hasSuspend || hasFlow) {
            buildSuspendErrorCancelledHelpers(common)
        }

        // ── callback invocation helpers (native → JVM dispatcher) ──────────────
        collectCallbackSignatures(module).forEach { sig -> common.addFunction(buildCallbackInvokeHelper(sig)) }

        // ── function-handle invocation bridges (JVM calls a native-returned lambda) ──
        collectFunctionReturnTypes(module).forEach { t -> common.addFunction(buildFnInvokeBridge(t)) }

        // ── common runtime natives (KneNativeBindings externals) ────────────────
        buildCommonNatives(common, module)

        // ── bootstrap (all functions exist by now) ─────────────────────────────
        common.addFunction(buildBootstrap())

        val result = LinkedHashMap<String, String>()
        result["$COMMON_FILE_BASE.kt"] = renderFile(common.build())
        for ((cls, spec) in classFiles) {
            val built = spec.build()
            if (built.funSpecs.isEmpty()) continue // nothing native-registered on this class
            result["${fileBases.getValue(cls)}.kt"] = renderFile(built)
        }
        return result
    }

    /**
     * Per-class bridge file base names. Different packages may declare classes with the same
     * simple name; colliding names fall back to the fq name (dots → underscores) so every file
     * name stays unique and deterministic.
     */
    private fun bridgeFileNames(classes: List<KneClass>): Map<KneClass, String> {
        val duplicated = classes.groupBy { it.simpleName }.filterValues { it.size > 1 }.keys
        return classes.associateWith { cls ->
            if (cls.simpleName in duplicated) "kne_${cls.fqName.replace('.', '_')}_bridges" else "kne_${cls.simpleName}_bridges"
        }
    }

    /** File an extension-function entry belongs to: the receiver class's file, else the common file. */
    private fun receiverFileFor(
        fn: KneFunction,
        classFiles: Map<KneClass, FileSpec.Builder>,
        common: FileSpec.Builder,
    ): FileSpec.Builder {
        val receiverFq = when (val receiver = fn.receiverType) {
            is KneType.OBJECT -> receiver.fqName
            is KneType.INTERFACE -> receiver.fqName
            else -> return common
        }
        return classFiles.entries.firstOrNull { it.key.fqName == receiverFq }?.value ?: common
    }

    /** Shared file header (comment + suppress-all + opt-ins) applied to every generated file. */
    private fun newFileSpec(baseName: String): FileSpec.Builder =
        FileSpec.builder("", baseName)
            .indent(KNE_INDENT)
            .addFileComment("Auto-generated by kotlin-native-export plugin. Do not modify.")
            .addAnnotation(
                AnnotationSpec.builder(SUPPRESS).useSiteTarget(AnnotationSpec.UseSiteTarget.FILE).addMember("%S", "all").build(),
            )
            .addAnnotation(
                AnnotationSpec.builder(OPT_IN).useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class, %T::class", EXPERIMENTAL_FOREIGN_API, EXPERIMENTAL_NATIVE_API)
                    .build(),
            )

    // KotlinPoet cannot emit wildcard imports, nor imports for the soft-keyword member
    // names `get`/`set` — the array-indexing operators are package extensions, so pin them
    // here. @CName resolves through Kotlin/Native's default imports (the old star-import
    // file used it bare); an explicit import of it fails to resolve, so drop that line.
    // Applied per file; both replaces are no-ops when the import line is absent.
    private fun renderFile(file: FileSpec): String =
        file.toString()
            .replace(
                "import kotlinx.cinterop.invoke",
                "import kotlinx.cinterop.get\n" +
                    "import kotlinx.cinterop.set\n" +
                    "import kotlinx.cinterop.invoke",
            )
            .replace("\nimport kotlin.experimental.CName", "")

    // ══════════════════════════════════════════════════════════════════════════
    // Static JNI runtime surface
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildRuntimeSurface(file: FileSpec.Builder) {
        // ── JNI environment ──
        file.addProperty(
            PropertySpec.builder("_kneVM", C_POINTER.parameterizedBy(flavor.javaVmVar).copy(nullable = true), KModifier.INTERNAL)
                .mutable(true)
                .initializer("null")
                .build(),
        )
        file.addProperty(
            PropertySpec.builder(
                "_kneClassCache",
                ClassName("kotlin.collections", "HashMap").parameterizedBy(PT.STRING, flavor.jobject.copy(nullable = true)),
                KModifier.INTERNAL,
            )
                .initializer("%T()", ClassName("kotlin.collections", "HashMap"))
                .addKdoc("Cached global refs resolved during bootstrap (read-only afterwards).\n")
                .build(),
        )
        file.addProperty(
            PropertySpec.builder("_kneExceptionClass", flavor.jobject.copy(nullable = true), KModifier.INTERNAL)
                .mutable(true)
                .initializer("null")
                .build(),
        )

        file.addFunction(
            FunSpec.builder("_kneFns")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .returns(flavor.jniInterface)
                .addCode(CodeBlock.of("return env!!.%M!!.value!!.%M!!\n", M_POINTED, M_POINTED))
                .build(),
        )

        file.addFunction(
            FunSpec.builder("_kneAttached")
                .addKdoc("Runs [block] with a JNIEnv attached to the current native thread (attach + detach).\n")
                .addModifiers(KModifier.INTERNAL, KModifier.INLINE)
                .addTypeVariable(TypeVariableName("T"))
                .addParameter("block", lambdaOf(listOf(KneCinteropTypes.pointerTo(flavor.envVar)), TypeVariableName("T")))
                .returns(TypeVariableName("T"))
                .addCode(attachedBody())
                .build(),
        )

        // ── modified UTF-8 codec ──
        file.addFunction(
            FunSpec.builder("_kneToModifiedUtf8")
                .addKdoc("Encodes a Kotlin String as JNI modified UTF-8 (NUL → 0xC0 0x80, surrogates as CESU-8).\n")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("value", PT.STRING)
                .returns(PT.BYTE_ARRAY)
                .addCode(TO_MODIFIED_UTF8_BODY)
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneFromModifiedUtf8")
                .addKdoc("Decodes JNI modified UTF-8 bytes (0xC0 0x80 → NUL, CESU-8 surrogate pairs combined).\n")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("bytes", PT.BYTE_ARRAY)
                .returns(PT.STRING)
                .addCode(FROM_MODIFIED_UTF8_BODY)
                .build(),
        )

        // ── strings ──
        file.addFunction(
            FunSpec.builder("_kneJToK")
                .addKdoc("Reads a jstring into a Kotlin String (null for JNI null).\n")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("str", flavor.jobject.copy(nullable = true))
                .returns(PT.STRING.copy(nullable = true))
                .addCode(J_TO_K_BODY)
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneKToJ")
                .addKdoc("Creates a jstring from a Kotlin String.\n")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("value", PT.STRING)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(kToJBody())
                .build(),
        )

        // ── exceptions ──
        file.addFunction(
            FunSpec.builder("_kneThrow")
                .addKdoc("Throws KotlinNativeException(message) on the JVM.\n")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("message", PT.STRING)
                .addCode(throwBody())
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_knePending")
                .addKdoc("Whether a JVM exception is pending (safe to call with a pending exception).\n")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .returns(PT.BOOLEAN)
                .addCode(CodeBlock.of("return _kneFns(env).ExceptionCheck!!.invoke(env) != 0.toUByte()\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneGlobal")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("ref", flavor.jobject.copy(nullable = true))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return if (ref == null) null else _kneFns(env).NewGlobalRef!!.invoke(env, ref)\n"))
                .build(),
        )

        // ── primitive arrays ──
        file.addFunction(
            FunSpec.builder("_kneReadJBytes")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .returns(PT.BYTE_ARRAY.copy(nullable = true))
                .addCode(readArrayBody(BYTE_VAR, "ByteArray", "GetByteArrayRegion"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneNewJBytes")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("values", PT.BYTE_ARRAY)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(newArrayBody(BYTE_VAR, "ByteArray", "NewByteArray", "SetByteArrayRegion", "b"))
                .build(),
        )
        for ((k, varType, arrType) in listOf(
            Triple("Int", INT_VAR, PT.INT_ARRAY),
            Triple("Long", LONG_VAR, PT.LONG_ARRAY),
            Triple("Double", DOUBLE_VAR, PT.DOUBLE_ARRAY),
            Triple("Float", FLOAT_VAR, PT.FLOAT_ARRAY),
            Triple("Short", SHORT_VAR, PT.SHORT_ARRAY),
        )) {
            file.addFunction(
                FunSpec.builder("_kneReadJ${k}Array")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                    .addParameter("arr", flavor.jobject.copy(nullable = true))
                    .returns(arrType.copy(nullable = true))
                    .addCode(readArrayBody(varType, "${k}Array", "Get${k}ArrayRegion"))
                    .build(),
            )
            file.addFunction(
                FunSpec.builder("_kneNewJ${k}Array")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                    .addParameter("values", arrType)
                    .returns(flavor.jobject.copy(nullable = true))
                    .addCode(newArrayBody(varType, "${k}Array", "New${k}Array", "Set${k}ArrayRegion", "v"))
                    .build(),
            )
        }
        file.addFunction(
            FunSpec.builder("_kneReadJBooleanArray")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .returns(PT.BOOLEAN_ARRAY.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        """|if (arr == null) return null
                           |val fns = _kneFns(env)
                           |val n = fns.GetArrayLength!!.invoke(env, arr)
                           |%M {
                           |    val tmp = allocArray<%T>(maxOf(1, n))
                           |    if (n > 0) fns.GetBooleanArrayRegion!!.invoke(env, arr, 0, n, tmp)
                           |    return BooleanArray(n) { tmp[it] != 0u.toUByte() }
                           |}
                           |
                        """.trimMargin(),
                        M_MEM_SCOPED, UBYTE_VAR,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneNewJBooleanArray")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("values", PT.BOOLEAN_ARRAY)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        """|val fns = _kneFns(env)
                           |val arr = fns.NewBooleanArray!!.invoke(env, values.size) ?: return null
                           |if (values.isNotEmpty()) %M {
                           |    val tmp = allocArray<%T>(values.size)
                           |    values.forEachIndexed { i, v -> tmp[i] = if (values[i]) 1u else 0u }
                           |    fns.SetBooleanArrayRegion!!.invoke(env, arr, 0, values.size, tmp)
                           |}
                           |return arr
                           |
                        """.trimMargin(),
                        M_MEM_SCOPED, UBYTE_VAR,
                    ),
                )
                .build(),
        )

        // ── object arrays ──
        file.addFunction(
            FunSpec.builder("_kneObjArrLen")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .returns(PT.INT)
                .addCode(CodeBlock.of("return if (arr == null) 0 else _kneFns(env).GetArrayLength!!.invoke(env, arr)\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneObjArrGet")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .addParameter("i", PT.INT)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneFns(env).GetObjectArrayElement!!.invoke(env, arr, i)\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneObjArrSet")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .addParameter("i", PT.INT)
                .addParameter("v", flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("_kneFns(env).SetObjectArrayElement!!.invoke(env, arr, i, v)\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneNewObjArr")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("size", PT.INT)
                .addParameter("elemClass", flavor.jobject.copy(nullable = true))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneFns(env).NewObjectArray!!.invoke(env, size, elemClass, null)\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneClass")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("fqn", PT.STRING)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneClassCache[fqn]\n"))
                .build(),
        )

        // The member-id / field-id lookups are intentionally NOT cached: the JVM recycles local
        // ref addresses, so an address-keyed cache can return a jmethodID belonging to a
        // *different* class with an identical descriptor. Resolving the id per call costs one
        // class-table lookup, orders of magnitude less than the JNI round trip it is used for.
        file.addFunction(
            FunSpec.builder("_kneMethodId")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("clazz", flavor.jobject.copy(nullable = true))
                .addParameter("name", PT.STRING)
                .addParameter("sig", PT.STRING)
                .returns(flavor.jmethodId.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "return %M { _kneFns(env).GetMethodID!!.invoke(env, clazz, name.%M.ptr, sig.%M.ptr) }\n",
                        M_MEM_SCOPED, M_CSTR, M_CSTR,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneStaticMethodId")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("clazz", flavor.jobject.copy(nullable = true))
                .addParameter("name", PT.STRING)
                .addParameter("sig", PT.STRING)
                .returns(flavor.jmethodId.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "return %M { _kneFns(env).GetStaticMethodID!!.invoke(env, clazz, name.%M.ptr, sig.%M.ptr) }\n",
                        M_MEM_SCOPED, M_CSTR, M_CSTR,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneFieldId")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("clazz", flavor.jobject.copy(nullable = true))
                .addParameter("name", PT.STRING)
                .addParameter("sig", PT.STRING)
                .returns(flavor.jfieldId.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val raw = %M { _kneFns(env).GetFieldID!!.invoke(env, clazz, name.%M.ptr, sig.%M.ptr) }\n" +
                            "@%T(%S)\n" +
                            "return raw as %T?\n",
                        M_MEM_SCOPED, M_CSTR, M_CSTR,
                        SUPPRESS, "UNCHECKED_CAST", flavor.jfieldId,
                    ),
                )
                .build(),
        )

        // ── callback sentinel ──
        file.addType(
            TypeSpec.classBuilder("KneJniCallbackPendingException")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Unwinds native frames when a JVM callback left a pending exception (never touches JNIEnv).\n")
                .superclass(RUNTIME_EXCEPTION)
                .build(),
        )
    }

    private fun attachedBody(): CodeBlock = CodeBlock.of(
        """|val vm = _kneVM ?: error(%S)
           |%M {
           |    val envStorage = %M<%T<%T>>()
           |    val rc = vm.%M!!. %M!!.pointed!!.AttachCurrentThread!!.invoke(vm, envStorage.%M.%M(), null)
           |    check(rc == 0) { %S + rc }
           |    val env = envStorage.%M
           |    try {
           |        return block(env)
           |    } finally {
           |        vm.%M!!. %M!!.pointed!!.DetachCurrentThread!!.invoke(vm)
           |    }
           |}
           |
        """.trimMargin(),
        "KNE JNI runtime not initialized",
        M_MEM_SCOPED, M_ALLOC, C_POINTER_VAR, flavor.envVar,
        M_POINTED, M_VALUE, M_PTR, M_REINTERPRET,
        "AttachCurrentThread failed: ",
        M_VALUE,
        M_POINTED, M_VALUE,
    )

    private val TO_MODIFIED_UTF8_BODY: CodeBlock = CodeBlock.of(
        """|val out = ByteArray(value.length * 3 + 1)
           |var o = 0
           |for (c in value) {
           |    val cp = c.code
           |    when {
           |        cp == 0 -> { out[o] = 0xC0.toByte(); out[o + 1] = 0x80.toByte(); o += 2 }
           |        cp < 0x80 -> { out[o] = cp.toByte(); o++ }
           |        cp < 0x800 -> {
           |            out[o] = (0xC0 or (cp shr 6)).toByte()
           |            out[o + 1] = (0x80 or (cp and 0x3F)).toByte()
           |            o += 2
           |        }
           |        else -> {
           |            out[o] = (0xE0 or ((cp shr 12) and 0x0F)).toByte()
           |            out[o + 1] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
           |            out[o + 2] = (0x80 or (cp and 0x3F)).toByte()
           |            o += 3
           |        }
           |    }
           |}
           |return out.copyOf(o)
           |
        """.trimMargin(),
    )

    private val FROM_MODIFIED_UTF8_BODY: CodeBlock = CodeBlock.of(
        """|val chars = CharArray(bytes.size)
           |var o = 0
           |var i = 0
           |while (i < bytes.size) {
           |    val b0 = bytes[i].toInt() and 0xFF
           |    when {
           |        b0 < 0x80 -> { chars[o] = b0.toChar(); o++; i++ }
           |        b0 == 0xC0 && i + 1 < bytes.size && (bytes[i + 1].toInt() and 0xFF) == 0x80 -> {
           |            chars[o] = 0.toChar(); o++; i += 2
           |        }
           |        b0 and 0xE0 == 0xC0 && i + 1 < bytes.size -> {
           |            chars[o] = (((b0 and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar()
           |            o++; i += 2
           |        }
           |        else -> {
           |            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0x3F else 0
           |            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0x3F else 0
           |            chars[o] = (((b0 and 0x0F) shl 12) or (b1 shl 6) or b2).toChar()
           |            o++; i += 3
           |        }
           |    }
           |}
           |return chars.concatToString(0, o)
           |
        """.trimMargin(),
    )

    private val J_TO_K_BODY: CodeBlock = CodeBlock.of(
        """|if (str == null) return null
           |val fns = _kneFns(env)
           |val chars = fns.GetStringUTFChars!!.invoke(env, str, null) ?: return null
           |try {
           |    var len = 0
           |    while (chars[len] != 0.toByte()) len++
           |    return _kneFromModifiedUtf8(ByteArray(len) { chars[it] })
           |} finally {
           |    fns.ReleaseStringUTFChars!!.invoke(env, str, chars)
           |}
           |
        """.trimMargin(),
    )

    private fun kToJBody(): CodeBlock = CodeBlock.of(
        """|val bytes = _kneToModifiedUtf8(value)
           |%M {
           |    val buf = %M<%T>(bytes.size + 1)
           |    bytes.forEachIndexed { i, b -> buf[i] = b }
           |    buf[bytes.size] = 0
           |    return _kneFns(env).NewStringUTF!!.invoke(env, buf)
           |}
           |
        """.trimMargin(),
        M_MEM_SCOPED, M_ALLOC_ARRAY, BYTE_VAR,
    )

    private fun throwBody(): CodeBlock = CodeBlock.of(
        """|val fns = _kneFns(env)
           |val clazz = _kneExceptionClass
           |%M {
           |    val msg = _kneToModifiedUtf8(message)
           |    val buf = allocArray<%T>(msg.size + 1)
           |    msg.forEachIndexed { i, b -> buf[i] = b }
           |    buf[msg.size] = 0
           |    if (clazz != null) {
           |        fns.ThrowNew!!.invoke(env, clazz, buf)
           |    } else {
           |        val runtime = %M { fns.FindClass!!.invoke(env, %S.%M.ptr) }
           |        if (runtime != null) fns.ThrowNew!!.invoke(env, runtime, buf)
           |    }
           |}
           |
        """.trimMargin(),
        M_MEM_SCOPED, BYTE_VAR,
        M_MEM_SCOPED, "java/lang/RuntimeException", M_CSTR,
    )

    /** Body for `_kneReadJ<k>Array`-style readers. */
    private fun readArrayBody(varType: ClassName, arrType: String, regionFn: String): CodeBlock = CodeBlock.of(
        """|if (arr == null) return null
           |val fns = _kneFns(env)
           |val n = fns.GetArrayLength!!.invoke(env, arr)
           |%M {
           |    val tmp = %M<%T>(maxOf(1, n))
           |    if (n > 0) fns.%L!!.invoke(env, arr, 0, n, tmp)
           |    return %L(n) { tmp[it] }
           |}
           |
        """.trimMargin(),
        M_MEM_SCOPED, M_ALLOC_ARRAY, varType, regionFn, arrType,
    )

    /** Body for `_kneNewJ<k>Array`-style builders. [elemVar] is the forEachIndexed loop variable. */
    private fun newArrayBody(varType: ClassName, arrType: String, newFn: String, regionFn: String, elemVar: String): CodeBlock = CodeBlock.of(
        """|val fns = _kneFns(env)
           |val arr = fns.%L!!.invoke(env, values.size) ?: return null
           |if (values.isNotEmpty()) %M {
           |    val tmp = %M<%T>(values.size)
           |    values.forEachIndexed { i, %L -> tmp[i] = %L }
           |    fns.%L!!.invoke(env, arr, 0, values.size, tmp)
           |}
           |return arr
           |
        """.trimMargin(),
        newFn, M_MEM_SCOPED, M_ALLOC_ARRAY, varType, elemVar, elemVar, regionFn,
    )

    /** `KneFlowJob`: cancellation plus credit-based backpressure driven by the JVM collector. */
    private fun buildFlowJobClass(): TypeSpec = TypeSpec.classBuilder("KneFlowJob")
        .addModifiers(KModifier.INTERNAL)
        .addKdoc("Flow job handle: cancellation plus credit-based backpressure driven by the JVM collector.\n")
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("job", JOB)
                .addParameter("credits", CHANNEL.parameterizedBy(PT.UNIT))
                .build(),
        )
        .addProperty(PropertySpec.builder("job", JOB).initializer("job").build())
        .addProperty(PropertySpec.builder("credits", CHANNEL.parameterizedBy(PT.UNIT)).initializer("credits").build())
        .build()

    private fun lambdaOf(paramTypes: List<TypeName>, returnType: TypeName): TypeName =
        LambdaTypeName.get(parameters = paramTypes.toTypedArray(), returnType = returnType)

    // ══════════════════════════════════════════════════════════════════════════
    // Classes
    // ══════════════════════════════════════════════════════════════════════════

    private fun appendClass(file: FileSpec.Builder, cls: KneClass) {
        val n = cls.simpleName
        val fq = cls.fqName
        val className = jvmClassPath(n)
        val isInstantiable = !cls.isAbstract && !cls.isSealed

        if (isInstantiable) {
            val ctorVariants = buildList {
                add(cls.constructor.params)
                val trailingDefaults = cls.constructor.params.reversed().takeWhile { it.hasDefault }.size
                for (drop in 1..trailingDefaults) add(cls.constructor.params.dropLast(drop))
            }
            for ((index, params) in ctorVariants.withIndex()) {
                val fnName = "kne_${n}_new${if (index == 0) "" else index}"
                val jvmName = if (index == 0) "_kneNew" else "_kneNew$index"

                val body = CodeBlock.builder()
                appendTryStart(body, envReconstructParams(params))
                body.add("return %L\n", stableRefCreate(CodeBlock.of("%T(%L)", kotlinType(fq), callArgs(params))))
                appendTryEnd(body, "0L")

                file.addFunction(
                    FunSpec.builder(fnName)
                        .addModifiers(KModifier.INTERNAL)
                        .addParameters(jniPrefixParams())
                        .addParameters(nativeParamSpecs(params))
                        .returns(PT.LONG)
                        .addCode(body.build())
                        .build(),
                )
                registerMethod(className, jvmName, paramDescriptor(params) + "J", fnName)
            }
        }

        cls.methods.forEach { method ->
            when {
                method.isSuspend -> appendSuspendMethod(file, method, cls)
                method.returnType is KneType.FLOW -> appendFlowMethod(file, method, cls)
                else -> appendMethod(file, method, cls)
            }
        }

        cls.properties.forEach { prop -> appendProperty(file, prop, cls) }
        cls.companionMethods.forEach { method -> appendCompanionMethod(file, method, cls) }
        cls.companionProperties.forEach { prop -> appendCompanionProperty(file, prop, cls) }
    }

    /** "(expanded params)" portion of a registered signature (no leading handle). */
    private fun paramDescriptor(params: List<KneParam>): String = buildString {
        append("(")
        params.forEach { p ->
            JniTypes.extractDataClass(p.type)?.let { dc ->
                if (p.type is KneType.NULLABLE) append("I")
                JniTypes.flattenDataClassFields(dc, p.name).forEach { (_, t) ->
                    when (t) {
                        is KneType.MAP -> {
                            append(JniTypes.arrayDescriptor(t.keyType)); append(JniTypes.arrayDescriptor(t.valueType))
                        }
                        else -> append(flatFieldDescriptor(t))
                    }
                }
            } ?: when {
                p.type == KneType.BYTE_ARRAY -> append("[B")
                p.type is KneType.MAP -> {
                    append(JniTypes.arrayDescriptor(p.type.keyType)); append(JniTypes.arrayDescriptor(p.type.valueType))
                }
                p.type is KneType.NULLABLE && p.type.inner is KneType.MAP -> {
                    val m = p.type.inner as KneType.MAP
                    append(JniTypes.arrayDescriptor(m.keyType)); append(JniTypes.arrayDescriptor(m.valueType))
                }
                else -> append(JniTypes.jniDescriptor(p.type))
            }
        }
        append(")")
    }

    /** Descriptor for a flattened DC field. */
    private fun flatFieldDescriptor(type: KneType): String = when (type) {
        KneType.STRING -> "Ljava/lang/String;"
        KneType.BYTE_ARRAY -> "[B"
        is KneType.LIST -> JniTypes.arrayDescriptor(type.elementType)
        is KneType.SET -> JniTypes.arrayDescriptor(type.elementType)
        is KneType.MAP -> "Ljava/util/Map;" // unused: DC map fields take parallel arrays
        else -> JniTypes.jniDescriptor(type)
    }

    /** Opens the try/catch: [pre] holds the param-reconstruction lines that run outside the try. */
    private fun appendTryStart(cb: CodeBlock.Builder, pre: CodeBlock = EMPTY_CODE) {
        if (pre.isNotEmpty()) cb.add("%L\n", pre)
        cb.add("try {\n")
    }

    /**
     * Closes the try/catch: Kotlin exceptions become ThrowNew(KotlinNativeException);
     * if a JVM callback exception is already pending we return the default silently
     * (calling env is otherwise forbidden while an exception is pending).
     */
    private fun appendTryEnd(cb: CodeBlock.Builder, returnExpr: String?) {
        cb.add("} catch (_: KneJniCallbackPendingException) {\n")
        if (returnExpr != null) cb.indent().add("return %L\n", returnExpr).unindent()
        cb.add("} catch (e: Throwable) {\n")
        cb.indent()
        cb.add("if (_knePending(env)) {\n")
        if (returnExpr != null) cb.indent().add("return %L\n", returnExpr).unindent()
        cb.add("} else {\n")
        cb.indent()
        cb.add("_kneThrow(env, e.message ?: e::class.simpleName ?: %S)\n", "Unknown error")
        if (returnExpr != null) cb.add("return %L\n", returnExpr)
        cb.unindent()
        cb.add("}\n")
        cb.unindent()
        cb.add("}\n")
    }

    /** Kotlin return expression used when unwinding after an error (sentinel for primitives). */
    private fun errorReturnExpr(type: KneType): String? = when {
        type == KneType.UNIT -> null
        type is KneType.FLOW -> null
        JniTypes.extractDataClass(type) != null -> "null"
        type == KneType.STRING || type == KneType.BYTE_ARRAY ||
            type is KneType.LIST || type is KneType.SET || type is KneType.MAP -> "null"
        type == KneType.BOOLEAN -> "0.toUByte()"
        type == KneType.INT || type is KneType.ENUM -> "0"
        type == KneType.LONG || type is KneType.OBJECT || type is KneType.INTERFACE -> "0L"
        type == KneType.DOUBLE -> "0.0"
        type == KneType.FLOAT -> "0.0f"
        type == KneType.BYTE -> "0.toByte()"
        type == KneType.SHORT -> "0.toShort()"
        type is KneType.FUNCTION -> "0L"
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> "null"
            is KneType.LIST, is KneType.SET, is KneType.MAP -> "null"
            KneType.BOOLEAN, is KneType.ENUM -> "(-1)"
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "Long.MIN_VALUE"
            KneType.SHORT, KneType.BYTE -> "Int.MIN_VALUE"
            is KneType.OBJECT, is KneType.INTERFACE -> "0L"
            else -> "0"
        }
        else -> "0"
    }

    /** Native return type declaration (null for Unit). */
    private fun nativeReturnDecl(type: KneType): TypeName? = when (nativeReturnValueKind(type)) {
        "UNIT" -> null
        "JOBJECT" -> flavor.jobject.copy(nullable = true)
        "UBYTE" -> UBYTE
        "LONG" -> PT.LONG
        "Int" -> PT.INT
        "Double" -> PT.DOUBLE
        "Float" -> PT.FLOAT
        "Byte" -> PT.BYTE
        "Short" -> PT.SHORT
        else -> PT.INT
    }

    /** Kind tag used by both the declaration and the error-return logic. */
    private fun nativeReturnValueKind(type: KneType): String = when {
        type == KneType.UNIT || type is KneType.FLOW -> "UNIT"
        JniTypes.extractDataClass(type) != null -> "JOBJECT"
        type == KneType.STRING || type == KneType.BYTE_ARRAY ||
            type is KneType.LIST || type is KneType.SET || type is KneType.MAP -> "JOBJECT"
        type == KneType.BOOLEAN -> "UBYTE"
        type == KneType.INT || type is KneType.ENUM -> "Int"
        type == KneType.LONG || type is KneType.OBJECT || type is KneType.INTERFACE -> "LONG"
        type == KneType.DOUBLE -> "Double"
        type == KneType.FLOAT -> "Float"
        type == KneType.BYTE -> "Byte"
        type == KneType.SHORT -> "Short"
        type is KneType.FUNCTION -> "LONG"
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> "JOBJECT"
            is KneType.LIST, is KneType.SET, is KneType.MAP -> "JOBJECT"
            KneType.BOOLEAN -> "Int"
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "LONG"
            KneType.SHORT, KneType.BYTE -> "Int"
            is KneType.ENUM -> "Int"
            is KneType.OBJECT, is KneType.INTERFACE -> "LONG"
            else -> "JOBJECT"
        }
        else -> "Int"
    }

    /** Emits the return statement converting a Kotlin expression into its JNI return value. */
    private fun emitReturn(cb: CodeBlock.Builder, expr: CodeBlock, type: KneType) {
        val dc = JniTypes.extractDataClass(type)
        when {
            dc != null -> {
                if (type is KneType.NULLABLE) {
                    cb.add("val _result = %L\n", expr)
                    cb.add("return if (_result == null) null else _kneNewJvm%L(env, _result)\n", dc.simpleName)
                } else {
                    cb.add("return _kneNewJvm%L(env, %L)\n", dc.simpleName, expr)
                }
            }
            type == KneType.UNIT -> cb.add("%L\n", expr)
            type == KneType.BOOLEAN -> cb.add("return if (%L) 1.toUByte() else 0.toUByte()\n", expr)
            type == KneType.STRING -> cb.add("return _kneKToJ(env, %L)\n", expr)
            type == KneType.BYTE_ARRAY -> cb.add("return _kneNewJBytes(env, %L)\n", expr)
            type is KneType.ENUM -> cb.add("return (%L).ordinal\n", expr)
            type is KneType.OBJECT || type is KneType.INTERFACE -> cb.add("return %L\n", stableRefCreate(expr))
            type is KneType.FUNCTION -> cb.add("return %L\n", stableRefCreate(expr))
            type is KneType.LIST || type is KneType.SET -> cb.add("return %L\n", arrayBuildExpr(expr, type))
            type is KneType.MAP -> cb.add("return %L\n", mapBuildExpr(expr, type))
            type is KneType.NULLABLE -> when (type.inner) {
                KneType.BOOLEAN -> cb.add("return if (%L == null) (-1) else if (%L == true) 1 else 0\n", expr, expr)
                KneType.INT -> cb.add("return %L?.toLong() ?: Long.MIN_VALUE\n", expr)
                KneType.LONG -> cb.add("return %L ?: Long.MIN_VALUE\n", expr)
                KneType.SHORT -> cb.add("return %L?.toInt() ?: Int.MIN_VALUE\n", expr)
                KneType.BYTE -> cb.add("return %L?.toInt() ?: Int.MIN_VALUE\n", expr)
                KneType.FLOAT -> cb.add("return %L?.toRawBits()?.toLong() ?: Long.MIN_VALUE\n", expr)
                KneType.DOUBLE -> cb.add("return %L?.toRawBits() ?: Long.MIN_VALUE\n", expr)
                is KneType.ENUM -> cb.add("return %L?.ordinal ?: -1\n", expr)
                else -> {
                    cb.add("val _result = %L\n", expr)
                    cb.add("if (_result == null) return %L\n", nullableNullReturn(type.inner))
                    emitReturn(cb, CodeBlock.of("_result!!"), type.inner)
                }
            }
            else -> cb.add("return %L\n", expr)
        }
    }

    private fun nullableNullReturn(inner: KneType): String = when (inner) {
        KneType.STRING, KneType.BYTE_ARRAY -> "null"
        is KneType.LIST, is KneType.SET, is KneType.MAP -> "null"
        KneType.BOOLEAN, is KneType.ENUM -> "(-1).toUByte()"
        KneType.SHORT, KneType.BYTE -> "Int.MIN_VALUE"
        KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "Long.MIN_VALUE"
        is KneType.OBJECT, is KneType.INTERFACE -> "0L"
        else -> "0"
    }

    /**
     * Expression producing a jarray from a Kotlin collection expression.
     *
     * [envExpr] is the JNIEnv in scope at the call site. Everything routed through `_kneAttached`
     * (suspend / flow completion on a coroutine worker) must pass the attached `_e`, because the
     * outer `env` belongs to the JVM caller's thread and is invalid there.
     */
    private fun arrayBuildExpr(expr: CodeBlock, type: KneType, envExpr: String = "env"): CodeBlock = when (type) {
        is KneType.LIST -> elemArrayBuildExpr(expr, type.elementType, envExpr)
        is KneType.SET -> elemArrayBuildExpr(CodeBlock.of("%L.toList()", expr), type.elementType, envExpr)
        else -> CodeBlock.of("null")
    }

    private fun elemArrayBuildExpr(expr: CodeBlock, elemType: KneType, envExpr: String = "env"): CodeBlock = when (elemType) {
        KneType.INT -> CodeBlock.of("_kneNewJIntArray(%L, (%L).toIntArray())", envExpr, expr)
        KneType.LONG -> CodeBlock.of("_kneNewJLongArray(%L, (%L).toLongArray())", envExpr, expr)
        KneType.DOUBLE -> CodeBlock.of("_kneNewJDoubleArray(%L, (%L).toDoubleArray())", envExpr, expr)
        KneType.FLOAT -> CodeBlock.of("_kneNewJFloatArray(%L, (%L).toFloatArray())", envExpr, expr)
        KneType.SHORT -> CodeBlock.of("_kneNewJShortArray(%L, (%L).toShortArray())", envExpr, expr)
        KneType.BYTE -> CodeBlock.of("_kneNewJBytes(%L, run { val _b = %L; ByteArray(_b.size) { _b[it] } })", envExpr, expr)
        KneType.BOOLEAN -> CodeBlock.of("_kneNewJBooleanArray(%L, (%L).toBooleanArray())", envExpr, expr)
        is KneType.ENUM -> CodeBlock.of("_kneNewJIntArray(%L, run { val _l = %L; IntArray(_l.size) { _l[it].ordinal } })", envExpr, expr)
        KneType.STRING -> CodeBlock.of("_kneNewJStrArr(%L, %L)", envExpr, expr)
        is KneType.OBJECT -> CodeBlock.of(
            "_kneNewJLongArray(%L, run { val _l = %L; LongArray(_l.size) { %L } })",
            envExpr, expr, stableRefCreate(CodeBlock.of("_l[it] as %T", kotlinType(elemType.fqName))),
        )
        is KneType.INTERFACE -> CodeBlock.of(
            "_kneNewJLongArray(%L, run { val _l = %L; LongArray(_l.size) { %L } })",
            envExpr, expr, stableRefCreate(CodeBlock.of("_l[it] as %T", kotlinType(elemType.fqName))),
        )
        KneType.BYTE_ARRAY -> CodeBlock.of("_kneNewJBytesArr(%L, %L)", envExpr, expr)
        is KneType.DATA_CLASS -> CodeBlock.of("_kneNewJObjArr(%L, %L.map { _kneNewJvm%L(%L, it) })", envExpr, expr, elemType.simpleName, envExpr)
        is KneType.LIST -> CodeBlock.of("_kneNewJObjArr(%L, %L.map { %L })", envExpr, expr, nestedListJobject(elemType.elementType, CodeBlock.of("it"), envExpr))
        is KneType.SET -> CodeBlock.of("_kneNewJObjArr(%L, %L.map { %L })", envExpr, expr, nestedListJobject(elemType.elementType, CodeBlock.of("it"), envExpr))
        is KneType.MAP -> CodeBlock.of("_kneNewJObjArr(%L, %L.map { %L })", envExpr, expr, mapBuildExpr(CodeBlock.of("it"), elemType, envExpr))
        else -> CodeBlock.of("null")
    }

    private fun mapBuildExpr(expr: CodeBlock, type: KneType.MAP, envExpr: String = "env"): CodeBlock =
        CodeBlock.of("_kneNewJvmMap_%L_%L(%L, %L)", payloadId(type.keyType), payloadId(type.valueType), envExpr, expr)

    // ── regular methods ──────────────────────────────────────────────────────

    private fun appendMethod(file: FileSpec.Builder, fn: KneFunction, cls: KneClass) {
        val fnName = "kne_${cls.simpleName}_${fn.name}"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}"
        val className = jvmClassPath(cls.simpleName)

        val body = CodeBlock.builder()
        appendTryStart(body, envReconstructParams(fn.params))
        body.add("val obj = %L\n", stableRefGet("handle", cls.fqName))
        emitReturn(body, CodeBlock.of("obj.%L(%L)", fn.name, callArgs(fn.params)), fn.returnType)
        appendTryEnd(body, errorReturnExpr(fn.returnType))

        file.addFunction(
            FunSpec.builder(fnName)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameter("handle", PT.LONG)
                .addParameters(nativeParamSpecs(fn.params))
                .apply { nativeReturnDecl(fn.returnType)?.let { returns(it) } }
                .addCode(body.build())
                .build(),
        )
        registerMethod(
            className, jvmName,
            "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + ")" + JniTypes.returnDescriptor(fn.returnType, jvmPackage),
            fnName,
        )
    }

    /** Reconstructs Kotlin values from flattened/expanded JNI params as local vals. */
    private fun envReconstructParams(params: List<KneParam>): CodeBlock {
        if (params.isEmpty()) return EMPTY_CODE
        val cb = CodeBlock.builder()
        cb.add("// param reconstruction\n")
        params.forEach { p ->
            val dc = JniTypes.extractDataClass(p.type)
            when {
                dc != null -> {
                    readDcFields(cb, dc, p.name)
                    readDcMapFields(cb, dc, p.name)
                    val ctorExpr = buildDcCtorExpr(dc, p.name)
                    if (p.type is KneType.NULLABLE) {
                        cb.add("val %LObj = if (%L_isNull != 0) null else %L\n", p.name, p.name, ctorExpr)
                    } else {
                        cb.add("val %LObj = %L\n", p.name, ctorExpr)
                    }
                }
                p.type == KneType.STRING -> cb.add("val %LStr = _kneJToK(env, %L) ?: \"\"\n", p.name, p.name)
                p.type is KneType.NULLABLE && p.type.inner == KneType.STRING ->
                    cb.add("val %LStr = _kneJToK(env, %L)\n", p.name, p.name)
                p.type == KneType.BYTE_ARRAY -> cb.add("val %LArr = _kneReadJBytes(env, %L) ?: ByteArray(0)\n", p.name, p.name)
                p.type is KneType.FUNCTION -> appendCallbackLambda(cb, p.name, p.type)
                p.type is KneType.NULLABLE && p.type.inner is KneType.FUNCTION ->
                    appendCallbackLambda(cb, p.name, p.type.inner as KneType.FUNCTION, nullable = true)
                else -> {}
            }
            val inner = JniTypes.unwrapNullable(p.type)
            val isNull = p.type is KneType.NULLABLE
            when (inner) {
                is KneType.LIST -> cb.add("val %LList = %L\n", p.name, collReadExpr(p.name, inner.elementType, "List", isNull))
                is KneType.SET -> cb.add("val %LSet = %L\n", p.name, collReadExpr(p.name, inner.elementType, "Set", isNull))
                is KneType.MAP -> cb.add("val %LMap = %L\n", p.name, mapReadExpr(p.name, inner, isNull))
                else -> {}
            }
        }
        return cb.build()
    }

    /** Reads flattened DC fields from JNI payload params into converted local vals. */
    private fun readDcFields(cb: CodeBlock.Builder, dc: KneType.DATA_CLASS, prefix: String) {
        dc.fields.forEach { f ->
            val name = "${prefix}_${f.name}"
            when (val t = f.type) {
                KneType.STRING -> cb.add("val %L = _kneJToK(env, %L) ?: \"\"\n", name, name)
                KneType.BOOLEAN -> cb.add("val %L = %L != 0.toUByte()\n", name, name)
                is KneType.DATA_CLASS -> readDcFields(cb, t, name)
                is KneType.OBJECT -> cb.add("val %L = %L\n", name, stableRefGet(name, t.fqName))
                is KneType.INTERFACE -> cb.add("val %L = %L\n", name, stableRefGet(name, t.fqName))
                is KneType.ENUM -> cb.add("val %L = %T.entries[%L]\n", name, kotlinType(t.fqName), name)
                KneType.BYTE_ARRAY -> cb.add("val %L = _kneReadJBytes(env, %L) ?: ByteArray(0)\n", name, name)
                is KneType.LIST -> cb.add("val %L = %L\n", name, collReadExpr(name, t.elementType, "List", isNull = false))
                is KneType.SET -> cb.add("val %L = %L\n", name, collReadExpr(name, t.elementType, "Set", isNull = false))
                else -> {}
            }
        }
    }

    /** Constructor expression for a DC from converted local vals. */
    private fun buildDcCtorExpr(dc: KneType.DATA_CLASS, prefix: String): CodeBlock {
        val fieldArgs = dc.fields.map { f ->
            val paramName = "${prefix}_${f.name}"
            when (f.type) {
                is KneType.DATA_CLASS -> CodeBlock.of("%L = %L", f.name, buildDcCtorExpr(f.type, paramName))
                is KneType.MAP -> CodeBlock.of("%L = %LMap", f.name, paramName)
                else -> CodeBlock.of("%L = %L", f.name, paramName)
            }
        }.joinToCode(", ")
        return CodeBlock.of("%T(%L)", kotlinType(dc.fqName), fieldArgs)
    }

    /** Reads DC map fields (keys/values array pairs) into `${prefix}_${name}Map` locals. */
    private fun readDcMapFields(cb: CodeBlock.Builder, dc: KneType.DATA_CLASS, prefix: String) {
        dc.fields.forEach { f ->
            val name = "${prefix}_${f.name}"
            when (val t = f.type) {
                is KneType.MAP -> cb.add("val %LMap = %L\n", name, mapReadExpr(name, t, isNull = false))
                is KneType.DATA_CLASS -> readDcMapFields(cb, t, name)
                else -> {}
            }
        }
    }

    /** Expression converting a JNI param into the Kotlin value passed to the user function. */
    private fun callArgExpr(name: String, type: KneType): CodeBlock = when {
        JniTypes.extractDataClass(type) != null -> CodeBlock.of("%LObj", name)
        type == KneType.STRING -> CodeBlock.of("%LStr", name)
        type == KneType.BYTE_ARRAY -> CodeBlock.of("%LArr", name)
        type == KneType.BOOLEAN -> CodeBlock.of("%L != 0.toUByte()", name)
        type is KneType.ENUM -> CodeBlock.of("%T.entries[%L]", kotlinType(type.fqName), name)
        type is KneType.OBJECT -> stableRefGet(name, type.fqName)
        type is KneType.INTERFACE -> stableRefGet(name, type.fqName)
        type is KneType.FUNCTION -> CodeBlock.of("%LFn", name)
        type is KneType.NULLABLE -> when (type.inner) {
            is KneType.FUNCTION -> CodeBlock.of("%LFnN", name)
            KneType.STRING -> CodeBlock.of("%LStr", name)
            is KneType.OBJECT -> CodeBlock.of(
                "if (%L != 0L) %L else null", name, stableRefGet(name, (type.inner as KneType.OBJECT).fqName),
            )
            is KneType.INTERFACE -> CodeBlock.of(
                "if (%L != 0L) %L else null", name, stableRefGet(name, (type.inner as KneType.INTERFACE).fqName),
            )
            KneType.BOOLEAN -> CodeBlock.of("if (%L >= 0) %L != 0 else null", name, name)
            KneType.INT -> CodeBlock.of("if (%L != Long.MIN_VALUE) %L.toInt() else null", name, name)
            KneType.LONG -> CodeBlock.of("if (%L != Long.MIN_VALUE) %L else null", name, name)
            KneType.SHORT -> CodeBlock.of("if (%L != Int.MIN_VALUE) %L.toShort() else null", name, name)
            KneType.BYTE -> CodeBlock.of("if (%L != Int.MIN_VALUE) %L.toByte() else null", name, name)
            KneType.FLOAT -> CodeBlock.of("if (%L != Long.MIN_VALUE) Float.fromBits(%L.toInt()) else null", name, name)
            KneType.DOUBLE -> CodeBlock.of("if (%L != Long.MIN_VALUE) Double.fromBits(%L) else null", name, name)
            is KneType.ENUM -> CodeBlock.of("if (%L >= 0) %T.entries[%L] else null", name, kotlinType(type.inner.fqName), name)
            is KneType.LIST -> CodeBlock.of("%LList", name)
            is KneType.SET -> CodeBlock.of("%LSet", name)
            is KneType.MAP -> CodeBlock.of("%LMap", name)
            else -> CodeBlock.of("%L", name)
        }
        type is KneType.LIST -> CodeBlock.of("%LList", name)
        type is KneType.SET -> CodeBlock.of("%LSet", name)
        type is KneType.MAP -> CodeBlock.of("%LMap", name)
        else -> CodeBlock.of("%L", name)
    }

    /** Comma-joined call args for [params]. */
    private fun callArgs(params: List<KneParam>): CodeBlock =
        params.map { callArgExpr(it.name, it.type) }.joinToCode(", ")

    /** Reads a collection jarray payload into a Kotlin collection expression. */
    private fun collReadExpr(name: String, elemType: KneType, collKind: String, isNull: Boolean): CodeBlock {
        val readList: CodeBlock = when (elemType) {
            KneType.INT -> CodeBlock.of("_kneReadJIntArray(env, %L)?.toList() ?: emptyList()", name)
            KneType.LONG -> CodeBlock.of("_kneReadJLongArray(env, %L)?.toList() ?: emptyList()", name)
            KneType.DOUBLE -> CodeBlock.of("_kneReadJDoubleArray(env, %L)?.toList() ?: emptyList()", name)
            KneType.FLOAT -> CodeBlock.of("_kneReadJFloatArray(env, %L)?.toList() ?: emptyList()", name)
            KneType.SHORT -> CodeBlock.of("_kneReadJShortArray(env, %L)?.toList() ?: emptyList()", name)
            KneType.BYTE -> CodeBlock.of("_kneReadJBytes(env, %L)?.toList() ?: emptyList()", name)
            KneType.BOOLEAN -> CodeBlock.of("_kneReadJBooleanArray(env, %L)?.toList() ?: emptyList()", name)
            is KneType.ENUM -> CodeBlock.of(
                "_kneReadJIntArray(env, %L)?.toList()?.map { %T.entries[it] } ?: emptyList()", name, kotlinType(elemType.fqName),
            )
            KneType.STRING -> CodeBlock.of("_kneReadJStrList(env, %L) ?: emptyList()", name)
            is KneType.OBJECT -> CodeBlock.of(
                "_kneReadJLongArray(env, %L)?.map { %L }", name, stableRefGet("it", elemType.fqName),
            )
            is KneType.INTERFACE -> CodeBlock.of(
                "_kneReadJLongArray(env, %L)?.map { %L }", name, stableRefGet("it", elemType.fqName),
            )
            KneType.BYTE_ARRAY -> CodeBlock.of("_kneReadJBytesList(env, %L)", name)
            is KneType.LIST -> CodeBlock.of("_kneReadJNestedList(env, %L, %S).map { it as %T }", name, payloadId(elemType), nativePublic(elemType))
            is KneType.SET -> CodeBlock.of("_kneReadJNestedList(env, %L, %S).map { it as %T }.toSet()", name, payloadId(elemType), nativePublic(elemType))
            is KneType.MAP -> CodeBlock.of("_kneReadJNestedList(env, %L, %S).map { it as %T }", name, payloadId(elemType), nativePublic(elemType))
            is KneType.DATA_CLASS -> CodeBlock.of(
                "List(_kneObjArrLen(env, %L)) { _kneReadJvm%L(env, _kneObjArrGet(env, %L, it)) }", name, elemType.simpleName, name,
            )
            else -> CodeBlock.of("null")
        }
        return when {
            isNull -> CodeBlock.of(
                "if (%L == null) null else (%L ?: emptyList())%L", name, readList,
                if (collKind == "Set") CodeBlock.of(".toSet()") else EMPTY_CODE,
            )
            collKind == "Set" -> CodeBlock.of("(%L ?: emptyList()).toSet()", readList)
            else -> CodeBlock.of("%L ?: emptyList()", readList)
        }
    }

    private fun mapReadExpr(name: String, type: KneType.MAP, isNull: Boolean): CodeBlock {
        val keys = collReadExpr("${name}_keys", type.keyType, "List", false)
        val values = collReadExpr("${name}_values", type.valueType, "List", false)
        val body = CodeBlock.of("(%L).zip(%L).toMap()", keys, values)
        return if (isNull) CodeBlock.of("if (%L_keys == null) null else %L", name, body) else body
    }

    /** Builds the Kotlin lambda wrapper that round-trips a JVM dispatcher through the invoke helper. */
    private fun appendCallbackLambda(cb: CodeBlock.Builder, name: String, fnType: KneType.FUNCTION, nullable: Boolean = false) {
        val id = callbackId(fnType)
        val lambdaParams = fnType.paramTypes.mapIndexed { i, t ->
            CodeBlock.of("_p%L: %T", i, nativePublic(t))
        }.joinToCode(", ")
        val invokeArgs = fnType.paramTypes.mapIndexed { i, t -> cbLambdaArg(t, "_p$i") }.joinToCode(", ")
        cb.add("val %LFn: %T = { %L ->\n", name, nativePublic(fnType), lambdaParams)
        cb.indent().add("%L\n", callbackResultExpr(fnType, id, name, invokeArgs)).unindent()
        cb.add("}\n")
        if (nullable) {
            cb.add("val %LFnN = if (%L != null) %LFn else null\n", name, name, name)
        }
    }

    /** Lambda arg → invoke-helper arg conversion (enum → ordinal; others pass through). */
    private fun cbLambdaArg(t: KneType, name: String): CodeBlock = when (t) {
        is KneType.ENUM -> CodeBlock.of("%L.ordinal", name)
        else -> CodeBlock.of("%L", name)
    }

    /** The lambda body: invoke the JVM dispatcher, then check for pending JVM exceptions. */
    private fun callbackResultExpr(fnType: KneType.FUNCTION, id: String, jobjectName: String, invokeArgs: CodeBlock): CodeBlock {
        val call = CodeBlock.of(
            "_kneInvoke_%L(env, %L%L)", id, jobjectName,
            if (invokeArgs.isEmpty()) EMPTY_CODE else CodeBlock.of(", %L", invokeArgs),
        )
        val guard = CodeBlock.of("if (_knePending(env)) throw KneJniCallbackPendingException(); ")
        return when (fnType.returnType) {
            KneType.UNIT -> CodeBlock.of("%L; %L", call, guard)
            KneType.BOOLEAN -> CodeBlock.of("run { val _r = %L; %L_r != 0.toUByte() }", call, guard)
            is KneType.ENUM -> CodeBlock.of("run { val _r = %L; %L%T.entries[_r] }", call, guard, kotlinType(fnType.returnType.fqName))
            KneType.BYTE -> CodeBlock.of("run { val _r = %L; %L_r.toByte() }", call, guard)
            KneType.SHORT -> CodeBlock.of("run { val _r = %L; %L_r.toShort() }", call, guard)
            KneType.STRING -> CodeBlock.of("run { val _r = %L; %L_r ?: \"\" }", call, guard)
            KneType.BYTE_ARRAY -> CodeBlock.of("run { val _r = %L; %L_r ?: ByteArray(0) }", call, guard)
            is KneType.DATA_CLASS -> CodeBlock.of("run { val _r = %L; %L_r }", call, guard)
            is KneType.LIST -> CodeBlock.of("run { val _r = %L; %L_r ?: emptyList() }", call, guard)
            is KneType.SET -> CodeBlock.of("run { val _r = %L; %L_r ?: emptySet() }", call, guard)
            is KneType.MAP -> CodeBlock.of("run { val _r = %L; %L_r ?: emptyMap() }", call, guard)
            is KneType.OBJECT -> CodeBlock.of(
                "run { val _r = %L; %L%L }", call, guard, stableRefGetTyped(CodeBlock.of("_r"), kotlinType(fnType.returnType.fqName)),
            )
            is KneType.INTERFACE -> CodeBlock.of(
                "run { val _r = %L; %L%L }", call, guard, stableRefGetTyped(CodeBlock.of("_r"), kotlinType(fnType.returnType.fqName)),
            )
            else -> CodeBlock.of("run { val _r = %L; %L_r }", call, guard)
        }
    }

    // ── properties ───────────────────────────────────────────────────────────

    private fun appendProperty(file: FileSpec.Builder, prop: KneProperty, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)

        val getterFn = "kne_${cls.simpleName}_get_${prop.name}"
        val getterJvm = "_kneGet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
        val getterBody = CodeBlock.builder()
        appendTryStart(getterBody)
        getterBody.add("val obj = %L\n", stableRefGet("handle", cls.fqName))
        emitReturn(getterBody, CodeBlock.of("obj.%L", prop.name), prop.type)
        appendTryEnd(getterBody, errorReturnExpr(prop.type))

        file.addFunction(
            FunSpec.builder(getterFn)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameter("handle", PT.LONG)
                .apply { nativeReturnDecl(prop.type)?.let { returns(it) } }
                .addCode(getterBody.build())
                .build(),
        )
        registerMethod(className, getterJvm, "(J)" + JniTypes.returnDescriptor(prop.type, jvmPackage), getterFn)

        if (prop.mutable) {
            val setterFn = "kne_${cls.simpleName}_set_${prop.name}"
            val setterJvm = "_kneSet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
            val setterBody = CodeBlock.builder()
            appendTryStart(setterBody, envReconstructParams(listOf(KneParam("value", prop.type))))
            setterBody.add("val obj = %L\n", stableRefGet("handle", cls.fqName))
            setterBody.add("obj.%L = %L\n", prop.name, callArgExpr("value", prop.type))
            appendTryEnd(setterBody, null)

            file.addFunction(
                FunSpec.builder(setterFn)
                    .addModifiers(KModifier.INTERNAL)
                    .addParameters(jniPrefixParams())
                    .addParameter("handle", PT.LONG)
                    .addParameters(nativeParamSpecs(listOf(KneParam("value", prop.type))))
                    .addCode(setterBody.build())
                    .build(),
            )
            registerMethod(
                className, setterJvm,
                // Handle + the expanded value descriptor (String/Double/Boolean/arrays all widen
                // differently), not a fixed "(JI)V".
                "(J" + paramDescriptor(listOf(KneParam("value", prop.type))).drop(1) + "V",
                setterFn,
            )
        }
    }

    // ── companion ────────────────────────────────────────────────────────────

    private fun appendCompanionMethod(file: FileSpec.Builder, fn: KneFunction, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)
        val fnName = "kne_${cls.simpleName}_companion_${fn.name}"
        val jvmName = "_kneCompanion${fn.name.replaceFirstChar { it.uppercaseChar() }}"

        val body = CodeBlock.builder()
        appendTryStart(body, envReconstructParams(fn.params))
        emitReturn(body, CodeBlock.of("%T.%L(%L)", kotlinType(cls.fqName), fn.name, callArgs(fn.params)), fn.returnType)
        appendTryEnd(body, errorReturnExpr(fn.returnType))

        file.addFunction(
            FunSpec.builder(fnName)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameters(nativeParamSpecs(fn.params))
                .apply { nativeReturnDecl(fn.returnType)?.let { returns(it) } }
                .addCode(body.build())
                .build(),
        )
        registerMethod(className, jvmName, paramDescriptor(fn.params) + JniTypes.returnDescriptor(fn.returnType, jvmPackage), fnName)
    }

    private fun appendCompanionProperty(file: FileSpec.Builder, prop: KneProperty, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)

        val getterFn = "kne_${cls.simpleName}_companion_get_${prop.name}"
        val getterJvm = "_kneCompanionGet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
        val getterBody = CodeBlock.builder()
        appendTryStart(getterBody)
        emitReturn(getterBody, CodeBlock.of("%T.%L", kotlinType(cls.fqName), prop.name), prop.type)
        appendTryEnd(getterBody, errorReturnExpr(prop.type))

        file.addFunction(
            FunSpec.builder(getterFn)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .apply { nativeReturnDecl(prop.type)?.let { returns(it) } }
                .addCode(getterBody.build())
                .build(),
        )
        registerMethod(className, getterJvm, "()" + JniTypes.returnDescriptor(prop.type, jvmPackage), getterFn)

        if (prop.mutable) {
            val setterFn = "kne_${cls.simpleName}_companion_set_${prop.name}"
            val setterJvm = "_kneCompanionSet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
            val setterBody = CodeBlock.builder()
            appendTryStart(setterBody, envReconstructParams(listOf(KneParam("value", prop.type))))
            setterBody.add("%T.%L = %L\n", kotlinType(cls.fqName), prop.name, callArgExpr("value", prop.type))
            appendTryEnd(setterBody, null)

            file.addFunction(
                FunSpec.builder(setterFn)
                    .addModifiers(KModifier.INTERNAL)
                    .addParameters(jniPrefixParams())
                    .addParameters(nativeParamSpecs(listOf(KneParam("value", prop.type))))
                    .addCode(setterBody.build())
                    .build(),
            )
            registerMethod(
                className, setterJvm,
                paramDescriptor(listOf(KneParam("value", prop.type))) + "V",
                setterFn,
            )
        }
    }

    // ── enums ────────────────────────────────────────────────────────────────

    private fun appendEnum(enum: KneEnum) {
        // The JVM enum is a plain `enum class` with no native methods (values are handed over as
        // ordinals), so nothing is registered — the class ref is cached for the constructors and
        // ordinal lookups emitted below.
        cacheClass(jvmClassPath(enum.simpleName))
    }

    // ── top-level functions ──────────────────────────────────────────────────

    private fun appendTopLevelFunction(file: FileSpec.Builder, fn: KneFunction, module: KneModule) {
        val fnName = "kne_${fn.name}"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}"
        val hostClass = jvmClassPath(module.libName.replaceFirstChar { it.uppercaseChar() })

        val body = CodeBlock.builder()
        appendTryStart(body, envReconstructParams(fn.params))
        // Fully-qualified call: KotlinPoet cannot emit star imports of the module packages the
        // bare name relied on, and a qualified call also survives multi-package modules.
        val targetExpr = module.packages.firstOrNull()?.let { CodeBlock.of("%L(%L)", "$it.${fn.name}", callArgs(fn.params)) }
            ?: CodeBlock.of("%L(%L)", fn.name, callArgs(fn.params))
        emitReturn(body, targetExpr, fn.returnType)
        appendTryEnd(body, errorReturnExpr(fn.returnType))

        file.addFunction(
            FunSpec.builder(fnName)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameters(nativeParamSpecs(fn.params))
                .apply { nativeReturnDecl(fn.returnType)?.let { returns(it) } }
                .addCode(body.build())
                .build(),
        )
        registerMethod(hostClass, jvmName, paramDescriptor(fn.params) + JniTypes.returnDescriptor(fn.returnType, jvmPackage), fnName)
    }

    // ── extension functions ──────────────────────────────────────────────────

    private fun appendExtensionFunction(file: FileSpec.Builder, fn: KneFunction, module: KneModule) {
        val receiverType = fn.receiverType ?: return
        val receiverSimpleName = when (receiverType) {
            is KneType.OBJECT -> receiverType.simpleName
            is KneType.INTERFACE -> receiverType.simpleName
            else -> return
        }
        val receiverFq = when (receiverType) {
            is KneType.OBJECT -> receiverType.fqName
            is KneType.INTERFACE -> receiverType.fqName
            else -> return
        }
        val fnName = "kne_${receiverSimpleName}_ext_${fn.name}"
        val jvmName = "_kneExt${fn.name.replaceFirstChar { it.uppercaseChar() }}"
        val hostClass = jvmClassPath("KneExt")

        val body = CodeBlock.builder()
        appendTryStart(body, envReconstructParams(fn.params))
        // JNI hands the Java args over in declaration order: the JVM side declares
        // `_kneExtX(receiverHandle: Long, ...params)`, so the receiver comes FIRST here too.
        body.add("val receiver = %L\n", stableRefGet("receiverHandle", receiverFq))
        // Extensions resolve through an explicit %M import (the old file relied on
        // `import <pkg>.*` star imports, which KotlinPoet cannot emit).
        val extCall = module.packages.firstOrNull()
            ?.let { CodeBlock.of("%M(%L)", MemberName(it, fn.name), callArgs(fn.params)) }
            ?: CodeBlock.of("%L(%L)", fn.name, callArgs(fn.params))
        emitReturn(body, CodeBlock.of("receiver.%L", extCall), fn.returnType)
        appendTryEnd(body, errorReturnExpr(fn.returnType))

        file.addFunction(
            FunSpec.builder(fnName)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameter("receiverHandle", PT.LONG)
                .addParameters(nativeParamSpecs(fn.params))
                .apply { nativeReturnDecl(fn.returnType)?.let { returns(it) } }
                .addCode(body.build())
                .build(),
        )
        registerMethod(
            hostClass, jvmName,
            "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + ")" + JniTypes.returnDescriptor(fn.returnType, jvmPackage),
            fnName,
        )
    }

    // ── sealed classes ───────────────────────────────────────────────────────

    /**
     * Keeps the native-side subclass-ordinal helper for future use, but only caches the sealed
     * class ref: the generated JVM sealed base has no `external fun` to bind, and registering a
     * native method on a class that does not declare it makes `RegisterNatives` fail.
     */
    private fun appendSealedOrdinalBridge(cls: KneClass) {
        if (cls.sealedSubclasses.isEmpty()) return
        cacheClass(jvmClassPath(cls.simpleName))
    }

    // ── List<DC> param bridges ───────────────────────────────────────────────

    /**
     * `List<DataClass>` params already cross as a JVM object array that the native side reads
     * element-by-element (`_kneReadJvm<DC>`), so no per-element builder natives are needed. Only the
     * data class refs have to exist on the native side, which the DC constructors already cover.
     */
    private fun collectDcListParamTypes(module: KneModule): Set<KneType.DATA_CLASS> {
        val result = mutableSetOf<KneType.DATA_CLASS>()
        fun scan(type: KneType) {
            val inner = JniTypes.unwrapNullable(type)
            when (inner) {
                is KneType.LIST -> if (inner.elementType is KneType.DATA_CLASS) result.add(inner.elementType)
                is KneType.SET -> if (inner.elementType is KneType.DATA_CLASS) result.add(inner.elementType)
                else -> {}
            }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { fn -> fn.params.forEach { scan(it.type) } }
            cls.companionMethods.forEach { fn -> fn.params.forEach { scan(it.type) } }
        }
        module.functions.forEach { fn -> fn.params.forEach { scan(it.type) } }
        return result
    }

    // ══════════════════════════════════════════════════════════════════════════
    // suspend / flow
    // ══════════════════════════════════════════════════════════════════════════

    private fun appendSuspendMethod(file: FileSpec.Builder, fn: KneFunction, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)
        val fnName = "kne_${cls.simpleName}_${fn.name}Async"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}Async"

        val body = CodeBlock.builder()
        appendTryStart(body, envReconstructParams(fn.params))
        body.add("val obj = %L\n", stableRefGet("handle", cls.fqName))
        body.add("val _completion = _kneGlobal(env, completion)\n")
        body.add("val _job = %T()\n", JOB)
        body.add(
            "%T(_job + %M.Default). %M(start = %T.ATOMIC) {\n" +
                "    try {\n" +
                "        val _result = obj.%L(%L)\n" +
                "        _kneAttached { _e -> %L }\n" +
                "    } catch (e: %T) {\n" +
                "        _kneAttached { _e -> _kneSuspendCancelled(_e, _completion!!) }\n" +
                "    } catch (e: Throwable) {\n" +
                "        val _msg = e.message ?: e::class.simpleName ?: %S\n" +
                "        _kneAttached { _e -> _kneSuspendError(_e, _completion!!, _msg) }\n" +
                "    } finally {\n" +
                "        _kneAttached { _e -> _kneFns(_e).DeleteGlobalRef!!.%M(_e, _completion) }\n" +
                "    }\n" +
                "}\n",
            COROUTINE_SCOPE, M_DISPATCHERS, M_LAUNCH, COROUTINE_START,
            fn.name, callArgs(fn.params),
            suspendOkCall(fn.returnType, CodeBlock.of("_result")),
            CANCELLATION_EXCEPTION,
            "Unknown error", M_INVOKE,
        )
        body.add("return %L\n", stableRefCreate(CodeBlock.of("_job")))
        appendTryEnd(body, "0L")

        file.addFunction(
            FunSpec.builder(fnName)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameter("handle", PT.LONG)
                .addParameters(nativeParamSpecs(fn.params))
                .addParameter("completion", flavor.jobject.copy(nullable = true))
                .returns(PT.LONG)
                .addCode(body.build())
                .build(),
        )
        registerMethod(
            className, jvmName,
            "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + "Ljava/lang/Object;)J",
            fnName,
        )
    }

    /** The (single-line) call delivering a suspend success payload of [type] to the completion object. */
    private fun suspendOkCall(type: KneType, valueExpr: CodeBlock): CodeBlock {
        val id = payloadId(type)
        if (type == KneType.UNIT) return CodeBlock.of("_kneSuspendOk_Void(_e, _completion!!)")
        val args = suspendPayloadArg(type, valueExpr)
        return CodeBlock.of("_kneSuspendOk_%L(_e, _completion!!, %L)", id, args)
    }

    /**
     * JNI payload arg expressions for a suspend/flow success value.
     *
     * [envExpr] must be the JNIEnv valid at the point of use: suspend/flow completion runs on a
     * coroutine worker thread, so it is the `_e` handed in by `_kneAttached`, never the caller's
     * `env` (using the latter silently builds arrays on the wrong thread → garbage/null payloads).
     */
    private fun suspendPayloadArg(type: KneType, valueExpr: CodeBlock, envExpr: String = "_e"): CodeBlock = when {
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING -> CodeBlock.of("%L?.let { _kneKToJ(%L, it) }", valueExpr, envExpr)
            KneType.BOOLEAN -> CodeBlock.of("if (%L != null) (if (%L) 1 else 0) else -1", valueExpr, valueExpr)
            KneType.INT -> CodeBlock.of("%L?.toLong() ?: Long.MIN_VALUE", valueExpr)
            KneType.LONG -> CodeBlock.of("%L ?: Long.MIN_VALUE", valueExpr)
            KneType.SHORT -> CodeBlock.of("%L?.toInt() ?: Int.MIN_VALUE", valueExpr)
            KneType.BYTE -> CodeBlock.of("%L?.toInt() ?: Int.MIN_VALUE", valueExpr)
            KneType.FLOAT -> CodeBlock.of("%L?.toRawBits()?.toLong() ?: Long.MIN_VALUE", valueExpr)
            KneType.DOUBLE -> CodeBlock.of("%L?.toRawBits() ?: Long.MIN_VALUE", valueExpr)
            is KneType.ENUM -> CodeBlock.of("%L?.ordinal ?: -1", valueExpr)
            KneType.BYTE_ARRAY -> CodeBlock.of("%L?.let { _kneNewJBytes(%L, it) }", valueExpr, envExpr)
            is KneType.LIST -> CodeBlock.of("%L?.let { %L }", valueExpr, elemArrayBuildExpr(CodeBlock.of("it"), type.inner.elementType, envExpr))
            is KneType.SET -> CodeBlock.of("%L?.let { %L }", valueExpr, elemArrayBuildExpr(CodeBlock.of("it.toList()"), type.inner.elementType, envExpr))
            is KneType.MAP -> CodeBlock.of("%L?.let { %L }", valueExpr, mapBuildExpr(CodeBlock.of("it"), type.inner, envExpr))
            is KneType.OBJECT, is KneType.INTERFACE ->
                CodeBlock.of("%L?.let { %L } ?: 0L", valueExpr, stableRefCreate(valueExpr))
            is KneType.DATA_CLASS -> CodeBlock.of("%L?.let { _kneNewJvm%L(%L, it) }", valueExpr, type.inner.simpleName, envExpr)
            else -> payloadArgExpr(type.inner, valueExpr, envExpr)
        }
        else -> payloadArgExpr(type, valueExpr, envExpr)
    }

    private fun payloadArgExpr(type: KneType, valueExpr: CodeBlock, envExpr: String = "_e"): CodeBlock = when (type) {
        KneType.INT -> valueExpr
        KneType.LONG -> valueExpr
        KneType.DOUBLE -> valueExpr
        KneType.FLOAT -> valueExpr
        KneType.BOOLEAN -> CodeBlock.of("if (%L) 1u else 0u", valueExpr)
        KneType.BYTE -> valueExpr
        KneType.SHORT -> valueExpr
        KneType.STRING -> CodeBlock.of("_kneKToJ(%L, %L)", envExpr, valueExpr)
        KneType.BYTE_ARRAY -> CodeBlock.of("_kneNewJBytes(%L, %L)", envExpr, valueExpr)
        is KneType.ENUM -> CodeBlock.of("%L.ordinal", valueExpr)
        is KneType.OBJECT, is KneType.INTERFACE -> stableRefCreate(valueExpr)
        is KneType.DATA_CLASS -> CodeBlock.of("_kneNewJvm%L(%L, %L)", type.simpleName, envExpr, valueExpr)
        is KneType.LIST -> elemArrayBuildExpr(valueExpr, type.elementType, envExpr)
        is KneType.SET -> elemArrayBuildExpr(CodeBlock.of("%L.toList()", valueExpr), type.elementType, envExpr)
        is KneType.MAP -> mapBuildExpr(valueExpr, type, envExpr)
        KneType.UNIT -> CodeBlock.of("Unit")
        else -> CodeBlock.of("null")
    }

    /** Short payload id used in generated helper names and JVM dispatcher class/method names. */
    private fun payloadId(type: KneType): String = when {
        type is KneType.NULLABLE -> "N" + payloadId(type.inner)
        else -> when (type) {
            KneType.INT -> "Int"
            KneType.LONG -> "Long"
            KneType.DOUBLE -> "Double"
            KneType.FLOAT -> "Float"
            KneType.BOOLEAN -> "Boolean"
            KneType.BYTE -> "Byte"
            KneType.SHORT -> "Short"
            KneType.STRING -> "String"
            KneType.BYTE_ARRAY -> "Bytes"
            KneType.UNIT -> "Void"
            is KneType.ENUM -> "E" + type.simpleName
            is KneType.OBJECT -> "H" + type.simpleName
            is KneType.INTERFACE -> "I" + type.simpleName
            is KneType.DATA_CLASS -> "D" + type.simpleName
            is KneType.LIST -> "L" + payloadId(type.elementType)
            is KneType.SET -> "S" + payloadId(type.elementType)
            is KneType.MAP -> "M" + payloadId(type.keyType) + "_" + payloadId(type.valueType)
            else -> "X"
        }
    }

    /** JVM-side `onSuccess` signature for a suspend payload id. */
    private fun successJvmSig(type: KneType): String = when {
        type is KneType.NULLABLE -> when (val inner = type.inner) {
            KneType.STRING -> "(Ljava/lang/String;)V"
            KneType.BYTE_ARRAY -> "([B)V"
            is KneType.LIST -> "(${JniTypes.arrayDescriptor(inner.elementType)})V"
            is KneType.SET -> "(${JniTypes.arrayDescriptor(inner.elementType)})V"
            is KneType.MAP -> "(Ljava/util/Map;)V"
            KneType.BOOLEAN -> "(I)V"
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "(J)V"
            KneType.SHORT, KneType.BYTE -> "(I)V"
            is KneType.ENUM -> "(I)V"
            is KneType.OBJECT, is KneType.INTERFACE -> "(J)V"
            is KneType.DATA_CLASS -> "(L${inner.jvmClassPath()};)V"
            else -> "(Ljava/lang/Object;)V"
        }
        else -> when (type) {
            KneType.INT -> "(I)V"
            KneType.LONG -> "(J)V"
            KneType.DOUBLE -> "(D)V"
            KneType.FLOAT -> "(F)V"
            KneType.BOOLEAN -> "(Z)V"
            KneType.BYTE -> "(B)V"
            KneType.SHORT -> "(S)V"
            KneType.STRING -> "(Ljava/lang/String;)V"
            KneType.BYTE_ARRAY -> "([B)V"
            KneType.UNIT -> "()V"
            is KneType.ENUM -> "(I)V"
            is KneType.OBJECT, is KneType.INTERFACE -> "(J)V"
            is KneType.DATA_CLASS -> "(L${type.jvmClassPath()};)V"
            is KneType.LIST -> "(${JniTypes.arrayDescriptor(type.elementType)})V"
            is KneType.SET -> "(${JniTypes.arrayDescriptor(type.elementType)})V"
            is KneType.MAP -> "(Ljava/util/Map;)V"
            else -> "(Ljava/lang/Object;)V"
        }
    }

    /** The native Kotlin param for a suspend/flow payload (matches the JVM dispatcher param). */
    private fun payloadNativeParam(type: KneType): TypeName = when {
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> flavor.jobject.copy(nullable = true)
            is KneType.LIST, is KneType.SET, is KneType.MAP -> flavor.jobject.copy(nullable = true)
            KneType.BOOLEAN, is KneType.ENUM -> PT.INT
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> PT.LONG
            KneType.SHORT, KneType.BYTE -> PT.INT
            is KneType.OBJECT, is KneType.INTERFACE -> PT.LONG
            is KneType.DATA_CLASS -> flavor.jobject.copy(nullable = true)
            else -> flavor.jobject.copy(nullable = true)
        }
        else -> when (type) {
            KneType.INT -> PT.INT
            KneType.LONG -> PT.LONG
            KneType.DOUBLE -> PT.DOUBLE
            KneType.FLOAT -> PT.FLOAT
            KneType.BOOLEAN -> UBYTE
            KneType.BYTE -> PT.BYTE
            KneType.SHORT -> PT.SHORT
            KneType.STRING, KneType.BYTE_ARRAY -> flavor.jobject.copy(nullable = true)
            is KneType.LIST, is KneType.SET, is KneType.MAP -> flavor.jobject.copy(nullable = true)
            is KneType.ENUM -> PT.INT
            is KneType.OBJECT, is KneType.INTERFACE -> PT.LONG
            is KneType.DATA_CLASS -> flavor.jobject.copy(nullable = true)
            else -> flavor.jobject.copy(nullable = true)
        }
    }

    /** Generates `_kneSuspendOk_<id>` delivering a completed value to `completion.onSuccess`. */
    private fun buildSuspendOkHelper(type: KneType): FunSpec {
        val id = payloadId(type)
        if (type == KneType.UNIT) {
            return FunSpec.builder("_kneSuspendOk_Void")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("completion", flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val mid = _kneCompletionMid(env, completion, %S, %S) ?: return\n" +
                            "_kneFns(env).CallVoidMethodA!!.invoke(env, completion, mid, null)\n",
                        "onSuccess", "()V",
                    ),
                )
                .build()
        }
        return FunSpec.builder("_kneSuspendOk_$id")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
            .addParameter("completion", flavor.jobject.copy(nullable = true))
            .addParameter("value", payloadNativeParam(type))
            .addCode(
                CodeBlock.of(
                    "val fns = _kneFns(env)\n" +
                        "val mid = _kneCompletionMid(env, completion, %S, %S) ?: return\n" +
                        "%M {\n" +
                        "    val args = allocArray<%T>(1)\n" +
                        "    args[0].%L = %L\n" +
                        "    fns.CallVoidMethodA!!.invoke(env, completion, mid, args)\n" +
                        "}\n",
                    "onSuccess", successJvmSig(type),
                    M_MEM_SCOPED, flavor.jvalue, jvalueSlot(type), payloadJvalueExpr(type, "value"),
                ),
            )
            .build()
    }

    /** Generates `_kneFlowNext_<id>` delivering a flow element to `callback.onNext`. */
    private fun buildFlowNextHelper(type: KneType): FunSpec {
        val id = payloadId(type)
        if (type == KneType.UNIT) {
            // Flow<Unit>: native calls the dispatcher's `onNext()` with no argument, matching the
            // parameterless `onNext()` generated on the JVM side.
            return FunSpec.builder("_kneFlowNext_$id")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("callback", flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val mid = _kneCompletionMid(env, callback, %S, %S) ?: return\n" +
                            "_kneFns(env).CallVoidMethodA!!.invoke(env, callback, mid, null)\n",
                        "onNext", "()V",
                    ),
                )
                .build()
        }
        return FunSpec.builder("_kneFlowNext_$id")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
            .addParameter("callback", flavor.jobject.copy(nullable = true))
            .addParameter("value", payloadNativeParam(type))
            .addCode(
                CodeBlock.of(
                    "val fns = _kneFns(env)\n" +
                        "val mid = _kneCompletionMid(env, callback, %S, %S) ?: return\n" +
                        "%M {\n" +
                        "    val args = allocArray<%T>(1)\n" +
                        "    args[0].%L = %L\n" +
                        "    fns.CallVoidMethodA!!.invoke(env, callback, mid, args)\n" +
                        "}\n",
                    "onNext", successJvmSig(type),
                    M_MEM_SCOPED, flavor.jvalue, jvalueSlot(type), payloadJvalueExpr(type, "value"),
                ),
            )
            .build()
    }

    /** jvalue union member for a payload type. */
    private fun jvalueSlot(type: KneType): String = when {
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> "l"
            is KneType.LIST, is KneType.SET, is KneType.MAP -> "l"
            is KneType.DATA_CLASS -> "l"
            is KneType.OBJECT, is KneType.INTERFACE -> "j"
            KneType.BOOLEAN, is KneType.ENUM, KneType.SHORT, KneType.BYTE -> "i" // sentinel Ints
            else -> "j" // widened Long bits
        }
        else -> when (type) {
            KneType.INT, is KneType.ENUM -> "i"
            KneType.LONG, is KneType.OBJECT, is KneType.INTERFACE -> "j"
            KneType.DOUBLE -> "d"
            KneType.FLOAT -> "f"
            KneType.BOOLEAN -> "z"
            KneType.BYTE -> "b"
            KneType.SHORT -> "s"
            else -> "l"
        }
    }

    /** Expression converting a native payload value into a jvalue member value. */
    private fun payloadJvalueExpr(type: KneType, valueExpr: String): CodeBlock = when {
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> CodeBlock.of("%L", valueExpr) // widened Long bits
            KneType.BOOLEAN, is KneType.ENUM, KneType.SHORT, KneType.BYTE -> CodeBlock.of("%L", valueExpr) // sentinel Ints
            else -> payloadJvalueExpr(type.inner, valueExpr) // object refs / nulls pass through
        }
        else -> when (type) {
            KneType.INT, is KneType.ENUM -> CodeBlock.of("%L", valueExpr)
            KneType.LONG, is KneType.OBJECT, is KneType.INTERFACE -> CodeBlock.of("%L", valueExpr)
            KneType.DOUBLE -> CodeBlock.of("%L", valueExpr)
            KneType.FLOAT -> CodeBlock.of("%L", valueExpr)
            KneType.BOOLEAN -> CodeBlock.of("if (%L != 0.toUByte()) 1u else 0u", valueExpr)
            KneType.BYTE -> CodeBlock.of("%L", valueExpr)
            KneType.SHORT -> CodeBlock.of("%L", valueExpr)
            else -> CodeBlock.of("%L", valueExpr)
        }
    }

    private fun buildSuspendErrorCancelledHelpers(file: FileSpec.Builder) {
        val messageParams = listOf(
            ParameterSpec.builder("env", KneCinteropTypes.pointerTo(flavor.envVar)).build(),
            ParameterSpec.builder("completion", flavor.jobject.copy(nullable = true)).build(),
            ParameterSpec.builder("message", PT.STRING).build(),
        )
        val flowMessageParams = listOf(
            ParameterSpec.builder("env", KneCinteropTypes.pointerTo(flavor.envVar)).build(),
            ParameterSpec.builder("callback", flavor.jobject.copy(nullable = true)).build(),
            ParameterSpec.builder("message", PT.STRING).build(),
        )
        val callbackParams = listOf(
            ParameterSpec.builder("env", KneCinteropTypes.pointerTo(flavor.envVar)).build(),
            ParameterSpec.builder("callback", flavor.jobject.copy(nullable = true)).build(),
        )
        val completionParams = listOf(
            ParameterSpec.builder("env", KneCinteropTypes.pointerTo(flavor.envVar)).build(),
            ParameterSpec.builder("completion", flavor.jobject.copy(nullable = true)).build(),
        )
        val errorBodyFor = { target: String ->
            CodeBlock.of(
                "val mid = _kneCompletionMid(env, %L, %S, %S) ?: return\n" +
                    "%M {\n" +
                    "    val args = allocArray<%T>(1)\n" +
                    "    args[0].l = _kneKToJ(env, message)\n" +
                    "    _kneFns(env).CallVoidMethodA!!.invoke(env, %L, mid, args)\n" +
                    "}\n",
                target, "onError", "(Ljava/lang/String;)V", M_MEM_SCOPED, flavor.jvalue, target,
            )
        }
        val signalBodyFor = { target: String, method: String ->
            CodeBlock.of(
                "val mid = _kneCompletionMid(env, %L, %S, %S) ?: return\n" +
                    "_kneFns(env).CallVoidMethodA!!.invoke(env, %L, mid, null)\n",
                target, method, "()V", target,
            )
        }
        file.addFunction(
            FunSpec.builder("_kneSuspendError")
                .addModifiers(KModifier.INTERNAL)
                .addParameters(messageParams)
                .addCode(errorBodyFor("completion"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneSuspendCancelled")
                .addModifiers(KModifier.INTERNAL)
                .addParameters(completionParams)
                .addCode(signalBodyFor("completion", "onCancelled"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneFlowError")
                .addModifiers(KModifier.INTERNAL)
                .addParameters(flowMessageParams)
                .addCode(errorBodyFor("callback"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneFlowComplete")
                .addModifiers(KModifier.INTERNAL)
                .addParameters(callbackParams)
                .addCode(signalBodyFor("callback", "onComplete"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneFlowCancelled")
                .addModifiers(KModifier.INTERNAL)
                .addParameters(callbackParams)
                .addCode(signalBodyFor("callback", "onCancelled"))
                .build(),
        )
    }

    private fun appendFlowMethod(file: FileSpec.Builder, fn: KneFunction, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)
        val fnName = "kne_${cls.simpleName}_${fn.name}Flow"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}Flow"
        val elemType = (fn.returnType as KneType.FLOW).elementType

        val body = CodeBlock.builder()
        appendTryStart(body, envReconstructParams(fn.params))
        body.add("val obj = %L\n", stableRefGet("handle", cls.fqName))
        body.add("val _callback = _kneGlobal(env, callback)\n")
        body.add("val _job = %T()\n", JOB)
        body.add("val _credits = %T<%T>(%T.UNLIMITED)\n", CHANNEL, PT.UNIT, CHANNEL)
        body.add("repeat(%L) { _credits.trySend(Unit) }\n", FLOW_INITIAL_CREDITS)
        body.add(
            "%T(_job + %M.Default). %M(start = %T.ATOMIC) {\n" +
                "    try {\n" +
                "        val _flow = obj.%L(%L)\n" +
                "        _flow.collect { _value ->\n" +
                "            _credits.receive()\n" +
                "            _kneAttached { _e -> %L }\n" +
                "        }\n" +
                "        _kneAttached { _e -> _kneFlowComplete(_e, _callback!!) }\n" +
                "    } catch (e: %T) {\n" +
                "        _kneAttached { _e -> _kneFlowCancelled(_e, _callback!!) }\n" +
                "    } catch (e: Throwable) {\n" +
                "        val _msg = e.message ?: e::class.simpleName ?: %S\n" +
                "        _kneAttached { _e -> _kneFlowError(_e, _callback!!, _msg) }\n" +
                "    } finally {\n" +
                "        _kneAttached { _e -> _kneFns(_e).DeleteGlobalRef!!.%M(_e, _callback) }\n" +
                "    }\n" +
                "}\n",
            COROUTINE_SCOPE, M_DISPATCHERS, M_LAUNCH, COROUTINE_START,
            fn.name, callArgs(fn.params),
            flowNextCall(elemType, CodeBlock.of("_value")),
            CANCELLATION_EXCEPTION,
            "Unknown error", M_INVOKE,
        )
        body.add("return %L\n", stableRefCreate(CodeBlock.of("KneFlowJob(_job, _credits)")))
        appendTryEnd(body, "0L")

        file.addFunction(
            FunSpec.builder(fnName)
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameter("handle", PT.LONG)
                .addParameters(nativeParamSpecs(fn.params))
                .addParameter("callback", flavor.jobject.copy(nullable = true))
                .returns(PT.LONG)
                .addCode(body.build())
                .build(),
        )
        registerMethod(
            className, jvmName,
            "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + "Ljava/lang/Object;)J",
            fnName,
        )
    }

    private fun flowNextCall(elemType: KneType, valueExpr: CodeBlock): CodeBlock =
        // Flow<Unit>: the payload-less helper / dispatcher overload (see buildFlowNextHelper).
        if (elemType == KneType.UNIT) CodeBlock.of("_kneFlowNext_%L(_e, _callback!!)", payloadId(elemType))
        else CodeBlock.of(
            "_kneFlowNext_%L(_e, _callback!!, %L)", payloadId(elemType), suspendPayloadArg(elemType, valueExpr),
        )

    // ── suspend/flow type collection ─────────────────────────────────────────

    private fun suspendReturnTypes(module: KneModule): Set<KneType> {
        val result = mutableSetOf<KneType>()
        fun scan(fn: KneFunction) {
            if (fn.isSuspend) result.add(fn.returnType)
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { scan(it) }
            cls.companionMethods.forEach { scan(it) }
        }
        module.functions.forEach { scan(it) }
        return result
    }

    private fun flowElemTypes(module: KneModule): Set<KneType> {
        val result = mutableSetOf<KneType>()
        fun scan(type: KneType) {
            if (type is KneType.FLOW) result.add(type.elementType)
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { scan(it.returnType) }
            cls.companionMethods.forEach { scan(it.returnType) }
        }
        module.functions.forEach { scan(it.returnType) }
        return result
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Callbacks
    // ══════════════════════════════════════════════════════════════════════════

    private fun collectCallbackSignatures(module: KneModule): Set<KneType.FUNCTION> {
        val signatures = mutableSetOf<KneType.FUNCTION>()
        fun scanParams(params: List<KneParam>) {
            params.forEach { p ->
                if (p.type is KneType.FUNCTION) signatures.add(p.type)
                else if (p.type is KneType.NULLABLE && (p.type as KneType.NULLABLE).inner is KneType.FUNCTION)
                    signatures.add((p.type as KneType.NULLABLE).inner as KneType.FUNCTION)
            }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { scanParams(it.params) }
            cls.companionMethods.forEach { scanParams(it.params) }
        }
        module.functions.forEach { scanParams(it.params) }
        return signatures
    }

    private fun callbackId(fnType: KneType.FUNCTION): String = JniTypes.functionId(fnType)

    /** Function types returned by the API: the JVM side needs an invoke bridge for each. */
    private fun collectFunctionReturnTypes(module: KneModule): List<KneType.FUNCTION> {
        val result = LinkedHashSet<KneType.FUNCTION>()
        fun scan(t: KneType) {
            when (t) {
                is KneType.FUNCTION -> result.add(t)
                is KneType.NULLABLE -> scan(t.inner)
                else -> {}
            }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { scan(it.returnType) }
            cls.companionMethods.forEach { scan(it.returnType) }
        }
        module.functions.forEach { scan(it.returnType) }
        return result.toList()
    }

    /**
     * Generates `kne_invokeFn_<id>` registered on `KneNativeBindings` as `_kneInvokeFn<id>`:
     * lets the JVM call back into a native lambda that a method returned as an opaque handle.
     */
    private fun buildFnInvokeBridge(fnType: KneType.FUNCTION): FunSpec {
        val id = JniTypes.functionId(fnType)
        val hostClass = jvmClassPath("KneNativeBindings")
        val params = fnType.paramTypes.mapIndexed { i, t -> KneParam("p$i", t) }
        val fnName = "kne_invokeFn_$id"

        val body = CodeBlock.builder()
        appendTryStart(body, envReconstructParams(params))
        body.add("val _fn = %L\n", stableRefGetTyped(CodeBlock.of("handle"), nativePublic(fnType)))
        emitReturn(body, CodeBlock.of("_fn(%L)", callArgs(params)), fnType.returnType)
        appendTryEnd(body, errorReturnExpr(fnType.returnType))

        registerMethod(
            hostClass, "_kneInvokeFn$id",
            "(J" + paramDescriptor(params).drop(1).dropLast(1) + ")" +
                JniTypes.returnDescriptor(fnType.returnType, jvmPackage),
            fnName,
        )
        return FunSpec.builder(fnName)
            .addModifiers(KModifier.INTERNAL)
            .addParameters(jniPrefixParams())
            .addParameter("handle", PT.LONG)
            .addParameters(nativeParamSpecs(params))
            .apply { nativeReturnDecl(fnType.returnType)?.let { returns(it) } }
            .addCode(body.build())
            .build()
    }

    /**
     * Generates `_kneInvoke_<id>`: calls the JVM dispatcher's `invoke` method with
     * native payload values. A JVM exception thrown by the callback is left pending;
     * the caller unwinds via the sentinel and it propagates on native return.
     */
    private fun buildCallbackInvokeHelper(fnType: KneType.FUNCTION): FunSpec {
        val id = callbackId(fnType)
        val invokeSig = buildString {
            append("(")
            fnType.paramTypes.forEach { t ->
                append(callbackParamJvmSig(t))
            }
            append(")${callbackReturnJvmSig(fnType.returnType)}")
        }

        val callKind = callbackCallKind(fnType.returnType)
        val argc = fnType.paramTypes.size

        val body = CodeBlock.builder()
        body.add("if (fn == null) return %L\n", callbackNativeErrorReturn(fnType.returnType))
        body.add("val fns = _kneFns(env)\n")
        body.add("val clazz = fns.GetObjectClass!!.invoke(env, fn)\n")
        body.add(
            "val mid = _kneMethodId(env, clazz, %S, %S) ?: return %L\n",
            "invoke", invokeSig, callbackNativeErrorReturn(fnType.returnType),
        )
        body.add("%M {\n", M_MEM_SCOPED)
        body.indent()
        body.add("val args = allocArray<%T>(maxOf(1, %L))\n", flavor.jvalue, argc)
        fnType.paramTypes.forEachIndexed { i, t ->
            body.add("args[%L].%L = %L\n", i, callbackJvalueSlot(t), callbackPayloadJvalueExpr(t, "p$i"))
        }
        // A throwing JVM callback makes Call*MethodA return NULL with a pending exception. The
        // result must never be handed to another JNI call in that state: GetObjectClass /
        // GetStringUTFChars / GetArrayLength on null segfault the VM. Unwind via the pending
        // protocol instead — the outer bridge catch lets the original exception propagate.
        fun callAndGuard(callExpr: CodeBlock, retExpr: CodeBlock) {
            body.add("val _r = %L\n", callExpr)
            body.add("if (_knePending(env)) throw KneJniCallbackPendingException()\n")
            body.add("return %L\n", retExpr)
        }
        when (callKind) {
            "VOID" -> {
                body.add("fns.CallVoidMethodA!!.invoke(env, fn, mid, args)\n")
                body.add("if (_knePending(env)) throw KneJniCallbackPendingException()\n")
            }
            "INT" -> callAndGuard(CodeBlock.of("fns.CallIntMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
            "LONG" -> callAndGuard(CodeBlock.of("fns.CallLongMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
            "DOUBLE" -> callAndGuard(CodeBlock.of("fns.CallDoubleMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
            "FLOAT" -> callAndGuard(CodeBlock.of("fns.CallFloatMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
            "UBYTE" -> callAndGuard(CodeBlock.of("fns.CallBooleanMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
            "BYTE" -> callAndGuard(CodeBlock.of("fns.CallByteMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
            "SHORT" -> callAndGuard(CodeBlock.of("fns.CallShortMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
            "STRING" -> callAndGuard(CodeBlock.of("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_kneJToK(env, _r)"))
            "BYTES" -> callAndGuard(CodeBlock.of("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_kneReadJBytes(env, _r)"))
            "HANDLE_OBJ" -> callAndGuard(CodeBlock.of("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_kneReadJvmHandle(env, _r)"))
            "DC_RET" -> callAndGuard(
                CodeBlock.of("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)"),
                CodeBlock.of("_kneReadJvm%L(env, _r)", (fnType.returnType as? KneType.DATA_CLASS)?.simpleName ?: "Todo"),
            )
            "COLL" -> {
                body.add("val _arr = fns.CallObjectMethodA!!.invoke(env, fn, mid, args)\n")
                body.add("if (_knePending(env)) throw KneJniCallbackPendingException()\n")
            }
            "MAP_PAIR" -> {
                body.add("val _pair = fns.CallObjectMethodA!!.invoke(env, fn, mid, args)\n")
                body.add("if (_knePending(env)) throw KneJniCallbackPendingException()\n")
            }
            else -> callAndGuard(CodeBlock.of("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)"), CodeBlock.of("_r"))
        }
        if (callKind == "COLL") {
            body.add("return %L\n", callbackCollUnwrap(fnType.returnType))
        }
        if (callKind == "MAP_PAIR") {
            body.add("val _keys = _kneObjArrGet(env, _pair, 0)\n")
            body.add("val _vals = _kneObjArrGet(env, _pair, 1)\n")
            body.add("return %L\n", callbackMapUnwrap(fnType.returnType as KneType.MAP))
        }
        body.unindent()
        body.add("}\n")

        val spec = FunSpec.builder("_kneInvoke_$id")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
            .addParameter("fn", flavor.jobject.copy(nullable = true))
        fnType.paramTypes.forEachIndexed { i, t ->
            spec.addParameter(ParameterSpec.builder("p$i", callbackPayloadNativeType(t)).build())
        }
        callbackNativeReturnDecl(fnType.returnType)?.let { spec.returns(it) }
        return spec.addCode(body.build()).build()
    }

    private fun callbackParamJvmSig(t: KneType): String = when (t) {
        KneType.INT -> "I"
        KneType.LONG -> "J"
        KneType.DOUBLE -> "D"
        KneType.FLOAT -> "F"
        KneType.BOOLEAN -> "Z"
        KneType.BYTE -> "B"
        KneType.SHORT -> "S"
        KneType.STRING -> "Ljava/lang/String;"
        is KneType.ENUM -> "I" // dispatcher converts ordinal
        KneType.BYTE_ARRAY -> "[B"
        is KneType.OBJECT -> "L${t.jvmClassPath()};"
        is KneType.INTERFACE -> "L${t.jvmClassPath()};"
        is KneType.DATA_CLASS -> "L${t.jvmClassPath()};" // constructed via NewObjectA
        is KneType.LIST -> JniTypes.arrayDescriptor(t.elementType)
        is KneType.SET -> JniTypes.arrayDescriptor(t.elementType)
        is KneType.MAP -> "Ljava/util/Map;" // constructed HashMap
        else -> "Ljava/lang/Object;"
    }

    private fun callbackReturnJvmSig(t: KneType): String = when (t) {
        KneType.UNIT -> "V"
        KneType.INT -> "I"
        KneType.LONG -> "J"
        KneType.DOUBLE -> "D"
        KneType.FLOAT -> "F"
        KneType.BOOLEAN -> "Z"
        KneType.BYTE -> "B"
        KneType.SHORT -> "S"
        KneType.STRING -> "Ljava/lang/String;"
        KneType.BYTE_ARRAY -> "[B"
        is KneType.ENUM -> "I" // dispatcher returns ordinal
        is KneType.OBJECT, is KneType.INTERFACE -> "Ljava/lang/Object;" // proxy → handle read
        is KneType.DATA_CLASS -> "Ljava/lang/Object;" // read via field IDs
        is KneType.LIST, is KneType.SET -> "Ljava/lang/Object;" // packed array(s)
        is KneType.MAP -> "Ljava/lang/Object;" // Object[]{keysArr, valuesArr}
        else -> "Ljava/lang/Object;"
    }

    private fun callbackCallKind(t: KneType): String = when (t) {
        KneType.UNIT -> "VOID"
        KneType.INT, is KneType.ENUM -> "INT"
        KneType.LONG -> "LONG"
        KneType.DOUBLE -> "DOUBLE"
        KneType.FLOAT -> "FLOAT"
        KneType.BOOLEAN -> "UBYTE"
        KneType.BYTE -> "BYTE"
        KneType.SHORT -> "SHORT"
        KneType.STRING -> "STRING"
        KneType.BYTE_ARRAY -> "BYTES"
        is KneType.OBJECT, is KneType.INTERFACE -> "HANDLE_OBJ"
        is KneType.DATA_CLASS -> "DC_RET"
        is KneType.LIST, is KneType.SET -> "COLL"
        is KneType.MAP -> "MAP_PAIR"
        else -> "OBJECT"
    }

    /** Unwrap expression for a returned List/Set jarray (references local `_arr`). */
    private fun callbackCollUnwrap(t: KneType): CodeBlock {
        val elem = if (t is KneType.LIST) t.elementType else (t as KneType.SET).elementType
        val list: CodeBlock = when (elem) {
            KneType.INT -> CodeBlock.of("_kneReadJIntArray(env, _arr)?.toList() ?: emptyList()")
            KneType.LONG -> CodeBlock.of("_kneReadJLongArray(env, _arr)?.toList() ?: emptyList()")
            KneType.DOUBLE -> CodeBlock.of("_kneReadJDoubleArray(env, _arr)?.toList() ?: emptyList()")
            KneType.FLOAT -> CodeBlock.of("_kneReadJFloatArray(env, _arr)?.toList() ?: emptyList()")
            KneType.SHORT -> CodeBlock.of("_kneReadJShortArray(env, _arr)?.toList() ?: emptyList()")
            KneType.BYTE -> CodeBlock.of("_kneReadJBytes(env, _arr)?.toList() ?: emptyList()")
            KneType.BOOLEAN -> CodeBlock.of("_kneReadJBooleanArray(env, _arr)?.toList() ?: emptyList()")
            is KneType.ENUM -> CodeBlock.of("_kneReadJIntArray(env, _arr)?.toList()?.map { %T.entries[it] } ?: emptyList()", kotlinType(elem.fqName))
            KneType.STRING -> CodeBlock.of("_kneReadJStrList(env, _arr) ?: emptyList()")
            is KneType.OBJECT -> CodeBlock.of("_kneReadJLongArray(env, _arr)?.map { %T.fromNativeHandle(it) } ?: emptyList()", kotlinType(elem.fqName))
            is KneType.INTERFACE -> CodeBlock.of("_kneReadJLongArray(env, _arr)?.map { %T.fromNativeHandle(it) } ?: emptyList()", kotlinType(elem.fqName))
            KneType.BYTE_ARRAY -> CodeBlock.of("_kneReadJBytesList(env, _arr) ?: emptyList()")
            is KneType.DATA_CLASS -> CodeBlock.of("_kneReadJLongArray(env, _arr)") // DC lists arrive via listparam bridges
            else -> CodeBlock.of("_arr?.toList() ?: emptyList()")
        }
        return if (t is KneType.SET) CodeBlock.of("(%L ?: emptyList()).toSet()", list) else CodeBlock.of("%L ?: emptyList()", list)
    }

    /** Unwrap expression for a returned Map (references locals `_keys`/`_vals` payload arrays). */
    private fun callbackMapUnwrap(t: KneType.MAP): CodeBlock {
        val keys = keyValList(t.keyType, "_keys")
        val vals = keyValList(t.valueType, "_vals")
        return CodeBlock.of("(%L).zip(%L).toMap()", keys, vals)
    }

    private fun keyValList(t: KneType, arrName: String): CodeBlock = when (t) {
        KneType.INT -> CodeBlock.of("_kneReadJIntArray(env, %L)?.toList() ?: emptyList()", arrName)
        KneType.LONG -> CodeBlock.of("_kneReadJLongArray(env, %L)?.toList() ?: emptyList()", arrName)
        KneType.DOUBLE -> CodeBlock.of("_kneReadJDoubleArray(env, %L)?.toList() ?: emptyList()", arrName)
        KneType.FLOAT -> CodeBlock.of("_kneReadJFloatArray(env, %L)?.toList() ?: emptyList()", arrName)
        KneType.SHORT -> CodeBlock.of("_kneReadJShortArray(env, %L)?.toList() ?: emptyList()", arrName)
        KneType.BYTE -> CodeBlock.of("_kneReadJBytes(env, %L)?.toList() ?: emptyList()", arrName)
        KneType.BOOLEAN -> CodeBlock.of("_kneReadJBooleanArray(env, %L)?.toList() ?: emptyList()", arrName)
        is KneType.ENUM -> CodeBlock.of("_kneReadJIntArray(env, %L)?.toList()?.map { %T.entries[it] } ?: emptyList()", arrName, kotlinType(t.fqName))
        KneType.STRING -> CodeBlock.of("_kneReadJStrList(env, %L) ?: emptyList()", arrName)
        is KneType.OBJECT, is KneType.INTERFACE -> CodeBlock.of("_kneReadJLongArray(env, %L)?.toList() ?: emptyList()", arrName)
        else -> CodeBlock.of("emptyList()")
    }

    /** Native return type decl for callback invoke helpers (native Kotlin types); null for Unit. */
    private fun callbackNativeReturnDecl(t: KneType): TypeName? = when (t) {
        KneType.UNIT -> null
        KneType.INT, is KneType.ENUM -> PT.INT
        KneType.LONG -> PT.LONG
        KneType.DOUBLE -> PT.DOUBLE
        KneType.FLOAT -> PT.FLOAT
        KneType.BOOLEAN -> UBYTE
        KneType.BYTE -> PT.BYTE
        KneType.SHORT -> PT.SHORT
        KneType.STRING -> PT.STRING.copy(nullable = true)
        KneType.BYTE_ARRAY -> PT.BYTE_ARRAY.copy(nullable = true)
        is KneType.OBJECT, is KneType.INTERFACE -> PT.LONG
        is KneType.DATA_CLASS -> kotlinType(t.fqName)
        is KneType.LIST -> PT.LIST.parameterizedBy(callbackElemJvm(t.elementType))
        is KneType.SET -> PT.SET.parameterizedBy(callbackElemJvm(t.elementType))
        is KneType.MAP -> PT.MAP.parameterizedBy(nativePublic(t.keyType), nativePublic(t.valueType))
        else -> flavor.jobject.copy(nullable = true)
    }

    private fun callbackElemJvm(t: KneType): TypeName = when (t) {
        is KneType.OBJECT -> kotlinType(t.fqName)
        is KneType.INTERFACE -> kotlinType(t.fqName)
        is KneType.DATA_CLASS -> kotlinType(t.fqName)
        else -> nativePublic(t)
    }

    private fun callbackNativeErrorReturn(t: KneType): CodeBlock = when (t) {
        KneType.UNIT -> CodeBlock.of("Unit")
        KneType.INT, is KneType.ENUM -> CodeBlock.of("0")
        KneType.LONG -> CodeBlock.of("0L")
        KneType.DOUBLE -> CodeBlock.of("0.0")
        KneType.FLOAT -> CodeBlock.of("0.0f")
        KneType.BOOLEAN -> CodeBlock.of("0.toUByte()")
        KneType.BYTE -> CodeBlock.of("0.toByte()")
        KneType.SHORT -> CodeBlock.of("0.toShort()")
        KneType.STRING -> CodeBlock.of("null")
        KneType.BYTE_ARRAY -> CodeBlock.of("null")
        is KneType.OBJECT, is KneType.INTERFACE -> CodeBlock.of("0L")
        is KneType.DATA_CLASS -> CodeBlock.of("error(%S)", "callback dispatch failed")
        is KneType.LIST -> CodeBlock.of("emptyList()")
        is KneType.SET -> CodeBlock.of("emptySet()")
        is KneType.MAP -> CodeBlock.of("emptyMap()")
        else -> CodeBlock.of("null")
    }

    private fun callbackPayloadNativeType(t: KneType): TypeName = when (t) {
        KneType.INT, is KneType.ENUM -> PT.INT
        KneType.LONG -> PT.LONG
        KneType.DOUBLE -> PT.DOUBLE
        KneType.FLOAT -> PT.FLOAT
        KneType.BOOLEAN -> PT.BOOLEAN
        KneType.BYTE -> PT.BYTE
        KneType.SHORT -> PT.SHORT
        KneType.STRING -> PT.STRING
        KneType.BYTE_ARRAY -> PT.BYTE_ARRAY
        is KneType.OBJECT -> kotlinType(t.fqName)
        is KneType.INTERFACE -> kotlinType(t.fqName)
        is KneType.DATA_CLASS -> kotlinType(t.fqName)
        is KneType.LIST -> PT.LIST.parameterizedBy(nativePublic(t.elementType))
        is KneType.SET -> PT.SET.parameterizedBy(nativePublic(t.elementType))
        is KneType.MAP -> PT.MAP.parameterizedBy(nativePublic(t.keyType), nativePublic(t.valueType))
        else -> PT.ANY.copy(nullable = true)
    }

    private fun callbackJvalueSlot(t: KneType): String = when (t) {
        KneType.INT, is KneType.ENUM -> "i"
        KneType.LONG -> "j"
        KneType.DOUBLE -> "d"
        KneType.FLOAT -> "f"
        KneType.BOOLEAN -> "z"
        KneType.BYTE -> "b"
        KneType.SHORT -> "s"
        else -> "l"
    }

    /** Converts an inner Kotlin List into a real JNI array object (nested list callbacks). */
    private fun nestedListJobject(elemType: KneType, paramName: CodeBlock, envExpr: String = "env"): CodeBlock = when (elemType) {
        KneType.INT -> CodeBlock.of("_kneNewJIntArray(%L, %L.toIntArray())", envExpr, paramName)
        KneType.LONG -> CodeBlock.of("_kneNewJLongArray(%L, %L.toLongArray())", envExpr, paramName)
        KneType.DOUBLE -> CodeBlock.of("_kneNewJDoubleArray(%L, %L.toDoubleArray())", envExpr, paramName)
        KneType.FLOAT -> CodeBlock.of("_kneNewJFloatArray(%L, %L.toFloatArray())", envExpr, paramName)
        KneType.SHORT -> CodeBlock.of("_kneNewJShortArray(%L, %L.toShortArray())", envExpr, paramName)
        KneType.BYTE -> CodeBlock.of("_kneNewJBytes(%L, %L.toByteArray())", envExpr, paramName)
        KneType.BOOLEAN -> CodeBlock.of("_kneNewJBooleanArray(%L, %L.toBooleanArray())", envExpr, paramName)
        is KneType.ENUM -> CodeBlock.of("_kneNewJIntArray(%L, IntArray(%L.size) { %L[it].ordinal })", envExpr, paramName, paramName)
        KneType.STRING -> CodeBlock.of("_kneNewJStrArr(%L, %L)", envExpr, paramName)
        is KneType.OBJECT -> CodeBlock.of(
            "_kneNewJLongArray(%L, LongArray(%L.size) { %L })",
            envExpr, paramName, stableRefCreate(CodeBlock.of("%L[it] as %T", paramName, kotlinType(elemType.fqName))),
        )
        is KneType.INTERFACE -> CodeBlock.of(
            "_kneNewJLongArray(%L, LongArray(%L.size) { %L })",
            envExpr, paramName, stableRefCreate(CodeBlock.of("%L[it] as %T", paramName, kotlinType(elemType.fqName))),
        )
        KneType.BYTE_ARRAY -> CodeBlock.of("_kneNewJBytesArr(%L, %L)", envExpr, paramName)
        is KneType.DATA_CLASS -> CodeBlock.of("_kneNewJObjArr(%L, %L.map { _kneNewJvm%L(%L, it) })", envExpr, paramName, elemType.simpleName, envExpr)
        is KneType.LIST -> CodeBlock.of("_kneNewJObjArr(%L, %L.map { %L })", envExpr, paramName, nestedListJobject(elemType.elementType, CodeBlock.of("it"), envExpr))
        is KneType.SET -> CodeBlock.of("_kneNewJObjArr(%L, %L.map { %L })", envExpr, paramName, nestedListJobject(elemType.elementType, CodeBlock.of("it"), envExpr))
        else -> CodeBlock.of("null")
    }

    private fun callbackPayloadJvalueExpr(t: KneType, name: String): CodeBlock = when (t) {
        KneType.INT, is KneType.ENUM -> CodeBlock.of("%L", name)
        KneType.LONG -> CodeBlock.of("%L", name)
        KneType.DOUBLE -> CodeBlock.of("%L", name)
        KneType.FLOAT -> CodeBlock.of("%L", name)
        KneType.BOOLEAN -> CodeBlock.of("if (%L) 1u else 0u", name)
        KneType.BYTE -> CodeBlock.of("%L", name)
        KneType.SHORT -> CodeBlock.of("%L", name)
        KneType.STRING -> CodeBlock.of("_kneKToJ(env, %L)", name)
        KneType.BYTE_ARRAY -> CodeBlock.of("_kneNewJBytes(env, %L)", name)
        is KneType.OBJECT -> CodeBlock.of("_kneNewJvmProxy%L(env, %L)", t.simpleName, stableRefCreate(CodeBlock.of("%L", name)))
        is KneType.INTERFACE -> CodeBlock.of("_kneNewJvmProxy%L(env, %L)", t.simpleName, stableRefCreate(CodeBlock.of("%L", name)))
        is KneType.DATA_CLASS -> CodeBlock.of("_kneNewJvm%L(env, %L)", t.simpleName, name)
        is KneType.LIST -> elemArrayBuildExpr(CodeBlock.of("%L", name), t.elementType)
        is KneType.SET -> elemArrayBuildExpr(CodeBlock.of("%L.toList()", name), t.elementType)
        is KneType.MAP -> mapBuildExpr(CodeBlock.of("%L", name), t)
        else -> CodeBlock.of("null")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JVM-side value construction helpers (native → JVM)
    // ══════════════════════════════════════════════════════════════════════════

    private fun collectJvmMaterialisedDcs(module: KneModule): Set<KneType.DATA_CLASS> {
        val result = mutableSetOf<KneType.DATA_CLASS>()
        fun scan(type: KneType) {
            when (type) {
                is KneType.DATA_CLASS -> { result.add(type); type.fields.forEach { scan(it.type) } }
                is KneType.NULLABLE -> scan(type.inner)
                is KneType.LIST -> scan(type.elementType)
                is KneType.SET -> scan(type.elementType)
                is KneType.FLOW -> scan(type.elementType)
                is KneType.MAP -> { scan(type.keyType); scan(type.valueType) }
                is KneType.FUNCTION -> { type.paramTypes.forEach { scan(it) }; scan(type.returnType) }
                else -> {}
            }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
            cls.companionMethods.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
            cls.properties.forEach { scan(it.type) }
        }
        module.functions.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
        suspendReturnTypes(module).forEach { scan(it) }
        flowElemTypes(module).forEach { scan(it) }
        return result
    }

    /** DCs read back from JVM objects (DC-returning callbacks). */
    private fun collectJvmReadDcs(module: KneModule): Set<KneType.DATA_CLASS> {
        val result = mutableSetOf<KneType.DATA_CLASS>()
        fun scanCallback(type: KneType) {
            when (type) {
                is KneType.DATA_CLASS -> { result.add(type); type.fields.forEach { scanCallback(it.type) } }
                is KneType.LIST -> scanCallback(type.elementType)
                is KneType.SET -> scanCallback(type.elementType)
                else -> {}
            }
        }
        collectCallbackSignatures(module).forEach { sig -> scanCallback(sig.returnType) }
        // DC-list params arrive as jobjectArray of JVM DC objects and are read back element-wise
        fun scanParams(params: List<KneParam>) {
            params.forEach { scanCallback(it.type) }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { scanParams(it.params) }
            cls.companionMethods.forEach { scanParams(it.params) }
        }
        module.functions.forEach { scanParams(it.params) }
        return result
    }

    /** Enums referenced as DC fields (need JVM constant lookup by ordinal). */
    private fun collectReferencedEnumsForDcFields(module: KneModule): Set<Pair<String, String>> {
        val result = mutableSetOf<Pair<String, String>>() // fqName to simpleName
        fun scanType(type: KneType) {
            when (type) {
                is KneType.ENUM -> result.add(type.fqName to type.simpleName)
                is KneType.NULLABLE -> scanType(type.inner)
                is KneType.LIST -> scanType(type.elementType)
                is KneType.SET -> scanType(type.elementType)
                is KneType.DATA_CLASS -> type.fields.forEach { scanType(it.type) }
                else -> {}
            }
        }
        collectJvmMaterialisedDcs(module).forEach { dc -> dc.fields.forEach { scanType(it.type) } }
        return result
    }

    private fun collectReferencedProxyClasses(module: KneModule): List<KneClass> {
        val fqns = mutableSetOf<String>()
        fun scan(type: KneType) {
            when (type) {
                is KneType.OBJECT -> fqns.add(type.fqName)
                is KneType.INTERFACE -> fqns.add(type.fqName)
                is KneType.NULLABLE -> scan(type.inner)
                is KneType.LIST -> scan(type.elementType)
                is KneType.SET -> scan(type.elementType)
                is KneType.FLOW -> scan(type.elementType)
                is KneType.DATA_CLASS -> type.fields.forEach { scan(it.type) }
                is KneType.MAP -> { scan(type.keyType); scan(type.valueType) }
                else -> {}
            }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
            cls.companionMethods.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
        }
        module.functions.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
        collectCallbackSignatures(module).forEach { sig -> sig.paramTypes.forEach { scan(it) }; scan(sig.returnType) }
        return module.classes.filter { !it.isCommon && it.fqName in fqns }
    }

    /** Emits java.util.List constructors for enum/DC/object element kinds used in DC fields. */
    private fun buildJvmListElemHelpers(file: FileSpec.Builder, module: KneModule) {
        val nestedIds = mutableSetOf<String>()
        fun scanNested(type: KneType) {
            when (type) {
                is KneType.NULLABLE -> scanNested(type.inner)
                is KneType.LIST -> { if (type.elementType is KneType.LIST) nestedIds.add(jvmListElemId(type.elementType)); scanNested(type.elementType) }
                is KneType.SET -> scanNested(type.elementType)
                is KneType.DATA_CLASS -> type.fields.forEach { scanNested(it.type) }
                else -> {}
            }
        }
        collectJvmMaterialisedDcs(module).forEach { dc -> dc.fields.forEach { scanNested(it.type) } }
        for (nid in nestedIds) {
            file.addFunction(
                FunSpec.builder("_kneNewJvmListOf$nid")
                    .addModifiers(KModifier.INTERNAL)
                    .addKdoc("Constructs a java.util.List of nested lists (element id $nid).\n")
                    .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                    .addParameter("values", PT.LIST.parameterizedBy(PT.ANY.copy(nullable = true)))
                    .returns(flavor.jobject.copy(nullable = true))
                    .addCode(
                        CodeBlock.of(
                            "val out = _kneNewJvmArrayList(env, values.size) ?: return null\n" +
                                "for (v in values) _kneJvmListAdd(env, out, v as? jobject)\n" +
                                "return out\n",
                        ),
                    )
                    .build(),
            )
        }
        for (e in collectReferencedEnumsForDcFields(module)) {
            val (fq, simple) = e
            file.addFunction(
                FunSpec.builder("_kneNewJvmListOfE$simple")
                    .addModifiers(KModifier.INTERNAL)
                    .addKdoc("Constructs a java.util.List<$simple> from Kotlin values (element = values[ordinal]).\n")
                    .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                    .addParameter("values", PT.LIST.parameterizedBy(kotlinType(fq)))
                    .returns(flavor.jobject.copy(nullable = true))
                    .addCode(
                        CodeBlock.of(
                            "val out = _kneNewJvmArrayList(env, values.size) ?: return null\n" +
                                "for (v in values) _kneJvmListAdd(env, out, _kneNewJvmEnum%L(env, v.ordinal))\n" +
                                "return out\n",
                            simple,
                        ),
                    )
                    .build(),
            )
        }
        for (dc in collectJvmMaterialisedDcs(module)) {
            file.addFunction(
                FunSpec.builder("_kneNewJvmListOfD${dc.simpleName}")
                    .addModifiers(KModifier.INTERNAL)
                    .addKdoc("Constructs a java.util.List<${dc.simpleName}> from Kotlin values.\n")
                    .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                    .addParameter("values", PT.LIST.parameterizedBy(kotlinType(dc.fqName)))
                    .returns(flavor.jobject.copy(nullable = true))
                    .addCode(
                        CodeBlock.of(
                            "val out = _kneNewJvmArrayList(env, values.size) ?: return null\n" +
                                "for (v in values) _kneJvmListAdd(env, out, _kneNewJvm%L(env, v))\n" +
                                "return out\n",
                            dc.simpleName,
                        ),
                    )
                    .build(),
            )
        }
        file.addFunction(
            FunSpec.builder("_kneNewJvmListOfX")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Generic fallback: empty list (unsupported element kind).\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("values", PT.LIST.parameterizedBy(PT.ANY.copy(nullable = true)))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneNewJvmArrayList(env, 0)\n"))
                .build(),
        )
        for (cls in collectReferencedProxyClasses(module)) {
            file.addFunction(
                FunSpec.builder("_kneNewJvmListOfH${cls.simpleName}")
                    .addModifiers(KModifier.INTERNAL)
                    .addKdoc("Constructs a java.util.List<${cls.simpleName}> from Kotlin values (element = proxy handle).\n")
                    .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                    .addParameter("values", PT.LIST.parameterizedBy(kotlinType(cls.fqName)))
                    .returns(flavor.jobject.copy(nullable = true))
                    .addCode(
                        CodeBlock.of(
                            "val out = _kneNewJvmArrayList(env, values.size) ?: return null\n" +
                                "for (v in values) _kneJvmListAdd(env, out, _kneNewJvmProxy%L(env, %L))\n" +
                                "return out\n",
                            cls.simpleName, stableRefCreate(CodeBlock.of("v")),
                        ),
                    )
                    .build(),
            )
        }
    }

    /** Emits the NewObjectA-based constructor helper for one data class. */
    private fun buildJvmDcConstructor(file: FileSpec.Builder, dc: KneType.DATA_CLASS) {
        val n = dc.simpleName

        file.addType(
            TypeSpec.objectBuilder("_kneDcJvm$n")
                .addModifiers(KModifier.INTERNAL)
                .addAnnotation(THREAD_LOCAL)
                .addProperty(
                    PropertySpec.builder("ctor", flavor.jmethodId.copy(nullable = true))
                        .mutable(true)
                        .initializer("null")
                        .build(),
                )
                .build(),
        )

        val ctorSig = buildString {
            append("(")
            dc.fields.forEach { f -> append(dcFieldJvmSig(f.type)) }
            append(")V")
        }

        val body = CodeBlock.builder()
        body.add("val fns = _kneFns(env)\n")
        body.add("val clazz = _kneClass(%S) ?: return null\n", dc.fqName.replace('.', '/'))
        body.add("var mid = _kneDcJvm%L.ctor\n", n)
        body.add(
            "if (mid == null) { mid = %M { fns.GetMethodID!!.invoke(env, clazz, %S.%M.ptr, %S.%M.ptr) }; _kneDcJvm%L.ctor = mid }\n",
            M_MEM_SCOPED, "<init>", M_CSTR, ctorSig, M_CSTR, n,
        )
        body.add("%M {\n", M_MEM_SCOPED)
        body.indent()
        body.add("val args = allocArray<%T>(maxOf(1, %L))\n", flavor.jvalue, dc.fields.size)
        dc.fields.forEachIndexed { idx, f ->
            body.add("args[%L].%L = %L\n", idx, dcFieldSlot(f.type), dcFieldJvalueExpr(f.type, "value.${f.name}"))
        }
        body.add("return fns.NewObjectA!!.invoke(env, clazz, mid, args)\n")
        body.unindent()
        body.add("}\n")

        file.addFunction(
            FunSpec.builder("_kneNewJvm$n")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("value", kotlinType(dc.fqName))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(body.build())
                .build(),
        )
    }

    /** Short id for a java.util.List element kind (construct/read helpers). */
    private fun jvmListElemId(elemType: KneType): String = when (elemType) {
        KneType.INT -> "Int"
        KneType.LONG -> "Long"
        KneType.DOUBLE -> "Double"
        KneType.FLOAT -> "Float"
        KneType.BOOLEAN -> "Boolean"
        KneType.BYTE -> "Byte"
        KneType.SHORT -> "Short"
        KneType.STRING -> "String"
        is KneType.ENUM -> "E" + elemType.simpleName
        is KneType.OBJECT -> "H" + elemType.simpleName
        is KneType.INTERFACE -> "H" + elemType.simpleName
        is KneType.DATA_CLASS -> "D" + elemType.simpleName
        is KneType.LIST -> "L" + jvmListElemId(elemType.elementType)
        else -> "X"
    }

    /** jvalue slot for a DC constructor field (object fields use l, primitives use their width). */
    private fun dcFieldSlot(type: KneType): String = when (type) {
        KneType.INT -> "i"
        KneType.LONG -> "j"
        KneType.DOUBLE -> "d"
        KneType.FLOAT -> "f"
        KneType.BOOLEAN -> "z"
        KneType.BYTE -> "b"
        KneType.SHORT -> "s"
        else -> "l"
    }

    /** JNI signature for a DC constructor field. */
    private fun dcFieldJvmSig(type: KneType): String = when (type) {
        KneType.INT -> "I"
        KneType.LONG -> "J"
        KneType.DOUBLE -> "D"
        KneType.FLOAT -> "F"
        KneType.BOOLEAN -> "Z"
        KneType.BYTE -> "B"
        KneType.SHORT -> "S"
        KneType.STRING -> "Ljava/lang/String;"
        KneType.BYTE_ARRAY -> "[B"
        is KneType.ENUM -> "L${type.jvmClassPath()};"
        is KneType.OBJECT -> "L${type.jvmClassPath()};"
        is KneType.INTERFACE -> "L${type.jvmClassPath()};"
        is KneType.DATA_CLASS -> "L${type.jvmClassPath()};"
        is KneType.LIST -> "Ljava/util/List;"
        is KneType.SET -> "Ljava/util/Set;"
        is KneType.MAP -> "Ljava/util/Map;"
        else -> "Ljava/lang/Object;"
    }

    /** jvalue expression for a DC constructor field of [type]. */
    private fun dcFieldJvalueExpr(type: KneType, expr: String): CodeBlock = when (type) {
        KneType.INT -> CodeBlock.of("%L", expr)
        KneType.LONG -> CodeBlock.of("%L", expr)
        KneType.DOUBLE -> CodeBlock.of("%L", expr)
        KneType.FLOAT -> CodeBlock.of("%L", expr)
        KneType.BOOLEAN -> CodeBlock.of("if (%L) 1u else 0u", expr)
        KneType.BYTE -> CodeBlock.of("%L", expr)
        KneType.SHORT -> CodeBlock.of("%L", expr)
        KneType.STRING -> CodeBlock.of("_kneKToJ(env, %L)", expr)
        KneType.BYTE_ARRAY -> CodeBlock.of("_kneNewJBytes(env, %L)", expr)
        is KneType.ENUM -> CodeBlock.of("_kneNewJvmEnum%L(env, %L.ordinal)", type.simpleName, expr)
        is KneType.OBJECT -> CodeBlock.of("_kneNewJvmProxy%L(env, %L)", type.simpleName, stableRefCreate(CodeBlock.of("%L", expr)))
        is KneType.INTERFACE -> CodeBlock.of("_kneNewJvmProxy%L(env, %L)", type.simpleName, stableRefCreate(CodeBlock.of("%L", expr)))
        is KneType.DATA_CLASS -> CodeBlock.of("_kneNewJvm%L(env, %L)", type.simpleName, expr)
        is KneType.LIST -> CodeBlock.of("_kneNewJvmListOf%L(env, %L)", jvmListElemId(type.elementType), expr)
        is KneType.SET -> CodeBlock.of("_kneNewJvmListOf%L(env, %L.toList())", jvmListElemId(type.elementType), expr)
        is KneType.MAP -> mapBuildExpr(CodeBlock.of("%L", expr), type)
        else -> CodeBlock.of("null")
    }

    /** Emits enum constant lookup (values() array element by ordinal). */
    private fun buildJvmEnumConstant(file: FileSpec.Builder, ref: Pair<String, String>) {
        val (fq, simple) = ref
        file.addFunction(
            FunSpec.builder("_kneNewJvmEnum$simple")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("ordinal", PT.INT)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "val clazz = _kneClass(%S) ?: return null\n" +
                            "%M {\n" +
                            "    val mid = _kneStaticMethodId(env, clazz, %S, %S) ?: return null\n" +
                            "    val arr = fns.CallStaticObjectMethodA!!.invoke(env, clazz, mid, null) ?: return null\n" +
                            "    return fns.GetObjectArrayElement!!.invoke(env, arr, ordinal)\n" +
                            "}\n",
                        fq.replace('.', '/'), M_MEM_SCOPED,
                        "values", "()[L${fq.replace('.', '/')};",
                    ),
                )
                .build(),
        )
    }

    /** Emits proxy construction from a native handle (cached static `fromNativeHandle`). */
    private fun buildJvmProxyFromHandle(file: FileSpec.Builder, cls: KneClass) {
        val fq = cls.fqName
        file.addFunction(
            FunSpec.builder("_kneNewJvmProxy${cls.simpleName}")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("handle", PT.LONG)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "val clazz = _kneClass(%S) ?: return null\n" +
                            "%M {\n" +
                            "    val mid = _kneStaticMethodId(env, clazz, %S, %S) ?: return null\n" +
                            "    val args = allocArray<%T>(1)\n" +
                            "    args[0].j = handle\n" +
                            "    return fns.CallStaticObjectMethodA!!.invoke(env, clazz, mid, args)\n" +
                            "}\n",
                        fq.replace('.', '/'), M_MEM_SCOPED,
                        "fromNativeHandle", "(J)L${fq.replace('.', '/')};",
                        flavor.jvalue,
                    ),
                )
                .build(),
        )
    }

    /** Emits per-combination java.util.Map builders for all Map return/DC-field types. */
    private fun buildJvmMapHelpers(file: FileSpec.Builder, module: KneModule) {
        file.addType(
            TypeSpec.objectBuilder("_kneHashMapCache")
                .addModifiers(KModifier.INTERNAL)
                .addAnnotation(THREAD_LOCAL)
                .addProperty(
                    PropertySpec.builder("ctor", flavor.jmethodId.copy(nullable = true))
                        .mutable(true)
                        .initializer("null")
                        .build(),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneNewJvmHashMap")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "val clazz = _kneClass(%S) ?: return null\n" +
                            "var mid = _kneHashMapCache.ctor\n" +
                            "if (mid == null) { mid = %M { fns.GetMethodID!!.invoke(env, clazz, %S.%M.ptr, %S.%M.ptr) }; _kneHashMapCache.ctor = mid }\n" +
                            "return fns.NewObjectA!!.invoke(env, clazz, mid, null)\n",
                        "java/util/HashMap", M_MEM_SCOPED, "<init>", M_CSTR, "()V", M_CSTR,
                    ),
                )
                .build(),
        )
        file.addType(
            TypeSpec.objectBuilder("_kneBoxCache")
                .addModifiers(KModifier.INTERNAL)
                .addAnnotation(THREAD_LOCAL)
                .addProperty(
                    PropertySpec.builder(
                        "mids",
                        ClassName("kotlin.collections", "HashMap").parameterizedBy(PT.STRING, flavor.jmethodId.copy(nullable = true)),
                    )
                        .initializer("%T()", ClassName("kotlin.collections", "HashMap"))
                        .build(),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmBoxSlot")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Boxes a primitive via the boxed type's valueOf static method (payload slot picked by [slot]).\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxClass", PT.STRING)
                .addParameter("sig", PT.STRING)
                .addParameter("slot", CHAR)
                .addParameter("i", PT.INT)
                .addParameter("l", PT.LONG)
                .addParameter("d", PT.DOUBLE)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "val clazz = _kneClass(boxClass) ?: return null\n" +
                            "var mid = _kneBoxCache.mids[sig]\n" +
                            "if (mid == null) {\n" +
                            "    mid = %M { fns.GetStaticMethodID!!.invoke(env, clazz, %S.%M.ptr, sig.%M.ptr) }\n" +
                            "    _kneBoxCache.mids[sig] = mid\n" +
                            "}\n" +
                            "%M {\n" +
                            "    val args = allocArray<%T>(1)\n" +
                            "    when (slot) {\n" +
                            "        'i' -> args[0].i = i\n" +
                            "        'j' -> args[0].j = l\n" +
                            "        else -> args[0].d = d\n" +
                            "    }\n" +
                            "    return fns.CallStaticObjectMethodA!!.invoke(env, clazz, mid, args)\n" +
                            "}\n",
                        M_MEM_SCOPED, "valueOf", M_CSTR, M_CSTR,
                        M_MEM_SCOPED, flavor.jvalue,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmBoxI")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxClass", PT.STRING)
                .addParameter("sig", PT.STRING)
                .addParameter("v", PT.INT)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneJvmBoxSlot(env, boxClass, sig, 'i', v, 0L, 0.0)\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmBoxL")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxClass", PT.STRING)
                .addParameter("sig", PT.STRING)
                .addParameter("v", PT.LONG)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneJvmBoxSlot(env, boxClass, sig, 'j', 0, v, 0.0)\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmBoxD")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("v", PT.DOUBLE)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "return _kneJvmBoxSlot(env, %S, %S, 'd', 0, 0L, v)\n",
                        "java/lang/Double", "(D)Ljava/lang/Double;",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmBoxF")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("v", PT.FLOAT)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "return _kneJvmBoxSlot(env, %S, %S, 'd', 0, 0L, v.toDouble())\n",
                        "java/lang/Float", "(F)Ljava/lang/Float;",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmMapPut")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("map", flavor.jobject.copy(nullable = true))
                .addParameter("k", flavor.jobject.copy(nullable = true))
                .addParameter("v", flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "%M {\n" +
                            "    val mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, map), %S.%M.ptr, %S.%M.ptr)\n" +
                            "    val args = allocArray<%T>(2)\n" +
                            "    args[0].l = k\n" +
                            "    args[1].l = v\n" +
                            "    fns.CallObjectMethodA!!.invoke(env, map, mid, args)\n" +
                            "}\n",
                        M_MEM_SCOPED, "put", M_CSTR,
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", M_CSTR,
                        flavor.jvalue,
                    ),
                )
                .build(),
        )

        val mapTypes = collectMapTypes(module)
        for (m in mapTypes) {
            val kid = payloadId(m.keyType)
            val vid = payloadId(m.valueType)
            file.addFunction(
                FunSpec.builder("_kneNewJvmMap_${kid}_$vid")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                    .addParameter("source", PT.MAP.parameterizedBy(nativePublic(m.keyType), nativePublic(m.valueType)))
                    .returns(flavor.jobject.copy(nullable = true))
                    .addCode(
                        CodeBlock.of(
                            "val out = _kneNewJvmHashMap(env) ?: return null\n" +
                                "for ((k, v) in source) {\n" +
                                "    _kneJvmMapPut(env, out, %L, %L)\n" +
                                "}\n" +
                                "return out\n",
                            mapBoxExpr(m.keyType, "k"), mapBoxExpr(m.valueType, "v"),
                        ),
                    )
                    .build(),
            )
        }
    }

    private fun collectMapTypes(module: KneModule): Set<KneType.MAP> {
        val result = mutableSetOf<KneType.MAP>()
        fun scan(type: KneType) {
            when (type) {
                is KneType.MAP -> { result.add(type); scan(type.keyType); scan(type.valueType) }
                is KneType.NULLABLE -> scan(type.inner)
                is KneType.LIST -> scan(type.elementType)
                is KneType.SET -> scan(type.elementType)
                is KneType.FLOW -> scan(type.elementType)
                is KneType.DATA_CLASS -> type.fields.forEach { scan(it.type) }
                else -> {}
            }
        }
        module.classes.forEach { cls ->
            cls.methods.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
            cls.companionMethods.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
            cls.properties.forEach { scan(it.type) }
        }
        module.functions.forEach { fn -> fn.params.forEach { scan(it.type) }; scan(fn.returnType) }
        suspendReturnTypes(module).forEach { scan(it) }
        flowElemTypes(module).forEach { scan(it) }
        return result
    }

    /** Boxes one Map entry value into a jobject. */
    private fun mapBoxExpr(type: KneType, expr: String): CodeBlock = when (type) {
        KneType.INT -> CodeBlock.of("_kneJvmBoxI(env, %S, %S, %L)", "java/lang/Integer", "(I)Ljava/lang/Integer;", expr)
        KneType.LONG -> CodeBlock.of("_kneJvmBoxL(env, %S, %S, %L)", "java/lang/Long", "(J)Ljava/lang/Long;", expr)
        KneType.DOUBLE -> CodeBlock.of("_kneJvmBoxD(env, %L)", expr)
        KneType.FLOAT -> CodeBlock.of("_kneJvmBoxF(env, %L)", expr)
        KneType.SHORT -> CodeBlock.of("_kneJvmBoxI(env, %S, %S, %L.toInt())", "java/lang/Short", "(S)Ljava/lang/Short;", expr)
        KneType.BYTE -> CodeBlock.of("_kneJvmBoxI(env, %S, %S, %L.toInt())", "java/lang/Byte", "(B)Ljava/lang/Byte;", expr)
        KneType.BOOLEAN -> CodeBlock.of("_kneJvmBoxI(env, %S, %S, if (%L) 1 else 0)", "java/lang/Boolean", "(Z)Ljava/lang/Boolean;", expr)
        KneType.STRING -> CodeBlock.of("_kneKToJ(env, %L)", expr)
        is KneType.ENUM -> CodeBlock.of("_kneNewJvmEnum%L(env, %L.ordinal)", type.simpleName, expr)
        is KneType.OBJECT -> CodeBlock.of("_kneNewJvmProxy%L(env, %L)", type.simpleName, stableRefCreate(CodeBlock.of("%L", expr)))
        is KneType.INTERFACE -> CodeBlock.of("_kneNewJvmProxy%L(env, %L)", type.simpleName, stableRefCreate(CodeBlock.of("%L", expr)))
        is KneType.DATA_CLASS -> CodeBlock.of("_kneNewJvm%L(env, %L)", type.simpleName, expr)
        else -> CodeBlock.of("null")
    }

    // ── extra helpers referenced above ───────────────────────────────────────

    private fun jmethodIdCacheProp(name: String): PropertySpec =
        PropertySpec.builder(name, flavor.jmethodId.copy(nullable = true))
            .mutable(true)
            .initializer("null")
            .build()

    private fun buildExtraArrayHelpers(file: FileSpec.Builder) {
        file.addType(
            TypeSpec.objectBuilder("_kneListCache")
                .addModifiers(KModifier.INTERNAL)
                .addAnnotation(THREAD_LOCAL)
                .addProperty(jmethodIdCacheProp("arrayCtor"))
                .addProperty(jmethodIdCacheProp("addMid"))
                .addProperty(jmethodIdCacheProp("sizeMid"))
                .addProperty(jmethodIdCacheProp("getMid"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneNewJvmArrayList")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("capacity", PT.INT)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "val clazz = _kneClass(%S) ?: return null\n" +
                            "var mid = _kneListCache.arrayCtor\n" +
                            "if (mid == null) { mid = %M { fns.GetMethodID!!.invoke(env, clazz, %S.%M.ptr, %S.%M.ptr) }; _kneListCache.arrayCtor = mid }\n" +
                            "%M {\n" +
                            "    val args = allocArray<%T>(1)\n" +
                            "    args[0].i = capacity\n" +
                            "    return fns.NewObjectA!!.invoke(env, clazz, mid, args)\n" +
                            "}\n",
                        "java/util/ArrayList", M_MEM_SCOPED, "<init>", M_CSTR, "(I)V", M_CSTR,
                        M_MEM_SCOPED, flavor.jvalue,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmListAdd")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("list", flavor.jobject.copy(nullable = true))
                .addParameter("v", flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "%M {\n" +
                            "    var mid = _kneListCache.addMid\n" +
                            "    if (mid == null) { mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, list), %S.%M.ptr, %S.%M.ptr); _kneListCache.addMid = mid }\n" +
                            "    val args = allocArray<%T>(1)\n" +
                            "    args[0].l = v\n" +
                            "    fns.CallBooleanMethodA!!.invoke(env, list, mid, args)\n" +
                            "}\n",
                        M_MEM_SCOPED, "add", M_CSTR, "(Ljava/lang/Object;)Z", M_CSTR, flavor.jvalue,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmListSize")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("list", flavor.jobject.copy(nullable = true))
                .returns(PT.INT)
                .addCode(
                    CodeBlock.of(
                        "if (list == null) return 0\n" +
                            "val fns = _kneFns(env)\n" +
                            "%M {\n" +
                            "    var mid = _kneListCache.sizeMid\n" +
                            "    if (mid == null) { mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, list), %S.%M.ptr, %S.%M.ptr); _kneListCache.sizeMid = mid }\n" +
                            "    return fns.CallIntMethodA!!.invoke(env, list, mid, null)\n" +
                            "}\n",
                        M_MEM_SCOPED, "size", M_CSTR, "()I", M_CSTR,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmListGet")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("list", flavor.jobject.copy(nullable = true))
                .addParameter("i", PT.INT)
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "%M {\n" +
                            "    var mid = _kneListCache.getMid\n" +
                            "    if (mid == null) { mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, list), %S.%M.ptr, %S.%M.ptr); _kneListCache.getMid = mid }\n" +
                            "    val args = allocArray<%T>(1)\n" +
                            "    args[0].i = i\n" +
                            "    return fns.CallObjectMethodA!!.invoke(env, list, mid, args)\n" +
                            "}\n",
                        M_MEM_SCOPED, "get", M_CSTR, "(I)Ljava/lang/Object;", M_CSTR, flavor.jvalue,
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmUnboxInt")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxed", flavor.jobject.copy(nullable = true))
                .returns(PT.INT)
                .addCode(
                    CodeBlock.of(
                        "return if (boxed == null) 0 else _kneFns(env).CallIntMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(%S), %S, %S), null)\n",
                        "java/lang/Integer", "intValue", "()I",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmUnboxLong")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxed", flavor.jobject.copy(nullable = true))
                .returns(PT.LONG)
                .addCode(
                    CodeBlock.of(
                        "return if (boxed == null) 0L else _kneFns(env).CallLongMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(%S), %S, %S), null)\n",
                        "java/lang/Long", "longValue", "()J",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmUnboxDouble")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxed", flavor.jobject.copy(nullable = true))
                .returns(PT.DOUBLE)
                .addCode(
                    CodeBlock.of(
                        "return if (boxed == null) 0.0 else _kneFns(env).CallDoubleMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(%S), %S, %S), null)\n",
                        "java/lang/Double", "doubleValue", "()D",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmUnboxFloat")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxed", flavor.jobject.copy(nullable = true))
                .returns(PT.FLOAT)
                .addCode(
                    CodeBlock.of(
                        "return if (boxed == null) 0f else _kneFns(env).CallFloatMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(%S), %S, %S), null)\n",
                        "java/lang/Float", "floatValue", "()F",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneJvmUnboxBoolean")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("boxed", flavor.jobject.copy(nullable = true))
                .returns(PT.BOOLEAN)
                .addCode(
                    CodeBlock.of(
                        "return if (boxed == null) false else _kneFns(env).CallBooleanMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(%S), %S, %S), null) != 0.toUByte()\n",
                        "java/lang/Boolean", "booleanValue", "()Z",
                    ),
                )
                .build(),
        )

        val listOfBuilder = { kind: String, valuesType: TypeName, addArg: CodeBlock ->
            FunSpec.builder("_kneNewJvmListOf$kind")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Constructs a java.util.List<$kind> from Kotlin values.\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("values", PT.LIST.parameterizedBy(valuesType))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val out = _kneNewJvmArrayList(env, values.size) ?: return null\n" +
                            "for (v in values) _kneJvmListAdd(env, out, %L)\n" +
                            "return out\n",
                        addArg,
                    ),
                )
                .build()
        }
        file.addFunction(
            listOfBuilder(
                "Int", PT.INT,
                CodeBlock.of("_kneJvmBoxI(env, %S, %S, v)", "java/lang/Integer", "(I)Ljava/lang/Integer;"),
            ),
        )
        file.addFunction(
            listOfBuilder("String", PT.STRING, CodeBlock.of("_kneKToJ(env, v)")),
        )
        file.addFunction(
            listOfBuilder(
                "Boolean", PT.BOOLEAN,
                CodeBlock.of("_kneJvmBoxI(env, %S, %S, if (v) 1 else 0)", "java/lang/Boolean", "(Z)Ljava/lang/Boolean;"),
            ),
        )
        file.addFunction(
            listOfBuilder(
                "Long", PT.LONG,
                CodeBlock.of("_kneJvmBoxL(env, %S, %S, v)", "java/lang/Long", "(J)Ljava/lang/Long;"),
            ),
        )
        file.addFunction(listOfBuilder("Double", PT.DOUBLE, CodeBlock.of("_kneJvmBoxD(env, v)")))
        file.addFunction(listOfBuilder("Float", PT.FLOAT, CodeBlock.of("_kneJvmBoxF(env, v)")))
        file.addFunction(
            listOfBuilder(
                "Short", PT.SHORT,
                CodeBlock.of("_kneJvmBoxI(env, %S, %S, v.toInt())", "java/lang/Short", "(S)Ljava/lang/Short;"),
            ),
        )
        file.addFunction(
            listOfBuilder(
                "Byte", PT.BYTE,
                CodeBlock.of("_kneJvmBoxI(env, %S, %S, v.toInt())", "java/lang/Byte", "(B)Ljava/lang/Byte;"),
            ),
        )

        val readListBuilder = { kind: String, listType: TypeName, readExpr: CodeBlock ->
            FunSpec.builder("_kneReadJvmListOf$kind")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Reads a java.util.List<$kind> into a Kotlin list.\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("list", flavor.jobject.copy(nullable = true))
                .returns(listType)
                .addCode(
                    CodeBlock.of(
                        "val n = _kneJvmListSize(env, list)\n" +
                            "return List(n) { %L }\n",
                        readExpr,
                    ),
                )
                .build()
        }
        file.addFunction(
            readListBuilder(
                "Short", PT.LIST.parameterizedBy(PT.SHORT),
                CodeBlock.of("_kneJvmUnboxInt(env, _kneJvmListGet(env, list, it)).toShort()"),
            ),
        )
        file.addFunction(
            readListBuilder(
                "Byte", PT.LIST.parameterizedBy(PT.BYTE),
                CodeBlock.of("_kneJvmUnboxInt(env, _kneJvmListGet(env, list, it)).toByte()"),
            ),
        )
        file.addFunction(
            readListBuilder(
                "Float", PT.LIST.parameterizedBy(PT.FLOAT),
                CodeBlock.of("_kneJvmUnboxFloat(env, _kneJvmListGet(env, list, it))"),
            ),
        )
        file.addFunction(
            readListBuilder(
                "Long", PT.LIST.parameterizedBy(PT.LONG),
                CodeBlock.of("_kneJvmUnboxLong(env, _kneJvmListGet(env, list, it))"),
            ),
        )
        file.addFunction(
            readListBuilder(
                "Double", PT.LIST.parameterizedBy(PT.DOUBLE),
                CodeBlock.of("_kneJvmUnboxDouble(env, _kneJvmListGet(env, list, it))"),
            ),
        )
        file.addFunction(
            readListBuilder(
                "Int", PT.LIST.parameterizedBy(PT.INT),
                CodeBlock.of("_kneJvmUnboxInt(env, _kneJvmListGet(env, list, it))"),
            ),
        )
        file.addFunction(
            readListBuilder(
                "String", PT.LIST.parameterizedBy(PT.STRING),
                CodeBlock.of("_kneJToK(env, _kneJvmListGet(env, list, it)) ?: \"\""),
            ),
        )
        file.addFunction(
            readListBuilder(
                "Boolean", PT.LIST.parameterizedBy(PT.BOOLEAN),
                CodeBlock.of("_kneJvmUnboxBoolean(env, _kneJvmListGet(env, list, it))"),
            ),
        )

        file.addFunction(
            FunSpec.builder("_kneNewJStrArr")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("values", PT.LIST.parameterizedBy(PT.STRING))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneNewJObjArr(env, values.map { _kneKToJ(env, it) })\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneNewJBytesArr")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("values", PT.LIST.parameterizedBy(PT.BYTE_ARRAY))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(CodeBlock.of("return _kneNewJObjArr(env, values.map { _kneNewJBytes(env, it) })\n"))
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneNewJObjArr")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("elements", PT.LIST.parameterizedBy(flavor.jobject.copy(nullable = true)))
                .returns(flavor.jobject.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "val arr = _kneNewObjArr(env, elements.size, _kneClass(%S)) ?: return null\n" +
                            "elements.forEachIndexed { i, e -> _kneObjArrSet(env, arr, i, e) }\n" +
                            "return arr\n",
                        "java/lang/Object",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneReadJStrList")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .returns(PT.LIST.parameterizedBy(PT.STRING).copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "if (arr == null) return null\n" +
                            "val n = _kneObjArrLen(env, arr)\n" +
                            "return List(n) { _kneJToK(env, _kneObjArrGet(env, arr, it)) ?: \"\" }\n",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneReadJBytesList")
                .addModifiers(KModifier.INTERNAL)
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .returns(PT.LIST.parameterizedBy(PT.BYTE_ARRAY).copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "if (arr == null) return null\n" +
                            "val n = _kneObjArrLen(env, arr)\n" +
                            "return List(n) { _kneReadJBytes(env, _kneObjArrGet(env, arr, it)) ?: ByteArray(0) }\n",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneReadJNestedList")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Reads a jobjectArray of nested collection payloads into a Kotlin nested list.\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("arr", flavor.jobject.copy(nullable = true))
                .addParameter("elemPayloadId", PT.STRING)
                .returns(PT.LIST.parameterizedBy(PT.ANY.copy(nullable = true)))
                .addCode(
                    CodeBlock.of(
                        "if (arr == null) return emptyList()\n" +
                            "val n = _kneObjArrLen(env, arr)\n" +
                            "return List(n) { i -> _kneDecodeNestedElem(env, _kneObjArrGet(env, arr, i), elemPayloadId) }\n",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneDecodeNestedElem")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Decodes one nested-collection element by payload id (L/S/M prefixes + element kind).\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("elem", flavor.jobject.copy(nullable = true))
                .addParameter("id", PT.STRING)
                .returns(PT.ANY.copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "if (elem == null) return null\n" +
                            "return when {\n" +
                            "    id.startsWith(%S) -> {\n" +
                            "        val inner = id.substring(1)\n" +
                            "        _kneDecodeFlatArray(env, elem, inner) ?: emptyList<Any?>()\n" +
                            "    }\n" +
                            "    id.startsWith(%S) -> {\n" +
                            "        val inner = id.substring(1)\n" +
                            "        (_kneDecodeFlatArray(env, elem, inner) ?: emptyList<Any?>()).toSet()\n" +
                            "    }\n" +
                            "    id.startsWith(%S) -> emptyMap<Any, Any>() // nested maps in collection params are not supported\n" +
                            "    else -> _kneDecodeFlatArray(env, elem, id)\n" +
                            "}\n",
                        "L", "S", "M",
                    ),
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("_kneDecodeFlatArray")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Decodes a flat JNI array element (jarray) into a Kotlin List by payload id.\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("elem", flavor.jobject.copy(nullable = true))
                .addParameter("id", PT.STRING)
                .returns(PT.LIST.parameterizedBy(PT.ANY.copy(nullable = true)).copy(nullable = true))
                .addCode(
                    CodeBlock.of(
                        "return when (id) {\n" +
                            "    %S -> _kneReadJIntArray(env, elem)?.toList()\n" +
                            "    %S -> _kneReadJLongArray(env, elem)?.toList()\n" +
                            "    %S -> _kneReadJDoubleArray(env, elem)?.toList()\n" +
                            "    %S -> _kneReadJFloatArray(env, elem)?.toList()\n" +
                            "    %S -> _kneReadJShortArray(env, elem)?.toList()\n" +
                            "    %S -> _kneReadJBytes(env, elem)?.toList()\n" +
                            "    %S -> _kneReadJBooleanArray(env, elem)?.toList()\n" +
                            "    %S -> _kneReadJStrList(env, elem)\n" +
                            "    %S -> _kneReadJBytesList(env, elem)\n" +
                            "    else -> null\n" +
                            "}\n",
                        "Int", "Long", "Double", "Float", "Short", "Byte", "Boolean", "String", "Bytes",
                    ),
                )
                .build(),
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JVM-object → native DC readers (DC-returning callbacks)
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildJvmDcReader(file: FileSpec.Builder, dc: KneType.DATA_CLASS) {
        val n = dc.simpleName
        val fq = dc.fqName

        val ctorArgs = CodeBlock.builder().indent().add("\n").apply {
            dc.fields.forEachIndexed { i, f ->
                val comma = if (i < dc.fields.size - 1) "," else ""
                add("%L = %L%L\n", f.name, dcFieldReadExpr(f.type, f.name, "clazz"), comma)
            }
        }.unindent().build()

        file.addFunction(
            FunSpec.builder("_kneReadJvm$n")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Reads fields of a JVM $n object into a native instance (DC-returning callbacks).\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("obj", flavor.jobject.copy(nullable = true))
                .returns(kotlinType(fq))
                .addCode(
                    CodeBlock.builder()
                        .add("val fns = _kneFns(env)\n")
                        .add("val clazz = fns.GetObjectClass!!.invoke(env, obj)\n")
                        .add("return %T(%L)\n", kotlinType(fq), ctorArgs)
                        .build(),
                )
                .build(),
        )
    }

    /** Expression reading one DC field from a JVM object via cached field IDs. */
    private fun dcFieldReadExpr(type: KneType, fieldName: String, clazzVar: String): CodeBlock = when (type) {
        KneType.INT -> CodeBlock.of("fns.GetIntField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))", clazzVar, fieldName, "I")
        KneType.LONG -> CodeBlock.of("fns.GetLongField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))", clazzVar, fieldName, "J")
        KneType.DOUBLE -> CodeBlock.of("fns.GetDoubleField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))", clazzVar, fieldName, "D")
        KneType.FLOAT -> CodeBlock.of("fns.GetFloatField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))", clazzVar, fieldName, "F")
        KneType.BOOLEAN -> CodeBlock.of("fns.GetBooleanField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S)) != 0.toUByte()", clazzVar, fieldName, "Z")
        KneType.BYTE -> CodeBlock.of("fns.GetByteField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))", clazzVar, fieldName, "B")
        KneType.SHORT -> CodeBlock.of("fns.GetShortField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))", clazzVar, fieldName, "S")
        KneType.STRING -> CodeBlock.of(
            "_kneJToK(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))) ?: \"\"",
            clazzVar, fieldName, "Ljava/lang/String;",
        )
        KneType.BYTE_ARRAY -> CodeBlock.of(
            "_kneReadJBytes(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))) ?: ByteArray(0)",
            clazzVar, fieldName, "[B",
        )
        is KneType.ENUM -> CodeBlock.of(
            "%T.entries[fns.CallIntMethodA!!.invoke(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S)), _kneMethodId(env, _kneClass(%S), %S, %S), null)]",
            kotlinType(type.fqName), clazzVar, fieldName, "L${type.jvmClassPath()};",
            type.jvmClassPath(), "ordinal", "()I",
        )
        is KneType.OBJECT -> CodeBlock.of(
            "_kneReadJvmHandle(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))).%M<%T>()!!.asStableRef<%T>().get()",
            clazzVar, fieldName, "L${type.jvmClassPath()};",
            M_TO_C_POINTER, C_OPAQUE, kotlinType(type.fqName),
        )
        is KneType.INTERFACE -> CodeBlock.of(
            "_kneReadJvmHandle(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S))).%M<%T>()!!.asStableRef<%T>().get()",
            clazzVar, fieldName, "L${type.jvmClassPath()};",
            M_TO_C_POINTER, C_OPAQUE, kotlinType(type.fqName),
        )
        is KneType.DATA_CLASS -> CodeBlock.of(
            "_kneReadJvm%L(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S)))",
            type.simpleName, clazzVar, fieldName, "L${type.jvmClassPath()};",
        )
        is KneType.LIST -> CodeBlock.of(
            "_kneReadJvmListOf%L(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S)))",
            jvmListElemId(type.elementType), clazzVar, fieldName, "Ljava/util/List;",
        )
        is KneType.SET -> CodeBlock.of(
            "_kneReadJvmListOf%L(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, %L, %S, %S)))",
            jvmListElemId(type.elementType), clazzVar, fieldName, "Ljava/util/List;",
        )
        is KneType.MAP -> CodeBlock.of(
            "emptyMap<%T, %T>()",
            nativePublic(type.keyType), nativePublic(type.valueType),
        )
        else -> CodeBlock.of("null")
    }

    /** Emits common helpers used by DC readers and OBJECT-returning callbacks. */
    private fun buildReadHelpers(file: FileSpec.Builder) {
        file.addFunction(
            FunSpec.builder("_kneReadJvmHandle")
                .addModifiers(KModifier.INTERNAL)
                .addKdoc("Reads the native handle stored in a JVM proxy object's `handle: Long` field.\n")
                .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
                .addParameter("obj", flavor.jobject.copy(nullable = true))
                .returns(PT.LONG)
                .addCode(
                    CodeBlock.of(
                        "val fns = _kneFns(env)\n" +
                            "val clazz = fns.GetObjectClass!!.invoke(env, obj)\n" +
                            "val fid = _kneFieldId(env, clazz, %S, %S) ?: return 0L\n" +
                            "return fns.GetLongField!!.invoke(env, obj, fid)\n",
                        "handle", "J",
                    ),
                )
                .build(),
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bootstrap
    // ══════════════════════════════════════════════════════════════════════════

    /** Native implementations for KneNativeBindings externals (dispose/cancel/credit). */
    private fun buildCommonNatives(file: FileSpec.Builder, module: KneModule) {
        val hostClass = jvmClassPath("KneNativeBindings")
        val hasSuspend = module.classes.any { cls -> cls.methods.any { it.isSuspend } || cls.companionMethods.any { it.isSuspend } } ||
            module.functions.any { it.isSuspend }
        val hasFlow = module.classes.any { cls -> cls.methods.any { it.returnType is KneType.FLOW } } ||
            module.functions.any { it.returnType is KneType.FLOW }

        file.addFunction(
            FunSpec.builder("kne_disposeRef")
                .addModifiers(KModifier.INTERNAL)
                .addParameters(jniPrefixParams())
                .addParameter("handle", PT.LONG)
                .addCode(
                    CodeBlock.of(
                        "if (handle == 0L) return\n" +
                            "try { handle.%M<%T>()?.%M<Any>()?.dispose() } catch (_: Throwable) {}\n",
                        M_TO_C_POINTER, C_OPAQUE, M_AS_STABLE_REF,
                    ),
                )
                .build(),
        )
        registerMethod(hostClass, "_kneDisposeRef", "(J)V", "kne_disposeRef")

        if (hasSuspend || hasFlow) {
            val cancelBody = CodeBlock.builder()
            cancelBody.add("if (jobHandle == 0L) return\n")
            if (hasFlow) {
                cancelBody.add(
                    "when (val target = jobHandle.%M<%T>()!!.asStableRef<Any>().get()) {\n" +
                        "    is Job -> target.cancel()\n" +
                        "    is KneFlowJob -> target.job.cancel()\n" +
                        "}\n",
                    M_TO_C_POINTER, C_OPAQUE,
                )
            } else {
                cancelBody.add(
                    "jobHandle.%M<%T>()!!.asStableRef<%T>().get().cancel()\n",
                    M_TO_C_POINTER, C_OPAQUE, JOB,
                )
            }
            file.addFunction(
                FunSpec.builder("kne_cancelJob")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameters(jniPrefixParams())
                    .addParameter("jobHandle", PT.LONG)
                    .addCode(cancelBody.build())
                    .build(),
            )
            registerMethod(hostClass, "_kneCancelJob", "(J)V", "kne_cancelJob")
        }

        if (hasFlow) {
            file.addFunction(
                FunSpec.builder("kne_flowCredit")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameters(jniPrefixParams())
                    .addParameter("jobHandle", PT.LONG)
                    .addCode(
                        CodeBlock.of(
                            "if (jobHandle == 0L) return\n" +
                                "jobHandle.%M<%T>()!!.asStableRef<KneFlowJob>().get().credits.trySend(Unit)\n",
                            M_TO_C_POINTER, C_OPAQUE,
                        ),
                    )
                    .build(),
            )
            registerMethod(hostClass, "_kneFlowCredit", "(J)V", "kne_flowCredit")
        }
    }

    private fun buildBootstrap(): FunSpec {
        val bootstrapSymbol = mangleBootstrapSymbol(jvmPackage, "KneRuntime", "initNatives")

        val body = CodeBlock.builder()
        body.add("try {\n")
        body.indent()
        body.add("println(%S)\n", "kne: bootstrap starting")
        body.add("val fns = _kneFns(env)\n")
        body.add("%M {\n", M_MEM_SCOPED)
        body.indent()
        body.add("val vmStorage = alloc<%T<%T>>()\n", C_POINTER_VAR, flavor.javaVmVar)
        body.add("val rc = fns.GetJavaVM!!. %M(env, vmStorage.ptr)\n", M_INVOKE)
        body.add("check(rc == 0) { %S + rc }\n", "GetJavaVM failed: ")
        body.add("_kneVM = vmStorage.value\n")
        // global-ref caches: exception class, boxing classes, registered classes
        body.add(
            "_kneExceptionClass = _kneGlobal(env, fns.FindClass!!.invoke(env, %S.%M.ptr))\n",
            jvmClassPath("KotlinNativeException"), M_CSTR,
        )
        body.add(
            "_kneClassCache[%S] = _kneGlobal(env, %M { fns.FindClass!!.invoke(env, %S.%M.ptr) })\n",
            "java/lang/Object", M_MEM_SCOPED, "java/lang/Object", M_CSTR,
        )
        body.add(
            "_kneClassCache[%S] = _kneGlobal(env, %M { fns.FindClass!!.invoke(env, %S.%M.ptr) })\n",
            "java/util/HashMap", M_MEM_SCOPED, "java/util/HashMap", M_CSTR,
        )
        body.add(
            "for (fqn in listOf(%S, %S, %S, %S, %S, %S, %S)) {\n",
            "java/lang/Integer", "java/lang/Long", "java/lang/Double", "java/lang/Float", "java/lang/Short", "java/lang/Byte", "java/lang/Boolean",
        )
        body.indent()
        body.add("_kneClassCache[fqn] = _kneGlobal(env, %M { fns.FindClass!!.invoke(env, fqn.%M.ptr) })\n", M_MEM_SCOPED, M_CSTR)
        body.unindent()
        body.add("}\n")
        // Collections are materialised for every List/Set/Map payload and DC field, so their
        // classes must be in the cache before the first call (helpers look them up here).
        body.add(
            "for (fqn in listOf(%S, %S, %S, %S)) {\n",
            "java/util/ArrayList", "java/util/List", "java/util/Set", "java/util/Map",
        )
        body.indent()
        body.add("_kneClassCache[fqn] = _kneGlobal(env, %M { fns.FindClass!!.invoke(env, fqn.%M.ptr) })\n", M_MEM_SCOPED, M_CSTR)
        body.unindent()
        body.add("}\n")
        for (reg in registrations.values) {
            body.add("run {\n")
            body.indent()
            body.add("val clazz = fns.FindClass!!.invoke(env, %S.%M.ptr)\n", reg.className, M_CSTR)
            body.add("check(clazz != null) { %S }\n", "FindClass failed: ${reg.className}")
            body.add("_kneClassCache[%S] = _kneGlobal(env, clazz)\n", reg.className)
            body.add("val methods = allocArray<%T>(%L)\n", flavor.jniNativeMethod, reg.methods.size)
            reg.methods.forEachIndexed { i, m ->
                body.add("methods[%L].name = %S.%M.ptr\n", i, m.jvmName, M_CSTR)
                body.add("methods[%L].signature = %S.%M.ptr\n", i, m.sig, M_CSTR)
                body.add("methods[%L].fnPtr = %M(::%L)\n", i, M_STATIC_C_FUNCTION, m.fnName)
            }
            body.add("val regRc = fns.RegisterNatives!!.invoke(env, clazz, methods, %L)\n", reg.methods.size)
            body.add("check(regRc == 0) { %S + regRc }\n", "RegisterNatives failed for ${reg.className}: ")
            body.unindent()
            body.add("}\n")
        }
        // Classes with no natives to bind: the native side only needs a global ref for
        // NewObjectA / GetMethodID later on (data classes, enums, sealed bases).
        for (className in cachedClasses - registrations.keys) {
            body.add("run {\n")
            body.indent()
            body.add("val clazz = fns.FindClass!!.invoke(env, %S.%M.ptr)\n", className, M_CSTR)
            body.add("check(clazz != null) { %S }\n", "FindClass failed: $className")
            body.add("_kneClassCache[%S] = _kneGlobal(env, clazz)\n", className)
            body.unindent()
            body.add("}\n")
        }
        body.unindent()
        body.add("}\n") // memScoped
        body.add("println(%S)\n", "kne: bootstrap done")
        body.unindent()
        body.add("} catch (e: Throwable) {\n")
        body.indent()
        body.add("// Rethrow: swallowing this leaves every native unregistered and the JVM side\n")
        body.add("// fails later with an opaque NoSuchMethodError far from the real cause.\n")
        body.add("println(%S + e::class.simpleName + %S + e.message)\n", "kne: BOOTSTRAP FAILED: ", ": ")
        body.add("throw e\n")
        body.unindent()
        body.add("}\n")

        return FunSpec.builder("kne_initNatives")
            .addKdoc("Resolved by the JVM via dlsym when KneRuntime.initNatives() runs. Registers all natives.\n")
            .addAnnotation(AnnotationSpec.builder(C_NAME).addMember("%S", bootstrapSymbol).build())
            .addParameters(jniPrefixParams())
            .addCode(body.build())
            .build()
    }

    private fun buildCompletionMid(): FunSpec =
        FunSpec.builder("_kneCompletionMid")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("env", KneCinteropTypes.pointerTo(flavor.envVar))
            .addParameter("obj", flavor.jobject.copy(nullable = true))
            .addParameter("name", PT.STRING)
            .addParameter("sig", PT.STRING)
            .returns(flavor.jmethodId.copy(nullable = true))
            .addCode(
                CodeBlock.of(
                    "val _kneClazz = _kneFns(env).GetObjectClass!!.invoke(env, obj)\n" +
                        "val mid = %M { _kneFns(env).GetMethodID!!.invoke(env, _kneClazz, name.%M.ptr, sig.%M.ptr) }\n" +
                        "_kneFns(env).DeleteLocalRef!!.invoke(env, _kneClazz)\n" +
                        "return mid\n",
                    M_MEM_SCOPED, M_CSTR, M_CSTR,
                ),
            )
            .build()

    private fun mangleBootstrapSymbol(pkg: String, className: String, method: String): String {
        fun esc(s: String) = s.replace("_", "_1")
        val pkgPart = pkg.split('.').filter { it.isNotEmpty() }.joinToString("_") { esc(it) }
        return "Java_${pkgPart}_${esc(className)}_${esc(method)}"
    }
}

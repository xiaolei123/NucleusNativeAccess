package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneProperty
import dev.nucleusframework.nna.plugin.ir.KneType

/**
 * Generates Kotlin/Native JNI bridge code consumed through the `dev.nucleusframework.knejni`
 * cinterop (jni.h compiled with `language = C++`, so `env.pointed.functions.pointed.Xxx`
 * mirrors the familiar Android JNI access pattern).
 *
 * Unlike the FFM backend there is exactly ONE exported symbol: the JNI bootstrap
 * `Java_<mangled>_KneRuntime_initNatives` (`@CName`). Every other native function is
 * registered on the JVM side via `RegisterNatives` during bootstrap, bound to the
 * generated JVM `external fun` declarations by name + signature.
 *
 * Marshalling summary:
 *  - object handles stay opaque `Long` (StableRef), like FFM
 *  - strings cross as jstring (modified UTF-8 codec included below)
 *  - collections cross as jarray types (Get/SetXxxArrayRegion); Maps as parallel key/value arrays
 *  - data class returns / suspend / flow payloads are materialised on the JVM via NewObjectA
 *  - data class params are flattened into individual field params (like FFM)
 *  - exceptions: native Kotlin errors are rethrown via ThrowNew(KotlinNativeException);
 *    JVM exceptions thrown inside a callback stay pending and propagate when the native
 *    method returns (original type preserved) — a private sentinel exception unwinds the
 *    native frames without touching JNIEnv while the exception is pending
 */
class JniNativeBridgeGenerator {

    companion object {
        /** Elements a native flow may emit ahead of the JVM collector (credit-based backpressure). */
        const val FLOW_INITIAL_CREDITS = 64
    }

    // Registration tables filled while emitting, rendered by appendBootstrap at the end.
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

    // ── JNI type mapping (native side) ───────────────────────────────────────

    /** Kotlin/Native param type for a JNI payload. */
    private fun nativeParamType(type: KneType): String = when (type) {
        KneType.INT -> "Int"
        KneType.LONG -> "Long"
        KneType.DOUBLE -> "Double"
        KneType.FLOAT -> "Float"
        KneType.BOOLEAN -> "UByte"
        KneType.BYTE -> "Byte"
        KneType.SHORT -> "Short"
        KneType.STRING -> "jobject?"
        KneType.UNIT -> ""
        is KneType.OBJECT, is KneType.INTERFACE -> "Long"
        is KneType.ENUM -> "Int"
        KneType.BYTE_ARRAY -> "jobject?"
        is KneType.FUNCTION -> "jobject?"
        is KneType.FLOW -> ""
        is KneType.MAP -> "jobject?" // unused; expanded to keys/values
        is KneType.LIST, is KneType.SET -> "jobject?"
        is KneType.DATA_CLASS -> error("data class params must be flattened")
        is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> "jobject?"
            is KneType.LIST, is KneType.SET, is KneType.MAP -> "jobject?"
            is KneType.FUNCTION -> "jobject?"
            is KneType.OBJECT, is KneType.INTERFACE -> "Long"
            KneType.BOOLEAN, is KneType.ENUM -> "Int"
            KneType.SHORT, KneType.BYTE -> "Int"
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "Long"
            else -> error("unsupported nullable native param: $type")
        }
    }

    /** Expanded native param list (DC → flattened fields, Map → keys/values, ByteArray → single array). */
    private fun expandNativeParams(name: String, type: KneType): List<Pair<String, String>> = when {
        JniTypes.extractDataClass(type) != null -> {
            val dc = JniTypes.extractDataClass(type)!!
            val fields = JniTypes.flattenDataClassFields(dc, name).flatMap { (n, t) ->
                when {
                    t is KneType.MAP -> listOf(
                        "${n}_keys" to "jobject?",
                        "${n}_values" to "jobject?",
                    )
                    else -> listOf(n to nativeParamType(t))
                }
            }
            if (type is KneType.NULLABLE) listOf("${name}_isNull" to "Int") + fields else fields
        }
        type is KneType.MAP || (type is KneType.NULLABLE && type.inner is KneType.MAP) -> {
            val inner = (if (type is KneType.NULLABLE) type.inner else type) as KneType.MAP
            listOf(
                "${name}_keys" to "jobject?",
                "${name}_values" to "jobject?",
            )
        }
        else -> listOf(name to nativeParamType(type))
    }

    private fun expandNativeParams(params: List<KneParam>): List<Pair<String, String>> =
        params.flatMap { expandNativeParams(it.name, it.type) }

    // ── main entry ───────────────────────────────────────────────────────────

    /**
     * @param jvmPackage package the JVM proxies are generated into; every registered class name and
     *   JNI descriptor is derived from it (see [jvmClassPath]). Falls back to the module's own
     *   package when omitted.
     */
    fun generate(module: KneModule, jvmPackage: String = ""): String = buildString {
        this@JniNativeBridgeGenerator.jvmPackage = jvmPackage.ifEmpty { module.packages.firstOrNull() ?: "" }
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("@file:Suppress(\"all\")")
        appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)")
        appendLine()
        appendLine("import kotlinx.cinterop.*")
        appendLine("import dev.nucleusframework.knejni.*")

        val hasSuspend = module.classes.any { cls -> cls.methods.any { it.isSuspend } || cls.companionMethods.any { it.isSuspend } } ||
            module.functions.any { it.isSuspend }
        val hasFlow = module.classes.any { cls -> cls.methods.any { it.returnType is KneType.FLOW } } ||
            module.functions.any { it.returnType is KneType.FLOW }
        if (hasSuspend || hasFlow) {
            appendLine("import kotlinx.coroutines.*")
        }
        if (hasFlow) {
            appendLine("import kotlinx.coroutines.flow.*")
        }
        module.packages.forEach { pkg ->
            appendLine("import $pkg.*")
        }
        appendLine()

        appendJniRuntimePreamble()
        appendThreadLocalCaches()
        if (hasFlow) {
            appendLine("/** Flow job handle: cancellation plus credit-based backpressure driven by the JVM collector. */")
            appendLine("private class KneFlowJob(val job: Job, val credits: kotlinx.coroutines.channels.Channel<Unit>)")
            appendLine()
        }
        appendCallbackSentinel()

        // ── per-class native functions ────────────────────────────────────────
        module.classes.filter { !it.isCommon }.forEach { cls -> appendClass(cls) }

        // ── enums ─────────────────────────────────────────────────────────────
        module.enums.forEach { enum -> appendEnum(enum) }

        // ── top-level functions ───────────────────────────────────────────────
        module.functions.filter { !it.isExtension }.forEach { fn -> appendTopLevelFunction(fn, module) }

        // ── extension functions ───────────────────────────────────────────────
        module.functions.filter { it.isExtension && it.receiverType != null }.forEach { fn ->
            appendExtensionFunction(fn, module)
        }

        // ── sealed ordinal dispatch ───────────────────────────────────────────
        module.classes.filter { it.isSealed && !it.isCommon }.forEach { cls ->
            appendSealedOrdinalBridge(cls)
        }

        // ── List<DC> param element types (already read element-wise; registers no natives) ──
        collectDcListParamTypes(module).forEach { dc -> cacheClass(jvmClassPath(dc.simpleName)) }

        // ── JVM-side value construction / reading helpers ─────────────────────
        appendExtraArrayHelpers()
        appendReadHelpers()
        appendJvmMapHelpers(module)
        collectJvmMaterialisedDcs(module).forEach { dc -> appendJvmDcConstructor(dc, module) }
        appendJvmListElemHelpers(module)
        collectReferencedEnumsForDcFields(module).forEach { e -> appendJvmEnumConstant(e) }
        collectReferencedProxyClasses(module).forEach { cls -> appendJvmProxyFromHandle(cls) }
        collectJvmReadDcs(module).forEach { dc -> appendJvmDcReader(dc) }

        // Classes materialised from native via NewObjectA / GetMethodID need a global ref too.
        collectJvmMaterialisedDcs(module).forEach { dc -> cacheClass(jvmClassPath(dc.simpleName)) }
        collectReferencedEnumsForDcFields(module).forEach { e -> cacheClass(jvmClassPath(e.second)) }
        collectReferencedProxyClasses(module).forEach { cls -> cacheClass(jvmClassPath(cls.simpleName)) }

        // ── suspend / flow payload delivery (concrete, per type) ───────────────
        if (hasSuspend || hasFlow) {
            appendLine("// ── suspend/flow completion dispatch ─────────────────────────────")
            appendLine()
            // Deliberately NOT cached: local/global ref addresses are recycled by the JVM once a
            // dispatcher becomes unreachable, so any cache keyed on the callback object (or on
            // GetObjectClass of it) can return a jmethodID belonging to a *different* dispatcher
            // class with the same "(I)V" shape. That silently routed Ints through the enum
            // dispatcher and vice versa. GetMethodID is a cheap class-table lookup in HotSpot.
            appendLine("private fun _kneCompletionMid(env: CPointer<JNIEnvVar>?, obj: jobject?, name: String, sig: String): jmethodID? {")
            appendLine("    val _kneClazz = _kneFns(env).GetObjectClass!!.invoke(env, obj)")
            appendLine("    val mid = memScoped { _kneFns(env).GetMethodID!!.invoke(env, _kneClazz, name.cstr.ptr, sig.cstr.ptr) }")
            appendLine("    _kneFns(env).DeleteLocalRef!!.invoke(env, _kneClazz)")
            appendLine("    return mid")
            appendLine("}")
            appendLine()
        }
        suspendReturnTypes(module).forEach { t -> appendSuspendOkHelper(t) }
        flowElemTypes(module).forEach { t -> appendFlowNextHelper(t) }
        if (hasSuspend || hasFlow) {
            appendSuspendErrorCancelledHelpers()
        }

        // ── callback invocation helpers (native → JVM dispatcher) ──────────────
        collectCallbackSignatures(module).forEach { sig -> appendCallbackInvokeHelper(sig) }

        // ── function-handle invocation bridges (JVM calls a native-returned lambda) ──
        collectFunctionReturnTypes(module).forEach { t -> appendFnInvokeBridge(t) }

        // ── common runtime natives (KneNativeBindings externals) ────────────────
        appendCommonNatives(module)

        // ── bootstrap (all functions exist by now) ─────────────────────────────
        appendBootstrap(module)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Static JNI runtime preamble
    // ══════════════════════════════════════════════════════════════════════════

    private fun StringBuilder.appendJniRuntimePreamble() {
        appendLine("// ── JNI environment ───────────────────────────────────────────────────")
        appendLine("private var _kneVM: CPointer<JavaVMVar>? = null")
        appendLine()
        appendLine("/** Cached global refs resolved during bootstrap (read-only afterwards). */")
        appendLine("private val _kneClassCache = HashMap<String, jobject?>()")
        appendLine()
        appendLine("private fun _kneFns(env: CPointer<JNIEnvVar>?): JNINativeInterface_ =")
        appendLine("    env!!.pointed!!.value!!.pointed!!")
        appendLine()
        appendLine("/** Runs [block] with a JNIEnv attached to the current native thread (attach + detach). */")
        appendLine("private inline fun <T> _kneAttached(block: (CPointer<JNIEnvVar>?) -> T): T {")
        appendLine("    val vm = _kneVM ?: error(\"KNE JNI runtime not initialized\")")
        appendLine("    memScoped {")
        appendLine("        val envStorage = alloc<CPointerVar<JNIEnvVar>>()")
        appendLine("        val rc = vm.pointed!!.value!!.pointed!!.AttachCurrentThread!!.invoke(vm, envStorage.ptr.reinterpret(), null)")
        appendLine("        check(rc == 0) { \"AttachCurrentThread failed: \$rc\" }")
        appendLine("        val env = envStorage.value")
        appendLine("        try {")
        appendLine("            return block(env)")
        appendLine("        } finally {")
        appendLine("            vm.pointed!!.value!!.pointed!!.DetachCurrentThread!!.invoke(vm)")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("// ── modified UTF-8 codec ─────────────────────────────────────────────")
        appendLine("/** Encodes a Kotlin String as JNI modified UTF-8 (NUL → 0xC0 0x80, surrogates as CESU-8). */")
        appendLine("private fun _kneToModifiedUtf8(value: String): ByteArray {")
        appendLine("    val out = ByteArray(value.length * 3 + 1)")
        appendLine("    var o = 0")
        appendLine("    for (c in value) {")
        appendLine("        val cp = c.code")
        appendLine("        when {")
        appendLine("            cp == 0 -> { out[o] = 0xC0.toByte(); out[o + 1] = 0x80.toByte(); o += 2 }")
        appendLine("            cp < 0x80 -> { out[o] = cp.toByte(); o++ }")
        appendLine("            cp < 0x800 -> {")
        appendLine("                out[o] = (0xC0 or (cp shr 6)).toByte()")
        appendLine("                out[o + 1] = (0x80 or (cp and 0x3F)).toByte()")
        appendLine("                o += 2")
        appendLine("            }")
        appendLine("            else -> {")
        appendLine("                out[o] = (0xE0 or ((cp shr 12) and 0x0F)).toByte()")
        appendLine("                out[o + 1] = (0x80 or ((cp shr 6) and 0x3F)).toByte()")
        appendLine("                out[o + 2] = (0x80 or (cp and 0x3F)).toByte()")
        appendLine("                o += 3")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine("    return out.copyOf(o)")
        appendLine("}")
        appendLine()
        appendLine("/** Decodes JNI modified UTF-8 bytes (0xC0 0x80 → NUL, CESU-8 surrogate pairs combined). */")
        appendLine("private fun _kneFromModifiedUtf8(bytes: ByteArray): String {")
        appendLine("    val chars = CharArray(bytes.size)")
        appendLine("    var o = 0")
        appendLine("    var i = 0")
        appendLine("    while (i < bytes.size) {")
        appendLine("        val b0 = bytes[i].toInt() and 0xFF")
        appendLine("        when {")
        appendLine("            b0 < 0x80 -> { chars[o] = b0.toChar(); o++; i++ }")
        appendLine("            b0 == 0xC0 && i + 1 < bytes.size && (bytes[i + 1].toInt() and 0xFF) == 0x80 -> {")
        appendLine("                chars[o] = '\\u0000'; o++; i += 2")
        appendLine("            }")
        appendLine("            b0 and 0xE0 == 0xC0 && i + 1 < bytes.size -> {")
        appendLine("                chars[o] = (((b0 and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar()")
        appendLine("                o++; i += 2")
        appendLine("            }")
        appendLine("            else -> {")
        appendLine("                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0x3F else 0")
        appendLine("                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0x3F else 0")
        appendLine("                chars[o] = (((b0 and 0x0F) shl 12) or (b1 shl 6) or b2).toChar()")
        appendLine("                o++; i += 3")
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
        appendLine("    return chars.concatToString(0, o)")
        appendLine("}")
        appendLine()
        appendLine("// ── strings ──────────────────────────────────────────────────────────")
        appendLine("/** Reads a jstring into a Kotlin String (null for JNI null). */")
        appendLine("private fun _kneJToK(env: CPointer<JNIEnvVar>?, str: jobject?): String? {")
        appendLine("    if (str == null) return null")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val chars = fns.GetStringUTFChars!!.invoke(env, str, null) ?: return null")
        appendLine("    try {")
        appendLine("        var len = 0")
        appendLine("        while (chars[len] != 0.toByte()) len++")
        appendLine("        return _kneFromModifiedUtf8(ByteArray(len) { chars[it] })")
        appendLine("    } finally {")
        appendLine("        fns.ReleaseStringUTFChars!!.invoke(env, str, chars)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/** Creates a jstring from a Kotlin String. */")
        appendLine("private fun _kneKToJ(env: CPointer<JNIEnvVar>?, value: String): jobject? {")
        appendLine("    val bytes = _kneToModifiedUtf8(value)")
        appendLine("    memScoped {")
        appendLine("        val buf = allocArray<ByteVar>(bytes.size + 1)")
        appendLine("        bytes.forEachIndexed { i, b -> buf[i] = b }")
        appendLine("        buf[bytes.size] = 0")
        appendLine("        return _kneFns(env).NewStringUTF!!.invoke(env, buf)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("// ── exceptions ───────────────────────────────────────────────────────")
        appendLine("private var _kneExceptionClass: jobject? = null")
        appendLine()
        appendLine("/** Throws KotlinNativeException(message) on the JVM. */")
        appendLine("private fun _kneThrow(env: CPointer<JNIEnvVar>?, message: String) {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = _kneExceptionClass")
        appendLine("    memScoped {")
        appendLine("        val msg = _kneToModifiedUtf8(message)")
        appendLine("        val buf = allocArray<ByteVar>(msg.size + 1)")
        appendLine("        msg.forEachIndexed { i, b -> buf[i] = b }")
        appendLine("        buf[msg.size] = 0")
        appendLine("        if (clazz != null) {")
        appendLine("            fns.ThrowNew!!.invoke(env, clazz, buf)")
        appendLine("        } else {")
        appendLine("            val runtime = memScoped { fns.FindClass!!.invoke(env, \"java/lang/RuntimeException\".cstr.ptr) }")
        appendLine("            if (runtime != null) fns.ThrowNew!!.invoke(env, runtime, buf)")
        appendLine("        }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/** Whether a JVM exception is pending (safe to call with a pending exception). */")
        appendLine("private fun _knePending(env: CPointer<JNIEnvVar>?): Boolean =")
        appendLine("    _kneFns(env).ExceptionCheck!!.invoke(env) != 0.toUByte()")
        appendLine()
        appendLine("private fun _kneGlobal(env: CPointer<JNIEnvVar>?, ref: jobject?): jobject? =")
        appendLine("    if (ref == null) null else _kneFns(env).NewGlobalRef!!.invoke(env, ref)")
        appendLine()
        appendLine("// ── primitive arrays ─────────────────────────────────────────────────")
        appendLine("private fun _kneReadJBytes(env: CPointer<JNIEnvVar>?, arr: jobject?): ByteArray? {")
        appendLine("    if (arr == null) return null")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val n = fns.GetArrayLength!!.invoke(env, arr)")
        appendLine("    memScoped {")
        appendLine("        val tmp = allocArray<ByteVar>(maxOf(1, n))")
        appendLine("        if (n > 0) fns.GetByteArrayRegion!!.invoke(env, arr, 0, n, tmp)")
        appendLine("        return ByteArray(n) { tmp[it] }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneNewJBytes(env: CPointer<JNIEnvVar>?, values: ByteArray): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val arr = fns.NewByteArray!!.invoke(env, values.size) ?: return null")
        appendLine("    if (values.isNotEmpty()) memScoped {")
        appendLine("        val tmp = allocArray<ByteVar>(values.size)")
        appendLine("        values.forEachIndexed { i, b -> tmp[i] = b }")
        appendLine("        fns.SetByteArrayRegion!!.invoke(env, arr, 0, values.size, tmp)")
        appendLine("    }")
        appendLine("    return arr")
        appendLine("}")
        appendLine()
        for (k in listOf("Int", "Long", "Double", "Float", "Short")) {
            appendLine("private fun _kneReadJ${k}Array(env: CPointer<JNIEnvVar>?, arr: jobject?): ${k}Array? {")
            appendLine("    if (arr == null) return null")
            appendLine("    val fns = _kneFns(env)")
            appendLine("    val n = fns.GetArrayLength!!.invoke(env, arr)")
            appendLine("    memScoped {")
            appendLine("        val tmp = allocArray<${k}Var>(maxOf(1, n))")
            appendLine("        if (n > 0) fns.Get${k}ArrayRegion!!.invoke(env, arr, 0, n, tmp)")
            appendLine("        return ${k}Array(n) { tmp[it] }")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("private fun _kneNewJ${k}Array(env: CPointer<JNIEnvVar>?, values: ${k}Array): jobject? {")
            appendLine("    val fns = _kneFns(env)")
            appendLine("    val arr = fns.New${k}Array!!.invoke(env, values.size) ?: return null")
            appendLine("    if (values.isNotEmpty()) memScoped {")
            appendLine("        val tmp = allocArray<${k}Var>(values.size)")
            appendLine("        values.forEachIndexed { i, v -> tmp[i] = v }")
            appendLine("        fns.Set${k}ArrayRegion!!.invoke(env, arr, 0, values.size, tmp)")
            appendLine("    }")
            appendLine("    return arr")
            appendLine("}")
            appendLine()
        }
        appendLine("private fun _kneReadJBooleanArray(env: CPointer<JNIEnvVar>?, arr: jobject?): BooleanArray? {")
        appendLine("    if (arr == null) return null")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val n = fns.GetArrayLength!!.invoke(env, arr)")
        appendLine("    memScoped {")
        appendLine("        val tmp = allocArray<UByteVar>(maxOf(1, n))")
        appendLine("        if (n > 0) fns.GetBooleanArrayRegion!!.invoke(env, arr, 0, n, tmp)")
        appendLine("        return BooleanArray(n) { tmp[it] != 0u.toUByte() }")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneNewJBooleanArray(env: CPointer<JNIEnvVar>?, values: BooleanArray): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val arr = fns.NewBooleanArray!!.invoke(env, values.size) ?: return null")
        appendLine("    if (values.isNotEmpty()) memScoped {")
        appendLine("        val tmp = allocArray<UByteVar>(values.size)")
        appendLine("        values.forEachIndexed { i, v -> tmp[i] = if (values[i]) 1u else 0u }")
        appendLine("        fns.SetBooleanArrayRegion!!.invoke(env, arr, 0, values.size, tmp)")
        appendLine("    }")
        appendLine("    return arr")
        appendLine("}")
        appendLine()
        appendLine("// ── object arrays ────────────────────────────────────────────────────")
        appendLine("private fun _kneObjArrLen(env: CPointer<JNIEnvVar>?, arr: jobject?): Int =")
        appendLine("    if (arr == null) 0 else _kneFns(env).GetArrayLength!!.invoke(env, arr)")
        appendLine()
        appendLine("private fun _kneObjArrGet(env: CPointer<JNIEnvVar>?, arr: jobject?, i: Int): jobject? =")
        appendLine("    _kneFns(env).GetObjectArrayElement!!.invoke(env, arr, i)")
        appendLine()
        appendLine("private fun _kneObjArrSet(env: CPointer<JNIEnvVar>?, arr: jobject?, i: Int, v: jobject?) {")
        appendLine("    _kneFns(env).SetObjectArrayElement!!.invoke(env, arr, i, v)")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneNewObjArr(env: CPointer<JNIEnvVar>?, size: Int, elemClass: jobject?): jobject? =")
        appendLine("    _kneFns(env).NewObjectArray!!.invoke(env, size, elemClass, null)")
        appendLine()
        appendLine("private fun _kneClass(fqn: String): jobject? = _kneClassCache[fqn]")
        appendLine()
    }

    private fun StringBuilder.appendThreadLocalCaches() {
        // The member-id / field-id lookups are intentionally NOT cached.
        //
        // The cache key has to be the owning class, and the only handle we have for it is a local
        // ref produced by GetObjectClass. The JVM recycles those addresses as soon as a ref is
        // released, so an address-keyed cache happily returns a jmethodID that belongs to a
        // *different* class with an identical descriptor (`invoke(I)V` is shared by dozens of
        // dispatcher classes) — an enum element then arrives inside a Flow<Int>. Resolving the id
        // per call costs one class-table lookup, orders of magnitude less than the JNI round trip
        // it is used for.
        appendLine("private fun _kneMethodId(env: CPointer<JNIEnvVar>?, clazz: jobject?, name: String, sig: String): jmethodID? =")
        appendLine("    memScoped { _kneFns(env).GetMethodID!!.invoke(env, clazz, name.cstr.ptr, sig.cstr.ptr) }")
        appendLine()
        appendLine("private fun _kneStaticMethodId(env: CPointer<JNIEnvVar>?, clazz: jobject?, name: String, sig: String): jmethodID? =")
        appendLine("    memScoped { _kneFns(env).GetStaticMethodID!!.invoke(env, clazz, name.cstr.ptr, sig.cstr.ptr) }")
        appendLine()
        appendLine("private fun _kneFieldId(env: CPointer<JNIEnvVar>?, clazz: jobject?, name: String, sig: String): jfieldID? {")
        appendLine("    val raw = memScoped { _kneFns(env).GetFieldID!!.invoke(env, clazz, name.cstr.ptr, sig.cstr.ptr) }")
        appendLine("    @Suppress(\"UNCHECKED_CAST\")")
        appendLine("    return raw as jfieldID?")
        appendLine("}")
        appendLine()
    }

    private fun StringBuilder.appendCallbackSentinel() {
        appendLine("/** Unwinds native frames when a JVM callback left a pending exception (never touches JNIEnv). */")
        appendLine("private class KneJniCallbackPendingException : RuntimeException()")
        appendLine()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Classes
    // ══════════════════════════════════════════════════════════════════════════

    private val JNI_PREFIX = "env: CPointer<JNIEnvVar>?, thiz: jobject?"

    private fun StringBuilder.appendClass(cls: KneClass) {
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
                val paramsDecl = expandNativeParams(params)
                val paramsText = if (paramsDecl.isEmpty()) "" else ", " + paramsDecl.joinToString(", ") { (pn, pt) -> "$pn: $pt" }
                appendLine("private fun $fnName($JNI_PREFIX$paramsText): Long {")
                appendTryStart(envReconstructParams(params))
                val callArgs = params.joinToString(", ") { p -> callArgExpr(p.name, p.type) }
                appendLine("    return StableRef.create($fq($callArgs)).asCPointer().toLong()")
                appendTryEnd("0L")
                appendLine("}")
                appendLine()
                registerMethod(className, jvmName, paramDescriptor(params) + "J", fnName)
            }
        }

        cls.methods.forEach { method ->
            when {
                method.isSuspend -> appendSuspendMethod(method, cls)
                method.returnType is KneType.FLOW -> appendFlowMethod(method, cls)
                else -> appendMethod(method, cls)
            }
        }

        cls.properties.forEach { prop -> appendProperty(prop, cls) }
        cls.companionMethods.forEach { method -> appendCompanionMethod(method, cls) }
        cls.companionProperties.forEach { prop -> appendCompanionProperty(prop, cls) }
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

    private fun StringBuilder.appendTryStart(pre: String = "") {
        if (pre.isNotEmpty()) appendLine(pre)
        appendLine("    try {")
    }

    /**
     * Closes the try/catch: Kotlin exceptions become ThrowNew(KotlinNativeException);
     * if a JVM callback exception is already pending we return the default silently
     * (calling env is otherwise forbidden while an exception is pending).
     */
    private fun StringBuilder.appendTryEnd(returnExpr: String?) {
        appendLine("    } catch (_: KneJniCallbackPendingException) {")
        if (returnExpr != null) appendLine("        return $returnExpr")
        appendLine("    } catch (e: Throwable) {")
        appendLine("        if (_knePending(env)) {")
        if (returnExpr != null) appendLine("            return $returnExpr")
        appendLine("        } else {")
        appendLine("            _kneThrow(env, e.message ?: e::class.simpleName ?: \"Unknown error\")")
        if (returnExpr != null) appendLine("            return $returnExpr")
        appendLine("        }")
        appendLine("    }")
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

    /** Native return type declaration (empty string for Unit). */
    private fun nativeReturnDecl(type: KneType): String {
        val t = nativeReturnValueKind(type)
        return when (t) {
            "UNIT" -> ""
            "JOBJECT" -> ": jobject?"
            "UBYTE" -> ": UByte"
            "LONG" -> ": Long"
            else -> ": $t"
        }
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
    private fun StringBuilder.emitReturn(expr: String, type: KneType) {
        val dc = JniTypes.extractDataClass(type)
        when {
            dc != null -> {
                if (type is KneType.NULLABLE) {
                    appendLine("    val _result = $expr")
                    appendLine("    return if (_result == null) null else _kneNewJvm${dc.simpleName}(env, _result)")
                } else {
                    appendLine("    return _kneNewJvm${dc.simpleName}(env, $expr)")
                }
            }
            type == KneType.UNIT -> appendLine("    $expr")
            type == KneType.BOOLEAN -> appendLine("    return if ($expr) 1.toUByte() else 0.toUByte()")
            type == KneType.STRING -> appendLine("    return _kneKToJ(env, $expr)")
            type == KneType.BYTE_ARRAY -> appendLine("    return _kneNewJBytes(env, $expr)")
            type is KneType.ENUM -> appendLine("    return ($expr).ordinal")
            type is KneType.OBJECT || type is KneType.INTERFACE ->
                appendLine("    return StableRef.create($expr).asCPointer().toLong()")
            type is KneType.FUNCTION ->
                appendLine("    return StableRef.create($expr).asCPointer().toLong()")
            type is KneType.LIST || type is KneType.SET -> emitArrayBuild(expr, type)
            type is KneType.MAP -> appendLine("    return ${mapBuildExpr(expr, type)}")
            type is KneType.NULLABLE -> when (type.inner) {
                KneType.BOOLEAN -> appendLine("    return if ($expr == null) (-1) else if ($expr == true) 1 else 0")
                KneType.INT -> appendLine("    return $expr?.toLong() ?: Long.MIN_VALUE")
                KneType.LONG -> appendLine("    return $expr ?: Long.MIN_VALUE")
                KneType.SHORT -> appendLine("    return $expr?.toInt() ?: Int.MIN_VALUE")
                KneType.BYTE -> appendLine("    return $expr?.toInt() ?: Int.MIN_VALUE")
                KneType.FLOAT -> appendLine("    return $expr?.toRawBits()?.toLong() ?: Long.MIN_VALUE")
                KneType.DOUBLE -> appendLine("    return $expr?.toRawBits() ?: Long.MIN_VALUE")
                is KneType.ENUM -> appendLine("    return $expr?.ordinal ?: -1")
                else -> {
                    appendLine("    val _result = $expr")
                    appendLine("    if (_result == null) return ${nullableNullReturn(type.inner)}")
                    emitReturn("_result!!", type.inner)
                }
            }
            else -> appendLine("    return $expr")
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
    private fun arrayBuildExpr(expr: String, type: KneType, envExpr: String = "env"): String = when (type) {
        is KneType.LIST -> elemArrayBuildExpr(expr, type.elementType, envExpr)
        is KneType.SET -> elemArrayBuildExpr("$expr.toList()", type.elementType, envExpr)
        else -> "null"
    }

    private fun StringBuilder.emitArrayBuild(expr: String, type: KneType) {
        appendLine("    return ${arrayBuildExpr(expr, type)}")
    }

    private fun elemArrayBuildExpr(expr: String, elemType: KneType, envExpr: String = "env"): String = when (elemType) {
        KneType.INT -> "_kneNewJIntArray($envExpr, ($expr).toIntArray())"
        KneType.LONG -> "_kneNewJLongArray($envExpr, ($expr).toLongArray())"
        KneType.DOUBLE -> "_kneNewJDoubleArray($envExpr, ($expr).toDoubleArray())"
        KneType.FLOAT -> "_kneNewJFloatArray($envExpr, ($expr).toFloatArray())"
        KneType.SHORT -> "_kneNewJShortArray($envExpr, ($expr).toShortArray())"
        KneType.BYTE -> "_kneNewJBytes($envExpr, run { val _b = $expr; ByteArray(_b.size) { _b[it] } })"
        KneType.BOOLEAN -> "_kneNewJBooleanArray($envExpr, ($expr).toBooleanArray())"
        is KneType.ENUM -> "_kneNewJIntArray($envExpr, run { val _l = $expr; IntArray(_l.size) { _l[it].ordinal } })"
        KneType.STRING -> "_kneNewJStrArr($envExpr, $expr)"
        is KneType.OBJECT ->
            "_kneNewJLongArray($envExpr, run { val _l = $expr; LongArray(_l.size) { StableRef.create(_l[it] as ${elemType.fqName}).asCPointer().toLong() } })"
        is KneType.INTERFACE ->
            "_kneNewJLongArray($envExpr, run { val _l = $expr; LongArray(_l.size) { StableRef.create(_l[it] as ${elemType.fqName}).asCPointer().toLong() } })"
        KneType.BYTE_ARRAY -> "_kneNewJBytesArr($envExpr, $expr)"
        is KneType.DATA_CLASS -> "_kneNewJObjArr($envExpr, $expr.map { _kneNewJvm${elemType.simpleName}($envExpr, it) })"
        is KneType.LIST -> "_kneNewJObjArr($envExpr, $expr.map { ${nestedListJobject(elemType.elementType, "it", envExpr)} })"
        is KneType.SET -> "_kneNewJObjArr($envExpr, $expr.map { ${nestedListJobject(elemType.elementType, "it", envExpr)} })"
        is KneType.MAP -> "_kneNewJObjArr($envExpr, $expr.map { ${mapBuildExpr("it", elemType, envExpr)} })"
        else -> "null"
    }

    /** Converts an inner Kotlin List into the primitive/object array the region builders take. */
    private fun innerListToArray(elemType: KneType): String = when (elemType) {
        KneType.INT -> "it.toIntArray()"
        KneType.LONG -> "it.toLongArray()"
        KneType.DOUBLE -> "it.toDoubleArray()"
        KneType.FLOAT -> "it.toFloatArray()"
        KneType.SHORT -> "it.toShortArray()"
        KneType.BYTE -> "it.toByteArray()"
        KneType.BOOLEAN -> "it.toBooleanArray()"
        is KneType.ENUM -> "IntArray(it.size) { it[it].ordinal }"
        KneType.STRING -> "run { val _a = arrayOfNulls<String>(it.size); it.forEachIndexed { i, s -> _a[i] = s }; _a }"
        is KneType.OBJECT ->
            "LongArray(it.size) { StableRef.create(it[it] as ${elemType.fqName}).asCPointer().toLong() }"
        is KneType.INTERFACE ->
            "LongArray(it.size) { StableRef.create(it[it] as ${elemType.fqName}).asCPointer().toLong() }"
        KneType.BYTE_ARRAY -> "run { val _a = arrayOfNulls<ByteArray>(it.size); it.forEachIndexed { i, b -> _a[i] = b }; _a }"
        is KneType.DATA_CLASS -> "run { val _a = arrayOfNulls<Any>(it.size); it.forEachIndexed { i, v -> _a[i] = _kneNewJvm${elemType.simpleName}(env, v) }; _a }"
        else -> "it.toTypedArray()"
    }

    private fun mapBuildExpr(expr: String, type: KneType.MAP, envExpr: String = "env"): String =
        "_kneNewJvmMap_${payloadId(type.keyType)}_${payloadId(type.valueType)}($envExpr, $expr)"

    // ── regular methods ──────────────────────────────────────────────────────

    private fun StringBuilder.appendMethod(fn: KneFunction, cls: KneClass) {
        val fnName = "kne_${cls.simpleName}_${fn.name}"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}"
        val className = jvmClassPath(cls.simpleName)

        val paramsDecl = expandNativeParams(fn.params)
        val paramsText = if (paramsDecl.isEmpty()) "" else ", " + paramsDecl.joinToString(", ") { (pn, pt) -> "$pn: $pt" }
        val fullParams = "$JNI_PREFIX, handle: Long${if (paramsText.isEmpty()) "" else ", " + paramsText.drop(2)}"

        appendLine("private fun $fnName($fullParams)${nativeReturnDecl(fn.returnType)} {")
        appendTryStart(envReconstructParams(fn.params))
        appendLine("    val obj = handle.toCPointer<COpaque>()!!.asStableRef<${cls.fqName}>().get()")
        val callArgs = fn.params.joinToString(", ") { p -> callArgExpr(p.name, p.type) }
        emitReturn("obj.${fn.name}($callArgs)", fn.returnType)
        appendTryEnd(errorReturnExpr(fn.returnType))
        appendLine("}")
        appendLine()
        registerMethod(className, jvmName, "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + ")" + JniTypes.returnDescriptor(fn.returnType, jvmPackage), fnName)
    }

    /** Reconstructs Kotlin values from flattened/expanded JNI params as local vals. */
    private fun envReconstructParams(params: List<KneParam>): String {
        if (params.isEmpty()) return ""
        return buildString {
            appendLine("    // param reconstruction")
            params.forEach { p ->
                val dc = JniTypes.extractDataClass(p.type)
                when {
                    dc != null -> {
                        readDcFields(dc, p.name)
                        readDcMapFields(dc, p.name)
                        val ctorExpr = buildDcCtorExpr(dc, p.name)
                        if (p.type is KneType.NULLABLE) {
                            appendLine("    val ${p.name}Obj = if (${p.name}_isNull != 0) null else $ctorExpr")
                        } else {
                            appendLine("    val ${p.name}Obj = $ctorExpr")
                        }
                    }
                    p.type == KneType.STRING -> appendLine("    val ${p.name}Str = _kneJToK(env, ${p.name}) ?: \"\"")
                    p.type is KneType.NULLABLE && (p.type as KneType.NULLABLE).inner == KneType.STRING ->
                        appendLine("    val ${p.name}Str = _kneJToK(env, ${p.name})")
                    p.type == KneType.BYTE_ARRAY -> appendLine("    val ${p.name}Arr = _kneReadJBytes(env, ${p.name}) ?: ByteArray(0)")
                    p.type is KneType.FUNCTION -> appendCallbackLambda(p.name, p.type as KneType.FUNCTION)
                    p.type is KneType.NULLABLE && (p.type as KneType.NULLABLE).inner is KneType.FUNCTION ->
                        appendCallbackLambda(p.name, (p.type as KneType.NULLABLE).inner as KneType.FUNCTION, nullable = true)
                    else -> {}
                }
                val inner = JniTypes.unwrapNullable(p.type)
                val isNull = p.type is KneType.NULLABLE
                when (inner) {
                    is KneType.LIST -> appendLine("    val ${p.name}List = ${collReadExpr(p.name, inner.elementType, "List", isNull)}")
                    is KneType.SET -> appendLine("    val ${p.name}Set = ${collReadExpr(p.name, inner.elementType, "Set", isNull)}")
                    is KneType.MAP -> appendLine("    val ${p.name}Map = ${mapReadExpr(p.name, inner, isNull)}")
                    else -> {}
                }
            }
        }.trimEnd('\n', ' ')
    }

    /** Reads flattened DC fields from JNI payload params into converted local vals. */
    private fun StringBuilder.readDcFields(dc: KneType.DATA_CLASS, prefix: String) {
        dc.fields.forEach { f ->
            val name = "${prefix}_${f.name}"
            when (val t = f.type) {
                KneType.STRING -> appendLine("    val $name = _kneJToK(env, $name) ?: \"\"")
                KneType.BOOLEAN -> appendLine("    val $name = $name != 0.toUByte()")
                is KneType.DATA_CLASS -> readDcFields(t, name)
                is KneType.OBJECT -> appendLine("    val $name = $name.toCPointer<COpaque>()!!.asStableRef<${t.fqName}>().get()")
                is KneType.INTERFACE -> appendLine("    val $name = $name.toCPointer<COpaque>()!!.asStableRef<${t.fqName}>().get()")
                is KneType.ENUM -> appendLine("    val $name = ${t.fqName}.entries[$name]")
                KneType.BYTE_ARRAY -> appendLine("    val $name = _kneReadJBytes(env, $name) ?: ByteArray(0)")
                is KneType.LIST -> appendLine("    val $name = ${collReadExpr(name, t.elementType, "List", isNull = false)}")
                is KneType.SET -> appendLine("    val $name = ${collReadExpr(name, t.elementType, "Set", isNull = false)}")
                else -> {}
            }
        }
    }

    /** Constructor expression for a DC from converted local vals. */
    private fun buildDcCtorExpr(dc: KneType.DATA_CLASS, prefix: String): String {
        val fieldArgs = dc.fields.joinToString(", ") { f ->
            val paramName = "${prefix}_${f.name}"
            when (f.type) {
                is KneType.DATA_CLASS -> "${f.name} = ${buildDcCtorExpr(f.type, paramName)}"
                is KneType.MAP -> "${f.name} = ${paramName}Map"
                else -> "${f.name} = $paramName"
            }
        }
        return "${dc.fqName}($fieldArgs)"
    }

    /** Reads DC map fields (keys/values array pairs) into `${prefix}_${name}Map` locals. */
    private fun StringBuilder.readDcMapFields(dc: KneType.DATA_CLASS, prefix: String) {
        dc.fields.forEach { f ->
            val name = "${prefix}_${f.name}"
            when (val t = f.type) {
                is KneType.MAP -> appendLine("    val ${name}Map = ${mapReadExpr(name, t, isNull = false)}")
                is KneType.DATA_CLASS -> readDcMapFields(t, name)
                else -> {}
            }
        }
    }

    /** Expression converting a JNI param into the Kotlin value passed to the user function. */
    private fun callArgExpr(name: String, type: KneType): String = when {
        JniTypes.extractDataClass(type) != null -> "${name}Obj"
        type == KneType.STRING -> "${name}Str"
        type == KneType.BYTE_ARRAY -> "${name}Arr"
        type == KneType.BOOLEAN -> "$name != 0.toUByte()"
        type is KneType.ENUM -> "${type.fqName}.entries[$name]"
        type is KneType.OBJECT -> "$name.toCPointer<COpaque>()!!.asStableRef<${type.fqName}>().get()"
        type is KneType.INTERFACE -> "$name.toCPointer<COpaque>()!!.asStableRef<${type.fqName}>().get()"
        type is KneType.FUNCTION -> "${name}Fn"
        type is KneType.NULLABLE -> when (type.inner) {
            is KneType.FUNCTION -> "${name}FnN"
            KneType.STRING -> "${name}Str"
            is KneType.OBJECT, is KneType.INTERFACE ->
                "if ($name != 0L) $name.toCPointer<COpaque>()!!.asStableRef<${(type.inner as KneType.OBJECT).fqName}>().get() else null"
            KneType.BOOLEAN -> "if ($name >= 0) $name != 0 else null"
            KneType.INT -> "if ($name != Long.MIN_VALUE) $name.toInt() else null"
            KneType.LONG -> "if ($name != Long.MIN_VALUE) $name else null"
            KneType.SHORT -> "if ($name != Int.MIN_VALUE) $name.toShort() else null"
            KneType.BYTE -> "if ($name != Int.MIN_VALUE) $name.toByte() else null"
            KneType.FLOAT -> "if ($name != Long.MIN_VALUE) Float.fromBits($name.toInt()) else null"
            KneType.DOUBLE -> "if ($name != Long.MIN_VALUE) Double.fromBits($name) else null"
            is KneType.ENUM -> "if ($name >= 0) ${type.inner.fqName}.entries[$name] else null"
            is KneType.LIST -> "${name}List"
            is KneType.SET -> "${name}Set"
            is KneType.MAP -> "${name}Map"
            else -> name
        }
        type is KneType.LIST -> "${name}List"
        type is KneType.SET -> "${name}Set"
        type is KneType.MAP -> "${name}Map"
        else -> name
    }

    /** Reads a collection jarray payload into a Kotlin collection expression. */
    private fun collReadExpr(name: String, elemType: KneType, collKind: String, isNull: Boolean): String {
        val readList = when (elemType) {
            KneType.INT -> "_kneReadJIntArray(env, $name)?.toList() ?: emptyList()"
            KneType.LONG -> "_kneReadJLongArray(env, $name)?.toList() ?: emptyList()"
            KneType.DOUBLE -> "_kneReadJDoubleArray(env, $name)?.toList() ?: emptyList()"
            KneType.FLOAT -> "_kneReadJFloatArray(env, $name)?.toList() ?: emptyList()"
            KneType.SHORT -> "_kneReadJShortArray(env, $name)?.toList() ?: emptyList()"
            KneType.BYTE -> "_kneReadJBytes(env, $name)?.toList() ?: emptyList()"
            KneType.BOOLEAN -> "_kneReadJBooleanArray(env, $name)?.toList() ?: emptyList()"
            is KneType.ENUM -> "_kneReadJIntArray(env, $name)?.toList()?.map { ${elemType.fqName}.entries[it] } ?: emptyList()"
            KneType.STRING -> "_kneReadJStrList(env, $name) ?: emptyList()"
            is KneType.OBJECT ->
                "_kneReadJLongArray(env, $name)?.map { it.toCPointer<COpaque>()!!.asStableRef<${elemType.fqName}>().get() }"
            is KneType.INTERFACE ->
                "_kneReadJLongArray(env, $name)?.map { it.toCPointer<COpaque>()!!.asStableRef<${elemType.fqName}>().get() }"
            KneType.BYTE_ARRAY -> "_kneReadJBytesList(env, $name)"
            is KneType.LIST -> "_kneReadJNestedList(env, $name, \"${payloadId(elemType)}\").map { it as ${elemType.jvmTypeName} }"
            is KneType.SET -> "_kneReadJNestedList(env, $name, \"${payloadId(elemType)}\").map { it as ${elemType.jvmTypeName} }.toSet()"
            is KneType.MAP -> "_kneReadJNestedList(env, $name, \"${payloadId(elemType)}\").map { it as ${elemType.jvmTypeName} }"
            is KneType.DATA_CLASS -> "List(_kneObjArrLen(env, $name)) { _kneReadJvm${elemType.simpleName}(env, _kneObjArrGet(env, $name, it)) }"
            else -> "null"
        }
        return when {
            isNull -> "if ($name == null) null else (${readList ?: "emptyList()"} ?: emptyList())${if (collKind == "Set") ".toSet()" else ""}"
            collKind == "Set" -> "(${readList ?: "emptyList()"} ?: emptyList()).toSet()"
            else -> "$readList ?: emptyList()"
        }
    }

    private fun mapReadExpr(name: String, type: KneType.MAP, isNull: Boolean): String {
        val keys = collReadExpr("${name}_keys", type.keyType, "List", false)
        val values = collReadExpr("${name}_values", type.valueType, "List", false)
        val body = "($keys).zip($values).toMap()"
        return if (isNull) "if (${name}_keys == null) null else $body" else body
    }

    /** Builds the Kotlin lambda wrapper that round-trips a JVM dispatcher through the invoke helper. */
    private fun StringBuilder.appendCallbackLambda(name: String, fnType: KneType.FUNCTION, nullable: Boolean = false) {
        val id = callbackId(fnType)
        val lambdaParams = fnType.paramTypes.mapIndexed { i, t -> "_p$i: ${t.jvmTypeName}" }.joinToString(", ")
        val invokeArgs = fnType.paramTypes.mapIndexed { i, t -> cbLambdaArg(t, "_p$i") }.joinToString(", ")
        appendLine("    val ${name}Fn: ${fnType.jvmTypeName} = { $lambdaParams ->")
        appendLine("        ${callbackResultExpr(fnType, id, name, invokeArgs)}")
        appendLine("    }")
        if (nullable) {
            appendLine("    val ${name}FnN = if ($name != null) ${name}Fn else null")
        }
    }

    /** Lambda arg → invoke-helper arg conversion (enum → ordinal; others pass through). */
    private fun cbLambdaArg(t: KneType, name: String): String = when (t) {
        is KneType.ENUM -> "$name.ordinal"
        else -> name
    }

    /** The lambda body: invoke the JVM dispatcher, then check for pending JVM exceptions. */
    private fun callbackResultExpr(fnType: KneType.FUNCTION, id: String, jobjectName: String, invokeArgs: String): String {
        val argList = if (invokeArgs.isEmpty()) "" else ", $invokeArgs"
        val call = "_kneInvoke_$id(env, $jobjectName$argList)"
        return when (fnType.returnType) {
            KneType.UNIT -> buildString {
                append(call)
                append("; if (_knePending(env)) throw KneJniCallbackPendingException()")
            }
            KneType.BOOLEAN -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r != 0.toUByte() }")
            }
            is KneType.ENUM -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); ${fnType.returnType.fqName}.entries[_r] }")
            }
            KneType.BYTE -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r.toByte() }")
            }
            KneType.SHORT -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r.toShort() }")
            }
            KneType.STRING -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r ?: \"\" }")
            }
            KneType.BYTE_ARRAY -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r ?: ByteArray(0) }")
            }
            is KneType.DATA_CLASS -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r }")
            }
            is KneType.LIST -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r ?: emptyList() }")
            }
            is KneType.SET -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r ?: emptySet() }")
            }
            is KneType.MAP -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r ?: emptyMap() }")
            }
            is KneType.OBJECT -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); ")
                append("_r.toCPointer<COpaque>()!!.asStableRef<${fnType.returnType.simpleName}>().get() }")
            }
            is KneType.INTERFACE -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); ")
                append("_r.toCPointer<COpaque>()!!.asStableRef<${fnType.returnType.simpleName}>().get() }")
            }
            else -> buildString {
                append("run { val _r = $call; if (_knePending(env)) throw KneJniCallbackPendingException(); _r }")
            }
        }
    }

    // ── properties ───────────────────────────────────────────────────────────

    private fun StringBuilder.appendProperty(prop: KneProperty, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)

        val getterFn = "kne_${cls.simpleName}_get_${prop.name}"
        val getterJvm = "_kneGet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
        appendLine("private fun $getterFn($JNI_PREFIX, handle: Long)${nativeReturnDecl(prop.type)} {")
        appendTryStart()
        appendLine("    val obj = handle.toCPointer<COpaque>()!!.asStableRef<${cls.fqName}>().get()")
        emitReturn("obj.${prop.name}", prop.type)
        appendTryEnd(errorReturnExpr(prop.type))
        appendLine("}")
        appendLine()
        registerMethod(className, getterJvm, "(J)" + JniTypes.returnDescriptor(prop.type, jvmPackage), getterFn)

        if (prop.mutable) {
            val setterFn = "kne_${cls.simpleName}_set_${prop.name}"
            val setterJvm = "_kneSet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
            val valueParams = expandNativeParams("value", prop.type)
                .joinToString(", ") { (pn, pt) -> "$pn: $pt" }
            appendLine("private fun $setterFn($JNI_PREFIX, handle: Long${if (valueParams.isEmpty()) "" else ", $valueParams"}) {")
            appendTryStart(envReconstructParams(listOf(KneParam("value", prop.type))))
            appendLine("    val obj = handle.toCPointer<COpaque>()!!.asStableRef<${cls.fqName}>().get()")
            appendLine("    obj.${prop.name} = ${callArgExpr("value", prop.type)}")
            appendTryEnd(null)
            appendLine("}")
            appendLine()
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

    private fun StringBuilder.appendCompanionMethod(fn: KneFunction, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)
        val fnName = "kne_${cls.simpleName}_companion_${fn.name}"
        val jvmName = "_kneCompanion${fn.name.replaceFirstChar { it.uppercaseChar() }}"

        val paramsDecl = expandNativeParams(fn.params)
        val paramsText = if (paramsDecl.isEmpty()) "" else ", " + paramsDecl.joinToString(", ") { (pn, pt) -> "$pn: $pt" }

        appendLine("private fun $fnName($JNI_PREFIX$paramsText)${nativeReturnDecl(fn.returnType)} {")
        appendTryStart(envReconstructParams(fn.params))
        val callArgs = fn.params.joinToString(", ") { p -> callArgExpr(p.name, p.type) }
        emitReturn("${cls.fqName}.${fn.name}($callArgs)", fn.returnType)
        appendTryEnd(errorReturnExpr(fn.returnType))
        appendLine("}")
        appendLine()
        registerMethod(className, jvmName, paramDescriptor(fn.params) + JniTypes.returnDescriptor(fn.returnType, jvmPackage), fnName)
    }

    private fun StringBuilder.appendCompanionProperty(prop: KneProperty, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)

        val getterFn = "kne_${cls.simpleName}_companion_get_${prop.name}"
        val getterJvm = "_kneCompanionGet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
        appendLine("private fun $getterFn($JNI_PREFIX)${nativeReturnDecl(prop.type)} {")
        appendTryStart()
        emitReturn("${cls.fqName}.${prop.name}", prop.type)
        appendTryEnd(errorReturnExpr(prop.type))
        appendLine("}")
        appendLine()
        registerMethod(className, getterJvm, "()" + JniTypes.returnDescriptor(prop.type, jvmPackage), getterFn)

        if (prop.mutable) {
            val setterFn = "kne_${cls.simpleName}_companion_set_${prop.name}"
            val setterJvm = "_kneCompanionSet${prop.name.replaceFirstChar { it.uppercaseChar() }}"
            val valueParams = expandNativeParams("value", prop.type)
                .joinToString(", ") { (pn, pt) -> "$pn: $pt" }
            appendLine("private fun $setterFn($JNI_PREFIX${if (valueParams.isEmpty()) "" else ", $valueParams"}) {")
            appendTryStart(envReconstructParams(listOf(KneParam("value", prop.type))))
            appendLine("    ${cls.fqName}.${prop.name} = ${callArgExpr("value", prop.type)}")
            appendTryEnd(null)
            appendLine("}")
            appendLine()
            registerMethod(
                className, setterJvm,
                paramDescriptor(listOf(KneParam("value", prop.type))) + "V",
                setterFn,
            )
        }
    }

    // ── enums ────────────────────────────────────────────────────────────────

    private fun StringBuilder.appendEnum(enum: KneEnum) {
        // The JVM enum is a plain `enum class` with no native methods (cards are handed over as
        // ordinals), so nothing is registered — the class ref is cached for the constructors and
        // ordinal lookups emitted below.
        cacheClass(jvmClassPath(enum.simpleName))
    }

    // ── top-level functions ──────────────────────────────────────────────────

    private fun StringBuilder.appendTopLevelFunction(fn: KneFunction, module: KneModule) {
        val fnName = "kne_${fn.name}"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}"
        val hostClass = jvmClassPath(module.libName.replaceFirstChar { it.uppercaseChar() })

        val paramsDecl = expandNativeParams(fn.params)
        val paramsText = if (paramsDecl.isEmpty()) "" else ", " + paramsDecl.joinToString(", ") { (pn, pt) -> "$pn: $pt" }

        appendLine("private fun $fnName($JNI_PREFIX$paramsText)${nativeReturnDecl(fn.returnType)} {")
        appendTryStart(envReconstructParams(fn.params))
        val callArgs = fn.params.joinToString(", ") { p -> callArgExpr(p.name, p.type) }
        emitReturn("${fn.name}($callArgs)", fn.returnType)
        appendTryEnd(errorReturnExpr(fn.returnType))
        appendLine("}")
        appendLine()
        registerMethod(hostClass, jvmName, paramDescriptor(fn.params) + JniTypes.returnDescriptor(fn.returnType, jvmPackage), fnName)
    }

    // ── extension functions ──────────────────────────────────────────────────

    private fun StringBuilder.appendExtensionFunction(fn: KneFunction, module: KneModule) {
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

        val paramsDecl = expandNativeParams(fn.params)
        val paramsText = if (paramsDecl.isEmpty()) "" else ", " + paramsDecl.joinToString(", ") { (pn, pt) -> "$pn: $pt" }
        // JNI hands the Java args over in declaration order: the JVM side declares
        // `_kneExtX(receiverHandle: Long, ...params)`, so the receiver must come FIRST here too.
        val fullParams = "$JNI_PREFIX, receiverHandle: Long$paramsText"

        appendLine("private fun $fnName($fullParams)${nativeReturnDecl(fn.returnType)} {")
        appendTryStart(envReconstructParams(fn.params))
        appendLine("    val receiver = receiverHandle.toCPointer<COpaque>()!!.asStableRef<$receiverFq>().get()")
        val callArgs = fn.params.joinToString(", ") { p -> callArgExpr(p.name, p.type) }
        emitReturn("receiver.${fn.name}($callArgs)", fn.returnType)
        appendTryEnd(errorReturnExpr(fn.returnType))
        appendLine("}")
        appendLine()
        registerMethod(hostClass, jvmName, "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + ")" + JniTypes.returnDescriptor(fn.returnType, jvmPackage), fnName)
    }

    // ── sealed classes ───────────────────────────────────────────────────────

    /**
     * Keeps the native-side subclass-ordinal helper for future use, but only caches the sealed
     * class ref: the generated JVM sealed base has no `external fun` to bind, and registering a
     * native method on a class that does not declare it makes `RegisterNatives` fail.
     */
    private fun StringBuilder.appendSealedOrdinalBridge(cls: KneClass) {
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

    private fun StringBuilder.appendSuspendMethod(fn: KneFunction, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)
        val fnName = "kne_${cls.simpleName}_${fn.name}Async"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}Async"

        val paramsDecl = expandNativeParams(fn.params)
        val paramsText = if (paramsDecl.isEmpty()) "" else ", " + paramsDecl.joinToString(", ") { (pn, pt) -> "$pn: $pt" }
        val fullParams = "$JNI_PREFIX, handle: Long${if (paramsText.isEmpty()) "" else ", " + paramsText.drop(2)}, completion: jobject?"

        appendLine("private fun $fnName($fullParams): Long {")
        appendTryStart(envReconstructParams(fn.params))
        appendLine("    val obj = handle.toCPointer<COpaque>()!!.asStableRef<${cls.fqName}>().get()")
        appendLine("    val _completion = _kneGlobal(env, completion)")
        appendLine("    val _job = Job()")
        val callArgs = fn.params.joinToString(", ") { p -> callArgExpr(p.name, p.type) }
        appendLine("    CoroutineScope(_job + Dispatchers.Default).launch(start = CoroutineStart.ATOMIC) {")
        appendLine("        try {")
        appendLine("            val _result = obj.${fn.name}($callArgs)")
        appendLine("            _kneAttached { _e -> ${suspendOkCall(fn.returnType, "_result")} }")
        appendLine("        } catch (e: CancellationException) {")
        appendLine("            _kneAttached { _e -> _kneSuspendCancelled(_e, _completion!!) }")
        appendLine("        } catch (e: Throwable) {")
        appendLine("            val _msg = e.message ?: e::class.simpleName ?: \"Unknown error\"")
        appendLine("            _kneAttached { _e -> _kneSuspendError(_e, _completion!!, _msg) }")
        appendLine("        } finally {")
        appendLine("            _kneAttached { _e -> _kneFns(_e).DeleteGlobalRef!!.invoke(_e, _completion) }")
        appendLine("        }")
        appendLine("    }")
        appendLine("    return StableRef.create(_job).asCPointer().toLong()")
        appendTryEnd("0L")
        appendLine("}")
        appendLine()
        registerMethod(
            className, jvmName,
            "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + "Ljava/lang/Object;)J",
            fnName,
        )
    }

    /** The (single-line) call delivering a suspend success payload of [type] to the completion object. */
    private fun suspendOkCall(type: KneType, valueExpr: String): String {
        val id = payloadId(type)
        if (type == KneType.UNIT) return "_kneSuspendOk_Void(_e, _completion!!)"
        val args = suspendPayloadArg(type, valueExpr)
        return "_kneSuspendOk_$id(_e, _completion!!, $args)"
    }

    /**
     * JNI payload arg expressions for a suspend/flow success value.
     *
     * [envExpr] must be the JNIEnv valid at the point of use: suspend/flow completion runs on a
     * coroutine worker thread, so it is the `_e` handed in by `_kneAttached`, never the caller's
     * `env` (using the latter silently builds arrays on the wrong thread → garbage/null payloads).
     */
    private fun suspendPayloadArg(type: KneType, valueExpr: String, envExpr: String = "_e"): String = when {
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING -> "$valueExpr?.let { _kneKToJ($envExpr, it) }"
            KneType.BOOLEAN -> "if ($valueExpr != null) (if ($valueExpr) 1 else 0) else -1"
            KneType.INT -> "$valueExpr?.toLong() ?: Long.MIN_VALUE"
            KneType.LONG -> "$valueExpr ?: Long.MIN_VALUE"
            KneType.SHORT -> "$valueExpr?.toInt() ?: Int.MIN_VALUE"
            KneType.BYTE -> "$valueExpr?.toInt() ?: Int.MIN_VALUE"
            KneType.FLOAT -> "$valueExpr?.toRawBits()?.toLong() ?: Long.MIN_VALUE"
            KneType.DOUBLE -> "$valueExpr?.toRawBits() ?: Long.MIN_VALUE"
            is KneType.ENUM -> "$valueExpr?.ordinal ?: -1"
            KneType.BYTE_ARRAY -> "$valueExpr?.let { _kneNewJBytes($envExpr, it) }"
            is KneType.LIST -> "$valueExpr?.let { ${elemArrayBuildExpr("it", type.inner.elementType, envExpr)} }"
            is KneType.SET -> "$valueExpr?.let { ${elemArrayBuildExpr("it.toList()", type.inner.elementType, envExpr)} }"
            is KneType.MAP -> "$valueExpr?.let { ${mapBuildExpr("it", type.inner, envExpr)} }"
            is KneType.OBJECT, is KneType.INTERFACE ->
                "$valueExpr?.let { StableRef.create(it).asCPointer().toLong() } ?: 0L"
            is KneType.DATA_CLASS -> "$valueExpr?.let { _kneNewJvm${type.inner.simpleName}($envExpr, it) }"
            else -> payloadArgExpr(type.inner, valueExpr, envExpr)
        }
        else -> payloadArgExpr(type, valueExpr, envExpr)
    }

    private fun payloadArgExpr(type: KneType, valueExpr: String, envExpr: String = "_e"): String = when (type) {
        KneType.INT -> valueExpr
        KneType.LONG -> valueExpr
        KneType.DOUBLE -> valueExpr
        KneType.FLOAT -> valueExpr
        KneType.BOOLEAN -> "if ($valueExpr) 1u else 0u"
        KneType.BYTE -> valueExpr
        KneType.SHORT -> valueExpr
        KneType.STRING -> "_kneKToJ($envExpr, $valueExpr)"
        KneType.BYTE_ARRAY -> "_kneNewJBytes($envExpr, $valueExpr)"
        is KneType.ENUM -> "$valueExpr.ordinal"
        is KneType.OBJECT, is KneType.INTERFACE -> "StableRef.create($valueExpr).asCPointer().toLong()"
        is KneType.DATA_CLASS -> "_kneNewJvm${type.simpleName}($envExpr, $valueExpr)"
        is KneType.LIST -> elemArrayBuildExpr("$valueExpr", type.elementType, envExpr)
        is KneType.SET -> elemArrayBuildExpr("$valueExpr.toList()", type.elementType, envExpr)
        is KneType.MAP -> mapBuildExpr(valueExpr, type, envExpr)
        KneType.UNIT -> "Unit"
        else -> "null"
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
    private fun payloadNativeParam(type: KneType): String = when {
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> "jobject?"
            is KneType.LIST, is KneType.SET, is KneType.MAP -> "jobject?"
            KneType.BOOLEAN, is KneType.ENUM -> "Int"
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "Long"
            KneType.SHORT, KneType.BYTE -> "Int"
            is KneType.OBJECT, is KneType.INTERFACE -> "Long"
            is KneType.DATA_CLASS -> "jobject?"
            else -> "jobject?"
        }
        else -> when (type) {
            KneType.INT -> "Int"
            KneType.LONG -> "Long"
            KneType.DOUBLE -> "Double"
            KneType.FLOAT -> "Float"
            KneType.BOOLEAN -> "UByte"
            KneType.BYTE -> "Byte"
            KneType.SHORT -> "Short"
            KneType.STRING, KneType.BYTE_ARRAY -> "jobject?"
            is KneType.LIST, is KneType.SET, is KneType.MAP -> "jobject?"
            is KneType.ENUM -> "Int"
            is KneType.OBJECT, is KneType.INTERFACE -> "Long"
            is KneType.DATA_CLASS -> "jobject?"
            else -> "jobject?"
        }
    }

    /** Generates `_kneSuspendOk_<id>` delivering a completed value to `completion.onSuccess`. */
    private fun StringBuilder.appendSuspendOkHelper(type: KneType) {
        val id = payloadId(type)
        if (type == KneType.UNIT) {
            appendLine("private fun _kneSuspendOk_Void(env: CPointer<JNIEnvVar>?, completion: jobject?) {")
            appendLine("    val mid = _kneCompletionMid(env, completion, \"onSuccess\", \"()V\") ?: return")
            appendLine("    _kneFns(env).CallVoidMethodA!!.invoke(env, completion, mid, null)")
            appendLine("}")
            appendLine()
            return
        }
        appendLine("private fun _kneSuspendOk_$id(env: CPointer<JNIEnvVar>?, completion: jobject?, value: ${payloadNativeParam(type)}) {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val mid = _kneCompletionMid(env, completion, \"onSuccess\", \"${successJvmSig(type)}\") ?: return")
        appendLine("    memScoped {")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].${jvalueSlot(type)} = ${payloadJvalueExpr(type, "value")}")
        appendLine("        fns.CallVoidMethodA!!.invoke(env, completion, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
    }

    /** Generates `_kneFlowNext_<id>` delivering a flow element to `callback.onNext`. */
    private fun StringBuilder.appendFlowNextHelper(type: KneType) {
        val id = payloadId(type)
        if (type == KneType.UNIT) {
            // Flow<Unit>: native calls the dispatcher's `onNext()` with no argument, matching the
            // parameterless `onNext()` generated on the JVM side.
            appendLine("private fun _kneFlowNext_$id(env: CPointer<JNIEnvVar>?, callback: jobject?) {")
            appendLine("    val mid = _kneCompletionMid(env, callback, \"onNext\", \"()V\") ?: return")
            appendLine("    _kneFns(env).CallVoidMethodA!!.invoke(env, callback, mid, null)")
            appendLine("}")
            appendLine()
            return
        }
        appendLine("private fun _kneFlowNext_$id(env: CPointer<JNIEnvVar>?, callback: jobject?, value: ${payloadNativeParam(type)}) {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val mid = _kneCompletionMid(env, callback, \"onNext\", \"${successJvmSig(type)}\") ?: return")
        appendLine("    memScoped {")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].${jvalueSlot(type)} = ${payloadJvalueExpr(type, "value")}")
        appendLine("        fns.CallVoidMethodA!!.invoke(env, callback, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
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
    private fun payloadJvalueExpr(type: KneType, valueExpr: String): String = when {
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "$valueExpr" // widened Long bits
            KneType.BOOLEAN, is KneType.ENUM, KneType.SHORT, KneType.BYTE -> "$valueExpr" // sentinel Ints
            else -> payloadJvalueExpr(type.inner, valueExpr) // object refs / nulls pass through
        }
        else -> when (type) {
            KneType.INT, is KneType.ENUM -> valueExpr
            KneType.LONG, is KneType.OBJECT, is KneType.INTERFACE -> valueExpr
            KneType.DOUBLE -> valueExpr
            KneType.FLOAT -> valueExpr
            KneType.BOOLEAN -> "if ($valueExpr != 0.toUByte()) 1u else 0u"
            KneType.BYTE -> valueExpr
            KneType.SHORT -> valueExpr
            else -> valueExpr
        }
    }

    private fun StringBuilder.appendSuspendErrorCancelledHelpers() {
        appendLine("private fun _kneSuspendError(env: CPointer<JNIEnvVar>?, completion: jobject?, message: String) {")
        appendLine("    val mid = _kneCompletionMid(env, completion, \"onError\", \"(Ljava/lang/String;)V\") ?: return")
        appendLine("    memScoped {")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].l = _kneKToJ(env, message)")
        appendLine("        _kneFns(env).CallVoidMethodA!!.invoke(env, completion, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneSuspendCancelled(env: CPointer<JNIEnvVar>?, completion: jobject?) {")
        appendLine("    val mid = _kneCompletionMid(env, completion, \"onCancelled\", \"()V\") ?: return")
        appendLine("    _kneFns(env).CallVoidMethodA!!.invoke(env, completion, mid, null)")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneFlowError(env: CPointer<JNIEnvVar>?, callback: jobject?, message: String) {")
        appendLine("    val mid = _kneCompletionMid(env, callback, \"onError\", \"(Ljava/lang/String;)V\") ?: return")
        appendLine("    memScoped {")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].l = _kneKToJ(env, message)")
        appendLine("        _kneFns(env).CallVoidMethodA!!.invoke(env, callback, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneFlowComplete(env: CPointer<JNIEnvVar>?, callback: jobject?) {")
        appendLine("    val mid = _kneCompletionMid(env, callback, \"onComplete\", \"()V\") ?: return")
        appendLine("    _kneFns(env).CallVoidMethodA!!.invoke(env, callback, mid, null)")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneFlowCancelled(env: CPointer<JNIEnvVar>?, callback: jobject?) {")
        appendLine("    val mid = _kneCompletionMid(env, callback, \"onCancelled\", \"()V\") ?: return")
        appendLine("    _kneFns(env).CallVoidMethodA!!.invoke(env, callback, mid, null)")
        appendLine("}")
        appendLine()
    }

    private fun StringBuilder.appendFlowMethod(fn: KneFunction, cls: KneClass) {
        val className = jvmClassPath(cls.simpleName)
        val fnName = "kne_${cls.simpleName}_${fn.name}Flow"
        val jvmName = "_kne${fn.name.replaceFirstChar { it.uppercaseChar() }}Flow"
        val elemType = (fn.returnType as KneType.FLOW).elementType

        val paramsDecl = expandNativeParams(fn.params)
        val paramsText = if (paramsDecl.isEmpty()) "" else ", " + paramsDecl.joinToString(", ") { (pn, pt) -> "$pn: $pt" }
        val fullParams = "$JNI_PREFIX, handle: Long${if (paramsText.isEmpty()) "" else ", " + paramsText.drop(2)}, callback: jobject?"

        appendLine("private fun $fnName($fullParams): Long {")
        appendTryStart(envReconstructParams(fn.params))
        appendLine("    val obj = handle.toCPointer<COpaque>()!!.asStableRef<${cls.fqName}>().get()")
        appendLine("    val _callback = _kneGlobal(env, callback)")
        appendLine("    val _job = Job()")
        appendLine("    val _credits = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)")
        appendLine("    repeat($FLOW_INITIAL_CREDITS) { _credits.trySend(Unit) }")
        val callArgs = fn.params.joinToString(", ") { p -> callArgExpr(p.name, p.type) }
        appendLine("    CoroutineScope(_job + Dispatchers.Default).launch(start = CoroutineStart.ATOMIC) {")
        appendLine("        try {")
        appendLine("            val _flow = obj.${fn.name}($callArgs)")
        appendLine("            _flow.collect { _value ->")
        appendLine("                _credits.receive()")
        appendLine("                _kneAttached { _e -> ${flowNextCall(elemType, "_value")} }")
        appendLine("            }")
        appendLine("            _kneAttached { _e -> _kneFlowComplete(_e, _callback!!) }")
        appendLine("        } catch (e: CancellationException) {")
        appendLine("            _kneAttached { _e -> _kneFlowCancelled(_e, _callback!!) }")
        appendLine("        } catch (e: Throwable) {")
        appendLine("            val _msg = e.message ?: e::class.simpleName ?: \"Unknown error\"")
        appendLine("            _kneAttached { _e -> _kneFlowError(_e, _callback!!, _msg) }")
        appendLine("        } finally {")
        appendLine("            _kneAttached { _e -> _kneFns(_e).DeleteGlobalRef!!.invoke(_e, _callback) }")
        appendLine("        }")
        appendLine("    }")
        appendLine("    return StableRef.create(KneFlowJob(_job, _credits)).asCPointer().toLong()")
        appendTryEnd("0L")
        appendLine("}")
        appendLine()
        registerMethod(
            className, jvmName,
            "(J" + paramDescriptor(fn.params).drop(1).dropLast(1) + "Ljava/lang/Object;)J",
            fnName,
        )
    }

    private fun flowNextCall(elemType: KneType, valueExpr: String): String =
        // Flow<Unit>: the payload-less helper / dispatcher overload (see appendFlowNextHelper).
        if (elemType == KneType.UNIT) "_kneFlowNext_${payloadId(elemType)}(_e, _callback!!)"
        else "_kneFlowNext_${payloadId(elemType)}(_e, _callback!!, ${suspendPayloadArg(elemType, valueExpr)})"

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
    private fun StringBuilder.appendFnInvokeBridge(fnType: KneType.FUNCTION) {
        val id = JniTypes.functionId(fnType)
        val hostClass = jvmClassPath("KneNativeBindings")
        val params = fnType.paramTypes.mapIndexed { i, t -> KneParam("p$i", t) }
        val fnName = "kne_invokeFn_$id"

        val decl = expandNativeParams(params)
        val declText = if (decl.isEmpty()) "" else ", " + decl.joinToString(", ") { (n, t) -> "$n: $t" }

        appendLine("private fun $fnName($JNI_PREFIX, handle: Long$declText)${nativeReturnDecl(fnType.returnType)} {")
        appendTryStart(envReconstructParams(params))
        appendLine("    val _fn = handle.toCPointer<COpaque>()!!.asStableRef<${fnType.jvmTypeName}>().get()")
        val callArgs = params.joinToString(", ") { callArgExpr(it.name, it.type) }
        emitReturn("_fn($callArgs)", fnType.returnType)
        appendTryEnd(errorReturnExpr(fnType.returnType))
        appendLine("}")
        appendLine()
        registerMethod(
            hostClass, "_kneInvokeFn$id",
            "(J" + paramDescriptor(params).drop(1).dropLast(1) + ")" +
                JniTypes.returnDescriptor(fnType.returnType, jvmPackage),
            fnName,
        )
    }

    /**
     * Generates `_kneInvoke_<id>`: calls the JVM dispatcher's `invoke` method with
     * native payload values. A JVM exception thrown by the callback is left pending;
     * the caller unwinds via the sentinel and it propagates on native return.
     */
    private fun StringBuilder.appendCallbackInvokeHelper(fnType: KneType.FUNCTION) {
        val id = callbackId(fnType)
        val invokeSig = buildString {
            append("(")
            fnType.paramTypes.forEach { t ->
                append(callbackParamJvmSig(t))
            }
            append(")${callbackReturnJvmSig(fnType.returnType)}")
        }

        val params = fnType.paramTypes.mapIndexed { i, t -> "p$i: ${callbackPayloadNativeType(t)}" }
        val callKind = callbackCallKind(fnType.returnType)

        appendLine("private fun _kneInvoke_$id(env: CPointer<JNIEnvVar>?, fn: jobject?${if (params.isEmpty()) "" else ", " + params.joinToString(", ")})${callbackNativeReturnDecl(fnType.returnType)} {")
        appendLine("    if (fn == null) return ${callbackNativeErrorReturn(fnType.returnType)}")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = fns.GetObjectClass!!.invoke(env, fn)")
        appendLine("    val mid = _kneMethodId(env, clazz, \"invoke\", \"$invokeSig\") ?: return ${callbackNativeErrorReturn(fnType.returnType)}")
        appendLine("    memScoped {")
        val argc = fnType.paramTypes.size
        appendLine("        val args = allocArray<jvalue>(maxOf(1, $argc))")
        fnType.paramTypes.forEachIndexed { i, t ->
            appendLine("        args[$i].${callbackJvalueSlot(t)} = ${callbackPayloadJvalueExpr(t, "p$i")}")
        }
        // A throwing JVM callback makes Call*MethodA return NULL with a pending exception. The
        // result must never be handed to another JNI call in that state: GetObjectClass /
        // GetStringUTFChars / GetArrayLength on null segfault the VM. Unwind via the pending
        // protocol instead — the outer bridge catch lets the original exception propagate.
        fun callAndGuard(callExpr: String, retExpr: String) {
            appendLine("        val _r = $callExpr")
            appendLine("        if (_knePending(env)) throw KneJniCallbackPendingException()")
            appendLine("        return $retExpr")
        }
        when (callKind) {
            "VOID" -> {
                appendLine("        fns.CallVoidMethodA!!.invoke(env, fn, mid, args)")
                appendLine("        if (_knePending(env)) throw KneJniCallbackPendingException()")
            }
            "INT" -> callAndGuard("fns.CallIntMethodA!!.invoke(env, fn, mid, args)", "_r")
            "LONG" -> callAndGuard("fns.CallLongMethodA!!.invoke(env, fn, mid, args)", "_r")
            "DOUBLE" -> callAndGuard("fns.CallDoubleMethodA!!.invoke(env, fn, mid, args)", "_r")
            "FLOAT" -> callAndGuard("fns.CallFloatMethodA!!.invoke(env, fn, mid, args)", "_r")
            "UBYTE" -> callAndGuard("fns.CallBooleanMethodA!!.invoke(env, fn, mid, args)", "_r")
            "BYTE" -> callAndGuard("fns.CallByteMethodA!!.invoke(env, fn, mid, args)", "_r")
            "SHORT" -> callAndGuard("fns.CallShortMethodA!!.invoke(env, fn, mid, args)", "_r")
            "STRING" -> callAndGuard("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)", "_kneJToK(env, _r)")
            "BYTES" -> callAndGuard("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)", "_kneReadJBytes(env, _r)")
            "HANDLE_OBJ" -> callAndGuard("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)", "_kneReadJvmHandle(env, _r)")
            "DC_RET" -> callAndGuard("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)", "_kneReadJvm${(fnType.returnType as? KneType.DATA_CLASS)?.simpleName ?: "Todo"}(env, _r)")
            "COLL" -> {
                appendLine("        val _arr = fns.CallObjectMethodA!!.invoke(env, fn, mid, args)")
                appendLine("        if (_knePending(env)) throw KneJniCallbackPendingException()")
            }
            "MAP_PAIR" -> {
                appendLine("        val _pair = fns.CallObjectMethodA!!.invoke(env, fn, mid, args)")
                appendLine("        if (_knePending(env)) throw KneJniCallbackPendingException()")
            }
            else -> callAndGuard("fns.CallObjectMethodA!!.invoke(env, fn, mid, args)", "_r")
        }
        if (callKind == "COLL") {
            appendLine("        return ${callbackCollUnwrap(fnType.returnType)}")
        }
        if (callKind == "MAP_PAIR") {
            appendLine("        val _keys = _kneObjArrGet(env, _pair, 0)")
            appendLine("        val _vals = _kneObjArrGet(env, _pair, 1)")
            appendLine("        return ${callbackMapUnwrap(fnType.returnType as KneType.MAP)}")
        }
        appendLine("    }")
        appendLine("}")
        appendLine()
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
    private fun callbackCollUnwrap(t: KneType): String {
        val elem = if (t is KneType.LIST) t.elementType else (t as KneType.SET).elementType
        val list = when (elem) {
            KneType.INT -> "_kneReadJIntArray(env, _arr)?.toList() ?: emptyList()"
            KneType.LONG -> "_kneReadJLongArray(env, _arr)?.toList() ?: emptyList()"
            KneType.DOUBLE -> "_kneReadJDoubleArray(env, _arr)?.toList() ?: emptyList()"
            KneType.FLOAT -> "_kneReadJFloatArray(env, _arr)?.toList() ?: emptyList()"
            KneType.SHORT -> "_kneReadJShortArray(env, _arr)?.toList() ?: emptyList()"
            KneType.BYTE -> "_kneReadJBytes(env, _arr)?.toList() ?: emptyList()"
            KneType.BOOLEAN -> "_kneReadJBooleanArray(env, _arr)?.toList() ?: emptyList()"
            is KneType.ENUM -> "_kneReadJIntArray(env, _arr)?.toList()?.map { ${elem.simpleName}.entries[it] } ?: emptyList()"
            KneType.STRING -> "_kneReadJStrList(env, _arr) ?: emptyList()"
            is KneType.OBJECT -> "_kneReadJLongArray(env, _arr)?.map { ${elem.simpleName}.fromNativeHandle(it) } ?: emptyList()"
            is KneType.INTERFACE -> "_kneReadJLongArray(env, _arr)?.map { ${elem.simpleName}.fromNativeHandle(it) } ?: emptyList()"
            KneType.BYTE_ARRAY -> "_kneReadJBytesList(env, _arr) ?: emptyList()"
            is KneType.DATA_CLASS -> "_kneReadJLongArray(env, _arr)" // DC lists arrive via listparam bridges
            else -> "_arr?.toList() ?: emptyList()"
        }
        return if (t is KneType.SET) "($list ?: emptyList()).toSet()" else "$list ?: emptyList()"
    }

    /** Unwrap expression for a returned Map (references locals `_keys`/`_vals` payload arrays). */
    private fun callbackMapUnwrap(t: KneType.MAP): String {
        val keys = keyValList(t.keyType, "_keys")
        val vals = keyValList(t.valueType, "_vals")
        return "($keys).zip($vals).toMap()"
    }

    private fun keyValList(t: KneType, arrName: String): String = when (t) {
        KneType.INT -> "_kneReadJIntArray(env, $arrName)?.toList() ?: emptyList()"
        KneType.LONG -> "_kneReadJLongArray(env, $arrName)?.toList() ?: emptyList()"
        KneType.DOUBLE -> "_kneReadJDoubleArray(env, $arrName)?.toList() ?: emptyList()"
        KneType.FLOAT -> "_kneReadJFloatArray(env, $arrName)?.toList() ?: emptyList()"
        KneType.SHORT -> "_kneReadJShortArray(env, $arrName)?.toList() ?: emptyList()"
        KneType.BYTE -> "_kneReadJBytes(env, $arrName)?.toList() ?: emptyList()"
        KneType.BOOLEAN -> "_kneReadJBooleanArray(env, $arrName)?.toList() ?: emptyList()"
        is KneType.ENUM -> "_kneReadJIntArray(env, $arrName)?.toList()?.map { ${t.simpleName}.entries[it] } ?: emptyList()"
        KneType.STRING -> "_kneReadJStrList(env, $arrName) ?: emptyList()"
        is KneType.OBJECT, is KneType.INTERFACE -> "_kneReadJLongArray(env, $arrName)?.toList() ?: emptyList()"
        else -> "emptyList()"
    }

    /** Native return type decl for callback invoke helpers (native Kotlin types). */

    private fun callbackNativeReturnDecl(t: KneType): String = when (t) {
        KneType.UNIT -> ""
        KneType.INT, is KneType.ENUM -> ": Int"
        KneType.LONG -> ": Long"
        KneType.DOUBLE -> ": Double"
        KneType.FLOAT -> ": Float"
        KneType.BOOLEAN -> ": UByte"
        KneType.BYTE -> ": Byte"
        KneType.SHORT -> ": Short"
        KneType.STRING -> ": String?"
        KneType.BYTE_ARRAY -> ": ByteArray?"
        is KneType.OBJECT, is KneType.INTERFACE -> ": Long"
        is KneType.DATA_CLASS -> ": ${t.fqName}"
        is KneType.LIST -> ": List<${callbackElemJvm(t.elementType)}>"
        is KneType.SET -> ": Set<${callbackElemJvm(t.elementType)}>"
        is KneType.MAP -> ": Map<${t.keyType.jvmTypeName}, ${t.valueType.jvmTypeName}>"
        else -> ": jobject?"
    }

    private fun callbackElemJvm(t: KneType): String = when (t) {
        is KneType.OBJECT -> t.simpleName
        is KneType.INTERFACE -> t.simpleName
        is KneType.DATA_CLASS -> t.simpleName
        else -> t.jvmTypeName
    }

    private fun callbackNativeErrorReturn(t: KneType): String = when (t) {
        KneType.UNIT -> "Unit"
        KneType.INT, is KneType.ENUM -> "0"
        KneType.LONG -> "0L"
        KneType.DOUBLE -> "0.0"
        KneType.FLOAT -> "0.0f"
        KneType.BOOLEAN -> "0.toUByte()"
        KneType.BYTE -> "0.toByte()"
        KneType.SHORT -> "0.toShort()"
        KneType.STRING -> "null"
        KneType.BYTE_ARRAY -> "null"
        is KneType.OBJECT, is KneType.INTERFACE -> "0L"
        is KneType.DATA_CLASS -> "error(\"callback dispatch failed\")"
        is KneType.LIST -> "emptyList()"
        is KneType.SET -> "emptySet()"
        is KneType.MAP -> "emptyMap()"
        else -> "null"
    }

    private fun callbackPayloadNativeType(t: KneType): String = when (t) {
        KneType.INT, is KneType.ENUM -> "Int"
        KneType.LONG -> "Long"
        KneType.DOUBLE -> "Double"
        KneType.FLOAT -> "Float"
        KneType.BOOLEAN -> "Boolean"
        KneType.BYTE -> "Byte"
        KneType.SHORT -> "Short"
        KneType.STRING -> "String"
        KneType.BYTE_ARRAY -> "ByteArray"
        is KneType.OBJECT, is KneType.INTERFACE -> t.jvmTypeName
        is KneType.DATA_CLASS -> t.fqName
        is KneType.LIST -> "List<${t.elementType.jvmTypeName}>"
        is KneType.SET -> "Set<${t.elementType.jvmTypeName}>"
        is KneType.MAP -> "Map<${t.keyType.jvmTypeName}, ${t.valueType.jvmTypeName}>"
        else -> "Any?"
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
    private fun nestedListJobject(elemType: KneType, paramName: String, envExpr: String = "env"): String = when (elemType) {
        KneType.INT -> "_kneNewJIntArray($envExpr, $paramName.toIntArray())"
        KneType.LONG -> "_kneNewJLongArray($envExpr, $paramName.toLongArray())"
        KneType.DOUBLE -> "_kneNewJDoubleArray($envExpr, $paramName.toDoubleArray())"
        KneType.FLOAT -> "_kneNewJFloatArray($envExpr, $paramName.toFloatArray())"
        KneType.SHORT -> "_kneNewJShortArray($envExpr, $paramName.toShortArray())"
        KneType.BYTE -> "_kneNewJBytes($envExpr, $paramName.toByteArray())"
        KneType.BOOLEAN -> "_kneNewJBooleanArray($envExpr, $paramName.toBooleanArray())"
        is KneType.ENUM -> "_kneNewJIntArray($envExpr, IntArray($paramName.size) { $paramName[it].ordinal })"
        KneType.STRING -> "_kneNewJStrArr($envExpr, $paramName)"
        is KneType.OBJECT -> "_kneNewJLongArray($envExpr, LongArray($paramName.size) { StableRef.create($paramName[it] as ${elemType.fqName}).asCPointer().toLong() })"
        is KneType.INTERFACE -> "_kneNewJLongArray($envExpr, LongArray($paramName.size) { StableRef.create($paramName[it] as ${elemType.fqName}).asCPointer().toLong() })"
        KneType.BYTE_ARRAY -> "_kneNewJBytesArr($envExpr, $paramName)"
        is KneType.DATA_CLASS -> "_kneNewJObjArr($envExpr, $paramName.map { _kneNewJvm${elemType.simpleName}($envExpr, it) })"
        is KneType.LIST -> "_kneNewJObjArr($envExpr, $paramName.map { ${nestedListJobject(elemType.elementType, "it", envExpr)} })"
        is KneType.SET -> "_kneNewJObjArr($envExpr, $paramName.map { ${nestedListJobject(elemType.elementType, "it", envExpr)} })"
        else -> "null"
    }

    private fun callbackPayloadJvalueExpr(t: KneType, name: String): String = when (t) {
        KneType.INT, is KneType.ENUM -> name
        KneType.LONG -> name
        KneType.DOUBLE -> name
        KneType.FLOAT -> name
        KneType.BOOLEAN -> "if ($name) 1u else 0u"
        KneType.BYTE -> name
        KneType.SHORT -> name
        KneType.STRING -> "_kneKToJ(env, $name)"
        KneType.BYTE_ARRAY -> "_kneNewJBytes(env, $name)"
        is KneType.OBJECT -> "_kneNewJvmProxy${t.simpleName}(env, StableRef.create($name).asCPointer().toLong())"
        is KneType.INTERFACE -> "_kneNewJvmProxy${t.simpleName}(env, StableRef.create($name).asCPointer().toLong())"
        is KneType.DATA_CLASS -> "_kneNewJvm${t.simpleName}(env, $name)"
        is KneType.LIST -> elemArrayBuildExpr(name, t.elementType)
        is KneType.SET -> elemArrayBuildExpr("$name.toList()", t.elementType)
        is KneType.MAP -> mapBuildExpr(name, t)
        else -> "null"
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
        fun scanDc(dc: KneType.DATA_CLASS) {
            dc.fields.forEach { scanType(it.type) }
        }
        collectJvmMaterialisedDcs(module).forEach { scanDc(it) }
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
    private fun StringBuilder.appendJvmListElemHelpers(module: KneModule) {
        // nested-list constructors (List<List<X>> fields)
        fun collectNestedListIds(type: KneType, acc: MutableSet<String>) {
            when (type) {
                is KneType.LIST -> {
                    if (type.elementType is KneType.LIST) acc.add(jvmListElemId(type.elementType))
                    collectNestedListIds(type.elementType, acc)
                }
                is KneType.SET -> collectNestedListIds(type.elementType, acc)
                is KneType.DATA_CLASS -> type.fields.forEach { collectNestedListIds(it.type, acc) }
                else -> {}
            }
        }
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
            appendLine("/** Constructs a java.util.List of nested lists (element id $nid). */")
            appendLine("private fun _kneNewJvmListOf$nid(env: CPointer<JNIEnvVar>?, values: List<Any?>): jobject? {")
            appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
            appendLine("    for (v in values) _kneJvmListAdd(env, out, v as? jobject)")
            appendLine("    return out")
            appendLine("}")
            appendLine()
        }
        for (e in collectReferencedEnumsForDcFields(module)) {
            val (fq, simple) = e
            appendLine("/** Constructs a java.util.List<${simple}> from Kotlin values (element = values[ordinal]). */")
            appendLine("private fun _kneNewJvmListOfE$simple(env: CPointer<JNIEnvVar>?, values: List<$fq>): jobject? {")
            appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
            appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneNewJvmEnum$simple(env, v.ordinal))")
            appendLine("    return out")
            appendLine("}")
            appendLine()
        }
        for (dc in collectJvmMaterialisedDcs(module)) {
            appendLine("/** Constructs a java.util.List<${dc.simpleName}> from Kotlin values. */")
            appendLine("private fun _kneNewJvmListOfD${dc.simpleName}(env: CPointer<JNIEnvVar>?, values: List<${dc.fqName}>): jobject? {")
            appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
            appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneNewJvm${dc.simpleName}(env, v))")
            appendLine("    return out")
            appendLine("}")
            appendLine()
        }
        appendLine("/** Generic fallback: empty list (unsupported element kind). */")
        appendLine("private fun _kneNewJvmListOfX(env: CPointer<JNIEnvVar>?, values: List<Any?>): jobject? {")
        appendLine("    return _kneNewJvmArrayList(env, 0)")
        appendLine("}")
        appendLine()
        for (cls in collectReferencedProxyClasses(module)) {
            appendLine("/** Constructs a java.util.List<${cls.simpleName}> from Kotlin values (element = proxy handle). */")
            appendLine("private fun _kneNewJvmListOfH${cls.simpleName}(env: CPointer<JNIEnvVar>?, values: List<${cls.fqName}>): jobject? {")
            appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
            appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneNewJvmProxy${cls.simpleName}(env, StableRef.create(v).asCPointer().toLong()))")
            appendLine("    return out")
            appendLine("}")
            appendLine()
        }
    }

    /** Emits the NewObjectA-based constructor helper for one data class. */
    private fun StringBuilder.appendJvmDcConstructor(dc: KneType.DATA_CLASS, module: KneModule) {
        val n = dc.simpleName
        val fq = dc.fqName
        val ctorSig = buildString {
            append("(")
            dc.fields.forEach { f -> append(dcFieldJvmSig(f.type)) }
            append(")V")
        }

        appendLine("@kotlin.native.concurrent.ThreadLocal")
        appendLine("private object _kneDcJvm$n { var ctor: jmethodID? = null }")
        appendLine()
        appendLine("private fun _kneNewJvm$n(env: CPointer<JNIEnvVar>?, value: $fq): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = _kneClass(\"${fq.replace('.', '/')}\") ?: return null")
        appendLine("    var mid = _kneDcJvm$n.ctor")
        appendLine("    if (mid == null) { mid = memScoped { fns.GetMethodID!!.invoke(env, clazz, \"<init>\".cstr.ptr, \"$ctorSig\".cstr.ptr) }; _kneDcJvm$n.ctor = mid }")
        appendLine("    memScoped {")
        appendLine("        val args = allocArray<jvalue>(maxOf(1, ${dc.fields.size}))")
        var idx = 0
        dc.fields.forEach { f ->
            val expr = "value.${f.name}"
            appendLine("        args[$idx].${dcFieldSlot(f.type)} = ${dcFieldJvalueExpr(f.type, expr, module)}")
            idx++
        }
        appendLine("        return fns.NewObjectA!!.invoke(env, clazz, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
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
        KneType.INT -> "i"; KneType.LONG -> "j"; KneType.DOUBLE -> "d"; KneType.FLOAT -> "f"
        KneType.BOOLEAN -> "z"; KneType.BYTE -> "b"; KneType.SHORT -> "s"
        else -> "l"
    }

    /** JNI signature for a DC constructor field. */
    private fun dcFieldJvmSig(type: KneType): String = when (type) {
        KneType.INT -> "I"; KneType.LONG -> "J"; KneType.DOUBLE -> "D"; KneType.FLOAT -> "F"
        KneType.BOOLEAN -> "Z"; KneType.BYTE -> "B"; KneType.SHORT -> "S"
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
    private fun dcFieldJvalueExpr(type: KneType, expr: String, module: KneModule): String = when (type) {
        KneType.INT -> expr
        KneType.LONG -> expr
        KneType.DOUBLE -> expr
        KneType.FLOAT -> expr
        KneType.BOOLEAN -> "if ($expr) 1u else 0u"
        KneType.BYTE -> expr
        KneType.SHORT -> expr
        KneType.STRING -> "_kneKToJ(env, $expr)"
        KneType.BYTE_ARRAY -> "_kneNewJBytes(env, $expr)"
        is KneType.ENUM -> "_kneNewJvmEnum${type.simpleName}(env, $expr.ordinal)"
        is KneType.OBJECT ->
            "_kneNewJvmProxy${type.simpleName}(env, StableRef.create($expr).asCPointer().toLong())"
        is KneType.INTERFACE ->
            "_kneNewJvmProxy${type.simpleName}(env, StableRef.create($expr).asCPointer().toLong())"
        is KneType.DATA_CLASS -> "_kneNewJvm${type.simpleName}(env, $expr)"
        is KneType.LIST -> "_kneNewJvmListOf${jvmListElemId(type.elementType)}(env, $expr)"
        is KneType.SET -> "_kneNewJvmListOf${jvmListElemId(type.elementType)}(env, $expr.toList())"
        is KneType.MAP -> mapBuildExpr(expr, type)
        else -> "null"
    }

    /** Emits enum constant lookup (values() array element by ordinal). */
    private fun StringBuilder.appendJvmEnumConstant(ref: Pair<String, String>) {
        val (fq, simple) = ref
        appendLine("private fun _kneNewJvmEnum$simple(env: CPointer<JNIEnvVar>?, ordinal: Int): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = _kneClass(\"${fq.replace('.', '/')}\") ?: return null")
        appendLine("    memScoped {")
        appendLine("        val mid = _kneStaticMethodId(env, clazz, \"values\", \"()[L${fq.replace('.', '/')};\") ?: return null")
        appendLine("        val arr = fns.CallStaticObjectMethodA!!.invoke(env, clazz, mid, null) ?: return null")
        appendLine("        return fns.GetObjectArrayElement!!.invoke(env, arr, ordinal)")
        appendLine("    }")
        appendLine("}")
        appendLine()
    }

    /** Emits proxy construction from a native handle (cached static `fromNativeHandle`). */
    private fun StringBuilder.appendJvmProxyFromHandle(cls: KneClass) {
        val fq = cls.fqName
        appendLine("private fun _kneNewJvmProxy${cls.simpleName}(env: CPointer<JNIEnvVar>?, handle: Long): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = _kneClass(\"${fq.replace('.', '/')}\") ?: return null")
        appendLine("    memScoped {")
        appendLine("        val mid = _kneStaticMethodId(env, clazz, \"fromNativeHandle\", \"(J)L${fq.replace('.', '/')};\") ?: return null")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].j = handle")
        appendLine("        return fns.CallStaticObjectMethodA!!.invoke(env, clazz, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
    }

    /** Emits per-combination java.util.Map builders for all Map return/DC-field types. */
    private fun StringBuilder.appendJvmMapHelpers(module: KneModule) {
        appendLine("// ── java.util.HashMap construction (Map returns / DC map fields) ────")
        appendLine("@kotlin.native.concurrent.ThreadLocal")
        appendLine("private object _kneHashMapCache { var ctor: jmethodID? = null }")
        appendLine()
        appendLine("private fun _kneNewJvmHashMap(env: CPointer<JNIEnvVar>?): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = _kneClass(\"java/util/HashMap\") ?: return null")
        appendLine("    var mid = _kneHashMapCache.ctor")
        appendLine("    if (mid == null) { mid = memScoped { fns.GetMethodID!!.invoke(env, clazz, \"<init>\".cstr.ptr, \"()V\".cstr.ptr) }; _kneHashMapCache.ctor = mid }")
        appendLine("    return fns.NewObjectA!!.invoke(env, clazz, mid, null)")
        appendLine("}")
        appendLine()
        appendLine("@kotlin.native.concurrent.ThreadLocal")
        appendLine("private object _kneBoxCache { val mids = HashMap<String, jmethodID?>() }")
        appendLine()
        appendLine("/** Boxes a primitive via the boxed type's valueOf static method (payload slot picked by [slot]). */")
        appendLine("private fun _kneJvmBoxSlot(env: CPointer<JNIEnvVar>?, boxClass: String, sig: String, slot: Char, i: Int, l: Long, d: Double): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = _kneClass(boxClass) ?: return null")
        appendLine("    var mid = _kneBoxCache.mids[sig]")
        appendLine("    if (mid == null) {")
        appendLine("        mid = memScoped { fns.GetStaticMethodID!!.invoke(env, clazz, \"valueOf\".cstr.ptr, sig.cstr.ptr) }")
        appendLine("        _kneBoxCache.mids[sig] = mid")
        appendLine("    }")
        appendLine("    memScoped {")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        when (slot) {")
        appendLine("            'i' -> args[0].i = i")
        appendLine("            'j' -> args[0].j = l")
        appendLine("            else -> args[0].d = d")
        appendLine("        }")
        appendLine("        return fns.CallStaticObjectMethodA!!.invoke(env, clazz, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneJvmBoxI(env: CPointer<JNIEnvVar>?, boxClass: String, sig: String, v: Int): jobject? = _kneJvmBoxSlot(env, boxClass, sig, 'i', v, 0L, 0.0)")
        appendLine("private fun _kneJvmBoxL(env: CPointer<JNIEnvVar>?, boxClass: String, sig: String, v: Long): jobject? = _kneJvmBoxSlot(env, boxClass, sig, 'j', 0, v, 0.0)")
        appendLine("private fun _kneJvmBoxD(env: CPointer<JNIEnvVar>?, v: Double): jobject? = _kneJvmBoxSlot(env, \"java/lang/Double\", \"(D)Ljava/lang/Double;\", 'd', 0, 0L, v)")
        appendLine("private fun _kneJvmBoxF(env: CPointer<JNIEnvVar>?, v: Float): jobject? = _kneJvmBoxSlot(env, \"java/lang/Float\", \"(F)Ljava/lang/Float;\", 'd', 0, 0L, v.toDouble())")
        appendLine()
        appendLine("private fun _kneJvmMapPut(env: CPointer<JNIEnvVar>?, map: jobject?, k: jobject?, v: jobject?) {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    memScoped {")
        appendLine("        val mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, map), \"put\".cstr.ptr, \"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;\".cstr.ptr)")
        appendLine("        val args = allocArray<jvalue>(2)")
        appendLine("        args[0].l = k")
        appendLine("        args[1].l = v")
        appendLine("        fns.CallObjectMethodA!!.invoke(env, map, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()

        val mapTypes = collectMapTypes(module)
        for (m in mapTypes) {
            val kid = payloadId(m.keyType)
            val vid = payloadId(m.valueType)
            appendLine("private fun _kneNewJvmMap_${kid}_$vid(env: CPointer<JNIEnvVar>?, source: Map<${m.keyType.jvmTypeName}, ${m.valueType.jvmTypeName}>): jobject? {")
            appendLine("    val out = _kneNewJvmHashMap(env) ?: return null")
            appendLine("    for ((k, v) in source) {")
            appendLine("        _kneJvmMapPut(env, out, ${mapBoxExpr(m.keyType, "k")}, ${mapBoxExpr(m.valueType, "v")})")
            appendLine("    }")
            appendLine("    return out")
            appendLine("}")
            appendLine()
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
    private fun mapBoxExpr(type: KneType, expr: String): String = when (type) {
        KneType.INT -> "_kneJvmBoxI(env, \"java/lang/Integer\", \"(I)Ljava/lang/Integer;\", $expr)"
        KneType.LONG -> "_kneJvmBoxL(env, \"java/lang/Long\", \"(J)Ljava/lang/Long;\", $expr)"
        KneType.DOUBLE -> "_kneJvmBoxD(env, $expr)"
        KneType.FLOAT -> "_kneJvmBoxF(env, $expr)"
        KneType.SHORT -> "_kneJvmBoxI(env, \"java/lang/Short\", \"(S)Ljava/lang/Short;\", $expr.toInt())"
        KneType.BYTE -> "_kneJvmBoxI(env, \"java/lang/Byte\", \"(B)Ljava/lang/Byte;\", $expr.toInt())"
        KneType.BOOLEAN -> "_kneJvmBoxI(env, \"java/lang/Boolean\", \"(Z)Ljava/lang/Boolean;\", if ($expr) 1 else 0)"
        KneType.STRING -> "_kneKToJ(env, $expr)"
        is KneType.ENUM -> "_kneNewJvmEnum${type.simpleName}(env, $expr.ordinal)"
        is KneType.OBJECT ->
            "_kneNewJvmProxy${type.simpleName}(env, StableRef.create($expr).asCPointer().toLong())"
        is KneType.INTERFACE ->
            "_kneNewJvmProxy${type.simpleName}(env, StableRef.create($expr).asCPointer().toLong())"
        is KneType.DATA_CLASS -> "_kneNewJvm${type.simpleName}(env, $expr)"
        else -> "null"
    }

    // ── extra helpers referenced above ───────────────────────────────────────

    private fun StringBuilder.appendExtraArrayHelpers() {
        appendLine("// ── java.util.List construction / reading (DC collection fields) ───")
        appendLine("@kotlin.native.concurrent.ThreadLocal")
        appendLine("private object _kneListCache { var arrayCtor: jmethodID? = null; var addMid: jmethodID? = null; var sizeMid: jmethodID? = null; var getMid: jmethodID? = null }")
        appendLine()
        appendLine("private fun _kneNewJvmArrayList(env: CPointer<JNIEnvVar>?, capacity: Int): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = _kneClass(\"java/util/ArrayList\") ?: return null")
        appendLine("    var mid = _kneListCache.arrayCtor")
        appendLine("    if (mid == null) { mid = memScoped { fns.GetMethodID!!.invoke(env, clazz, \"<init>\".cstr.ptr, \"(I)V\".cstr.ptr) }; _kneListCache.arrayCtor = mid }")
        appendLine("    memScoped {")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].i = capacity")
        appendLine("        return fns.NewObjectA!!.invoke(env, clazz, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneJvmListAdd(env: CPointer<JNIEnvVar>?, list: jobject?, v: jobject?) {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    memScoped {")
        appendLine("        var mid = _kneListCache.addMid")
        appendLine("        if (mid == null) { mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, list), \"add\".cstr.ptr, \"(Ljava/lang/Object;)Z\".cstr.ptr); _kneListCache.addMid = mid }")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].l = v")
        appendLine("        fns.CallBooleanMethodA!!.invoke(env, list, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneJvmListSize(env: CPointer<JNIEnvVar>?, list: jobject?): Int {")
        appendLine("    if (list == null) return 0")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    memScoped {")
        appendLine("        var mid = _kneListCache.sizeMid")
        appendLine("        if (mid == null) { mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, list), \"size\".cstr.ptr, \"()I\".cstr.ptr); _kneListCache.sizeMid = mid }")
        appendLine("        return fns.CallIntMethodA!!.invoke(env, list, mid, null)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneJvmListGet(env: CPointer<JNIEnvVar>?, list: jobject?, i: Int): jobject? {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    memScoped {")
        appendLine("        var mid = _kneListCache.getMid")
        appendLine("        if (mid == null) { mid = fns.GetMethodID!!.invoke(env, _kneFns(env).GetObjectClass!!.invoke(env, list), \"get\".cstr.ptr, \"(I)Ljava/lang/Object;\".cstr.ptr); _kneListCache.getMid = mid }")
        appendLine("        val args = allocArray<jvalue>(1)")
        appendLine("        args[0].i = i")
        appendLine("        return fns.CallObjectMethodA!!.invoke(env, list, mid, args)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneJvmUnboxInt(env: CPointer<JNIEnvVar>?, boxed: jobject?): Int =")
        appendLine("    if (boxed == null) 0 else _kneFns(env).CallIntMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(\"java/lang/Integer\"), \"intValue\", \"()I\"), null)")
        appendLine()
        appendLine("private fun _kneJvmUnboxLong(env: CPointer<JNIEnvVar>?, boxed: jobject?): Long =")
        appendLine("    if (boxed == null) 0L else _kneFns(env).CallLongMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(\"java/lang/Long\"), \"longValue\", \"()J\"), null)")
        appendLine()
        appendLine("private fun _kneJvmUnboxDouble(env: CPointer<JNIEnvVar>?, boxed: jobject?): Double =")
        appendLine("    if (boxed == null) 0.0 else _kneFns(env).CallDoubleMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(\"java/lang/Double\"), \"doubleValue\", \"()D\"), null)")
        appendLine()
        appendLine("private fun _kneJvmUnboxFloat(env: CPointer<JNIEnvVar>?, boxed: jobject?): Float =")
        appendLine("    if (boxed == null) 0f else _kneFns(env).CallFloatMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(\"java/lang/Float\"), \"floatValue\", \"()F\"), null)")
        appendLine()
        appendLine("private fun _kneJvmUnboxBoolean(env: CPointer<JNIEnvVar>?, boxed: jobject?): Boolean =")
        appendLine("    if (boxed == null) false else _kneFns(env).CallBooleanMethodA!!.invoke(env, boxed, _kneMethodId(env, _kneClass(\"java/lang/Boolean\"), \"booleanValue\", \"()Z\"), null) != 0.toUByte()")
        appendLine()
        appendLine("/** Constructs a java.util.List<Int> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfInt(env: CPointer<JNIEnvVar>?, values: List<Int>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneJvmBoxI(env, \"java/lang/Integer\", \"(I)Ljava/lang/Integer;\", v))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Constructs a java.util.List<String> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfString(env: CPointer<JNIEnvVar>?, values: List<String>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneKToJ(env, v))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Constructs a java.util.List<Boolean> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfBoolean(env: CPointer<JNIEnvVar>?, values: List<Boolean>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneJvmBoxI(env, \"java/lang/Boolean\", \"(Z)Ljava/lang/Boolean;\", if (v) 1 else 0))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Constructs a java.util.List<Long> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfLong(env: CPointer<JNIEnvVar>?, values: List<Long>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneJvmBoxL(env, \"java/lang/Long\", \"(J)Ljava/lang/Long;\", v))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Constructs a java.util.List<Double> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfDouble(env: CPointer<JNIEnvVar>?, values: List<Double>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneJvmBoxD(env, v))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Constructs a java.util.List<Float> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfFloat(env: CPointer<JNIEnvVar>?, values: List<Float>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneJvmBoxF(env, v))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Constructs a java.util.List<Short> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfShort(env: CPointer<JNIEnvVar>?, values: List<Short>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneJvmBoxI(env, \"java/lang/Short\", \"(S)Ljava/lang/Short;\", v.toInt()))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Constructs a java.util.List<Byte> from Kotlin values. */")
        appendLine("private fun _kneNewJvmListOfByte(env: CPointer<JNIEnvVar>?, values: List<Byte>): jobject? {")
        appendLine("    val out = _kneNewJvmArrayList(env, values.size) ?: return null")
        appendLine("    for (v in values) _kneJvmListAdd(env, out, _kneJvmBoxI(env, \"java/lang/Byte\", \"(B)Ljava/lang/Byte;\", v.toInt()))")
        appendLine("    return out")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<Short> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfShort(env: CPointer<JNIEnvVar>?, list: jobject?): List<Short> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJvmUnboxInt(env, _kneJvmListGet(env, list, it)).toShort() }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<Byte> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfByte(env: CPointer<JNIEnvVar>?, list: jobject?): List<Byte> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJvmUnboxInt(env, _kneJvmListGet(env, list, it)).toByte() }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<Float> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfFloat(env: CPointer<JNIEnvVar>?, list: jobject?): List<Float> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJvmUnboxFloat(env, _kneJvmListGet(env, list, it)) }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<Long> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfLong(env: CPointer<JNIEnvVar>?, list: jobject?): List<Long> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJvmUnboxLong(env, _kneJvmListGet(env, list, it)) }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<Double> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfDouble(env: CPointer<JNIEnvVar>?, list: jobject?): List<Double> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJvmUnboxDouble(env, _kneJvmListGet(env, list, it)) }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<Int> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfInt(env: CPointer<JNIEnvVar>?, list: jobject?): List<Int> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJvmUnboxInt(env, _kneJvmListGet(env, list, it)) }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<String> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfString(env: CPointer<JNIEnvVar>?, list: jobject?): List<String> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJToK(env, _kneJvmListGet(env, list, it)) ?: \"\" }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a java.util.List<Boolean> into a Kotlin list. */")
        appendLine("private fun _kneReadJvmListOfBoolean(env: CPointer<JNIEnvVar>?, list: jobject?): List<Boolean> {")
        appendLine("    val n = _kneJvmListSize(env, list)")
        appendLine("    return List(n) { _kneJvmUnboxBoolean(env, _kneJvmListGet(env, list, it)) }")
        appendLine("}")
        appendLine()

        appendLine("private fun _kneNewJStrArr(env: CPointer<JNIEnvVar>?, values: List<String>): jobject? =")
        appendLine("    _kneNewJObjArr(env, values.map { _kneKToJ(env, it) })")
        appendLine()
        appendLine("private fun _kneNewJBytesArr(env: CPointer<JNIEnvVar>?, values: List<ByteArray>): jobject? =")
        appendLine("    _kneNewJObjArr(env, values.map { _kneNewJBytes(env, it) })")
        appendLine()
        appendLine("private fun _kneNewJObjArr(env: CPointer<JNIEnvVar>?, elements: List<jobject?>): jobject? {")
        appendLine("    val arr = _kneNewObjArr(env, elements.size, _kneClass(\"java/lang/Object\")) ?: return null")
        appendLine("    elements.forEachIndexed { i, e -> _kneObjArrSet(env, arr, i, e) }")
        appendLine("    return arr")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneReadJStrList(env: CPointer<JNIEnvVar>?, arr: jobject?): List<String>? {")
        appendLine("    if (arr == null) return null")
        appendLine("    val n = _kneObjArrLen(env, arr)")
        appendLine("    return List(n) { _kneJToK(env, _kneObjArrGet(env, arr, it)) ?: \"\" }")
        appendLine("}")
        appendLine()
        appendLine("private fun _kneReadJBytesList(env: CPointer<JNIEnvVar>?, arr: jobject?): List<ByteArray>? {")
        appendLine("    if (arr == null) return null")
        appendLine("    val n = _kneObjArrLen(env, arr)")
        appendLine("    return List(n) { _kneReadJBytes(env, _kneObjArrGet(env, arr, it)) ?: ByteArray(0) }")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a jobjectArray of nested collection payloads into a Kotlin nested list. */")
        appendLine("private fun _kneReadJNestedList(env: CPointer<JNIEnvVar>?, arr: jobject?, elemPayloadId: String): List<Any?> {")
        appendLine("    if (arr == null) return emptyList()")
        appendLine("    val n = _kneObjArrLen(env, arr)")
        appendLine("    return List(n) { i -> _kneDecodeNestedElem(env, _kneObjArrGet(env, arr, i), elemPayloadId) }")
        appendLine("}")
        appendLine()
        appendLine("/** Decodes one nested-collection element by payload id (L/S/M prefixes + element kind). */")
        appendLine("private fun _kneDecodeNestedElem(env: CPointer<JNIEnvVar>?, elem: jobject?, id: String): Any? {")
        appendLine("    if (elem == null) return null")
        appendLine("    return when {")
        appendLine("        id.startsWith(\"L\") -> {")
        appendLine("            val inner = id.substring(1)")
        appendLine("            val list = _kneDecodeFlatArray(env, elem, inner) ?: emptyList<Any?>()")
        appendLine("            list")
        appendLine("        }")
        appendLine("        id.startsWith(\"S\") -> {")
        appendLine("            val inner = id.substring(1)")
        appendLine("            (_kneDecodeFlatArray(env, elem, inner) ?: emptyList<Any?>()).toSet()")
        appendLine("        }")
        appendLine("        id.startsWith(\"M\") -> emptyMap<Any, Any>() // nested maps in collection params are not supported")
        appendLine("        else -> _kneDecodeFlatArray(env, elem, id)")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("/** Decodes a flat JNI array element (jarray) into a Kotlin List by payload id. */")
        appendLine("private fun _kneDecodeFlatArray(env: CPointer<JNIEnvVar>?, elem: jobject?, id: String): List<Any?>? = when (id) {")
        appendLine("    \"Int\" -> _kneReadJIntArray(env, elem)?.toList()")
        appendLine("    \"Long\" -> _kneReadJLongArray(env, elem)?.toList()")
        appendLine("    \"Double\" -> _kneReadJDoubleArray(env, elem)?.toList()")
        appendLine("    \"Float\" -> _kneReadJFloatArray(env, elem)?.toList()")
        appendLine("    \"Short\" -> _kneReadJShortArray(env, elem)?.toList()")
        appendLine("    \"Byte\" -> _kneReadJBytes(env, elem)?.toList()")
        appendLine("    \"Boolean\" -> _kneReadJBooleanArray(env, elem)?.toList()")
        appendLine("    \"String\" -> _kneReadJStrList(env, elem)")
        appendLine("    \"Bytes\" -> _kneReadJBytesList(env, elem)")
        appendLine("    else -> null")
        appendLine("}")
        appendLine()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // JVM-object → native DC readers (DC-returning callbacks)
    // ══════════════════════════════════════════════════════════════════════════

    private fun StringBuilder.appendJvmDcReader(dc: KneType.DATA_CLASS) {
        val n = dc.simpleName
        val fq = dc.fqName
        appendLine("/** Reads fields of a JVM $n object into a native instance (DC-returning callbacks). */")
        appendLine("private fun _kneReadJvm$n(env: CPointer<JNIEnvVar>?, obj: jobject?): $fq {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = fns.GetObjectClass!!.invoke(env, obj)")
        appendLine("    return $fq(")
        dc.fields.forEachIndexed { i, f ->
            val comma = if (i < dc.fields.size - 1) "," else ""
            appendLine("        ${f.name} = ${dcFieldReadExpr(f.type, f.name, "clazz")}$comma")
        }
        appendLine("    )")
        appendLine("}")
        appendLine()
    }

    /** JNI field descriptor char(s) for a DC field. */
    private fun dcFieldSig(type: KneType): String = when (type) {
        KneType.INT -> "I"; KneType.LONG -> "J"; KneType.DOUBLE -> "D"; KneType.FLOAT -> "F"
        KneType.BOOLEAN -> "Z"; KneType.BYTE -> "B"; KneType.SHORT -> "S"
        KneType.STRING -> "Ljava/lang/String;"
        KneType.BYTE_ARRAY -> "[B"
        is KneType.ENUM -> "L${type.jvmClassPath()};"
        is KneType.OBJECT -> "L${type.jvmClassPath()};"
        is KneType.INTERFACE -> "L${type.jvmClassPath()};"
        is KneType.DATA_CLASS -> "L${type.jvmClassPath()};"
        is KneType.LIST, is KneType.SET -> "[Ljava/lang/Object;"
        is KneType.MAP -> "Ljava/util/Map;"
        else -> "Ljava/lang/Object;"
    }

    /** Expression reading one DC field from a JVM object via cached field IDs. */
    private fun dcFieldReadExpr(type: KneType, fieldName: String, clazzVar: String): String = when (type) {
        KneType.INT -> "fns.GetIntField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"I\"))"
        KneType.LONG -> "fns.GetLongField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"J\"))"
        KneType.DOUBLE -> "fns.GetDoubleField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"D\"))"
        KneType.FLOAT -> "fns.GetFloatField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"F\"))"
        KneType.BOOLEAN -> "fns.GetBooleanField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"Z\")) != 0.toUByte()"
        KneType.BYTE -> "fns.GetByteField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"B\"))"
        KneType.SHORT -> "fns.GetShortField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"S\"))"
        KneType.STRING -> "_kneJToK(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"Ljava/lang/String;\"))) ?: \"\""
        KneType.BYTE_ARRAY -> "_kneReadJBytes(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"[B\"))) ?: ByteArray(0)"
        is KneType.ENUM -> "${type.fqName}.entries[fns.CallIntMethodA!!.invoke(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"L${type.jvmClassPath()};\")), _kneMethodId(env, _kneClass(\"${type.jvmClassPath()}\"), \"ordinal\", \"()I\"), null)]"
        is KneType.OBJECT -> "_kneReadJvmHandle(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"L${type.jvmClassPath()};\"))).toCPointer<COpaque>()!!.asStableRef<${type.fqName}>().get()"
        is KneType.INTERFACE -> "_kneReadJvmHandle(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"L${type.jvmClassPath()};\"))).toCPointer<COpaque>()!!.asStableRef<${type.fqName}>().get()"
        is KneType.DATA_CLASS -> "_kneReadJvm${type.simpleName}(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"L${type.jvmClassPath()};\")))"
        is KneType.LIST -> "_kneReadJvmListOf${jvmListElemId(type.elementType)}(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"Ljava/util/List;\")))"
        is KneType.SET -> "_kneReadJvmListOf${jvmListElemId(type.elementType)}(env, fns.GetObjectField!!.invoke(env, obj, _kneFieldId(env, $clazzVar, \"$fieldName\", \"Ljava/util/List;\")))"
        is KneType.MAP -> "emptyMap<${(type as KneType.MAP).keyType.jvmTypeName}, ${(type as KneType.MAP).valueType.jvmTypeName}>()"
        else -> "TODO(\"unsupported DC field type in callback return\")"
    }

    /** Emits common helpers used by DC readers and OBJECT-returning callbacks. */
    private fun StringBuilder.appendReadHelpers() {
        appendLine("/** Reads the native handle stored in a JVM proxy object's `handle: Long` field. */")
        appendLine("private fun _kneReadJvmHandle(env: CPointer<JNIEnvVar>?, obj: jobject?): Long {")
        appendLine("    val fns = _kneFns(env)")
        appendLine("    val clazz = fns.GetObjectClass!!.invoke(env, obj)")
        appendLine("    val fid = _kneFieldId(env, clazz, \"handle\", \"J\") ?: return 0L")
        appendLine("    return fns.GetLongField!!.invoke(env, obj, fid)")
        appendLine("}")
        appendLine()
        appendLine("/** Reads a jobjectArray into a plain Kotlin List of opaque elements. */")
        appendLine("private fun _kneReadJListAny(env: CPointer<JNIEnvVar>?, arr: jobject?): List<Any> {")
        appendLine("    if (arr == null) return emptyList()")
        appendLine("    val n = _kneObjArrLen(env, arr)")
        appendLine("    return List(n) { _kneObjArrGet(env, arr, it) as Any }")
        appendLine("}")
        appendLine()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bootstrap
    // ══════════════════════════════════════════════════════════════════════════

    /** Native implementations for KneNativeBindings externals (dispose/cancel/credit). */
    private fun StringBuilder.appendCommonNatives(module: KneModule) {
        val hostClass = jvmClassPath("KneNativeBindings")
        val hasSuspend = module.classes.any { cls -> cls.methods.any { it.isSuspend } || cls.companionMethods.any { it.isSuspend } } ||
            module.functions.any { it.isSuspend }
        val hasFlow = module.classes.any { cls -> cls.methods.any { it.returnType is KneType.FLOW } } ||
            module.functions.any { it.returnType is KneType.FLOW }

        appendLine("private fun kne_disposeRef(env: CPointer<JNIEnvVar>?, thiz: jobject?, handle: Long) {")
        appendLine("    if (handle == 0L) return")
        appendLine("    try { handle.toCPointer<COpaque>()?.asStableRef<Any>()?.dispose() } catch (_: Throwable) {}")
        appendLine("}")
        appendLine()
        registerMethod(hostClass, "_kneDisposeRef", "(J)V", "kne_disposeRef")

        if (hasSuspend || hasFlow) {
            appendLine("private fun kne_cancelJob(env: CPointer<JNIEnvVar>?, thiz: jobject?, jobHandle: Long) {")
            appendLine("    if (jobHandle == 0L) return")
            if (hasFlow) {
                appendLine("    when (val target = jobHandle.toCPointer<COpaque>()!!.asStableRef<Any>().get()) {")
                appendLine("        is Job -> target.cancel()")
                appendLine("        is KneFlowJob -> target.job.cancel()")
                appendLine("    }")
            } else {
                appendLine("    jobHandle.toCPointer<COpaque>()!!.asStableRef<Job>().get().cancel()")
            }
            appendLine("}")
            appendLine()
            registerMethod(hostClass, "_kneCancelJob", "(J)V", "kne_cancelJob")
        }

        if (hasFlow) {
            appendLine("private fun kne_flowCredit(env: CPointer<JNIEnvVar>?, thiz: jobject?, jobHandle: Long) {")
            appendLine("    if (jobHandle == 0L) return")
            appendLine("    jobHandle.toCPointer<COpaque>()!!.asStableRef<KneFlowJob>().get().credits.trySend(Unit)")
            appendLine("}")
            appendLine()
            registerMethod(hostClass, "_kneFlowCredit", "(J)V", "kne_flowCredit")
        }
    }

    private fun StringBuilder.appendBootstrap(module: KneModule) {
        val bootstrapSymbol = mangleBootstrapSymbol(jvmPackage, "KneRuntime", "initNatives")

        appendLine("// ═══ JNI bootstrap ═════════════════════════════════════════════════")
        appendLine("/** Resolved by the JVM via dlsym when KneRuntime.initNatives() runs. Registers all natives. */")
        appendLine("@CName(\"$bootstrapSymbol\")")
        appendLine("public fun kne_initNatives(env: CPointer<JNIEnvVar>?, thiz: jobject?) {")
        appendLine("    try {")
        appendLine("        println(\"kne: bootstrap starting\")")
        appendLine("        val fns = _kneFns(env)")
        appendLine("        memScoped {")
        appendLine("        val vmStorage = alloc<CPointerVar<JavaVMVar>>()")
        appendLine("        val rc = fns.GetJavaVM!!.invoke(env, vmStorage.ptr)")
        appendLine("        check(rc == 0) { \"GetJavaVM failed: \$rc\" }")
        appendLine("        _kneVM = vmStorage.value")
        appendLine("        // global-ref caches: exception class, boxing classes, registered classes")
        appendLine("        _kneExceptionClass = _kneGlobal(env, fns.FindClass!!.invoke(env, \"${jvmClassPath("KotlinNativeException")}\".cstr.ptr))")
        appendLine("        _kneClassCache[\"java/lang/Object\"] = _kneGlobal(env, memScoped { fns.FindClass!!.invoke(env, \"java/lang/Object\".cstr.ptr) })")
        appendLine("        _kneClassCache[\"java/util/HashMap\"] = _kneGlobal(env, memScoped { fns.FindClass!!.invoke(env, \"java/util/HashMap\".cstr.ptr) })")
        appendLine("        for (fqn in listOf(\"java/lang/Integer\", \"java/lang/Long\", \"java/lang/Double\", \"java/lang/Float\", \"java/lang/Short\", \"java/lang/Byte\", \"java/lang/Boolean\")) {")
        appendLine("            _kneClassCache[fqn] = _kneGlobal(env, memScoped { fns.FindClass!!.invoke(env, fqn.cstr.ptr) })")
        appendLine("        }")
        // Collections are materialised for every List/Set/Map payload and DC field, so their
        // classes must be in the cache before the first call (helpers look them up here).
        appendLine("        for (fqn in listOf(\"java/util/ArrayList\", \"java/util/List\", \"java/util/Set\", \"java/util/Map\")) {")
        appendLine("            _kneClassCache[fqn] = _kneGlobal(env, memScoped { fns.FindClass!!.invoke(env, fqn.cstr.ptr) })")
        appendLine("        }")
        for (reg in registrations.values) {
            appendLine("        run {")
            appendLine("            val clazz = fns.FindClass!!.invoke(env, \"${reg.className}\".cstr.ptr)")
            appendLine("            check(clazz != null) { \"FindClass failed: ${reg.className}\" }")
            appendLine("            _kneClassCache[\"${reg.className}\"] = _kneGlobal(env, clazz)")
            appendLine("            val methods = allocArray<JNINativeMethod>(${reg.methods.size})")
            reg.methods.forEachIndexed { i, m ->
                appendLine("            methods[$i].name = \"${m.jvmName}\".cstr.ptr")
                appendLine("            methods[$i].signature = \"${m.sig}\".cstr.ptr")
                appendLine("            methods[$i].fnPtr = staticCFunction(::${m.fnName})")
            }
            appendLine("            val regRc = fns.RegisterNatives!!.invoke(env, clazz, methods, ${reg.methods.size})")
            appendLine("            check(regRc == 0) { \"RegisterNatives failed for ${reg.className}: \$regRc\" }")
            appendLine("        }")
        }
        // Classes with no natives to bind: the native side only needs a global ref for
        // NewObjectA / GetMethodID later on (data classes, enums, sealed bases).
        for (className in cachedClasses - registrations.keys) {
            appendLine("        run {")
            appendLine("            val clazz = fns.FindClass!!.invoke(env, \"$className\".cstr.ptr)")
            appendLine("            check(clazz != null) { \"FindClass failed: $className\" }")
            appendLine("            _kneClassCache[\"$className\"] = _kneGlobal(env, clazz)")
            appendLine("        }")
        }
        appendLine("        }") // memScoped
        appendLine("        println(\"kne: bootstrap done\")")
        appendLine("    } catch (e: Throwable) {")
        appendLine("        // Rethrow: swallowing this leaves every native unregistered and the JVM side")
        appendLine("        // fails later with an opaque NoSuchMethodError far from the real cause.")
        appendLine("        println(\"kne: BOOTSTRAP FAILED: \${e::class.simpleName}: \${e.message}\")")
        appendLine("        throw e")
        appendLine("    }")
        appendLine("}")
        appendLine()
    }

    private fun mangleBootstrapSymbol(pkg: String, className: String, method: String): String {
        fun esc(s: String) = s.replace("_", "_1")
        val pkgPart = pkg.split('.').filter { it.isNotEmpty() }.joinToString("_") { esc(it) }
        return "Java_${pkgPart}_${esc(className)}_${esc(method)}"
    }
}

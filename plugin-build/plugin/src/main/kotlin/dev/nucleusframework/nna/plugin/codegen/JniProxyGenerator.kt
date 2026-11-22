package dev.nucleusframework.nna.plugin.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.joinToCode
import dev.nucleusframework.nna.plugin.codegen.poet.KnePoetTypes
import dev.nucleusframework.nna.plugin.ir.KneClass
import dev.nucleusframework.nna.plugin.ir.KneDataClass
import dev.nucleusframework.nna.plugin.ir.KneEnum
import dev.nucleusframework.nna.plugin.ir.KneFunction
import dev.nucleusframework.nna.plugin.ir.KneInterface
import dev.nucleusframework.nna.plugin.ir.KneModule
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneProperty
import dev.nucleusframework.nna.plugin.ir.KneSourceDecl
import dev.nucleusframework.nna.plugin.ir.KneType

/**
 * Generates JVM-side proxy classes for the JNI bridge backend, structured through KotlinPoet.
 *
 * The public API surface is identical to the pre-KotlinPoet string assembly (same class shapes,
 * same `AutoCloseable`/`Cleaner`/`Lease` lifecycle, same `KotlinNativeException`), but calls
 * are dispatched through `external fun` (JNI native methods registered by the native
 * bootstrap via `RegisterNatives`) instead of FFM `MethodHandle` downcalls.
 *
 * No `java.lang.foreign` API is used anywhere, so the generated code runs on JDK 8+
 * and Android JVMs.
 *
 * KotlinPoet notes the emitters rely on:
 *  - A [PropertySpec] whose initializer is exactly a primary-constructor parameter name is
 *    emitted as a constructor property (`internal val handle: Long`), which is how ctor
 *    properties are produced (KotlinPoet forbids modifiers on parameters directly).
 *  - KotlinPoet manages imports from `%T`/`%M` references; coroutine imports appear only when
 *    suspend/flow members are generated.
 *  - KotlinPoet cannot express arbitrary declaration text, so verbatim [KneSourceDecl] value
 *    types are passed through as-is (they are copied source, not assembled code).
 */
class JniProxyGenerator {

    fun generate(module: KneModule, jvmPackage: String): Map<String, String> {
        val poet = KnePoetTypes(jvmPackage)
        val files = mutableMapOf<String, String>()

        val moduleSuspend = module.classes.any { cls -> cls.methods.any { it.isSuspend } || cls.companionMethods.any { it.isSuspend } } ||
            module.functions.any { it.isSuspend }
        val moduleFlow = module.classes.any { cls -> cls.methods.any { it.returnType is KneType.FLOW } } ||
            module.functions.any { it.returnType is KneType.FLOW }
        val callbackSignatures = collectCallbackSignatures(module)
        val functionReturnTypes = collectFunctionReturnTypes(module)

        files["KneRuntime.kt"] = buildRuntimeFileSpec(
            module, jvmPackage, callbackSignatures, functionReturnTypes, moduleSuspend, moduleFlow, poet,
        )
        files["KotlinNativeException.kt"] = buildExceptionFileSpec(jvmPackage).toString()

        module.dataClasses.filter { !it.isCommon }.forEach { dc ->
            files["${dc.simpleName}.kt"] = buildDataClassFileSpec(dc, module, jvmPackage, poet)
        }

        module.enums.forEach { enum ->
            files["${enum.simpleName}.kt"] = buildEnumFileSpec(enum, module, jvmPackage, poet)
        }

        module.classes.filter { !it.isCommon }.forEach { cls ->
            files["${cls.simpleName}.kt"] = buildClassProxyFileSpec(cls, module, jvmPackage, poet).toString()
        }

        module.interfaces.filter { !it.isCommon }.forEach { iface ->
            files["${iface.simpleName}.kt"] = buildInterfaceFileSpec(iface, poet).toString()
        }

        val regularFunctions = module.functions.filter { !it.isExtension }
        if (regularFunctions.isNotEmpty()) {
            val objectName = module.libName.replaceFirstChar { it.uppercaseChar() }
            files["$objectName.kt"] = buildFunctionObjectFileSpec(regularFunctions, objectName, jvmPackage, poet).toString()
        }

        val extensionFunctions = module.functions.filter { it.isExtension && it.receiverType != null }
        if (extensionFunctions.isNotEmpty()) {
            files["Extensions.kt"] = buildExtensionsFileSpec(extensionFunctions, jvmPackage, poet).toString()
        }

        return files
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KneRuntime
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildRuntimeFileSpec(
        module: KneModule,
        pkg: String,
        callbackSignatures: Set<KneType.FUNCTION>,
        functionReturnTypes: List<KneType.FUNCTION>,
        hasSuspend: Boolean,
        hasFlow: Boolean,
        poet: KnePoetTypes,
    ): String {
        val runtime = TypeSpec.objectBuilder("KneRuntime")
            .addModifiers(KModifier.INTERNAL)
            .addKdoc(
                "Shared JNI runtime: loads the native shared library, triggers JNI native-method\n" +
                    "registration (RegisterNatives bootstrap) and manages object lifecycle.\n\n" +
                    "No java.lang.foreign APIs are used; runs on JDK 8+ and Android JVMs.\n",
            )
            .addProperty(
                PropertySpec.builder("LIB_NAME", KnePoetTypes.STRING, KModifier.PRIVATE, KModifier.CONST)
                    .initializer(CodeBlock.of("%S", module.libName))
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("nativesRegistered", KnePoetTypes.BOOLEAN, KModifier.PRIVATE)
                    .mutable(true)
                    .addAnnotation(JVM_VOLATILE)
                    .initializer("false")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("registrationLock", KnePoetTypes.ANY, KModifier.PRIVATE)
                    .initializer("%T()", KnePoetTypes.ANY)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("ensureNativesRegistered")
                    .addKdoc("Idempotent: loads the library and registers every native method exactly once.\n")
                    .returns(KnePoetTypes.BOOLEAN)
                    .addCode(ENSURE_NATIVES_BLOCK)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("initNatives")
                    .addKdoc("Bound during bootstrap: the JVM resolves it by symbol name via dlsym.\n")
                    .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("loadLibrary")
                    .addModifiers(KModifier.PRIVATE)
                    .addCode(loadLibraryBody())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("detectPlatform")
                    .addModifiers(KModifier.PRIVATE)
                    .returns(KnePoetTypes.STRING)
                    .addCode(detectPlatformBody())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("resolveCacheDir")
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("platform", KnePoetTypes.STRING)
                    .returns(PATH)
                    .addCode(resolveCacheDirBody())
                    .build(),
            )
            .addFunction(
                FunSpec.builder("disposer")
                    .addKdoc("Disposal action for a proxy; captures only the native handle so the proxy stays collectable.\n")
                    .addParameter("handle", KnePoetTypes.LONG)
                    .returns(LAMBDA_VOID)
                    .addCode("return { runCatching { KneNativeBindings._kneDisposeRef(handle) } }\n")
                    .build(),
            )
            .addType(buildLease())
            .addFunction(
                FunSpec.builder("leased")
                    .addKdoc("Keeps an object passed as an argument alive (not disposable) for the duration of a call.\n")
                    .addModifiers(KModifier.INLINE)
                    .addTypeVariable(T)
                    .addParameter("lease", poet.runtimeLease().copy(nullable = true))
                    .addParameter("block", lambdaOf(emptyList(), T))
                    .returns(T)
                    .addCode(LEASED_BLOCK)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("checkString")
                    .addKdoc("Rejects strings with embedded NUL characters (they would be truncated in transit).\n")
                    .addParameter("value", KnePoetTypes.STRING)
                    .returns(KnePoetTypes.STRING)
                    .addCode(
                        "require(value.indexOf('\\u0000') < 0) { %S }\n" +
                            "return value\n",
                        "Strings passed to native code cannot contain NUL characters",
                    )
                    .build(),
            )

        if (callbackSignatures.isNotEmpty()) {
            for (sig in callbackSignatures) {
                runtime.addType(buildCallbackDispatcher(sig, poet))
            }
        }

        if (hasSuspend) {
            suspendReturnTypes(module).forEach { t -> runtime.addType(buildSuspendDispatcher(module, t, poet)) }
        }

        if (hasFlow) {
            flowElemTypes(module).forEach { t -> runtime.addType(buildFlowDispatcher(module, t, poet)) }
        }

        val bindings = buildNativeBindings(functionReturnTypes, hasSuspend, hasFlow, poet)
        return FileSpec.builder(pkg, "KneRuntime")
            .addType(runtime.build())
            .addType(bindings)
            .build()
            .toString()
    }

    private fun buildNativeBindings(
        functionReturnTypes: List<KneType.FUNCTION>,
        hasSuspend: Boolean,
        hasFlow: Boolean,
        poet: KnePoetTypes,
    ): TypeSpec {
        val bindings = TypeSpec.objectBuilder("KneNativeBindings")
            .addKdoc("Hosts the common JNI natives (dispose, suspend cancel, flow credits).\n")
            .addProperty(
                PropertySpec.builder("_kneReg", KnePoetTypes.BOOLEAN, KModifier.PRIVATE)
                    .initializer("KneRuntime.ensureNativesRegistered()")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("_kneDisposeRef")
                    .addAnnotation(JVM_STATIC)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameter("handle", KnePoetTypes.LONG)
                    .build(),
            )
        if (hasSuspend || hasFlow) {
            bindings.addFunction(
                FunSpec.builder("_kneCancelJob")
                    .addAnnotation(JVM_STATIC)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameter("jobHandle", KnePoetTypes.LONG)
                    .build(),
            )
        }
        if (hasFlow) {
            bindings.addFunction(
                FunSpec.builder("_kneFlowCredit")
                    .addAnnotation(JVM_STATIC)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameter("jobHandle", KnePoetTypes.LONG)
                    .build(),
            )
        }
        // Invoke bridges for lambdas that native methods return as opaque handles.
        functionReturnTypes.forEach { fnType ->
            val id = JniTypes.functionId(fnType)
            val params = externalParamSpecs(
                fnType.paramTypes.mapIndexed { i, t -> KneParam("p$i", t) },
                leadingHandle = true,
                poet,
            )
            bindings.addFunction(
                FunSpec.builder("_kneInvokeFn$id")
                    .addAnnotation(JVM_STATIC)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameters(params)
                    .returns(extReturnType(fnType.returnType, poet))
                    .build(),
            )
        }
        return bindings.build()
    }

    private fun buildLease(): TypeSpec {
        val lease = TypeSpec.classBuilder("Lease")
            .addKdoc(
                "Guards a native object: counts in-flight calls and defers disposal until the last one ends,\n" +
                    "so close() is safe from any thread, including from inside a callback on the same object.\n",
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("dispose", LAMBDA_VOID)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("dispose", LAMBDA_VOID, KModifier.PRIVATE)
                    .initializer("dispose")
                    .build(),
            )
            // Low bits: in-flight calls; CLOSED bit: close requested.
            .addProperty(
                PropertySpec.builder("state", ATOMIC_INTEGER, KModifier.PRIVATE)
                    .initializer("%T(0)", ATOMIC_INTEGER)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("isClosed", KnePoetTypes.BOOLEAN)
                    .getter(FunSpec.getterBuilder().addCode("return state.get() and CLOSED != 0\n").build())
                    .build(),
            )
            .addFunction(FunSpec.builder("acquire").addCode(LEASE_ACQUIRE_BLOCK).build())
            .addFunction(FunSpec.builder("release").addCode("if (state.decrementAndGet() == CLOSED) dispose()\n").build())
            .addFunction(FunSpec.builder("close").addCode(LEASE_CLOSE_BLOCK).build())
            .addFunction(
                FunSpec.builder("use")
                    .addModifiers(KModifier.INLINE)
                    .addTypeVariable(T)
                    .addParameter("block", lambdaOf(emptyList(), T))
                    .returns(T)
                    .addCode(LEASE_USE_BLOCK)
                    .build(),
            )
        lease.addType(
            TypeSpec.companionObjectBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addProperty(
                    PropertySpec.builder("CLOSED", KnePoetTypes.INT, KModifier.CONST)
                        .initializer("1 shl 30")
                        .build(),
                )
                .build(),
        )
        return lease.build()
    }

    // ── Callback dispatchers ─────────────────────────────────────────────────

    /** Builds `KneFn<id>`: adapts flat JNI payloads into the user lambda's Kotlin signature. */
    private fun buildCallbackDispatcher(sig: KneType.FUNCTION, poet: KnePoetTypes): TypeSpec {
        val id = callbackId(sig)
        val args = sig.paramTypes.mapIndexed { i, t -> cbAdaptedArg(t, "p$i", poet) }.joinToCode(", ")
        val invoke = FunSpec.builder("invoke")
        sig.paramTypes.forEachIndexed { i, t ->
            val (type, name) = cbAdaptedParam(t, "p$i", poet)
            invoke.addParameter(name, type)
        }
        when (val rt = sig.returnType) {
            is KneType.UNIT -> invoke.addCode("fn(%L)\n", args)
            is KneType.ENUM -> {
                invoke.returns(KnePoetTypes.INT)
                invoke.addCode("return fn(%L).ordinal\n", args)
            }
            is KneType.OBJECT, is KneType.INTERFACE, is KneType.DATA_CLASS -> {
                invoke.returns(KnePoetTypes.ANY)
                invoke.addCode("return fn(%L) as Any\n", args)
            }
            is KneType.LIST, is KneType.SET -> {
                val elem = if (rt is KneType.LIST) rt.elementType else (rt as KneType.SET).elementType
                invoke.returns(KnePoetTypes.ANY)
                invoke.addCode("val _r = fn(%L)\nreturn %L\n", args, cbCollReturnPack(elem, CodeBlock.of("_r"), poet))
            }
            is KneType.MAP -> {
                invoke.returns(KnePoetTypes.ANY)
                invoke.addCode(
                    "val _r = fn(%L)\n" +
                        "return arrayOf<Any?>(\n" +
                        "%L!!,\n" +
                        "%L!!,\n" +
                        ")\n",
                    args,
                    cbMapPackExpr(rt.keyType, CodeBlock.of("_r.keys"), poet),
                    cbMapPackExpr(rt.valueType, CodeBlock.of("_r.values"), poet),
                )
            }
            // Primitives / String / ByteArray: the native side looks the dispatcher up by the
            // *unboxed* signature (e.g. (Ljava/lang/String;)Ljava/lang/String;), so `invoke` must
            // declare the real type — returning `Any` would compile to Ljava/lang/Object; and the
            // GetMethodID lookup would miss.
            is KneType.STRING -> {
                invoke.returns(KnePoetTypes.STRING)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.BYTE_ARRAY -> {
                invoke.returns(KnePoetTypes.BYTE_ARRAY)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.BOOLEAN -> {
                invoke.returns(KnePoetTypes.BOOLEAN)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.INT -> {
                invoke.returns(KnePoetTypes.INT)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.LONG -> {
                invoke.returns(KnePoetTypes.LONG)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.DOUBLE -> {
                invoke.returns(KnePoetTypes.DOUBLE)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.FLOAT -> {
                invoke.returns(KnePoetTypes.FLOAT)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.SHORT -> {
                invoke.returns(KnePoetTypes.SHORT)
                invoke.addCode("return fn(%L)\n", args)
            }
            is KneType.BYTE -> {
                invoke.returns(KnePoetTypes.BYTE)
                invoke.addCode("return fn(%L)\n", args)
            }
            else -> {
                invoke.returns(KnePoetTypes.ANY)
                invoke.addCode("return fn(%L) as Any\n", args)
            }
        }
        return TypeSpec.classBuilder("KneFn$id")
            .addModifiers(KModifier.INTERNAL)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("fn", poet.publicType(sig))
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("fn", poet.publicType(sig))
                    .addAnnotation(JVM_FIELD)
                    .initializer("fn")
                    .build(),
            )
            .addFunction(invoke.build())
            .build()
    }

    /** Packs a returned List/Set into the flat array the native side reads back. */
    private fun cbCollReturnPack(elemType: KneType, expr: CodeBlock, poet: KnePoetTypes): CodeBlock = when (elemType) {
        is KneType.INT -> CodeBlock.of("%L.toIntArray()", expr)
        is KneType.LONG -> CodeBlock.of("%L.toLongArray()", expr)
        is KneType.DOUBLE -> CodeBlock.of("%L.toDoubleArray()", expr)
        is KneType.FLOAT -> CodeBlock.of("%L.toFloatArray()", expr)
        is KneType.SHORT -> CodeBlock.of("%L.toShortArray()", expr)
        is KneType.BYTE -> CodeBlock.of("%L.toByteArray()", expr)
        is KneType.BOOLEAN -> CodeBlock.of("%L.toBooleanArray()", expr)
        is KneType.ENUM -> CodeBlock.of("IntArray(%L.size) { %L[it].ordinal }", expr, expr)
        is KneType.STRING -> CodeBlock.of(
            "run { val _a = arrayOfNulls<%T>(%L.size); %L.forEachIndexed { i, s -> _a[i] = s }; _a }",
            KnePoetTypes.STRING, expr, expr,
        )
        is KneType.OBJECT -> CodeBlock.of("LongArray(%L.size) { (%L[it] as %T).handle }", expr, expr, poet.jvmClass(elemType.simpleName))
        is KneType.INTERFACE -> CodeBlock.of("LongArray(%L.size) { (%L[it] as %T).handle }", expr, expr, poet.jvmClass(elemType.simpleName))
        is KneType.BYTE_ARRAY -> CodeBlock.of(
            "run { val _a = arrayOfNulls<%T>(%L.size); %L.forEachIndexed { i, b -> _a[i] = b }; _a }",
            KnePoetTypes.BYTE_ARRAY, expr, expr,
        )
        else -> CodeBlock.of(
            "run { val _a = arrayOfNulls<Any>(%L.size); %L.forEachIndexed { i, v -> _a[i] = v }; _a }",
            expr, expr,
        )
    }

    /** (declared JVM type, param name) the dispatcher's invoke method exposes to native. */
    private fun cbAdaptedParam(t: KneType, name: String, poet: KnePoetTypes): Pair<TypeName, String> = when (t) {
        is KneType.INT, is KneType.LONG, is KneType.DOUBLE, is KneType.FLOAT,
        is KneType.BYTE, is KneType.SHORT -> poet.publicType(t) to name
        is KneType.BOOLEAN -> KnePoetTypes.BOOLEAN to name
        is KneType.STRING -> KnePoetTypes.STRING to name
        is KneType.ENUM -> KnePoetTypes.INT to name // ordinal
        is KneType.BYTE_ARRAY -> KnePoetTypes.BYTE_ARRAY to name
        is KneType.OBJECT -> poet.jvmClass(t.simpleName) to name // proxy object
        is KneType.INTERFACE -> poet.jvmClass(t.simpleName) to name
        is KneType.DATA_CLASS -> poet.jvmClass(t.simpleName) to name // JVM object (constructed natively)
        is KneType.LIST -> listAdaptedParam(t.elementType) to name
        is KneType.SET -> listAdaptedParam(t.elementType) to name
        is KneType.MAP -> poet.publicType(t) to name
        else -> KnePoetTypes.ANY.copy(nullable = true) to name
    }

    private fun listAdaptedParam(elemType: KneType): TypeName = when (elemType) {
        is KneType.INT -> KnePoetTypes.INT_ARRAY
        is KneType.LONG -> KnePoetTypes.LONG_ARRAY
        is KneType.DOUBLE -> KnePoetTypes.DOUBLE_ARRAY
        is KneType.FLOAT -> KnePoetTypes.FLOAT_ARRAY
        is KneType.SHORT -> KnePoetTypes.SHORT_ARRAY
        is KneType.BYTE -> KnePoetTypes.BYTE_ARRAY
        is KneType.BOOLEAN -> KnePoetTypes.BOOLEAN_ARRAY
        is KneType.ENUM -> KnePoetTypes.INT_ARRAY
        is KneType.STRING -> KnePoetTypes.ARRAY.parameterizedBy(KnePoetTypes.STRING.copy(nullable = true))
        is KneType.OBJECT, is KneType.INTERFACE -> KnePoetTypes.LONG_ARRAY
        is KneType.BYTE_ARRAY -> KnePoetTypes.ARRAY.parameterizedBy(KnePoetTypes.BYTE_ARRAY.copy(nullable = true))
        else -> KnePoetTypes.ARRAY.parameterizedBy(KnePoetTypes.ANY.copy(nullable = true))
    }

    /** Expression converting the adapted param into the user-lambda argument. */
    private fun cbAdaptedArg(t: KneType, name: String, poet: KnePoetTypes): CodeBlock = when (t) {
        is KneType.ENUM -> CodeBlock.of("%T.entries[%L]", poet.jvmClass(t.simpleName), name)
        is KneType.LIST -> cbListAdaptedArg(t.elementType, name, poet)
        is KneType.SET -> CodeBlock.of("%L.toSet()", cbListAdaptedArg(t.elementType, name, poet))
        is KneType.MAP -> CodeBlock.of("(%L as %T)", name, poet.publicType(t))
        else -> CodeBlock.of("%L", name)
    }

    private fun cbListAdaptedArg(elemType: KneType, name: String, poet: KnePoetTypes): CodeBlock = when (elemType) {
        is KneType.INT, is KneType.LONG, is KneType.DOUBLE, is KneType.FLOAT,
        is KneType.SHORT, is KneType.BYTE, is KneType.BOOLEAN -> CodeBlock.of("%L.toList()", name)
        is KneType.ENUM -> CodeBlock.of("%L.toList().map { %T.entries[it] }", name, poet.jvmClass(elemType.simpleName))
        is KneType.STRING -> CodeBlock.of("%L.map { it ?: \"\" }", name)
        is KneType.OBJECT -> CodeBlock.of("%L.map { %T.fromNativeHandle(it) }", name, poet.jvmClass(elemType.simpleName))
        is KneType.INTERFACE -> CodeBlock.of("%L.map { %T.fromNativeHandle(it) }", name, poet.jvmClass(elemType.simpleName))
        is KneType.BYTE_ARRAY -> CodeBlock.of("%L.map { it ?: ByteArray(0) }", name)
        is KneType.DATA_CLASS -> CodeBlock.of("%L.map { it as %T }", name, poet.jvmClass(elemType.simpleName))
        else -> CodeBlock.of("%L.toList()", name)
    }

    /** Packing expression for a Map half when a callback returns a Map. */
    private fun cbMapPackExpr(t: KneType, expr: CodeBlock, poet: KnePoetTypes): CodeBlock = when (t) {
        is KneType.INT -> CodeBlock.of("IntArray(%L.size) { (%L.toList()[it] as Int) }", expr, expr)
        is KneType.LONG -> CodeBlock.of("LongArray(%L.size) { (%L.toList()[it] as Long) }", expr, expr)
        is KneType.DOUBLE -> CodeBlock.of("DoubleArray(%L.size) { (%L.toList()[it] as Double) }", expr, expr)
        is KneType.FLOAT -> CodeBlock.of("FloatArray(%L.size) { (%L.toList()[it] as Float) }", expr, expr)
        is KneType.SHORT -> CodeBlock.of("ShortArray(%L.size) { (%L.toList()[it] as Short) }", expr, expr)
        is KneType.BYTE -> CodeBlock.of("ByteArray(%L.size) { (%L.toList()[it] as Byte) }", expr, expr)
        is KneType.BOOLEAN -> CodeBlock.of("BooleanArray(%L.size) { (%L.toList()[it] as Boolean) }", expr, expr)
        is KneType.ENUM -> CodeBlock.of("IntArray(%L.size) { (%L.toList()[it] as %T).ordinal }", expr, expr, poet.jvmClass(t.simpleName))
        is KneType.STRING -> CodeBlock.of(
            "run { val _a = arrayOfNulls<%T>(%L.size); %L.toList().forEachIndexed { i, v -> _a[i] = v }; _a }",
            KnePoetTypes.STRING, expr, expr,
        )
        is KneType.OBJECT -> CodeBlock.of("LongArray(%L.size) { (%L.toList()[it] as %T).handle }", expr, expr, poet.jvmClass(t.simpleName))
        is KneType.INTERFACE -> CodeBlock.of("LongArray(%L.size) { (%L.toList()[it] as %T).handle }", expr, expr, poet.jvmClass(t.simpleName))
        else -> CodeBlock.of(
            "run { val _a = arrayOfNulls<Any>(%L.size); %L.toList().forEachIndexed { i, v -> _a[i] = v }; _a }",
            expr, expr,
        )
    }

    // ── Suspend dispatchers ──────────────────────────────────────────────────

    /** Builds `KneSuspend<Id>`: JNI completion object resuming the continuation. */
    private fun buildSuspendDispatcher(module: KneModule, type: KneType, poet: KnePoetTypes): TypeSpec {
        val id = payloadId(type)
        val disp = TypeSpec.classBuilder("KneSuspend$id")
            .addModifiers(KModifier.INTERNAL)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("cont", CANCELLABLE_CONTINUATION.parameterizedBy(poet.publicType(type))).build(),
                    )
                    .addParameter(ParameterSpec.builder("lease", poet.runtimeLease()).build())
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("cont", CANCELLABLE_CONTINUATION.parameterizedBy(poet.publicType(type)), KModifier.PRIVATE)
                    .initializer("cont")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("lease", poet.runtimeLease(), KModifier.PRIVATE)
                    .initializer("lease")
                    .build(),
            )
        // Unit suspend functions have no payload: native calls `onSuccess()V` (see
        // _kneSuspendOk_Void), so the JVM overload must take no parameter. Declaring
        // `onSuccess(value: Unit)` would compile to `(Lkotlin/Unit;)V` and GetMethodID fails.
        val onSuccess = FunSpec.builder("onSuccess")
        if (type == KneType.UNIT) {
            onSuccess.addCode(
                "try {\n" +
                    "cont.%M(Unit) {}\n" +
                    "} catch (_: IllegalStateException) {\n" +
                    "// continuation already cancelled from the JVM side\n" +
                    "} finally {\n" +
                    "lease.release()\n" +
                    "}\n",
                M_RESUME,
            )
        } else {
            onSuccess.addParameter("value", suspendParamType(type, poet))
            onSuccess.addCode(
                "try {\n" +
                    "cont.%M(%L) {}\n" +
                    "} catch (_: IllegalStateException) {\n" +
                    "// continuation already cancelled from the JVM side\n" +
                    "} finally {\n" +
                    "lease.release()\n" +
                    "}\n",
                M_RESUME,
                cbResumeExpr(module, type, CodeBlock.of("value"), poet),
            )
        }
        disp.addFunction(onSuccess.build())
        disp.addFunction(
            FunSpec.builder("onError")
                .addParameter("message", KnePoetTypes.STRING)
                .addCode(
                    "try {\n" +
                        "cont.%M(KotlinNativeException(message))\n" +
                        "} catch (_: IllegalStateException) {\n" +
                        "} finally {\n" +
                        "lease.release()\n" +
                        "}\n",
                    M_RESUME_WITH_EXCEPTION,
                )
                .build(),
        )
        disp.addFunction(
            FunSpec.builder("onCancelled")
                .addCode(
                    "try {\n" +
                        "cont.cancel(%T(%S))\n" +
                        "} catch (_: IllegalStateException) {\n" +
                        "} finally {\n" +
                        "lease.release()\n" +
                        "}\n",
                    CANCELLATION_EXCEPTION, "cancelled natively",
                )
                .build(),
        )
        return disp.build()
    }

    /** Param type of the dispatcher's onSuccess/onNext for a native payload mapping. */
    private fun suspendParamType(type: KneType, poet: KnePoetTypes): TypeName = when {
        type is KneType.NULLABLE -> when (val inner = type.inner) {
            is KneType.STRING -> KnePoetTypes.STRING.copy(nullable = true)
            is KneType.BYTE_ARRAY -> KnePoetTypes.BYTE_ARRAY.copy(nullable = true)
            is KneType.BOOLEAN -> KnePoetTypes.INT
            is KneType.INT, is KneType.LONG, is KneType.FLOAT, is KneType.DOUBLE -> KnePoetTypes.LONG
            is KneType.SHORT, is KneType.BYTE -> KnePoetTypes.INT
            is KneType.ENUM -> KnePoetTypes.INT
            is KneType.OBJECT, is KneType.INTERFACE -> KnePoetTypes.LONG
            is KneType.DATA_CLASS -> poet.jvmClass(inner.simpleName).copy(nullable = true)
            is KneType.LIST -> jArrayParamType(inner.elementType).copy(nullable = true)
            is KneType.SET -> jArrayParamType(inner.elementType).copy(nullable = true)
            is KneType.MAP -> poet.publicType(inner).copy(nullable = true)
            else -> KnePoetTypes.ANY.copy(nullable = true)
        }
        else -> when (type) {
            is KneType.INT -> KnePoetTypes.INT
            is KneType.LONG -> KnePoetTypes.LONG
            is KneType.DOUBLE -> KnePoetTypes.DOUBLE
            is KneType.FLOAT -> KnePoetTypes.FLOAT
            is KneType.BOOLEAN -> KnePoetTypes.BOOLEAN
            is KneType.BYTE -> KnePoetTypes.BYTE
            is KneType.SHORT -> KnePoetTypes.SHORT
            is KneType.STRING -> KnePoetTypes.STRING
            is KneType.BYTE_ARRAY -> KnePoetTypes.BYTE_ARRAY
            is KneType.ENUM -> KnePoetTypes.INT
            is KneType.OBJECT, is KneType.INTERFACE -> KnePoetTypes.LONG
            is KneType.DATA_CLASS -> poet.jvmClass(type.simpleName)
            is KneType.LIST -> jArrayParamType(type.elementType)
            is KneType.SET -> jArrayParamType(type.elementType)
            is KneType.MAP -> poet.publicType(type)
            is KneType.UNIT -> KnePoetTypes.UNIT
            else -> KnePoetTypes.ANY.copy(nullable = true)
        }
    }

    private fun jArrayParamType(elemType: KneType): TypeName = when (elemType) {
        is KneType.INT -> KnePoetTypes.INT_ARRAY
        is KneType.LONG -> KnePoetTypes.LONG_ARRAY
        is KneType.DOUBLE -> KnePoetTypes.DOUBLE_ARRAY
        is KneType.FLOAT -> KnePoetTypes.FLOAT_ARRAY
        is KneType.SHORT -> KnePoetTypes.SHORT_ARRAY
        is KneType.BYTE -> KnePoetTypes.BYTE_ARRAY
        is KneType.BOOLEAN -> KnePoetTypes.BOOLEAN_ARRAY
        is KneType.ENUM -> KnePoetTypes.INT_ARRAY
        is KneType.STRING -> KnePoetTypes.ARRAY.parameterizedBy(KnePoetTypes.STRING.copy(nullable = true))
        is KneType.OBJECT, is KneType.INTERFACE -> KnePoetTypes.LONG_ARRAY
        is KneType.BYTE_ARRAY -> KnePoetTypes.ARRAY.parameterizedBy(KnePoetTypes.BYTE_ARRAY.copy(nullable = true))
        else -> KnePoetTypes.ARRAY.parameterizedBy(KnePoetTypes.ANY.copy(nullable = true))
    }

    /** Expression converting the dispatcher payload into the public suspend/flow value. */
    private fun cbResumeExpr(module: KneModule, type: KneType, value: CodeBlock, poet: KnePoetTypes): CodeBlock {
        if (type is KneType.NULLABLE) {
            return when (val inner = type.inner) {
                is KneType.STRING, is KneType.BYTE_ARRAY, is KneType.DATA_CLASS, is KneType.MAP -> value
                is KneType.BOOLEAN -> CodeBlock.of("if (%L < 0) null else %L != 0", value, value)
                is KneType.INT -> CodeBlock.of("if (%L != Long.MIN_VALUE) %L.toInt() else null", value, value)
                is KneType.LONG -> CodeBlock.of("if (%L != Long.MIN_VALUE) %L else null", value, value)
                is KneType.SHORT -> CodeBlock.of("if (%L != Int.MIN_VALUE) %L.toShort() else null", value, value)
                is KneType.BYTE -> CodeBlock.of("if (%L != Int.MIN_VALUE) %L.toByte() else null", value, value)
                is KneType.FLOAT -> CodeBlock.of("if (%L != Long.MIN_VALUE) Float.fromBits(%L.toInt()) else null", value, value)
                is KneType.DOUBLE -> CodeBlock.of("if (%L != Long.MIN_VALUE) Double.fromBits(%L) else null", value, value)
                is KneType.ENUM -> CodeBlock.of("if (%L >= 0) %T.entries[%L] else null", value, poet.jvmClass(inner.simpleName), value)
                is KneType.OBJECT -> CodeBlock.of("if (%L != 0L) %T.fromNativeHandle(%L) else null", value, poet.jvmClass(inner.simpleName), value)
                is KneType.INTERFACE -> CodeBlock.of("if (%L != 0L) %T.fromNativeHandle(%L) else null", value, poet.jvmClass(inner.simpleName), value)
                is KneType.LIST -> CodeBlock.of("if (%L == null) null else %L", value, cbCollResumeExpr(inner.elementType, value, "List", poet))
                is KneType.SET -> CodeBlock.of("if (%L == null) null else %L", value, cbCollResumeExpr(inner.elementType, value, "Set", poet))
                else -> value
            }
        }
        return when (type) {
            is KneType.ENUM -> CodeBlock.of("%T.entries[%L]", poet.jvmClass(type.simpleName), value)
            is KneType.OBJECT -> CodeBlock.of("%T.fromNativeHandle(%L)", poet.jvmClass(type.simpleName), value)
            is KneType.INTERFACE -> CodeBlock.of("%T.fromNativeHandle(%L)", poet.jvmClass(type.simpleName), value)
            is KneType.LIST -> cbCollResumeExpr(type.elementType, value, "List", poet)
            is KneType.SET -> cbCollResumeExpr(type.elementType, value, "Set", poet)
            else -> value
        }
    }

    private fun cbCollResumeExpr(elemType: KneType, value: CodeBlock, collKind: String, poet: KnePoetTypes): CodeBlock {
        val list = when (elemType) {
            is KneType.INT, is KneType.LONG, is KneType.DOUBLE, is KneType.FLOAT,
            is KneType.SHORT, is KneType.BYTE, is KneType.BOOLEAN -> CodeBlock.of("%L.toList()", value)
            is KneType.ENUM -> CodeBlock.of("%L.toList().map { %T.entries[it] }", value, poet.jvmClass(elemType.simpleName))
            is KneType.STRING -> CodeBlock.of("%L.map { it ?: \"\" }", value)
            is KneType.OBJECT -> CodeBlock.of("%L.map { %T.fromNativeHandle(it) }", value, poet.jvmClass(elemType.simpleName))
            is KneType.INTERFACE -> CodeBlock.of("%L.map { %T.fromNativeHandle(it) }", value, poet.jvmClass(elemType.simpleName))
            is KneType.BYTE_ARRAY -> CodeBlock.of("%L.map { it ?: ByteArray(0) }", value)
            is KneType.DATA_CLASS -> CodeBlock.of("%L.map { it as %T }", value, poet.jvmClass(elemType.simpleName))
            // Nested collections arrive as an Object[] of jarray elements (List<List<Int>> →
            // Object[] of int[]), so each element must be unpacked, never cast to java.util.List.
            is KneType.LIST -> CodeBlock.of("%L.map { %L }", value, cbNestedCollUnwrap(elemType.elementType, CodeBlock.of("it"), poet))
            is KneType.SET -> CodeBlock.of("%L.map { %L }", value, cbNestedCollUnwrap(elemType.elementType, CodeBlock.of("it"), poet))
            else -> CodeBlock.of("%L.toList()", value)
        }
        return if (collKind == "Set") CodeBlock.of("%L.toSet()", list) else list
    }

    // ── Flow dispatchers ─────────────────────────────────────────────────────

    /** Builds `KneFlow<Id>`: JNI callback object feeding a callbackFlow channel. */
    private fun buildFlowDispatcher(module: KneModule, elemType: KneType, poet: KnePoetTypes): TypeSpec {
        val id = payloadId(elemType)
        val disp = TypeSpec.classBuilder("KneFlow$id")
            .addModifiers(KModifier.INTERNAL)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("channel", SEND_CHANNEL.parameterizedBy(poet.publicType(elemType))).build(),
                    )
                    .addParameter(
                        ParameterSpec.builder("lease", poet.runtimeLease().copy(nullable = true))
                            .defaultValue("null")
                            .build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("channel", SEND_CHANNEL.parameterizedBy(poet.publicType(elemType)), KModifier.PRIVATE)
                    .initializer("channel")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("lease", poet.runtimeLease().copy(nullable = true), KModifier.PRIVATE)
                    .initializer("lease")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("jobId", KnePoetTypes.LONG)
                    .mutable(true)
                    .addAnnotation(JVM_FIELD)
                    .initializer("0L")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("released", ATOMIC_BOOLEAN, KModifier.PRIVATE)
                    .initializer("%T(false)", ATOMIC_BOOLEAN)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("release")
                    .addKdoc("Releases the owner's lease exactly once (native signals exactly one terminal event).\n")
                    .addCode("if (released.compareAndSet(false, true)) lease?.release()\n")
                    .build(),
            )
        // Flow<Unit> elements carry no payload: native calls `onNext()V`, so the JVM overload must
        // be parameterless (a `value: Unit` param would compile to `(Lkotlin/Unit;)V`).
        val onNext = FunSpec.builder("onNext")
        if (elemType == KneType.UNIT) {
            onNext.addCode(
                "try {\n" +
                    "%M { channel.send(Unit) }\n" +
                    "KneNativeBindings._kneFlowCredit(jobId)\n" +
                    "} catch (_: Exception) {\n" +
                    "// channel closed: collection was cancelled\n" +
                    "}\n",
                M_RUN_BLOCKING,
            )
        } else {
            onNext.addParameter("value", suspendParamType(elemType, poet))
            onNext.addCode(
                "try {\n" +
                    "%M { channel.send(%L) }\n" +
                    "KneNativeBindings._kneFlowCredit(jobId)\n" +
                    "} catch (_: Exception) {\n" +
                    "// channel closed: collection was cancelled\n" +
                    "}\n",
                M_RUN_BLOCKING,
                cbResumeExpr(module, elemType, CodeBlock.of("value"), poet),
            )
        }
        disp.addFunction(onNext.build())
        disp.addFunction(
            FunSpec.builder("onError")
                .addParameter("message", KnePoetTypes.STRING)
                .addCode("channel.close(KotlinNativeException(message)); release()\n")
                .build(),
        )
        disp.addFunction(FunSpec.builder("onComplete").addCode("channel.close(); release()\n").build())
        disp.addFunction(FunSpec.builder("onCancelled").addCode("channel.close(); release()\n").build())
        return disp.build()
    }

    /** Payload id shared with the native generator's naming. */
    private fun payloadId(type: KneType): String = when {
        type is KneType.NULLABLE -> "N" + payloadId(type.inner)
        else -> when (type) {
            is KneType.INT -> "Int"
            is KneType.LONG -> "Long"
            is KneType.DOUBLE -> "Double"
            is KneType.FLOAT -> "Float"
            is KneType.BOOLEAN -> "Boolean"
            is KneType.BYTE -> "Byte"
            is KneType.SHORT -> "Short"
            is KneType.STRING -> "String"
            is KneType.BYTE_ARRAY -> "Bytes"
            is KneType.UNIT -> "Void"
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

    // ══════════════════════════════════════════════════════════════════════════
    // Simple files
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildExceptionFileSpec(pkg: String): FileSpec =
        FileSpec.builder(pkg, "KotlinNativeException")
            .addType(
                TypeSpec.classBuilder("KotlinNativeException")
                    .addKdoc(
                        "Exception thrown when a Kotlin/Native function throws across the JNI boundary.\n" +
                            "The message contains the original Kotlin exception's message.\n",
                    )
                    .primaryConstructor(
                        FunSpec.constructorBuilder().addParameter("message", KnePoetTypes.STRING).build(),
                    )
                    .superclass(RUNTIME_EXCEPTION)
                    .addSuperclassConstructorParameter("message")
                    .build(),
            )
            .build()

    private fun buildDataClassFileSpec(dc: KneDataClass, module: KneModule, pkg: String, poet: KnePoetTypes): String {
        val source = dc.source
        if (source != null) return buildSourceDeclText(source, module, pkg)
        val ctor = FunSpec.constructorBuilder()
        dc.fields.forEach { f -> ctor.addParameter(ParameterSpec.builder(f.name, poet.publicType(f.type)).build()) }
        val type = TypeSpec.classBuilder(dc.simpleName)
            .addModifiers(KModifier.DATA)
            .primaryConstructor(ctor.build())
        dc.fields.forEach { f ->
            // Initializer equal to the ctor parameter name -> KotlinPoet emits a ctor property.
            type.addProperty(PropertySpec.builder(f.name, poet.publicType(f.type)).initializer(f.name).build())
        }
        return FileSpec.builder(pkg, dc.simpleName).addType(type.build()).build().toString()
    }

    private fun buildEnumFileSpec(enum: KneEnum, module: KneModule, pkg: String, poet: KnePoetTypes): String {
        val source = enum.source
        if (source != null) return buildSourceDeclText(source, module, pkg)
        val type = TypeSpec.enumBuilder(enum.simpleName)
        if (enum.constructorParams.isNotEmpty()) {
            val ctor = FunSpec.constructorBuilder()
            enum.constructorParams.forEach { p ->
                ctor.addParameter(ParameterSpec.builder(p.name, poet.publicType(p.type)).build())
            }
            type.primaryConstructor(ctor.build())
            enum.constructorParams.forEach { p ->
                type.addProperty(PropertySpec.builder(p.name, poet.publicType(p.type)).initializer(p.name).build())
            }
        }
        enum.entries.forEach { entry -> type.addEnumConstant(entry.name) }
        return FileSpec.builder(pkg, enum.simpleName).addType(type.build()).build().toString()
    }

    private fun buildInterfaceFileSpec(iface: KneInterface, poet: KnePoetTypes): FileSpec {
        val ifacePkg = iface.fqName.substringBeforeLast('.')
        val type = TypeSpec.interfaceBuilder(iface.simpleName)
            .addKdoc("JVM interface proxy for Kotlin/Native interface [${iface.simpleName}].\n")
            .addProperty(PropertySpec.builder("handle", KnePoetTypes.LONG).build())
        iface.methods.forEach { method ->
            val fn = FunSpec.builder(method.name)
            // Abstract must be explicit: KotlinPoet renders interface funs without it as
            // concrete members with an empty body (invalid for non-Unit return types).
            fn.addModifiers(KModifier.ABSTRACT)
            if (method.isSuspend) fn.addModifiers(KModifier.SUSPEND)
            method.params.forEach { p -> fn.addParameter(ParameterSpec.builder(p.name, poet.publicType(p.type)).build()) }
            if (method.returnType != KneType.UNIT) fn.returns(poet.publicType(method.returnType))
            type.addFunction(fn.build())
        }
        iface.properties.forEach { prop ->
            type.addProperty(
                PropertySpec.builder(prop.name, poet.publicType(prop.type))
                    .mutable(prop.mutable)
                    .build(),
            )
        }
        return FileSpec.builder(ifacePkg, iface.simpleName).addType(type.build()).build()
    }

    /** Source-decl emission: verbatim copy with native-only import filtering (same as pre-KotlinPoet). */
    private fun buildSourceDeclText(source: KneSourceDecl, module: KneModule, pkg: String): String = buildString {
        appendLine("// Auto-generated by kotlin-native-export plugin. Do not modify.")
        appendLine("package $pkg")
        appendLine()
        val nativeOnly = listOf("kotlinx.cinterop", "platform", "kotlin.native")
        val localPackages = module.packages + pkg
        val imports = source.imports.filter { directive ->
            val fq = directive.removePrefix("import").substringBefore(" as ").trim()
            val importPkg = if (fq.endsWith(".*")) fq.removeSuffix(".*") else fq.substringBeforeLast('.', "")
            importPkg !in localPackages && nativeOnly.none { fq == it || fq.startsWith("$it.") }
        }
        if (imports.isNotEmpty()) {
            imports.forEach { appendLine(it) }
            appendLine()
        }
        appendLine(source.text)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Top-level function object + Extensions
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildFunctionObjectFileSpec(
        fns: List<KneFunction>,
        objectName: String,
        pkg: String,
        poet: KnePoetTypes,
    ): FileSpec {
        val obj = TypeSpec.objectBuilder(objectName)
            .addProperty(
                PropertySpec.builder("_kneReg", KnePoetTypes.BOOLEAN, KModifier.PRIVATE)
                    .initializer("KneRuntime.ensureNativesRegistered()")
                    .build(),
            )
        val unsupported = fns.filter { it.isSuspend || it.returnType is KneType.FLOW }
        if (unsupported.isNotEmpty()) {
            obj.addKdoc(
                unsupported.joinToString("\n") { fn ->
                    if (fn.isSuspend) "The JNI backend does not support suspend top-level functions yet: ${fn.name}"
                    else "The JNI backend does not support Flow top-level functions yet: ${fn.name}"
                } + "\n",
            )
        }
        fns.forEach { fn ->
            if (fn.isSuspend || fn.returnType is KneType.FLOW) return@forEach
            obj.addFunction(
                FunSpec.builder(extName(fn.name))
                    .addAnnotation(JVM_STATIC)
                    .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
                    .addParameters(externalParamSpecs(fn.params, leadingHandle = false, poet))
                    .returns(extReturnType(fn.returnType, poet))
                    .build(),
            )
        }
        fns.forEach { fn ->
            if (fn.isSuspend || fn.returnType is KneType.FLOW) return@forEach
            obj.addFunction(
                FunSpec.builder(fn.name)
                    .addParameters(publicParamSpecs(fn.params, poet))
                    .apply { if (fn.returnType != KneType.UNIT) returns(poet.publicType(fn.returnType)) }
                    .addCode(callableBody(fn, poet, receiverExpr = null, externalRef = extName(fn.name)))
                    .build(),
            )
        }
        return FileSpec.builder(pkg, objectName).addType(obj.build()).build()
    }

    private fun buildExtensionsFileSpec(
        functions: List<KneFunction>,
        pkg: String,
        poet: KnePoetTypes,
    ): FileSpec {
        val ext = TypeSpec.objectBuilder("KneExt")
            .addModifiers(KModifier.INTERNAL)
            .addProperty(
                PropertySpec.builder("_kneReg", KnePoetTypes.BOOLEAN, KModifier.PRIVATE)
                    .initializer("KneRuntime.ensureNativesRegistered()")
                    .build(),
            )
        functions.forEach { fn ->
            if (fn.isSuspend || fn.returnType is KneType.FLOW) return@forEach
            val specs = buildList {
                add(ParameterSpec.builder("receiverHandle", KnePoetTypes.LONG).build())
                addAll(externalParamSpecs(fn.params, leadingHandle = false, poet))
            }
            ext.addFunction(
                FunSpec.builder(extName("Ext", fn.name))
                    .addAnnotation(JVM_STATIC)
                    .addModifiers(KModifier.EXTERNAL)
                    .addParameters(specs)
                    .returns(extReturnType(fn.returnType, poet))
                    .build(),
            )
        }

        val file = FileSpec.builder(pkg, "Extensions").addType(ext.build())
        functions.forEach { fn ->
            val receiverType = fn.receiverType ?: return@forEach
            if (fn.isSuspend || fn.returnType is KneType.FLOW) return@forEach
            val receiverSimple = when (receiverType) {
                is KneType.OBJECT -> receiverType.simpleName
                is KneType.INTERFACE -> receiverType.simpleName
                else -> return@forEach
            }
            file.addFunction(
                FunSpec.builder(fn.name)
                    .receiver(poet.jvmClass(receiverSimple))
                    .addParameters(publicParamSpecs(fn.params, poet))
                    .apply { if (fn.returnType != KneType.UNIT) returns(poet.publicType(fn.returnType)) }
                    .addCode(
                        callableBody(
                            fn, poet,
                            receiverExpr = "receiverHandle = this.handle",
                            externalRef = "KneExt." + extName("Ext", fn.name),
                        ),
                    )
                    .build(),
            )
        }
        return file.build()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Class proxy
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildClassProxyFileSpec(cls: KneClass, module: KneModule, pkg: String, poet: KnePoetTypes): FileSpec {
        val n = cls.simpleName
        val classType = poet.jvmClass(n)
        val isRoot = cls.superClass == null
        val hasSuperClass = cls.superClass != null

        val modifiers = buildList {
            if (cls.isSealed) add(KModifier.SEALED)
            if (cls.isAbstract) add(KModifier.ABSTRACT)
            if (cls.isOpen) add(KModifier.OPEN)
        }

        val isInstantiable = !cls.isAbstract && !cls.isSealed
        val hasHierarchy = hasSuperClass || cls.isOpen || cls.isAbstract || cls.isSealed || cls.interfaces.isNotEmpty()

        val implementsInterface = cls.interfaces.isNotEmpty()

        val type = TypeSpec.classBuilder(n)
            .addKdoc(
                "JVM proxy for Kotlin/Native class [$n].\n" +
                    "Dispatches every call into the native shared library via JNI (external fun).\n" +
                    "Object lifecycle is managed via Java Cleaner (automatic GC) or explicit close().\n",
            )
            .addModifiers(modifiers)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(
                        when {
                            cls.isSealed -> KModifier.PROTECTED
                            hasHierarchy -> KModifier.INTERNAL
                            else -> KModifier.PRIVATE
                        },
                    )
                    .addParameter("handle", KnePoetTypes.LONG)
                    .build(),
            )

        if (hasSuperClass) {
            type.superclass(poet.jvmClass(cls.superClass!!.substringAfterLast(".")))
            type.addSuperclassConstructorParameter("handle")
        } else {
            type.addSuperinterface(AUTO_CLOSEABLE)
        }
        cls.interfaces.forEach { ifaceFq -> type.addSuperinterface(poet.jvmClass(ifaceFq.substringAfterLast("."))) }

        if (isRoot) {
            // Ctor property: the property initializer equal to the ctor parameter name makes
            // KotlinPoet emit `internal/override val handle: Long` inside the constructor.
            val handleModifiers = buildList {
                if (implementsInterface) add(KModifier.OVERRIDE)
                if (!implementsInterface && !hasHierarchy) add(KModifier.INTERNAL)
            }
            type.addProperty(
                PropertySpec.builder("handle", KnePoetTypes.LONG, *handleModifiers.toTypedArray())
                    .initializer("handle")
                    .build(),
            )
            type.addProperty(
                PropertySpec.builder("_lease", poet.runtimeLease(), KModifier.INTERNAL)
                    .initializer("KneRuntime.Lease(KneRuntime.disposer(handle))")
                    .build(),
            )
        }

        type.addType(buildCompanion(cls, isInstantiable, poet))

        // ── instance members ──
        // Track parent method/property names for override detection
        val parentMembers = collectParentMembers(cls, module)
        val canHaveOverrides = cls.isOpen || cls.isAbstract || cls.isSealed || hasSuperClass
        cls.methods.forEach { method ->
            type.addFunction(
                buildInstanceMethod(
                    method, poet,
                    isOverride = method.name in parentMembers,
                    parentIsOpen = canHaveOverrides && method.name !in parentMembers,
                ),
            )
        }
        cls.properties.forEach { prop ->
            type.addProperty(
                buildInstanceProperty(
                    prop, poet,
                    isOverride = prop.name in parentMembers,
                    parentIsOpen = canHaveOverrides && prop.name !in parentMembers,
                ),
            )
        }

        if (isInstantiable && (isRoot || hasSuperClass)) {
            type.addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE)
                    .addCode("_lease.close()\n")
                    .build(),
            )
        }

        return FileSpec.builder(pkg, n).addType(type.build()).build()
    }

    private fun buildCompanion(cls: KneClass, isInstantiable: Boolean, poet: KnePoetTypes): TypeSpec {
        val companion = TypeSpec.companionObjectBuilder()
        if (isInstantiable) {
            companion.addProperty(
                PropertySpec.builder("CLEANER", CLEANER, KModifier.PRIVATE)
                    .initializer("%T.create()", CLEANER)
                    .build(),
            )
        }
        companion.addProperty(
            PropertySpec.builder("_kneReg", KnePoetTypes.BOOLEAN, KModifier.PRIVATE)
                .initializer("KneRuntime.ensureNativesRegistered()")
                .build(),
        )

        if (isInstantiable) {
            val ctorVariants = buildList {
                add(cls.constructor.params)
                val trailingDefaults = cls.constructor.params.reversed().takeWhile { it.hasDefault }.size
                for (drop in 1..trailingDefaults) add(cls.constructor.params.dropLast(drop))
            }
            ctorVariants.forEachIndexed { index, params ->
                val suffix = if (index == 0) "" else index.toString()
                companion.addFunction(
                    FunSpec.builder("_kneNew$suffix")
                        .addAnnotation(JVM_STATIC)
                        .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
                        .addParameters(externalParamSpecs(params, leadingHandle = false, poet))
                        .returns(KnePoetTypes.LONG)
                        .build(),
                )
                companion.addFunction(
                    FunSpec.builder("invoke")
                        .addModifiers(KModifier.OPERATOR)
                        .addParameters(publicParamSpecs(params, poet))
                        .returns(poet.jvmClass(cls.simpleName))
                        .addCode(
                            "val h = _kneNew$suffix(%L)\n" +
                                "return fromNativeHandle(h)\n",
                            ctorCallArgs(params, poet),
                        )
                        .build(),
                )
            }

            // @JvmStatic + public is required by the JNI backend: the native side rebuilds the
            // proxy with CallStaticObjectMethodA(clazz, "fromNativeHandle", "(J)L...;"), which only
            // resolves for a real static method. A plain `internal fun` would compile to an
            // instance method with a mangled name (fromNativeHandle$module) and never be found.
            companion.addFunction(
                FunSpec.builder("fromNativeHandle")
                    .addAnnotation(JVM_STATIC)
                    .addParameter("h", KnePoetTypes.LONG)
                    .returns(poet.jvmClass(cls.simpleName))
                    .addCode(
                        "val obj = %T(h)\n" +
                            "val lease = obj._lease\n" +
                            "CLEANER.register(obj) { lease.close() }\n" +
                            "return obj\n",
                        poet.jvmClass(cls.simpleName),
                    )
                    .build(),
            )
        }

        // externals
        cls.methods.forEach { method ->
            when {
                method.isSuspend -> companion.addFunction(buildSuspendExternalDecl(method, poet))
                method.returnType is KneType.FLOW -> companion.addFunction(buildFlowExternalDecl(method, poet))
                else -> companion.addFunction(
                    buildExternalDecl(method.name, method.params, withHandle = true, returnType = method.returnType, poet),
                )
            }
        }
        cls.properties.forEach { prop ->
            companion.addFunction(buildPropertyGetExternal(prop, poet))
            if (prop.mutable) companion.addFunction(buildPropertySetExternal(prop, poet))
        }
        cls.companionMethods.forEach { method ->
            companion.addFunction(
                buildExternalDecl(method.name, method.params, withHandle = false, returnType = method.returnType, poet, prefix = "Companion"),
            )
        }
        cls.companionProperties.forEach { prop ->
            companion.addFunction(buildCompanionPropertyGetExternal(prop, poet))
            if (prop.mutable) companion.addFunction(buildCompanionPropertySetExternal(prop, poet))
        }

        // companion method/property public bodies (inside companion object)
        cls.companionMethods.forEach { method -> companion.addFunction(buildCompanionMethodBody(method, poet)) }
        cls.companionProperties.forEach { prop -> companion.addProperty(buildCompanionPropertyBody(prop, poet)) }

        return companion.build()
    }

    // ── external declaration emitters ────────────────────────────────────────

    /** Collects method and property names from all ancestor classes for override detection. */
    private fun collectParentMembers(cls: KneClass, module: KneModule): Set<String> {
        val names = mutableSetOf<String>()
        var current = cls.superClass
        while (current != null) {
            val parent = module.classes.find { it.fqName == current }
            if (parent != null) {
                parent.methods.forEach { names.add(it.name) }
                parent.properties.forEach { names.add(it.name) }
                current = parent.superClass
            } else break
        }
        // Also collect interface members
        cls.interfaces.forEach { ifaceFq ->
            val iface = module.interfaces.find { it.fqName == ifaceFq }
            if (iface != null) {
                iface.methods.forEach { names.add(it.name) }
                iface.properties.forEach { names.add(it.name) }
            }
        }
        return names
    }

    private fun extName(name: String): String = "_kne" + name.replaceFirstChar { it.uppercaseChar() }

    /**
     * Builds an external-fun name from a prefix plus a member name, capitalizing the member so the
     * result matches the native side's `"$prefix" + name.replaceFirstChar { uppercase }` rule.
     *
     * `extName("Get$current")` would yield `_kneGetcurrent`; this yields `_kneGetCurrent`.
     */
    private fun extName(prefix: String, member: String): String =
        "_kne" + prefix + member.replaceFirstChar { it.uppercaseChar() }

    /** Declared external params for the JVM side (DCs flatten; maps expand into keys/values). */
    private fun externalParamSpecs(params: List<KneParam>, leadingHandle: Boolean, poet: KnePoetTypes): List<ParameterSpec> {
        val specs = mutableListOf<ParameterSpec>()
        if (leadingHandle) specs.add(ParameterSpec.builder("handle", KnePoetTypes.LONG).build())
        params.forEach { p ->
            val dc = JniTypes.extractDataClass(p.type)
            if (dc != null) {
                if (p.type is KneType.NULLABLE) specs.add(ParameterSpec.builder("${p.name}_isNull", KnePoetTypes.INT).build())
                dcFlatExternalParams(dc, p.name, poet).forEach { (fname, ftype) ->
                    specs.add(ParameterSpec.builder(fname, ftype).build())
                }
            } else {
                poet.expandExternalParams(p.name, p.type).forEach { (ename, etype) ->
                    specs.add(ParameterSpec.builder(ename, etype).build())
                }
            }
        }
        return specs
    }

    /** Flat external params for a DC: map fields expand into keys/values array pairs. */
    private fun dcFlatExternalParams(dc: KneType.DATA_CLASS, name: String, poet: KnePoetTypes): List<Pair<String, TypeName>> =
        JniTypes.flattenDataClassFields(dc, name).flatMap { (fname, ftype) ->
            when {
                ftype is KneType.MAP -> listOf(
                    "${fname}_keys" to poet.arrayParamType(ftype.keyType),
                    "${fname}_values" to poet.arrayParamType(ftype.valueType),
                )
                else -> listOf(fname to dcFieldWireType(ftype, poet))
            }
        }

    /** Wire view of an already-flattened DC field (mirrors the pre-KotlinPoet extParamType). */
    private fun dcFieldWireType(type: KneType, poet: KnePoetTypes): TypeName = when {
        JniTypes.extractDataClass(type) != null -> error("DC fields must be flat here")
        type is KneType.NULLABLE -> when (type.inner) {
            is KneType.STRING -> KnePoetTypes.STRING.copy(nullable = true)
            is KneType.BYTE_ARRAY -> KnePoetTypes.BYTE_ARRAY.copy(nullable = true)
            is KneType.LIST -> poet.arrayParamType(type.inner.elementType).copy(nullable = true)
            is KneType.SET -> poet.arrayParamType(type.inner.elementType).copy(nullable = true)
            is KneType.MAP -> poet.publicType(type.inner).copy(nullable = true)
            is KneType.FUNCTION -> KnePoetTypes.ANY.copy(nullable = true)
            else -> poet.externalParamType(type)
        }
        else -> poet.externalParamType(type)
    }

    /** External fun return type for a KneType. */
    private fun extReturnType(type: KneType, poet: KnePoetTypes): TypeName = when {
        type == KneType.UNIT -> KnePoetTypes.UNIT
        JniTypes.extractDataClass(type) != null -> {
            val dc = JniTypes.extractDataClass(type)!!
            poet.jvmClass(dc.simpleName).copy(nullable = type is KneType.NULLABLE)
        }
        type is KneType.ENUM -> KnePoetTypes.INT
        type is KneType.OBJECT -> KnePoetTypes.LONG
        type is KneType.INTERFACE -> KnePoetTypes.LONG
        type is KneType.NULLABLE -> when (type.inner) {
            is KneType.STRING -> KnePoetTypes.STRING.copy(nullable = true)
            is KneType.BYTE_ARRAY -> KnePoetTypes.BYTE_ARRAY.copy(nullable = true)
            is KneType.LIST -> poet.arrayParamType(type.inner.elementType).copy(nullable = true)
            is KneType.SET -> poet.arrayParamType(type.inner.elementType).copy(nullable = true)
            is KneType.MAP -> poet.publicType(type.inner).copy(nullable = true)
            is KneType.ENUM -> KnePoetTypes.INT
            // Native returns an opaque StableRef handle for a lambda; see fnInvokeWrapExpr.
            is KneType.FUNCTION -> KnePoetTypes.LONG
            else -> poet.externalParamType(type)
        }
        type is KneType.LIST || type is KneType.SET ->
            poet.arrayParamType(
                JniTypes.unwrapNullable(type).let { t ->
                    if (t is KneType.LIST) t.elementType else (t as KneType.SET).elementType
                },
            )
        type is KneType.MAP -> poet.publicType(type)
        type is KneType.FUNCTION -> KnePoetTypes.LONG
        else -> poet.publicType(type)
    }

    private fun buildExternalDecl(
        fnName: String,
        params: List<KneParam>,
        withHandle: Boolean,
        returnType: KneType,
        poet: KnePoetTypes,
        prefix: String = "",
    ): FunSpec =
        FunSpec.builder(extName(prefix, fnName))
            .addAnnotation(JVM_STATIC)
            .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
            .addParameters(externalParamSpecs(params, leadingHandle = withHandle, poet))
            .returns(extReturnType(returnType, poet))
            .build()

    private fun buildSuspendExternalDecl(fn: KneFunction, poet: KnePoetTypes): FunSpec =
        FunSpec.builder(extName(fn.name) + "Async")
            .addAnnotation(JVM_STATIC)
            .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
            .addParameters(externalParamSpecs(fn.params, leadingHandle = true, poet))
            .addParameter(ParameterSpec.builder("completion", KnePoetTypes.ANY.copy(nullable = true)).build())
            .returns(KnePoetTypes.LONG)
            .build()

    private fun buildFlowExternalDecl(fn: KneFunction, poet: KnePoetTypes): FunSpec =
        FunSpec.builder(extName(fn.name) + "Flow")
            .addAnnotation(JVM_STATIC)
            .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
            .addParameters(externalParamSpecs(fn.params, leadingHandle = true, poet))
            .addParameter(ParameterSpec.builder("callback", KnePoetTypes.ANY.copy(nullable = true)).build())
            .returns(KnePoetTypes.LONG)
            .build()

    private fun buildPropertyGetExternal(prop: KneProperty, poet: KnePoetTypes): FunSpec =
        FunSpec.builder(extName("Get", prop.name))
            .addAnnotation(JVM_STATIC)
            .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
            .addParameter("handle", KnePoetTypes.LONG)
            .returns(extReturnType(prop.type, poet))
            .build()

    private fun buildPropertySetExternal(prop: KneProperty, poet: KnePoetTypes): FunSpec =
        FunSpec.builder(extName("Set", prop.name))
            .addAnnotation(JVM_STATIC)
            .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
            .addParameters(externalParamSpecs(listOf(KneParam("value", prop.type)), leadingHandle = true, poet))
            .build()

    private fun buildCompanionPropertyGetExternal(prop: KneProperty, poet: KnePoetTypes): FunSpec =
        FunSpec.builder(extName("CompanionGet", prop.name))
            .addAnnotation(JVM_STATIC)
            .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
            .returns(extReturnType(prop.type, poet))
            .build()

    private fun buildCompanionPropertySetExternal(prop: KneProperty, poet: KnePoetTypes): FunSpec =
        FunSpec.builder(extName("CompanionSet", prop.name))
            .addAnnotation(JVM_STATIC)
            .addModifiers(KModifier.PRIVATE, KModifier.EXTERNAL)
            .addParameters(externalParamSpecs(listOf(KneParam("value", prop.type)), leadingHandle = false, poet))
            .build()

    // ── call-argument packing (JVM value → external params) ──────────────────

    /** Conversion of a plain (non-DC) value at an external call site (multi-arg for maps). */
    private fun callArgExpr(name: String, type: KneType, poet: KnePoetTypes): List<CodeBlock> = when {
        JniTypes.extractDataClass(type) != null -> {
            val dc = JniTypes.extractDataClass(type)!!
            if (type is KneType.NULLABLE) {
                // Native reads this as `_isNull`: 1 means "no value, build null".
                listOf(CodeBlock.of("if (%L == null) 1 else 0", name)) +
                    dcFlatCallArgs(dc, name, nullable = true, poet)
            } else {
                dcFlatCallArgs(dc, name, nullable = false, poet)
            }
        }
        type is KneType.NULLABLE && type.inner is KneType.MAP -> {
            val m = type.inner as KneType.MAP
            listOf(
                cbPackCollectionArg(CodeBlock.of("%L.keys", name), m.keyType, setWrap = false, nullable = true, poet),
                cbPackCollectionArg(CodeBlock.of("%L.values", name), m.valueType, setWrap = false, nullable = true, poet),
            )
        }
        type is KneType.MAP -> listOf(
            cbPackCollectionArg(CodeBlock.of("%L.keys", name), type.keyType, setWrap = false, nullable = false, poet),
            cbPackCollectionArg(CodeBlock.of("%L.values", name), type.valueType, setWrap = false, nullable = false, poet),
        )
        else -> listOf(cbPlainCallArg(name, type, poet))
    }

    /** Conversion of a plain (non-DC, non-map) value at an external call site. */
    private fun cbPlainCallArg(expr: String, type: KneType, poet: KnePoetTypes): CodeBlock = when (type) {
        is KneType.ENUM -> CodeBlock.of("%L.ordinal", expr)
        is KneType.OBJECT -> CodeBlock.of("%L.handle", expr)
        is KneType.INTERFACE -> CodeBlock.of("%L.handle", expr)
        // Native strings are NUL-terminated on the wire (GetStringUTFChars protocol): reject
        // embedded NULs up front, mirroring the KneRuntime.checkString validation.
        is KneType.STRING -> CodeBlock.of("KneRuntime.checkString(%L)", expr)
        is KneType.LIST -> cbPackCollectionArg(CodeBlock.of("%L", expr), type.elementType, setWrap = false, nullable = false, poet)
        is KneType.SET -> cbPackCollectionArg(CodeBlock.of("%L", expr), type.elementType, setWrap = true, nullable = false, poet)
        is KneType.FUNCTION -> CodeBlock.of("KneRuntime.KneFn%L(%L)", callbackId(type), expr)
        is KneType.NULLABLE -> when (type.inner) {
            is KneType.FUNCTION -> CodeBlock.of(
                "%L?.let { KneRuntime.KneFn%L(it) }",
                expr, callbackId(type.inner as KneType.FUNCTION),
            )
            is KneType.STRING -> CodeBlock.of("%L?.let { KneRuntime.checkString(it) }", expr)
            is KneType.LIST -> cbPackCollectionArg(CodeBlock.of("%L", expr), type.inner.elementType, setWrap = false, nullable = true, poet)
            is KneType.SET -> cbPackCollectionArg(CodeBlock.of("%L", expr), type.inner.elementType, setWrap = true, nullable = true, poet)
            else -> CodeBlock.of("%L", expr)
        }
        else -> CodeBlock.of("%L", expr)
    }

    /** A flattened DC field call arg (value comes from a non-null DC object). */
    private fun cbNullableDcFieldArg(dcName: String, fieldName: String, fieldType: KneType, poet: KnePoetTypes): CodeBlock {
        val path = CodeBlock.of("%L?.%L", dcName, fieldName)
        return when (fieldType) {
            is KneType.STRING -> CodeBlock.of("%L?.let { KneRuntime.checkString(it) } ?: \"\"", path)
            is KneType.BOOLEAN -> CodeBlock.of("if (%L == true) 1 else 0", path)
            is KneType.BYTE -> CodeBlock.of("%L ?: 0.toByte()", path)
            is KneType.SHORT -> CodeBlock.of("%L ?: 0.toShort()", path)
            is KneType.INT -> CodeBlock.of("%L ?: 0", path)
            is KneType.LONG -> CodeBlock.of("%L ?: 0L", path)
            is KneType.FLOAT -> CodeBlock.of("%L ?: 0f", path)
            is KneType.DOUBLE -> CodeBlock.of("%L ?: 0.0", path)
            is KneType.ENUM -> CodeBlock.of("%L?.ordinal ?: 0", path)
            is KneType.OBJECT, is KneType.INTERFACE -> CodeBlock.of("%L?.handle ?: 0L", path)
            is KneType.BYTE_ARRAY -> CodeBlock.of("%L ?: ByteArray(0)", path)
            is KneType.LIST -> CodeBlock.of("%L ?: emptyList()", path)
            is KneType.SET -> CodeBlock.of("%L ?: emptySet()", path)
            is KneType.MAP -> CodeBlock.of("%L ?: emptyMap()", path)
            is KneType.DATA_CLASS -> CodeBlock.of("%L!!", path) // nested nullability not flattened across null boundary
            else -> CodeBlock.of("%L ?: null", path)
        }
    }

    /** Call args for a DC in the exact order of [dcFlatExternalParams]. */
    private fun dcFlatCallArgs(dc: KneType.DATA_CLASS, name: String, nullable: Boolean, poet: KnePoetTypes): List<CodeBlock> {
        val result = mutableListOf<CodeBlock>()
        fun addFields(dc: KneType.DATA_CLASS, objPath: CodeBlock) {
            dc.fields.forEach { f ->
                val fieldPath = CodeBlock.of("%L.%L", objPath, f.name)
                when {
                    f.type is KneType.DATA_CLASS -> addFields(f.type, fieldPath)
                    f.type is KneType.MAP -> {
                        result.add(cbPackCollectionArg(CodeBlock.of("%L.keys", fieldPath), f.type.keyType, setWrap = false, nullable = false, poet))
                        result.add(cbPackCollectionArg(CodeBlock.of("%L.values", fieldPath), f.type.valueType, setWrap = false, nullable = false, poet))
                    }
                    else -> result.add(cbPlainCallArg(fieldPath.toString(), f.type, poet))
                }
            }
        }
        fun addNullableFields(dc: KneType.DATA_CLASS, objName: String) {
            dc.fields.forEach { f ->
                val fieldPath = CodeBlock.of("%L?.%L", objName, f.name)
                when {
                    f.type is KneType.DATA_CLASS -> { /* nested nullable DC not common */ }
                    f.type is KneType.MAP -> {
                        result.add(cbPackCollectionArg(CodeBlock.of("%L?.keys", fieldPath), f.type.keyType, setWrap = false, nullable = true, poet))
                        result.add(cbPackCollectionArg(CodeBlock.of("%L?.values", fieldPath), f.type.valueType, setWrap = false, nullable = true, poet))
                    }
                    else -> result.add(cbNullableDcFieldArg(objName, f.name, f.type, poet))
                }
            }
        }
        if (nullable) addNullableFields(dc, name) else addFields(dc, CodeBlock.of("%L", name))
        return result
    }

    /** Packs a List/Set<T> param into the external array type. */
    private fun cbPackCollectionArg(expr: CodeBlock, elemType: KneType, setWrap: Boolean, nullable: Boolean, poet: KnePoetTypes): CodeBlock {
        val src = if (setWrap) CodeBlock.of("%L.toList()", expr) else expr
        val body = when (elemType) {
            is KneType.INT -> CodeBlock.of("%L.toIntArray()", src)
            is KneType.LONG -> CodeBlock.of("%L.toLongArray()", src)
            is KneType.DOUBLE -> CodeBlock.of("%L.toDoubleArray()", src)
            is KneType.FLOAT -> CodeBlock.of("%L.toFloatArray()", src)
            is KneType.SHORT -> CodeBlock.of("%L.toShortArray()", src)
            is KneType.BYTE -> CodeBlock.of("%L.toByteArray()", src)
            is KneType.BOOLEAN -> CodeBlock.of("%L.toBooleanArray()", src)
            is KneType.ENUM -> CodeBlock.of("IntArray(%L.size) { %L[it].ordinal }", src, src)
            is KneType.STRING -> CodeBlock.of(
                "run { val _a = arrayOfNulls<%T>(%L.size); %L.forEachIndexed { i, s -> _a[i] = KneRuntime.checkString(s) }; _a }",
                KnePoetTypes.STRING, src, src,
            )
            is KneType.OBJECT -> CodeBlock.of("LongArray(%L.size) { %L[it].handle }", src, src)
            is KneType.INTERFACE -> CodeBlock.of("LongArray(%L.size) { %L[it].handle }", src, src)
            is KneType.BYTE_ARRAY -> CodeBlock.of(
                "run { val _a = arrayOfNulls<%T>(%L.size); %L.forEachIndexed { i, b -> _a[i] = b }; _a }",
                KnePoetTypes.BYTE_ARRAY, src, src,
            )
            is KneType.DATA_CLASS -> CodeBlock.of(
                "run { val _a = arrayOfNulls<Any>(%L.size); %L.forEachIndexed { i, v -> _a[i] = v }; _a }",
                src, src,
            )
            is KneType.LIST -> CodeBlock.of(
                "run { val _a = arrayOfNulls<Any>(%L.size); %L.forEachIndexed { i, v -> _a[i] = %L }; _a }",
                src, src, cbPackNestedElem(CodeBlock.of("v"), elemType.elementType, poet),
            )
            is KneType.SET -> CodeBlock.of(
                "run { val _a = arrayOfNulls<Any>(%L.size); %L.forEachIndexed { i, v -> _a[i] = %L }; _a }",
                src, src, cbPackNestedElem(CodeBlock.of("v"), elemType.elementType, poet),
            )
            is KneType.MAP -> CodeBlock.of(
                "run { val _a = arrayOfNulls<Any>(%L.size); %L.forEachIndexed { i, v -> _a[i] = java.util.HashMap(v) }; _a }",
                src, src,
            )
            else -> CodeBlock.of("%L.toTypedArray()", src)
        }
        return if (nullable) CodeBlock.of("%L?.let { %L }", expr, body) else body
    }

    private fun cbPackNestedElem(expr: CodeBlock, elemType: KneType, poet: KnePoetTypes): CodeBlock = when (elemType) {
        is KneType.LIST -> cbPackCollectionArg(expr, elemType.elementType, setWrap = false, nullable = false, poet)
        is KneType.SET -> cbPackCollectionArg(expr, elemType.elementType, setWrap = true, nullable = false, poet)
        is KneType.MAP -> CodeBlock.of("java.util.HashMap(%L)", expr)
        else -> cbPackCollectionArg(expr, elemType, setWrap = false, nullable = false, poet)
    }

    /** Call args CodeBlock for a constructor invocation (no leading handle). */
    private fun ctorCallArgs(params: List<KneParam>, poet: KnePoetTypes): CodeBlock =
        params.flatMap { callArgExpr(it.name, it.type, poet) }.joinToCode(", ")

    /** All call args for a function, optionally with leading handle. */
    private fun allCallArgs(params: List<KneParam>, handleExpr: String?, poet: KnePoetTypes): CodeBlock {
        val parts = mutableListOf<CodeBlock>()
        if (handleExpr != null) parts.add(CodeBlock.of("%L", handleExpr))
        params.forEach { p -> parts.addAll(callArgExpr(p.name, p.type, poet)) }
        return parts.joinToCode(", ")
    }

    // ── return unwrapping (external result → public value) ───────────────────

    /** CodeBlock referencing the local `_r` (raw external result) producing the public value. */
    private fun cbUnwrapReturnExpr(type: KneType, poet: KnePoetTypes): CodeBlock = when (val t = JniTypes.unwrapNullable(type)) {
        is KneType.ENUM ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r >= 0) %T.entries[_r] else null", poet.jvmClass(t.simpleName))
            else CodeBlock.of("%T.entries[_r]", poet.jvmClass(t.simpleName))
        is KneType.OBJECT ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != 0L) %T.fromNativeHandle(_r) else null", poet.jvmClass(t.simpleName))
            else CodeBlock.of("%T.fromNativeHandle(_r)", poet.jvmClass(t.simpleName))
        is KneType.INTERFACE ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != 0L) %T.fromNativeHandle(_r) else null", poet.jvmClass(t.simpleName))
            else CodeBlock.of("%T.fromNativeHandle(_r)", poet.jvmClass(t.simpleName))
        is KneType.LIST ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r == null) null else %L", cbCollUnwrap(t.elementType, "List", poet))
            else cbCollUnwrap(t.elementType, "List", poet)
        is KneType.SET ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r == null) null else %L", cbCollUnwrap(t.elementType, "Set", poet))
            else cbCollUnwrap(t.elementType, "Set", poet)
        is KneType.BOOLEAN ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r < 0) null else _r != 0")
            else CodeBlock.of("_r")
        is KneType.INT ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != Long.MIN_VALUE) _r.toInt() else null")
            else CodeBlock.of("_r")
        is KneType.LONG ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != Long.MIN_VALUE) _r else null")
            else CodeBlock.of("_r")
        is KneType.SHORT ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != Int.MIN_VALUE) _r.toShort() else null")
            else CodeBlock.of("_r")
        is KneType.BYTE ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != Int.MIN_VALUE) _r.toByte() else null")
            else CodeBlock.of("_r")
        is KneType.FLOAT ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != Long.MIN_VALUE) Float.fromBits(_r.toInt()) else null")
            else CodeBlock.of("_r")
        is KneType.DOUBLE ->
            if (type is KneType.NULLABLE) CodeBlock.of("if (_r != Long.MIN_VALUE) Double.fromBits(_r) else null")
            else CodeBlock.of("_r")
        is KneType.FUNCTION ->
            if (type is KneType.NULLABLE) cbFnInvokeWrapExpr(t, nullable = true, poet)
            else cbFnInvokeWrapExpr(t, nullable = false, poet)
        else -> CodeBlock.of("_r")
    }

    /**
     * Wraps a native lambda handle (`_r`) in a real Kotlin function value whose body calls the
     * `KneNativeBindings._kneInvokeFn<id>` bridge registered on the native side.
     */
    private fun cbFnInvokeWrapExpr(fnType: KneType.FUNCTION, nullable: Boolean, poet: KnePoetTypes): CodeBlock {
        val id = JniTypes.functionId(fnType)
        val lambdaParams = fnType.paramTypes.indices.joinToString(", ") { "_p$it" }
        val callArgs = buildList {
            add(CodeBlock.of("_r"))
            fnType.paramTypes.forEachIndexed { i, t ->
                add(
                    when {
                        t is KneType.ENUM -> CodeBlock.of("_p$i.ordinal")
                        t is KneType.OBJECT || t is KneType.INTERFACE -> CodeBlock.of("_p$i.handle")
                        t is KneType.STRING -> CodeBlock.of("KneRuntime.checkString(_p$i)")
                        else -> CodeBlock.of("_p$i")
                    }
                )
            }
        }.joinToCode(", ")
        val body = when (fnType.returnType) {
            KneType.UNIT -> CodeBlock.of("KneNativeBindings._kneInvokeFn$id(%L); Unit", callArgs)
            else -> CodeBlock.of(
                "(KneNativeBindings._kneInvokeFn$id(%L)) as %T",
                callArgs, poet.publicType(fnType.returnType),
            )
        }
        val wrapped = CodeBlock.of("{ %L -> %L }", lambdaParams, body)
        return if (nullable) CodeBlock.of("if (_r == 0L) null else %L", wrapped) else wrapped
    }

    /** CodeBlock producing a Kotlin List/Set from the raw `_r` jarray. */
    private fun cbCollUnwrap(elemType: KneType, collKind: String, poet: KnePoetTypes): CodeBlock {
        val list = when (elemType) {
            is KneType.INT, is KneType.LONG, is KneType.DOUBLE, is KneType.FLOAT,
            is KneType.SHORT, is KneType.BYTE, is KneType.BOOLEAN -> CodeBlock.of("_r.toList()")
            is KneType.ENUM -> CodeBlock.of("_r.toList().map { %T.entries[it] }", poet.jvmClass(elemType.simpleName))
            is KneType.STRING -> CodeBlock.of("_r.map { it ?: \"\" }")
            is KneType.OBJECT -> CodeBlock.of("_r.map { %T.fromNativeHandle(it) }", poet.jvmClass(elemType.simpleName))
            is KneType.INTERFACE -> CodeBlock.of("_r.map { %T.fromNativeHandle(it) }", poet.jvmClass(elemType.simpleName))
            is KneType.BYTE_ARRAY -> CodeBlock.of("_r.map { it ?: ByteArray(0) }")
            is KneType.DATA_CLASS -> CodeBlock.of("_r.map { it as %T }", poet.jvmClass(elemType.simpleName))
            // Nested collections cross as an Object[] of jarray elements (e.g. List<List<Int>>
            // arrives as Object[] of int[]), so each element has to be unpacked, not cast.
            is KneType.LIST -> CodeBlock.of("_r.map { %L }", cbNestedCollUnwrap(elemType.elementType, CodeBlock.of("it"), poet))
            is KneType.SET -> CodeBlock.of("_r.map { %L }", cbNestedCollUnwrap(elemType.elementType, CodeBlock.of("it"), poet))
            else -> CodeBlock.of("_r.toList()")
        }
        return if (collKind == "Set") CodeBlock.of("%L.toSet()", list) else list
    }

    /** Converts one packed jarray element (`Any?`) inside a nested collection into a Kotlin value. */
    private fun cbNestedCollUnwrap(elemType: KneType, expr: CodeBlock, poet: KnePoetTypes): CodeBlock = when (elemType) {
        is KneType.INT -> CodeBlock.of("(%L as IntArray).toList()", expr)
        is KneType.LONG -> CodeBlock.of("(%L as LongArray).toList()", expr)
        is KneType.DOUBLE -> CodeBlock.of("(%L as DoubleArray).toList()", expr)
        is KneType.FLOAT -> CodeBlock.of("(%L as FloatArray).toList()", expr)
        is KneType.SHORT -> CodeBlock.of("(%L as ShortArray).toList()", expr)
        is KneType.BYTE -> CodeBlock.of("(%L as ByteArray).toList()", expr)
        is KneType.BOOLEAN -> CodeBlock.of("(%L as BooleanArray).toList()", expr)
        is KneType.ENUM -> CodeBlock.of("(%L as IntArray).toList().map { %T.entries[it] }", expr, poet.jvmClass(elemType.simpleName))
        is KneType.STRING -> CodeBlock.of("(%L as Array<*>).map { it as String }", expr)
        is KneType.OBJECT -> CodeBlock.of("(%L as LongArray).toList().map { %T.fromNativeHandle(it) }", expr, poet.jvmClass(elemType.simpleName))
        is KneType.INTERFACE -> CodeBlock.of("(%L as LongArray).toList().map { %T.fromNativeHandle(it) }", expr, poet.jvmClass(elemType.simpleName))
        is KneType.LIST -> CodeBlock.of("(%L as Array<*>).map { %L }", expr, cbNestedCollUnwrap(elemType.elementType, CodeBlock.of("it"), poet))
        is KneType.SET -> CodeBlock.of("(%L as Array<*>).map { %L }", expr, cbNestedCollUnwrap(elemType.elementType, CodeBlock.of("it"), poet))
        else -> CodeBlock.of("(%L as List<Any?>)", expr)
    }

    // ── instance member bodies ───────────────────────────────────────────────

    /** Emits a call whose result is unwrapped into the public return value. */
    private fun callableBody(
        fn: KneFunction,
        poet: KnePoetTypes,
        receiverExpr: String?,
        externalRef: String,
    ): CodeBlock {
        val args = allCallArgs(fn.params, receiverExpr, poet)
        val call = CodeBlock.of("%L(%L)", externalRef, args)
        val usesLease = receiverExpr == "handle"

        // Proxy objects passed as arguments are leased for the call: acquire() throws when the
        // argument was already closed (fails cleanly on the caller side instead of handing a stale
        // handle to native) and release() defers disposal until native stops using it.
        val leasedArgs = fn.params.filter {
            it.type is KneType.OBJECT || (it.type is KneType.NULLABLE && it.type.inner is KneType.OBJECT)
        }
        val cb = CodeBlock.builder()
        leasedArgs.forEach { p ->
            val leaseExpr = if (p.type is KneType.NULLABLE) "${p.name}?._lease" else "${p.name}._lease"
            cb.add("KneRuntime.leased(%L) {\n", leaseExpr).indent()
        }

        if (fn.returnType == KneType.UNIT) {
            if (usesLease) {
                cb.add("_lease.use {\n").indent()
                cb.add("%L\n", call)
                cb.unindent().add("}\n")
            } else {
                cb.add("%L\n", call)
            }
        } else {
            val unwrap = cbUnwrapReturnExpr(fn.returnType, poet)
            if (usesLease) {
                cb.add("_lease.use {\n").indent()
                cb.add("val _r = %L\n", call)
                cb.add("return %L\n", unwrap)
                cb.unindent().add("}\n")
            } else {
                cb.add("val _r = %L\n", call)
                cb.add("return %L\n", unwrap)
            }
        }

        repeat(leasedArgs.size) { cb.unindent().add("}\n") }
        return cb.build()
    }

    private fun buildInstanceMethod(
        fn: KneFunction,
        poet: KnePoetTypes,
        isOverride: Boolean,
        parentIsOpen: Boolean,
    ): FunSpec {
        val spec = FunSpec.builder(fn.name)
        when {
            isOverride -> spec.addModifiers(KModifier.OVERRIDE)
            parentIsOpen -> spec.addModifiers(KModifier.OPEN)
        }
        if (fn.isSuspend) spec.addModifiers(KModifier.SUSPEND)
        publicParamSpecs(fn.params, poet).forEach { spec.addParameter(it) }
        if (fn.returnType != KneType.UNIT) spec.returns(poet.publicType(fn.returnType))
        when {
            fn.isSuspend -> spec.addCode(buildSuspendBody(fn, poet))
            fn.returnType is KneType.FLOW -> spec.addCode(buildFlowBody(fn, poet))
            else -> spec.addCode(callableBody(fn, poet, receiverExpr = "handle", externalRef = extName(fn.name)))
        }
        return spec.build()
    }

    private fun buildSuspendBody(fn: KneFunction, poet: KnePoetTypes): CodeBlock {
        val id = payloadId(fn.returnType)
        // Once the async call is dispatched, the completer (KneSuspend$id) is the lease's sole
        // owner: it releases exactly once in its finally, whether it completes, errors, or runs
        // after a JVM-side cancellation. The wrapper releases only when the dispatch itself
        // failed — no completer will ever fire in that case. Releasing unconditionally would
        // double-release on error/cancellation completions: the lease state underflows below
        // zero (reads as "closed" on every later call) and, worse, an undercounted in-flight
        // counter lets close() dispose the native ref while other calls are still using it.
        return CodeBlock.builder()
            .add("_lease.acquire()\n")
            .add("var _kneDispatched = false\n")
            .add("try {\n")
            .indent()
            .add("return %M { cont ->\n", M_SUSPEND_CANCELLABLE_COROUTINE)
            .indent()
            .add(
                "val job = %LAsync(%L, KneRuntime.KneSuspend%L(cont, _lease))\n",
                extName(fn.name), allCallArgs(fn.params, "handle", poet), id,
            )
            .add("_kneDispatched = true\n")
            .add("cont.invokeOnCancellation { KneNativeBindings._kneCancelJob(job) }\n")
            .unindent()
            .add("}\n")
            .unindent()
            .add("} catch (t: Throwable) {\n")
            .indent()
            .add("if (!_kneDispatched) _lease.release()\n")
            .add("throw t\n")
            .unindent()
            .add("}\n")
            .build()
    }

    private fun buildFlowBody(fn: KneFunction, poet: KnePoetTypes): CodeBlock {
        val elemType = (fn.returnType as KneType.FLOW).elementType
        val id = payloadId(elemType)
        // Lease is acquired when collection starts (mirrors the flow-body convention):
        // acquire() rejects closed owners before the handle ever reaches native — otherwise a flow
        // started on a closed object dereferences a disposed StableRef and dies with a native NPE.
        // The dispatcher releases it on the single terminal event (onComplete/onError/onCancelled).
        return CodeBlock.builder()
            .add("return %M {\n", M_CALLBACK_FLOW)
            .indent()
            .add("_lease.acquire()\n")
            .add("val _kneCb = KneRuntime.KneFlow%L(channel, _lease)\n", id)
            .add("val job = try {\n")
            .indent()
            .add("%LFlow(%L, _kneCb)\n", extName(fn.name), allCallArgs(fn.params, "handle", poet))
            .unindent()
            .add("} catch (t: Throwable) {\n")
            .indent()
            .add("_lease.release()\n")
            .add("throw t\n")
            .unindent()
            .add("}\n")
            .add("_kneCb.jobId = job\n")
            .add("%M { KneNativeBindings._kneCancelJob(job) }\n", M_AWAIT_CLOSE)
            .unindent()
            .add("}\n")
            .build()
    }

    private fun buildInstanceProperty(
        prop: KneProperty,
        poet: KnePoetTypes,
        isOverride: Boolean,
        parentIsOpen: Boolean,
    ): PropertySpec {
        val spec = PropertySpec.builder(prop.name, poet.publicType(prop.type))
        when {
            isOverride -> spec.addModifiers(KModifier.OVERRIDE)
            parentIsOpen -> spec.addModifiers(KModifier.OPEN)
        }
        spec.mutable(prop.mutable)
        spec.getter(
            FunSpec.getterBuilder()
                .addCode(
                    "return _lease.use { val _r = %L(handle); %L }\n",
                    extName("Get", prop.name), cbUnwrapReturnExpr(prop.type, poet),
                )
                .build(),
        )
        if (prop.mutable) {
            spec.setter(
                FunSpec.setterBuilder()
                    .addParameter("value", poet.publicType(prop.type))
                    .addCode(
                        CodeBlock.builder()
                            .add("_lease.use {\n")
                            .indent()
                            .add(
                                "%L(%L)\n",
                                extName("Set", prop.name),
                                allCallArgs(listOf(KneParam("value", prop.type)), "handle", poet),
                            )
                            .unindent()
                            .add("}\n")
                            .build(),
                    )
                    .build(),
            )
        }
        return spec.build()
    }

    private fun buildCompanionMethodBody(fn: KneFunction, poet: KnePoetTypes): FunSpec {
        val spec = FunSpec.builder(fn.name)
        publicParamSpecs(fn.params, poet).forEach { spec.addParameter(it) }
        if (fn.returnType != KneType.UNIT) spec.returns(poet.publicType(fn.returnType))
        spec.addCode(callableBody(fn, poet, receiverExpr = null, externalRef = extName("Companion", fn.name)))
        return spec.build()
    }

    private fun buildCompanionPropertyBody(prop: KneProperty, poet: KnePoetTypes): PropertySpec {
        val spec = PropertySpec.builder(prop.name, poet.publicType(prop.type))
            .mutable(prop.mutable)
            .getter(
                FunSpec.getterBuilder()
                    .addCode(
                        "val _r = %L()\nreturn %L\n",
                        extName("CompanionGet", prop.name), cbUnwrapReturnExpr(prop.type, poet),
                    )
                    .build(),
            )
        if (prop.mutable) {
            spec.setter(
                FunSpec.setterBuilder()
                    .addParameter("value", poet.publicType(prop.type))
                    .addCode(
                        "%L(%L)\n",
                        extName("CompanionSet", prop.name),
                        allCallArgs(listOf(KneParam("value", prop.type)), null, poet),
                    )
                    .build(),
            )
        }
        return spec.build()
    }

    /** Public API parameter specs (as declared on the user's Kotlin/Native API). */
    private fun publicParamSpecs(params: List<KneParam>, poet: KnePoetTypes): List<ParameterSpec> =
        params.map { p -> ParameterSpec.builder(p.name, poet.publicType(p.type)).build() }

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

    private fun callbackId(fnType: KneType.FUNCTION): String {
        fun sanitize(s: String) = s.replace("<", "_").replace(">", "").replace(", ", "_").replace("?", "N")
        val params = fnType.paramTypes.joinToString("_") { sanitize(it.jvmTypeName) }
        val ret = sanitize(fnType.returnType.jvmTypeName)
        return "${params.ifEmpty { "Void" }}_to_$ret"
    }

    /** Function types returned by the API surface (need an invoke bridge on both sides). */
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

    // ── statement block constants (fixed runtime code) ───────────────────────

    private fun lambdaOf(paramTypes: List<TypeName>, returnType: TypeName): TypeName =
        LambdaTypeName.get(parameters = paramTypes.toTypedArray(), returnType = returnType)

    private val T: TypeVariableName = TypeVariableName("T")
    private val LAMBDA_VOID: TypeName = LambdaTypeName.get(parameters = emptyArray<TypeName>(), returnType = KnePoetTypes.UNIT)

    private fun loadLibraryBody(): CodeBlock = CodeBlock.builder()
        .add("val fileName = System.mapLibraryName(LIB_NAME)\n")
        .add("\n")
        .add("// Tier 1: explicit paths (java.library.path, working dir, packaged apps)\n")
        .add("val sep = if (System.getProperty(%S).lowercase().contains(%S)) %S else %S\n", "os.name", "win", ";", ":")
        .add("val basePaths = System.getProperty(%S, %S).split(sep)\n", "java.library.path", "")
        .add("val extraDirs = mutableListOf(System.getProperty(%S, %S))\n", "user.dir", ".")
        .add("for (dir in (basePaths + extraDirs).distinct().filter { it.isNotBlank() }) {\n")
        .indent()
        .add("val file = %T.get(dir, fileName).toFile()\n", PATHS)
        .add("if (file.exists()) { System.load(file.absolutePath); return }\n")
        .add("val libFile = %T.get(dir, %S, fileName).toFile()\n", PATHS, "lib")
        .add("if (libFile.exists()) { System.load(libFile.absolutePath); return }\n")
        .unindent()
        .add("}\n")
        .add("\n")
        .add("// Tier 2: standard library path resolution (Android jniLibs, installed dirs)\n")
        .add("try {\n")
        .indent()
        .add("System.loadLibrary(LIB_NAME)\n")
        .add("return\n")
        .unindent()
        .add("} catch (_: %T) {\n", UNSATISFIED_LINK_ERROR)
        .indent()
        .add("// fall through to JAR extraction\n")
        .unindent()
        .add("}\n")
        .add("\n")
        .add("// Tier 3: extract from JAR resources to a persistent cache (zero-config desktop)\n")
        .add("val platform = detectPlatform()\n")
        .add("val resourcePath = \"/jniLibs/\$platform/\$fileName\"\n")
        .add("val stream = KneRuntime::class.java.getResourceAsStream(resourcePath)\n")
        .add("if (stream != null) {\n")
        .indent()
        .add("val cacheDir = resolveCacheDir(platform)\n")
        .add("%T.createDirectories(cacheDir)\n", FILES)
        .add("val target = cacheDir.resolve(fileName)\n")
        .add("stream.use { input ->\n")
        .indent()
        .add("val bytes = input.readAllBytes()\n")
        .add("val stale = !%T.exists(target) ||\n", FILES)
        .add("%T.size(target) != bytes.size.toLong() ||\n", FILES)
        .add("!%T.readAllBytes(target).contentEquals(bytes)\n", FILES)
        .add("if (stale) {\n")
        .indent()
        .add("val tmp = %T.createTempFile(cacheDir, LIB_NAME, %S)\n", FILES, ".tmp")
        .add("%T.write(tmp, bytes)\n", FILES)
        .add("%T.move(tmp, target, %T.REPLACE_EXISTING, %T.ATOMIC_MOVE)\n", FILES, STANDARD_COPY_OPTION, STANDARD_COPY_OPTION)
        .unindent()
        .add("}\n")
        .unindent()
        .add("}\n")
        .add("System.load(target.toFile().absolutePath)\n")
        .add("return\n")
        .unindent()
        .add("}\n")
        .add("\n")
        .add("throw %T(%S + fileName)\n", UNSATISFIED_LINK_ERROR, "Native library not found: ")
        .build()

    private fun detectPlatformBody(): CodeBlock = CodeBlock.builder()
        .add("val os = System.getProperty(%S, %S).lowercase()\n", "os.name", "")
        .add("val arch = System.getProperty(%S, %S).lowercase()\n", "os.arch", "")
        .add("// Android JVMs report os.name=Linux; discriminate via vendor/vm name and emit\n")
        .add("// the standard Android ABI so the runtime matches the jniLibs layout.\n")
        .add("val isAndroid = System.getProperty(%S, %S).contains(%S) ||\n", "java.vendor", "", "Android")
        .add("System.getProperty(%S, %S).let { it.contains(%S) || it.contains(%S) }\n", "java.vm.name", "", "Dalvik", "ART")
        .add("if (isAndroid) return when {\n")
        .indent()
        .add("arch.contains(%S) || arch.contains(%S) -> %S\n", "aarch64", "arm64", "arm64-v8a")
        .add("arch.contains(%S) || arch.contains(%S) -> %S\n", "x86_64", "amd64", "x86_64")
        .add("arch.startsWith(%S) -> %S\n", "arm", "armeabi-v7a")
        .add("else -> %S\n", "x86")
        .unindent()
        .add("}\n")
        .add("val archName = if (arch.contains(%S) || arch.contains(%S)) %S else %S\n", "aarch64", "arm64", "aarch64", "x64")
        .add("val osName = when { os.contains(%S) || os.contains(%S) -> %S; os.contains(%S) -> %S; else -> %S }\n", "mac", "darwin", "darwin", "win", "win32", "linux")
        .add("return \"\$osName-\$archName\"\n")
        .build()

    private fun resolveCacheDirBody(): CodeBlock = CodeBlock.builder()
        .add("val os = System.getProperty(%S, %S).lowercase()\n", "os.name", "")
        .add("val base = when {\n")
        .indent()
        .add("os.contains(%S) -> %T.get(System.getProperty(%S), %S, %S)\n", "mac", PATHS, "user.home", "Library", "Caches")
        .add("os.contains(%S) -> %T.get(System.getenv(%S) ?: System.getProperty(%S))\n", "win", PATHS, "LOCALAPPDATA", "user.home")
        .add("else -> %T.get(System.getenv(%S) ?: \"\${System.getProperty(%S)}/.cache\")\n", PATHS, "XDG_CACHE_HOME", "user.home")
        .unindent()
        .add("}\n")
        .add("return base.resolve(%S).resolve(%S).resolve(platform)\n", "kne", "native")
        .build()

    private companion object {
        private val ENSURE_NATIVES_BLOCK = CodeBlock.builder()
            .add("if (!nativesRegistered) {\n")
            .indent()
            .add("synchronized(registrationLock) {\n")
            .indent()
            .add("if (!nativesRegistered) {\n")
            .indent()
            .add("loadLibrary()\n")
            .add("initNatives()\n")
            .add("nativesRegistered = true\n")
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .add("return true\n")
            .build()

        private val LEASE_ACQUIRE_BLOCK = CodeBlock.builder()
            .add("while (true) {\n")
            .indent()
            .add("val s = state.get()\n")
            .add("check(s and CLOSED == 0) { %S }\n", "Native object has been closed")
            .add("if (state.compareAndSet(s, s + 1)) return\n")
            .unindent()
            .add("}\n")
            .build()

        private val LEASE_CLOSE_BLOCK = CodeBlock.builder()
            .add("while (true) {\n")
            .indent()
            .add("val s = state.get()\n")
            .add("if (s and CLOSED != 0) return\n")
            .add("if (state.compareAndSet(s, s or CLOSED)) {\n")
            .indent()
            .add("if (s == 0) dispose()\n")
            .add("return\n")
            .unindent()
            .add("}\n")
            .unindent()
            .add("}\n")
            .build()

        private val LEASE_USE_BLOCK = CodeBlock.builder()
            .add("acquire()\n")
            .add("try {\n")
            .indent()
            .add("return block()\n")
            .unindent()
            .add("} finally {\n")
            .indent()
            .add("release()\n")
            .unindent()
            .add("}\n")
            .build()

        private val LEASED_BLOCK = CodeBlock.builder()
            .add("lease?.acquire()\n")
            .add("try {\n")
            .indent()
            .add("return block()\n")
            .unindent()
            .add("} finally {\n")
            .indent()
            .add("lease?.release()\n")
            .unindent()
            .add("}\n")
            .build()

        private val JVM_STATIC = ClassName("kotlin.jvm", "JvmStatic")
        private val JVM_FIELD = ClassName("kotlin.jvm", "JvmField")
        private val JVM_VOLATILE = ClassName("kotlin.jvm", "Volatile")
        private val CLEANER = ClassName("java.lang.ref", "Cleaner")
        private val CANCELLABLE_CONTINUATION = ClassName("kotlinx.coroutines", "CancellableContinuation")
        private val CANCELLATION_EXCEPTION = ClassName("kotlinx.coroutines", "CancellationException")
        private val SEND_CHANNEL = ClassName("kotlinx.coroutines.channels", "SendChannel")
        private val ATOMIC_INTEGER = ClassName("java.util.concurrent.atomic", "AtomicInteger")
        private val ATOMIC_BOOLEAN = ClassName("java.util.concurrent.atomic", "AtomicBoolean")
        private val PATHS = ClassName("java.nio.file", "Paths")
        private val PATH = ClassName("java.nio.file", "Path")
        private val FILES = ClassName("java.nio.file", "Files")
        private val STANDARD_COPY_OPTION = ClassName("java.nio.file", "StandardCopyOption")
        private val UNSATISFIED_LINK_ERROR = ClassName("java.lang", "UnsatisfiedLinkError")
        private val AUTO_CLOSEABLE = ClassName("java.lang", "AutoCloseable")
        private val RUNTIME_EXCEPTION = ClassName("java.lang", "RuntimeException")

        private val M_RUN_BLOCKING = MemberName("kotlinx.coroutines", "runBlocking")
        private val M_CALLBACK_FLOW = MemberName("kotlinx.coroutines.flow", "callbackFlow")
        private val M_AWAIT_CLOSE = MemberName("kotlinx.coroutines.channels", "awaitClose")
        private val M_SUSPEND_CANCELLABLE_COROUTINE = MemberName("kotlinx.coroutines", "suspendCancellableCoroutine")
        private val M_RESUME = MemberName("kotlin.coroutines", "resume")
        private val M_RESUME_WITH_EXCEPTION = MemberName("kotlin.coroutines", "resumeWithException")
    }
}

package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneType

/**
 * JNI backend type mapping: translates [KneType] into everything the two JNI generators need.
 *
 * Shared conventions (mirroring the FFM backend so JVM-visible behaviour is identical):
 *  - Nullable primitives travel as widened non-null values with sentinels
 *    (Long.MIN_VALUE / Int.MIN_VALUE / -1), converted by the JVM proxy wrapper.
 *  - Nullable objects travel as 0L handles.
 *  - Nullable enums travel as -1 ordinals.
 *  - Nullable strings/arrays/objects-on-the-wire use JNI's natural NULL.
 *  - Data class params are flattened into individual field params (recursively);
 *    data class returns are materialised by the native side via `NewObjectA`.
 *  - Object handles stay opaque `Long` (StableRef), exactly like FFM.
 */
object JniTypes {

    /** The Kotlin type used in the JVM `external fun` declaration (JNI-mappable). */
    fun externalParamType(type: KneType): String = when (type) {
        KneType.INT -> "Int"
        KneType.LONG -> "Long"
        KneType.DOUBLE -> "Double"
        KneType.FLOAT -> "Float"
        KneType.BOOLEAN -> "Boolean"
        KneType.BYTE -> "Byte"
        KneType.SHORT -> "Short"
        KneType.STRING -> "String"
        KneType.UNIT -> "Unit"
        is KneType.OBJECT -> "Long"
        is KneType.INTERFACE -> "Long"
        is KneType.ENUM -> "Int"
        KneType.BYTE_ARRAY -> "ByteArray"
        is KneType.FUNCTION -> "Any?"
        is KneType.DATA_CLASS -> error("data class params must be flattened, not mapped directly")
        is KneType.LIST -> arrayParamType(type.elementType)
        is KneType.SET -> arrayParamType(type.elementType)
        is KneType.MAP -> error("map params must be expanded into keys/values")
        is KneType.FLOW -> error("flow is only supported as a return type")
        is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING -> "String?"
            is KneType.OBJECT, is KneType.INTERFACE -> "Long"
            is KneType.ENUM -> "Int"
            KneType.BOOLEAN -> "Int"
            KneType.INT, KneType.LONG -> "Long"
            KneType.SHORT, KneType.BYTE -> "Int"
            KneType.FLOAT, KneType.DOUBLE -> "Long"
            is KneType.DATA_CLASS -> error("nullable data class params must be flattened with _isNull prefix")
            is KneType.FUNCTION -> "Any?"
            KneType.BYTE_ARRAY -> "ByteArray?"
            is KneType.LIST -> arrayParamType(type.inner.elementType) + "?"
            is KneType.SET -> arrayParamType(type.inner.elementType) + "?"
            is KneType.MAP -> error("nullable map params must be expanded into keys/values")
            else -> error("unsupported nullable param type: $type")
        }
    }

    /** Array type for List<T>/Set<T> params. Nullability handled by callers. */
    fun arrayParamType(elementType: KneType): String = when (elementType) {
        KneType.INT, is KneType.ENUM -> "IntArray"
        KneType.LONG, is KneType.OBJECT, is KneType.INTERFACE -> "LongArray"
        KneType.DOUBLE -> "DoubleArray"
        KneType.FLOAT -> "FloatArray"
        KneType.SHORT -> "ShortArray"
        KneType.BYTE -> "ByteArray"
        KneType.BOOLEAN -> "BooleanArray"
        KneType.STRING -> "Array<String?>"
        KneType.BYTE_ARRAY -> "Array<ByteArray?>"
        is KneType.DATA_CLASS -> "Array<Any?>"
        is KneType.LIST, is KneType.SET, is KneType.MAP -> "Array<Any?>"
        else -> error("unsupported collection element type: $elementType")
    }

    /**
     * Expanded external-fun param list for a value of [name] with [type].
     * Returns (name, kotlinType) pairs, flattened for DC / expanded for Map / ByteArray.
     */
    fun expandExternalParams(name: String, type: KneType): List<Pair<String, String>> = when {
        extractDataClass(type) != null -> {
            val dc = extractDataClass(type)!!
            val fields = flattenDataClassFields(dc, name).map { (n, t) -> n to externalParamType(t) }
            if (type is KneType.NULLABLE) listOf("${name}_isNull" to "Int") + fields else fields
        }
        type == KneType.BYTE_ARRAY -> listOf(name to "ByteArray")
        type is KneType.MAP || (type is KneType.NULLABLE && type.inner is KneType.MAP) -> {
            val inner = (if (type is KneType.NULLABLE) type.inner else type) as KneType.MAP
            val suffix = if (type is KneType.NULLABLE) "?" else ""
            listOf(
                "${name}_keys" to arrayParamType(inner.keyType) + suffix,
                "${name}_values" to arrayParamType(inner.valueType) + suffix,
            )
        }
        else -> listOf(name to externalParamType(type))
    }

    /** Convenience for building whole signatures from [KneParam]s. */
    fun expandExternalParams(params: List<KneParam>): List<Pair<String, String>> =
        params.flatMap { expandExternalParams(it.name, it.type) }

    /** JNI type descriptor for one value of [type] (inside a method signature string). */
    fun jniDescriptor(type: KneType): String = when (type) {
        KneType.INT -> "I"
        KneType.LONG -> "J"
        KneType.DOUBLE -> "D"
        KneType.FLOAT -> "F"
        KneType.BOOLEAN -> "Z"
        KneType.BYTE -> "B"
        KneType.SHORT -> "S"
        KneType.UNIT -> "V"
        KneType.STRING -> "Ljava/lang/String;"
        is KneType.OBJECT, is KneType.INTERFACE -> "J" // opaque handle
        is KneType.ENUM -> "I" // ordinal
        KneType.BYTE_ARRAY -> "[B"
        is KneType.FUNCTION -> "Ljava/lang/Object;" // dispatcher
        is KneType.DATA_CLASS -> error("data class params must be flattened")
        is KneType.LIST -> arrayDescriptor(type.elementType)
        is KneType.SET -> arrayDescriptor(type.elementType)
        is KneType.MAP -> error("map params must be expanded into keys/values")
        is KneType.FLOW -> error("flow is only supported as a return type")
        is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING -> "Ljava/lang/String;"
            KneType.BYTE_ARRAY -> "[B"
            is KneType.LIST -> arrayDescriptor(type.inner.elementType)
            is KneType.SET -> arrayDescriptor(type.inner.elementType)
            is KneType.MAP -> "Ljava/util/Map;" // DC map fields materialise as java.util.Map
            KneType.BOOLEAN, is KneType.ENUM, KneType.SHORT, KneType.BYTE -> "I" // sentinel-widened
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "J" // sentinel-widened
            is KneType.OBJECT, is KneType.INTERFACE -> "J"
            is KneType.FUNCTION -> "Ljava/lang/Object;"
            else -> error("unsupported nullable descriptor: $type")
        }
    }

    /** Array descriptor for List<T>/Set<T>. */
    fun arrayDescriptor(elementType: KneType): String = when (elementType) {
        KneType.INT -> "[I"
        KneType.LONG -> "[J"
        KneType.DOUBLE -> "[D"
        KneType.FLOAT -> "[F"
        KneType.SHORT -> "[S"
        KneType.BYTE -> "[B"
        KneType.BOOLEAN -> "[Z"
        is KneType.ENUM -> "[I"
        is KneType.OBJECT, is KneType.INTERFACE -> "[J"
        KneType.STRING -> "[Ljava/lang/String;"
        KneType.BYTE_ARRAY -> "[[B"
        is KneType.DATA_CLASS -> "[Ljava/lang/Object;"
        is KneType.LIST, is KneType.SET, is KneType.MAP -> "[Ljava/lang/Object;"
        else -> "[Ljava/lang/Object;"
    }

    /** Descriptor for the return value of an external fun (DCs materialise as their JVM class). */
    /**
     * JNI return descriptor for [type].
     *
     * [jvmPackage] selects the flattened JVM layout: proxy/data classes are generated directly in
     * that package under their `simpleName`, so a nested data class must be described as
     * `Lpkg/Outer_Inner;` rather than the Kotlin `Lpkg/Outer/Inner;`. When empty, the raw
     * [KneType.DATA_CLASS.fqName] is used (FFM backend, where classes keep their nesting).
     */
    fun returnDescriptor(type: KneType, jvmPackage: String = ""): String = when {
        type is KneType.FLOW -> error("flow handled separately")
        extractDataClass(type) != null -> {
            val dc = extractDataClass(type)!!
            "L${dataClassPath(dc, jvmPackage)};"
        }
        type == KneType.UNIT -> "V"
        // A returned lambda is not a JVM Function object on the native side: native hands back an
        // opaque StableRef handle and the JVM wraps it in a lambda calling the invoke bridge.
        type is KneType.FUNCTION -> "J"
        type is KneType.MAP -> "Ljava/util/Map;" // materialised as java.util.HashMap
        else -> jniDescriptor(type)
    }

    /** Binary path a JVM materialised data class lives at (`a/b/Outer_Inner` when flattened). */
    private fun dataClassPath(dc: KneType.DATA_CLASS, jvmPackage: String): String =
        if (jvmPackage.isEmpty()) dc.fqName.replace('.', '/')
        else "${jvmPackage.replace('.', '/')}/${dc.simpleName}"

    /**
     * Stable identifier for a function type, used to name the generated "invoke this native lambda"
     * bridge. Both generators must derive the exact same string, so it lives here.
     */
    fun functionId(fnType: KneType.FUNCTION): String {
        fun sanitize(s: String) = s.replace("<", "_").replace(">", "").replace(", ", "_").replace("?", "N")
        val params = fnType.paramTypes.joinToString("_") { sanitize(it.jvmTypeName) }
        val ret = sanitize(fnType.returnType.jvmTypeName)
        return "${params.ifEmpty { "Void" }}_to_$ret"
    }

    /** Whether the external fun for a value of [type] returns a JNI object (needs null for null). */
    fun returnsJniObject(type: KneType): Boolean = when {
        type is KneType.NULLABLE && (type.inner == KneType.STRING || type.inner == KneType.BYTE_ARRAY ||
            type.inner is KneType.LIST || type.inner is KneType.SET || type.inner is KneType.MAP) -> true
        type == KneType.BYTE_ARRAY -> false // non-null byte arrays: still an array — JNI descriptor is [B
        else -> false
    }

    // ── shared IR helpers (mirroring NativeBridgeGenerator) ─────────────────

    fun extractDataClass(type: KneType): KneType.DATA_CLASS? = when (type) {
        is KneType.DATA_CLASS -> type
        is KneType.NULLABLE -> type.inner as? KneType.DATA_CLASS
        else -> null
    }

    fun isCollection(type: KneType): Boolean = when (type) {
        is KneType.LIST, is KneType.SET, is KneType.MAP -> true
        is KneType.NULLABLE -> isCollection(type.inner)
        else -> false
    }

    fun unwrapNullable(type: KneType): KneType = if (type is KneType.NULLABLE) type.inner else type

    fun isNullable(type: KneType): Boolean = type is KneType.NULLABLE

    /** Flatten data class fields, expanding nested data classes with prefixed names. */
    fun flattenDataClassFields(dc: KneType.DATA_CLASS, prefix: String): List<Pair<String, KneType>> =
        dc.fields.flatMap { f ->
            val name = "${prefix}_${f.name}"
            if (f.type is KneType.DATA_CLASS) flattenDataClassFields(f.type, name)
            else listOf(Pair(name, f.type))
        }

    /** Builds the JNI method descriptor string "(args)ret" for expanded external params. */
    fun methodDescriptor(
        params: List<KneParam>,
        returnType: KneType,
        jvmPackage: String,
        leadingHandle: Boolean = true,
    ): String {
        val args = buildString {
            if (leadingHandle) append("J")
            params.forEach { p ->
                extractDataClass(p.type)?.let { dc ->
                    if (p.type is KneType.NULLABLE) append("I")
                    flattenDataClassFields(dc, p.name).forEach { (_, t) -> append(jniDescriptor(t)) }
                } ?: when {
                    p.type == KneType.BYTE_ARRAY -> append("[B")
                    p.type is KneType.MAP -> {
                        val inner = p.type as KneType.MAP
                        append(arrayDescriptor(inner.keyType)); append(arrayDescriptor(inner.valueType))
                    }
                    p.type is KneType.NULLABLE && p.type.inner is KneType.MAP -> {
                        val inner = p.type.inner as KneType.MAP
                        append(arrayDescriptor(inner.keyType)); append(arrayDescriptor(inner.valueType))
                    }
                    else -> append(jniDescriptor(p.type))
                }
            }
        }
        return "($args)${returnDescriptor(unwrapNullable(returnType), jvmPackage)}"
    }

    /** Default value the native function returns when an exception is thrown / callback error is pending. */
    fun nativeErrorValue(type: KneType): String = when {
        type == KneType.UNIT || type is KneType.FLOW -> ""
        extractDataClass(type) != null -> "NULL"
        type == KneType.STRING -> "NULL"
        type == KneType.BYTE_ARRAY -> "NULL"
        type is KneType.LIST || type is KneType.SET || type is KneType.MAP -> "NULL"
        type is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING, KneType.BYTE_ARRAY -> "NULL"
            is KneType.LIST, is KneType.SET, is KneType.MAP -> "NULL"
            KneType.BOOLEAN, is KneType.ENUM -> "(-1)"
            KneType.INT, KneType.LONG, KneType.FLOAT, KneType.DOUBLE -> "Long.MIN_VALUE"
            KneType.SHORT, KneType.BYTE -> "(-1)"
            is KneType.OBJECT, is KneType.INTERFACE -> "0L"
            else -> "0"
        }
        else -> when (type) {
            KneType.INT -> "0"
            KneType.LONG -> "0L"
            KneType.DOUBLE -> "0.0"
            KneType.FLOAT -> "0.0f"
            KneType.BOOLEAN -> "0"
            KneType.BYTE -> "0"
            KneType.SHORT -> "0"
            is KneType.OBJECT, is KneType.INTERFACE -> "0L"
            is KneType.ENUM -> "0"
            else -> "0"
        }
    }

    /** JVM-side wrapper conversion expression for a nullable primitive param (external param → call arg). */
    fun jvmNullableParamConversion(paramName: String, type: KneType.NULLABLE): String = when (type.inner) {
        KneType.BOOLEAN -> "if ($paramName != null) (if ($paramName) 1 else 0) else -1"
        KneType.INT -> "$paramName?.toLong() ?: Long.MIN_VALUE"
        KneType.LONG -> "$paramName ?: Long.MIN_VALUE"
        KneType.SHORT -> "$paramName?.toInt() ?: Int.MIN_VALUE"
        KneType.BYTE -> "$paramName?.toInt() ?: Int.MIN_VALUE"
        KneType.FLOAT -> "$paramName?.toRawBits()?.toLong() ?: Long.MIN_VALUE"
        KneType.DOUBLE -> "$paramName?.toRawBits() ?: Long.MIN_VALUE"
        is KneType.OBJECT, is KneType.INTERFACE -> "$paramName?.handle ?: 0L"
        is KneType.ENUM -> "$paramName?.ordinal ?: -1"
        else -> paramName
    }
}

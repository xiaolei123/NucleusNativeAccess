package dev.nucleusframework.nna.plugin.codegen.poet

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import dev.nucleusframework.nna.plugin.codegen.JniTypes
import dev.nucleusframework.nna.plugin.ir.KneParam
import dev.nucleusframework.nna.plugin.ir.KneType

/** Indentation used by every generated Kotlin file (KotlinPoet's default is two spaces). */
const val KNE_INDENT = "    "

/**
 * KotlinPoet type mapping for the JNI backend: translates [KneType] into [TypeName]s for the
 * generated JVM-side code.
 *
 * Every JVM-visible type (proxies, data classes, enums) is generated flat inside [jvmPackage],
 * so object/enum/data-class references map to same-package simple [ClassName]s regardless of
 * their original nesting — mirroring [JniTypes]' string-based `jvmTypeName` behaviour.
 *
 * Two views of a type are needed, mirroring the wire conventions documented on [JniTypes]:
 *  - [publicType]: the public API type as declared on proxy members (e.g. `Int?`, `(Int) -> Unit`).
 *  - [externalParamType]: the widened/expanded type an `external fun` actually declares
 *    (nullable primitives travel as widened values with sentinels; data classes and maps are
 *    flattened/expanded via [expandExternalParams]).
 */
class KnePoetTypes(private val jvmPackage: String) {

    /** ClassName of a type generated flat in the JVM package (proxy, data class, enum). */
    fun jvmClass(simpleName: String): ClassName = ClassName(jvmPackage, simpleName)

    /** ClassName of the nested KneRuntime.Lease generated in the JVM package. */
    fun runtimeLease(): ClassName = ClassName(jvmPackage, "KneRuntime", "Lease")

    /** The public API type as it appears on generated JVM proxy members. */
    fun publicType(type: KneType): TypeName = when (type) {
        KneType.INT -> INT
        KneType.LONG -> LONG
        KneType.DOUBLE -> DOUBLE
        KneType.FLOAT -> FLOAT
        KneType.BOOLEAN -> BOOLEAN
        KneType.BYTE -> BYTE
        KneType.SHORT -> SHORT
        KneType.STRING -> STRING
        KneType.UNIT -> UNIT
        KneType.BYTE_ARRAY -> BYTE_ARRAY
        is KneType.OBJECT -> jvmClass(type.simpleName)
        is KneType.INTERFACE -> jvmClass(type.simpleName)
        is KneType.ENUM -> jvmClass(type.simpleName)
        is KneType.DATA_CLASS -> jvmClass(type.simpleName)
        is KneType.FUNCTION -> lambdaOf(type)
        is KneType.LIST -> LIST.parameterizedBy(publicType(type.elementType))
        is KneType.SET -> SET.parameterizedBy(publicType(type.elementType))
        is KneType.MAP -> MAP.parameterizedBy(publicType(type.keyType), publicType(type.valueType))
        is KneType.FLOW -> FLOW.parameterizedBy(publicType(type.elementType))
        is KneType.NULLABLE -> publicType(type.inner).copy(nullable = true)
    }

    /** The Kotlin type used in the JVM `external fun` declaration (JNI-mappable wire view). */
    fun externalParamType(type: KneType): TypeName = when (type) {
        KneType.INT -> INT
        KneType.LONG -> LONG
        KneType.DOUBLE -> DOUBLE
        KneType.FLOAT -> FLOAT
        KneType.BOOLEAN -> BOOLEAN
        KneType.BYTE -> BYTE
        KneType.SHORT -> SHORT
        KneType.STRING -> STRING
        KneType.UNIT -> UNIT
        is KneType.OBJECT -> LONG
        is KneType.INTERFACE -> LONG
        is KneType.ENUM -> INT
        KneType.BYTE_ARRAY -> BYTE_ARRAY
        is KneType.FUNCTION -> ANY.copy(nullable = true)
        is KneType.DATA_CLASS -> error("data class params must be flattened, not mapped directly")
        is KneType.LIST -> arrayParamType(type.elementType)
        is KneType.SET -> arrayParamType(type.elementType)
        is KneType.MAP -> error("map params must be expanded into keys/values")
        is KneType.FLOW -> error("flow is only supported as a return type")
        is KneType.NULLABLE -> when (type.inner) {
            KneType.STRING -> STRING.copy(nullable = true)
            is KneType.OBJECT, is KneType.INTERFACE -> LONG
            is KneType.ENUM -> INT
            KneType.BOOLEAN -> INT
            KneType.INT, KneType.LONG -> LONG
            KneType.SHORT, KneType.BYTE -> INT
            KneType.FLOAT, KneType.DOUBLE -> LONG
            is KneType.DATA_CLASS -> error("nullable data class params must be flattened with _isNull prefix")
            is KneType.FUNCTION -> ANY.copy(nullable = true)
            KneType.BYTE_ARRAY -> BYTE_ARRAY.copy(nullable = true)
            is KneType.LIST -> arrayParamType(type.inner.elementType).copy(nullable = true)
            is KneType.SET -> arrayParamType(type.inner.elementType).copy(nullable = true)
            is KneType.MAP -> error("nullable map params must be expanded into keys/values")
            else -> error("unsupported nullable param type: $type")
        }
    }

    /** Array type for List<T>/Set<T> params. Nullability handled by callers. */
    fun arrayParamType(elementType: KneType): TypeName = when (elementType) {
        KneType.INT, is KneType.ENUM -> INT_ARRAY
        KneType.LONG, is KneType.OBJECT, is KneType.INTERFACE -> LONG_ARRAY
        KneType.DOUBLE -> DOUBLE_ARRAY
        KneType.FLOAT -> FLOAT_ARRAY
        KneType.SHORT -> SHORT_ARRAY
        KneType.BYTE -> BYTE_ARRAY
        KneType.BOOLEAN -> BOOLEAN_ARRAY
        KneType.STRING -> ARRAY.parameterizedBy(STRING.copy(nullable = true))
        KneType.BYTE_ARRAY -> ARRAY.parameterizedBy(BYTE_ARRAY.copy(nullable = true))
        is KneType.DATA_CLASS -> ARRAY.parameterizedBy(ANY.copy(nullable = true))
        is KneType.LIST, is KneType.SET, is KneType.MAP -> ARRAY.parameterizedBy(ANY.copy(nullable = true))
        else -> error("unsupported collection element type: $elementType")
    }

    /**
     * Expanded external-fun param list for a value of [name] with [type].
     * Returns (name, TypeName) pairs, flattened for DC / expanded for Map / ByteArray.
     */
    fun expandExternalParams(name: String, type: KneType): List<Pair<String, TypeName>> {
        val dc = JniTypes.extractDataClass(type)
        return when {
            dc != null -> {
                val fields = JniTypes.flattenDataClassFields(dc, name).map { (n, t) -> n to externalParamType(t) }
                if (type is KneType.NULLABLE) listOf("${name}_isNull" to INT) + fields else fields
            }
            type == KneType.BYTE_ARRAY -> listOf(name to BYTE_ARRAY)
            type is KneType.MAP || (type is KneType.NULLABLE && type.inner is KneType.MAP) -> {
                val inner = (if (type is KneType.NULLABLE) type.inner else type) as KneType.MAP
                val nullable = type is KneType.NULLABLE
                listOf(
                    "${name}_keys" to arrayParamType(inner.keyType).nullableIf(nullable),
                    "${name}_values" to arrayParamType(inner.valueType).nullableIf(nullable),
                )
            }
            else -> listOf(name to externalParamType(type))
        }
    }

    /** Convenience for building whole signatures from [KneParam]s. */
    fun expandExternalParams(params: List<KneParam>): List<Pair<String, TypeName>> =
        params.flatMap { expandExternalParams(it.name, it.type) }

    /** Lambda type name for a function type (public parameter/return views). */
    private fun lambdaOf(fn: KneType.FUNCTION): TypeName = LambdaTypeName.get(
        parameters = fn.paramTypes.map { publicType(it) }.toTypedArray(),
        returnType = publicType(fn.returnType),
    )

    private fun TypeName.nullableIf(nullable: Boolean): TypeName =
        if (nullable) copy(nullable = true) else this

    companion object {
        // Kotlin core type names (explicit, not relying on KotlinPoet's predefined constants)
        val INT = ClassName("kotlin", "Int")
        val LONG = ClassName("kotlin", "Long")
        val DOUBLE = ClassName("kotlin", "Double")
        val FLOAT = ClassName("kotlin", "Float")
        val BOOLEAN = ClassName("kotlin", "Boolean")
        val BYTE = ClassName("kotlin", "Byte")
        val SHORT = ClassName("kotlin", "Short")
        val STRING = ClassName("kotlin", "String")
        val UNIT = ClassName("kotlin", "Unit")
        val ANY = ClassName("kotlin", "Any")
        val BYTE_ARRAY = ClassName("kotlin", "ByteArray")
        val INT_ARRAY = ClassName("kotlin", "IntArray")
        val LONG_ARRAY = ClassName("kotlin", "LongArray")
        val DOUBLE_ARRAY = ClassName("kotlin", "DoubleArray")
        val FLOAT_ARRAY = ClassName("kotlin", "FloatArray")
        val SHORT_ARRAY = ClassName("kotlin", "ShortArray")
        val BOOLEAN_ARRAY = ClassName("kotlin", "BooleanArray")
        val ARRAY = ClassName("kotlin", "Array")
        val LIST = ClassName("kotlin.collections", "List")
        val SET = ClassName("kotlin.collections", "Set")
        val MAP = ClassName("kotlin.collections", "Map")
        val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
    }
}

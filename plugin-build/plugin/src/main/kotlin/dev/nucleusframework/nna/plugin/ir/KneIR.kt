package dev.nucleusframework.nna.plugin.ir

import java.io.Serializable

data class KneModule(
    val libName: String,
    val packages: Set<String>,
    val classes: List<KneClass>,
    val dataClasses: List<KneDataClass>,
    val enums: List<KneEnum>,
    val functions: List<KneFunction>,
    val interfaces: List<KneInterface> = emptyList(),
) : Serializable

data class KneDataClass(
    val simpleName: String,
    val fqName: String,
    val fields: List<KneParam>,
    val isCommon: Boolean = false,
    val source: KneSourceDecl? = null,
) : Serializable

/**
 * Verbatim source of a value-type declaration (enum / data class), copied as-is to the JVM side
 * so that the JVM variant matches the native one (body members, defaults, KDoc, annotations).
 */
data class KneSourceDecl(
    val text: String,
    val imports: List<String> = emptyList(),
) : Serializable

data class KneInterface(
    val simpleName: String,
    val fqName: String,
    val methods: List<KneFunction>,
    val properties: List<KneProperty>,
    val superInterfaces: List<String> = emptyList(),
    val isCommon: Boolean = false,
) : Serializable

data class KneClass(
    val simpleName: String,
    val fqName: String,
    val constructor: KneConstructor,
    val methods: List<KneFunction>,
    val properties: List<KneProperty>,
    val companionMethods: List<KneFunction> = emptyList(),
    val companionProperties: List<KneProperty> = emptyList(),
    val isOpen: Boolean = false,
    val isAbstract: Boolean = false,
    val isSealed: Boolean = false,
    val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    val sealedSubclasses: List<String> = emptyList(),
    val isCommon: Boolean = false,
) : Serializable

data class KneEnumEntry(
    val name: String,
    val constructorArgs: List<String> = emptyList(),
) : Serializable

data class KneEnum(
    val simpleName: String,
    val fqName: String,
    val entries: List<KneEnumEntry>,
    val constructorParams: List<KneParam> = emptyList(),
    val source: KneSourceDecl? = null,
) : Serializable

data class KneConstructor(
    val params: List<KneParam>,
) : Serializable

data class KneFunction(
    val name: String,
    val params: List<KneParam>,
    val returnType: KneType,
    val isSuspend: Boolean = false,
    val isExtension: Boolean = false,
    val receiverType: KneType? = null,
    val isOverride: Boolean = false,
) : Serializable

data class KneProperty(
    val name: String,
    val type: KneType,
    val mutable: Boolean,
    val isOverride: Boolean = false,
) : Serializable

data class KneParam(
    val name: String,
    val type: KneType,
    val hasDefault: Boolean = false,
) : Serializable

sealed class KneType : Serializable {
    data object INT : KneType()
    data object LONG : KneType()
    data object DOUBLE : KneType()
    data object FLOAT : KneType()
    data object BOOLEAN : KneType()
    data object BYTE : KneType()
    data object SHORT : KneType()
    data object STRING : KneType()
    data object UNIT : KneType()
    data class OBJECT(val fqName: String, val simpleName: String) : KneType()
    data class INTERFACE(val fqName: String, val simpleName: String) : KneType()
    data class ENUM(val fqName: String, val simpleName: String) : KneType()
    data class NULLABLE(val inner: KneType) : KneType()
    data class FUNCTION(val paramTypes: List<KneType>, val returnType: KneType) : KneType()
    data class DATA_CLASS(val fqName: String, val simpleName: String, val fields: List<KneParam>) : KneType()
    data object BYTE_ARRAY : KneType()
    data class LIST(val elementType: KneType) : KneType()
    data class SET(val elementType: KneType) : KneType()
    data class MAP(val keyType: KneType, val valueType: KneType) : KneType()
    data class FLOW(val elementType: KneType) : KneType()

    /** Preserves singleton identity when deserialized from the incremental cache. */
    protected fun readResolve(): Any = when (this) {
        INT -> INT
        LONG -> LONG
        DOUBLE -> DOUBLE
        FLOAT -> FLOAT
        BOOLEAN -> BOOLEAN
        BYTE -> BYTE
        SHORT -> SHORT
        STRING -> STRING
        UNIT -> UNIT
        BYTE_ARRAY -> BYTE_ARRAY
        else -> this
    }

    /** Kotlin/JVM type name as it appears in generated JVM code. */
    val jvmTypeName: String
        get() = when (this) {
            INT -> "Int"
            LONG -> "Long"
            DOUBLE -> "Double"
            FLOAT -> "Float"
            BOOLEAN -> "Boolean"
            BYTE -> "Byte"
            SHORT -> "Short"
            STRING -> "String"
            UNIT -> "Unit"
            is OBJECT -> simpleName
            is INTERFACE -> simpleName
            is ENUM -> simpleName
            is NULLABLE -> if (inner is FUNCTION) "(${inner.jvmTypeName})?" else "${inner.jvmTypeName}?"
            is FUNCTION -> "(${paramTypes.joinToString(", ") { it.jvmTypeName }}) -> ${returnType.jvmTypeName}"
            is DATA_CLASS -> simpleName
            BYTE_ARRAY -> "ByteArray"
            is LIST -> "List<${elementType.jvmTypeName}>"
            is SET -> "Set<${elementType.jvmTypeName}>"
            is MAP -> "Map<${keyType.jvmTypeName}, ${valueType.jvmTypeName}>"
            is FLOW -> "kotlinx.coroutines.flow.Flow<${elementType.jvmTypeName}>"
        }

}

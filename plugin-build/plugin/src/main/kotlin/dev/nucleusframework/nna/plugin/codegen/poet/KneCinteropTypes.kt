package dev.nucleusframework.nna.plugin.codegen.poet

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

/**
 * Shared cinterop [TypeName] references used by the JNI native-bridge generators.
 *
 * KotlinPoet resolves imports from the [TypeName]s a `FileSpec` references, so the bridge no
 * longer relies on star-imports: every JNI-facing type is referenced through the binding flavor
 * ([JniBindingFlavor]) that the variant compiles against.
 */
object KneCinteropTypes {

    // kotlinx.cinterop surface (identical package on every binding flavor)
    val C_POINTER = ClassName("kotlinx.cinterop", "CPointer")
    val BYTE_VAR = ClassName("kotlinx.cinterop", "ByteVar")
    val SHORT_VAR = ClassName("kotlinx.cinterop", "ShortVar")
    val INT_VAR = ClassName("kotlinx.cinterop", "IntVar")
    val LONG_VAR = ClassName("kotlinx.cinterop", "LongVar")
    val FLOAT_VAR = ClassName("kotlinx.cinterop", "FloatVar")
    val DOUBLE_VAR = ClassName("kotlinx.cinterop", "DoubleVar")
    val STABLE_REF = ClassName("kotlinx.cinterop", "StableRef")
    val MEM_SCOPE = ClassName("kotlinx.cinterop", "MemScope")

    /** `CPointer<T>?` for a pointee var type (the common nullable-pointer shape). */
    fun pointerTo(varType: ClassName): TypeName = C_POINTER.parameterizedBy(varType).copy(nullable = true)

    /** `CPointer<T>` for a pointee var type. */
    fun nonNullPointerTo(varType: ClassName): TypeName = C_POINTER.parameterizedBy(varType)
}

/**
 * Which JNI binding surface a native bridge variant compiles against. Both wrap the same jni.h;
 * they differ in the cinterop package exposing it and the function-table struct name (the NDK
 * header has no trailing underscore).
 */
enum class JniBindingFlavor(val packageName: String, val jniInterfaceStruct: String) {
    /** Desktop native targets: the generated knejni cinterop over the local JDK's jni.h. */
    DESKTOP("dev.nucleusframework.knejni", "JNINativeInterface_"),

    /** androidNative targets: Kotlin/Native's built-in bindings over the NDK jni.h. */
    ANDROID("platform.android", "JNINativeInterface"),
    ;

    val envVar: ClassName = ClassName(packageName, "JNIEnvVar")
    val jniInterface: ClassName = ClassName(packageName, jniInterfaceStruct)
    val javaVmVar: ClassName = ClassName(packageName, "JavaVMVar")
    val jobject: ClassName = ClassName(packageName, "jobject")
    val jclass: ClassName = ClassName(packageName, "jclass")
    val jstring: ClassName = ClassName(packageName, "jstring")
    val jmethodId: ClassName = ClassName(packageName, "jmethodID")
    val jfieldId: ClassName = ClassName(packageName, "jfieldID")
    val jvalue: ClassName = ClassName(packageName, "jvalue")
    val jniNativeMethod: ClassName = ClassName(packageName, "JNINativeMethod")
}

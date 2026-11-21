package dev.nucleusframework.nna.plugin.codegen

import dev.nucleusframework.nna.plugin.ir.KneModule

/**
 * Generates GraalVM reachability metadata for the JNI bridge backend.
 *
 * JNI mode has no FFM foreign downcall/upcall descriptors; instead the classes the native
 * side touches through `JNIEnv` (proxy classes, KotlinNativeException, data classes built
 * via NewObjectA, callback/suspend/flow dispatchers) are registered in the `jni` section,
 * alongside the usual reflection and resource entries.
 */
fun generateGraalVmMetadataJni(
    resources: MutableMap<String, String>,
    module: KneModule,
    jvmPackage: String,
) {
    val metaDir = "META-INF/native-image/kne/${module.libName}"

    val classNames = mutableListOf<String>()
    classNames.add("$jvmPackage.KneRuntime")
    classNames.add("$jvmPackage.KotlinNativeException")
    classNames.add("$jvmPackage.KneNativeBindings")
    module.classes.filter { !it.isCommon }.forEach { classNames.add("$jvmPackage.${it.simpleName}") }
    module.dataClasses.filter { !it.isCommon }.forEach { classNames.add("$jvmPackage.${it.simpleName}") }
    module.enums.forEach { classNames.add("$jvmPackage.${it.simpleName}") }
    module.interfaces.filter { !it.isCommon }.forEach { classNames.add("$jvmPackage.${it.simpleName}") }
    if (module.functions.any { !it.isExtension }) {
        classNames.add("$jvmPackage.${module.libName.replaceFirstChar { it.uppercaseChar() }}")
    }
    if (module.functions.any { it.isExtension && it.receiverType != null }) {
        classNames.add("$jvmPackage.KneExt")
    }

    // Callback / suspend / flow dispatcher classes live in KneRuntime (same FQN)
    // and are registered wholesale via the KneRuntime entry above.

    // reflect-config.json
    val reflectEntries = classNames.joinToString(",\n") { name ->
        """  {
    "name": "$name",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }"""
    }
    resources["$metaDir/reflect-config.json"] = "[\n$reflectEntries\n]\n"

    // resource-config.json
    resources["$metaDir/resource-config.json"] = """{
  "resources": {
    "includes": [
      { "pattern": "\\Qkne/native/\\E.*" }
    ]
  }
}
"""

    // reachability-metadata.json — reflection + jni (no foreign section in JNI mode)
    val classEntries = classNames.joinToString(",\n") {
        """    { "type": "$it", "allDeclaredConstructors": true, "allDeclaredMethods": true, "allDeclaredFields": true }"""
    }
    resources["$metaDir/reachability-metadata.json"] = """{
  "reflection": [
$classEntries
  ],
  "resources": [
    { "glob": "kne/native/**" }
  ],
  "jni": [
$classEntries
  ]
}
"""
}

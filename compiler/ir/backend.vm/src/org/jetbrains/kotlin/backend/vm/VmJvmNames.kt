/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import java.io.File
import org.jetbrains.kotlin.backend.jvm.ir.getJvmNameFromAnnotation
import org.jetbrains.kotlin.backend.jvm.lower.getFileClassInfoFromIrFile
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isTopLevel
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils

/*
 * The names a function crosses between the VM and the JVM by: `jvm:`, the JVM class that holds the method, a dot, the
 * method's name and its descriptor, as the JVM backend generates them for the same declaration.
 */

private const val JVM_BINDING_PREFIX = "jvm:"

/** A Kotlin type the descriptor covers, by its qualified name: the descriptor of its non-null type, and its box's internal name. */
private class JvmScalar(val descriptor: String, val box: String)

private val JVM_SCALARS = mapOf(
    "kotlin.Int" to JvmScalar("I", "java/lang/Integer"),
    "kotlin.Long" to JvmScalar("J", "java/lang/Long"),
    "kotlin.Short" to JvmScalar("S", "java/lang/Short"),
    "kotlin.Byte" to JvmScalar("B", "java/lang/Byte"),
    "kotlin.Char" to JvmScalar("C", "java/lang/Character"),
    "kotlin.Boolean" to JvmScalar("Z", "java/lang/Boolean"),
    "kotlin.Float" to JvmScalar("F", "java/lang/Float"),
    "kotlin.Double" to JvmScalar("D", "java/lang/Double"),
)

private const val STRING_DESCRIPTOR = "Ljava/lang/String;"

private fun IrType.jvmParameterDescriptor(): String? {
    val name = classFqName?.asString() ?: return null
    if (name == "kotlin.String") return STRING_DESCRIPTOR
    val scalar = JVM_SCALARS[name] ?: return null
    return if (isNullable()) "L${scalar.box};" else scalar.descriptor
}

private fun IrType.jvmReturnDescriptor(): String? = if (isUnit()) "V" else if (isNothing()) null else jvmParameterDescriptor()

/**
 * The binding name of the public top-level [function], held by the JVM class [owner] (an internal name), or null for what
 * this does not cover: a function that is not public, one with a suspend or generic-typed signature, and any type but the
 * primitives, `String` and, as a result, `Unit`.
 */
internal fun jvmBindingName(function: IrSimpleFunction, owner: String): String? {
    if (!function.isTopLevel || function.visibility != DescriptorVisibilities.PUBLIC || function.isSuspend) return null
    val parameters = function.nonDispatchParameters.map { it.type.jvmParameterDescriptor() ?: return null }
    val result = function.returnType.jvmReturnDescriptor() ?: return null
    val name = function.getJvmNameFromAnnotation() ?: function.name.asString()
    return "$JVM_BINDING_PREFIX$owner.$name(${parameters.joinToString("")})$result"
}

/** The internal name of the JVM class holding the top-level functions of [this], or null when they are in a multifile class's parts. */
internal fun IrFile.jvmFacadeName(): String? {
    val info = getFileClassInfoFromIrFile(this, File(fileEntry.name).name)
    return if (info.withJvmMultifileClass) null else info.fileClassFqName.asString().replace('.', '/')
}

/**
 * The internal name of the class holding the `actual`s of the `expect` declarations of this file, by the platform file
 * convention: the file `Name.kt` has its `actual`s in `Name.jvm.kt`, which the JVM compiles to `Name_jvmKt`.
 */
internal fun IrFile.jvmActualFacadeName(): String {
    val actualFileName = "${File(fileEntry.name).nameWithoutExtension}.jvm.kt"
    return PackagePartClassUtils.getPackagePartFqName(packageFqName, actualFileName).asString().replace('.', '/')
}

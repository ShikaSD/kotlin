/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import java.io.File
import org.jetbrains.kotlin.backend.jvm.InlineClassAbi
import org.jetbrains.kotlin.backend.jvm.hasMangledParameters
import org.jetbrains.kotlin.backend.jvm.hasMangledReturnType
import org.jetbrains.kotlin.backend.jvm.ir.getJvmNameFromAnnotation
import org.jetbrains.kotlin.backend.jvm.ir.inlineClassRepresentation
import org.jetbrains.kotlin.backend.jvm.lower.getFileClassInfoFromIrFile
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.load.java.BuiltinSpecialProperties
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.java.SpecialGenericSignatures
import org.jetbrains.kotlin.load.kotlin.internalName
import org.jetbrains.kotlin.load.kotlin.FacadeClassSource
import org.jetbrains.kotlin.ir.declarations.IrMemberWithContainerSource
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.isTopLevel
import org.jetbrains.kotlin.ir.util.nonDispatchParameters

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
    // A type parameter is erased to its first bound, as the JVM backend erases it.
    (classifierOrNull as? IrTypeParameterSymbol)?.let { return it.owner.superTypes.first().jvmParameterDescriptor() }
    val name = classFqName?.asString() ?: return null
    if (name == "kotlin.String") return STRING_DESCRIPTOR
    if (name == "kotlin.Any") return "Ljava/lang/Object;"
    JVM_SCALARS[name]?.let { scalar -> return if (isNullable()) "L${scalar.box};" else scalar.descriptor }
    val irClass = classOrNull?.owner ?: return null
    irClass.jvmMappedInternalName?.let { return "L$it;" }
    if (isFunction()) irClass.functionInterfaceArity()?.let { return "L${jvmFunctionInterfaceName(it)};" }
    // A value class is its underlying type, but where that cannot be null, as a nullable one over a primitive is its box.
    if (irClass.isValue) {
        if (irClass.inlineClassRepresentation == null) return null
        return InlineClassAbi.unboxType(this)?.jvmParameterDescriptor() ?: "L${irClass.jvmInternalName};"
    }
    // Kotlin's other classes are the JDK's on the JVM, under names this does not map, but for `kotlin.reflect`'s, which
    // keep theirs.
    val packageName = irClass.packageFqName
    if (packageName?.startsWith(StandardNames.BUILT_INS_PACKAGE_NAME) == true && packageName != StandardNames.KOTLIN_REFLECT_FQ_NAME) return null
    return "L${irClass.jvmInternalName};"
}

private fun IrType.jvmReturnDescriptor(): String? = if (isUnit()) "V" else if (isNothing()) null else jvmParameterDescriptor()

/**
 * The internal name of the Java interface the JVM has [this], a collection interface of Kotlin's, as: both
 * `kotlin.collections.List` and `MutableList` are `java/util/List`. Null for any other class.
 */
val IrClass.jvmMappedInternalName: String?
    get() {
        if (!isInterface) return null
        val name = fqNameWhenAvailable ?: return null
        val mapped = JavaToKotlinClassMap.mutabilityMappings.firstOrNull {
            it.kotlinReadOnly.asSingleFqName() == name || it.kotlinMutable.asSingleFqName() == name
        } ?: return null
        return mapped.javaClass.internalName
    }

/**
 * The binding name of the Java method [function], a member of a collection interface of Kotlin's, stands for on the
 * JVM: held by its interface's Java one, under the name the JVM gives it, as `size` for the property and `remove` for
 * `removeAt`. Null where [jvmBindingName] would be.
 */
fun jvmMappedMemberName(function: IrSimpleFunction): String? {
    val owner = function.parentClassOrNull?.jvmMappedInternalName ?: return null
    val described = jvmBindingName(function, owner, function.name.asString(), "") ?: return null
    val descriptor = described.substring(described.indexOf('('))
    val property = function.correspondingPropertySymbol?.owner
    val name = when {
        property != null -> BuiltinSpecialProperties.PROPERTY_FQ_NAME_TO_JVM_GETTER_NAME_MAP[property.fqNameWhenAvailable]?.asString()
            ?: function.jvmName() ?: return null
        else -> SpecialGenericSignatures.SIGNATURE_TO_JVM_REPRESENTATION_NAME["$owner.${function.name}$descriptor"]?.asString()
            ?: function.name.asString()
    }
    return "$JVM_BINDING_PREFIX$owner.$name$descriptor"
}

/** The JVM's function interface of [arity], which the VM's of that arity stands for; see [IrClass.functionInterfaceArity]. */
fun jvmFunctionInterfaceName(arity: Int): String = "kotlin/jvm/functions/Function$arity"

/** The binding name of the erased `invoke` of the JVM's function interface of [arity]. */
fun jvmFunctionInvokeName(arity: Int): String =
    "$JVM_BINDING_PREFIX${jvmFunctionInterfaceName(arity)}.invoke(${"Ljava/lang/Object;".repeat(arity)})Ljava/lang/Object;"

/** The JVM's internal name of [this] class: its package's path, then its name after those of the classes nesting it, joined by `$`. */
val IrClass.jvmInternalName: String
    get() {
        val nesting = generateSequence(this) { it.parent as? IrClass }.toList().asReversed()
        val packageName = getPackageFragment().packageFqName
        val simpleNames = nesting.joinToString("$") { it.name.asString() }
        return if (packageName.isRoot) simpleNames else "${packageName.asString().replace('.', '/')}/$simpleNames"
    }

/**
 * The binding name of [function], held by the JVM class [owner] (an internal name), or null for what this does not
 * cover: a function that is neither public nor protected nor internal and top-level, one with a suspend or generic-typed signature, and any type
 * but the primitives, `String`, `Any`, the classes outside `kotlin`, and, as a result, `Unit`. A constructor is `<init>`,
 * and an accessor has its JVM name, `getX` or `setX`.
 */
internal fun jvmBindingName(function: IrFunction, owner: String): String? =
    jvmBindingName(function, owner, function.jvmName() ?: return null, "")

/**
 * The binding name of the static method filling in the default arguments of [function], a top-level function the JVM
 * class [owner] holds: `name$default`, taking its parameters, an `int` mask and an unused `Object`. Null where
 * [jvmBindingName] would be.
 */
internal fun jvmDefaultsBindingName(function: IrFunction, owner: String): String? =
    jvmBindingName(function, owner, "${function.jvmName() ?: return null}${JvmAbi.DEFAULT_PARAMS_IMPL_SUFFIX}", "ILjava/lang/Object;")

private fun jvmBindingName(function: IrFunction, owner: String, name: String, trailing: String): String? {
    // An internal top-level function is public on the JVM under its own name, as Compose's plugin calls one of its
    // runtime's; an internal member's name carries its module's.
    val isInternalTopLevel = function.visibility == DescriptorVisibilities.INTERNAL && function.parentClassOrNull == null
    if (function.visibility != DescriptorVisibilities.PUBLIC && function.visibility != DescriptorVisibilities.PROTECTED && !isInternalTopLevel) {
        return null
    }
    if (function is IrSimpleFunction && function.isSuspend) return null
    val parameters = function.nonDispatchParameters.map { it.type.jvmParameterDescriptor() ?: return null }
    val result = if (function is IrConstructor) "V" else function.returnType.jvmReturnDescriptor() ?: return null
    return "$JVM_BINDING_PREFIX$owner.$name(${parameters.joinToString("")}$trailing)$result"
}

/**
 * The name the JVM gives [this]'s method, or null for a constructor taking a value class, which the JVM gives a marker
 * parameter instead. A function taking a value class, or a member returning one, is mangled with a hash of its signature;
 * a top-level one returning one is not, as its facade's members are not.
 */
private fun IrFunction.jvmName(): String? {
    if (this is IrConstructor) return if (hasMangledParameters()) null else "<init>"
    getJvmNameFromAnnotation()?.let { return it }
    val isMember = parentClassOrNull != null
    if (hasMangledParameters() || isMember && hasMangledReturnType) {
        return InlineClassAbi.mangledNameFor(this, mangleReturnTypes = isMember, useOldMangleRules = false).asString()
    }
    val property = (this as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: return name.asString()
    val propertyName = property.name.asString()
    return if (this == property.setter) JvmAbi.setterName(propertyName) else JvmAbi.getterName(propertyName)
}

/**
 * The binding name of the static method filling in the default arguments of [function], a member of the JVM class
 * [owner] holds: `name$default`, taking the receiver, its parameters, an `int` mask and an unused `Object`. Null where
 * [jvmBindingName] would be.
 */
internal fun jvmMemberDefaultsBindingName(function: IrFunction, owner: IrClass): String? {
    val described = jvmDefaultsBindingName(function, owner.jvmInternalName) ?: return null
    val open = described.indexOf('(') + 1
    return described.substring(0, open) + "L${owner.jvmInternalName};" + described.substring(open)
}

/** The binding name of the static [field] of the JVM class [owner], or null for a type the descriptor does not cover. */
internal fun jvmStaticFieldName(field: IrField, owner: IrClass): String? =
    field.type.jvmParameterDescriptor()?.let { "jvm-field:${owner.jvmInternalName}.${field.name.asString()}:$it" }

/**
 * The binding name of the static field holding the object [declaration]: `jvm-field:`, the class holding it, `.`, its
 * name and `:` its type. A companion is a field of its [outer] class named after it, and any other object its own
 * `INSTANCE`.
 */
internal fun jvmObjectFieldName(declaration: IrClass, outer: IrClass?): String {
    val type = "L${declaration.jvmInternalName};"
    return if (outer != null) {
        "jvm-field:${outer.jvmInternalName}.${declaration.name.asString()}:$type"
    } else {
        "jvm-field:${declaration.jvmInternalName}.${JvmAbi.INSTANCE_FIELD}:$type"
    }
}

/**
 * The name a module the VM runs exports the public top-level [function] by, which the JVM calls it by; null when
 * [jvmBindingName] does not cover it, or its file is a multifile class's part.
 */
fun jvmExportName(function: IrSimpleFunction): String? {
    if (!function.isTopLevel || function.visibility != DescriptorVisibilities.PUBLIC) return null
    return jvmBindingName(function, function.fileOrNull?.jvmFacadeName() ?: return null)
}

/** The name the JVM calls a member or constructor of a class by; null when [jvmBindingName] does not cover it. */
fun jvmMemberName(function: IrFunction): String? = jvmBindingName(function, function.parentClassOrNull?.jvmInternalName ?: return null)

/** The name of [function], a member of a JVM class, called by a `super` call: [jvmMemberName]'s, `jvm-super:` in place of `jvm:`. */
internal fun jvmSuperMemberName(function: IrFunction): String? =
    jvmMemberName(function)?.let { "jvm-super:" + it.removePrefix(JVM_BINDING_PREFIX) }

/**
 * The name of the pair constructor of the shell of [declaration], a class extending the JVM class [superConstructor]
 * constructs: `jvm-pair:`, the shell's internal name, `<init>`, and [superConstructor]'s parameters, which the
 * constructor takes after a `vm.bridge.VmPaired` and the VM half's handle. Null where [jvmBindingName] would be.
 */
internal fun jvmPairConstructorName(declaration: IrClass, superConstructor: IrConstructor): String? =
    jvmBindingName(superConstructor, declaration.jvmInternalName)?.let { "jvm-pair:" + it.removePrefix(JVM_BINDING_PREFIX) }

/**
 * The internal name of the JVM class holding [function], a top-level function of a JVM library: its file's facade, or
 * the multifile class its file is a part of, which declares every part's public functions. Null for any other function.
 */
internal fun jvmLibraryFacadeName(function: IrFunction): String? {
    if (!function.isTopLevel) return null
    val source = (function as? IrMemberWithContainerSource)?.containerSource as? FacadeClassSource ?: return null
    return (source.facadeClassName ?: source.className).internalName
}

/** The internal name of the JVM class holding the top-level functions of [this], or null when they are in a multifile class's parts. */
internal fun IrFile.jvmFacadeName(): String? {
    val info = getFileClassInfoFromIrFile(this, File(fileEntry.name).name)
    return if (info.withJvmMultifileClass) null else info.fileClassFqName.asString().replace('.', '/')
}

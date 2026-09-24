/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.vm.vmir.*
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrSyntheticBody
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

class VmCodegenException(message: String) : RuntimeException(message)

/** How a call reaches its callee, and the signature it calls it with. */
internal class CallTarget(val callee: VmCallee, val symbol: VmFunctionSymbol)

internal fun unsupported(what: String): Nothing = throw VmCodegenException("the VM backend does not support $what yet")

/** The module the VM repository's runtime/src/runtime.ir defines: the root class, the string class and their selectors. */
const val VM_RUNTIME_MODULE = "vm.runtime"

/**
 * The JVM's throwables the runtime implements, by the runtime class standing for each. The Kotlin names are the
 * standard library's own classes; its other exceptions are aliases of the Java ones. The VM raises the runtime classes
 * under `vm.` names itself, which is what makes a `catch` of the JVM class catch the VM's failure.
 */
private val RUNTIME_THROWABLES = mapOf(
    "kotlin.Throwable" to "Throwable",
    "java.lang.Throwable" to "Throwable",
    "java.lang.Exception" to "Exception",
    "java.lang.Error" to "Error",
    "java.lang.AssertionError" to "AssertionError",
    "java.lang.RuntimeException" to "RuntimeException",
    "java.lang.IllegalStateException" to "IllegalStateException",
    "java.lang.IllegalArgumentException" to "IllegalArgumentException",
    "java.lang.NumberFormatException" to "NumberFormatException",
    "java.lang.UnsupportedOperationException" to "UnsupportedOperationException",
    "java.lang.IndexOutOfBoundsException" to "IndexOutOfBoundsException",
    "java.util.NoSuchElementException" to "NoSuchElementException",
    "kotlin.NoWhenBranchMatchedException" to "NoWhenBranchMatchedException",
    "kotlin.UninitializedPropertyAccessException" to "UninitializedPropertyAccessException",
    "java.lang.NullPointerException" to "vm.NullReference",
    "java.lang.ArithmeticException" to "vm.DivisionByZero",
    "java.lang.ClassCastException" to "vm.ClassCast",
    "java.lang.ArrayIndexOutOfBoundsException" to "vm.ArrayIndexOutOfBounds",
    "java.lang.StringIndexOutOfBoundsException" to "vm.StringIndexOutOfBounds",
)

/**
 * Generates one VM module from a linked, unlowered IR module fragment.
 *
 * Covers top-level functions and classes over primitives and string literals; anything else is reported through
 * [VmCodegenException] rather than approximated.
 *
 * Every reference type but `String` is held as the runtime's `Any`: registers are untyped, and one type for all of them
 * keeps an override's signature equal to its selector's however its declared types narrow. A method takes its
 * signature from the declaration it overrides for the same reason, which leaves an override that turns a primitive
 * into a reference, and so needs a boxing bridge, as the one mismatch to report.
 */
class VmModuleGenerator(internal val irBuiltIns: IrBuiltIns, moduleName: String) {
    private val module = VmModule(moduleName)
    private val runtimeClasses = hashMapOf<String, Int>()
    private val runtimeSelectors = hashMapOf<String, Int>()
    private val functionSymbols = hashMapOf<IrFunctionSymbol, VmFunctionSymbol>()
    private val definedFunctions = hashSetOf<IrFunctionSymbol>()
    private val classIndices = hashMapOf<IrClassSymbol, Int>()
    private val fieldLayouts = hashMapOf<IrClassSymbol, List<LayoutField>>()
    private val selectors = hashMapOf<IrSimpleFunctionSymbol, Int>()
    private val defaultToStrings = hashMapOf<IrClassSymbol, VmFunctionSymbol>()
    private val globals = hashMapOf<IrFieldSymbol, Int>()
    private val objectInstances = hashMapOf<IrClassSymbol, Int>()
    private val enumEntries = hashMapOf<IrEnumEntrySymbol, Int>()
    private val functionOwners = hashMapOf<IrFunctionSymbol, VmOwner>()

    fun generate(fragment: IrModuleFragment): VmModule {
        val functions = mutableListOf<IrFunction>()
        val classes = mutableListOf<IrClass>()
        val fileMembers = mutableMapOf<IrFile, FileMembers>()
        for (file in fragment.files) {
            val members = fileMembers.getOrPut(file) { FileMembers() }
            for (declaration in file.declarations) {
                when (declaration) {
                    is IrSimpleFunction -> if (!declaration.isOnlyInlined()) members.functions += declaration
                    is IrProperty -> {
                        members.properties += declaration
                        listOfNotNull(declaration.getter, declaration.setter).filterTo(members.functions) { !it.isOnlyInlined() }
                    }
                    is IrClass -> collectClass(declaration, classes, functions)
                    is IrTypeAlias -> {}
                    else -> unsupported("the declaration ${declaration.render()}")
                }
            }
            functions += members.functions
        }
        for (function in functions) {
            val isAbstract = function is IrSimpleFunction && function.modality == Modality.ABSTRACT
            if (function.body == null && !isAbstract) unsupported("the declaration ${function.render()}")
        }
        val bodies = functions.filter { it.body != null }
        bodies.mapTo(definedFunctions) { it.symbol }

        for (declaration in classes) {
            classIndices[declaration.symbol] = module.classes.size
            // Replaced once every class has an index, which supertypes and methods refer to.
            module.classes += VmClass(declaration.vmName, null, emptyList(), false, false, VmClassKind.Interface)
        }
        val initializers = fileMembers.mapNotNull { declareFileGlobals(it.key, it.value) } + declareClassGlobals(classes)
        for (declaration in classes) {
            for (function in declaration.memberFunctions()) {
                if (!function.isFakeOverride && function.overriddenSymbols.isEmpty() && function.isOverridable()) {
                    val symbol = functionSymbol(function)
                    module.selectors += VmSelector(
                        symbol.name, null, classIndices.getValue(declaration.symbol), symbol.paramTypes.drop(1), symbol.returnType,
                    )
                    selectors[function.symbol] = module.selectors.size - 1
                }
            }
        }

        // The VM starts a program from its entry module's first function.
        functions.singleOrNull(::isBox)?.let { module.functions += boxEntry(it as IrSimpleFunction) }
        bodies.mapTo(module.functions) { VmFunctionGenerator(this, it).generate() }
        initializers.forEach { it() }
        for (declaration in classes) {
            module.classes[classIndices.getValue(declaration.symbol)] = classDefinition(declaration)
        }
        return module
    }

    private class FileMembers {
        val functions = mutableListOf<IrSimpleFunction>()
        val properties = mutableListOf<IrProperty>()
    }

    // Globals.

    /**
     * Declares a file's top-level properties as globals. A property whose initializer is a primitive constant starts as
     * that constant; the rest are computed by the file's initializer, in declaration order. As on the JVM, where they are
     * a facade class's statics, the initializer runs on the first use of the file: a load or store of one of those
     * globals, or a call to one of its functions. Returns what generates the initializer, if the file needs one.
     */
    private fun declareFileGlobals(file: IrFile, members: FileMembers): (() -> Unit)? {
        val fields = members.properties.mapNotNull { it.backingField }
        val computed = fields.filter { it.initializer != null && constantStart(it) == null }
        val owner = if (computed.isEmpty()) null else VmOwner.Class(fileOwner(file))
        for (field in fields) {
            val start = when {
                constantStart(field) != null -> VmGlobalStart.Constant(constantStart(field)!!)
                field.initializer == null -> VmGlobalStart.Zero
                else -> VmGlobalStart.Owned(owner!!)
            }
            val name = sanitized(field.correspondingPropertySymbol?.owner?.fqNameWhenAvailable?.asString() ?: field.name.asString())
            globals[field.symbol] = declareGlobal(name, vmType(field.type) ?: unsupported("a Unit property"), start)
        }
        if (owner == null) return null
        members.functions.forEach { functionOwners[it.symbol] = owner }
        val symbol = declareInitializer("${module.classes[owner.classIndex].name}::init", owner)
        return { module.functions += VmFunctionGenerator.fileInitializer(this, symbol, computed) }
    }

    /** The bits a global starts as when its field's initializer is a constant a scalar global can hold. */
    private fun constantStart(field: IrField): Long? {
        val initializer = field.initializer?.expression as? IrConst ?: return null
        if (vmType(field.type) is VmType.Ref) return null
        return when (initializer.kind) {
            IrConstKind.Boolean -> if (initializer.value as Boolean) 1L else 0L
            IrConstKind.Char -> (initializer.value as Char).code.toLong()
            IrConstKind.Byte, IrConstKind.Short, IrConstKind.Int, IrConstKind.Long -> (initializer.value as Number).toLong()
            IrConstKind.Double -> (initializer.value as Double).toRawBits()
            IrConstKind.Null, IrConstKind.String, IrConstKind.Float -> null
        }
    }

    /** What stands for the file's facade class as the owner of its initializer: an interface nothing implements. */
    private fun fileOwner(file: IrFile): Int {
        val simpleName = file.fileEntry.name.substringAfterLast('/').removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
        val packageName = file.packageFqName
        val name = if (packageName.isRoot) simpleName else "${packageName.asString()}.$simpleName"
        module.classes += VmClass(sanitized(name), null, emptyList(), isOpen = true, isAbstract = true, VmClassKind.Interface)
        return module.classes.size - 1
    }

    private fun declareGlobal(name: String, type: VmType, start: VmGlobalStart): Int {
        module.globals += VmGlobal(name, type, start)
        return module.globals.size - 1
    }

    private fun declareInitializer(name: String, owner: VmOwner): VmFunctionSymbol {
        val symbol = VmFunctionSymbol(name, emptyList(), null)
        // Nothing tracks what an initializer depends on yet, so a reload would treat every one as unchanged.
        module.initializers += VmInitializer(symbol, owner, fingerprint = 0)
        return symbol
    }

    /**
     * Declares the globals classes own and the initializer that creates them: each enum class's entries, then each
     * object's `INSTANCE`. The enum's entries are its initializer's to create, as on the JVM, so a use of the class runs
     * it. An object's instance is stored before its constructor runs, so the constructor sees it. A companion's belongs
     * to its outer class, as it is that class's static on the JVM: creating the outer class creates the companion.
     */
    private fun declareClassGlobals(classes: List<IrClass>): List<() -> Unit> {
        val parts = LinkedHashMap<IrClass, MutableList<IrClass>>()
        for (declaration in classes) {
            val outer = declaration.parent as? IrClass
            when {
                declaration.kind == ClassKind.ENUM_CLASS -> parts.getOrPut(declaration) { mutableListOf() }.add(0, declaration)
                declaration.kind != ClassKind.OBJECT -> {}
                declaration.isCompanion && outer != null -> parts.getOrPut(outer) { mutableListOf() } += declaration
                else -> parts.getOrPut(declaration) { mutableListOf() } += declaration
            }
        }
        return parts.map { entry ->
            val ownerClass = entry.key
            val owner = VmOwner.Class(classIndices.getValue(ownerClass.symbol))
            for (declaration in entry.value) {
                if (declaration.kind == ClassKind.ENUM_CLASS) {
                    for (enumEntry in declaration.declarations.filterIsInstance<IrEnumEntry>()) {
                        val name = "${declaration.vmName}::${enumEntry.name.asString()}"
                        enumEntries[enumEntry.symbol] = declareGlobal(name, anyType, VmGlobalStart.Owned(owner))
                    }
                } else {
                    objectInstances[declaration.symbol] = declareGlobal("${declaration.vmName}::INSTANCE", anyType, VmGlobalStart.Owned(owner))
                }
            }
            val symbol = declareInitializer("${ownerClass.vmName}::init", owner)
            return@map { module.functions += VmFunctionGenerator.classInitializer(this, symbol, entry.value) }
        }
    }

    internal fun enumEntryGlobal(entry: IrEnumEntry): Int =
        enumEntries[entry.symbol] ?: unsupported("the enum entry ${entry.name} from another module")

    internal fun globalOf(field: IrField): Int = globals[field.symbol] ?: unsupported("the static field ${field.name} from another module")

    internal fun objectInstance(declaration: IrClass): Int =
        objectInstances[declaration.symbol] ?: unsupported("the object ${declaration.name} from another module")

    /** The owner whose initializer a static call to [function] runs first, if any. */
    internal fun ownerOf(function: IrFunction): VmOwner? = functionOwners[function.symbol]

    private fun collectClass(declaration: IrClass, classes: MutableList<IrClass>, functions: MutableList<IrFunction>) {
        when {
            declaration.kind == ClassKind.ANNOTATION_CLASS ->
                unsupported("the ${declaration.kind.codeRepresentation ?: declaration.kind.name} ${declaration.name}")
            declaration.isInner -> unsupported("the inner class ${declaration.name}")
            declaration.isValue -> unsupported("the value class ${declaration.name}")
        }
        classes += declaration
        for (member in declaration.declarations) {
            when (member) {
                is IrConstructor -> functions += member
                is IrSimpleFunction -> if (member.isGenerated()) functions += member
                is IrProperty -> {
                    listOfNotNull(member.getter, member.setter).filterTo(functions) { it.isGenerated() }
                    if (member.backingField?.isStatic == true) unsupported("the static property ${member.name}")
                }
                is IrAnonymousInitializer -> if (member.isStatic) unsupported("a static initializer in ${declaration.name}")
                is IrField -> if (member.isStatic) unsupported("the static field ${member.name}")
                is IrClass -> collectClass(member, classes, functions)
                is IrEnumEntry -> member.correspondingClass?.let { collectClass(it, classes, functions) }
                else -> unsupported("the member ${member.render()}")
            }
        }
    }

    /**
     * Every call to an inline function is inlined — [lowerForVm] inlines the module's own and a klib's alike — so its
     * body is never called, and one with a reified type parameter could not even be generated.
     */
    private fun IrSimpleFunction.isOnlyInlined(): Boolean = isInline

    /**
     * Whether the function gets code of its own: not a fake override, nor inlined everywhere, nor one of an enum's
     * `values`, `valueOf` and `entries`, whose bodies the frontend leaves for a lowering to synthesize.
     */
    private fun IrSimpleFunction.isGenerated(): Boolean = !isFakeOverride && !isOnlyInlined() && body !is IrSyntheticBody

    private fun isBox(function: IrFunction): Boolean =
        function is IrSimpleFunction && function.name.asString() == "box" && function.dispatchReceiverParameter == null &&
                function.parameters.isEmpty() && function.returnType.isString()

    /** Runs `box()` and answers 1 when it returned "OK", which is what a codegen box test checks. */
    private fun boxEntry(box: IrSimpleFunction): VmFunction {
        val entry = VmFunction(VmFunctionSymbol("main", emptyList(), VmType.I64), owner = null)
        val block = entry.newBlock()
        val result = entry.newValue(stringType)
        val ok = entry.newValue(stringType)
        val equal = entry.newValue(VmType.I64)
        block.instructions += VmInstruction.Call(result, VmCallee.Static(functionSymbol(box)), emptyList())
        block.instructions += VmInstruction.StringConstant(ok, "OK")
        block.instructions += VmInstruction.Call(equal, VmCallee.Virtual(equalsSelector), listOf(result, ok))
        block.terminator = VmTerminator.Return(equal)
        return entry
    }

    private fun runtimeClass(name: String): Int = runtimeClasses.getOrPut(name) {
        module.classes += VmClass(name, null, emptyList(), isOpen = false, isAbstract = false, VmClassKind.Imported(VM_RUNTIME_MODULE))
        module.classes.size - 1
    }

    internal val anyType: VmType get() = VmType.Ref(runtimeClass("Any"))
    internal val stringType: VmType get() = VmType.Ref(runtimeClass("vm.String"))

    /** The class the VM raises for a null dereference, which Kotlin throws for a failed null check too. */
    internal val nullPointerClass: Int get() = runtimeClass("vm.NullReference")

    internal val throwableClass: Int get() = runtimeClass("Throwable")

    /** A runtime class by the name the runtime declares it under. */
    internal fun runtimeClassNamed(name: String): Int = runtimeClass(name)

    internal val toStringSelector: Int get() = runtimeSelector("toString")

    /** A selector the runtime declares on `Any`, as it declares it. */
    private fun runtimeSelector(name: String): Int = runtimeSelectors.getOrPut(name) {
        val any = runtimeClass("Any")
        module.selectors += when (name) {
            "equals" -> VmSelector(name, VM_RUNTIME_MODULE, any, listOf(anyType), VmType.I64)
            "hashCode" -> VmSelector(name, VM_RUNTIME_MODULE, any, emptyList(), VmType.I64)
            "toString" -> VmSelector(name, VM_RUNTIME_MODULE, any, emptyList(), stringType)
            "message" -> VmSelector(name, VM_RUNTIME_MODULE, runtimeClass("Throwable"), emptyList(), stringType)
            "cause" -> VmSelector(name, VM_RUNTIME_MODULE, runtimeClass("Throwable"), emptyList(), anyType)
            else -> error("The runtime declares no selector $name on Any")
        }
        module.selectors.size - 1
    }

    internal val equalsSelector: Int get() = runtimeSelector("equals")

    /** The runtime class implementing [declaration], one of [RUNTIME_THROWABLES], or null for any other class. */
    internal fun runtimeThrowable(declaration: IrClass): Int? =
        RUNTIME_THROWABLES[declaration.fqNameWhenAvailable?.asString()]?.let(::runtimeClass)

    /** The runtime function storing what one of the JVM's `Throwable` constructors takes. */
    internal val throwableInit: VmFunctionSymbol by lazy {
        runtimeFunction("Throwable::init", listOf(anyType, stringType, anyType), null)
    }

    /** Kotlin's `String.plus(Any?)`; see `@String::plus` in the runtime. */
    internal val stringPlus: VmFunctionSymbol by lazy { runtimeFunction("String::plus", listOf(stringType, anyType), stringType) }

    private fun runtimeFunction(name: String, params: List<VmType>, returnType: VmType?): VmFunctionSymbol {
        val symbol = VmFunctionSymbol(name, params, returnType)
        if (module.imports.none { it.symbol == symbol }) module.imports += VmImport(VM_RUNTIME_MODULE, symbol)
        return symbol
    }

    /** The runtime selector a member of `Throwable` dispatches through, by the member's Kotlin or Java name. */
    private fun throwableSelector(name: String): Int? = when (name) {
        "<get-message>", "getMessage", "getLocalizedMessage" -> runtimeSelector("message")
        "<get-cause>", "getCause" -> runtimeSelector("cause")
        "toString" -> runtimeSelector("toString")
        else -> null
    }

    /** How a value of [type] is held, or null for a type with no values to hold: `Unit` and `Nothing`. */
    internal fun vmType(type: IrType): VmType? {
        if (type.isUnit() || type.isNothing()) return null
        if (type.isPrimitiveType()) {
            return when (type.getPrimitiveType()!!) {
                PrimitiveType.DOUBLE -> VmType.F64
                PrimitiveType.FLOAT -> unsupported("Float, which needs 32-bit floats in the VM")
                PrimitiveType.BOOLEAN, PrimitiveType.CHAR, PrimitiveType.BYTE, PrimitiveType.SHORT,
                PrimitiveType.INT, PrimitiveType.LONG -> VmType.I64
            }
        }
        if (type.isNullablePrimitiveType()) unsupported("the boxed type ${type.render()}")
        if (type.isString() || type.isNullableString()) return stringType
        return anyType
    }

    // Classes.

    internal fun classIndex(declaration: IrClass): Int? = classIndices[declaration.symbol]

    /** The class a type test or cast against [type] names, or null for `Any`, which every object is. */
    internal fun testedClass(type: IrType): Int? {
        if (type.isAny() || type.isNullableAny()) return null
        if (type.isString() || type.isNullableString()) return runtimeClass("vm.String")
        val declaration = type.classOrNull?.owner ?: unsupported("a type test against ${type.render()}")
        return classIndex(declaration) ?: runtimeThrowable(declaration)
            ?: unsupported("a type test against ${type.render()} from another module")
    }

    private fun IrClass.superClass(): IrClass? =
        superTypes.mapNotNull { it.classOrNull?.owner }.firstOrNull { !it.isInterface }

    internal fun IrClass.isAny() = symbol == irBuiltIns.anyClass

    /** `kotlin.Enum`, which the runtime's `Enum` implements. */
    internal fun IrClass.isEnumBase() = symbol == irBuiltIns.enumClass

    /** A field of a class's VM layout: one it declares, or one the runtime class it extends lays out. */
    private class LayoutField(val name: String, val type: VmType, val declared: IrField?)

    /** The class's fields in the VM's layout: its superclass's, then its own. */
    private fun fieldLayout(declaration: IrClass): List<LayoutField> = fieldLayouts.getOrPut(declaration.symbol) {
        val superClass = declaration.superClass()
        val inherited = when {
            superClass == null || superClass.isAny() -> emptyList()
            superClass.isEnumBase() -> listOf(LayoutField("name", stringType, null), LayoutField("ordinal", VmType.I64, null))
            runtimeThrowable(superClass) != null -> listOf(LayoutField("message", stringType, null), LayoutField("cause", anyType, null))
            classIndex(superClass) != null -> fieldLayout(superClass)
            else -> unsupported("the superclass ${superClass.name} from another module")
        }
        inherited + declaration.declarations.mapNotNull {
            val field = when (it) {
                is IrProperty -> it.backingField
                is IrField -> it
                else -> null
            } ?: return@mapNotNull null
            LayoutField(field.name.asString(), vmType(field.type) ?: unsupported("a Unit field"), field)
        }
    }

    internal fun fieldIndex(field: IrField): Int {
        val owner = field.parentAsClass
        if (classIndex(owner) == null) unsupported("the field ${field.name} of ${owner.name} from another module")
        return fieldLayout(owner).indexOfFirst { it.declared == field }
    }

    /** The name the JVM gives the class, which its default `toString` shows. */
    private val IrClass.jvmName: String
        get() {
            val nesting = generateSequence(this) { it.parent as? IrClass }.toList().asReversed()
            val packageName = getPackageFragment().packageFqName
            val simpleNames = nesting.joinToString("$") { it.name.asString() }
            return if (packageName.isRoot) simpleNames else "${packageName.asString()}.$simpleNames"
        }

    private val IrClass.vmName: String
        get() = sanitized(fqNameWhenAvailable?.asString() ?: unsupported("the local class $name"))

    private fun classDefinition(declaration: IrClass): VmClass {
        val interfaces = declaration.superTypes.mapNotNull { it.classOrNull?.owner }.filter { it.isInterface }.map {
            classIndex(it) ?: unsupported("the supertype ${it.name} from another module")
        }
        if (declaration.isInterface) {
            return VmClass(declaration.vmName, null, interfaces, isOpen = true, isAbstract = true, VmClassKind.Interface)
        }
        val superClass = declaration.superClass()
        val superIndex = when {
            superClass == null || superClass.isAny() -> runtimeClass("Any")
            superClass.isEnumBase() -> runtimeClass("Enum")
            runtimeThrowable(superClass) != null -> runtimeThrowable(superClass)!!
            else -> classIndex(superClass) ?: unsupported("the superclass ${superClass.name} from another module")
        }
        val fields = fieldLayout(declaration).map { VmField(it.name, it.type) }
        val isAbstract = declaration.modality == Modality.ABSTRACT || declaration.modality == Modality.SEALED
        return VmClass(
            declaration.vmName, superIndex, interfaces,
            isOpen = declaration.modality != Modality.FINAL, isAbstract = isAbstract,
            VmClassKind.Class(fields, methods(declaration, isAbstract)),
        )
    }

    /**
     * What answers each selector applying to [declaration], inherited implementations included: a superclass's
     * vtable covers the selectors it knows, but not those of an interface only this class implements.
     */
    private fun methods(declaration: IrClass, isAbstract: Boolean): List<VmMethod> {
        val methods = LinkedHashSet<VmMethod>()
        for (function in declaration.memberFunctions()) {
            // An abstract declaration resolves to itself, and leaves the selector to a subclass.
            val implementation = function.resolveFakeOverride()?.takeIf { it.modality != Modality.ABSTRACT } ?: continue
            // Only an instance member answers a selector, which leaves out an enum's `values` and `valueOf`.
            if (implementation.dispatchReceiverParameter == null) continue
            val implementationSymbol = when {
                // Answered by the runtime's `Enum`, or by `Any` through it.
                implementation.parentClassOrNull?.isEnumBase() == true -> continue
                // Answered by the runtime's throwable, except its name, which only the class's own code has.
                implementation.parentClassOrNull?.let(::runtimeThrowable) != null -> {
                    if (implementation.name.asString() != "toString" || isAbstract) continue
                    throwableToString(declaration)
                }
                implementation.parentClassOrNull?.isAny() != true -> {
                    if (implementation.symbol !in definedFunctions) unsupported("inheriting ${implementation.render()} from another module")
                    functionSymbol(implementation)
                }
                implementation.name.asString() == "toString" && !isAbstract -> defaultToString(declaration)
                else -> continue // Answered by the runtime's `Any`.
            }
            for (root in function.roots()) {
                val selector = selectorOf(root) ?: continue
                val declared = module.selectors[selector]
                if (declared.paramTypes != implementationSymbol.paramTypes.drop(1) || declared.returnType != implementationSymbol.returnType) {
                    unsupported("a bridge from ${root.render()} to ${implementation.render()}")
                }
                methods += VmMethod(selector, implementationSymbol)
            }
        }
        return methods.toList()
    }

    /** `Name: message`, for a throwable whose `toString` is still `Throwable`'s; see `@Throwable::describe`. */
    private fun throwableToString(declaration: IrClass): VmFunctionSymbol = defaultToStrings.getOrPut(declaration.symbol) {
        val symbol = VmFunctionSymbol("${declaration.vmName}::toString", listOf(anyType), stringType)
        val describe = runtimeFunction("Throwable::describe", listOf(anyType, stringType), stringType)
        val function = VmFunction(symbol, owner = null)
        val self = function.newValue(anyType)
        function.params += self
        val name = function.newValue(stringType)
        val result = function.newValue(stringType)
        val block = function.newBlock()
        block.instructions += VmInstruction.StringConstant(name, declaration.jvmName)
        block.instructions += VmInstruction.Call(result, VmCallee.Static(describe), listOf(self, name))
        block.terminator = VmTerminator.Return(result)
        module.functions += function
        symbol
    }

    /** `Name@hash`, for a class whose `toString` is still `Any`'s; see `@Any::defaultToString` in the runtime. */
    private fun defaultToString(declaration: IrClass): VmFunctionSymbol = defaultToStrings.getOrPut(declaration.symbol) {
        val symbol = VmFunctionSymbol("${declaration.vmName}::toString", listOf(anyType), stringType)
        val helper = runtimeFunction("Any::defaultToString", listOf(stringType, VmType.I64), stringType)

        val function = VmFunction(symbol, owner = null)
        val self = function.newValue(anyType)
        function.params += self
        val hash = function.newValue(VmType.I64)
        val name = function.newValue(stringType)
        val result = function.newValue(stringType)
        val block = function.newBlock()
        block.instructions += VmInstruction.Call(hash, VmCallee.Virtual(runtimeSelector("hashCode")), listOf(self))
        block.instructions += VmInstruction.StringConstant(name, declaration.jvmName)
        block.instructions += VmInstruction.Call(result, VmCallee.Static(helper), listOf(name, hash))
        block.terminator = VmTerminator.Return(result)
        module.functions += function
        symbol
    }

    // Functions and calls.

    private fun IrClass.memberFunctions(): List<IrSimpleFunction> = declarations.flatMap {
        when (it) {
            is IrSimpleFunction -> listOf(it)
            is IrProperty -> listOfNotNull(it.getter, it.setter)
            else -> emptyList()
        }
    }

    /** The declarations this one overrides that override nothing themselves, in a stable order. */
    private fun IrSimpleFunction.roots(): List<IrSimpleFunction> =
        if (overriddenSymbols.isEmpty()) listOf(this) else overriddenSymbols.flatMap { it.owner.roots() }.distinct()

    private fun IrSimpleFunction.isOverridable(): Boolean {
        val owner = parentClassOrNull ?: return false
        return modality != Modality.FINAL && owner.modality != Modality.FINAL &&
                !DescriptorVisibilities.isPrivate(visibility) && owner.kind != ClassKind.OBJECT
    }

    /** The selector dispatching a call to [root], or null when nothing can override it. */
    private fun selectorOf(root: IrSimpleFunction): Int? {
        if (root.parentClassOrNull?.isAny() == true) return runtimeSelector(root.name.asString())
        if (root.parentClassOrNull?.let(::runtimeThrowable) != null) return throwableSelector(root.name.asString())
        if (!root.isOverridable()) return null
        return selectors[root.symbol] ?: unsupported("overriding ${root.render()} from another module")
    }

    /** How a call reaches [callee]: by selector when it may be overridden, directly otherwise. */
    internal fun callTarget(callee: IrSimpleFunction, isSuperCall: Boolean): CallTarget {
        if (callee.dispatchReceiverParameter == null) return staticTarget(callee)
        val root = callee.roots().first()
        if (!isSuperCall && callee.isOverridable()) {
            val selector = selectorOf(root) ?: unsupported("calling ${root.render()}")
            return CallTarget(VmCallee.Virtual(selector), functionSymbol(root))
        }
        val implementation = callee.resolveFakeOverride() ?: unsupported("calling the abstract ${callee.render()}")
        if (implementation.parentClassOrNull?.let(::runtimeThrowable) != null) {
            val selector = throwableSelector(implementation.name.asString())
            if (isSuperCall || selector == null) unsupported("calling ${implementation.render()}")
            return CallTarget(VmCallee.Virtual(selector), functionSymbol(implementation))
        }
        if (implementation.parentClassOrNull?.isEnumBase() == true) {
            val name = implementation.name.asString()
            if (isSuperCall || name !in setOf("toString", "equals", "hashCode")) unsupported("calling ${implementation.render()}")
            return CallTarget(VmCallee.Virtual(runtimeSelector(name)), functionSymbol(implementation))
        }
        if (implementation.parentClassOrNull?.isAny() == true) {
            if (isSuperCall) unsupported("calling ${implementation.render()} through super")
            return CallTarget(VmCallee.Virtual(runtimeSelector(implementation.name.asString())), functionSymbol(implementation))
        }
        return staticTarget(implementation)
    }

    internal fun staticTarget(callee: IrFunction): CallTarget {
        if (callee.symbol !in definedFunctions) unsupported("a call to ${callee.render()} outside the module")
        val symbol = functionSymbol(callee)
        return CallTarget(VmCallee.Static(symbol), symbol)
    }

    /**
     * The function's VM signature. A member takes its receiver as `Any` and the rest from the declaration it overrides;
     * a constructor takes the object it initializes.
     */
    internal fun functionSymbol(function: IrFunction): VmFunctionSymbol = functionSymbols.getOrPut(function.symbol) {
        fun types(parameters: List<IrValueParameter>) =
            parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
                .map { vmType(it.type) ?: unsupported("the parameter type ${it.type.render()}") }

        when {
            function is IrConstructor -> VmFunctionSymbol(vmName(function), listOf(anyType) + types(function.parameters), null)
            function is IrSimpleFunction && function.dispatchReceiverParameter != null -> {
                val root = function.roots().first()
                val params = types(root.parameters)
                val returnType = vmType(root.returnType)
                val own = types(function.parameters) + listOfNotNull(vmType(function.returnType))
                if (own.map { it is VmType.Ref } != (params + listOfNotNull(returnType)).map { it is VmType.Ref }) {
                    unsupported("a bridge from ${root.render()} to ${function.render()}")
                }
                VmFunctionSymbol(vmName(function), listOf(anyType) + params, returnType)
            }
            else -> VmFunctionSymbol(vmName(function), types(function.parameters), vmType(function.returnType))
        }
    }

    /**
     * A function's name in the VM: its qualified name — a member's as `Class::name` — then the hash its public signature
     * carries, which is what tells overloads apart.
     */
    private fun vmName(function: IrFunction): String {
        val owner = function.parentClassOrNull
        val base = if (owner != null) "${owner.vmName}::${function.name.asString()}"
        else function.fqNameWhenAvailable?.asString() ?: function.name.asString()
        val hash = (function.symbol.signature as? IdSignature.CommonSignature)?.id
        return sanitized(if (hash == null) base else "${base}_${java.lang.Long.toHexString(hash)}")
    }

    /** Keeps only what an IR text name may hold, so a dump reads back in. */
    private fun sanitized(name: String): String = buildString(name.length) {
        var i = 0
        while (i < name.length) {
            if (name.startsWith("::", i)) {
                append("::")
                i += 2
                continue
            }
            val c = name[i++]
            append(if (c.isLetterOrDigit() || c == '_' || c == '.') c else '_')
        }
    }
}

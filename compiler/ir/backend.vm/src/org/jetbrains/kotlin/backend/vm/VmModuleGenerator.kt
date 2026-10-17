/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.backend.vm.vmir.*
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.InlineClassRepresentation
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrSyntheticBody
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

class VmCodegenException(message: String) : RuntimeException(message)

/** How a call reaches its callee, and the signature it calls it with. */
internal class CallTarget(val callee: VmCallee, val symbol: VmFunctionSymbol)

/**
 * What [this] overrides in the VM: an `expect` class's member is the JVM's, which the JVM dispatches to the shell of an
 * override, so an override of one alone declares its own selector, if it has one.
 */
fun IrSimpleFunction.vmOverridden(): List<IrSimpleFunction> =
    overriddenSymbols.map { it.owner }.filter { (it.parent as? IrClass)?.isExpect != true }

/** The declarations [this] overrides in the VM that override nothing themselves, in a stable order; see [vmOverridden]. */
fun IrSimpleFunction.vmRoots(): List<IrSimpleFunction> =
    vmOverridden().let { overridden -> if (overridden.isEmpty()) listOf(this) else overridden.flatMap { it.vmRoots() }.distinct() }

/**
 * Whether a JVM object may implement [this] interface, so the VM cannot know every implementation: one of Kotlin's
 * collection interfaces, which the JVM has as Java's, or, with [hasProxies], one the proxies have, as a public interface
 * nested in public classes only.
 */
internal fun IrClass.isHostInterface(hasProxies: Boolean): Boolean =
    jvmMappedInternalName != null || hasProxies && generateSequence(this) { it.parent as? IrClass }.all {
        it.visibility == DescriptorVisibilities.PUBLIC || it.visibility == DescriptorVisibilities.PROTECTED
    }

internal fun unsupported(what: String): Nothing = throw VmCodegenException("the VM backend does not support $what yet")

/** A `Float`'s bits as a register holds them: in the low half, the high half zero. */
internal fun floatBits(value: Float): Long = value.toRawBits().toLong() and 0xFFFF_FFFFL

/**
 * The standard library's types the runtime declares, by the runtime class standing for each: the JVM class the Kotlin
 * type is compiled to, whose simple name the runtime gives its class in its own namespace, `vm.runtime`, so no Java name
 * reaches the VM.
 */
private val RUNTIME_TYPES = mapOf(
    "kotlin.Number" to "vm.runtime.Number",
    "kotlin.Comparable" to "vm.runtime.Comparable",
    "kotlin.CharSequence" to "vm.runtime.CharSequence",
    "java.lang.StringBuilder" to "vm.runtime.StringBuilder",
)

/**
 * Whether [declaration] is part of the VM standard library's mirror of the runtime, `platform-src/vm`: an external
 * declaration in a `vm` package, which is the runtime's declaration of the same qualified name.
 */
internal fun isRuntimeMirror(declaration: IrDeclarationWithName): Boolean =
    (declaration as? IrPossiblyExternalDeclaration)?.isExternal == true &&
            declaration.fqNameWhenAvailable?.pathSegments()?.firstOrNull()?.asString() == "vm"

/** Whether [this] is a function type, suspending or not: every function value is the same runtime object. */
internal fun IrType.isFunctionValueType(): Boolean = isFunctionOrKFunction() || isSuspendFunctionOrKFunction()

/**
 * The arity of [this] when it is a function type a class may implement, `kotlin.FunctionN` or `kotlin.reflect.KFunctionN`,
 * which the VM's function interface of that arity stands for; see "Function interfaces" in wip/FUNCTION_VALUES_DESIGN.md.
 */
internal fun IrClass.functionInterfaceArity(): Int? {
    // `kotlin.Function` is every function type's supertype, of no arity.
    if (!defaultType.isFunctionOrKFunction() || fqNameWhenAvailable?.asString() == "kotlin.Function") return null
    return typeParameters.size - 1
}

/**
 * The JVM's throwables the runtime implements, by the runtime class standing for each. The Kotlin names are the
 * standard library's own classes; its other exceptions are aliases of the Java ones. The VM raises the `vm.builtin.` classes
 * itself, which is what makes a `catch` of the JVM class catch the VM's failure.
 */
private val RUNTIME_THROWABLES = mapOf(
    "kotlin.Throwable" to "vm.runtime.Throwable",
    "kotlin.NoWhenBranchMatchedException" to "kotlin.NoWhenBranchMatchedException",
    "kotlin.UninitializedPropertyAccessException" to "kotlin.UninitializedPropertyAccessException",
    "java.lang.NullPointerException" to "vm.builtin.NullReference",
    "java.lang.ArithmeticException" to "vm.builtin.DivisionByZero",
    "java.lang.ClassCastException" to "vm.builtin.ClassCast",
    "java.lang.ArrayIndexOutOfBoundsException" to "vm.builtin.ArrayIndexOutOfBounds",
    "java.lang.StringIndexOutOfBoundsException" to "vm.builtin.StringIndexOutOfBounds",
) + listOf(
    "java.lang.Throwable",
    "java.lang.Exception",
    "java.lang.Error",
    "java.lang.AssertionError",
    "java.lang.RuntimeException",
    "java.lang.IllegalStateException",
    "java.lang.IllegalArgumentException",
    "java.lang.NumberFormatException",
    "java.lang.UnsupportedOperationException",
    "java.lang.IndexOutOfBoundsException",
    "java.util.NoSuchElementException",
    "java.util.ConcurrentModificationException",
    "java.lang.OutOfMemoryError",
).associateWith { "vm.runtime.${it.substringAfterLast('.')}" }

/**
 * Generates one VM module from a linked, unlowered IR module fragment.
 *
 * Covers top-level functions and classes over primitives and string literals; anything else is reported through
 * [VmCodegenException] rather than approximated.
 *
 * Every reference type but `String` is held as the runtime's `Any`: registers are untyped, and one type for all of them
 * keeps an override's signature equal to its selector's however its declared types narrow. A method takes its
 * signature from the declaration it overrides for the same reason; where the override declares a primitive and that
 * declaration a reference, the method's own body unboxes and boxes, which is all a bridge would do.
 */
class VmModuleGenerator(
    internal val irBuiltIns: IrBuiltIns,
    moduleName: String,
    /** The libraries the module is compiled against, which tell another VM module's declarations from the host's. */
    private val libraries: VmLibraries,
    /**
     * Whether the module is the standard library itself. Its declarations the runtime implements are left out, as are
     * its intrinsics, which have no bodies; and a function it cannot generate yet becomes a stub throwing
     * `UnsupportedOperationException`, reported through [reportStub], rather than failing the module, so a program can
     * link against the rest.
     */
    private val isStdlib: Boolean = false,
    /**
     * The internal name of the JVM facade holding the `actual` of an `expect` function, for each the JVM implements. A
     * call of one is a host import; see [hostImport].
     */
    private val hostActuals: (IrSimpleFunction) -> String? = { null },
    /** Told of each declaration of the standard library generated as a stub; see [isStdlib]. */
    private val reportStub: (String) -> Unit = {},
    /** Whether the module is compiled with proxies, whose interfaces a JVM class may implement; see [isHostInterface]. */
    private val hasProxies: Boolean = false,
) {
    private val module = VmModule(moduleName)
    private lateinit var fragment: IrModuleFragment
    private val runtimeClasses = hashMapOf<String, Int>()
    private val runtimeSelectors = hashMapOf<String, Int>()
    private val functionSymbols = hashMapOf<IrFunctionSymbol, VmFunctionSymbol>()
    private val definedFunctions = hashSetOf<IrFunctionSymbol>()

    /** The JVM names two functions of the module share, as two extensions differing only in a bound can; see [exportName]. */
    private val clashingExportNames = hashMapOf<String, Int>()
    private val classIndices = hashMapOf<IrClassSymbol, Int>()
    private val ownFields = hashMapOf<IrClassSymbol, List<IrField>>()
    private val selectors = hashMapOf<IrSimpleFunctionSymbol, Int>()
    private val throwableToStrings = hashMapOf<IrClassSymbol, VmFunctionSymbol>()
    private val arrayClasses = hashMapOf<VmType, Int>()
    private val globals = hashMapOf<IrFieldSymbol, Int>()
    private val objectInstances = hashMapOf<IrClassSymbol, Int>()
    private val enumEntries = hashMapOf<IrEnumEntrySymbol, Int>()
    private val functionOwners = hashMapOf<IrFunctionSymbol, VmOwner>()


    fun generate(fragment: IrModuleFragment): VmModule {
        this.fragment = fragment
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
                    // The JVM's `actual` is the class; the VM declares none of its own.
                    is IrClass -> if (!declaration.isExpect) stubbing(declaration) { collectClass(declaration, classes, functions) }
                    is IrTypeAlias -> {}
                    else -> unsupported("the declaration ${declaration.render()}")
                }
            }
            functions += members.functions
        }
        // Imported whether called or not, so every link checks the whole mirror against the runtime's signatures.
        functions.filter(::isRuntimeMirror).forEach(::staticTarget)
        functions.retainAll { function ->
            val isAbstract = function is IrSimpleFunction && function.modality == Modality.ABSTRACT
            when {
                function.body != null || isAbstract -> true
                isRuntimeMirror(function) -> false
                // Bound to the JVM's `actual` by the host; see [hostImport].
                function is IrSimpleFunction && function.isExpect && function.isTopLevel -> false
                isStdlib -> false
                else -> unsupported("the declaration ${function.render()}")
            }
        }
        val bodies = functions.filter { it.body != null }
        bodies.mapTo(definedFunctions) { it.symbol }
        bodies.mapNotNull(::jvmExportNameOf).groupingBy { it }.eachCount().filterTo(clashingExportNames) { it.value > 1 }

        // The classes other modules can name first, so no local class takes a name one of them has.
        classes.sortedBy { libraries.publicSignature(it) !is IdSignature.CommonSignature }.forEach { it.vmName }
        for (declaration in classes) {
            classIndices[declaration.symbol] = module.classes.size
            // Replaced once every class has an index, which supertypes and methods refer to.
            module.classes += VmClass(declaration.vmName, null, emptyList(), false, false, VmClassKind.Interface)
        }
        val initializers = fileMembers.mapNotNull { declareFileGlobals(it.key, it.value) } + declareClassGlobals(classes)
        for (declaration in classes) {
            for (function in declaration.memberFunctions()) {
                // A member with no body that is not abstract is a Java one the JVM maps onto a Kotlin class, which
                // nothing on the VM answers or calls; see [methods].
                val answerable = function.body != null || function.modality == Modality.ABSTRACT
                if (!function.isFakeOverride && function.overriddenSymbols.isEmpty() && function.isOverridable() && answerable) {
                    val symbol = functionSymbol(function)
                    module.selectors += VmSelector(
                        selectorName(function), imported = false, classIndices.getValue(declaration.symbol), symbol.paramTypes.drop(1), symbol.returnType,
                    )
                    selectors[function.symbol] = module.selectors.size - 1
                }
            }
        }

        // The VM starts a program from its entry module's first function.
        functions.singleOrNull(::isBox)?.let { module.functions += boxEntry(it as IrSimpleFunction) }
        for (function in bodies) {
            stubbing(function) { module.functions += VmFunctionGenerator(this, function).generate().also { it.export = exportName(function) } }
                ?: stub(function)?.let(module.functions::add)
        }
        for (function in functions.filter { function -> function.parameters.any { it.defaultValue != null } }) {
            val symbol = stubbing(function) { defaultStub(function) } ?: continue
            module.functions += stubbing(function) { VmFunctionGenerator.defaultStub(this, function, symbol) }
                ?: VmFunctionGenerator.unsupportedStub(this, symbol, "the defaults of ${function.fqNameWhenAvailable ?: function.name}")
        }
        initializers.forEach { it() }
        for (declaration in classes) {
            module.classes[classIndices.getValue(declaration.symbol)] = stubbing(declaration) { classDefinition(declaration) }
                ?: if (declaration.isInterface) {
                    VmClass(declaration.vmName, null, emptyList(), isOpen = true, isAbstract = true, VmClassKind.Interface)
                } else {
                    VmClass(declaration.vmName, runtimeClass("Any"), emptyList(), isOpen = true, isAbstract = true, VmClassKind.Class(emptyList(), emptyList()))
                }
        }
        return module
    }

    /**
     * Runs [generate] for [declaration], and answers what it did; in the standard library, answers null instead when it
     * is something the backend does not support, reporting it.
     */
    private fun <T> stubbing(declaration: IrDeclaration, generate: () -> T): T? {
        if (!isStdlib) return generate()
        return try {
            generate()
        } catch (e: VmCodegenException) {
            reportStub("${declaration.fileOrNull?.name}: ${declaration.render()}: ${e.message}")
            null
        }
    }

    /**
     * A function of the standard library standing in for one the backend cannot generate: it throws, saying so. Null when
     * not even the function's signature can be generated, which leaves out every caller too.
     */
    private fun stub(function: IrFunction): VmFunction? {
        val symbol = stubbing(function) { functionSymbol(function) } ?: return null
        return VmFunctionGenerator.unsupportedStub(this, symbol, "${function.fqNameWhenAvailable ?: function.name}")
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
        for (field in fields.filter(::hasStorage)) {
            val start = when {
                constantStart(field) != null -> VmGlobalStart.Constant(constantStart(field)!!)
                field.initializer == null -> VmGlobalStart.Zero
                else -> VmGlobalStart.Owned(owner!!)
            }
            globals[field.symbol] = declareGlobal(definedGlobalName(field), heldType(field.type), start)
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
            IrConstKind.Float -> floatBits(initializer.value as Float)
            IrConstKind.Null, IrConstKind.String -> null
        }
    }

    /** What stands for the file's facade class as the owner of its initializer: an interface nothing implements. */
    private fun fileOwner(file: IrFile): Int {
        val simpleName = file.fileEntry.name.substringAfterLast('/').removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
        val packageName = file.packageFqName
        val name = if (packageName.isRoot) simpleName else "${packageName.asString()}.$simpleName"
        module.classes += VmClass(vmSanitized(name), null, emptyList(), isOpen = true, isAbstract = true, VmClassKind.Interface)
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
     * object's `INSTANCE`, then the static fields it computes. The enum's entries are its initializer's to create, as on
     * the JVM, so a use of the class runs it. An object's instance is stored before its constructor runs, so the
     * constructor sees it. A companion's belongs to its outer class, as it is that class's static on the JVM: creating
     * the outer class creates the companion.
     *
     * Only a plugin gives a class static fields, as Compose gives each its `$stable` and a singleton lambda's holder. One
     * whose initializer is a constant starts as it, and one without starts as zero, so neither needs the initializer.
     */
    private fun declareClassGlobals(classes: List<IrClass>): List<() -> Unit> {
        val parts = LinkedHashMap<IrClass, MutableList<IrClass>>()
        val computed = HashMap<IrClass, List<IrField>>()
        for (declaration in classes) {
            val outer = declaration.parent as? IrClass
            when {
                declaration.kind == ClassKind.ENUM_CLASS -> parts.getOrPut(declaration) { mutableListOf() }.add(0, declaration)
                declaration.kind != ClassKind.OBJECT -> {}
                declaration.isCompanion && outer != null -> parts.getOrPut(outer) { mutableListOf() } += declaration
                else -> parts.getOrPut(declaration) { mutableListOf() } += declaration
            }
            val statics = declaration.properties.mapNotNull { it.backingField }.filter { it.isStatic }.toList()
            for (field in statics.filter { it.initializer == null || constantStart(it) != null }) {
                val start = constantStart(field)?.let(VmGlobalStart::Constant) ?: VmGlobalStart.Zero
                globals[field.symbol] = declareGlobal(definedGlobalName(field), heldType(field.type), start)
            }
            statics.filter { it.initializer != null && constantStart(it) == null }.takeIf { it.isNotEmpty() }?.let { fields ->
                computed[declaration] = fields
                parts.getOrPut(declaration) { mutableListOf() }
            }
        }
        return parts.map { entry ->
            val ownerClass = entry.key
            val owner = VmOwner.Class(classIndices.getValue(ownerClass.symbol))
            val fields = computed[ownerClass].orEmpty()
            for (field in fields) {
                globals[field.symbol] = declareGlobal(definedGlobalName(field), heldType(field.type), VmGlobalStart.Owned(owner))
            }
            for (declaration in entry.value) {
                if (declaration.kind == ClassKind.ENUM_CLASS) {
                    for (enumEntry in declaration.declarations.filterIsInstance<IrEnumEntry>()) {
                        enumEntries[enumEntry.symbol] = declareGlobal(enumEntryName(enumEntry), anyType, VmGlobalStart.Owned(owner))
                    }
                } else {
                    objectInstances[declaration.symbol] = declareGlobal(instanceName(declaration), anyType, VmGlobalStart.Owned(owner))
                }
            }
            val symbol = declareInitializer("${ownerClass.vmName}::init", owner)
            return@map { module.functions += VmFunctionGenerator.classInitializer(this, symbol, entry.value, fields) }
        }
    }

    private fun globalName(field: IrField): String =
        vmSanitized(field.correspondingPropertySymbol?.owner?.fqNameWhenAvailable?.asString() ?: field.name.asString())

    /**
     * [globalName], made distinct from the module's other globals by a counter. Only private properties of two files in
     * one package share a name, and no other module imports those.
     */
    private fun definedGlobalName(field: IrField): String {
        val name = globalName(field)
        if (usedGlobalNames.add(name)) return name
        return generateSequence(1) { it + 1 }.map { "${name}_$it" }.first(usedGlobalNames::add)
    }

    private val usedGlobalNames = hashSetOf<String>()

    private fun enumEntryName(entry: IrEnumEntry): String = "${entry.parentAsClass.vmName}::${entry.name.asString()}"

    private fun instanceName(declaration: IrClass): String = "${declaration.vmName}::INSTANCE"

    internal fun enumEntryGlobal(entry: IrEnumEntry): Int = enumEntries.getOrPut(entry.symbol) {
        importGlobal(entry, enumEntryName(entry), anyType) ?: unsupported("the enum entry ${entry.name} outside the VM modules")
    }

    internal fun globalOf(field: IrField): Int = globals.getOrPut(field.symbol) {
        importGlobal(field, globalName(field), heldType(field.type))
            ?: unsupported("the static field ${field.name} outside the VM modules")
    }

    /** Whether [declaration] is the companion of a class the runtime implements, `Char`'s or `String`'s, which has no state. */
    internal fun isStatelessCompanion(declaration: IrClass): Boolean =
        declaration.isCompanion && declaration.parentClassOrNull?.let(::isRuntimeImplemented) == true

    internal fun objectInstance(declaration: IrClass): Int = objectInstances.getOrPut(declaration.symbol) {
        importGlobal(declaration, instanceName(declaration), anyType) ?: unsupported("the object ${declaration.name} outside the VM modules")
    }

    /** The global holding the standard library's `Unit` object. */
    internal fun unitInstance(): Int = objectInstance(irBuiltIns.unitClass.owner)

    /** The global [name] of the VM module declaring [declaration], or null when no VM module does. */
    private fun importGlobal(declaration: IrDeclaration, name: String, type: VmType): Int? =
        if (isInAnotherVmModule(declaration)) declareGlobal(name, type, VmGlobalStart.Imported) else null

    /** The owner whose initializer a static call to [function] runs first, if any. */
    internal fun ownerOf(function: IrFunction): VmOwner? = functionOwners[function.symbol]

    /**
     * The function filling in [function]'s default arguments, named after it: it takes the function's parameters and a
     * mask with bit `i` set for each parameter `i` a call left out, computes those, and calls the function. Every
     * module generates the stubs of its own functions, and imports another's from it. A JVM library's is a host import;
     * see [jvmLibraryDefaultStub].
     */
    internal fun defaultStub(function: IrFunction): VmFunctionSymbol = defaultStubs.getOrPut(function.symbol) {
        jvmLibraryFacadeName(function)?.let { return@getOrPut jvmLibraryDefaultStub(function, jvmDefaultsBindingName(function, it)) }
        hostMemberOwner(function)?.let { return@getOrPut jvmLibraryDefaultStub(function, jvmMemberDefaultsBindingName(function, it)) }
        if (function.parameters.size > Long.SIZE_BITS) unsupported("defaults for more than ${Long.SIZE_BITS} parameters")
        val original = functionSymbol(function)
        val symbol = VmFunctionSymbol(vmSanitized("${original.name}\$default"), original.paramTypes + VmType.I64, original.returnType)
        when {
            isOwn(function) -> {}
            isInAnotherVmModule(function) -> module.imports += symbol
            else -> unsupported("a default argument to ${function.render()} outside the VM modules")
        }
        symbol
    }

    private val defaultStubs = hashMapOf<IrFunctionSymbol, VmFunctionSymbol>()

    /**
     * The JVM's `$default` method of [function], bound by [name]: a top-level function of a JVM library, or a member of a
     * class the JVM owns, whose stub is static and takes the receiver first. It takes a mask of the regular parameters
     * only, as an `int`, and a trailing `Object` the call passes null; see [VmFunctionGenerator.defaultArguments].
     */
    private fun jvmLibraryDefaultStub(function: IrFunction, name: String?): VmFunctionSymbol {
        if (function.parameters.count { it.kind == IrParameterKind.Regular } > Int.SIZE_BITS) {
            unsupported("defaults for more than ${Int.SIZE_BITS} parameters of a JVM function")
        }
        name ?: unsupported("the defaults of ${function.render()} on the JVM, a signature whose JVM name is not covered")
        val params = function.parameters.map(::heldParameterType) + VmType.I64 + anyType
        return VmFunctionSymbol(name, params, vmType(function.returnType)).also { module.externals += it }
    }

    /** Whether the stub of [function]'s defaults is the JVM's, whose mask counts only regular parameters. */
    internal fun hasJvmDefaultStub(function: IrFunction): Boolean = jvmLibraryFacadeName(function) != null || hostMemberOwner(function) != null

    /** The class the JVM owns that declares [function], a member with a body or abstract, or null for any other function. */
    private fun hostMemberOwner(function: IrFunction): IrClass? =
        function.takeIf { it is IrSimpleFunction && it.dispatchReceiverParameter != null }?.parentClassOrNull?.takeIf(::isHostClass)

    /** Adds a function only code generation creates, such as a lambda's body. */
    internal fun addFunction(function: VmFunction) {
        module.functions += function
    }

    /** A name for a function only code generation creates, distinct from every other function's in the module. */
    internal fun uniqueFunctionName(base: String): String {
        val name = vmSanitized(base)
        if (usedFunctionNames.add(name)) return name
        return generateSequence(1) { it + 1 }.map { "${name}_$it" }.first(usedFunctionNames::add)
    }

    /** The standard library's box for a captured variable, which the runtime implements; see `SharedVariablesLowering`. */
    internal fun isSharedVariableBox(declaration: IrClass): Boolean =
        declaration.fqNameWhenAvailable?.asString() == "kotlin.internal.SharedVariableBox"

    internal val sharedVariableBoxClass: Int get() = runtimeClass("kotlin.internal.SharedVariableBox")

    private fun collectClass(declaration: IrClass, classes: MutableList<IrClass>, functions: MutableList<IrFunction>) {
        // What only the compiler reads, and what the runtime implements in the standard library's stead.
        if (isStdlib && (declaration.kind == ClassKind.ANNOTATION_CLASS || isRuntimeImplemented(declaration))) return
        when {
            declaration.isValue && declaration.valueClassRepresentation !is InlineClassRepresentation ->
                unsupported("the multi-field value class ${declaration.name}")
        }
        classes += declaration
        for (member in declaration.declarations) {
            when (member) {
                // A value class held unboxed is boxed by storing its field, so nothing calls the box's constructor.
                is IrConstructor -> if (!declaration.isInlinedValueClass()) functions += member
                is IrSimpleFunction -> if (member.isGenerated()) functions += member
                is IrProperty -> {
                    listOfNotNull(member.getter, member.setter).filterTo(functions) { it.isGenerated() }
                }
                is IrAnonymousInitializer -> if (member.isStatic) unsupported("a static initializer in ${declaration.name}")
                is IrField -> if (member.isStatic) unsupported("the static field ${member.name}")
                is IrClass -> stubbing(member) { collectClass(member, classes, functions) }
                is IrEnumEntry -> member.correspondingClass?.let { stubbing(it) { collectClass(it, classes, functions) } }
                else -> unsupported("the member ${member.render()}")
            }
        }
    }

    /**
     * Every call to an inline function in the source is inlined — [lowerForVm] inlines the module's own and a klib's
     * alike — and one with a reified type parameter could not even be generated. The standard library generates the
     * rest all the same: a lowering running after inlining calls some, as a loop over unsigned values calls `Int.toUInt`
     * and `UInt.toInt`, and another module imports them from it.
     */
    private fun IrSimpleFunction.isOnlyInlined(): Boolean = isInline && (!isStdlib || typeParameters.any { it.isReified })

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

    /** Whether the module declares [declaration]: everything else reaches it as a library's, from outside its files. */
    private fun isOwn(declaration: IrDeclaration): Boolean = (declaration.getPackageFragment() as? IrFile)?.module == fragment

    /** Whether a VM library defines [declaration], which the module then imports; see [VmLibraries.isInVmLibrary]. */
    private fun isInAnotherVmModule(declaration: IrDeclaration): Boolean = !isOwn(declaration) && libraries.isInVmLibrary(declaration)

    private fun runtimeClass(name: String): Int = runtimeClasses.getOrPut(name) {
        module.classes += VmClass(name, null, emptyList(), isOpen = false, isAbstract = false, VmClassKind.Imported())
        module.classes.size - 1
    }

    internal val anyType: VmType get() = VmType.Ref(runtimeClass("Any"))

    /** The VM's function interface of [arity], which the linker declares once a module names it. */
    private fun functionInterface(arity: Int): Int = runtimeClass("vm.builtin.Function$arity")

    /** The `invoke` of the function interface of [arity], taking and returning references as the JVM's erased one does. */
    private fun functionInvokeSelector(arity: Int): Int = runtimeSelectors.getOrPut("vm.builtin.Function$arity::invoke") {
        module.selectors += VmSelector("invoke", imported = true, functionInterface(arity), List(arity) { anyType }, anyType)
        module.selectors.size - 1
    }
    internal val stringType: VmType get() = VmType.Ref(runtimeClass("vm.builtin.String"))

    /** The class the VM raises for a null dereference, which Kotlin throws for a failed null check too. */
    internal val nullPointerClass: Int get() = runtimeClass("vm.builtin.NullReference")

    internal val throwableClass: Int get() = runtimeClass("vm.runtime.Throwable")

    /** A runtime class by the name the runtime declares it under. */
    internal fun runtimeClassNamed(name: String): Int = runtimeClass(name)

    internal val toStringSelector: Int get() = runtimeSelector("toString")

    /** A selector the runtime declares, on `Any` or on one of the classes [RUNTIME_TYPES] lists, as it declares it. */
    private fun runtimeSelector(name: String): Int = runtimeSelectors.getOrPut(name) {
        val any = runtimeClass("Any")
        module.selectors += when (name) {
            "equals" -> VmSelector(name, imported = true, any, listOf(anyType), VmType.I64)
            "hashCode" -> VmSelector(name, imported = true, any, emptyList(), VmType.I64)
            "toString" -> VmSelector(name, imported = true, any, emptyList(), stringType)
            "message" -> VmSelector(name, imported = true, runtimeClass("vm.runtime.Throwable"), emptyList(), stringType)
            "cause" -> VmSelector(name, imported = true, runtimeClass("vm.runtime.Throwable"), emptyList(), anyType)
            "compareTo" -> VmSelector(name, imported = true, runtimeClass("vm.runtime.Comparable"), listOf(anyType), VmType.I64)
            "length" -> VmSelector(name, imported = true, runtimeClass("vm.runtime.CharSequence"), emptyList(), VmType.I64)
            "get" -> VmSelector(name, imported = true, runtimeClass("vm.runtime.CharSequence"), listOf(VmType.I64), VmType.I64)
            "subSequence" ->
                VmSelector(name, imported = true, runtimeClass("vm.runtime.CharSequence"), listOf(VmType.I64, VmType.I64), anyType)
            else -> error("The runtime declares no selector $name on Any")
        }
        module.selectors.size - 1
    }

    internal val equalsSelector: Int get() = runtimeSelector("equals")

    /** The runtime's `CharSequence` selector [name], `length` or `get`. */
    internal fun charSequenceSelector(name: String): Int = runtimeSelector(name)

    internal val hashCodeSelector: Int get() = runtimeSelector("hashCode")

    /** The standard library's `arrayIterator` overload for arrays of [arrayClass], which an array's `iterator()` calls. */
    @OptIn(InternalSymbolFinderAPI::class)
    internal fun arrayIterator(arrayClass: IrClassSymbol): IrSimpleFunction = arrayIterators.getOrPut(arrayClass) {
        irBuiltIns.symbolFinder.findFunctions(CallableId(StandardNames.COLLECTIONS_PACKAGE_FQ_NAME, Name.identifier("arrayIterator")))
            .map { it.owner }
            .singleOrNull { it.parameters.singleOrNull()?.type?.classOrNull == arrayClass }
            ?: unsupported("iterating a ${arrayClass.owner.name} without the VM standard library's arrayIterator")
    }

    private val arrayIterators = hashMapOf<IrClassSymbol, IrSimpleFunction>()

    /** The standard library's `kotlin.enums` function [name], which an enum class's `entries` or `valueOf` calls. */
    @OptIn(InternalSymbolFinderAPI::class)
    internal fun enumsFunction(name: String): IrSimpleFunction =
        irBuiltIns.symbolFinder.findFunctions(CallableId(StandardNames.BUILT_INS_PACKAGE_FQ_NAME.child(Name.identifier("enums")), Name.identifier(name)))
            .map { it.owner }
            .singleOrNull { function -> function.parameters.firstOrNull()?.type?.classOrNull == irBuiltIns.arrayClass }
            ?: unsupported("an enum's members without the standard library's kotlin.enums.$name")

    /**
     * The `kotlin.collections` extension [name] on a nullable [arrayClass], `contentToString` or `contentHashCode`: what a
     * data or value class's generated member calls for an array property.
     */
    @OptIn(InternalSymbolFinderAPI::class)
    internal fun arrayContentFunction(name: String, arrayClass: IrClassSymbol): IrSimpleFunction =
        irBuiltIns.symbolFinder.findFunctions(CallableId(StandardNames.COLLECTIONS_PACKAGE_FQ_NAME, Name.identifier(name)))
            .map { it.owner }
            .singleOrNull { function ->
                val receiver = function.parameters.singleOrNull()?.takeIf { it.kind == IrParameterKind.ExtensionReceiver }
                receiver != null && receiver.type.isMarkedNullable() && receiver.type.classOrNull == arrayClass
            }
            ?: unsupported("$name of a ${arrayClass.owner.name}")

    /** The standard library's `until` from a [from] to a [to], which a primitive's `rangeUntil` computes too. */
    @OptIn(InternalSymbolFinderAPI::class)
    internal fun rangeUntil(from: IrType, to: IrType): IrSimpleFunction =
        irBuiltIns.symbolFinder.findFunctions(CallableId(StandardNames.RANGES_PACKAGE_FQ_NAME, Name.identifier("until")))
            .map { it.owner }
            .singleOrNull { until ->
                until.parameters.size == 2 && until.parameters[0].type.classOrNull == from.classOrNull &&
                        until.parameters[1].type.classOrNull == to.classOrNull
            }
            ?: unsupported("${from.render()}.rangeUntil(${to.render()}) without the standard library's until")

    /** A function of the runtime's `java.lang.StringBuilder`, which Kotlin's `StringBuilder` is an alias of. */
    internal fun stringBuilderFunction(name: String, params: List<VmType>, returnType: VmType?): VmFunctionSymbol =
        runtimeFunction("StringBuilder::$name", listOf(anyType) + params, returnType)

    internal val stringBuilderClass: Int get() = runtimeClass("vm.runtime.StringBuilder")

    /** The runtime class implementing [declaration], one of [RUNTIME_THROWABLES], or null for any other class. */
    internal fun runtimeThrowable(declaration: IrClass): Int? =
        RUNTIME_THROWABLES[declaration.fqNameWhenAvailable?.asString()]?.let(::runtimeClass)

    /** The runtime function storing what one of the JVM's `Throwable` constructors takes. */
    internal val throwableInit: VmFunctionSymbol by lazy {
        runtimeFunction("Throwable::init", listOf(anyType, stringType, anyType), null)
    }

    /** The runtime's box for a primitive: its JVM class, holding the value in its only field. */
    internal fun boxClass(kind: PrimitiveType): Int = runtimeClass("vm.runtime.${boxName(kind)}")

    /** The runtime's `valueOf` for a primitive's box, which boxing calls. */
    internal fun valueOf(kind: PrimitiveType): VmFunctionSymbol {
        return runtimeFunction("${boxName(kind)}::valueOf", listOf(primitiveVmType(kind)), VmType.Ref(boxClass(kind)))
    }

    private fun boxName(kind: PrimitiveType): String = when (kind) {
        PrimitiveType.INT -> "Integer"
        PrimitiveType.LONG -> "Long"
        PrimitiveType.SHORT -> "Short"
        PrimitiveType.BYTE -> "Byte"
        PrimitiveType.CHAR -> "Character"
        PrimitiveType.BOOLEAN -> "Boolean"
        PrimitiveType.DOUBLE -> "Double"
        PrimitiveType.FLOAT -> "Float"
    }

    /** Kotlin's `String.plus(Any?)`; see `@String::plus` in the runtime. */
    internal val stringPlus: VmFunctionSymbol by lazy { runtimeFunction("String::plus", listOf(stringType, anyType), stringType) }

    private fun runtimeFunction(name: String, params: List<VmType>, returnType: VmType?): VmFunctionSymbol {
        val symbol = VmFunctionSymbol(name, params, returnType)
        if (symbol !in module.imports) module.imports += symbol
        return symbol
    }

    /** The runtime selector a member of `Throwable` dispatches through, by the member's Kotlin or Java name. */
    private fun throwableSelector(name: String): Int? = when (name) {
        "<get-message>", "getMessage", "getLocalizedMessage" -> runtimeSelector("message")
        "<get-cause>", "getCause" -> runtimeSelector("cause")
        "toString" -> runtimeSelector("toString")
        else -> null
    }

    /** How a value of a primitive [kind] is held: every integral kind, `Char` and `Boolean` as a whole word. */
    internal fun primitiveVmType(kind: PrimitiveType): VmType = when (kind) {
        PrimitiveType.DOUBLE -> VmType.F64
        PrimitiveType.FLOAT -> VmType.F32
        PrimitiveType.BOOLEAN, PrimitiveType.CHAR, PrimitiveType.BYTE, PrimitiveType.SHORT,
        PrimitiveType.INT, PrimitiveType.LONG -> VmType.I64
    }

    /** The value class [type] is, where a value of it is held unboxed: see [isInlinedValueClass]. Null for any other type. */
    internal fun inlinedValueClassOf(type: IrType): IrClass? =
        type.takeUnless { it.isMarkedNullable() }?.classOrNull?.owner?.takeIf { it.isInlinedValueClass() }

    /** The one field of [valueClass], which its box holds the value in. */
    internal fun valueClassField(valueClass: IrClass): IrField = getInlineClassBackingField(valueClass)

    /**
     * How a value of [type] is held, or null for a type whose expressions produce no register: `Unit`, whose one value is
     * loaded where something needs it, and `Nothing`.
     */
    internal fun vmType(type: IrType): VmType? {
        if (type.isUnit() || type.isNothing()) return null
        if (type.isPrimitiveType()) return primitiveVmType(type.getPrimitiveType()!!)
        inlinedValueClassOf(type)?.let { return vmType(valueClassField(it).type) }
        if (type.isString() || type.isNullableString()) return stringType
        return anyType
    }

    /**
     * How a register, field or global holds a value of [type]: as [vmType], `Unit` as the reference it is, and `Nothing`,
     * which no slot ever receives, as a reference too; see [hasStorage].
     */
    internal fun heldType(type: IrType): VmType = vmType(type) ?: anyType

    /**
     * How a built-in of the runtime mirror declares [type]: as the class a reference has, which the linker checks the
     * import against, where a register holds any reference as `Any`.
     */
    private fun mirrorType(type: IrType): VmType? {
        val vmType = vmType(type)
        if (vmType != anyType) return vmType
        // A built-in takes a function value, which is what the runtime declares, whatever its arity.
        if (type.isFunctionValueType()) return VmType.Ref(runtimeClass("vm.builtin.Function"))
        val index = arrayClassOf(type) ?: type.classOrNull?.owner?.let(::classIndex) ?: return vmType
        return VmType.Ref(index)
    }

    // Classes.

    /**
     * The runtime class implementing [declaration], a class of the standard library the runtime declares in its stead:
     * `Any`, `String`, a primitive's box, the classes [RUNTIME_TYPES] and [RUNTIME_THROWABLES] list, and those of the
     * runtime mirror; see [isRuntimeMirror]. Null for any other class.
     */
    private fun runtimeClassOf(declaration: IrClass): Int? {
        val fqName = declaration.fqNameWhenAvailable?.asString()
        return when {
            declaration.isAny() -> runtimeClass("Any")
            declaration.symbol == irBuiltIns.stringClass -> runtimeClass("vm.builtin.String")
            declaration.isEnumBase() -> runtimeClass("vm.runtime.Enum")
            isSharedVariableBox(declaration) -> sharedVariableBoxClass
            declaration.defaultType.isPrimitiveType() -> boxClass(declaration.defaultType.getPrimitiveType()!!)
            // The linker declares the function interface of each arity a module names; see wip/FUNCTION_VALUES_DESIGN.md.
            declaration.functionInterfaceArity() != null -> functionInterface(declaration.functionInterfaceArity()!!)
            // Every function value is an instance of `vm.builtin.Function`, whatever its arity.
            fqName == "kotlin.Function" || declaration.defaultType.isFunctionValueType() -> runtimeClass("vm.builtin.Function")
            isRuntimeMirror(declaration) -> runtimeClass(fqName!!)
            else -> RUNTIME_TYPES[fqName]?.let(::runtimeClass) ?: runtimeThrowable(declaration)
        }
    }

    /**
     * Whether the standard library's [declaration] needs no class of its own in the VM: the runtime implements it, or it
     * is one the backend compiles to something else — an array and `Nothing`.
     */
    private fun isRuntimeImplemented(declaration: IrClass): Boolean =
        runtimeClassOf(declaration) != null || arrayClassOf(declaration.defaultType) != null ||
                declaration.symbol == irBuiltIns.nothingClass

    /** The class standing for [declaration]: the runtime's, this module's, or one imported from the VM module declaring it. */
    internal fun classIndex(declaration: IrClass): Int? = runtimeClassOf(declaration) ?: classIndices[declaration.symbol] ?: if (!isInAnotherVmModule(declaration)) null else {
        module.classes += VmClass(declaration.vmName, null, emptyList(), isOpen = false, isAbstract = false, VmClassKind.Imported())
        (module.classes.size - 1).also { classIndices[declaration.symbol] = it }
    }

    /** The VM's array of [element]s. */
    internal fun arrayClass(element: VmType): Int = arrayClasses.getOrPut(element) {
        module.classes += VmClass("", null, emptyList(), isOpen = false, isAbstract = false, VmClassKind.Array(element))
        module.classes.size - 1
    }

    /**
     * The VM array standing for [type] when it is an array type, or null for any other: a primitive array holds its
     * element at the element's own width, and an `Array<E>` holds references to E's class, as the JVM's `E[]` does.
     */
    internal fun arrayClassOf(type: IrType): Int? {
        val classifier = type.classOrNull ?: return null
        irBuiltIns.primitiveArraysToPrimitiveTypes[classifier]?.let { return arrayClass(arrayElement(it)) }
        if (classifier != irBuiltIns.arrayClass) return null
        val element = ((type as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection)?.type
        return arrayClass(VmType.Ref(element?.let(::elementClass) ?: runtimeClass("Any")))
    }

    /** How a register holds an element of [type], an array type: a primitive as the primitive, anything else as `Any`. */
    internal fun arrayElementSlot(type: IrType): VmType =
        irBuiltIns.primitiveArraysToPrimitiveTypes[type.classOrNull]?.let(::primitiveVmType) ?: anyType

    private fun arrayElement(kind: PrimitiveType): VmType = when (kind) {
        PrimitiveType.BOOLEAN -> VmType.Bool
        PrimitiveType.BYTE -> VmType.I8
        PrimitiveType.SHORT -> VmType.I16
        PrimitiveType.CHAR -> VmType.U16
        PrimitiveType.INT -> VmType.I32
        PrimitiveType.LONG -> VmType.I64
        PrimitiveType.FLOAT -> VmType.F32
        PrimitiveType.DOUBLE -> VmType.F64
    }

    /**
     * The class an `Array<E>` of [type] E holds references to: E's own, or `Any` for a type parameter, which is erased, and
     * for a class no VM module declares, which the VM cannot name.
     */
    private fun elementClass(type: IrType): Int {
        if (type.classifierOrNull is IrTypeParameterSymbol) return runtimeClass("Any")
        return testedClassOrNull(type.makeNotNull()) ?: runtimeClass("Any")
    }

    /** The class a type test or cast against [type] names, or null for `Any`, which every object is. */
    internal fun testedClass(type: IrType): Int? {
        // Only a cast names a type parameter, and it checks what the JVM's does: the class its bound erases to.
        (type.classifierOrNull as? IrTypeParameterSymbol)?.let { return testedClass(it.owner.erasedUpperBound.defaultType) }
        if (type.isAny() || type.isNullableAny()) return null
        return testedClassOrNull(type) ?: unsupported("a type test against ${type.render()} outside the VM modules")
    }

    /** The class [type] names, `Any` included, or null for a class no VM module declares. */
    private fun testedClassOrNull(type: IrType): Int? {
        if (type.isAny() || type.isNullableAny()) return runtimeClass("Any")
        arrayClassOf(type)?.let { return it }
        if (type.isString() || type.isNullableString()) return runtimeClass("vm.builtin.String")
        type.getPrimitiveType()?.let { return boxClass(it) }
        RUNTIME_TYPES[type.classFqName?.asString()]?.let { return runtimeClass(it) }
        val declaration = type.classOrNull?.owner ?: unsupported("a type test against ${type.render()}")
        return classIndex(declaration) ?: runtimeThrowable(declaration)
    }

    /** Whether one of [this]'s superclasses is a throwable the runtime implements. */
    private fun IrClass.extendsRuntimeThrowable(): Boolean =
        generateSequence(superClass()) { it.superClass() }.any { runtimeThrowable(it) != null }

    /** Whether [this] is an interface declaring nothing that no VM module holds. */
    private fun IrClass.isMarkerOutsideVm(): Boolean =
        isInterface && classIndex(this) == null &&
                declarations.none { (it is IrSimpleFunction && !it.isFakeOverride) || (it is IrProperty && !it.isFakeOverride) }

    private fun IrClass.superClass(): IrClass? =
        superTypes.mapNotNull { it.classOrNull?.owner }.firstOrNull { !it.isInterface }

    internal fun IrClass.isAny() = symbol == irBuiltIns.anyClass

    /** `kotlin.Enum`, which the runtime's `Enum` implements. */
    internal fun IrClass.isEnumBase() = symbol == irBuiltIns.enumClass

    /** The fields [declaration] declares, in order; the VM lays them out after its superclass's. */
    private fun ownFields(declaration: IrClass): List<IrField> = ownFields.getOrPut(declaration.symbol) {
        declaration.declarations.mapNotNull {
            when (it) {
                is IrProperty -> it.backingField
                is IrField -> it
                else -> null
            }?.takeIf(::hasStorage)
        }
    }

    /**
     * Whether [field] is stored: one of type `Nothing` never is, since nothing completes to assign it, so it has no slot,
     * its initializer runs only for its effect, and reading it gives the default a slot would have held.
     */
    internal fun hasStorage(field: IrField): Boolean = !field.type.isNothing()

    /**
     * [field] as an instruction names it: by its position among the fields its class declares in this module, and by its
     * name in another module's, which the VM linker finds among the fields that module's class declares.
     */
    internal fun fieldRef(field: IrField): VmFieldRef {
        val owner = field.parentAsClass
        val index = classIndex(owner)
        if (runtimeClassOf(owner) != null || index == null) unsupported("the field ${field.name} of ${owner.name}")
        if (isOwn(owner)) return VmFieldRef(index, ownFields(owner).indexOf(field))
        return importedField(index, field.name.asString(), heldType(field.type))
    }

    /** The field [name] of the imported class [owner], listed on its import the first time code names it. */
    private fun importedField(owner: Int, name: String, type: VmType): VmFieldRef {
        val fields = (module.classes[owner].kind as VmClassKind.Imported).fields
        val index = fields.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: fields.size.also { fields += VmField(name, type) }
        if (fields[index].type != type) unsupported("the field $name of ${module.classes[owner].name} as two types")
        return VmFieldRef(owner, index)
    }

    /** Where the runtime's `Enum` holds an entry's name. */
    internal val enumNameField: VmFieldRef get() = importedField(runtimeClass("vm.runtime.Enum"), "name", stringType)

    /** Where the runtime's `Enum` holds an entry's ordinal. */
    internal val enumOrdinalField: VmFieldRef get() = importedField(runtimeClass("vm.runtime.Enum"), "ordinal", VmType.I64)

    /** Where the runtime's `kotlin.internal.SharedVariableBox` holds its variable. */
    internal val sharedBoxElementField: VmFieldRef get() = importedField(sharedVariableBoxClass, "element", anyType)

    /** Where the runtime's box for a primitive [kind] holds its value. */
    internal fun boxValueField(kind: PrimitiveType): VmFieldRef = importedField(boxClass(kind), "value", primitiveVmType(kind))

    /**
     * A class's name in the VM: [vmClassName]. A class other modules can name, by its public signature, keeps it
     * exactly; a local or anonymous one, which lowering has moved out of its function, gets a counter when another
     * class of the module has the name already.
     */
    private val IrClass.vmName: String
        get() = classNames.getOrPut(symbol) {
            val name = vmClassName(this)
            if (usedClassNames.add(name) || libraries.publicSignature(this) is IdSignature.CommonSignature) return@getOrPut name
            generateSequence(1) { it + 1 }.map { "${name}_$it" }.first(usedClassNames::add)
        }

    private val classNames = hashMapOf<IrClassSymbol, String>()
    private val usedClassNames = hashSetOf<String>()

    private fun classDefinition(declaration: IrClass): VmClass {
        val interfaces = declaration.superTypes.mapNotNull { it.classOrNull?.owner }.filter { it.isInterface }.mapNotNull {
            // A marker interface the VM cannot name, such as `java.io.Serializable`, only a type test could ask about.
            if (it.isMarkerOutsideVm()) return@mapNotNull null
            // An `expect` interface is the JVM's: the class's shell implements its `actual`, and the JVM calls it there.
            if (it.isExpect) return@mapNotNull null
            // Every function type extends `kotlin.Function`, which declares nothing to answer.
            if (it.fqNameWhenAvailable?.asString() == "kotlin.Function") return@mapNotNull null
            // A suspend function type has no function interface: its value is a function value run under a continuation.
            if (it.defaultType.isSuspendFunctionOrKFunction()) {
                if (declaration.isInterface) return@mapNotNull null
                unsupported("implementing the function type ${it.name}")
            }
            classIndex(it) ?: unsupported("the supertype ${it.name} outside the VM modules")
        }
        if (declaration.isInterface) {
            return VmClass(
                declaration.vmName, null, interfaces, isOpen = true, isAbstract = true, VmClassKind.Interface,
                isHost = declaration.isHostInterface(hasProxies),
            )
        }
        val superClass = declaration.superClass()
        val superIndex = when {
            superClass == null || superClass.isAny() -> runtimeClass("Any")
            superClass.isEnumBase() -> runtimeClass("vm.runtime.Enum")
            runtimeThrowable(superClass) != null -> runtimeThrowable(superClass)!!
            // A paired object's JVM superclass is its JVM half's.
            isHostClass(superClass) -> runtimeClass("Any")
            else -> classIndex(superClass) ?: unsupported("the superclass ${superClass.name} outside the VM modules")
        }
        val fields = ownFields(declaration).map { VmField(it.name.asString(), heldType(it.type)) }
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
            // A JVM superclass's member is the JVM half's, which a call reaches as a host import; see [hostMember].
            if (implementation.parentClassOrNull?.let(::isHostClass) == true) continue
            // Only an instance member answers a selector, which leaves out an enum's `values` and `valueOf`.
            if (implementation.dispatchReceiverParameter == null) continue
            val implementationSymbol = when {
                // Answered by the runtime's `Enum`, or by `Any` through it, but for an interface's selector.
                implementation.parentClassOrNull?.isEnumBase() == true -> {
                    for (root in function.vmRoots()) {
                        val selector = selectors[root.symbol] ?: continue
                        methods += VmMethod(selector, enumAdapter(declaration, implementation, module.selectors[selector]))
                    }
                    continue
                }
                // Answered by the runtime's throwable, except its name, which only the class's own code has. A Java
                // throwable's `toString` can resolve to `Any`'s, so the class is what tells.
                implementation.parentClassOrNull?.let(::runtimeThrowable) != null ||
                        (implementation.parentClassOrNull?.isAny() == true && declaration.extendsRuntimeThrowable()) -> {
                    if (implementation.name.asString() != "toString" || isAbstract) continue
                    throwableToString(declaration)
                }
                implementation.parentClassOrNull?.isAny() != true -> {
                    // A Java interface's default method, or a member of this module with no body — an intrinsic, or a
                    // Java member the JVM maps onto a Kotlin class — which nothing here answers and nothing on the VM
                    // calls.
                    val isJava = implementation.origin == IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB
                    if (isJava || (implementation.symbol !in definedFunctions && isOwn(implementation))) continue
                    if (implementation.symbol !in definedFunctions) {
                        importFunction(implementation) ?: unsupported("inheriting ${implementation.render()} outside the VM modules")
                    } else {
                        functionSymbol(implementation)
                    }
                }
                else -> continue // Answered by the runtime's `Any`.
            }
            for (root in function.vmRoots()) {
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

    /**
     * The function of an enum answering [selector], which an interface it implements declares, with the runtime
     * `Enum`'s [member]: the runtime cannot answer a selector of the module.
     */
    private fun enumAdapter(declaration: IrClass, member: IrSimpleFunction, selector: VmSelector): VmFunctionSymbol =
        enumAdapters.getOrPut(declaration.symbol to member.name.asString()) {
            val [field, type] = when (member.name.asString()) {
                "<get-name>" -> enumNameField to stringType
                "<get-ordinal>" -> enumOrdinalField to VmType.I64
                else -> unsupported("implementing an interface with ${member.render()}")
            }
            if (selector.paramTypes.isNotEmpty() || selector.returnType != type) unsupported("a bridge to ${member.render()}")
            val symbol = VmFunctionSymbol(vmSanitized("${declaration.vmName}::${member.name.asString()}"), listOf(anyType), type)
            val function = VmFunction(symbol, owner = null)
            val self = function.newValue(anyType)
            function.params += self
            val result = function.newValue(type)
            val block = function.newBlock()
            block.instructions += VmInstruction.LoadField(result, self, field)
            block.terminator = VmTerminator.Return(result)
            module.functions += function
            symbol
        }

    private val enumAdapters = hashMapOf<Pair<IrClassSymbol, String>, VmFunctionSymbol>()

    /** `Name: message`, for a throwable whose `toString` is still `Throwable`'s; see `@Throwable::describe`. */
    private fun throwableToString(declaration: IrClass): VmFunctionSymbol = throwableToStrings.getOrPut(declaration.symbol) {
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

    // Functions and calls.

    private fun IrClass.memberFunctions(): List<IrSimpleFunction> = declarations.flatMap {
        when (it) {
            is IrSimpleFunction -> listOf(it)
            is IrProperty -> listOfNotNull(it.getter, it.setter)
            else -> emptyList()
        }
    }

    private fun IrSimpleFunction.isOverridable(): Boolean {
        val owner = parentClassOrNull ?: return false
        // An enum class reads as final from another module's metadata even where its entries override its members, so only
        // the member's own modality tells, in every module alike.
        val ownerIsFinal = owner.modality == Modality.FINAL && owner.kind != ClassKind.ENUM_CLASS
        return modality != Modality.FINAL && !ownerIsFinal &&
                !DescriptorVisibilities.isPrivate(visibility) && owner.kind != ClassKind.OBJECT
    }

    /** The selector dispatching a call to [root], or null when nothing can override it. */
    private fun selectorOf(root: IrSimpleFunction): Int? {
        if (root.parentClassOrNull?.isAny() == true) return runtimeSelector(root.name.asString())
        root.parentClassOrNull?.functionInterfaceArity()?.let { return functionInvokeSelector(it) }
        if (root.parentClassOrNull?.fqNameWhenAvailable?.asString() in RUNTIME_TYPES) return runtimeTypeSelector(root)
        if (root.parentClassOrNull?.let(::runtimeThrowable) != null) return throwableSelector(root.name.asString())
        if (!root.isOverridable()) return null
        // A call to an `expect` class's member; see [vmOverridden].
        if (root.parentClassOrNull?.isExpect == true) return null
        return selectors[root.symbol] ?: importSelector(root) ?: unsupported("overriding ${root.render()} outside the VM modules")
    }

    /** The runtime's selector for [root], a member of one of the classes [RUNTIME_TYPES] lists. */
    private fun runtimeTypeSelector(root: IrSimpleFunction): Int = when (val name = root.name.asString()) {
        "compareTo", "get", "subSequence" -> runtimeSelector(name)
        "<get-length>" -> runtimeSelector("length")
        else -> unsupported("overriding ${root.render()}")
    }

    /**
     * The name of the selector [root] declares: its function's name without its class's, since the VM names a selector
     * `Class::selector` after its class, as the runtime's `Any::toString` is. Its full name is the function's.
     */
    private fun selectorName(root: IrSimpleFunction): String = functionSymbol(root).name.removePrefix("${root.parentAsClass.vmName}::")

    /** The selector [root] declares in the VM module declaring it, or null when no VM module does. */
    private fun importSelector(root: IrSimpleFunction): Int? {
        if (!isInAnotherVmModule(root)) return null
        val symbol = functionSymbol(root)
        module.selectors += VmSelector(selectorName(root), imported = true, classIndex(root.parentAsClass)!!, symbol.paramTypes.drop(1), symbol.returnType)
        return (module.selectors.size - 1).also { selectors[root.symbol] = it }
    }

    /** How a call reaches [callee]: by selector when it may be overridden, directly otherwise. */
    internal fun callTarget(callee: IrSimpleFunction, isSuperCall: Boolean): CallTarget {
        if (callee.dispatchReceiverParameter == null) return staticTarget(callee)
        hostMember(callee, isSuperCall)?.let { symbol -> return CallTarget(VmCallee.Static(symbol), symbol) }
        val root = callee.vmRoots().first()
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
        // A class the runtime implements answers only through the selectors it declares, such as `String.length` through
        // `CharSequence`'s.
        if (!isSuperCall && root != implementation && implementation.parentClassOrNull?.let(::isRuntimeImplemented) == true) {
            return CallTarget(VmCallee.Virtual(selectorOf(root) ?: unsupported("calling ${root.render()}")), functionSymbol(root))
        }
        return staticTarget(implementation)
    }

    internal fun staticTarget(callee: IrFunction): CallTarget {
        val symbol = if (isRuntimeMirror(callee)) {
            runtimeFunction(callee.fqNameWhenAvailable!!.asString(), callee.parameters.map { mirrorType(it.type)!! }, mirrorType(callee.returnType))
        } else if (callee is IrSimpleFunction && callee.isExpect) {
            hostImport(callee)
        } else if (callee.symbol in definedFunctions) {
            functionSymbol(callee)
        } else {
            importFunction(callee) ?: libraryHostImport(callee) ?: hostStaticGetter(callee)
                ?: unsupported("a call to ${callee.render()} outside the VM modules")
        }
        return CallTarget(VmCallee.Static(symbol), symbol)
    }

    /**
     * The extern reading the static field [callee] gets, a static getter of a class the JVM owns, as Compose's plugin
     * gives each class one for its `$stable`; null for any other function.
     */
    private fun hostStaticGetter(callee: IrFunction): VmFunctionSymbol? {
        if (callee !is IrSimpleFunction || callee.dispatchReceiverParameter != null) return null
        val owner = callee.parentClassOrNull?.takeIf(::isHostClass) ?: return null
        val field = callee.correspondingPropertySymbol?.owner?.backingField?.takeIf { it.isStatic && callee.parameters.isEmpty() } ?: return null
        return hostImports.getOrPut(callee.symbol) {
            val name = jvmStaticFieldName(field, owner) ?: unsupported("reading ${field.render()} on the JVM, a type whose JVM name is not covered")
            VmFunctionSymbol(name, emptyList(), vmType(callee.returnType)).also { module.externals += it }
        }
    }

    /**
     * The extern standing for [callee], an `expect` function whose `actual` the host runs, in the facade of the `actual`'s
     * file.
     */
    private fun hostImport(callee: IrSimpleFunction): VmFunctionSymbol = hostExtern(
        callee,
        hostActuals(callee) ?: unsupported("calling ${callee.render()}, whose actual is not a top-level function in a JVM file's own facade"),
    )

    /**
     * The extern standing for [callee], a top-level function of a JVM library, inline ones included: the JVM backend keeps
     * an inline function's method, so the JVM runs it where the VM has no body to inline. Null for any other function.
     */
    private fun libraryHostImport(callee: IrFunction): VmFunctionSymbol? = jvmLibraryFacadeName(callee)?.let { hostExtern(callee, it) }

    /** The extern standing for [callee], which the JVM class [facade] holds: bound by `jvm:` and the JVM name of [callee]. */
    private fun hostExtern(callee: IrFunction, facade: String): VmFunctionSymbol = hostImports.getOrPut(callee.symbol) {
        val name = jvmBindingName(callee, facade)
            ?: unsupported("calling ${callee.render()} on the JVM, a signature whose JVM name is not covered")
        val symbol = VmFunctionSymbol(name, callee.parameters.map(::heldParameterType), vmType(callee.returnType))
        module.externals += symbol
        symbol
    }

    private val hostImports = hashMapOf<IrFunctionSymbol, VmFunctionSymbol>()
    private val superHostImports = hashMapOf<IrFunctionSymbol, VmFunctionSymbol>()
    private val pairConstructors = hashMapOf<Pair<IrClassSymbol, IrConstructorSymbol>, VmFunctionSymbol>()

    /**
     * Whether the JVM owns [declaration], so its instances are JVM objects: an `expect` class, whose `actual` the JVM
     * fragment has, or a class of a JVM library.
     */
    /**
     * The extern answering the JVM's instance of [declaration], an object of a class the JVM owns, or null for any other
     * object: the static field of its outer class, or of an interface, that a companion is, and any other's `INSTANCE`.
     */
    internal fun hostObject(declaration: IrClass): VmFunctionSymbol? {
        if (!isHostClass(declaration)) return null
        return hostObjects.getOrPut(declaration.symbol) {
            val outer = declaration.parentClassOrNull?.takeIf { declaration.isCompanion }
            val name = jvmObjectFieldName(declaration, outer)
            VmFunctionSymbol(name, emptyList(), anyType).also { module.externals += it }
        }
    }

    private val hostObjects = hashMapOf<IrClassSymbol, VmFunctionSymbol>()

    internal fun isHostClass(declaration: IrClass): Boolean =
        declaration.isExpect || (!isOwn(declaration) && !libraries.isInVmLibrary(declaration) && !isRuntimeImplemented(declaration))

    /**
     * The extern standing for [callee], a member of a class the JVM owns, which takes the receiver first and which the
     * JVM calls virtually on it; null for any other member, and for one `Any` declares, which the VM answers.
     */
    private fun hostMember(callee: IrSimpleFunction, isSuperCall: Boolean): VmFunctionSymbol? {
        // A paired object's class inherits a JVM superclass's members, which its JVM half answers.
        val member = when {
            isHostClass(callee.parentAsClass) -> callee
            callee.isFakeOverride -> callee.resolveFakeOverride()?.takeIf { isHostClass(it.parentAsClass) } ?: return null
            else -> return null
        }
        if (member.resolveFakeOverride()?.parentClassOrNull?.isAny() == true) return null
        val imports = if (isSuperCall) superHostImports else hostImports
        return imports.getOrPut(member.symbol) {
            val name = (if (isSuperCall) jvmSuperMemberName(member) else jvmMemberName(member))
                ?: unsupported("calling ${member.render()} on the JVM, a signature whose JVM name is not covered")
            val params = listOf(anyType) + member.nonDispatchParameters.map(::heldParameterType)
            VmFunctionSymbol(name, params, vmType(member.returnType)).also { module.externals += it }
        }
    }

    /**
     * The extern making the JVM half of a paired object of [declaration], a class of the module whose superclass is
     * the JVM's [superConstructor] constructs: it runs [superConstructor] in the shell's pair constructor, on the VM
     * half it takes first; see `jvm-pair:` in vm-jni.
     */
    internal fun pairConstructor(declaration: IrClass, superConstructor: IrConstructor): VmFunctionSymbol =
        pairConstructors.getOrPut(declaration.symbol to superConstructor.symbol) {
            val name = jvmPairConstructorName(declaration, superConstructor)
                ?: unsupported("extending ${superConstructor.render()} on the JVM, a signature whose JVM name is not covered")
            val params = listOf(anyType) + superConstructor.parameters.map(::heldParameterType)
            VmFunctionSymbol(name, params, null).also { module.externals += it }
        }

    /** The extern standing for [constructor], of a class the JVM owns, which answers the JVM object it makes. */
    internal fun hostConstructor(constructor: IrConstructor): VmFunctionSymbol = hostImports.getOrPut(constructor.symbol) {
        val name = jvmMemberName(constructor) ?: unsupported("constructing ${constructor.render()} on the JVM, a signature whose JVM name is not covered")
        VmFunctionSymbol(name, constructor.parameters.map(::heldParameterType), anyType).also { module.externals += it }
    }

    /** Whether a call of [callee] is a host import's. */
    internal fun isHostImport(callee: IrFunction): Boolean = callee.symbol in hostImports

    /** The bridge's `KProperty` named by its argument; see [VmFunctionGenerator.propertyStandIn]. */
    internal val propertyStandIn: VmFunctionSymbol by lazy {
        VmFunctionSymbol("jvm:vm/bridge/VmBridge.property(Ljava/lang/String;)Lkotlin/reflect/KProperty;", listOf(stringType), anyType)
            .also { module.externals += it }
    }

    private fun heldParameterType(parameter: IrValueParameter): VmType = heldType(parameter.type)

    /**
     * The name [function] is exported by: its JVM name, for each public top-level function of a module the VM runs. Null
     * for any other, for a function whose JVM name is not covered, see [jvmBindingName], and for one another function
     * shares, which the JVM could not have both of.
     */
    private fun exportName(function: IrFunction): String? = jvmExportNameOf(function)?.takeUnless { it in clashingExportNames }

    private fun jvmExportNameOf(function: IrFunction): String? {
        if (isStdlib || function !is IrSimpleFunction) return null
        return jvmExportName(function)
    }

    /** [function] as the VM module declaring it defines it, imported, or null when no VM module does. */
    private fun importFunction(function: IrFunction): VmFunctionSymbol? {
        if (!isInAnotherVmModule(function)) return null
        val symbol = functionSymbol(function)
        if (symbol !in module.imports) module.imports += symbol
        return symbol
    }

    /**
     * The function's VM signature. A member takes its receiver as `Any` and the rest from the declaration it overrides;
     * a constructor takes the object it initializes.
     */
    internal fun functionSymbol(function: IrFunction): VmFunctionSymbol = functionSymbols.getOrPut(function.symbol) {
        // A parameter of a type with no values, `Unit`, still takes an argument: whatever the caller has, as `Any`.
        fun types(parameters: List<IrValueParameter>) =
            parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.map { vmType(it.type) ?: anyType }

        when {
            function is IrConstructor -> VmFunctionSymbol(vmName(function), listOf(anyType) + types(function.parameters), null)
            function is IrSimpleFunction && function.dispatchReceiverParameter != null -> {
                val root = function.vmRoots().first()
                val params = types(root.parameters)
                val returnType = vmType(root.returnType)
                // Where the override is primitive and the root a reference, the body boxes and unboxes; see
                // [VmFunctionGenerator.generate].
                VmFunctionSymbol(vmName(function), listOf(anyType) + params, returnType)
            }
            else -> VmFunctionSymbol(vmName(function), types(function.parameters), vmType(function.returnType))
        }
    }

    /**
     * A function's name in the VM: [vmFunctionName], with the hash its public signature carries, which is what tells
     * overloads apart. A function without one is private to the module, so a counter tells its overloads apart instead.
     */
    private fun vmName(function: IrFunction): String {
        val owner = function.parentClassOrNull
        // A private function of the module, or a local declaration lowering lifted out of its scope, which no other module
        // names; the types of a lifted one can still name the scope's type parameters, which the mangler cannot place. A
        // member of a private class is named, by a subclass in another module.
        val isPrivateToModule = isOwn(function) && (DescriptorVisibilities.isPrivate(function.visibility) || function.isOriginallyLocal)
        val hash = inlineClassImplHash(function) ?: if (isPrivateToModule) null else signatureHash(libraries.publicSignature(function))
        val name = vmFunctionName(function, owner?.vmName, hash)
        // Another module's function is named as that module names it, which only a public signature makes stable.
        if (isInAnotherVmModule(function)) return if (hash == null) unsupported("naming ${function.render()} of another module") else name
        if (usedFunctionNames.add(name)) return name
        return generateSequence(1) { it + 1 }.map { "${name}_$it" }.first(usedFunctionNames::add)
    }

    private val usedFunctionNames = hashSetOf<String>()

    /**
     * The hash naming a function the value-class lowering made from a member of a value class held unboxed, which takes
     * the value in place of the receiver; null for any other function. No klib records it, and every module calling the
     * member makes it again, so it is hashed by what it is in each: its name, and the classes its types erase to.
     */
    private fun inlineClassImplHash(function: IrFunction): Long? {
        if (function.dispatchReceiverParameter != null) return null
        if (function.parentClassOrNull?.isInlinedValueClass() != true) return null
        fun erased(type: IrType) = (type.classifierOrNull as? IrTypeParameterSymbol)?.owner?.erasedUpperBound?.fqNameWhenAvailable
            ?: type.classFqName
        val key = (listOf(function.name.asString()) + function.parameters.map { erased(it.type) } + erased(function.returnType))
            .joinToString(",")
        return key.fold(-0x340d631b7bdddcdbL) { hash, c -> (hash xor c.code.toLong()) * 0x100000001b3L }
    }

    /** An accessor's hash is its own; its property's would be the same for the getter and the setter. */
    private fun signatureHash(signature: IdSignature?): Long? = when (signature) {
        is IdSignature.CommonSignature -> signature.id
        is IdSignature.AccessorSignature -> signature.accessorSignature.id
        is IdSignature.CompositeSignature -> signatureHash(signature.inner)
        else -> null
    }

}

/** The name the JVM gives [irClass], dotted: what the runtime's `Any.toString` shows. */
private val IrClass.jvmName: String
    get() = jvmInternalName.replace('/', '.')

/** The name the VM knows [irClass] by, when other modules can name it: its JVM name. */
fun vmClassName(irClass: IrClass): String = vmSanitized(irClass.jvmName)

/**
 * The name the VM knows [function] by: its qualified name, a member's as `Class::name` after its class's [ownerName],
 * then the [hash] of its public signature, which the klib writes and the mangler computes, when it has one.
 */
fun vmFunctionName(function: IrFunction, ownerName: String? = function.parentClassOrNull?.let(::vmClassName), hash: Long?): String {
    val base = if (ownerName != null) "$ownerName::${function.name.asString()}"
    else function.fqNameWhenAvailable?.asString() ?: function.name.asString()
    return vmSanitized(if (hash == null) base else "${base}_${java.lang.Long.toHexString(hash)}")
}

/** Keeps only what an IR text name may hold, so a dump reads back in. */
private fun vmSanitized(name: String): String = buildString(name.length) {
    var i = 0
    while (i < name.length) {
        if (name.startsWith("::", i)) {
            append("::")
            i += 2
            continue
        }
        val c = name[i++]
        append(if (c.isLetterOrDigit() || c == '_' || c == '.' || c == '$') c else '_')
    }
}

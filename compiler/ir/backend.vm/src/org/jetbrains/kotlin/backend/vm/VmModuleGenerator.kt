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
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*

class VmCodegenException(message: String) : RuntimeException(message)

/** How a call reaches its callee, and the signature it calls it with. */
internal class CallTarget(val callee: VmCallee, val symbol: VmFunctionSymbol)

internal fun unsupported(what: String): Nothing = throw VmCodegenException("the VM backend does not support $what yet")

/** A `Float`'s bits as a register holds them: in the low half, the high half zero. */
internal fun floatBits(value: Float): Long = value.toRawBits().toLong() and 0xFFFF_FFFFL

/** The module the VM repository's runtime/src/runtime.ir defines: the root class, the string class and their selectors. */
const val VM_RUNTIME_MODULE = "vm.runtime"

/**
 * The standard library's types the runtime declares, by the runtime class standing for each: the JVM class the Kotlin
 * type is compiled to, whose name the runtime gives its class.
 */
private val RUNTIME_TYPES = mapOf(
    "kotlin.Number" to "java.lang.Number",
    "kotlin.Comparable" to "java.lang.Comparable",
    "kotlin.CharSequence" to "java.lang.CharSequence",
    "java.lang.StringBuilder" to "java.lang.StringBuilder",
)

/**
 * The JVM's throwables the runtime implements, by the runtime class standing for each. The Kotlin names are the
 * standard library's own classes; its other exceptions are aliases of the Java ones. The VM raises the `vm.` classes
 * itself, which is what makes a `catch` of the JVM class catch the VM's failure.
 */
private val RUNTIME_THROWABLES = mapOf(
    "kotlin.Throwable" to "java.lang.Throwable",
    "kotlin.NoWhenBranchMatchedException" to "kotlin.NoWhenBranchMatchedException",
    "kotlin.UninitializedPropertyAccessException" to "kotlin.UninitializedPropertyAccessException",
    "java.lang.NullPointerException" to "vm.NullReference",
    "java.lang.ArithmeticException" to "vm.DivisionByZero",
    "java.lang.ClassCastException" to "vm.ClassCast",
    "java.lang.ArrayIndexOutOfBoundsException" to "vm.ArrayIndexOutOfBounds",
    "java.lang.StringIndexOutOfBoundsException" to "vm.StringIndexOutOfBounds",
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
).associateWith { it }

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
    /** Told of each declaration of the standard library generated as a stub; see [isStdlib]. */
    private val reportStub: (String) -> Unit = {},
) {
    private val module = VmModule(moduleName)
    private lateinit var fragment: IrModuleFragment
    private val runtimeClasses = hashMapOf<String, Int>()
    private val runtimeSelectors = hashMapOf<String, Int>()
    private val functionSymbols = hashMapOf<IrFunctionSymbol, VmFunctionSymbol>()
    private val definedFunctions = hashSetOf<IrFunctionSymbol>()
    private val classIndices = hashMapOf<IrClassSymbol, Int>()
    private val fieldLayouts = hashMapOf<IrClassSymbol, List<LayoutField>>()
    private val selectors = hashMapOf<IrSimpleFunctionSymbol, Int>()
    private val throwableToStrings = hashMapOf<IrClassSymbol, VmFunctionSymbol>()
    private val arrayClasses = hashMapOf<VmType, Int>()
    private val globals = hashMapOf<IrFieldSymbol, Int>()
    private val objectInstances = hashMapOf<IrClassSymbol, Int>()
    private val enumEntries = hashMapOf<IrEnumEntrySymbol, Int>()
    private val functionOwners = hashMapOf<IrFunctionSymbol, VmOwner>()

    /**
     * Whether the module is the standard library itself. Its declarations the runtime implements are left out, as are
     * its intrinsics, which have no bodies; and a function it cannot generate yet becomes a stub throwing
     * `UnsupportedOperationException`, reported through [reportStub], rather than failing the module, so a program can
     * link against the rest.
     */
    private var isStdlib = false

    fun generate(fragment: IrModuleFragment): VmModule {
        this.fragment = fragment
        isStdlib = fragment == irBuiltIns.anyClass.owner.fileOrNull?.module
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
                    is IrClass -> stubbing(declaration) { collectClass(declaration, classes, functions) }
                    is IrTypeAlias -> {}
                    else -> unsupported("the declaration ${declaration.render()}")
                }
            }
            functions += members.functions
        }
        functions.retainAll { function ->
            val isAbstract = function is IrSimpleFunction && function.modality == Modality.ABSTRACT
            when {
                function.body != null || isAbstract -> true
                isStdlib -> false
                else -> unsupported("the declaration ${function.render()}")
            }
        }
        val bodies = functions.filter { it.body != null }
        bodies.mapTo(definedFunctions) { it.symbol }

        // The classes other modules can name first, so no local class takes a name one of them has.
        classes.sortedBy { it.symbol.signature !is IdSignature.CommonSignature }.forEach { it.vmName }
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
                        symbol.name, null, classIndices.getValue(declaration.symbol), symbol.paramTypes.drop(1), symbol.returnType,
                    )
                    selectors[function.symbol] = module.selectors.size - 1
                }
            }
        }

        // The VM starts a program from its entry module's first function.
        functions.singleOrNull(::isBox)?.let { module.functions += boxEntry(it as IrSimpleFunction) }
        for (function in bodies) {
            stubbing(function) { module.functions += VmFunctionGenerator(this, function).generate() }
                ?: stub(function)?.let(module.functions::add)
        }
        functions.filter { function -> function.parameters.any { it.defaultValue != null } }.mapTo(module.functions) {
            VmFunctionGenerator.defaultStub(this, it, defaultStub(it))
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
            reportStub("${declaration.render()}: ${e.message}")
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
        for (field in fields) {
            val start = when {
                constantStart(field) != null -> VmGlobalStart.Constant(constantStart(field)!!)
                field.initializer == null -> VmGlobalStart.Zero
                else -> VmGlobalStart.Owned(owner!!)
            }
            globals[field.symbol] = declareGlobal(globalName(field), vmType(field.type) ?: unsupported("a Unit property"), start)
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
                        enumEntries[enumEntry.symbol] = declareGlobal(enumEntryName(enumEntry), anyType, VmGlobalStart.Owned(owner))
                    }
                } else {
                    objectInstances[declaration.symbol] = declareGlobal(instanceName(declaration), anyType, VmGlobalStart.Owned(owner))
                }
            }
            val symbol = declareInitializer("${ownerClass.vmName}::init", owner)
            return@map { module.functions += VmFunctionGenerator.classInitializer(this, symbol, entry.value) }
        }
    }

    private fun globalName(field: IrField): String =
        sanitized(field.correspondingPropertySymbol?.owner?.fqNameWhenAvailable?.asString() ?: field.name.asString())

    private fun enumEntryName(entry: IrEnumEntry): String = "${entry.parentAsClass.vmName}::${entry.name.asString()}"

    private fun instanceName(declaration: IrClass): String = "${declaration.vmName}::INSTANCE"

    internal fun enumEntryGlobal(entry: IrEnumEntry): Int = enumEntries.getOrPut(entry.symbol) {
        importGlobal(entry, enumEntryName(entry), anyType) ?: unsupported("the enum entry ${entry.name} outside the VM modules")
    }

    internal fun globalOf(field: IrField): Int = globals.getOrPut(field.symbol) {
        importGlobal(field, globalName(field), vmType(field.type) ?: unsupported("a Unit property"))
            ?: unsupported("the static field ${field.name} outside the VM modules")
    }

    internal fun objectInstance(declaration: IrClass): Int = objectInstances.getOrPut(declaration.symbol) {
        importGlobal(declaration, instanceName(declaration), anyType) ?: unsupported("the object ${declaration.name} outside the VM modules")
    }

    /** The global [name] of the VM module declaring [declaration], or null when no VM module does. */
    private fun importGlobal(declaration: IrDeclaration, name: String, type: VmType): Int? =
        vmModuleOf(declaration)?.let { owner -> declareGlobal(name, type, VmGlobalStart.Imported(owner)) }

    /** The owner whose initializer a static call to [function] runs first, if any. */
    internal fun ownerOf(function: IrFunction): VmOwner? = functionOwners[function.symbol]

    /**
     * The function filling in [function]'s default arguments, named after it: it takes the function's parameters and a
     * mask with bit `i` set for each parameter `i` a call left out, computes those, and calls the function. Every
     * module generates the stubs of its own functions, and imports another's from it.
     */
    internal fun defaultStub(function: IrFunction): VmFunctionSymbol = defaultStubs.getOrPut(function.symbol) {
        if (function.parameters.size > Long.SIZE_BITS) unsupported("defaults for more than ${Long.SIZE_BITS} parameters")
        val original = functionSymbol(function)
        val symbol = VmFunctionSymbol(sanitized("${original.name}\$default"), original.paramTypes + VmType.I64, original.returnType)
        when {
            function.fileOrNull?.module == fragment -> {}
            vmModuleOf(function) != null -> module.imports += VmImport(vmModuleOf(function)!!, symbol)
            else -> unsupported("a default argument to ${function.render()} outside the VM modules")
        }
        symbol
    }

    private val defaultStubs = hashMapOf<IrFunctionSymbol, VmFunctionSymbol>()

    /** Adds a function only code generation creates, such as a lambda's body. */
    internal fun addFunction(function: VmFunction) {
        module.functions += function
    }

    /** A name for a function only code generation creates, distinct from every other function's in the module. */
    internal fun uniqueFunctionName(base: String): String {
        val name = sanitized(base)
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
            declaration.kind == ClassKind.ANNOTATION_CLASS ->
                unsupported("the ${declaration.kind.codeRepresentation ?: declaration.kind.name} ${declaration.name}")
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
                is IrClass -> stubbing(member) { collectClass(member, classes, functions) }
                is IrEnumEntry -> member.correspondingClass?.let { stubbing(it) { collectClass(it, classes, functions) } }
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

    /**
     * The VM module holding [declaration] when another Kotlin module declares it: the one the VM backend compiled that
     * module into, named as the module is. Null for this module's declarations, and for the standard library's and
     * Java's, which no VM module holds yet.
     */
    private fun vmModuleOf(declaration: IrDeclaration): String? {
        val kotlinModule = declaration.fileOrNull?.module ?: return null
        if (kotlinModule == fragment) return null
        return kotlinModule.name.asStringStripSpecialMarkers()
    }

    private fun runtimeClass(name: String): Int = runtimeClasses.getOrPut(name) {
        module.classes += VmClass(name, null, emptyList(), isOpen = false, isAbstract = false, VmClassKind.Imported(VM_RUNTIME_MODULE))
        module.classes.size - 1
    }

    internal val anyType: VmType get() = VmType.Ref(runtimeClass("Any"))
    internal val stringType: VmType get() = VmType.Ref(runtimeClass("vm.String"))

    /** The class the VM raises for a null dereference, which Kotlin throws for a failed null check too. */
    internal val nullPointerClass: Int get() = runtimeClass("vm.NullReference")

    internal val throwableClass: Int get() = runtimeClass("java.lang.Throwable")

    /** A runtime class by the name the runtime declares it under. */
    internal fun runtimeClassNamed(name: String): Int = runtimeClass(name)

    internal val toStringSelector: Int get() = runtimeSelector("toString")

    /** A selector the runtime declares, on `Any` or on one of the classes [RUNTIME_TYPES] lists, as it declares it. */
    private fun runtimeSelector(name: String): Int = runtimeSelectors.getOrPut(name) {
        val any = runtimeClass("Any")
        module.selectors += when (name) {
            "equals" -> VmSelector(name, VM_RUNTIME_MODULE, any, listOf(anyType), VmType.I64)
            "hashCode" -> VmSelector(name, VM_RUNTIME_MODULE, any, emptyList(), VmType.I64)
            "toString" -> VmSelector(name, VM_RUNTIME_MODULE, any, emptyList(), stringType)
            "message" -> VmSelector(name, VM_RUNTIME_MODULE, runtimeClass("java.lang.Throwable"), emptyList(), stringType)
            "cause" -> VmSelector(name, VM_RUNTIME_MODULE, runtimeClass("java.lang.Throwable"), emptyList(), anyType)
            "compareTo" -> VmSelector(name, VM_RUNTIME_MODULE, runtimeClass("java.lang.Comparable"), listOf(anyType), VmType.I64)
            "length" -> VmSelector(name, VM_RUNTIME_MODULE, runtimeClass("java.lang.CharSequence"), emptyList(), VmType.I64)
            "get" -> VmSelector(name, VM_RUNTIME_MODULE, runtimeClass("java.lang.CharSequence"), listOf(VmType.I64), VmType.I64)
            else -> error("The runtime declares no selector $name on Any")
        }
        module.selectors.size - 1
    }

    internal val equalsSelector: Int get() = runtimeSelector("equals")

    internal val hashCodeSelector: Int get() = runtimeSelector("hashCode")

    /** The runtime's `vm.random.seed`, which the VM stdlib's `kotlin.random.randomSeed` is. */
    internal val randomSeed: VmFunctionSymbol by lazy { runtimeFunction("vm.random.seed", emptyList(), VmType.I64) }

    /** The runtime's `vm.io.print`, which the VM stdlib's `kotlin.io.printString` is. */
    internal val print: VmFunctionSymbol by lazy { runtimeFunction("vm.io.print", listOf(stringType), null) }

    /** A function of the runtime's `java.lang.StringBuilder`, which Kotlin's `StringBuilder` is an alias of. */
    internal fun stringBuilderFunction(name: String, params: List<VmType>, returnType: VmType?): VmFunctionSymbol =
        runtimeFunction("StringBuilder::$name", listOf(anyType) + params, returnType)

    internal val stringBuilderClass: Int get() = runtimeClass("java.lang.StringBuilder")

    /** The runtime class implementing [declaration], one of [RUNTIME_THROWABLES], or null for any other class. */
    internal fun runtimeThrowable(declaration: IrClass): Int? =
        RUNTIME_THROWABLES[declaration.fqNameWhenAvailable?.asString()]?.let(::runtimeClass)

    /** The runtime function storing what one of the JVM's `Throwable` constructors takes. */
    internal val throwableInit: VmFunctionSymbol by lazy {
        runtimeFunction("Throwable::init", listOf(anyType, stringType, anyType), null)
    }

    /** The runtime's box for a primitive: its JVM class, holding the value in its only field. */
    internal fun boxClass(kind: PrimitiveType): Int = runtimeClass("java.lang.${boxName(kind)}")

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

    /** How a value of a primitive [kind] is held: every integral kind, `Char` and `Boolean` as a whole word. */
    internal fun primitiveVmType(kind: PrimitiveType): VmType = when (kind) {
        PrimitiveType.DOUBLE -> VmType.F64
        PrimitiveType.FLOAT -> VmType.F32
        PrimitiveType.BOOLEAN, PrimitiveType.CHAR, PrimitiveType.BYTE, PrimitiveType.SHORT,
        PrimitiveType.INT, PrimitiveType.LONG -> VmType.I64
    }

    /** How a value of [type] is held, or null for a type with no values to hold: `Unit` and `Nothing`. */
    internal fun vmType(type: IrType): VmType? {
        if (type.isUnit() || type.isNothing()) return null
        if (type.isPrimitiveType()) return primitiveVmType(type.getPrimitiveType()!!)
        if (type.isString() || type.isNullableString()) return stringType
        return anyType
    }

    // Classes.

    /**
     * The runtime class implementing [declaration], a class of the standard library the runtime declares in its stead:
     * `Any`, `String`, a primitive's box, and the classes [RUNTIME_TYPES] and [RUNTIME_THROWABLES] list. Null for any
     * other class.
     */
    private fun runtimeClassOf(declaration: IrClass): Int? {
        val fqName = declaration.fqNameWhenAvailable?.asString()
        return when {
            declaration.isAny() -> runtimeClass("Any")
            declaration.symbol == irBuiltIns.stringClass -> runtimeClass("vm.String")
            declaration.isEnumBase() -> runtimeClass("java.lang.Enum")
            isSharedVariableBox(declaration) -> sharedVariableBoxClass
            declaration.defaultType.isPrimitiveType() -> boxClass(declaration.defaultType.getPrimitiveType()!!)
            // Every function value is an instance of `vm.Function`, whatever its arity; see wip/FUNCTION_VALUES_DESIGN.md.
            fqName == "kotlin.Function" || declaration.defaultType.isFunctionOrKFunction() -> runtimeClass("vm.Function")
            else -> RUNTIME_TYPES[fqName]?.let(::runtimeClass) ?: runtimeThrowable(declaration)
        }
    }

    /**
     * Whether the standard library's [declaration] needs no class of its own in the VM: the runtime implements it, or it
     * is one the backend compiles to something else — an array, `Unit` and `Nothing`.
     */
    private fun isRuntimeImplemented(declaration: IrClass): Boolean =
        runtimeClassOf(declaration) != null || arrayClassOf(declaration.defaultType) != null ||
                declaration.symbol == irBuiltIns.unitClass || declaration.symbol == irBuiltIns.nothingClass

    /** The class standing for [declaration]: the runtime's, this module's, or one imported from the VM module declaring it. */
    internal fun classIndex(declaration: IrClass): Int? = runtimeClassOf(declaration) ?: classIndices[declaration.symbol] ?: vmModuleOf(declaration)?.let { owner ->
        module.classes += VmClass(declaration.vmName, null, emptyList(), isOpen = false, isAbstract = false, VmClassKind.Imported(owner))
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
        if (type.isAny() || type.isNullableAny()) return null
        return testedClassOrNull(type) ?: unsupported("a type test against ${type.render()} outside the VM modules")
    }

    /** The class [type] names, `Any` included, or null for a class no VM module declares. */
    private fun testedClassOrNull(type: IrType): Int? {
        if (type.isAny() || type.isNullableAny()) return runtimeClass("Any")
        arrayClassOf(type)?.let { return it }
        if (type.isString() || type.isNullableString()) return runtimeClass("vm.String")
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
            else -> unsupported("the superclass ${superClass.name} outside the VM modules")
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
        if (runtimeClassOf(owner) != null || classIndex(owner) == null) unsupported("the field ${field.name} of ${owner.name}")
        return fieldLayout(owner).indexOfFirst { it.declared == field }
    }

    /**
     * The name the JVM gives the class: its package, then its name after those of the classes nesting it, joined by `$`.
     * What the runtime's `Any.toString` shows.
     */
    private val IrClass.jvmName: String
        get() {
            val nesting = generateSequence(this) { it.parent as? IrClass }.toList().asReversed()
            val packageName = getPackageFragment().packageFqName
            val simpleNames = nesting.joinToString("$") { it.name.asString() }
            return if (packageName.isRoot) simpleNames else "${packageName.asString()}.$simpleNames"
        }

    /**
     * A class's name in the VM: its [jvmName]. A class other modules can name, by its public signature, keeps it
     * exactly; a local or anonymous one, which lowering has moved out of its function, gets a counter when another
     * class of the module has the name already.
     */
    private val IrClass.vmName: String
        get() = classNames.getOrPut(symbol) {
            val name = sanitized(jvmName)
            if (usedClassNames.add(name) || symbol.signature is IdSignature.CommonSignature) return@getOrPut name
            generateSequence(1) { it + 1 }.map { "${name}_$it" }.first(usedClassNames::add)
        }

    private val classNames = hashMapOf<IrClassSymbol, String>()
    private val usedClassNames = hashSetOf<String>()

    private fun classDefinition(declaration: IrClass): VmClass {
        val interfaces = declaration.superTypes.mapNotNull { it.classOrNull?.owner }.filter { it.isInterface }.mapNotNull {
            // A marker interface the VM cannot name, such as `java.io.Serializable`, only a type test could ask about.
            if (it.isMarkerOutsideVm()) return@mapNotNull null
            // A function type stands for `vm.Function`, the class every function value has, which an interface cannot
            // extend and a class gains nothing by naming: a value is a function value only when the VM made it one.
            // A class calling itself one is what the VM cannot express.
            val isFunctionType = it.fqNameWhenAvailable?.asString() == "kotlin.Function" || it.defaultType.isFunctionOrKFunction()
            if (isFunctionType && (declaration.isInterface || !it.defaultType.isFunctionOrKFunction())) return@mapNotNull null
            if (isFunctionType) unsupported("implementing the function type ${it.name}")
            classIndex(it) ?: unsupported("the supertype ${it.name} outside the VM modules")
        }
        if (declaration.isInterface) {
            return VmClass(declaration.vmName, null, interfaces, isOpen = true, isAbstract = true, VmClassKind.Interface)
        }
        val superClass = declaration.superClass()
        val superIndex = when {
            superClass == null || superClass.isAny() -> runtimeClass("Any")
            superClass.isEnumBase() -> runtimeClass("java.lang.Enum")
            runtimeThrowable(superClass) != null -> runtimeThrowable(superClass)!!
            else -> classIndex(superClass) ?: unsupported("the superclass ${superClass.name} outside the VM modules")
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
                // Answered by the runtime's `Enum`, or by `Any` through it, but for an interface's selector.
                implementation.parentClassOrNull?.isEnumBase() == true -> {
                    for (root in function.roots()) {
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
                    if (isJava || (implementation.symbol !in definedFunctions && implementation.fileOrNull?.module == fragment)) continue
                    if (implementation.symbol !in definedFunctions) {
                        importFunction(implementation) ?: unsupported("inheriting ${implementation.render()} outside the VM modules")
                    } else {
                        functionSymbol(implementation)
                    }
                }
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

    /**
     * The function of an enum answering [selector], which an interface it implements declares, with the runtime
     * `Enum`'s [member]: the runtime cannot answer a selector of the module.
     */
    private fun enumAdapter(declaration: IrClass, member: IrSimpleFunction, selector: VmSelector): VmFunctionSymbol =
        enumAdapters.getOrPut(declaration.symbol to member.name.asString()) {
            val [field, type] = when (member.name.asString()) {
                "<get-name>" -> VmFunctionGenerator.ENUM_NAME_FIELD to stringType
                "<get-ordinal>" -> VmFunctionGenerator.ENUM_ORDINAL_FIELD to VmType.I64
                else -> unsupported("implementing an interface with ${member.render()}")
            }
            if (selector.paramTypes.isNotEmpty() || selector.returnType != type) unsupported("a bridge to ${member.render()}")
            val symbol = VmFunctionSymbol(sanitized("${declaration.vmName}::${member.name.asString()}"), listOf(anyType), type)
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
        if (root.parentClassOrNull?.fqNameWhenAvailable?.asString() in RUNTIME_TYPES) return runtimeTypeSelector(root)
        if (root.parentClassOrNull?.let(::runtimeThrowable) != null) return throwableSelector(root.name.asString())
        if (!root.isOverridable()) return null
        return selectors[root.symbol] ?: importSelector(root) ?: unsupported("overriding ${root.render()} outside the VM modules")
    }

    /** The runtime's selector for [root], a member of one of the classes [RUNTIME_TYPES] lists. */
    private fun runtimeTypeSelector(root: IrSimpleFunction): Int = when (val name = root.name.asString()) {
        "compareTo", "get" -> runtimeSelector(name)
        "<get-length>" -> runtimeSelector("length")
        else -> unsupported("overriding ${root.render()}")
    }

    /** The selector [root] declares in the VM module declaring it, or null when no VM module does. */
    private fun importSelector(root: IrSimpleFunction): Int? {
        val owner = vmModuleOf(root) ?: return null
        val symbol = functionSymbol(root)
        module.selectors += VmSelector(symbol.name, owner, classIndex(root.parentAsClass)!!, symbol.paramTypes.drop(1), symbol.returnType)
        return (module.selectors.size - 1).also { selectors[root.symbol] = it }
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
        val symbol = if (callee.symbol in definedFunctions) {
            functionSymbol(callee)
        } else {
            importFunction(callee) ?: unsupported("a call to ${callee.render()} outside the VM modules")
        }
        return CallTarget(VmCallee.Static(symbol), symbol)
    }

    /** [function] as the VM module declaring it defines it, imported, or null when no VM module does. */
    private fun importFunction(function: IrFunction): VmFunctionSymbol? {
        val owner = vmModuleOf(function) ?: return null
        val symbol = functionSymbol(function)
        if (module.imports.none { it.symbol == symbol }) module.imports += VmImport(owner, symbol)
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
                val root = function.roots().first()
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
     * A function's name in the VM: its qualified name — a member's as `Class::name` — then the hash its public signature
     * carries, which is what tells overloads apart. A function without one is private to the module, so a counter
     * tells its overloads apart instead.
     */
    private fun vmName(function: IrFunction): String {
        val owner = function.parentClassOrNull
        val base = if (owner != null) "${owner.vmName}::${function.name.asString()}"
        else function.fqNameWhenAvailable?.asString() ?: function.name.asString()
        val hash = signatureHash(function.symbol.signature)
        val name = sanitized(if (hash == null) base else "${base}_${java.lang.Long.toHexString(hash)}")
        // Another module's function is named as that module names it, which only a public signature makes stable.
        if (vmModuleOf(function) != null) return if (hash == null) unsupported("naming ${function.render()} of another module") else name
        if (usedFunctionNames.add(name)) return name
        return generateSequence(1) { it + 1 }.map { "${name}_$it" }.first(usedFunctionNames::add)
    }

    private val usedFunctionNames = hashSetOf<String>()

    /** An accessor's hash is its own; its property's would be the same for the getter and the setter. */
    private fun signatureHash(signature: IdSignature?): Long? = when (signature) {
        is IdSignature.CommonSignature -> signature.id
        is IdSignature.AccessorSignature -> signature.accessorSignature.id
        is IdSignature.CompositeSignature -> signatureHash(signature.inner)
        else -> null
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
            append(if (c.isLetterOrDigit() || c == '_' || c == '.' || c == '$') c else '_')
        }
    }
}

/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.pipeline

import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.common.output.OutputFile
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.backend.vm.jvmExportName
import org.jetbrains.kotlin.backend.vm.jvmInternalName
import org.jetbrains.kotlin.backend.vm.jvmMemberName
import org.jetbrains.kotlin.backend.vm.vmClassName
import org.jetbrains.kotlin.backend.vm.vmFunctionName
import org.jetbrains.kotlin.backend.vm.vmOverridden
import org.jetbrains.kotlin.backend.vm.vmRoots
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.jvm.compiler.CompileEnvironmentUtil
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.cli.vm.config.vmProxies
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendClassResolver
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendExtension
import org.jetbrains.kotlin.fir.pipeline.Fir2IrActualizedResult
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.jklib.JKlibIrMangler
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.DeepCopySymbolRemapper
import org.jetbrains.kotlin.ir.util.DeepCopyTypeRemapper
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.io.File

/**
 * Writes the jar of the module's proxies, which `-Xvm-proxies` names: the module compiled for the JVM with each body
 * replaced by a call into the VM, through `vm.bridge.VmBridge`. The JVM's fragments compile against the jar in place of
 * the module's own classes; see "Proxies" in wip/JVM_INTEGRATION_DESIGN.md in the VM repository.
 *
 * - A public top-level function calls the export the module has for it, by the name it exports it under.
 * - A public class is a shell, which `META-INF/vm.shells` in the jar names with the VM class it stands for: a member that
 *   may be overridden calls its selector, any other its VM function, and a constructor the VM function initializing a
 *   new VM object of the class, which the shell adopts.
 *
 * Generated from a copy of the IR the klib was written from, which [VmCodegenPhase] then lowers for the VM.
 */
object VmProxyPhase : PipelinePhase<VmSerializationArtifact, VmSerializationArtifact>(
    name = "VmProxyPhase",
    postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
) {
    /** The shell list's path in the jar; see `vm.bridge.ShellList`. */
    const val SHELL_LIST = "META-INF/vm.shells"

    override fun executePhase(input: VmSerializationArtifact): VmSerializationArtifact {
        val configuration = input.configuration
        val output = configuration.vmProxies ?: return input
        val fir2IrResult = input.fir2IrResult ?: return input

        val module = fir2IrResult.irModuleFragment.copyWithMetadata()
        val proxies = Proxies(VmBridge(module, fir2IrResult.irBuiltIns), fir2IrResult)
        proxies.proxyModule(module)

        val state = GenerationState(
            input.projectEnvironment.project,
            module.descriptor,
            configuration,
            builderFactory = ClassBuilderFactories.BINARIES,
            moduleName = configuration.moduleName,
            diagnosticReporter = configuration.diagnosticsCollector,
            jvmBackendClassResolver = FirJvmBackendClassResolver(fir2IrResult.components),
        )
        val backendInput = with(fir2IrResult) {
            JvmIrCodegenFactory.BackendInput(
                module, irBuiltIns, symbolTable, components.irProviders, debuggerExtensions = null,
                FirJvmBackendExtension(components, actualizedExpectDeclarations = null), pluginContext,
            )
        }
        val codegenFactory = JvmIrCodegenFactory(configuration)
        codegenFactory.invokeCodegen(codegenFactory.invokeLowerings(state, backendInput))
        val shellList = proxies.shells.joinToString("") { [jvm, vm] -> "class $jvm $vm\n" }
        // No runtime, no reflection, reset timestamps, no main class.
        CompileEnvironmentUtil.writeToJar(File(output), false, true, true, null, state.factory.with(SHELL_LIST, shellList), configuration)
        return input
    }

    /** A copy of [this], with the Kotlin metadata the JVM backend writes into the classes, which a plain copy leaves out. */
    private fun IrModuleFragment.copyWithMetadata(): IrModuleFragment {
        val symbols = DeepCopySymbolRemapper()
        acceptVoid(symbols)
        val copier = object : DeepCopyIrTreeWithSymbols(symbols, DeepCopyTypeRemapper(symbols)) {
            override fun <D : IrElement> D.processAttributes(other: IrElement) {
                copyAttributes(other)
                if (this is IrMetadataSourceOwner && other is IrMetadataSourceOwner) metadata = other.metadata
            }
        }
        return transform(copier, null).also { it.patchDeclarationParents() }
    }

    /** [this], and a text file at [path]. */
    private fun OutputFileCollection.with(path: String, text: String): OutputFileCollection {
        val file = object : OutputFile {
            override val relativePath: String get() = path
            override val sourceFiles: List<File> get() = emptyList()
            override fun asByteArray(): ByteArray = text.toByteArray()
            override fun asText(): String = text
        }
        val files = asList() + file
        return object : OutputFileCollection {
            override fun get(relativePath: String): OutputFile? = files.firstOrNull { it.relativePath == relativePath }
            override fun asList(): List<OutputFile> = files
        }
    }
}

/** Turns the module's IR into its proxies, recording each shell class. */
private class Proxies(private val bridge: VmBridge, private val fir2IrResult: Fir2IrActualizedResult) {
    private val irBuiltIns = fir2IrResult.irBuiltIns
    private val mangler = JKlibIrMangler()

    /** Each shell class's internal name, with the name of the VM class it stands for. */
    val shells = mutableListOf<Pair<String, String>>()

    /** The chain constructor of each shell class; see [chainConstructor]. */
    private val chains = hashMapOf<IrClass, IrConstructor>()

    /**
     * Keeps what the JVM may call of [module] and replaces every body with a call into the VM. The chain constructors are
     * made first, a superclass's before its subclasses', since every constructor of a shell runs its class's.
     */
    fun proxyModule(module: IrModuleFragment) {
        val classes = mutableListOf<IrClass>()
        fun retain(container: IrDeclarationContainer) {
            container.declarations.retainAll { it.isProxied() }
            container.declarations.filterIsInstance<IrClass>().forEach { classes += it; retain(it) }
        }
        module.files.forEach(::retain)
        val shellClasses = classes.filterTo(hashSetOf()) { it.kind == ClassKind.CLASS }
        fun chain(irClass: IrClass): IrConstructor = chains[irClass] ?: run {
            val superclass = irClass.superclass()
            if (superclass in shellClasses) chain(superclass)
            makeShell(irClass, holdsHandle = superclass !in shellClasses)
            chainConstructor(irClass, superclass).also { chains[irClass] = it }
        }
        shellClasses.forEach(::chain)

        for (file in module.files) proxyContainer(file)
        for (irClass in classes) proxyContainer(irClass)
    }

    /**
     * Whether [this] has a proxy: a public or protected function, property, constructor, class or interface, other than
     * an inner or value class. An `expect` function the JVM backend leaves out itself; an `expect` class is the JVM's `actual`.
     */
    private fun IrDeclaration.isProxied(): Boolean = when (this) {
        is IrClass -> !isExpect && (kind == ClassKind.CLASS || kind == ClassKind.INTERFACE) && !isValue && !isInner && isVisible()
        is IrSimpleFunction -> isVisible()
        is IrProperty -> isVisible()
        is IrConstructor -> isVisible()
        else -> false
    }

    private fun IrDeclarationWithVisibility.isVisible(): Boolean =
        visibility == DescriptorVisibilities.PUBLIC || visibility == DescriptorVisibilities.PROTECTED

    /** The member of `Any` [this] overrides, if it overrides `equals`, `hashCode` or `toString`. */
    private fun IrSimpleFunction.anyRoot(): IrSimpleFunction? = vmRoots().firstOrNull { it.parent == irBuiltIns.anyClass.owner }

    /** [function]'s name in the VM, with the hash of its public signature; see [vmFunctionName]. */
    private fun vmName(function: IrFunction): String = vmFunctionName(function, hash = with(mangler) { function.signatureMangle(false) })

    /** Records [irClass] as a shell, which implements `VmShell` and holds its VM object's handle in `$vm$handle` if [holdsHandle]. */
    private fun makeShell(irClass: IrClass, holdsHandle: Boolean) {
        shells += irClass.jvmInternalName to vmClassName(irClass)
        if (holdsHandle) {
            irClass.superTypes += bridge.shell.defaultType
            irClass.addField(Name.identifier("\$vm\$handle"), irBuiltIns.longType, DescriptorVisibilities.PROTECTED)
        }
    }

    /** The class [this] extends, rather than implements. */
    private fun IrClass.superclass(): IrClass =
        superTypes.firstNotNullOfOrNull { type -> type.classOrNull?.owner?.takeUnless { it.isInterface } } ?: irBuiltIns.anyClass.owner

    /**
     * The constructor every constructor of the shell [irClass] runs first, which only runs its [superclass]'s, so that a
     * VM object is made once, by the VM function of the most derived class's constructor, however deep the chain of
     * shells: `protected Owner(VmShell unused) { super(unused) }`, or `super()` when the superclass is no shell.
     */
    private fun chainConstructor(irClass: IrClass, superclass: IrClass): IrConstructor {
        val superChain = chains[superclass]
        val superConstructor = superChain ?: superclass.constructors.singleOrNull { it.parameters.isEmpty() }
            ?: error("${irClass.name}'s superclass ${superclass.name} has no constructor without parameters to chain to")
        val chain = irClass.addConstructor {
            visibility = DescriptorVisibilities.PROTECTED
            returnType = irClass.defaultType
        }
        val unused = chain.addValueParameter("unused", bridge.shell.defaultType.makeNullable())
        val superType = irClass.superTypes.firstOrNull { it.classOrNull?.owner == superclass } as? IrSimpleType
        chain.body = DeclarationIrBuilder(fir2IrResult.pluginContext, chain.symbol).irBlockBody {
            +irDelegatingConstructorCall(superConstructor).apply {
                superType?.arguments?.forEachIndexed { index, argument -> typeArguments[index] = argument.typeOrNull }
                if (superChain != null) arguments[0] = irGet(unused)
            }
        }
        return chain
    }

    /** Replaces the body of each function, accessor and constructor of [container], but its chain constructor. */
    private fun proxyContainer(container: IrDeclarationContainer) {
        val tokens = Tokens(container)
        val isInterface = (container as? IrClass)?.isInterface == true
        fun proxy(function: IrSimpleFunction) = when {
            function.body == null -> {}
            container is IrFile -> proxyExport(function, tokens)
            // A default body runs on any implementation, which the JVM may have; none crosses yet.
            isInterface -> unsupported(function)
            else -> proxyMember(function, tokens)
        }
        for (declaration in container.declarations.toList()) {
            when (declaration) {
                is IrSimpleFunction -> proxy(declaration)
                is IrProperty -> {
                    declaration.backingField = null
                    listOfNotNull(declaration.getter, declaration.setter).forEach(::proxy)
                }
                is IrConstructor -> if (declaration != chains[container]) proxyConstructor(declaration, tokens)
                else -> {}
            }
        }
    }

    /** A top-level function's proxy calls the export the module has for it. */
    private fun proxyExport(function: IrSimpleFunction, tokens: Tokens) {
        val name = jvmExportName(function) ?: return unsupported(function)
        proxy(function, tokens, { exportToken(name) }) { token, arguments -> callToken(token, arguments) }
    }

    /** A member that may be overridden calls its selector, which dispatches on the receiver's VM class; any other its VM function. */
    private fun proxyMember(function: IrSimpleFunction, tokens: Tokens) {
        val binding = jvmMemberName(function) ?: return unsupported(function)
        val receiver = function.dispatchReceiverParameter!!
        val isOverridable = function.vmOverridden().isNotEmpty() ||
                function.modality != Modality.FINAL && function.parentAsClass.modality != Modality.FINAL
        val anyRoot = function.anyRoot()
        if (anyRoot != null && anyRoot.name == OperatorNameConventions.EQUALS) {
            proxyEquals(function, tokens, binding)
        } else if (isOverridable) {
            // The runtime declares `Any`'s selectors, under its own names rather than the module's.
            val selector = anyRoot?.let { "Any::${it.name}" } ?: vmName(function.vmRoots().first())
            proxy(function, tokens, { methodToken(selector, binding) }) { token, arguments ->
                callMethodToken(token, builder.irGet(receiver), arguments)
            }
        } else {
            val name = vmName(function)
            proxy(function, tokens, { functionToken(name, binding) }) { token, arguments ->
                callFunctionToken(token, builder.irGet(receiver), arguments)
            }
        }
    }

    /** `equals` calls `Any`'s selector, but a JVM object that is no shell is no VM object, so it equals none. */
    private fun proxyEquals(function: IrSimpleFunction, tokens: Tokens, binding: String) {
        val receiver = function.dispatchReceiverParameter!!
        val other = function.parameters.single { it.kind == IrParameterKind.Regular }
        proxy(
            function, tokens, { methodToken("Any::equals", binding) },
            guard = { irIfThen(irNot(irIs(irGet(other), bridge.shell.defaultType)), irReturn(irFalse())) },
        ) { token, arguments -> callMethodToken(token, builder.irGet(receiver), arguments) }
    }

    /**
     * Replaces [function]'s body with a call through the token [lookUp] answers, kept in a private static field of its
     * class or file after the first call, answering what [call] answers.
     */
    private fun proxy(
        function: IrSimpleFunction,
        tokens: Tokens,
        lookUp: VmBridge.Calls.() -> IrExpression,
        guard: (IrBuilderWithScope.() -> IrExpression)? = null,
        call: VmBridge.Calls.(token: IrExpression, arguments: IrExpression) -> IrExpression,
    ) {
        val token = tokens.next()
        function.body = DeclarationIrBuilder(fir2IrResult.pluginContext, function.symbol).irBlockBody {
            guard?.let { +it() }
            val calls = bridge.Calls(this)
            +lookUpOnce(token) { calls.lookUp() }
            val result = calls.call(irGetField(null, token), argumentsOf(function))
            +if (function.returnType.isUnit()) result else irReturn(irAs(result, function.returnType))
        }
    }

    /** A constructor runs the chain constructor, then the VM function initializing a new VM object, which the shell adopts. */
    private fun proxyConstructor(constructor: IrConstructor, tokens: Tokens) {
        val irClass = constructor.parentAsClass
        val binding = jvmMemberName(constructor)
        val token = binding?.let { tokens.next() }
        constructor.body = DeclarationIrBuilder(fir2IrResult.pluginContext, constructor.symbol).irBlockBody {
            +irDelegatingConstructorCall(chains.getValue(irClass)).apply { arguments[0] = irNull() }
            if (binding == null || token == null) {
                +throwUnsupported(constructor)
                return@irBlockBody
            }
            val calls = bridge.Calls(this)
            +lookUpOnce(token) { calls.constructorToken(vmClassName(irClass), vmName(constructor), binding) }
            +calls.callFunctionToken(irGetField(null, token), irGet(irClass.thisReceiver!!), argumentsOf(constructor))
        }
    }

    /** `if (token == 0L) token = lookUp()`: a race only looks the call up twice, to the same token. */
    private fun IrBuilderWithScope.lookUpOnce(token: IrField, lookUp: () -> IrExpression): IrExpression =
        irIfThen(irEquals(irGetField(null, token), irLong(0)), irSetField(null, token, lookUp()))

    /** The arguments [function] takes but its receiver, as the bridge takes them: boxed, in an array. */
    private fun IrBuilderWithScope.argumentsOf(function: IrFunction): IrExpression {
        val arguments = function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.map { irGet(it) }
        return IrVarargImpl(startOffset, endOffset, bridge.anyArray, irBuiltIns.anyNType, arguments)
    }

    /** A function the VM has no name for the JVM to call it by, for a type the binding does not cover, throws. */
    private fun unsupported(function: IrSimpleFunction) {
        function.body = DeclarationIrBuilder(fir2IrResult.pluginContext, function.symbol).irBlockBody {
            +throwUnsupported(function)
        }
    }

    private fun IrBuilderWithScope.throwUnsupported(function: IrFunction): IrExpression =
        irCall(irBuiltIns.illegalArgumentExceptionSymbol).apply {
            arguments[0] = irString("${function.name} has no proxy: its signature is not one the VM can be called by from the JVM")
        }

    /** The private static fields of a class or file, each holding one call's token. */
    private inner class Tokens(private val container: IrDeclarationContainer) {
        private var count = 0

        fun next(): IrField = irBuiltIns.irFactory.buildField {
            name = Name.identifier("\$vm\$token\$${count++}")
            type = irBuiltIns.longType
            isStatic = true
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            parent = container as IrDeclarationParent
            container.declarations += this
        }
    }
}

/**
 * The JVM's `vm.bridge`, declared as the external Java classes it is for the JVM backend to call into: the `VmShell`
 * interface every shell implements, and the static methods of `VmBridge` a proxy calls. Each `*Token` method answers a
 * call's handle plus one, so that a zero field means one not looked up yet, and each `call*Token` calls the call a token
 * names, answering its result boxed.
 */
private class VmBridge(module: IrModuleFragment, private val irBuiltIns: IrBuiltIns) {
    val anyArray = irBuiltIns.arrayClass.typeWith(irBuiltIns.anyNType)

    private val fragment = createEmptyExternalPackageFragment(module, FqName("vm.bridge"))

    private fun javaClass(name: String, kind: ClassKind): IrClass = irBuiltIns.irFactory.buildClass {
        this.name = Name.identifier(name)
        this.kind = kind
        origin = IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB
    }.apply {
        parent = fragment
        fragment.declarations += this
        createThisReceiverParameter()
    }

    val shell: IrClass = javaClass("VmShell", ClassKind.INTERFACE)

    private val bridge: IrClass = javaClass("VmBridge", ClassKind.CLASS)

    private fun method(name: String, returnType: IrType, vararg parameters: Pair<String, IrType>): IrSimpleFunction =
        bridge.addFunction(name, returnType, isStatic = true, origin = IrDeclarationOrigin.IR_EXTERNAL_JAVA_DECLARATION_STUB).apply {
            for ([parameterName, type] in parameters) addValueParameter(parameterName, type)
            // An array to the JVM; a vararg here only so that the JVM backend builds it from the arguments.
            parameters.lastOrNull()?.takeIf { it.second == anyArray }?.let { this.parameters.last().varargElementType = irBuiltIns.anyNType }
        }

    private val string = irBuiltIns.stringType
    private val long = irBuiltIns.longType
    private val any = irBuiltIns.anyNType

    private val exportToken = method("exportToken", long, "name" to string)
    private val methodToken = method("methodToken", long, "vmName" to string, "jvmName" to string)
    private val functionToken = method("functionToken", long, "vmName" to string, "jvmName" to string, "receiver" to irBuiltIns.booleanType)
    private val constructorToken = method("constructorToken", long, "vmClass" to string, "vmName" to string, "jvmName" to string)
    private val callToken = method("callToken", any, "token" to long, "args" to anyArray)
    private val callMethodToken = method("callMethodToken", any, "token" to long, "receiver" to any, "args" to anyArray)
    private val callFunctionToken = method("callFunctionToken", any, "token" to long, "receiver" to any, "args" to anyArray)

    /** The calls of the bridge's methods, built by [builder]. */
    inner class Calls(val builder: IrBuilderWithScope) {
        private fun call(method: IrSimpleFunction, vararg arguments: IrExpression) = builder.irCall(method).apply {
            arguments.forEachIndexed { index, argument -> this.arguments[index] = argument }
        }

        private fun string(value: String) = builder.irString(value)

        fun exportToken(name: String) = call(exportToken, string(name))
        fun methodToken(vmName: String, jvmName: String) = call(methodToken, string(vmName), string(jvmName))
        fun functionToken(vmName: String, jvmName: String) = call(functionToken, string(vmName), string(jvmName), builder.irTrue())
        fun constructorToken(vmClass: String, vmName: String, jvmName: String) =
            call(constructorToken, string(vmClass), string(vmName), string(jvmName))

        fun callToken(token: IrExpression, args: IrExpression) = call(callToken, token, args)
        fun callMethodToken(token: IrExpression, receiver: IrExpression, args: IrExpression) = call(callMethodToken, token, receiver, args)
        fun callFunctionToken(token: IrExpression, receiver: IrExpression, args: IrExpression) =
            call(callFunctionToken, token, receiver, args)
    }
}

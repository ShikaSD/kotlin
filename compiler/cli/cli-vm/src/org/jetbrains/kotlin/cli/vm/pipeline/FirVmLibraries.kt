/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.pipeline

import org.jetbrains.kotlin.backend.common.serialization.NonLinkingIrInlineFunctionDeserializer
import org.jetbrains.kotlin.backend.common.serialization.signature.PublicIdSignatureComputer
import org.jetbrains.kotlin.backend.vm.VmLibraries
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.lazy.AbstractFir2IrLazyDeclaration
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.backend.jklib.JKlibIrMangler
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.IrErrorModuleFragment
import org.jetbrains.kotlin.ir.util.DeepCopySymbolRemapper
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.DeepCopyTypeRemapper
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.originalOfPreparedInlineFunctionCopy
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * The libraries as the frontend read them. A klib is a VM library and a jar is the host's, each set its own module data,
 * [vmModules] the klibs'; see [VmFrontendPipelinePhase].
 */
internal class FirVmLibraries(private val vmModules: Set<FirModuleData>, private val irBuiltIns: IrBuiltIns) : VmLibraries {
    private val signatures = PublicIdSignatureComputer(JKlibIrMangler())
    private val inlineFunctions = NonLinkingIrInlineFunctionDeserializer(irBuiltIns, signatures)
    private val libraryFile =
        IrFileImpl(NaiveSourceBasedFileEntryImpl("<library>"), IrFileSymbolImpl(), FqName.ROOT, IrErrorModuleFragment).symbol

    override fun isInVmLibrary(declaration: IrDeclaration): Boolean {
        var topLevel = declaration
        while (true) topLevel = topLevel.parent as? IrDeclaration ?: break
        val fir = (topLevel as? AbstractFir2IrLazyDeclaration<*>)?.fir ?: return false
        // The Java facade gives every Java class one of the sets, whichever it was handed, so the origin is what tells.
        return fir.origin !is FirDeclarationOrigin.Java && fir.moduleData in vmModules
    }

    override fun publicSignature(declaration: IrDeclaration): IdSignature? {
        if (!with(signatures.mangler) { declaration.isExported(compatibleMode = false) }) return null
        // A private top-level declaration's signature is its file's, which tells it from another file's. A library's has no
        // file, and stands in a file of its own: its name hashes only the part within the file.
        val file = declaration.fileOrNull?.symbol ?: libraryFile
        return signatures.inFile(file) { signatures.computeSignature(declaration) }
    }

    /**
     * The klib's copy names what it calls by signature alone, through symbols bound to nothing, so it is copied once more
     * onto the declarations the frontend has for them, which is what code generation reads.
     */
    override fun inlineFunction(function: IrSimpleFunction): IrSimpleFunction? {
        val deserialized = inlineFunctions.deserializeInlineFunction(function) ?: return null
        val remapper = BindingSymbolRemapper()
        deserialized.acceptVoid(remapper)
        val copy = deserialized.transform(DeepCopyIrTreeWithSymbols(remapper, DeepCopyTypeRemapper(remapper)), null) as IrSimpleFunction
        copy.patchDeclarationParents(deserialized.parent)
        copy.originalOfPreparedInlineFunctionCopy = function
        return copy
    }

    /**
     * Remaps a symbol bound to nothing onto the declaration with its signature, as [bound] finds it. Any other is the
     * body's own declaration, copied with it: its copy is bound only once it is built, so the original is what tells.
     */
    private inner class BindingSymbolRemapper : DeepCopySymbolRemapper() {
        override fun getReferencedClass(symbol: IrClassSymbol): IrClassSymbol =
            if (symbol.isBound) super.getReferencedClass(symbol) else bound(symbol)

        override fun getReferencedEnumEntry(symbol: IrEnumEntrySymbol): IrEnumEntrySymbol =
            if (symbol.isBound) super.getReferencedEnumEntry(symbol) else bound(symbol)

        override fun getReferencedField(symbol: IrFieldSymbol): IrFieldSymbol =
            if (symbol.isBound) super.getReferencedField(symbol) else bound(symbol)

        override fun getReferencedConstructor(symbol: IrConstructorSymbol): IrConstructorSymbol =
            if (symbol.isBound) super.getReferencedConstructor(symbol) else bound(symbol)

        override fun getReferencedSimpleFunction(symbol: IrSimpleFunctionSymbol): IrSimpleFunctionSymbol =
            if (symbol.isBound) super.getReferencedSimpleFunction(symbol) else bound(symbol)

        override fun getReferencedProperty(symbol: IrPropertySymbol): IrPropertySymbol =
            if (symbol.isBound) super.getReferencedProperty(symbol) else bound(symbol)

        override fun getReferencedTypeParameter(symbol: IrTypeParameterSymbol): IrClassifierSymbol =
            if (symbol.isBound) super.getReferencedTypeParameter(symbol) else bound(symbol)
    }

    private inline fun <reified S : IrSymbol> bound(symbol: S): S {
        val signature = symbol.signature ?: error("An inline function's body names a declaration with no signature")
        return declarationOf(signature)?.symbol as? S ?: error("No declaration has the signature $signature")
    }

    /** The declaration whose signature [signature] is, found from the outermost declaration it names inward. */
    @OptIn(InternalSymbolFinderAPI::class)
    private fun declarationOf(signature: IdSignature): IrSymbolOwner? = when (signature) {
        is IdSignature.CommonSignature -> {
            val packageName = FqName(signature.packageFqName)
            val path = FqName(signature.declarationFqName).pathSegments()
            val candidates = if (path.size == 1) {
                val callable = CallableId(packageName, path.single())
                // The operators the frontend lowers comparisons to, such as `EQEQ`, are the built-ins' own, which no library holds.
                val operators = irBuiltIns.operatorsPackageFragment.takeIf { it.packageFqName == packageName }?.declarations.orEmpty()
                listOfNotNull(irBuiltIns.symbolFinder.findClass(ClassId(packageName, path.single()))) +
                        irBuiltIns.symbolFinder.findFunctions(callable) + irBuiltIns.symbolFinder.findProperties(callable) +
                        operators.map { it.symbol }
            } else {
                val outer = IdSignature.CommonSignature(
                    signature.packageFqName, path.dropLast(1).joinToString("."), id = null, mask = 0, description = null,
                )
                (declarationOf(outer) as? IrClass)?.declarations.orEmpty().map { it.symbol }
            }
            candidates.map { it.owner as IrDeclaration }.firstOrNull { publicSignature(it) == signature }
        }
        is IdSignature.AccessorSignature -> (declarationOf(signature.propertySignature) as? IrProperty)
            ?.let { listOfNotNull(it.getter, it.setter) }.orEmpty().firstOrNull { publicSignature(it) == signature }
        is IdSignature.CompositeSignature -> (declarationOf(signature.container) as? IrTypeParametersContainer)
            ?.typeParameters.orEmpty().firstOrNull { signatures.computeSignature(it) == signature }
        else -> null
    }
}

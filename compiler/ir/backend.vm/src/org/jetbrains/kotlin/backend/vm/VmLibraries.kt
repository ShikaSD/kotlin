/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.vm

import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.IdSignature

/**
 * What the backend learns from the compilation about the libraries the module is compiled against, whose declarations
 * reach it as the frontend's lazy IR: without bodies, and without the signatures a klib would give them.
 */
interface VmLibraries {
    /**
     * Whether a VM library defines [declaration], a declaration outside the module: the module then imports it by name,
     * which the VM linker resolves among the program's modules. Otherwise the host defines it.
     */
    fun isInVmLibrary(declaration: IrDeclaration): Boolean

    /** The body of [function], an inline function of a library, as its klib carries it; null when it carries none. */
    fun inlineFunction(function: IrSimpleFunction): IrSimpleFunction?

    /** [declaration]'s public signature, as a klib records it; null for a declaration other modules cannot name. */
    fun publicSignature(declaration: IrDeclaration): IdSignature?
}

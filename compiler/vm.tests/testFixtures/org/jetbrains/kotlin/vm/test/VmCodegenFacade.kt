/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.backend.vm.vmir.VmIrWriter
import org.jetbrains.kotlin.cli.vm.pipeline.compileModuleToVm
import org.jetbrains.kotlin.test.model.AbstractTestFacade
import org.jetbrains.kotlin.test.model.ArtifactKind
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.TestArtifactKind
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import java.io.File

/**
 * Lowers the IR a module's klib was written from and generates it as binary IR for the VM, as `-Xvm-output` does. What
 * the backend does not support fails the module with its `VmCodegenException`.
 */
class VmCodegenFacade(private val testServices: TestServices) : AbstractTestFacade<VmKLibWithArtifact, VmArtifact>() {
    @Suppress("UNCHECKED_CAST")
    override val inputKind: TestArtifactKind<VmKLibWithArtifact>
        get() = ArtifactKinds.KLib as ArtifactKind<VmKLibWithArtifact>

    override val outputKind: TestArtifactKind<VmArtifact>
        get() = VmArtifactKind

    override fun shouldTransform(module: TestModule): Boolean = true

    override fun transform(module: TestModule, inputArtifact: VmKLibWithArtifact): VmArtifact {
        val vmModule = compileModuleToVm(inputArtifact.cliArtifact)
        val output = File(testServices.temporaryDirectoryManager.getOrCreateTempDirectory("vm-output"), "${module.name}.vir")
        output.writeBytes(VmIrWriter.write(vmModule))
        return VmArtifact(output)
    }
}

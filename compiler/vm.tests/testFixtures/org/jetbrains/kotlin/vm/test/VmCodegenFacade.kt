/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.backend.vm.VmLoweringContext
import org.jetbrains.kotlin.backend.vm.compileToVm
import org.jetbrains.kotlin.backend.vm.vmir.VmIrWriter
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.moduleName
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.vm.test.VmDeserializedIrBackendInput
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.model.BackendFacade
import org.jetbrains.kotlin.test.model.BackendKinds
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.defaultsProvider
import org.jetbrains.kotlin.test.services.temporaryDirectoryManager
import java.io.File

/**
 * Lowers a module's linked IR and generates it as binary IR for the VM, as `-Xvm-output` does. What the backend does
 * not support fails the module with its `VmCodegenException`.
 */
class VmCodegenFacade(testServices: TestServices) :
    BackendFacade<IrBackendInput, VmArtifact>(testServices, BackendKinds.IrBackend, VmArtifactKind) {

    // The defaults name the KLIB the steps before this one produce, not this facade's output.
    override fun shouldTransform(module: TestModule): Boolean = testServices.defaultsProvider.backendKind == inputKind

    override fun transform(module: TestModule, inputArtifact: IrBackendInput): VmArtifact {
        val compilation = (inputArtifact as VmDeserializedIrBackendInput).compilationArtifact
        val configuration = compilation.configuration
        val diagnosticReporter = KtDiagnosticReporterWithImplicitIrBasedContext(
            configuration.diagnosticsCollector, configuration.languageVersionSettings,
        )
        val context = VmLoweringContext(configuration, compilation.pluginContext.irBuiltIns, diagnosticReporter)
        val vmModule = compileToVm(compilation.moduleFragment, configuration.moduleName ?: module.name, context)
        val output = File(testServices.temporaryDirectoryManager.getOrCreateTempDirectory("vm-output"), "${module.name}.vir")
        output.writeBytes(VmIrWriter.write(vmModule))
        return VmArtifact(output)
    }
}

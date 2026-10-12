/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.cli.vm.pipeline.VmIrCompilationArtifact
import org.jetbrains.kotlin.cli.vm.pipeline.VmIrCompilationPhase
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.withNewDiagnosticCollector
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import org.jetbrains.kotlin.diagnostics.impl.DiagnosticsCollectorImpl
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.backend.jklib.JKlibIrMangler
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.KotlinMangler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.frontend.fir.processErrorFromCliPhase
import org.jetbrains.kotlin.test.model.ArtifactKind
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.BackendKinds.IrBackend
import org.jetbrains.kotlin.test.model.DeserializerFacade
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices

@Suppress("UNCHECKED_CAST")
class VmIrCompilationCliFacade(testServices: TestServices) :
    DeserializerFacade<VmKLibWithArtifact, IrBackendInput>(
        testServices,
        ArtifactKinds.KLib as ArtifactKind<VmKLibWithArtifact>,
        IrBackend
    ) {

    override fun transform(module: TestModule, inputArtifact: VmKLibWithArtifact): IrBackendInput? {
        val serializationArtifact = inputArtifact.cliArtifact
        val input = serializationArtifact.withNewDiagnosticCollector(DiagnosticsCollectorImpl())
        val configuration = input.configuration

        val compilationArtifact = VmIrCompilationPhase.executePhase(input)

        if (CheckCompilationErrors.CheckDiagnosticCollector.checkHasErrors(configuration)) {
            return processErrorFromCliPhase(configuration, testServices)
        }

        return VmDeserializedIrBackendInput(compilationArtifact)
    }
}

class VmDeserializedIrBackendInput(
    val compilationArtifact: VmIrCompilationArtifact,
) : IrBackendInput() {

    override val irModuleFragment: IrModuleFragment
        get() = compilationArtifact.moduleFragment

    override val irBuiltIns: IrBuiltIns
        get() = compilationArtifact.pluginContext.irBuiltIns

    override val irMangler: KotlinMangler.IrMangler
        get() = JKlibIrMangler()

    override val diagnosticReporter: BaseDiagnosticsCollector
        get() = compilationArtifact.configuration.diagnosticsCollector
}


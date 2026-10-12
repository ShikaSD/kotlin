/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.cli.vm.pipeline.VmFir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.vm.pipeline.VmKlibSerializationPhase
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PipelineContext
import org.jetbrains.kotlin.config.phaser.PhaseConfig
import org.jetbrains.kotlin.config.phaser.invokeToplevel
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliBasedOutputArtifact
import org.jetbrains.kotlin.test.frontend.fir.processErrorFromCliPhase
import org.jetbrains.kotlin.test.model.BackendFacade
import org.jetbrains.kotlin.test.model.BackendKinds.IrBackend
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.util.PerformanceManager
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.TestArtifactKind
import org.jetbrains.kotlin.test.model.ArtifactKind

@Suppress("UNCHECKED_CAST")
class SerializationCliVmFacade(testServices: TestServices) :
    BackendFacade<IrBackendInput, VmKLibWithArtifact>(testServices, IrBackend, ArtifactKinds.KLib as ArtifactKind<VmKLibWithArtifact>) {
    override fun transform(module: TestModule, inputArtifact: IrBackendInput): VmKLibWithArtifact? {
        if (inputArtifact !is Fir2IrCliBasedOutputArtifact<*>) {
            error("input artifact is not a Fir2IrCliBasedOutputArtifact")
        }
        val cliArtifact = inputArtifact.cliArtifact
        require(cliArtifact is VmFir2IrPipelineArtifact)

        val serializationArtifact = VmKlibSerializationPhase.executePhase(cliArtifact)

        val configuration = serializationArtifact.configuration
        if (CheckCompilationErrors.CheckDiagnosticCollector.checkHasErrors(configuration)) {
            return processErrorFromCliPhase(configuration, testServices)
        }

        return VmKLibWithArtifact(serializationArtifact)
    }
}


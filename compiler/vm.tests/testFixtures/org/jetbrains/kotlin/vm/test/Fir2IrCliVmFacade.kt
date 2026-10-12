/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.cli.vm.pipeline.VmFir2IrPipelineArtifact
import org.jetbrains.kotlin.cli.vm.pipeline.VmFir2IrPipelinePhase
import org.jetbrains.kotlin.cli.vm.pipeline.VmFrontendPipelineArtifact
import org.jetbrains.kotlin.test.frontend.fir.Fir2IrCliFacade
import org.jetbrains.kotlin.test.services.TestServices

class Fir2IrCliVmFacade(
    testServices: TestServices,
) : Fir2IrCliFacade<VmFir2IrPipelinePhase, VmFrontendPipelineArtifact, VmFir2IrPipelineArtifact>(
    testServices,
    VmFir2IrPipelinePhase
)

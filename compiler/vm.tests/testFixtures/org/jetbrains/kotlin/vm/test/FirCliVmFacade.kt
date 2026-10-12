/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.cli.vm.pipeline.VmFrontendPipelineArtifact
import org.jetbrains.kotlin.cli.vm.pipeline.VmFrontendPipelinePhase
import org.jetbrains.kotlin.test.frontend.fir.FirCliFacade
import org.jetbrains.kotlin.test.services.TestServices

class FirCliVmFacade(
    testServices: TestServices,
) : FirCliFacade<VmFrontendPipelinePhase, VmFrontendPipelineArtifact>(testServices, VmFrontendPipelinePhase)

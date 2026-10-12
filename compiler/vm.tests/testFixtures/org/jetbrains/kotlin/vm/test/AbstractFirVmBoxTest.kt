/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.vm.test.*
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.firHandlersStep
import org.jetbrains.kotlin.test.configuration.commonFirHandlersForCodegenTest
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerJKlibTest
import org.jetbrains.kotlin.test.services.CompilationStage
import org.jetbrains.kotlin.test.services.MetaTestConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.sourceProviders.AdditionalDiagnosticsSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.CoroutineHelpersSourceFilesProvider

/**
 * The codegen box tests on the VM: each module goes through the VM compilation as the CLI takes it — frontend, KLIB, linked IR — then
 * through the VM backend, and the modules are linked with the VM runtime and `box()` run. Tests the VM cannot pass yet
 * are listed in [VmExpectedFailures.FILE].
 */
abstract class AbstractFirVmBoxTest : AbstractKotlinCompilerJKlibTest() {
    override fun configure(builder: TestConfigurationBuilder): Unit = with(builder) {
        globalDefaults {
            frontend = FrontendKinds.FIR
            targetBackend = TargetBackend.JKLIB
            targetPlatform = JvmPlatforms.defaultJvmPlatform
            artifactKind = ArtifactKinds.KLib
            dependencyKind = DependencyKind.Binary
        }

        useConfigurators(
            ::CommonEnvironmentConfigurator,
            ::VmSourceRootConfigurator,
            ::VmEnvironmentConfigurator,
            ::VmJavaSourceConfigurator,
        )

        useAdditionalSourceProviders(
            ::AdditionalDiagnosticsSourceFilesProvider,
            ::CoroutineHelpersSourceFilesProvider,
        )

        useMetaTestConfigurators(::WithReflectSkipper, ::VmRepositorySkipper)

        facadeStep(::FirCliVmFacade)
        firHandlersStep {
            commonFirHandlersForCodegenTest()
        }
        facadeStep(::Fir2IrCliVmFacade)
        facadeStep(::SerializationCliVmFacade)
        facadeStep(::VmIrCompilationCliFacade)
        facadeStep(::VmCodegenFacade)
        handlersStep(VmArtifactKind, CompilationStage.SECOND) {
            useHandlers(::VmBoxRunner)
        }

        defaultDirectives {
            +JvmEnvironmentConfigurationDirectives.NO_RUNTIME
        }

        useFailureSuppressors(::BlackBoxCodegenSuppressor, ::VmExpectedFailures)
    }
}

/** Skips every test when no VM checkout is named to link and run with. */
class VmRepositorySkipper(testServices: TestServices) : MetaTestConfigurator(testServices) {
    override fun shouldSkipTest(): Boolean = VmBoxRunner.vmRepository == null
}

/** Skips the tests that need kotlin-reflect, which the VM has none of. */
class WithReflectSkipper(testServices: TestServices) : MetaTestConfigurator(testServices) {
    override val directiveContainers: List<DirectivesContainer>
        get() = listOf(JvmEnvironmentConfigurationDirectives)

    override fun shouldSkipTest(): Boolean =
        testServices.moduleStructure.allDirectives.contains(JvmEnvironmentConfigurationDirectives.WITH_REFLECT)
}

/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jklib.test.vm

import org.jetbrains.kotlin.jklib.test.irText.*
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.BlackBoxCodegenSuppressor
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.firHandlersStep
import org.jetbrains.kotlin.test.configuration.commonFirHandlersForCodegenTest
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.model.ArtifactKinds
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerJKlibTest
import org.jetbrains.kotlin.test.services.CompilationStage
import org.jetbrains.kotlin.test.services.MetaTestConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.sourceProviders.AdditionalDiagnosticsSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.CoroutineHelpersSourceFilesProvider

/**
 * The codegen box tests on the VM: each module goes through jklib as the CLI takes it — frontend, KLIB, linked IR — then
 * through the VM backend, and the modules are linked with the VM runtime and `box()` run. Tests the VM cannot pass yet
 * are listed in [JKlibVmExpectedFailures.FILE].
 */
abstract class AbstractFirJKlibVmBoxTest : AbstractKotlinCompilerJKlibTest() {
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
            ::JKlibSourceRootConfigurator,
            ::JKlibEnvironmentConfigurator,
            ::JKlibJavaSourceConfigurator,
        )

        useAdditionalSourceProviders(
            ::AdditionalDiagnosticsSourceFilesProvider,
            ::CoroutineHelpersSourceFilesProvider,
        )

        useMetaTestConfigurators(::WithReflectSkipper, ::VmRepositorySkipper)

        facadeStep(::FirCliJKlibFacade)
        firHandlersStep {
            commonFirHandlersForCodegenTest()
        }
        facadeStep(::Fir2IrCliJKlibFacade)
        facadeStep(::SerializationCliJKlibFacade)
        facadeStep(::JKlibIrCompilationCliFacade)
        facadeStep(::JKlibVmCodegenFacade)
        handlersStep(JKlibVmArtifactKind, CompilationStage.SECOND) {
            useHandlers(::JKlibVmBoxRunner)
        }

        defaultDirectives {
            +JvmEnvironmentConfigurationDirectives.NO_RUNTIME
        }

        useFailureSuppressors(::BlackBoxCodegenSuppressor, ::JKlibVmExpectedFailures)
    }
}

/** Skips every test when no VM checkout is named to link and run with. */
class VmRepositorySkipper(testServices: TestServices) : MetaTestConfigurator(testServices) {
    override fun shouldSkipTest(): Boolean = JKlibVmBoxRunner.vmRepository == null
}

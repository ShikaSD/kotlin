/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.cli.vm.pipeline.VmSerializationArtifact
import org.jetbrains.kotlin.test.model.ArtifactKind
import org.jetbrains.kotlin.test.model.ResultingArtifact
import org.jetbrains.kotlin.test.services.CompilationStage

class VmKLibWithArtifact(val cliArtifact: VmSerializationArtifact) : ResultingArtifact.Binary<VmKLibWithArtifact>() {
    @Suppress("UNCHECKED_CAST")
    override val kind: ArtifactKind<VmKLibWithArtifact> get() = org.jetbrains.kotlin.test.model.ArtifactKinds.KLib as ArtifactKind<VmKLibWithArtifact>
}

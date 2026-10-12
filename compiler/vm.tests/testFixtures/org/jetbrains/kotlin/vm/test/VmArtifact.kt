/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.vm.test

import org.jetbrains.kotlin.test.model.ArtifactKind
import org.jetbrains.kotlin.test.model.ResultingArtifact
import org.jetbrains.kotlin.test.services.CompilationStage
import java.io.File

/** A module compiled for the VM: the binary IR the VM backend wrote for it, which linking takes. */
class VmArtifact(val file: File) : ResultingArtifact.Binary<VmArtifact>() {
    override val kind: ArtifactKind<VmArtifact> get() = VmArtifactKind
}

/** Produced from a module's linked IR, as a KLIB-based backend's executable is. */
object VmArtifactKind : ArtifactKind<VmArtifact>("VmIr", CompilationStage.SECOND)

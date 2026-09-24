/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jklib.test.vm

import org.jetbrains.kotlin.test.model.ArtifactKind
import org.jetbrains.kotlin.test.model.ResultingArtifact
import org.jetbrains.kotlin.test.services.CompilationStage
import java.io.File

/** A module compiled for the VM: the binary IR the VM backend wrote for it, which linking takes. */
class JKlibVmArtifact(val file: File) : ResultingArtifact.Binary<JKlibVmArtifact>() {
    override val kind: ArtifactKind<JKlibVmArtifact> get() = JKlibVmArtifactKind
}

/** Produced from a module's linked IR, as a KLIB-based backend's executable is. */
object JKlibVmArtifactKind : ArtifactKind<JKlibVmArtifact>("VmIr", CompilationStage.SECOND)

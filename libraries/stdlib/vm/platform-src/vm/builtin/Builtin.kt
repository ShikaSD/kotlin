/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

// The VM standard library's mirror of the runtime's built-ins, `runtime/src/runtime.ir` in the VM repository: each
// external declaration under `vm` is the runtime's of the same qualified name, which the VM backend calls or names.
// A parameter's Kotlin type must map to the built-in's exactly, as the linker checks each import's signature.

package vm.builtin

/** A coroutine's execution, which the VM captures when it suspends; see wip/COROUTINES_DESIGN.md in the VM repository. */
@Suppress("WRONG_MODIFIER_TARGET")
internal external class Continuation

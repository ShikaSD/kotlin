/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package vm.builtin.io

/** Writes [message] to the output the VM's embedder chooses. */
internal external fun print(message: String)

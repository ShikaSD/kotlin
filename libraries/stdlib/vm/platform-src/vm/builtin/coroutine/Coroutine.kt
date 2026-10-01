/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package vm.builtin.coroutine

import vm.builtin.Continuation

internal external fun new(f: () -> Any?, owner: Any): Continuation
internal external fun resume(continuation: Continuation, value: Any?): Any?
internal external fun resume_throw(continuation: Continuation, exception: Any): Any?
internal external fun suspend(value: Any?): Any?
internal external fun current(): Any?

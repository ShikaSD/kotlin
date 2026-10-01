/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package vm.builtin.array

/** Copies [count] elements of the array [from], from [fromIndex] on, into the array [to] at [toIndex], as `System.arraycopy` does. */
internal external fun copy(from: Any, fromIndex: Int, to: Any, toIndex: Int, count: Int)

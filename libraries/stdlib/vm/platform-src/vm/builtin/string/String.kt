/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package vm.builtin.string

/** A new string of [chars] from [begin] to [end]. */
internal external fun from_chars(chars: CharArray, begin: Int, end: Int): String

/** Copies [string] from [begin] to [end] into [destination] from [at]. */
internal external fun get_chars(string: String, begin: Int, end: Int, destination: CharArray, at: Int)

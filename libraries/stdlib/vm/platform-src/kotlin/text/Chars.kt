/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.text

// Strings made from and copied into char arrays, which the VM's built-ins do.

/** A new string of [chars] from [begin] to [end], which the VM backend compiles to `vm.builtin.string.from_chars`. */
internal external fun stringFromChars(chars: CharArray, begin: Int, end: Int): String

/**
 * Copies [string] from [begin] to [end] into [destination] from [at], which the VM backend compiles to
 * `vm.builtin.string.get_chars`.
 */
internal external fun stringGetChars(string: String, begin: Int, end: Int, destination: CharArray, at: Int)

internal actual fun unsafeStringFromCharArray(array: CharArray, start: Int, size: Int): String =
    stringFromChars(array, start, start + size)

internal actual fun insertString(array: CharArray, destinationIndex: Int, value: String, sourceIndex: Int, count: Int): Int {
    stringGetChars(value, sourceIndex, sourceIndex + count, array, destinationIndex)
    return count
}

internal actual fun insertInt(array: CharArray, start: Int, value: Int): Int {
    val text = value.toString()
    return insertString(array, start, text, 0, text.length)
}

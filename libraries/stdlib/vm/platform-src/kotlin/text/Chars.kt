/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.text

internal actual fun unsafeStringFromCharArray(array: CharArray, start: Int, size: Int): String =
    vm.builtin.string.from_chars(array, start, start + size)

internal actual fun insertString(array: CharArray, destinationIndex: Int, value: String, sourceIndex: Int, count: Int): Int {
    vm.builtin.string.get_chars(value, sourceIndex, sourceIndex + count, array, destinationIndex)
    return count
}

internal actual fun insertInt(array: CharArray, start: Int, value: Int): Int {
    val text = value.toString()
    return insertString(array, start, text, 0, text.length)
}

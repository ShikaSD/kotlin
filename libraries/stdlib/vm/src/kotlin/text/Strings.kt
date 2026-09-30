/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.text

// What the VM's standard library needs of the common `Strings.kt`, which does not build for the VM yet.

/** Returns `true` if this char sequence is empty (contains no characters). */
@kotlin.internal.InlineOnly
public inline fun CharSequence.isEmpty(): Boolean = length == 0

/** Returns a string representation of this [Int] value in the specified [radix]. */
public fun Int.toString(radix: Int): String = toLong().toString(radix)

/** Returns a string representation of this [Long] value in the specified [radix]. */
public fun Long.toString(radix: Int): String {
    require(radix in 2..36) { "radix $radix was not in valid range 2..36" }
    if (this == 0L) return "0"
    val digits = CharArray(65)
    var at = digits.size
    var rest = this
    val negative = rest < 0
    while (rest != 0L) {
        val digit = (rest % radix).toInt().let { if (it < 0) -it else it }
        digits[--at] = if (digit < 10) '0' + digit else 'a' + (digit - 10)
        rest /= radix
    }
    if (negative) digits[--at] = '-'
    val text = StringBuilder(digits.size - at)
    for (index in at until digits.size) text.append(digits[index])
    return text.toString()
}

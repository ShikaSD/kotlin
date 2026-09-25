/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

// `CharCode.kt` as the common sources declare it, less `Char(code: UShort)`: the VM's standard library has no unsigned
// types yet.

/**
 * Creates a Char with the specified [code], or throws an exception if the [code] is out of `Char.MIN_VALUE.code..Char.MAX_VALUE.code`.
 */
@SinceKotlin("1.5")
@kotlin.internal.InlineOnly
public inline fun Char(code: Int): Char {
    if (code < Char.MIN_VALUE.code || code > Char.MAX_VALUE.code) {
        throw IllegalArgumentException("Invalid Char code: $code")
    }
    return code.toChar()
}

/**
 * Returns the code of this Char.
 *
 * Code of a Char is the value it was constructed with, and the UTF-16 code unit corresponding to this Char.
 */
@SinceKotlin("1.5")
@kotlin.internal.InlineOnly
@Suppress("DEPRECATION")
@kotlin.internal.IntrinsicConstEvaluation
public inline val Char.code: Int get() = this.toInt()

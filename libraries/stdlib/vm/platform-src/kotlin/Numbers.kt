/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

// The VM's half of the common `KotlinH.kt` and `Numbers.kt`. The VM backend compiles the externals, which reinterpret a
// value's bits, to register copies.

public actual fun Int.countOneBits(): Int {
    var v = this - ((this ushr 1) and 0x55555555)
    v = (v and 0x33333333) + ((v ushr 2) and 0x33333333)
    v = (v + (v ushr 4)) and 0x0f0f0f0f
    return (v * 0x01010101) ushr 24
}

public actual fun Int.countLeadingZeroBits(): Int {
    if (this == 0) return 32
    var n = 0
    var v = this
    while (v and (1 shl 31) == 0) {
        v = v shl 1
        n++
    }
    return n
}

public actual fun Int.countTrailingZeroBits(): Int =
    Int.SIZE_BITS - (this or -this).inv().countLeadingZeroBits()

public actual fun Int.takeHighestOneBit(): Int =
    if (this == 0) 0 else 1.shl(Int.SIZE_BITS - 1 - countLeadingZeroBits())

public actual fun Int.takeLowestOneBit(): Int = this and -this

public actual fun Int.rotateLeft(bitCount: Int): Int =
    shl(bitCount) or ushr(Int.SIZE_BITS - bitCount)

public actual fun Int.rotateRight(bitCount: Int): Int =
    shl(Int.SIZE_BITS - bitCount) or ushr(bitCount)

public actual fun Long.countOneBits(): Int =
    toInt().countOneBits() + ushr(32).toInt().countOneBits()

public actual fun Long.countLeadingZeroBits(): Int {
    val high = ushr(32).toInt()
    return if (high == 0) Int.SIZE_BITS + toInt().countLeadingZeroBits() else high.countLeadingZeroBits()
}

public actual fun Long.countTrailingZeroBits(): Int {
    val low = toInt()
    return if (low == 0) Int.SIZE_BITS + ushr(32).toInt().countTrailingZeroBits() else low.countTrailingZeroBits()
}

public actual fun Long.takeHighestOneBit(): Long =
    if (this == 0L) 0L else 1L.shl(Long.SIZE_BITS - 1 - countLeadingZeroBits())

public actual fun Long.takeLowestOneBit(): Long = this and -this

public actual fun Long.rotateLeft(bitCount: Int): Long =
    shl(bitCount) or ushr(Long.SIZE_BITS - bitCount)

public actual fun Long.rotateRight(bitCount: Int): Long =
    shl(Long.SIZE_BITS - bitCount) or ushr(bitCount)

public actual fun Double.isNaN(): Boolean = this != this

public actual fun Float.isNaN(): Boolean = this != this

public actual fun Double.isInfinite(): Boolean = (this == Double.POSITIVE_INFINITY) || (this == Double.NEGATIVE_INFINITY)

public actual fun Float.isInfinite(): Boolean = (this == Float.POSITIVE_INFINITY) || (this == Float.NEGATIVE_INFINITY)

public actual fun Double.isFinite(): Boolean = (toRawBits() and 0x7fffffff_ffffffffL) < 0x7ff00000_00000000L

public actual fun Float.isFinite(): Boolean = (toRawBits() and 0x7fffffff) < 0x7f800000

public actual fun Double.toBits(): Long = if (isNaN()) Double.NaN.toRawBits() else toRawBits()

public actual external fun Double.toRawBits(): Long

public actual external fun Double.Companion.fromBits(bits: Long): Double

public actual fun Float.toBits(): Int = if (isNaN()) Float.NaN.toRawBits() else toRawBits()

public actual external fun Float.toRawBits(): Int

public actual external fun Float.Companion.fromBits(bits: Int): Float

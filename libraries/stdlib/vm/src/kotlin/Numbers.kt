/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin

// The functions of the common `Numbers.kt` the VM's standard library needs so far: the common file's other expects
// have no VM actuals yet. Those the VM itself implements are expected here and external in the platform sources.

/** The IEEE 754 bits of this value: reinterpreted, not converted. */
public expect fun Double.toRawBits(): Long

/** The value whose IEEE 754 bits [bits] are: reinterpreted, not converted. */
public expect fun Double.Companion.fromBits(bits: Long): Double

/** Whether this value is NaN, which is the one value unequal to itself. */
public fun Double.isNaN(): Boolean = this != this

/** Whether this value is positive or negative infinity. */
public fun Double.isInfinite(): Boolean = this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY

/** Whether this value is neither infinite nor NaN. */
public fun Double.isFinite(): Boolean = !isInfinite() && !isNaN()

/** The number of consecutive zero bits above the highest one bit of this value, 32 for zero. */
public fun Int.countLeadingZeroBits(): Int {
    if (this == 0) return 32
    var count = 0
    var rest = this
    while (rest > 0) {
        rest = rest shl 1
        count++
    }
    return count
}

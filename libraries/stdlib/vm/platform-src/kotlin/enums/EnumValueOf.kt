/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.enums

/**
 * The entry of [values] named [name]: what an enum class's `valueOf` answers, which the VM backend compiles to a call of
 * this. The message is the JVM's, naming the entry by [className].
 */
@PublishedApi
internal fun <E : Enum<E>> valueOfEnum(values: Array<E>, name: String, className: String): E {
    for (value in values) {
        if (value.name == name) return value
    }
    throw IllegalArgumentException("No enum constant $className.$name")
}

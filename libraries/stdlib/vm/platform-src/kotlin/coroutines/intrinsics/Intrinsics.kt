/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines.intrinsics

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineImplVm
import kotlin.vm.internal.createCoroutineVm0
import kotlin.vm.internal.createCoroutineVm1
import kotlin.vm.internal.createCoroutineVm2
import kotlin.vm.internal.startCoroutineVm

// Coroutines are the VM's continuations, which `CoroutineImplVm` owns; see `kotlin.vm.internal`.

@Suppress("NOTHING_TO_INLINE")
public actual inline fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(completion: Continuation<T>): Any? =
    startCoroutineVm(createCoroutineVm0(this, completion))

@Suppress("NOTHING_TO_INLINE")
public actual inline fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(
    receiver: R,
    completion: Continuation<T>
): Any? = startCoroutineVm(createCoroutineVm1(this, receiver, completion))

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <R, P, T> (suspend R.(P) -> T).startCoroutineUninterceptedOrReturn(
    receiver: R,
    param: P,
    completion: Continuation<T>
): Any? = startCoroutineVm(createCoroutineVm2(this, receiver, param, completion))

public actual fun <T> (suspend () -> T).createCoroutineUnintercepted(completion: Continuation<T>): Continuation<Unit> =
    createCoroutineVm0(this, completion)

public actual fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(
    receiver: R,
    completion: Continuation<T>
): Continuation<Unit> = createCoroutineVm1(this, receiver, completion)

@Suppress("UNCHECKED_CAST")
public actual fun <T> Continuation<T>.intercepted(): Continuation<T> =
    (this as? CoroutineImplVm<T, *>)?.intercepted() ?: this

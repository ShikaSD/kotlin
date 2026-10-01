/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines

import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

// Wasm's `CoroutineImplStackSwitching` over the VM's continuations. The continuation a `suspendCoroutineUninterceptedOrReturn`
// block resumes names the coroutine it continues as its [owner].
@SinceKotlin("1.3")
internal class CoroutineImplVm<T, R>(
    internal val resultContinuation: Continuation<R>,
    internal var continuation: vm.builtin.Continuation? = null,
    private val owner: CoroutineImplVm<*, R>? = null,
) : Continuation<T> {

    internal var result: Any? = null
    internal var exception: Throwable? = null

    public override val context: CoroutineContext = resultContinuation.context
    private var intercepted_: Continuation<T>? = null
    public fun intercepted(): Continuation<T> = intercepted_
        ?: (context[ContinuationInterceptor]?.interceptContinuation(this) ?: this)
            .also { intercepted_ = it }

    /** Whether the coroutine has not yet suspended on the `suspendCoroutineUninterceptedOrReturn` block resuming this. */
    internal var pendingSuspend = false

    @Suppress("UNCHECKED_CAST")
    override fun resumeWith(result: Result<T>) {
        this.result = result.getOrNull()
        exception = result.exceptionOrNull()

        if (pendingSuspend) {
            pendingSuspend = false
            return
        }

        try {
            val outcome = doResume()
            this.result = outcome
            exception = null
            if (outcome === COROUTINE_SUSPENDED) return
        } catch (exception: Throwable) { // Catch all exceptions
            this.result = null
            this.exception = exception
        }

        releaseIntercepted() // this instance is terminating

        val completion = resultContinuation

        // top-level completion reached -- invoke and return
        if (exception != null) {
            completion.resumeWithException(exception!!)
        } else {
            completion.resume(this.result as R)
        }
    }

    private fun releaseIntercepted() {
        val intercepted = intercepted_
        if (intercepted != null && intercepted !== this) {
            context[ContinuationInterceptor]!!.releaseInterceptedContinuation(intercepted)
        }
        this.intercepted_ = CompletedContinuation // just in case
    }

    /**
     * Whether the coroutine suspended for a `suspendCoroutineUninterceptedOrReturn` block that had resumed it before
     * answering `COROUTINE_SUSPENDED`; whoever ran the coroutine then continues it. Held by the coroutine itself.
     */
    internal var resumedBeforeSuspending = false

    /** Continues the coroutine, throwing the failure at its suspension point. */
    fun doResume(): Any? {
        val e = exception
        var outcome = if (e != null) vm.builtin.coroutine.resume_throw(continuation!!, e) else vm.builtin.coroutine.resume(continuation!!, null)
        val coroutine = owner ?: this
        while (outcome === COROUTINE_SUSPENDED && coroutine.resumedBeforeSuspending) {
            coroutine.resumedBeforeSuspending = false
            outcome = vm.builtin.coroutine.resume(continuation!!, null)
        }
        return outcome
    }
}

internal object CompletedContinuation : Continuation<Any?> {
    override val context: CoroutineContext
        get() = error("This continuation is already complete")

    override fun resumeWith(result: Result<Any?>) {
        error("This continuation is already complete")
    }

    override fun toString(): String = "This continuation is already complete"
}

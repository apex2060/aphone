package com.apex2060.softphone.push

import kotlinx.coroutines.delay

/** Result of one phone.token attempt. [Failed] is retried. The others stop. */
sealed class TokenAttempt {
    data object Acked : TokenAttempt()
    data object Revoked : TokenAttempt()
    data class Rejected(val code: Int) : TokenAttempt()
    data class Failed(val reason: String) : TokenAttempt()
}

object TokenBackoff {
    const val MAX_ATTEMPTS = 6

    /** 1s, 2s, 4s, 8s, 16s, then 60s. [attempt] is 1-based and is the attempt that just failed. */
    fun delayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtLeast(0).coerceAtMost(6)
        return (1_000L shl shift).coerceAtMost(60_000L)
    }
}

/**
 * Runs [post] immediately, then again after [TokenBackoff] if it fails because the
 * network is down. Stops on success, revocation, or a non-retriable HTTP status.
 */
suspend fun retryTokenPost(
    maxAttempts: Int = TokenBackoff.MAX_ATTEMPTS,
    delayForAttempt: (Int) -> Long = TokenBackoff::delayMs,
    pause: suspend (Long) -> Unit = { delay(it) },
    post: suspend () -> TokenAttempt,
): TokenAttempt {
    var last: TokenAttempt = TokenAttempt.Failed("not tried")
    for (attempt in 1..maxAttempts) {
        last = try {
            post()
        } catch (e: Exception) {
            TokenAttempt.Failed(e.message ?: e.javaClass.simpleName)
        }
        when (last) {
            TokenAttempt.Acked, TokenAttempt.Revoked, is TokenAttempt.Rejected -> return last
            is TokenAttempt.Failed -> if (attempt < maxAttempts) pause(delayForAttempt(attempt))
        }
    }
    return last
}

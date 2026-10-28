package com.apex2060.softphone

import com.apex2060.softphone.push.TokenAttempt
import com.apex2060.softphone.push.TokenBackoff
import com.apex2060.softphone.push.retryTokenPost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TokenUploadTest {
    @Test
    fun delaysDoubleUntilAMinute() {
        assertEquals(1_000L, TokenBackoff.delayMs(1))
        assertEquals(2_000L, TokenBackoff.delayMs(2))
        assertEquals(4_000L, TokenBackoff.delayMs(3))
        assertEquals(60_000L, TokenBackoff.delayMs(8))
    }

    @Test
    fun postsImmediatelyThenRetriesOfflineUntilAcked() = runBlocking {
        val pauses = mutableListOf<Long>()
        var calls = 0
        val result = retryTokenPost(pause = { pauses += it }) {
            calls++
            if (calls < 3) throw IOException("offline")
            TokenAttempt.Acked
        }
        assertEquals(TokenAttempt.Acked, result)
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 2_000L), pauses)
    }

    @Test
    fun revocationAndRejectionDoNotRetry() = runBlocking {
        var revokedCalls = 0
        val revoked = retryTokenPost(pause = { error("should not wait") }) {
            revokedCalls++
            TokenAttempt.Revoked
        }
        assertEquals(TokenAttempt.Revoked, revoked)
        assertEquals(1, revokedCalls)

        var rejectedCalls = 0
        val rejected = retryTokenPost(pause = { error("should not wait") }) {
            rejectedCalls++
            TokenAttempt.Rejected(400)
        }
        assertTrue(rejected is TokenAttempt.Rejected)
        assertEquals(1, rejectedCalls)
    }

    @Test
    fun givesUpAfterTheLastOfflineAttempt() = runBlocking {
        val pauses = mutableListOf<Long>()
        var calls = 0
        val result = retryTokenPost(maxAttempts = 3, pause = { pauses += it }) {
            calls++
            TokenAttempt.Failed("offline")
        }
        assertTrue(result is TokenAttempt.Failed)
        assertEquals(3, calls)
        assertEquals(listOf(1_000L, 2_000L), pauses)
    }
}

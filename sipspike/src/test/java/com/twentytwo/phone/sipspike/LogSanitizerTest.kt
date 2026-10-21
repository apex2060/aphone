package com.twentytwo.phone.sipspike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun redactsPasswordAndAuthorization() {
        val raw = """
            REGISTER sip:phone.plivo.com SIP/2.0
            Authorization: Digest username="ryan", response="abc", password=s3cret-value
            Proxy-Authorization: Digest response="zzz"
            sip status 401
        """.trimIndent()
        val out = LogSanitizer.redact(raw, "s3cret-value")
        assertFalse(out.contains("s3cret-value"))
        assertFalse(out.contains("Digest username"))
        assertFalse(out.contains("response=\"zzz\""))
        assertEquals(true, out.contains("401"))
        assertEquals(true, out.contains("Authorization: [redacted]"))
        assertEquals(true, out.contains("Proxy-Authorization: [redacted]"))
    }

    @Test
    fun leavesOrdinaryLinesAlone() {
        val line = "TLS phone.plivo.com:5061 REGISTER 200 OK"
        assertEquals(line, LogSanitizer.redact(line, null))
        assertEquals(line, LogSanitizer.redact(line, ""))
    }
}

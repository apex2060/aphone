package com.apex2060.softphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun redactsPasswordAuthorizationAndProvisionKey() {
        val raw = """
            POST https://phones.example/_comm?action=phone.provision&key=front-desk-secret
            X-Provision-Key: front-desk-secret
            {"key":"front-desk-secret","device":{"model":"Samsung SM-S908U1"}}
            {"sip":{"username":"ryan","password":"s3cret-value"},"token":"abc.def"}
            Authorization: Digest username="ryan", response="abc"
            Proxy-Authorization: Digest response="zzz"
            sip status 401
        """.trimIndent()
        val out = LogSanitizer.redact(raw, listOf("s3cret-value", "front-desk-secret"))
        assertFalse(out.contains("s3cret-value"))
        assertFalse(out.contains("front-desk-secret"))
        assertFalse(out.contains("abc.def"))
        assertFalse(out.contains("Digest username"))
        assertEquals(true, out.contains("401"))
        assertEquals(true, out.contains("ryan"))
        assertEquals(true, out.contains("Authorization: [redacted]"))
        assertEquals(true, out.contains("Proxy-Authorization: [redacted]"))
        assertEquals(true, out.contains("X-Provision-Key: [redacted]"))
        assertEquals(true, out.contains("key=[redacted]"))
    }

    @Test
    fun leavesOrdinaryLinesAlone() {
        val line = "TLS phone.plivo.com:5061 REGISTER 200 OK"
        assertEquals(line, LogSanitizer.redact(line))
        assertEquals(line, LogSanitizer.redact(line, listOf("", null)))
    }
}

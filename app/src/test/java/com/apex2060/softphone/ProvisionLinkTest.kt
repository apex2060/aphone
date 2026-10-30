package com.apex2060.softphone

import com.apex2060.softphone.config.Provisioner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ProvisionLinkTest {
    private val json = """{"url":"https://a.alphabetize.us/_comm?action=phone.provision","key":"line-key"}"""

    @Test
    fun fragmentAndQueryOnBothSchemes() {
        val token = b64(json, pad = false)
        val padded = b64(json, pad = true)
        for (encoded in listOf(token, padded)) {
            val httpsFrag = Provisioner.parseLink(
                "https://a.alphabetize.us/22phone/setup#t=$encoded",
                release = true,
            )
            val httpsQuery = Provisioner.parseLink(
                "https://a.alphabetize.us/22phone/setup?t=$encoded",
                release = true,
            )
            val custom = Provisioner.parseLink("phone22://setup?t=$encoded", release = true)
            val customFrag = Provisioner.parseLink("phone22://setup#t=$encoded", release = true)
            for (ticket in listOf(httpsFrag, httpsQuery, custom, customFrag)) {
                assertEquals("https://a.alphabetize.us/_comm?action=phone.provision", ticket?.url)
                assertEquals("line-key", ticket?.key)
            }
        }
    }

    @Test
    fun fragmentWinsOverQuery() {
        val good = b64(json, pad = false)
        val other = b64("""{"url":"https://us.ed22.org/p","key":"other"}""", pad = false)
        val ticket = Provisioner.parseLink(
            "https://a.alphabetize.us/22phone/setup?t=$other#t=$good",
            release = true,
        )
        assertEquals("line-key", ticket?.key)
        assertEquals("a.alphabetize.us", Provisioner.ticketHost(ticket!!.url))
    }

    @Test
    fun malformedBase64IsRejected() {
        assertNull(Provisioner.parseLink("phone22://setup?t=!!!!", release = true))
        assertNull(Provisioner.parseLink("https://a.alphabetize.us/22phone/setup#t=", release = true))
        assertNull(Provisioner.parseLink("https://a.alphabetize.us/22phone/setup", release = true))
    }

    @Test
    fun disallowedHostIsRejected() {
        val token = b64("""{"url":"https://phones.example.com/provision","key":"k"}""", pad = false)
        assertNull(Provisioner.parseLink("phone22://setup?t=$token", release = true))
    }

    @Test
    fun httpTicketIsRejectedInRelease() {
        val token = b64("""{"url":"http://a.alphabetize.us/provision","key":"k"}""", pad = false)
        assertNull(Provisioner.parseLink("phone22://setup?t=$token", release = true))
        val debug = Provisioner.parseLink("phone22://setup?t=$token", release = false)
        assertEquals("http://a.alphabetize.us/provision", debug?.url)
    }

    @Test
    fun otherAllowlistedHosts() {
        val ed = b64("""{"url":"https://us.ed22.org/p","key":"k"}""", pad = false)
        val works = b64("""{"url":"https://my.latterday.works/p","key":"k"}""", pad = false)
        assertEquals("us.ed22.org", Provisioner.parseLink("phone22://setup?t=$ed", release = true)?.let { Provisioner.ticketHost(it.url) })
        assertEquals("my.latterday.works", Provisioner.parseLink("phone22://setup?t=$works", release = true)?.let { Provisioner.ticketHost(it.url) })
    }

    private fun b64(text: String, pad: Boolean): String {
        val encoder = Base64.getUrlEncoder()
        val raw = if (pad) encoder.encodeToString(text.toByteArray()) else encoder.withoutPadding().encodeToString(text.toByteArray())
        return raw
    }
}

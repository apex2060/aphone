package com.apex2060.softphone

import com.apex2060.softphone.push.provisionRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProvisionBodyTest {
    @Test
    fun provisionBodyParsesAsJsonObjectWithKey() {
        val raw = provisionRequestBody(
            key = "front-desk",
            device = JSONObject().put("model", "test"),
            token = null,
            appVersion = "1.3.1",
            deviceId = "device-1",
        )
        assertFalse(raw.startsWith("kotlin."))
        val json = JSONObject(raw)
        assertEquals("front-desk", json.getString("key"))
        assertEquals("test", json.getJSONObject("device").getString("model"))
        assertFalse(json.has("fcmToken"))
    }

    @Test
    fun provisionBodyIncludesPushFieldsWhenATokenExists() {
        val json = JSONObject(
            provisionRequestBody(
                key = "front-desk",
                device = JSONObject().put("app", "1.3.1"),
                token = "fcm-token",
                appVersion = "1.3.1",
                deviceId = "device-1",
            ),
        )
        assertEquals("front-desk", json.getString("key"))
        assertEquals("fcm-token", json.getString("fcmToken"))
        assertEquals("android", json.getString("platform"))
        assertEquals("1.3.1", json.getString("appVersion"))
        assertEquals("device-1", json.getString("deviceId"))
    }
}

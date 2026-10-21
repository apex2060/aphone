# Custom FCM push (PJSIP app)

Plivo no longer supports push in the native mobile SDKs. Inbound calls to the PJSIP app (22 SIP Test, `com.twentytwo.phone.sipspike`) wake it with our own FCM: Plivo's answer URL hits `_comm` (not in this repo), `_comm` sends a data push, the app registers, then Plivo dials the endpoint.

This is the app-side contract only. It does not change 22 Phone, the Plivo SDK, or `_comm`. PR #4 (Plivo SDK push) stays as it is. No ConnectionService.

The app uses the existing **ed22** Firebase project. `google-services.json` stays uncommitted. CI passes it as `GOOGLE_SERVICES_JSON_BASE64` and writes `sipspike/google-services.json`. That file must contain an Android client with `package_name` `com.twentytwo.phone.sipspike`. A json that only lists `com.apex2060.softphone` will not mint a token for this app; add the spike package to ed22 and refresh the secret. If the file is missing, push is compiled out and the app keeps today's always-on registration so it still builds and can call out.

Never log the SIP password, the provisioning key, or the FCM token. The log redactor already strips `fcmToken`; the raw token string is a secret too.

## 1. FCM token

Get the token with `FirebaseMessaging.getInstance().token`. Create `deviceId` once per install (a UUID in the app's private prefs). Do not use `ANDROID_ID`: 22 Phone and 22 SIP Test share a signing key, so they would share that id.

Do not block provisioning on Firebase. Scan and register as today. When the token arrives, send it. Send it again on every `onNewToken`, on every `phone.provision` refresh, and on process start if it has not been acknowledged.

`phone.provision` stays the same URL, method, `X-Provision-Key` header, and `{"key","device"}` body. The new fields are added beside `device`. Omit `fcmToken` until one exists. Do not send `""`.

```json
{
  "key": "<provisioning key>",
  "fcmToken": "<fcm token>",
  "platform": "android",
  "appVersion": "0.2",
  "deviceId": "<uuid>",
  "device": {"model": "samsung SM-S908U1", "android": "16", "app": "0.2"}
}
```

`onNewToken` uses a new action on the same host. Take the stored provision URL and set the query parameter `action` to `phone.token` (leave every other parameter alone).

```http
POST /_comm?action=phone.token
Content-Type: application/json
X-Provision-Key: <provisioning key>

{"key":"<provisioning key>","fcmToken":"<token>","platform":"android","appVersion":"0.2","deviceId":"<uuid>"}
```

| Status | App |
|---|---|
| 200 | Token is stored. Inbound push can work. |
| 410 | Same as provision: wipe the line and return to the scanner. |
| anything else | Keep the token. Retry on the next `onNewToken`, provision refresh, or process start. Do not block SIP. |

## 2. Push payload

Data only. HTTP v1 `android.priority` = `HIGH`, `android.ttl` = `30s`. No `notification` block at either level. A notification block is drawn by the system and does **not** run the app when it is backgrounded, so there is no registration and no full-screen ring. Every data value is a string.

```json
{
  "message": {
    "token": "<stored fcmToken>",
    "android": {
      "priority": "HIGH",
      "ttl": "30s",
      "data": {
        "type": "incoming_call",
        "callUuid": "<Plivo CallUUID>",
        "from": "15551234567",
        "fromName": "Ada",
        "to": "15557654321",
        "ts": "1729530000000"
      }
    }
  }
}
```

| Field | Required | Meaning |
|---|---|---|
| `type` | yes | `incoming_call`, or `cancel` when the caller hangs up before answer. |
| `callUuid` | yes | Plivo `CallUUID`. Same value on cancel and in the SIP header below. |
| `from` | incoming | Caller ID, digits only, no `+`. |
| `fromName` | no | Lock-screen name. Empty string is fine. |
| `to` | no | Dialed number. Logged, not used for matching. |
| `ts` | yes | Unix epoch milliseconds when `_comm` sent the push. |

`cancel` needs `type`, `callUuid`, and `ts`. Other fields may be omitted.

## 3. What the app does

`FirebaseMessagingService.onMessageReceived` runs only for this data message. Ignore it when the phone is not provisioned, when `type` is unknown, when `callUuid` is empty, or when `now - ts` is over 30 seconds.

For `incoming_call`, in this order, before any SIP work:

1. Start a foreground service with type `phoneCall` and post the ringing notification in the same turn, before `onMessageReceived` returns. The high-priority FCM start exemption is only valid for a few seconds. PJSIP startup is too slow to do first.
2. Notification is `CallStyle.forIncomingCall` on the existing incoming channel (high importance, public lock-screen visibility, full-screen intent, Answer and Decline). Title is `fromName` when set, otherwise `from`.
3. Then register with the existing auto transport. On a push, try the remembered winner for this network, then one fallback, and stop at 20 seconds even if UDP was not tried. First push on a network with nothing remembered: TLS `phone.plivo.com:5061`, or the IPv6 pin first when the network is cellular with no IPv4 and no CLAT, then one fallback. A later non-2xx forgets the winner, same as today.
4. When REGISTER is 2xx, POST `phone.ready` once (section 5). Stay registered and wait for the INVITE.
5. Accept the INVITE when header `X-Call-UUID` equals `callUuid`, or when the From user-part digits equal `from`. Send 180 as today. A pending push whose INVITE matches neither is logged and still rung if nothing else is in progress, so a missing header does not drop the call. No pending push (always-on Wi-Fi) rings as today.
6. Give up 20 seconds after the push was received if no INVITE arrived. Stop the ring, post a missed call, unregister, stop the service.

`cancel` for the ringing `callUuid`, before answer: stop the ring, send 486 if the INVITE already arrived, unregister, stop the service. Ignore `cancel` after answer (BYE ends the call) and ignore an unknown `callUuid`. A duplicate `incoming_call` for the same `callUuid` is ignored. A second call while this one is up is rejected with 486 when its INVITE arrives, same as today.

When the call ends (answer then hangup, remote BYE, decline, cancel, or timeout), send BYE or CANCEL before tearing down the UI, then `REGISTER` with `Expires: 0`, then stop the service. Do not leave the registration up.

Outbound is unchanged in the dialer. Starting a call registers if needed, then unregisters after the call unless the Wi-Fi setting below is on. Sitting on the dialer does not register by itself.

Android 14 through 16, app `targetSdk` 35, on the SM-S908U1:

| Constraint | What we do |
|---|---|
| FGS from the background | Allowed from a high-priority FCM data message. `startForeground` before any other work. If it throws, log it and still post the notification. |
| FGS type | `phoneCall`. Manifest: `FOREGROUND_SERVICE_PHONE_CALL` and `MANAGE_OWN_CALLS` (`MANAGE_OWN_CALLS` is a normal install-time permission and is the documented phoneCall requirement). No Telecom role, no ConnectionService. |
| Microphone | Not part of the push start. Add type `microphone` only after the user answers and the call screen is in front. Decline and timeout never capture audio. |
| Full-screen intent | `USE_FULL_SCREEN_INTENT` is not granted on API 34+ just by declaring it. Keep the existing prompt (`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`) and the Details shortcut. Without it, CallStyle may show a heads-up and still not turn the screen on. |
| Notifications | `POST_NOTIFICATIONS` must be allowed, including the incoming channel. |
| Swipe away vs Force stop | Swiping the app out of Recents must still ring. Force stop in App info sets the stopped state; Android will not deliver FCM until the user opens the app once. |
| Process start | The service is `phoneCall` for the wake, not the current always-on `specialUse` service. `specialUse` remains only for the Wi-Fi setting. |

Battery guidance, including Samsung One UI: the first-run ignore-battery-optimization prompt stays. Also tell the user to set 22 SIP Test to Unrestricted, and under Battery → Background usage limits to put it in Never sleeping apps and remove it from Sleeping and Deep sleeping. Allow background data. High-priority FCM is delivered during stock Doze. Samsung deep sleep can drop it anyway, and no app code fixes that list.

## 4. Always-on Wi-Fi, and when push fails

Details gets one switch, **Stay registered on Wi-Fi**, stored in the `sipspike` prefs, default off. It is not a provision field.

- On, and the default network is Wi-Fi: keep today's always-on registration and re-register on network change. An INVITE rings even if FCM never arrives. A push while already registered does not start a second stack; it only arms `callUuid` matching.
- On, and the network is cellular: do not stay registered. Push wakes the phone. Switching from Wi-Fi to cellular while idle unregisters.
- Off: register only for a push or an outbound call, then unregister.
- Push compiled out (no `google-services.json`): behave as the switch being on for every network, which is today's spike.

If the push never arrives (deep sleep, Force stop, stale token, TTL), the app cannot ring. `_comm` must stop waiting at 20 seconds and take its no-answer path. A push that arrives after that registers, sees no INVITE, times out, and unregisters. A token that FCM reports as `UNREGISTERED` should be dropped by the backend; the next `phone.token` replaces it. A wrong password still fails REGISTER and the ring ends at the timeout.

## 5. Latency and the backend contract

The app does not see this XML. It is what `_comm` must do so the INVITE shows up while the app is registered.

| Step | Budget |
|---|---|
| Plivo answer URL to `_comm` | 300 ms |
| Send the FCM HTTP v1 request before the first XML body is returned | 300 ms |
| FCM delivery | 2 s typical, 5 s budget |
| `onMessageReceived` to notification and `startForeground` | 300 ms |
| REGISTER 2xx on the remembered transport | 2 s typical, 8 s budget |
| `phone.ready` visible to the next redirect | one 2 s Wait |
| Dial to INVITE at the phone | 1 s |
| Target, answer URL to ringing | under 8 s |
| App stops waiting | 20 s from the push |
| Backend stops redirecting | 20 s from the Plivo call start |

**Store tokens.** On `phone.provision` and `phone.token`, save `fcmToken` for `(sip username, deviceId)`. Same key check as provision (body `key` and `X-Provision-Key`). Missing `fcmToken` means this client has no push yet; do not clear a token already stored. 410 still revokes the phone.

**On the inbound answer URL, push first.** Look up the endpoint for the called number. Send the `incoming_call` data message. Then return XML that waits. Do not `Dial` in that first response.

```xml
<Response>
  <Wait length="2"/>
  <Redirect method="POST">https://HOST/_comm?action=phone.inbound</Redirect>
</Response>
```

Each redirect: if `phone.ready` has arrived for this `CallUUID`, or 20 seconds have elapsed, return the Dial once. Otherwise Wait and Redirect again.

```xml
<Response>
  <Dial callerId="15551234567" timeout="20" action="https://HOST/_comm?action=phone.dialstatus">
    <User sipHeaders="X-Call-UUID=CALLUUID">sip:ENDPOINT@phone.plivo.com</User>
  </Dial>
</Response>
```

`callerId` and `from` are digits with no `+`. `sipHeaders` must produce an INVITE header `X-Call-UUID` whose value is the same `CallUUID` as the push. `timeout="20"` is how long Plivo rings after Dial. The Dial action URL ends the attempt on no-answer, busy, or failed, and must not send a second push.

**`phone.ready`** is how the app tells `_comm` that REGISTER succeeded. The redirect loop cannot see the SIP registration from here. The app POSTs it once, as soon as REGISTER is 2xx for that push, to the provision URL with `action=phone.ready`:

```json
{"key":"<provisioning key>","callUuid":"<CallUUID>","deviceId":"<uuid>"}
```

Header `X-Provision-Key` is the same key. 200 means the next redirect may Dial. Any other status is logged; the app stays registered until the INVITE or the 20 second timeout, so the deadline Dial can still land.

**Cancel.** When the caller hangs up before answer, stop the redirect loop and send `type=cancel` for that `callUuid`. If Dial already went out, the BYE still ends a call the app has answered.

**FCM errors.** On `UNREGISTERED` or `INVALID_ARGUMENT`, delete that token. Do not retry a dead token for the rest of the call.

## 6. Logging

Add these lines to the existing timestamped, rotating Details log (Share / Copy, about 2 MB). Redact before write.

- `push recv type=incoming_call callUuid=… from=… ageMs=…` (and `priority`)
- `push ignore` with the reason: not provisioned, stale, duplicate, unknown type, empty callUuid
- `push fgs started` or `push fgs denied` plus the exception name
- `push register latency ms=… code=200` from push receipt to the first REGISTER 2xx
- `push ready HTTP 200` (or the error code)
- `invite arrive match=header|from|none latencyMs=…` from the push, and from REGISTER 2xx
- `push cancel callUuid=…`
- `push timeout no invite`
- `token update HTTP 200` on `phone.token`. The token value does not appear.

REGISTER, INVITE, and BYE codes, transport choice, SDP, and RTP counts stay as they are.

## 7. Test on the Samsung SM-S908U1 (Android 16)

Install beside 22 Phone. Allow camera, microphone, notifications, full-screen alerts, and unrestricted battery. Put 22 SIP Test in Never sleeping apps.

1. Scan the Comm Manager QR. Details shows provision HTTP 200 and `token update HTTP 200`, with no password, key, or token in the log.
2. Swipe the app out of Recents. Call the number. The lock screen shows Answer / Decline within a few seconds. The log shows `push recv`, `push register latency`, REGISTER 200, and `invite arrive match=header`.
3. Decline. The caller disconnects, the notification is gone, and a second call wakes the app again.
4. Answer one call. Confirm two-way audio and RTP in and out above zero. Hangup drops the remote side, then the log shows unregister and the service notification goes away.
5. Hang up from the caller before answer. The ring clears (`push cancel`) and no notification is stuck.
6. Turn Wi-Fi off and repeat 2 and 4 on 5G. The log names the transport that won (TLS 5061 if there is IPv4 or CLAT, otherwise the IPv6 pin).
7. Turn the Wi-Fi switch on, stay on Wi-Fi, and confirm a call still rings while the app was already registered. Turn it off, leave the app idle, and confirm it does not stay registered.
8. Force stop in App info, call, and confirm it does **not** ring until the app has been opened once. Then swipe away (not Force stop) and confirm the next call does ring.

# 22 SIP Test

A throwaway arm64 softphone for the IPv6 spike. It installs beside 22 Phone
(`com.apex2060.softphone`) because its id is `com.twentytwo.phone.sipspike`.
It does not change how 22 Phone registers or places calls.

The stack is PJSIP 2.17 / pjsua2 with OpenSSL, voice codecs PCMU and PCMA,
RFC 2833 `telephone-event`, and SRTP off. The native libraries are already
built for `arm64-v8a` with 16 KB page alignment and checked in under
`src/main/jniLibs`, so CI does not compile PJSIP. `third_party/build-pjsip-android.sh`
rebuilds them. Versions and licenses are in `third_party/`.

Credentials are stored only in the app's private SharedPreferences (`sipspike`).
The log redacts the password and `Authorization` headers before display, share,
or logcat.

## Build

From the repo root:

```sh
./gradlew -p sipspike assembleDebug
./gradlew -p sipspike testDebugUnitTest
```

A signed release is produced by `.github/workflows/spike-release.yml` on tags
`spike-*`, using the same `ANDROID_*` signing secrets as 22 Phone. The asset
name is `22siptest.apk`. Those tags are prereleases and are not `v*` tags.

## What to try on the Samsung SM-S908U1

Install `22siptest.apk` and leave 22 Phone installed. Open **22 SIP Test**,
allow the microphone and notifications, and type the endpoint username and
password. The AOR is always `sip:<user>@phone.plivo.com`.

Keep the app in the foreground for the first attempt. The ongoing notification
keeps the process alive if you pull down the shade to change networks.

### Wi-Fi

1. Select **TLS phone.plivo.com:5061** and tap Register.
2. The status line should become `Registered 200` (or another 2xx) and the log
   should show `transport connected`, the local and remote addresses, and
   `REGISTER 200`.
3. From the Plivo console, or from another phone, dial the endpoint
   (`sip:<user>@phone.plivo.com`). The screen should show Incoming with
   Answer and Decline. Answer and confirm two-way audio. Hangup should drop
   the remote side.
4. Call a number from the Number field. DTMF buttons send RFC 2833 while the
   call is up. Speaker on/off is labeled on the button.
5. Share the log. It must not contain the password.

### 5G

Turn Wi-Fi off and wait until the log's `network` block says `cellular`.

1. Try **TLS phone.plivo.com:5061** again. A normal stack can succeed here
   when the carrier's 464XLAT (CLAT) translates IPv4. The log labels a
   `192.0.0.0/29` address as `clat`.
2. If that REGISTER fails, switch to **TLS IPv6 mobile.phone.plivo.com
   (SNI phone.plivo.com)** and register again. The log should list AAAA
   records, a line `pinned phone.plivo.com -> <ipv6>`, and a transport whose
   remote address is IPv6. The certificate name checked by PJSIP is still
   `phone.plivo.com`.
3. Repeat the inbound answer and the outbound call. If the call connects and
   both directions are silent, the log's SDP and transport lines are the
   evidence: Plivo's media servers are IPv4, so a network with no CLAT and
   no IPv4 source cannot carry the audio.
4. With registration up, turn Wi-Fi back on. The log should show
   `re-register after` and a new REGISTER result without reinstalling the app.

**UDP phone.plivo.com:5060** is the third radio button. Use it only if both
TLS choices fail, and expect a worse NAT contact.

Success for the spike is REGISTER 2xx, an inbound ring, two-way audio, and a
re-register across the network change, on Wi-Fi and on 5G.

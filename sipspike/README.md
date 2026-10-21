# 22 SIP Test

A throwaway arm64 softphone for the IPv6 spike. It installs beside 22 Phone
(`com.apex2060.softphone`) because its id is `com.twentytwo.phone.sipspike`
and its label is **22 SIP Test**. It does not change how 22 Phone registers
or places calls.

Setup matches 22 Phone. Scan the Comm Manager QR. The app POSTs that URL
(typically `.../_comm?action=phone.provision`) with the same JSON body and
`X-Provision-Key` header, saves the document (SIP username and password,
`refreshHours`, `adminPin`, theme, speed dials), and registers by itself.
Long-press the title and enter the admin PIN for reset, or for a hidden
manual SIP login.

Transport is automatic. REGISTER goes only to `phone.plivo.com`. There
is no outbound proxy on the account.

1. TLS `phone.plivo.com:5061`
2. On failure, or on cellular with no IPv4 source (no CLAT), TLS to an AAAA
   of `mobile.phone.plivo.com` with the certificate name `phone.plivo.com`
3. Then UDP `phone.plivo.com:5060`

The transport that succeeds is remembered per network type (wifi, cellular,
cellular with no IPv4). Details has a manual override. A change of network
type or routable addresses re-registers. A resume or a capabilities callback
that leaves those the same does not. Codecs offered are PCMU then PCMA
(`PCMU/8000/1`, `PCMA/8000/1`). AMR and the other MediaCodec entries are
priority 0. `telephone-event/8000` payload type 120 is added by pjmedia with
the 8 kHz codecs; it is not a separate codec-list entry. SRTP is off. G.711
VAD is off so silence still sends RTP behind CGNAT. A network change
during a call waits until the call ends before it re-registers. Hangup
sends BYE or CANCEL before the local call is cleared. The log says
`hangup party=local-user`, `local-stack-teardown`, or `pjsua-or-remote`.

The log is timestamped, stored in a rotating file capped around 2 MB, and
shown in Details with Share and Copy. It records provisioning (password,
provisioning key, and tokens redacted), DNS, addresses including IPv6 and
CLAT, transport attempts, REGISTER/INVITE/BYE codes, call state, SDP, and
RTP packet counts. An outbound call logs the normalized digits, the full
INVITE (Authorization redacted), and every response code. The request URI
is `sip:<digits>@phone.plivo.com;transport=tls` so it uses the registered
TLS connection. Ten-digit numbers get the provisioned country code `1`.
22 Phone sends no X-PH headers, and neither does this app. Details can
send that INVITE with a Route to `client.plivo.com:5061` instead. The
default is the direct path. A direct 403, 404, 480, 484, 488, or 5xx
before ringing is retried once via that Route when the OPTIONS probe got
a SIP answer.

PJSIP 2.17 (GPL-2.0-or-later) and OpenSSL 3.5.4 are prebuilt for `arm64-v8a`
with 16 KB page alignment under `src/main/jniLibs`. CI does not compile them.
`third_party/build-pjsip-android.sh` rebuilds them.

## Build

```sh
./gradlew -p sipspike testDebugUnitTest assembleDebug
```

`.github/workflows/spike-release.yml` signs `spike-*` tags with the same
`ANDROID_*` secrets as 22 Phone and uploads prerelease asset `22siptest.apk`.

## What to try on the Samsung SM-S908U1

Install `22siptest.apk` and leave 22 Phone installed. Allow the camera,
microphone, notifications, and full-screen alerts.

### Wi-Fi

1. Scan the same Comm Manager QR 22 Phone uses. The dialer should appear and
   the header should become Ready without typing a password.
2. Open Details. The log should show the provision HTTP 200 with the password
   and key redacted, DNS, `transport won TLS phone.plivo.com:5061`, and
   `REGISTER 200`.
3. Place a call and answer one. Confirm two-way audio. Hangup should drop
   the remote side. Details should show RTP in and out above zero.
4. Share or copy the log and confirm the password is absent.

### 5G

Turn Wi-Fi off and wait until the log says `re-register after`.

1. If the network has IPv4 or CLAT, TLS 5061 is tried first.
2. If REGISTER fails, or the log says `cellular-no-ipv4`, the app should
   pin an AAAA and show `transport won` on the IPv6 choice.
3. Answer an inbound call from the lock screen (Answer / Decline) and place
   an outbound call. If RTP stays `0/0`, the audio path has no IPv4 route.
4. Turn Wi-Fi back on. The log should show a new REGISTER without scanning again.

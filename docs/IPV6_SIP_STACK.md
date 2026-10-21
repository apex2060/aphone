# Plan: IPv6-capable SIP stack

Status: plan only. This branch does not change the softphone. v1.0.5 (merge `a50311a`) stays on `com.plivo.endpoint:endpoint:3.2.2-beta`.

The cellular failure on the Samsung SM-S908U1 (Android 16, 5G) is in that binary. It registers on Wi-Fi and returns `NOT_REGISTERED` on the carrier. v1.0.5 already says so in the header (“This network can't register”) and recreates the endpoint when the default network kind changes. Recreating it cannot add IPv6.

Plivo has discontinued the native Android and iOS SDKs and points integrators at PJSIP and Linphone: <https://plivo.com/docs/voice/client/androidios/overview>. Maven’s latest `com.plivo.endpoint:endpoint` is `3.2.2-beta` (published 2024-10-04). The Android quickstart text says the SDK supports IPv6. The binary we ship does not. There is no newer Android artifact to upgrade to.

## Recommendation

Use **PJSIP (pjsua2)** for the spike, and do not port `PhoneManager` until that spike passes on the same phone.

Pass criteria, all four, on Wi-Fi and on 5G:

1. `REGISTER` succeeds and the Plivo console shows the endpoint registered.
2. An inbound `<Dial><User>sip:&lt;user&gt;@phone.plivo.com</User></Dial>` rings the app.
3. Answer produces two-way audio.
4. Switching Wi-Fi and cellular re-registers without a reinstall.

Try the simple path first. A normal stack that uses the platform sockets may already reach `phone.plivo.com` over 464XLAT, because the carrier’s CLAT translates IPv4 destinations. The Plivo SDK never gets that far: it binds its own IPv4 TLS socket and IPv6 is compiled out. If CLAT is enough, signaling and RTP can both stay on the IPv4 addresses Plivo publishes, which is the path Wi-Fi already uses.

If that `REGISTER` still fails on 5G, repeat it explicitly over IPv6 to `mobile.phone.plivo.com`, with the TLS certificate checked as `phone.plivo.com` (see below). If the IPv6 host rejects a third-party `REGISTER`, or accepts it and never delivers an INVITE for `sip:&lt;user&gt;@phone.plivo.com`, stop. Linphone cannot invent an edge that PJSIP cannot reach.

Choose Linphone instead only when we want a prebuilt Maven artifact and we will either publish the app’s source under the SDK’s copyleft terms or buy Belledonne’s commercial license. The call-state work in `PhoneManager` is similar either way. Linphone trades the NDK build for a much larger core and AGPL.

Leave draft PR #4 alone. Plivo has not issued push credentials (ticket #29042). Main keeps the always-on registration foreground service.

## What the edge accepts

Looked up and probed on 2026-10-21 from this build environment. Addresses change; re-resolve before the spike hardcodes anything.

| Name | A | AAAA |
|---|---|---|
| `phone.plivo.com` | `52.9.254.123` | none |
| `mobile.phone.plivo.com` | `52.9.254.123` (same host today) | `2600:1f18:6750:901:489d:c5d9:cfed:1f1d`, `2600:1f18:6750:908:929b:b6e5:4a46:8fa3`, `2600:1f1c:4ad:2f02:b00f:413:525a:9484`, `2600:1f1c:4ad:2f02:ff37:478:ec64:c1b2` |
| `client.plivo.com` | `50.18.74.18`, `54.215.94.121` | none |
| `app.plivo.com` | did not resolve (NXDOMAIN here) | none |

`client.plivo.com` is the WebRTC SDK’s old default domain. The running 3.2.2-beta client registers to `phone.plivo.com`. `app.plivo.com` is named on Plivo’s firewall page and did not resolve here. Do not use either as the registrar.

Plivo’s firewall page (<https://plivo.com/docs/voice/concepts/firewall-network-configuration>) lists SIP phones (`phone.plivo.com` and `app.plivo.com`) as IPv4 only, and mobile phones (`mobile.phone.plivo.com`) as the name that has IPv6. The page’s single published IPv6 address (`2600:1f1c:4ad:2f02:ac95:55cc:9a28:3891`) is not in the live AAAA set above. Trust DNS for addresses and the page for the port list, then confirm with a probe.

Ports on `52.9.254.123` from here:

| Port | Result |
|---|---|
| TCP 5060, 5061, 5063, 443 | open |
| TCP 5080 | connection refused |
| TLS 5061 | TLS 1.2, `AES256-GCM-SHA384`. Certificate CN and SAN are `DNS:phone.plivo.com` only (valid 2026-01-09 through 2027-02-09). |
| TLS 443 | TCP accepts, handshake did not finish within 6 seconds |
| UDP 5060 | answered `SIP/2.0 484 Address Incomplete` to an unauthenticated `OPTIONS` (the port speaks SIP) |
| UDP 5080 | no response |

Connecting to `mobile.phone.plivo.com:5061` and verifying the name `mobile.phone.plivo.com` fails with a hostname mismatch. Connecting to that same host and verifying `phone.plivo.com` succeeds. The stack must look up AAAA on `mobile.phone.plivo.com` when it needs IPv6, and it must send SNI and check the certificate as `phone.plivo.com`. A strict check against the lookup name will fail before any SIP response and look like the cellular bug all over again.

This environment has no IPv6 route (`Network is unreachable` to those AAAA addresses). The phone spike is what proves the v6 listener answers.

RTP media on the firewall page is IPv4 CIDRs, UDP 16384–32768, plus a wider UDP/TCP range. There is no published IPv6 media range. On 464XLAT, RTP to an IPv4 media address works when the stack `connect()`s normally and the carrier’s CLAT translates it. A network with no CLAT and no IPv4 source cannot send RTP to those servers. Two-way audio on the Samsung is the test, not a DNS lookup.

Do not copy Zentrunk settings onto this endpoint. Zentrunk (<https://plivo.com/docs/sip-trunking/concepts/technical-specifications>) allows PCMU/PCMA, TLS 1.2/1.3, SRTP, and RFC 2833, and it does not accept `REGISTER`. Endpoint accounts are the other product: the console sip URI stays `sip:&lt;user&gt;@phone.plivo.com`, and generic SIP phones that register there use TLS, TCP, or UDP, G.711 µ-law, and RTP rather than SRTP. The discontinued Android SDK is a third path (WebRTC, Opus, DTLS-SRTP inside `libjingle_peerconnection_so.so`). Replacing the SDK means leaving that WebRTC path.

The username in testing, `ryan199045754223789643`, is 24 characters and starts with a letter. Plivo’s endpoint docs describe alphanumeric usernames of 1–25 characters starting with a letter. Keep the username the provision document returns. Do not prepend `+` to caller-ID; it arrives as digits.

### Transport order for the spike

1. When the phone has a usable IPv4 source (Wi-Fi, or cellular CLAT): TLS to `phone.plivo.com:5061`, certificate name `phone.plivo.com`. This is the path v1.0.5 already uses successfully on Wi-Fi.
2. When there is no IPv4 source: TLS to an AAAA of `mobile.phone.plivo.com:5061`, SNI and certificate name `phone.plivo.com`. AOR stays `sip:&lt;user&gt;@phone.plivo.com`.
3. If the TLS handshake fails: TCP `5060` to the same target.
4. TCP `5063` only if 5061 and 5060 both fail. It accepted a TCP connection here and was not spoken to as SIP.
5. UDP `5060` last. It speaks SIP and it is the worst NAT binding.
6. Skip 5080 and 443. 5080 was refused or silent, and 443 did not complete TLS.

Offer PCMU first, PCMA second, and `telephone-event/8000` for RFC 2833 DTMF. Leave Opus and SRTP off. If a call connects and both directions are silent, one diagnostic build can offer SAVP; do not make SRTP the default on the strength of the Zentrunk page.

`REGISTER` via `mobile.phone.plivo.com` might still not receive INVITEs addressed to `@phone.plivo.com`. The endpoint’s sip URI does not change. That is why inbound ring is a pass criterion and not an assumption.

## Why 3.2.2-beta cannot do this

`Endpoint.login` builds `<sip:user@domain;transport=tls>` and calls resiprocate `SipStack::addTransport` with `IpVersion` 0 (IPv4) and TLS. The app never passes a transport. `newInstance`’s setup map only reads `enableTracking`, `enableQualityTracking`, and `maxAverageBitrate`. There is no public IPv6 or UDP switch. The native library contains `Library was not build w/ required capabilities(probably USE_IPV6 resip/ares mismatch` and `Unparsable V6 address (note, this might be unparsable because IPV6 support is not enabled)`. `networkChange()` retries the same IPv4 bind. Empty interface means “any IPv4 interface”, which an IPv6-only network does not have, so registration fails with `NOT_REGISTERED` and a resip log line (`Can't find source address for Via`, `No transport found`, or the pre-send `makeInitialRequest` snapshot whose Via looks empty because the transport fills it in later).

## What stays

The SIP owner is `PhoneManager`. UI, provisioning, and the foreground service stay. Public methods the UI already calls, and that the new stack has to keep:

| Behavior | Where it lives today | New stack |
|---|---|---|
| QR provisioning, `sip.username` / `sip.password` only | `Provisioner`, `ConfigStore` (prefs name `softphone`), `ProvisionConfig`, [PROVISIONING.md](PROVISIONING.md) | Unchanged. First cut hardcodes registrar selection. A later document may add `sip.registrar` and `sip.transport`. Existing QR codes keep working. |
| Register, 120s expiry, 20s login timeout | `PhoneManager.REG_TIMEOUT_SEC`, `LOGIN_TIMEOUT_MS` | Same timers. |
| 90s refresh, network-change re-register | `PhoneService.REGISTER_REFRESH_MS`, `onDefaultNetwork` | Same. Do not `login` again while a registration is in flight. Same network kind does not recreate the stack. |
| Outgoing | `PhoneManager.dial` | `INVITE` the digits as today. |
| Incoming ring, lock screen, Answer / Decline | `PhoneService`, `Notifications` (CallStyle, full-screen intent), `CallActivity` | A real SIP stack delivers the INVITE. Delete the Plivo reflection that synthesizes an `Incoming` (`bindIncoming`, the `onCallEventListener` proxy). |
| Hangup / BYE | `signalSdkHangup` then local teardown | Signal first. `Call.hangup()` sends BYE or CANCEL. Unanswered incoming rejects with 486. Do not close media before the SIP request; that is how v1.0.5’s remote party stayed connected. |
| DTMF | `PhoneManager.sendDigit` | RFC 2833 via `telephone-event`. |
| Mute / speaker | `toggleMute`, `toggleSpeaker` (`AudioManager`) | Mute is a transmit level on the conference bridge. Speaker stays `AudioManager`. Oboe or OpenSL can fight the speaker route; test it on the Samsung. |
| Details | last login and call result, SDK reason, timestamps, network line | SIP status code, reason phrase, transport, address family, registrar host. Never log the SIP password, `Authorization`, the provision key, or a full FCM token. |
| Locks | partial wake lock `softphone:register`, wifi lock, foreground service | Keep them until our own push exists. |
| Identity | `applicationId` `com.apex2060.softphone` | Unchanged. |

Caller display keeps stripping `sip:` / `sips:` / `tel:` and taking the user part. No leading `+`.

Mid-call handover can drop the call. Re-register when the default network returns. Do not promise seamless handover. PJSIP’s ICE has historically not supported IPv6, and this app does not need ICE to register to a known edge.

## Linphone and PJSIP

### PJSIP / pjsua2 (Teluu)

Dual license: GPL-2.0-or-later, and a proprietary license from Teluu (`licensing@teluu.com`, also published as `licensing@pjsip.org`). <https://pjsip.org/licensing.htm>. No AGPL network clause.

An old Teluu datasheet says “IPv6 (UDP only)”. That is stale. TCP and TLS over IPv6 have existed since 2.1 (ticket 1585). Current pjsua transports include `PJSIP_TRANSPORT_UDP6`, `TCP6`, and `TLS6`, and `pjsip_tls_transport_start2` takes an IPv6 flag. Build with `PJ_HAS_IPV6` enabled (it is the default in current releases). IPv6 media is a separate account flag (`ipv6_media_use`). For this carrier, prefer an IPv4 media address whenever any IPv4 source exists, including CLAT, so SDP matches Plivo’s IPv4 media servers.

Android build: NDK, `./configure-android --with-ssl --with-oboe`, SWIG bindings in `org.pjsip.pjsua2`, shipping `libpjsua2.so`, `libc++_shared.so`, and OpenSSL if it is not linked statically. The test phone is Android 16, which uses 16 KB pages. Build with NDK r27 or newer and `-Wl,-z,max-page-size=16384` (pjproject PR 4068). A 4 KB-aligned `.so` will fail to load on that phone.

The API lines up with `PhoneManager`: `Account`, `Call`, `onIncomingCall`, `hangup`, `dialDtmf`. One account, PCMU, TLS, and `REGISTER` is the whole product surface.

### Linphone SDK (Belledonne)

Dual license: GNU AGPLv3, or a proprietary license from Belledonne. Their README says a client app is under the usual GPLv3 terms because the AGPL remote-network clause is aimed at using the SDK server-side, and that AGPL was chosen so the same license covers server use. The license file is still AGPLv3. A build that links GPL third parties such as FFmpeg is GPL regardless. The default build without those third parties still subjects the combined app to the SDK license unless the commercial license is purchased.

belle-sip is dual-stack. A Maven AAR exists, so we would not own the NDK configure. The core also carries video, chat, and presence that this app does not use. Stripping them is its own project, and the published Linphone Android client (6.2.8, 2026-09-28, about 89 MB) is that full client, not the size of an embedded SDK. Do not budget 89 MB.

### Licensing for a sideloaded app

This is not legal advice.

Copyleft obligations attach when the binary is distributed. Installing the APK only on phones the copyright holder owns, and not giving it to anyone else, is generally not distribution, so neither license by itself forces publication of the source for that private install.

Giving the APK to employees, customers, or anyone else is distribution. The combined app then has to carry the license notices and a source offer, and recipients may redistribute it, unless a commercial license is bought from Teluu (PJSIP) or Belledonne (Linphone). GPL-2.0-or-later can be satisfied under GPLv2 or GPLv3 and has no AGPL network term. Linphone’s client-side reading is GPLv3 as Belledonne describes it, with an AGPL text on the file.

A household or single-company sideload is the gray area. Treat handing the APK to any other person as distribution.

This repo’s release workflow already attaches a signed APK to tags. The first tagged build that links PJSIP or Linphone is distribution if anyone else can download that artifact. Before that tag: buy the commercial license, or publish the corresponding source with the APK, or stop publishing the APK and sideload it privately. The provision server (`_comm`) is a separate program and is not linked into the APK, so the SDK license does not cover it.

PJSIP is the better fit on this point for a private sideload: GPL-2.0-or-later, no AGPL clause, and a commercial escape hatch that is a single vendor email. Linphone is the better fit when we want Belledonne to ship the binary and we are ready to comply with AGPLv3 or pay them.

## APK size

Measured on the v1.0.5 tree:

| Artifact | Size |
|---|---|
| `app-release.apk` | 23,975,232 bytes (~23 MB), minify and resource shrinking on |
| `app-debug.apk` | 33,875,555 bytes (~34 MB) |
| `endpoint-3.2.2-beta.aar` | 20,544,791 bytes (~20 MB), includes x86 and x86_64 which the app does not package |
| arm64 native, uncompressed | `libjingle_peerconnection_so.so` 6,135,952 + `librtcsip_jni.so` 5,884,528 + `libc++_shared.so` 976,648 |
| armeabi-v7a native, uncompressed | `libjingle` 3,480,752 + `librtcsip` 4,138,016 + `libc++` 656,848 |

`abiFilters` stay `arm64-v8a` and `armeabi-v7a`.

PJSIP, voice only (PCMU, PCMA, `telephone-event`, OpenSSL, Oboe, those two ABIs), should land in the same band or a bit under the native libraries above. **Estimate, not a measurement:** release APK stays around 20–30 MB. The spike’s arm64 `.so` is the number to write down before believing the estimate.

Linphone’s embedded SDK was not measured. Even with video and extra ABIs removed it is expected to be noticeably larger than today’s release APK. Measure the no-video AAR before choosing it for size reasons. The 89 MB figure is the full Linphone app and is not the delta.

## Battery and push

v1.0.5 already pays for an always-on foreground service, a partial wake lock, and a wifi lock so the registration survives Doze. The SIP timers are `Expires: 120` and a refresh every 90 seconds, plus a re-register when the default network kind changes. Keep that. TLS or TCP does not need the extra 15–30 second CRLF keepalive that UDP needs, so prefer TLS and do not add a second timer. A shorter expiry would wake the radio more often for no signaling benefit.

Plivo FCM does not survive this migration. PR #4’s flow (`login(username, password, fcmToken)`, then `loginForIncomingWithUsername`) depends on Plivo holding the INVITE and sending a data message whose keys (`registrar`, `index`, `label`) only their SDK understands. pjsua2 and liblinphone cannot finish a call from that payload. Ticket #29042, if it is ever fulfilled, still would not wake a third-party stack.

Our own high-priority FCM message from `_comm` is the later way to drop the always-on service:

- The app obtains an FCM token. Main has no Firebase today (`README` says Play services are not required). `google-services.json` stays uncommitted, the same rule as PR #4.
- `_comm` stores the token. That is a new field or a small register call, not a change to the required `sip.username` / `sip.password` pair.
- The answer URL that emits `<Dial>` sends the data message first, so the phone can register inside Dial’s timeout (the server has been using 20 seconds).
- Until that exists, keep the v1.0.5 foreground service. Push is not a blocker for the stack swap, and PR #4 is not the wake mechanism.

## Effort

The unknown is on the phone, not in Compose.

- **Spike.** A throwaway pjsua2 client with one account, no QR scanner, no call screen. The work is the NDK build (OpenSSL, 16 KB pages, arm64 first) and reading registration and call callbacks. armeabi-v7a waits until the arm64 spike passes. This environment cannot run the pass criteria; the Samsung has to.
- **Port, only if the spike passes.** One owner class, `PhoneManager`, plus a prebuilt `jniLibs` module. `CallActivity`, `HomeScreen`, `Notifications`, `PhoneService`’s ring and locks, `Provisioner`, and `ConfigStore` stay. Delete the Plivo reflection (registration-event proxy, call-event proxy, `IO.reset`, `PeerConnection` close). Drop `-keep class com.plivo.**` and keep `org.pjsip.pjsua2`. The call-state machine is a port of the existing phases (`RINGING_IN`, `DIALING`, `RINGING_OUT`, `ACTIVE`, `ENDED`), including signal-then-teardown.
- **Device follow-up.** Speaker versus earpiece, DTMF, and one-way audio are the pieces likely to need a second build on the carrier. Details maps SIP status codes instead of resip log lines.
- **Push from `_comm`.** A separate, later change. It does not gate the registrar swap.

Linphone skips the NDK configure and keeps the same `PhoneManager` port, plus an AAR size check and the license decision above.

## Migration steps

Do these in order. Stop at the first failed gate.

1. Leave PR #4 and the v1.0.5 behavior untouched.
2. New branch, throwaway client, not an edit to `PhoneManager`.
3. Build current PJSIP with `PJ_HAS_IPV6`, OpenSSL, Oboe, PCMU/PCMA/`telephone-event`, 16 KB max page size, `arm64-v8a` only.
4. Account: AOR `sip:&lt;user&gt;@phone.plivo.com`. Registrar `sip:phone.plivo.com;transport=tls` when an IPv4 source exists. Otherwise resolve `mobile.phone.plivo.com` AAAA and set the TLS server name to `phone.plivo.com`. SRTP off. Registration expiry 120 seconds.
5. Run the pass criteria on Wi-Fi, on 5G, and across a network change. Capture the SIP status line, the Contact address family, and whether audio is two-way. If `REGISTER` succeeds and audio fails, record the SDP `c=` and `m=` lines before changing codecs.
6. If both the CLAT attempt and the explicit IPv6 attempt fail, write down the SIP code and stop. Do not start the UI port.
7. If they pass, replace `implementation("com.plivo.endpoint:endpoint:3.2.2-beta")` with the prebuilt module. Keep `minSdk` 26 and the two phone ABIs (add armeabi-v7a back for release parity).
8. Reimplement `login`, `refreshRegistration`, `onDefaultNetwork`, `dial`, `answer`, `decline`, `hangup`, `toggleMute`, `toggleSpeaker`, and `sendDigit` on pjsua2. `CallActivity` and `HomeScreen` keep calling those methods.
9. Map `onIncomingCall` onto the existing `RINGING_IN` state so `PhoneService` rings as it does now.
10. Hang up with `Call.hangup()` / 486 before marking the call ended.
11. Point Details at the SIP status, transport, address family, and registrar. Keep the “this network can't register” label for a local bind failure, matched against pjsip errors rather than resip strings.
12. `./gradlew assembleDebug`. The owner tests the same matrix on the Samsung before any tag.

## Risks

- **The v6 name is not our registrar.** `mobile.phone.plivo.com` may exist for Plivo’s own mobile SDK. A rejected `REGISTER`, or a `REGISTER` that never receives the endpoint INVITE, ends the project. No stack works around a missing edge.
- **Signaling up, audio down.** Media is IPv4. IPv6-only without CLAT cannot reach it. CLAT that translates TCP SIP and drops UDP RTP looks like a successful registration and a silent call. Confirm with SDP and a packet capture on the phone before swapping codecs.
- **Certificate name.** Verifying `mobile.phone.plivo.com` fails today. The TLS server name has to be `phone.plivo.com`.
- **SRTP guess.** Endpoint phones use RTP. The old SDK uses DTLS-SRTP. Offering SRTP first can make a working PCMU call fail. Offer it only after plain RTP is silent.
- **Codec order.** Offering Opus first can be rejected or transcoded badly. PCMU first.
- **Contact reachability.** A 200 OK on `REGISTER` whose Contact Plivo cannot route to still never rings. Read the Contact and the `received` / `rport` parameters. We depend on the edge latching the source address. We are not adding ICE to paper over that.
- **16 KB pages.** A default Android PJSIP build crashes on load on the Android 16 phone.
- **Audio route.** Oboe and `AudioManager.isSpeakerphoneOn` disagree often enough that mute can work while speaker does not, or the earpiece stays silent after answer.
- **Publishing the APK.** A tagged GitHub release that contains the GPL or AGPL library is distribution. Decide license or source offer before that tag.
- **Log leakage.** High pjsip log levels print `Authorization`. Cap the level in release, and keep Details free of credentials.
- **Registration storms.** Keep the v1.0.5 guards: no second `login` while connecting, and no stack rebuild when the network kind did not change.
- **Wi-Fi regression.** Prefer `phone.plivo.com` TLS on any network that has IPv4, so the working Wi-Fi path stays the default.
- **Username and caller-ID.** Do not normalize the provisioned username, and do not add `+` to inbound caller-ID.

## Out of scope here

No SIP implementation, no SDK bump, no merge, and no tag. PR #4 stays a draft on its own branch.

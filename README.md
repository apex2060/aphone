# 22 Phone

An Android phone app for Plivo. A fresh install shows nothing but a QR scanner. Scan a
setup code and the phone pulls its SIP credentials, title and colors from your server, registers
its push token with Plivo, and becomes a dialer that makes calls, rings for incoming calls, and
keeps a call history.

* **Provisioning:** QR code with a URL and key, then a JSON document from your server. See
  [docs/PROVISIONING.md](docs/PROVISIONING.md) for the schema and [server/](server/) for a
  zero-dependency sample endpoint.
* **Calling:** Plivo Android SDK (`com.plivo.endpoint:endpoint`) logged in with endpoint credentials.
* **Incoming calls:** Plivo sends a high-priority FCM data message. The app relays it to the
  Plivo SDK, which builds the incoming call, and a full-screen Answer / Decline notification
  rings the phone. The app does not keep a SIP registration open between calls.
* **History:** a local SQLite log of incoming, outgoing, missed and declined calls, with tap to call back.
* **Remote control of the fleet:** the phone re-fetches its document every `refreshHours`, and
  an HTTP `410` from your server wipes it back to the scanner.
* **Reset:** long-press the title bar and enter the admin PIN.

Stack: Kotlin, Jetpack Compose, CameraX + zxing for scanning, Firebase Cloud Messaging for
incoming calls. minSdk 26 (Android 8), target 35. The application id stays `com.apex2060.softphone`.

Release builds accept only `https://` provisioning URLs. Debug builds also accept `http://` so a
phone on the same LAN can reach the sample server.

## Build

Debug, for a local install:

```sh
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

A release APK is signed with one stable keystore, not the debug key. `./gradlew assembleRelease`
fails if that keystore is not configured, instead of writing an unsigned APK.

```sh
export ANDROID_KEYSTORE_FILE=/path/to/22phone-release.keystore
export ANDROID_KEYSTORE_PASSWORD='...'
export ANDROID_KEY_ALIAS='22phone'
export ANDROID_KEY_PASSWORD='...'
./gradlew assembleRelease -PversionName=1.0.1 -PversionCode=2
```

`versionName` defaults to `1.0.0` and `versionCode` defaults to `1`. CI sets `versionName` from
the git tag (`v1.0.1` becomes `1.0.1`) and `versionCode` from the GitHub Actions run number, which
increases on every run. Pass `-PversionName` and `-PversionCode` to override either value. The
keystore is never committed.

### One-time signing setup

Generate a keystore and keep a copy of the file and the password outside the repo. Losing them
means installed phones cannot be updated.

```sh
keytool -genkeypair -v \
  -keystore 22phone-release.keystore \
  -alias 22phone \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=22 Phone, O=22 Phone, C=US"
```

`keytool` asks for one password. PKCS12 uses that password for both the store and the key. Do not
commit `22phone-release.keystore`.

Base64-encode the file (a single line, no wrapping):

```sh
base64 -w 0 22phone-release.keystore          # Linux
base64 -i 22phone-release.keystore | tr -d '\n'   # macOS
```

In GitHub: Settings → Secrets and variables → Actions → New repository secret. Add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the base64 string |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `22phone` (or the alias you chose) |
| `ANDROID_KEY_PASSWORD` | the same password (PKCS12 has only one) |
| `GOOGLE_SERVICES_JSON_BASE64` | base64 of `google-services.json` (package `com.apex2060.softphone`). Optional: if it is unset, the APK builds with incoming push disabled |

`google-services.json` is not committed. Place it at `app/google-services.json` for a local build
that can receive pushes, or set the secret above. Without the file, `./gradlew assembleDebug`
still succeeds.

### Cutting a release

```sh
git tag v1.0.1
git push origin v1.0.1
```

GitHub Actions (`.github/workflows/build.yml`) compiles a debug APK on pull requests. Pushing a
tag such as `v1.0.1`, or running the workflow manually, builds `22phone.apk`, uploads it as the
`22phone` artifact, and (for a tag) attaches it to that tag's GitHub Release. Download it from
the release page (`22phone.apk`) or from the workflow run's `22phone` artifact, then update the
Google Drive link. The job stops with an error if any of the four secrets is missing, and it does
not upload an unsigned or debug-signed APK.

The first build signed with this key will not install over a phone that still has an older
debug-signed build. Uninstall that copy once. Later tags install over this one.

## First run on a phone

1. Install the APK and open it. Allow the camera.
2. Scan the setup QR code.
3. Allow the microphone, notifications, full-screen alerts, and battery optimisation
   (it asks once). Incoming calls arrive as a push, including after the app has been swiped away.

## Layout

```
app/src/main/java/com/apex2060/softphone/
  config/   ProvisionConfig (schema), Provisioner (QR + HTTP), ConfigStore (persistence)
  phone/    PhoneManager (Plivo endpoint + call state), PhoneService (in-call foreground service),
            PushService (FCM), Notifications, BootReceiver
  history/  CallHistory (SQLite)
  ui/       MainActivity, ScanScreen, HomeScreen (keypad, contacts, recents), CallActivity, Theme
server/     sample provisioning endpoint
docs/       provisioning schema
```

## Known limits

* Incoming calls need a Firebase Android app for `com.apex2060.softphone` and a Plivo mobile
  push credential (FCM HTTP v1 service account) linked to the endpoint's application. Until
  `google-services.json` is present, push is compiled out and inbound calls do not ring.
  A debug build can turn on "Stay registered" from Details to keep the old always-on SIP
  registration while testing.
* Plivo's Android SDK stays `com.plivo.endpoint:endpoint:3.2.2-beta`. That release already
  has `login(username, password, fcmToken)` and `loginForIncomingWithUsername` (the push
  entry that builds the incoming call). `relayVoipPushNotification` is deprecated.
  Plivo has stopped publishing a newer native mobile SDK.
* One call at a time. A second incoming call while busy is rejected and logged as missed.

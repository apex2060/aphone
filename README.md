# 22 Phone

An Android phone app. Opening it shows the list of phones. Tap Add phone and scan a
setup code. The phone pulls its SIP credentials, title and colors from your server, registers,
and becomes a dialer that makes calls, rings for incoming calls, and keeps a call history.

* **Provisioning:** QR code with a URL and key, then a JSON document from your server. See
  [docs/PROVISIONING.md](docs/PROVISIONING.md) for the schema and [server/](server/) for a
  zero-dependency sample endpoint.
* **Calling:** PJSIP 2.17 (`libpjsua2`), the same registration and call plan as 22 SIP Test 0.5.
  The Plivo Android SDK is not part of this app. Credentials are still the endpoint username
  and password from the provisioning document.
* **Incoming calls:** with Firebase configured, a high-priority data push wakes the phone,
  which registers, tells the server it is ready, and rings. Without `google-services.json`,
  a foreground service keeps the registration open. Either way the ring is a full-screen
  notification with Answer and Decline, and missed calls are logged.
* **History:** a local SQLite log of incoming, outgoing, missed and declined calls, with tap to call back.
* **Remote control of the fleet:** the phone re-fetches its document every `refreshHours`, and
  an HTTP `410` from your server removes that phone from the list.
* **Reset:** long-press the title bar and enter the admin PIN.

Stack: Kotlin, Jetpack Compose, CameraX + zxing for scanning, PJSIP 2.17, and optional
Firebase Cloud Messaging. minSdk 26 (Android 8), target 36. The Play application id is
`us.alphabetize.phone22`. Kotlin sources stay in `com.apex2060.softphone`.

A line's provisioning response can name that server's Firebase project (`push.projectId` and
the other public client fields in [docs/PROVISIONING.md](docs/PROVISIONING.md)). The phone
creates one Firebase app per project and sends that project's token only back to that line.
Lines without `push` use the default app from `app/google-services.json` when the file was
present at build time. That file is not in git. It must include an Android client whose
`package_name` is `us.alphabetize.phone22` (Firebase project homeschool-express). CI reads it
from the `GOOGLE_SERVICES_JSON_BASE64` secret and fails if that package is missing. If a line
has neither its own `push` object nor that default client, push stays off and that line keeps
the always-on registration.

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

`versionName` defaults to `1.3.6` and `versionCode` defaults to `27`. A manual Build APK run
uses the `version_name` and `version_code` inputs (for example `1.3.6` and `27`). A tag run
uses the tag name (`v1.0.1` becomes `1.0.1`) and the GitHub Actions run number instead.
Pass `-PversionName` and `-PversionCode` to override either value locally. The keystore is
never committed.

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
`us.alphabetize.phone22` is a different app from `com.apex2060.softphone`, so the same
certificate does not upgrade a sideload of the old id. Install this package beside it, or
uninstall the old one, then scan the QR again.

## First run on a phone

1. Install the APK and open it. The phone list is empty until you add one.
2. Tap Add phone and allow the camera.
3. Scan the setup QR code.
4. Allow the microphone and notifications. Full-screen alerts are requested so a locked
   phone can show the answer screen. A line that push cannot wake stays registered in a
   foreground service.

## Layout

```
app/src/main/java/com/apex2060/softphone/
  config/   ProvisionConfig (schema), Provisioner (QR + HTTP), ConfigStore (persistence)
  phone/    PhoneService (foreground service), Notifications, BootReceiver
  SipEngine.kt and the classes next to it  PJSIP registration, calls, and the on/off switch
  history/  CallHistory (SQLite)
  ui/       MainActivity, ScanScreen, HomeScreen (keypad, contacts, recents), CallActivity, Theme
server/     sample provisioning endpoint
docs/       provisioning schema
```

## License

22 Phone is free software under the GNU General Public License, version 2 or, at your
option, any later version. See [LICENSE](LICENSE). PJSIP is GPL-2.0-or-later. OpenSSL and
the AndroidX / Kotlin / Compose / CameraX / ZXing libraries are Apache-2.0.

Apache-2.0 software can be combined with a GPL-2.0-or-later program when the combined work
is distributed under GPL version 3 (the "or later" option). It would not be compatible with
GPL-2.0-only. The Open-source licenses screen in the app lists the same notices.

## Known limits

* A line without push depends on its foreground service staying connected. Android may still
  defer that service while the phone is optimizing battery use.
* A line with push registers when a high-priority data message arrives.
* The native library is arm64-v8a only.
* One call at a time. A second incoming call while busy is rejected and logged as missed.

# Plivo Softphone

A small Android softphone for Plivo. A fresh install shows nothing but a QR scanner. Scan a
setup code and the phone pulls its SIP credentials, title and colors from your server, registers
with Plivo, and becomes a dialer that makes calls, rings for incoming calls, and keeps a call history.

* **Provisioning:** QR code with a URL and key, then a JSON document from your server. See
  [docs/PROVISIONING.md](docs/PROVISIONING.md) for the schema and [server/](server/) for a
  zero-dependency sample endpoint.
* **Calling:** Plivo Android SDK (`com.plivo.endpoint:endpoint`) logged in with endpoint credentials.
* **Incoming calls:** a foreground service keeps the registration open, posts a full-screen
  ringing notification with Answer and Decline, and logs missed calls.
* **History:** a local SQLite log of incoming, outgoing, missed and declined calls, with tap to call back.
* **Remote control of the fleet:** the phone re-fetches its document every `refreshHours`, and
  an HTTP `410` from your server wipes it back to the scanner.
* **Reset:** long-press the title bar and enter the admin PIN.

Stack: Kotlin, Jetpack Compose, CameraX + zxing for scanning. No Google Play services or Firebase
needed. minSdk 26 (Android 8), target 35.

## Build

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

GitHub Actions builds debug and release APKs on every push (`softphone-apks` artifact).
The release build is signed with the debug key so it can be sideloaded; set up a real keystore
before publishing to a store.

## First run on a phone

1. Install the APK and open it. Allow the camera.
2. Scan the setup QR code.
3. Allow the microphone and notifications, and allow the app to ignore battery optimisation
   (it asks once). Without that, Android may stop the connection and the phone won't ring.

## Layout

```
app/src/main/java/com/apex2060/softphone/
  config/   ProvisionConfig (schema), Provisioner (QR + HTTP), ConfigStore (persistence)
  phone/    PhoneManager (Plivo endpoint + call state), PhoneService (foreground service),
            Notifications, BootReceiver
  history/  CallHistory (SQLite)
  ui/       MainActivity, ScanScreen, HomeScreen (keypad, contacts, recents), CallActivity, Theme
server/     sample provisioning endpoint
docs/       provisioning schema
```

## Known limits

* Incoming calls depend on the foreground service staying connected. That is reliable with the
  battery exemption, but a Firebase push setup (Plivo supports it) would be more robust on
  aggressive OEM battery managers.
* One call at a time. A second incoming call while busy is rejected and logged as missed.

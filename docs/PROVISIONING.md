# Provisioning

A fresh install shows only a QR scanner. Scanning a setup code makes the phone fetch a JSON
document from your server, save it, register with Plivo, and switch to the dialer. The phone
stays provisioned across reboots and app updates until it is reset or revoked.

## 1. The QR code

The QR code holds one of two things.

**JSON ticket (recommended).** The key travels separately from the URL.

```json
{"url": "https://phones.example.com/provision", "key": "front-desk-7f3a9c"}
```

The phone sends:

```http
POST /provision
Content-Type: application/json
X-Provision-Key: front-desk-7f3a9c

{"key": "front-desk-7f3a9c", "device": {"model": "Google Pixel 8", "android": "15", "app": "1.0.0"}}
```

**Bare URL.** The phone sends a plain `GET` to the URL, so the key has to be in it,
for example `https://phones.example.com/provision?key=front-desk-7f3a9c`.

Release builds only accept `https://` URLs. Debug builds also accept `http://` so you can test
against a laptop on the LAN.

Generate a code with any QR tool, for example:

```sh
qrencode -o front-desk.png '{"url":"https://phones.example.com/provision","key":"front-desk-7f3a9c"}'
```

## 2. The response

Respond `200` with `application/json`. Only `sip.username` and `sip.password` are required.

```json
{
  "version": 1,
  "title": "Acme Front Desk",
  "subtitle": "Main line",
  "sip": {
    "username": "frontdesk123456789",
    "password": "endpoint-password"
  },
  "theme": {
    "dark": true,
    "primary": "#0B5FFF",
    "onPrimary": "#FFFFFF",
    "background": "#0E1116",
    "onBackground": "#E6E8EB",
    "surface": "#171B22",
    "onSurface": "#E6E8EB",
    "accept": "#1DB954",
    "decline": "#E5484D"
  },
  "dialing": {
    "countryCode": "1",
    "allowFreeDial": true
  },
  "speedDials": [
    {"label": "Manager", "number": "+15551230001"}
  ],
  "historyLimit": 200,
  "adminPin": "2468",
  "refreshHours": 24
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `version` | int | `1` | Schema version, reserved for future changes. |
| `title` | string | `"22 Phone"` | Shown in the header bar and notifications. |
| `subtitle` | string | none | Small line under the title, e.g. the line name or extension. |
| `sip.username` | string | **required** | Plivo **Endpoint** username (Voice > Endpoints in the Plivo console). |
| `sip.password` | string | **required** | Plivo Endpoint password. |
| `theme.dark` | bool | `true` | Base Material palette to start from. |
| `theme.primary` / `onPrimary` | color | blue / white | Header bar and highlighted buttons, and the text on them. |
| `theme.background` / `onBackground` | color | near-black / light grey | Screen background and its text. |
| `theme.surface` / `onSurface` | color | dark grey / light grey | Keypad keys, tab bar, dividers. |
| `theme.accept` / `decline` | color | green / red | Call and hang-up buttons, status dot, missed calls. |
| `dialing.countryCode` | digits | none | When `"1"`, 10-digit numbers get a leading `1`. |
| `dialing.allowFreeDial` | bool | `true` | `false` hides the keypad, so only speed dials and calling back known numbers work. |
| `speedDials[]` | `{label, number}` | `[]` | One-tap contacts, also used to put names on call history. |
| `historyLimit` | int | `200` | Calls kept on the device (10 to 5000). |
| `adminPin` | string | none | PIN needed to reset the phone. With no PIN, reset only asks for confirmation. |
| `refreshHours` | int | `24` | How often the phone re-fetches this document with the same ticket, so you can rotate credentials or colors remotely. `0` turns refresh off. |
| `push` | object | none | Optional Firebase Android client for this server. See below. |

Colors are `#RRGGBB` or `#AARRGGBB`. Unknown fields are ignored, and a bad color falls back to
its default.

### Push

Lines can be provisioned from different servers, and each server can send pushes from its own
Firebase project. The values are the public Android client config, not a server secret. Use
placeholders in examples. Do not put a real key in this document.

```json
"push": {
  "projectId": "example-project",
  "applicationId": "1:000000000000:android:0000000000000000000000",
  "apiKey": "AIzaSyEXAMPLEEXAMPLEEXAMPLEEXAMPLE",
  "gcmSenderId": "000000000000"
}
```

| Field | Meaning |
|---|---|
| `projectId` | Firebase project id. The phone keeps one named Firebase app per id and reuses it for every line that names the same project. |
| `applicationId` | `mobilesdk_app_id` (`1:<sender>:android:<hash>`). |
| `apiKey` | Android API key for that Firebase project. |
| `gcmSenderId` | Sender id, the numeric project number. |

If `push` is missing or any field is blank, the phone does not create a secondary app for that
line. It then uses the default Firebase app from `google-services.json` when this build has one
(the Ed 22 project). If neither exists, push is off for that line and the phone keeps its
registration open instead.

The phone asks Firebase for that app's token and sends it only to this line's provision origin:

* on `phone.provision`, once a token for that project is already known (the first scan often
  omits it, because the project is inside this response)
* on `POST {same url with action=phone.token}` with
  `{"key","fcmToken","platform":"android","appVersion","deviceId"}`

`deviceId` is a random UUID stored on the install. HTTP 410 still revokes that line.

Data messages are delivered to one messaging service for every sender. Include `endpoint`
(the SIP username) and `origin` (the provision host, such as `https://us.ed22.org`) so the phone
can pick the line. `callUuid` still identifies the call. If both `endpoint` and `origin` are
absent, any switched-on line may be woken, narrowed by `to` when that matches a number.

The caller's digits are `caller`. FCM v1 rejects a data key named `from`, so do not send that
name. The phone still accepts `from` only as a legacy fallback when `caller` is missing.

```json
{
  "type": "incoming_call",
  "callUuid": "example-call",
  "caller": "15551234567",
  "fromName": "Ada",
  "to": "15557654321",
  "endpoint": "frontdesk123456789",
  "origin": "https://phones.example.com",
  "ts": "1729530000000"
}
```

`cancel` uses the same `callUuid`, `endpoint`, and `origin`. The phone matches an INVITE header
named `X-Call-UUID` or any `X-PH-` header whose name contains `Call-UUID`, case-insensitive.

### Status codes

| Status | Phone behaviour |
|---|---|
| `200` | Save the document and (re)register. |
| `410 Gone` | The phone wipes its line and history and returns to the scanner. Use this to retire a device. |
| anything else | Setup shows the error; a background refresh keeps the current config and tries again later. |

## 3. Resetting a phone

Long-press the coloured title bar and enter the admin PIN. This removes the line and the call
history and returns to the scanner.

## 4. Plivo side

* Create one Plivo **Endpoint** per phone and put its username and password in `sip`.
* Attach the endpoint to a Plivo **Application** whose answer URL handles outbound calls. The phone
  dials digits only (for example `15551234567`) or a `sip:` URI, and your answer URL receives it
  as the `To` parameter. Return a `<Dial><Number>` with your caller ID, for example:

  ```xml
  <Response>
    <Dial callerId="15551230000"><Number>15551234567</Number></Dial>
  </Response>
  ```

* For inbound calls, route your Plivo number to `<Dial><User>sip:frontdesk123456789@phone.plivo.com</User></Dial>`.

## 5. Security notes

* The provisioning key is effectively a password for the SIP line. Use long random keys and HTTPS.
* Set `"revoked": true` (sample server) or return `410` to kill a lost phone at its next refresh.
* The document is stored in the app's private storage, which other apps cannot read.

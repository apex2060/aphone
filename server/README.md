# Sample provisioning server

A zero-dependency Node 18+ server implementing [docs/PROVISIONING.md](../docs/PROVISIONING.md).

```sh
cp devices.example.json devices.json   # one entry per phone, keyed by its provisioning key
node provision-server.mjs              # listens on :8080, PORT=... to change
```

Test it:

```sh
curl -s -X POST localhost:8080/provision -H 'Content-Type: application/json' -d '{"key":"front-desk-7f3a9c"}'
```

Then make the QR code:

```sh
qrencode -o front-desk.png '{"url":"https://phones.example.com/provision","key":"front-desk-7f3a9c"}'
```

Put it behind HTTPS (a reverse proxy, Cloudflare Tunnel, etc.) for release builds. Debug builds of
the app accept `http://` so you can point the QR code at `http://<laptop-ip>:8080/provision` on the LAN.

`devices.json` holds SIP passwords and is git-ignored.

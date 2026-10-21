// Minimal provisioning endpoint for 22 Phone. Node 18+, no dependencies.
//
//   cp devices.example.json devices.json   # edit keys, SIP creds, colors
//   node provision-server.mjs              # PORT=8080 by default
//
// Each key in devices.json maps to one phone's provisioning document.
// Set "revoked": true on an entry to make that phone wipe itself at its next refresh.
import { createServer } from "node:http";
import { readFileSync } from "node:fs";

const PORT = Number(process.env.PORT ?? 8080);
const FILE = process.env.DEVICES ?? new URL("./devices.json", import.meta.url);

function loadDevices() {
  return JSON.parse(readFileSync(FILE, "utf8"));
}

function send(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  res.end(JSON.stringify(body));
}

async function readBody(req) {
  let data = "";
  for await (const chunk of req) {
    data += chunk;
    if (data.length > 64_000) throw new Error("body too large");
  }
  return data ? JSON.parse(data) : {};
}

createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname !== "/provision") return send(res, 404, { error: "not found" });

  let key = req.headers["x-provision-key"] ?? url.searchParams.get("key");
  let device = {};
  if (req.method === "POST") {
    try {
      const body = await readBody(req);
      key = body.key ?? key;
      device = body.device ?? {};
    } catch {
      return send(res, 400, { error: "bad request" });
    }
  } else if (req.method !== "GET") {
    return send(res, 405, { error: "method not allowed" });
  }

  const entry = key ? loadDevices()[key] : undefined;
  if (!entry) return send(res, 404, { error: "unknown key" });
  if (entry.revoked) return send(res, 410, { error: "revoked" });

  console.log(new Date().toISOString(), "provisioned", key, JSON.stringify(device));
  const { revoked, ...doc } = entry;
  send(res, 200, doc);
}).listen(PORT, () => console.log(`provisioning on :${PORT}/provision`));

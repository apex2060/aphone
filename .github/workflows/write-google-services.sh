#!/usr/bin/env bash
# Writes app/google-services.json from GOOGLE_SERVICES_JSON_BASE64 when that secret is set.
# An empty secret is not an error: the Gradle build then compiles with push disabled.
set -euo pipefail

if [ -z "${GOOGLE_SERVICES_JSON_BASE64:-}" ]; then
  echo "GOOGLE_SERVICES_JSON_BASE64 is not set. This build leaves FCM push disabled."
  exit 0
fi

umask 077
mkdir -p app
printf '%s' "$GOOGLE_SERVICES_JSON_BASE64" | tr -d '[:space:]' | base64 -d > app/google-services.json
if [ ! -s app/google-services.json ]; then
  echo "::error::GOOGLE_SERVICES_JSON_BASE64 did not decode to a file."
  exit 1
fi
if ! grep -q 'com.apex2060.softphone' app/google-services.json; then
  echo "::error::google-services.json must contain package_name com.apex2060.softphone."
  exit 1
fi
echo "Wrote app/google-services.json ($(wc -c < app/google-services.json) bytes). FCM push is enabled for this build."

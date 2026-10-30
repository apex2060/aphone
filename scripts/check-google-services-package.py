#!/usr/bin/env python3
"""Fail unless google-services.json has an Android client for the Play application id.

CI decodes the GOOGLE_SERVICES_JSON_BASE64 secret into that file. This script prints
package names only, never keys.
"""
import json
import sys

EXPECTED = "us.alphabetize.phone22"
PATH = "app/google-services.json"


def main() -> int:
    try:
        with open(PATH, encoding="utf-8") as fh:
            data = json.load(fh)
    except json.JSONDecodeError as exc:
        print(
            f"::error::GOOGLE_SERVICES_JSON_BASE64 decoded to invalid JSON ({exc}). "
            f"It must include an Android client with package_name {EXPECTED}."
        )
        return 1
    found = []
    for client in data.get("client") or []:
        info = (client or {}).get("client_info") or {}
        pkg = (info.get("android_client_info") or {}).get("package_name")
        if isinstance(pkg, str) and pkg:
            found.append(pkg)
    if EXPECTED not in found:
        shown = ", ".join(found) if found else "(none)"
        print(
            f"::error::GOOGLE_SERVICES_JSON_BASE64 has no Android client with "
            f"package_name {EXPECTED} (found: {shown})."
        )
        return 1
    print(f"google-services.json includes package_name {EXPECTED}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

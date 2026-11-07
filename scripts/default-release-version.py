#!/usr/bin/env python3
"""Print the versionName or versionCode default committed in app/build.gradle.kts.

Usage: default-release-version.py name|code
"""
import pathlib
import re
import sys

text = pathlib.Path("app/build.gradle.kts").read_text(encoding="utf-8")
kind = sys.argv[1] if len(sys.argv) > 1 else ""
if kind == "name":
    match = re.search(r'\?: "(\d+\.\d+\.\d+)"', text)
    label = "versionName"
elif kind == "code":
    match = re.search(r"toIntOrNull\(\) \?: (\d+)", text)
    label = "versionCode"
else:
    sys.exit("usage: default-release-version.py name|code")
if not match:
    sys.exit(f"{label} default was not found in app/build.gradle.kts")
print(match.group(1))

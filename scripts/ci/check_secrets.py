#!/usr/bin/env python3
"""Lightweight tracked-file secret guard, not a replacement for dedicated scanners."""
import re
import subprocess
import sys
from pathlib import Path
patterns = [
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    re.compile(r"gh[pousr]_[A-Za-z0-9_]{30,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
]
files = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
issues = []
for fname in filter(None, files):
    path = Path(fname)
    if not path.is_file() or path.stat().st_size > 1_000_000:
        continue
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue
    if any(pattern.search(content) for pattern in patterns):
        issues.append(fname)
if issues:
    print("Possible tracked secret in: " + ", ".join(issues), file=sys.stderr)
    sys.exit(1)
print("Tracked secret patterns: PASS (limited patterns only)")

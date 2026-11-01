#!/usr/bin/env python3
"""Fail on high-risk paths; review all other changes through normal PR process."""
from pathlib import Path
import sys
files = Path(sys.argv[1]).read_text().splitlines()
bad = []
for name in files:
    normalized = name.replace("\\", "/")
    parts = normalized.lower().split("/")
    lower = normalized.lower()
    if any(p == ".env" or p.startswith(".env.") for p in parts) and not lower.endswith(".example"):
        bad.append(name + ": environment secret file")
    if any(p in ("id_rsa", "id_ed25519") for p in parts) or lower.endswith((".pem", ".p12", ".pfx", ".key")):
        bad.append(name + ": private key or certificate file")
    if lower.endswith((".db", ".sqlite", ".sqlite3", ".bak")):
        bad.append(name + ": database/backup artifact")
if bad:
    print("Forbidden committed paths:\n" + "\n".join(bad), file=sys.stderr)
    sys.exit(1)
print(f"PR scope gate: PASS ({len(files)} changed paths reviewed)")

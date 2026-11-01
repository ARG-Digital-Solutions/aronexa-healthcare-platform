#!/usr/bin/env python3
"""Small dependency-free documentation gate. No application tests implied."""
from pathlib import Path
import sys

required = [
    "README.md", "AGENTS.md",
    "docs/architecture/ARCHITECTURE.md",
    "docs/requirements/TRACEABILITY.md",
    "docs/phases/PHASE_REGISTER.md",
    "docs/releases/RELEASE_GATE.md",
]
errors = []
for name in required:
    p = Path(name)
    if not p.is_file() or not p.read_text(encoding="utf-8").strip():
        errors.append(f"Missing/empty required document: {name}")
for name in ("docs/requirements/TRACEABILITY.md", "docs/phases/PHASE_REGISTER.md"):
    text = Path(name).read_text(encoding="utf-8") if Path(name).exists() else ""
    for number in range(15):
        token = f"{number:02d}"
        if not any(line.lstrip().startswith(f"| {token} |") or line.lstrip().startswith(f"| {int(token)} |") for line in text.splitlines()) and name.endswith("PHASE_REGISTER.md"):
            errors.append(f"Missing phase {token} in {name}")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("Documentation gate: PASS (existence, nonempty, phase 00-14 listing)")

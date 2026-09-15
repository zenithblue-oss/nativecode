#!/usr/bin/env python3
import hashlib
import json
import pathlib
import sys

verify = len(sys.argv) > 2 and sys.argv[1] == "--verify"
root = pathlib.Path(sys.argv[2] if verify else sys.argv[1]).resolve()
files = {}
for path in sorted(p for p in root.rglob("*") if p.is_file() and p.name != "manifest.json"):
    files[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()

if verify:
    expected = json.loads((root / "manifest.json").read_text(encoding="utf-8"))["files"]
    if files != expected:
        raise SystemExit("mesa-mali: manifest verification failed")
    print("mesa-mali: manifest verification passed")
    raise SystemExit(0)

(root / "manifest.json").write_text(
    json.dumps({"format": 1, "files": files}, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)

#!/usr/bin/env python3
"""Verify transferred research, archived artifacts, and any extracted copy offline."""
from pathlib import Path
import hashlib
import json
import tarfile

root = Path(__file__).resolve().parent
manifest = json.loads((root / "transfer-manifest.json").read_text())
errors = []

def digest(stream):
    h = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        h.update(chunk)
    return h.hexdigest()

def verify_file(path, entry):
    if not path.is_file():
        errors.append(f"Missing: {path.relative_to(root)}")
        return
    with path.open("rb") as stream:
        actual = digest(stream)
    if path.stat().st_size != entry["bytes"] or actual != entry["sha256"]:
        errors.append(f"Changed: {path.relative_to(root)}")

for entry in manifest["files"]:
    verify_file(root / entry["path"], entry)

archive_path = root / "source-context.tar.gz"
if archive_path.is_file():
    with tarfile.open(archive_path, "r:gz") as archive:
        members = {m.name: m for m in archive.getmembers() if m.isfile()}
        expected = {e["path"]: e for e in manifest["archived_files"]}
        if members.keys() != expected.keys():
            errors.append("Archive file inventory differs from manifest")
        for name in members:
            if name not in expected:
                continue
            stream = archive.extractfile(members[name])
            with stream:
                actual = digest(stream)
            if members[name].size != expected[name]["bytes"] or actual != expected[name]["sha256"]:
                errors.append(f"Changed archive member: {name}")

if (root / "artifacts").exists():
    for entry in manifest["archived_files"]:
        verify_file(root / entry["path"], entry)

if errors:
    raise SystemExit("\n".join(errors))
print(f"Verified {len(manifest['files'])} transferred files and "
      f"{len(manifest['archived_files'])} archived artifacts.")

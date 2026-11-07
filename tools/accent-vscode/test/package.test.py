#!/usr/bin/env python3
"""Verify the distributed VSIX contains the current version, docs, and keyword grammar."""
import json
from pathlib import Path
import re
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
metadata = json.loads((root / "package.json").read_text())
version = metadata["version"]
archive = Path(sys.argv[1]) if len(sys.argv) > 1 else root / f"accent-vscode-{version}.vsix"
lock = json.loads((root / "package-lock.json").read_text())
assert lock["version"] == lock["packages"][""]["version"] == version
with zipfile.ZipFile(archive) as package:
    packaged_metadata = json.loads(package.read("extension/package.json"))
    assert packaged_metadata["version"] == version
    grammar_bytes = package.read("extension/syntaxes/accent.tmLanguage.json")
    assert grammar_bytes == (root / "syntaxes/accent.tmLanguage.json").read_bytes()
    grammar = json.loads(grammar_bytes)
    patterns = [rule["match"] for rule in grammar["repository"]["accent-keywords"]["patterns"]]
    assert not any(re.search(pattern, "async") for pattern in patterns)
    assert not any(re.search(pattern, "await") for pattern in patterns)
    assert package.read("extension/readme.md") == (root / "README.md").read_bytes()
    assert package.read("extension/dist/extension.js") == (root / "dist/extension.js").read_bytes()
print(f"Verified {archive.name}: start and await are methods, async is not a keyword, version and contents match source")

#!/usr/bin/env python3
"""Fetch the explicitly licensed smoke clip. Never scrape arbitrary workout videos."""
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "HangInThereTests/Fixtures/source.json"


def main():
    manifest = json.loads(MANIFEST.read_text())
    target = MANIFEST.parent / "pullups.mov"
    expected = manifest.get("sha256")
    if target.exists() and expected and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        print("Fixture checksum verified (cached).")
        return
    request = urllib.request.Request(manifest["download_url"], headers={
        "User-Agent": "HangInThere-P0/0.1 (https://github.com/yongkyuns/HangInThere; licensed test fixture)"
    })
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read(3_000_001)
            break
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    if not 10_000 <= len(data) <= 3_000_000:
        raise ValueError("Unexpected fixture size; refusing response.")
    if b"ftyp" not in data[:128] and b"moov" not in data[:128]:
        raise ValueError("Response is not a QuickTime/MP4 file.")
    actual = hashlib.sha256(data).hexdigest()
    print(f"FIXTURE_SHA256={actual} bytes={len(data)}")
    if expected and actual != expected:
        raise ValueError("Fixture checksum changed. Review the upstream media before changing the pin.")
    if not expected:
        print("WARNING: initial acquisition; pin this digest before qualification.", file=sys.stderr)
    temporary = target.with_suffix(".tmp")
    temporary.write_bytes(data)
    temporary.replace(target)


if __name__ == "__main__":
    main()

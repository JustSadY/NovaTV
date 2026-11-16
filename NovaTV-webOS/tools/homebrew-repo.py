#!/usr/bin/env python3
"""
Writes the files webOS Homebrew Channel needs to list, install and update NovaTV from a custom
repository (Homebrew Channel → Settings → Add repository):

  apps.json                      repository: {"paging": {...}, "packages": [ ... ]}
  com.novatv.webos.manifest.json app manifest: id, version, ipkUrl, ipkHash.sha256 ...

Usage:
  python3 tools/homebrew-repo.py dist/com.novatv.webos_1.1.0_all.ipk \
      --download-base https://github.com/OWNER/REPO/releases/download/v1.1.0 --out dist/homebrew

When the IPK, the manifest and apps.json are attached to every GitHub release, the repository
address that never changes is:  https://github.com/OWNER/REPO/releases/latest/download/apps.json
"""
import argparse
import hashlib
import json
import os
import sys

APP_ID = "com.novatv.webos"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("ipk")
    p.add_argument("--download-base", required=True, help="URL folder where the IPK and manifest are published")
    p.add_argument("--source-url", default="", help="project page shown in Homebrew Channel")
    p.add_argument("--icon-url", default="", help="https URL of a 160x160 or larger PNG icon")
    p.add_argument("--out", default="dist/homebrew")
    a = p.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with open(os.path.join(root, "appinfo.json"), encoding="utf-8") as f:
        info = json.load(f)
    if info["id"] != APP_ID:
        sys.exit("unexpected app id %s" % info["id"])
    name = os.path.basename(a.ipk)
    if info["version"] not in name:
        sys.exit("IPK %s does not match appinfo.json version %s" % (name, info["version"]))
    with open(a.ipk, "rb") as f:
        data = f.read()
    base = a.download_base.rstrip("/")
    if not base.startswith("https://"):
        sys.exit("--download-base must be an https URL")

    manifest = {
        "id": APP_ID,
        "version": info["version"],
        "type": "web",
        "title": info["title"],
        "appDescription": "IPTV oynatıcı (M3U/M3U8, EPG) — LG webOS 1.x ve sonrası",
        "iconUri": a.icon_url,
        "sourceUrl": a.source_url,
        "rootRequired": False,
        "ipkUrl": base + "/" + name,
        "ipkSize": len(data),
        "ipkHash": {"sha256": hashlib.sha256(data).hexdigest()},
    }
    package = {
        "id": APP_ID,
        "title": info["title"],
        "iconUri": a.icon_url,
        "manifestUrl": base + "/" + APP_ID + ".manifest.json",
        "manifest": manifest,
        "category": "multimedia",
        "pool": "main",
        "shortDescription": "M3U/M3U8 IPTV oynatıcı; telefondan yönetim (NovaTV proxy ile)",
        "sourceUrl": a.source_url,
    }
    repo = {"paging": {"page": 1, "count": 1, "maxPage": 1, "itemsTotal": 1}, "packages": [package]}

    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, APP_ID + ".manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    with open(os.path.join(a.out, "apps.json"), "w", encoding="utf-8") as f:
        json.dump(repo, f, ensure_ascii=False, indent=1)
    print("wrote %s/apps.json and %s.manifest.json (sha256 %s)" % (a.out, APP_ID, manifest["ipkHash"]["sha256"][:16]))


if __name__ == "__main__":
    main()

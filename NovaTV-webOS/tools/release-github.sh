#!/usr/bin/env bash
# Publishes the LG webOS IPK as a GitHub release, together with the Homebrew Channel repository
# files, so Homebrew Channel can list, install and update NovaTV from:
#   https://github.com/OWNER/REPO/releases/latest/download/apps.json
#
# Usage: tools/release-github.sh OWNER/REPO [notes-file]
# Needs: the GitHub CLI (gh auth login), Node.js, Python 3. The version comes from appinfo.json.
# If the Android release of the same version already exists, the files are added to it.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="${1:?usage: tools/release-github.sh OWNER/REPO [notes-file]}"
NOTES="${2:-}"
VERSION=$(python3 -c 'import json; print(json.load(open("appinfo.json"))["version"])')
TAG="v$VERSION"

bash tools/build-ipk.sh
IPK="dist/com.novatv.webos_${VERSION}_all.ipk"
python3 tools/homebrew-repo.py "$IPK" \
  --download-base "https://github.com/$REPO/releases/download/$TAG" \
  --source-url "https://github.com/$REPO" \
  --icon-url "https://github.com/$REPO/releases/download/$TAG/novatv-icon.png" \
  --out dist/homebrew
cp largeIcon.png dist/homebrew/novatv-icon.png

FILES=("$IPK" dist/homebrew/apps.json dist/homebrew/com.novatv.webos.manifest.json dist/homebrew/novatv-icon.png)
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" -R "$REPO" --clobber "${FILES[@]}"
else
  if [ -n "$NOTES" ]; then
    gh release create "$TAG" -R "$REPO" --title "NovaTV $VERSION" --notes-file "$NOTES" "${FILES[@]}"
  else
    gh release create "$TAG" -R "$REPO" --title "NovaTV $VERSION" --generate-notes "${FILES[@]}"
  fi
fi
echo "Homebrew Channel repository: https://github.com/$REPO/releases/latest/download/apps.json"

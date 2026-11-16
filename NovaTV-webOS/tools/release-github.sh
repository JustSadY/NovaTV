#!/usr/bin/env bash
# Publishes the LG webOS IPK as a GitHub release, together with the Homebrew Channel repository
# files, so Homebrew Channel can list, install and update NovaTV from:
#   https://github.com/OWNER/REPO/releases/latest/download/apps.json
#
# Usage: tools/release-github.sh OWNER/REPO [notes-file] [--tag vX.Y.Z]
# Needs: the GitHub CLI (gh auth login), Node.js, Python 3. The IPK version comes from appinfo.json.
# The release is "v<that version>" unless --tag names another (e.g. a newer Android release):
# the Homebrew link always reads the LATEST release, so every release must carry these files.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="${1:?usage: tools/release-github.sh OWNER/REPO [notes-file] [--tag vX.Y.Z]}"
shift
NOTES=""
TAG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="${2:?--tag needs a value}"; shift 2 ;;
    *) NOTES="$1"; shift ;;
  esac
done
VERSION=$(python3 -c 'import json; print(json.load(open("appinfo.json"))["version"])')
TAG="${TAG:-v$VERSION}"

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

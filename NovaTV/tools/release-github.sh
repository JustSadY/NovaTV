#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub release. Android TVs running NovaTV
# with Settings → Güncellemeler → GitHub deposu = OWNER/REPO then offer it as an update.
#
# Usage: tools/release-github.sh OWNER/REPO [notes-file] [--allow-debug-key]
# Needs: JDK 17, Android SDK, the GitHub CLI (gh auth login). The version is versionName in
# app/build.gradle.kts — raise versionCode and versionName before every release.
#
# Every release must be signed with the SAME key, otherwise Android refuses to install it over
# the previous one. Create keystore.properties once (see README → Güncellemeler). With
# --allow-debug-key the APK is signed with this computer's ~/.android/debug.keystore instead;
# then keep a backup of that file — losing it means users must uninstall to update.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="${1:?usage: tools/release-github.sh OWNER/REPO [notes-file] [--allow-debug-key]}"
NOTES=""
ALLOW_DEBUG=0
for a in "${@:2}"; do
  case "$a" in
    --allow-debug-key) ALLOW_DEBUG=1 ;;
    *) NOTES="$a" ;;
  esac
done
if [ ! -f keystore.properties ] && [ "$ALLOW_DEBUG" != 1 ]; then
  echo "keystore.properties yok: sürümler kalıcı bir anahtarla imzalanmalı (README → Güncellemeler)." >&2
  echo "Bu bilgisayarın hata ayıklama anahtarını bilerek kullanmak için --allow-debug-key ekleyin." >&2
  exit 1
fi
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
TAG="v$VERSION"

./gradlew --console=plain testDebugUnitTest assembleRelease
mkdir -p dist
APK="dist/NovaTV-${VERSION}-release.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  gh release upload "$TAG" -R "$REPO" --clobber "$APK"
elif [ -n "$NOTES" ]; then
  gh release create "$TAG" -R "$REPO" --title "NovaTV $VERSION" --notes-file "$NOTES" "$APK"
else
  gh release create "$TAG" -R "$REPO" --title "NovaTV $VERSION" --generate-notes "$APK"
fi
echo "Published $APK as $TAG on $REPO"

#!/usr/bin/env bash
# Builds dist/com.novatv.webos_<version>_all.ipk with LG's official CLI (ares-package).
# Prerequisite once:  npm install   (installs @webos-tools/cli and acorn locally)
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$PWD/node_modules/.bin:$PATH"

node tools/check-es5.js
node tests/run-tests.js

# Stage only the files the TV needs (tests, tools, the PC proxy and docs stay out).
rm -rf build dist
mkdir -p build/app dist
cp appinfo.json index.html icon.png largeIcon.png splash.png build/app/
cp -R css js assets build/app/

ares-package build/app -o dist
ares-package -i dist/*.ipk
rm -rf build
echo "IPK ready: $(ls dist/*.ipk)"

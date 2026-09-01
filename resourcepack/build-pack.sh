#!/usr/bin/env sh
# Zips this folder into a server resource pack and prints the sha1 that
# server.properties needs. Run it from anywhere:
#
#   ./resourcepack/build-pack.sh
#
# The zip must contain pack.mcmeta at its ROOT, which is why we zip the
# folder's contents and not the folder itself.
set -e

DIR=$(cd "$(dirname "$0")" && pwd)
OUT="$DIR/../hardcoreworldreset-resources.zip"

rm -f "$OUT"
(cd "$DIR" && zip -r -q -X "$OUT" pack.mcmeta pack.png assets)

echo "Wrote $OUT"
echo "sha1: $(sha1sum "$OUT" | cut -d' ' -f1)"

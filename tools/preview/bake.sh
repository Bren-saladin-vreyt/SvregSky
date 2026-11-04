#!/usr/bin/env bash
# Bakes the noise atlases the mod builds at runtime, straight from the mod's own Java (no Gradle needed):
#   out/crumble.png  (CrumbleNoise: puff mode)   out/noise.png  (RaymarchNoise: bubble mode)
set -e
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../../src/main/java/com/svreg/sky/sky"
work="$(mktemp -d)"
mkdir -p "$here/out"
for c in CrumbleNoise RaymarchNoise; do
  sed 's/^package .*;//' "$src/$c.java" > "$work/$c.java"
done
javac -d "$work" "$work/CrumbleNoise.java" "$work/RaymarchNoise.java" 2>&1 | grep -v JAVA_TOOL || true
java -cp "$work" CrumbleNoise "$here/out/crumble.png" 2>&1 | grep -v JAVA_TOOL || true
java -cp "$work" RaymarchNoise "$here/out/noise.png" 2>&1 | grep -v JAVA_TOOL || true
rm -rf "$work"
ls -la "$here/out"

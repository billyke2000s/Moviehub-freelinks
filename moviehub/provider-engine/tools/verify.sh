#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/provider-tests
rhino="${RHINO_JAR:?Set RHINO_JAR to the pinned Rhino 1.9.1 JAR}"
node -e 'const fs=require("fs"); fs.writeFileSync("build/provider-tests/compiled.js", JSON.parse(fs.readFileSync("android-tv-launcher/app/src/main/assets/providers/builtin.json")).modules.freelivesports)'
javac -cp "$rhino" -d build/provider-tests android-tv-launcher/app/src/main/java/local/tv/home/{ProviderScript,ProviderCodec,ProviderSignature,ProviderUrls,ProviderSegments}.java android-tv-launcher/tests/ProviderEngineTest.java
java -Xmx96m -cp "$rhino:build/provider-tests" local.tv.home.ProviderEngineTest build/provider-tests/compiled.js
javac -cp "$rhino:build/provider-tests" -d build/provider-tests android-tv-launcher/tests/ProviderRuntimeCli.java
RHINO_JAR="$rhino" node provider-engine/tools/runtime-check.mjs

javac -d build/provider-tests android-tv-launcher/app/src/main/java/local/tv/home/{ProviderPngSegments,ProviderContainerSegments}.java android-tv-launcher/tests/{ProviderPngSegmentsTest,ProviderContainerSegmentsTest}.java
java -Xmx96m -cp build/provider-tests local.tv.home.ProviderPngSegmentsTest
java -Xmx96m -cp build/provider-tests local.tv.home.ProviderContainerSegmentsTest

json="${JSON_JAR:-build/provider-tests/json.jar}"
if [ ! -f "$json" ]; then
  curl --fail --location --output "$json" https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar
fi
javac -cp "$json" -d build/provider-tests android-tv-launcher/app/src/main/java/local/tv/home/{ProviderUrls,ProviderSegments,ProviderFileRanges,ProviderRelay,StreamRate,PlaylistSession,ProviderPngSegments,ProviderContainerSegments}.java android-tv-launcher/tests/{ProviderRelayProbe,ProviderRelayTransportTest}.java
java -Xmx96m -cp "$json:build/provider-tests" local.tv.home.ProviderRelayTransportTest

# Fast experimental lookups reuse compiled code, never the script's globals or Java capabilities.
javac -cp "$rhino:$json:build/provider-tests" -d build/provider-tests android-tv-launcher/tests/ProviderScriptFastTest.java
java -Xmx96m -cp "$rhino:$json:build/provider-tests" local.tv.home.ProviderScriptFastTest

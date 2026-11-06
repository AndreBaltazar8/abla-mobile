#!/usr/bin/env bash
# CI-only focused TLS isolation: no base UI or missing-entitlement rerun.
set -euo pipefail
mkdir -p "$RUNNER_TEMP/ios-ui-evidence"
xcrun simctl list devices available -j > "$RUNNER_TEMP/ios-ui-evidence/simulators.json"
device=$(jq -r '[.devices[][] | select(.isAvailable == true and (.name | startswith("iPhone")))][0].udid' \
  "$RUNNER_TEMP/ios-ui-evidence/simulators.json")
test -n "$device" && test "$device" != null
if test "$(jq -r --arg id "$device" '.devices[][] | select(.udid == $id) | .state' "$RUNNER_TEMP/ios-ui-evidence/simulators.json")" = Shutdown; then
  xcrun simctl boot "$device"
fi
xcrun simctl bootstatus "$device" -b
# All keys stay outside the evidence artifact. This CA is installed
# only in this disposable Simulator, after the unknown-issuer test.
umask 077
tls="$RUNNER_TEMP/ios-services-private"
mkdir -p "$tls"
cat > "$tls/ca.cnf" <<'EOF'
[req]
distinguished_name=dn
x509_extensions=ca
prompt=no
[dn]
CN=Abla CI Disposable CA
[ca]
basicConstraints=critical,CA:TRUE
keyUsage=critical,keyCertSign,cRLSign
EOF
cat > "$tls/leaf.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -sha256 \
  -config "$tls/ca.cnf" -keyout "$tls/ca.key" -out "$tls/ca.crt" > "$tls/generate.log" 2>&1
openssl req -newkey rsa:2048 -nodes -subj /CN=localhost \
  -keyout "$tls/server.key" -out "$tls/server.csr" >> "$tls/generate.log" 2>&1
openssl x509 -req -in "$tls/server.csr" -CA "$tls/ca.crt" -CAkey "$tls/ca.key" \
  -CAcreateserial -days 1 -sha256 -extfile "$tls/leaf.cnf" -out "$tls/server.crt" >> "$tls/generate.log" 2>&1
# Public certificates only. The app probes system trust without
# setting custom anchors or exceptions, before its one HTTP attempt.
node --input-type=module - "$tls/server.crt" "$tls/ca.crt" \
  runtime/ios-host/SmokeApp/HostSmoke/FixtureTrust.json <<'NODE'
import fs from 'node:fs';
import { X509Certificate } from 'node:crypto';
const [leaf, root, output] = process.argv.slice(2);
const der = path => new X509Certificate(fs.readFileSync(path)).raw.toString('base64');
fs.writeFileSync(output, JSON.stringify({ leaf: der(leaf), root: der(root) }));
NODE
node runtime/ios-host/Tests/https-standin.mjs "$tls/server.key" "$tls/server.crt" \
  "$RUNNER_TEMP/ios-ui-evidence/https-counts.json" > "$tls/server.log" 2>&1 &
peer=$!
trustedDevice=''
cleanup() {
  kill "$peer" 2>/dev/null || true
  if test -n "$trustedDevice"; then
    xcrun simctl shutdown "$trustedDevice" 2>/dev/null || true
    xcrun simctl delete "$trustedDevice" 2>/dev/null || true
  fi
  rm -rf "$tls"
}
trap cleanup EXIT
for attempt in $(seq 1 50); do
  grep -q 'TLS stand-in ready' "$tls/server.log" && break
  kill -0 "$peer"; sleep .1
done
grep -q 'TLS stand-in ready' "$tls/server.log"
cp "$RUNNER_TEMP/ios-ui-evidence/https-counts.json.transport.json" "$RUNNER_TEMP/ios-ui-evidence/transport-before-untrusted.json"
set +e
xcodebuild test -project runtime/ios-host/SmokeApp/HostSmoke.xcodeproj \
  -scheme HostSmoke -sdk iphonesimulator -destination "id=$device" \
  -parallel-testing-enabled NO -derivedDataPath "$RUNNER_TEMP/ios-smoke-build" \
  -resultBundlePath "$RUNNER_TEMP/ios-ui-evidence/Untrusted.xcresult" \
  -only-testing:HostSmokeUITests/HostSmokeUITests/testHttpsRefusesUnknownCertificateAuthority \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  2>&1 | tee "$RUNNER_TEMP/ios-ui-evidence/simulator-ui.log"
result=${PIPESTATUS[0]}
set -e
cp "$RUNNER_TEMP/ios-ui-evidence/https-counts.json.transport.json" "$RUNNER_TEMP/ios-ui-evidence/transport-after-untrusted.json"
codesign --display --verbose=2 \
  "$RUNNER_TEMP/ios-smoke-build/Build/Products/Debug-iphonesimulator/HostSmoke.app" \
  > "$RUNNER_TEMP/ios-ui-evidence/simulator-signature.txt" 2>&1
codesign --verify --strict \
  "$RUNNER_TEMP/ios-smoke-build/Build/Products/Debug-iphonesimulator/HostSmoke.app"
node runtime/ios-host/Tests/simulator-entitlements.test.mjs
node runtime/ios-host/Tests/simulator-entitlements.mjs \
  "$RUNNER_TEMP/ios-smoke-build/Build/Products/Debug-iphonesimulator/HostSmoke.app/HostSmoke" \
  "$RUNNER_TEMP/ios-ui-evidence/simulator-entitlements.plist"
test "$(plutil -extract application-identifier raw -o - "$RUNNER_TEMP/ios-ui-evidence/simulator-entitlements.plist")" = org.abla.host-smoke
if test "$result" -eq 0; then
  for mode in trusted; do
    if test "$mode" = untrusted; then
      testCase=testHttpsRefusesUnknownCertificateAuthority
    else
      node -e 'const c=require(process.argv[1]); if(Object.values(c).some(x=>x!==0)) process.exit(1)' \
        "$RUNNER_TEMP/ios-ui-evidence/https-counts.json"
      # A previous unchanged-client run timed out with zero HTTP
      # requests after the untrusted case. Isolate positive trust
      # provisioning from that Simulator's connection/trust state.
      type=$(jq -r --arg id "$device" '.devices[][]|select(.udid==$id)|.deviceTypeIdentifier' "$RUNNER_TEMP/ios-ui-evidence/simulators.json")
      runtime=$(jq -r --arg id "$device" '.devices|to_entries[]|select(any(.value[];.udid==$id))|.key' "$RUNNER_TEMP/ios-ui-evidence/simulators.json")
      trustedDevice=$(xcrun simctl create "Abla-trusted-$GITHUB_RUN_ID" "$type" "$runtime")
      xcrun simctl boot "$trustedDevice"
      xcrun simctl bootstatus "$trustedDevice" -b
      xcrun simctl keychain "$trustedDevice" add-root-cert "$tls/ca.crt"
      device=$trustedDevice
      kill -0 "$peer"
      curl --fail --silent --show-error --max-time 5 --cacert "$tls/ca.crt" \
        https://127.0.0.1:19443/health > "$RUNNER_TEMP/ios-ui-evidence/peer-health.txt"
      testCase=testHttpsUsesSystemTrustWithoutRedirectOrLostAnswerReplay
    fi
    cp "$RUNNER_TEMP/ios-ui-evidence/https-counts.json.transport.json" \
      "$RUNNER_TEMP/ios-ui-evidence/transport-before-$mode.json"
    set +e
    xcodebuild test -project runtime/ios-host/SmokeApp/HostSmoke.xcodeproj \
      -scheme HostSmoke -sdk iphonesimulator -destination "id=$device" \
      -parallel-testing-enabled NO -derivedDataPath "$RUNNER_TEMP/ios-smoke-build" \
      -only-testing:"HostSmokeUITests/HostSmokeUITests/$testCase" \
      -resultBundlePath "$RUNNER_TEMP/ios-ui-evidence/HTTPS-$mode.xcresult" \
      CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
      2>&1 | tee "$RUNNER_TEMP/ios-ui-evidence/https-$mode.log"
    result=${PIPESTATUS[0]}
    set -e
    cp "$RUNNER_TEMP/ios-ui-evidence/https-counts.json.transport.json" \
      "$RUNNER_TEMP/ios-ui-evidence/transport-after-$mode.json"
    test "$result" -eq 0 || break
  done
  if test "$result" -eq 0; then
    node -e 'const c=require(process.argv[1]); if(c.ok!==1||c.redirect!==1||c.lost!==1||c.forbidden!==0) process.exit(1); console.log("HTTPS counts: one accepted, one redirect, one lost answer, zero redirected requests/replays")' \
      "$RUNNER_TEMP/ios-ui-evidence/https-counts.json"
  fi
fi
if test "$result" -ne 0; then
  xcrun simctl list devices available -j > "$RUNNER_TEMP/ios-ui-evidence/devices-after-failure.json"
  xcrun simctl spawn "$device" log show --last 3m --style compact \
    --predicate 'process == "HostSmoke"' \
    > "$RUNNER_TEMP/ios-ui-evidence/app-after-failure.log" 2>&1 || true
  mkdir -p "$RUNNER_TEMP/ios-ui-evidence/crashes"
  find "$HOME/Library/Logs/DiagnosticReports" -maxdepth 1 -type f -name 'HostSmoke*' \
    -exec cp {} "$RUNNER_TEMP/ios-ui-evidence/crashes/" \; || true
fi
exit "$result"

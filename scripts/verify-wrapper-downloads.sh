#!/bin/sh
# Run in the pinned build image with network=none and an empty temporary Maven cache.
set -eu

wrapper=${1:-/workspace/mvnw}
command -v curl >/dev/null
if command -v wget >/dev/null; then
  echo 'This check requires the curl download branch (no wget).' >&2
  exit 1
fi

taskRoot=$(mktemp -d)
export CURL_HOME="$taskRoot/curl-config"
mkdir -p "$CURL_HOME"
checks=0

reject_download() {
  scheme=$1
  checks=$((checks + 1))
  if MVNW_VERBOSE=true MVNW_USERNAME= MVNW_PASSWORD= \
      MVNW_REPOURL="$scheme://127.0.0.1:9" MAVEN_USER_HOME="$taskRoot/cache-$checks" \
      sh "$wrapper" -v >"$taskRoot/result-$checks.log" 2>&1; then
    echo "FAIL: $scheme download was accepted" >&2
    exit 1
  fi
  if ! grep -Eq 'Protocol .*disabled' "$taskRoot/result-$checks.log"; then
    cat "$taskRoot/result-$checks.log" >&2
    echo "FAIL: $scheme must be rejected by curl protocol policy before connecting" >&2
    exit 1
  fi
  if grep -qi 'unknown option' "$taskRoot/result-$checks.log"; then
    echo 'FAIL: curl read its automatic configuration' >&2
    exit 1
  fi
}

for scheme in http ftp rtmp rtmpe rtmps rtmpt rtmpte rtmpts; do
  reject_download "$scheme"
done
# A user curlrc must not override the pinned download policy or enable compression.
printf '%s\n' 'this-option-does-not-exist = true' >"$CURL_HOME/.curlrc"
reject_download rtmp
echo "Wrapper download policy PASS: $checks real cold-cache curl rejections"

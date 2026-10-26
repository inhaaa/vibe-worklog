#!/bin/sh
# The build uses the existing JDK downloader; RTMP/HTTP decompression clients are unnecessary.
set -eu
for program in curl wget; do
  if command -v "$program" >/dev/null; then
    echo "FAIL: unnecessary downloader is installed: $program" >&2
    exit 1
  fi
done
packages=$(dpkg-query -W -f='${Package} ${db:Status-Status}\n')
for package in curl libcurl4t64 librtmp1; do
  if printf '%s\n' "$packages" | grep -Fxq "$package installed"; then
    echo "FAIL: unnecessary native input dependency is installed: $package" >&2
    exit 1
  fi
done
java -version
unzip -v >/dev/null
echo 'Build download surface PASS: JDK/unzip available; curl/wget/libcurl/librtmp absent'

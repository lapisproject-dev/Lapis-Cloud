#!/usr/bin/env bash
# V1.9.93: content check of the release image before it is pushed. Fails (exit 1) when the image contains
# files that never belong in it (secrets, env files, keys, private geodata, dumps, .git, deploy/, source maps in the
# client bundle) or when the expected application files are missing (guards against a vacuous pass on a broken image).
# Exit 2 = the check itself could not run (Docker error); never treat that as green.
#
# Usage: scripts/ci-assert-image-clean.sh <image-reference>
set -euo pipefail

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
  echo "usage: $0 <image>" >&2
  exit 2
fi
image="$1"

if ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "::error::image '$image' is not available locally" >&2
  exit 2
fi

user="$(docker image inspect --format '{{.Config.User}}' "$image")" || exit 2
if [ "$user" != "lapiscloud" ]; then
  echo "FAIL: image user is '$user', expected 'lapiscloud'" >&2
  exit 1
fi

# shellcheck disable=SC2016  # single quotes on purpose: the script is expanded inside the container
# The script runs inside the image AS ROOT (--user 0:0 below): the image's own user (lapiscloud) cannot read root-only
# directories (/root is 0700, /var/cache/ldconfig too), which would leave them unscanned. /proc, /sys, system certificate
# stores and the JDK's own tree are skipped.
inner='
set -u
for f in /app/server/bin/lapis-server /app/client/index.html; do
  [ -e "$f" ] || { echo "MISSING $f"; exit 3; }
done
# find exits non-zero on any unreadable or vanished entry; that must neither abort the script (set -e) nor hide
# findings. Its exit status is judged on purpose: 0 and 1 are tolerated (errors on single entries), anything above is a
# real failure of the scan itself. Findings are taken from stdout.
scan_out="$(find / -xdev \
  \( -path /proc -o -path /sys -o -path /etc/ssl -o -path /usr/lib/ssl -o -path /usr/share/ca-certificates -o -path /opt/java \) -prune -o \
  \( -name ".env" -o -name ".env.*" -o -name "*.env" -o -name "*.pem" -o -name "*.key" -o -name "*.p12" \
     -o -name "*.pfx" -o -name "*.jks" -o -name "*.keystore" -o -name "*.pmtiles" -o -name "*.dump" \
     -o -name "*.dump.gz" -o -name "*.sql.gz" -o -name "*.hprof" -o -name ".git" -o -name "deploy" \) \
  -print 2>/dev/null)" || find_rc=$?
find_rc="${find_rc:-0}"
if [ "$find_rc" -gt 1 ]; then echo "SCANFAIL find exited $find_rc"; exit 4; fi
map_out="$(find /app/client -name "*.map" -print 2>/dev/null)" || map_rc=$?
map_rc="${map_rc:-0}"
if [ "$map_rc" -gt 1 ]; then echo "SCANFAIL find (map) exited $map_rc"; exit 4; fi
[ -n "$scan_out" ] && echo "$scan_out"
[ -n "$map_out" ] && echo "$map_out"
exit 0
'

set +e
out="$(docker run --rm --user 0:0 --entrypoint sh "$image" -c "$inner" 2>&1)"
rc=$?
set -e

if [ "$rc" -eq 3 ]; then
  echo "FAIL: expected application files are missing:" >&2
  echo "$out" >&2
  exit 1
fi
if [ "$rc" -ne 0 ]; then
  echo "::error::docker run failed (exit $rc):" >&2
  echo "$out" >&2
  exit 2
fi
if [ -n "$out" ]; then
  echo "FAIL: forbidden files found in image:" >&2
  echo "$out" >&2
  exit 1
fi
echo "OK: image '$image' is clean (user $user, application files present)"

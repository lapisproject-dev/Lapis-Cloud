#!/usr/bin/env bash
# V1.9.93: logic test for scripts/ci-assert-image-clean.sh without a Docker daemon. A stub `docker` on PATH answers
# `image inspect` and runs the in-container script against a fixture directory instead of an image (the script's `/app/`
# and `find /` are redirected to the fixture). Covers: clean image, planted .env / .pem / source map, missing application
# files, wrong user, unreadable directory (the root-only case that broke the first version), Docker failure (exit 2),
# and that the real run uses --user 0:0. A real-image run happens in the `image` job of ci.yml on pull requests.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/ci-assert-image-clean.sh"
work="$(mktemp -d)"
trap 'chmod -R u+rwx "$work" 2>/dev/null; rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "image inspect")
    if [ "${STUB_NO_IMAGE:-}" = 1 ]; then exit 1; fi
    echo "${STUB_USER:-lapiscloud}"; exit 0 ;;
esac
if [ "$1" = run ]; then
  echo "$*" >> "$STUB_LOG"
  [ "${STUB_RUN_FAIL:-}" = 1 ] && { echo "docker: boom" >&2; exit 125; }
  inner="${*: -1}"
  inner="${inner//\/app\//$STUB_FIXTURE/app/}"
  inner="${inner//find \/ -xdev/find $STUB_FIXTURE -xdev}"
  exec sh -c "$inner"
fi
exit 1
STUB
chmod +x "$work/bin/docker"

fails=0
new_fixture() {
  fx="$work/fx$RANDOM"
  mkdir -p "$fx/app/server/bin" "$fx/app/client" "$fx/etc"
  : > "$fx/app/server/bin/lapis-server"; : > "$fx/app/client/index.html"
}
run_case() { # name expected-rc [env...]
  local name="$1" want="$2"; shift 2
  : > "$work/log"
  env STUB_FIXTURE="$fx" STUB_LOG="$work/log" PATH="$work/bin:$PATH" "$@" "$script" img:test >"$work/out" 2>&1
  local rc=$?
  if [ "$rc" -ne "$want" ]; then echo "FAIL $name: rc=$rc want=$want"; cat "$work/out"; fails=$((fails+1)); else echo "ok   $name"; fi
}

new_fixture; run_case "clean image" 0
grep -q -- '--user 0:0' "$work/log" || { echo "FAIL: docker run lacks --user 0:0"; fails=$((fails+1)); }

new_fixture; : > "$fx/app/.env"; run_case "planted .env" 1
new_fixture; : > "$fx/etc/server.pem"; run_case "planted .pem" 1
new_fixture; : > "$fx/app/client/main.bundle.js.map"; run_case "source map" 1
new_fixture; rm "$fx/app/client/index.html"; run_case "missing application file" 1
new_fixture; run_case "wrong user" 1 STUB_USER=root
new_fixture; run_case "docker run failure" 2 STUB_RUN_FAIL=1
new_fixture; run_case "image missing" 2 STUB_NO_IMAGE=1

# The regression: a directory the scanning user cannot read makes find exit 1; that must not abort the scan, and a
# finding elsewhere must still be reported.
new_fixture; mkdir "$fx/rootonly"; : > "$fx/rootonly/x"; chmod 000 "$fx/rootonly"
run_case "unreadable directory tolerated" 0
: > "$fx/app/.env"; run_case "finding reported next to unreadable directory" 1
chmod 755 "$fx/rootonly"

[ "$fails" -eq 0 ] && echo "all cases passed" || { echo "$fails case(s) failed"; exit 1; }

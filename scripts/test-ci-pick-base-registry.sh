#!/usr/bin/env bash
# V1.9.160: logic test for scripts/ci-pick-base-registry.sh without a Docker daemon. A stub `docker` answers `buildx imagetools inspect`
# for the registries listed in STUB_OK (a space separated list of registry prefixes); everything else fails like a rate-limited pull.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/ci-pick-base-registry.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat > "$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
[ "$1 $2 $3" = "buildx imagetools inspect" ] || exit 1
ref="$4"
echo "$ref" >> "$STUB_LOG"
for ok in $STUB_OK; do
  case "$ref" in "$ok"/*)
    # STUB_BAD="<registry>|<image part>": that one image is missing on that one registry
    if [ -n "${STUB_BAD:-}" ] && [ "$ok" = "${STUB_BAD%%|*}" ]; then case "$ref" in *"${STUB_BAD#*|}"*) exit 1 ;; esac; fi
    exit 0 ;; esac
done
exit 1
STUB
chmod +x "$work/bin/docker"

cat > "$work/Dockerfile" <<'DF'
ARG BASE_REGISTRY=public.ecr.aws/docker/library
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jdk@sha256:aaaa AS build
RUN true
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jre@sha256:bbbb AS runtime
DF

fails=0
check() { # name want-rc want-out [env...]
  local name="$1" want_rc="$2" want_out="$3"; shift 3
  : > "$work/log"
  local out rc
  out="$(env STUB_LOG="$work/log" PATH="$work/bin:$PATH" "$@" "$script" "$work/Dockerfile" 2>"$work/err")"; rc=$?
  if [ "$rc" -ne "$want_rc" ] || [ "$out" != "$want_out" ]; then
    echo "FAIL $name: rc=$rc out='$out' (want rc=$want_rc out='$want_out')"; cat "$work/err"; fails=$((fails+1))
  else
    echo "ok   $name"
  fi
}

check "primary works" 0 "public.ecr.aws/docker/library" STUB_OK="public.ecr.aws/docker/library mirror.gcr.io/library"
grep -q 'sha256:aaaa' "$work/log" && grep -q 'sha256:bbbb' "$work/log" || { echo "FAIL: not every image is inspected"; fails=$((fails+1)); }
check "primary rate-limited, mirror used" 0 "mirror.gcr.io/library" STUB_OK="mirror.gcr.io/library docker.io/library"
check "only Docker Hub left" 0 "docker.io/library" STUB_OK="docker.io/library"
check "none works" 1 "" STUB_OK=""
check "one image missing on the primary -> next registry" 0 "mirror.gcr.io/library" STUB_OK="public.ecr.aws/docker/library mirror.gcr.io/library" STUB_BAD="public.ecr.aws/docker/library|25-jre@sha256:bbbb"
check "custom list respected" 1 "" STUB_OK="docker.io/library" BASE_REGISTRIES="mirror.gcr.io/library"
printf 'ARG X=1\nFROM scratch\n' > "$work/Plain"
: > "$work/log"
if PATH="$work/bin:$PATH" STUB_LOG="$work/log" STUB_OK="x" "$script" "$work/Plain" >/dev/null 2>&1; then echo "FAIL: Dockerfile without BASE_REGISTRY accepted"; fails=$((fails+1)); else echo "ok   Dockerfile without BASE_REGISTRY refused"; fi

[ "$fails" -eq 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }

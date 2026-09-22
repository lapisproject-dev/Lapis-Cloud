#!/usr/bin/env bash
# Renders livekit.yaml.template / turnserver.conf.template / egress.yaml.template into the real,
# gitignored livekit.yaml / turnserver.conf / egress.yaml using the secrets in this directory's
# .env file.
# Run from deploy/production/ (or pass no args, it cd's to its own location).
#
# Requires `envsubst` (gettext-base package on Debian: apt-get install gettext-base).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [ ! -f .env ]; then
  echo "error: .env not found in $(pwd) -- copy .env.example and fill in real values first" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

for var in LAPIS_PUBLIC_IP LAPIS_LIVEKIT_API_KEY LAPIS_LIVEKIT_API_SECRET LAPIS_TURN_SECRET LAPIS_TURN_REALM; do
  if [ -z "${!var:-}" ]; then
    echo "error: $var is not set in .env" >&2
    exit 1
  fi
done

# LAPIS_TURN_REALM used to be a prose redaction placeholder (`realm=PROD_HOST`) hand-edited
# directly into the TRACKED turnserver.conf.template -- an operator who committed their edits
# after a deploy would push the real realm hostname straight into this public repo. It is now a
# regular envsubst variable (round 6 of "Redact real infra topology from public repo"), sourced
# from .env like everything else here, so the real value never touches a tracked file. Guard
# against someone copying an old .env.example line verbatim (an unedited `PROD_HOST`/`ELB_HOST`/
# `STAGING_HOST` literal is not a real realm) BEFORE any output file is written -- checking the
# *rendered* turnserver.conf only after livekit.yaml had already been written left a half-rendered
# deployment behind on abort in the pre-round-6 version of this script.
case "$LAPIS_TURN_REALM" in
  PROD_HOST | ELB_HOST | STAGING_HOST)
    echo "error: LAPIS_TURN_REALM in .env is still the placeholder '$LAPIS_TURN_REALM' --" >&2
    echo "  set it to this deployment's real realm (its domain) first" >&2
    echo "  (see the NOTE block near the top of README.adoc in this directory)" >&2
    exit 1
    ;;
esac

envsubst '${LAPIS_PUBLIC_IP} ${LAPIS_LIVEKIT_API_KEY} ${LAPIS_LIVEKIT_API_SECRET}' \
  < livekit.yaml.template > livekit.yaml
envsubst '${LAPIS_PUBLIC_IP} ${LAPIS_TURN_SECRET} ${LAPIS_TURN_REALM}' \
  < turnserver.conf.template > turnserver.conf
envsubst '${LAPIS_LIVEKIT_API_KEY} ${LAPIS_LIVEKIT_API_SECRET}' \
  < egress.yaml.template > egress.yaml

echo "Rendered livekit.yaml, turnserver.conf, and egress.yaml."

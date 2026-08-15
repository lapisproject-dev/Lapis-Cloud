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

for var in LAPIS_PUBLIC_IP LAPIS_LIVEKIT_API_KEY LAPIS_LIVEKIT_API_SECRET LAPIS_TURN_SECRET; do
  if [ -z "${!var:-}" ]; then
    echo "error: $var is not set in .env" >&2
    exit 1
  fi
done

envsubst '${LAPIS_PUBLIC_IP} ${LAPIS_LIVEKIT_API_KEY} ${LAPIS_LIVEKIT_API_SECRET}' \
  < livekit.yaml.template > livekit.yaml
envsubst '${LAPIS_PUBLIC_IP} ${LAPIS_TURN_SECRET}' \
  < turnserver.conf.template > turnserver.conf
envsubst '${LAPIS_LIVEKIT_API_KEY} ${LAPIS_LIVEKIT_API_SECRET}' \
  < egress.yaml.template > egress.yaml

echo "Rendered livekit.yaml, turnserver.conf, and egress.yaml."

#!/bin/sh
# Lapis Cloud -- operator backup of ONE instance: database, uploaded files and branding.
#
# Run from this directory (it cd's to its own location). Needs a running Postgres service of the compose project here; the
# lapis-server container does not have to run (the files are read through a one-off container that mounts the same volume).
#
#   ./backup.sh [--out DIR] [--stop-server] [--include-raw-recordings] [--include-geodata]
#
# Result: DIR/lapis-backup-<UTC timestamp>/ with
#   db.dump               pg_dump custom format (restore with restore.sh / pg_restore)
#   document-storage.tar  the lapis-document-storage volume: documents, recordings, chapter crests, event/article covers,
#                         member photos, travel-expense receipts, conference backgrounds, SEPA and dunning archives
#   branding.tar          ./branding (the bind mount behind LAPIS_BRAND_LOGO_PATH)
#   MANIFEST.txt          time, newest Flyway migration, file counts and sizes
#   SHA256SUMS            checksums of the files above (verified by restore.sh)
#
# NOT included, on purpose (back these up separately and encrypted):
#   .env, livekit.yaml, turnserver.conf, egress.yaml, coturn-certs/  -- secrets. Above all LAPIS_SECRET_ENCRYPTION_KEY: without
#                                                                       it the stored lexoffice/sevDesk tokens and SEPA IBANs
#                                                                       cannot be decrypted after a restore.
#   lapis-egress-output   -- volatile raw track recordings (only with --include-raw-recordings)
#   ./geodata             -- reproducible map data (only with --include-geodata)
#
# Order matters: the database is dumped FIRST, the files afterwards. A file that arrives between the two steps is a harmless
# orphan; a database row whose file is missing would be a loss. For a strictly consistent copy use --stop-server (lapis-server is
# stopped for the duration and started again afterwards, also on failure).
#
# No secret is passed on a command line or through the host environment: the database credentials are read INSIDE the postgres
# container from its own environment. Output files and the target directory are private to the current user (umask 077).
set -eu
umask 077
cd "$(dirname "$0")"

OUT="${HOME}/lapis-backups"
STOP_SERVER=0
INCLUDE_RAW=0
INCLUDE_GEODATA=0

while [ $# -gt 0 ]; do
  case "$1" in
    --out)
      [ $# -ge 2 ] || { echo "error: --out needs a directory" >&2; exit 2; }
      OUT="$2"
      shift 2
      ;;
    --stop-server) STOP_SERVER=1; shift ;;
    --include-raw-recordings) INCLUDE_RAW=1; shift ;;
    --include-geodata) INCLUDE_GEODATA=1; shift ;;
    -h | --help)
      sed -n '2,29p' "$0"
      exit 0
      ;;
    *)
      echo "error: unknown option: $1" >&2
      exit 2
      ;;
  esac
done

# docker compose reads .env from this directory; fail early with a clear message instead of an interpolation error.
[ -f .env ] || { echo "error: .env not found in $(pwd)" >&2; exit 1; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="${OUT}/lapis-backup-${STAMP}"
mkdir -p "$OUT"
chmod 700 "$OUT"
mkdir "$DEST"
chmod 700 "$DEST"

SERVER_STOPPED=0
cleanup() {
  if [ "$SERVER_STOPPED" = 1 ]; then
    echo "starting lapis-server again ..."
    docker compose start lapis-server || echo "WARNING: lapis-server did not start -- start it by hand" >&2
  fi
}
trap cleanup EXIT INT TERM

if [ "$STOP_SERVER" = 1 ]; then
  echo "stopping lapis-server for a consistent copy ..."
  docker compose stop lapis-server
  SERVER_STOPPED=1
fi

# Reads a directory out of the lapis-server image's volume mounts through a throw-away container. No volume name is guessed (the
# compose project name differs between hosts); compose resolves it.
tar_from_service() {
  docker compose run --rm --no-deps -T --entrypoint tar lapis-server -C "$1" -cf - .
}

echo "1/4 database dump ..."
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$DEST/db.dump"
[ -s "$DEST/db.dump" ] || { echo "error: db.dump is empty" >&2; exit 1; }
docker compose exec -T postgres pg_restore --list < "$DEST/db.dump" > /dev/null \
  || { echo "error: db.dump is not a readable pg_dump archive" >&2; exit 1; }

echo "2/4 uploaded files (document storage volume) ..."
tar_from_service /app/document-storage > "$DEST/document-storage.tar"
tar -tf "$DEST/document-storage.tar" > /dev/null || { echo "error: document-storage.tar is not a readable tar archive" >&2; exit 1; }

echo "3/4 branding ..."
if [ -d branding ]; then
  tar -C branding -cf "$DEST/branding.tar" .
else
  echo "  (no ./branding directory, skipped)"
fi

echo "4/4 optional parts ..."
if [ "$INCLUDE_RAW" = 1 ]; then
  tar_from_service /app/egress-out > "$DEST/egress-output.tar"
fi
if [ "$INCLUDE_GEODATA" = 1 ] && [ -d geodata ]; then
  tar -C geodata -cf "$DEST/geodata.tar" .
fi

{
  echo "lapis-cloud backup"
  echo "created_utc: ${STAMP}"
  echo "consistent_stop_server: ${STOP_SERVER}"
  echo "flyway_latest_version: $(docker compose exec -T postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"' \
    | tr -d '\r')"
  echo "files per top-level folder in document-storage.tar:"
  tar -tf "$DEST/document-storage.tar" | grep -v '/$' | cut -d/ -f2 | sort | uniq -c | sed 's/^/  /'
  echo "sizes (bytes):"
  for f in "$DEST"/*; do
    case "$f" in */MANIFEST.txt | */SHA256SUMS) continue ;; esac
    echo "  $(wc -c < "$f" | tr -d ' ') $(basename "$f")"
  done
} > "$DEST/MANIFEST.txt"

cd "$DEST"
if command -v sha256sum > /dev/null 2>&1; then
  sha256sum -- *.dump *.tar MANIFEST.txt > SHA256SUMS
else
  shasum -a 256 -- *.dump *.tar MANIFEST.txt > SHA256SUMS
fi

echo "done: $DEST"
echo "reminder: .env and LAPIS_SECRET_ENCRYPTION_KEY are NOT part of this backup -- keep them separately and encrypted."

#!/bin/sh
# Lapis Cloud -- restores a backup made by backup.sh into THIS instance's compose project.
#
#   ./restore.sh BACKUP_DIR
#
# This script never deletes anything. It refuses to run unless the target database is empty (no table in schema public); to
# restore over an existing database, recreate that database yourself first and make sure you have a fresh backup of it:
#   docker compose stop lapis-server
#   docker compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
#
# Steps: verify checksums, stop lapis-server, check the database is empty, pg_restore (single transaction), unpack the
# document storage and branding, start lapis-server. Flyway validates the restored schema history on start; a backup taken by an
# OLDER release restores fine and is migrated forward on start, a backup from a NEWER release than the running image is refused
# by Flyway (deploy the matching release first).
#
# The .env of the instance, above all LAPIS_SECRET_ENCRYPTION_KEY, must already be in place and be the one the backup was made
# with -- it is not part of the backup.
#
# After restoring an OLDER backup: lexoffice vouchers exported after that backup are not known locally any more. The export checks
# lexoffice for the voucher number before creating (see docs/architecture/accounting-export-idempotency.adoc), so they are adopted,
# not duplicated. sevDesk has no such lookup -- do not re-export a period for sevDesk that was already exported after the backup.
set -eu
umask 077

[ $# -eq 1 ] || { echo "usage: $0 BACKUP_DIR" >&2; exit 2; }
# Resolve the backup path BEFORE changing into this script's directory.
case "$1" in
  /*) BACKUP="$1" ;;
  *) BACKUP="$(pwd)/$1" ;;
esac
cd "$(dirname "$0")"
[ -d "$BACKUP" ] || { echo "error: not a directory: $BACKUP" >&2; exit 1; }
[ -f "$BACKUP/db.dump" ] || { echo "error: $BACKUP/db.dump missing" >&2; exit 1; }
[ -f .env ] || { echo "error: .env not found in $(pwd) -- put the instance's .env in place first" >&2; exit 1; }

echo "1/6 verifying checksums ..."
(
  cd "$BACKUP"
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum -c SHA256SUMS
  else
    shasum -a 256 -c SHA256SUMS
  fi
)

echo "2/6 stopping lapis-server ..."
docker compose stop lapis-server

echo "3/6 checking that the database is empty ..."
TABLES="$(docker compose exec -T postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = '"'"'public'"'"'"' \
  | tr -d '\r ')"
if [ "$TABLES" != "0" ]; then
  echo "error: the target database is not empty ($TABLES tables). Nothing was changed." >&2
  echo "       Recreate the database first (see the header of this script) and run again." >&2
  exit 1
fi

echo "4/6 restoring the database ..."
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --single-transaction --no-owner --exit-on-error' \
  < "$BACKUP/db.dump"

echo "5/6 restoring files ..."
if [ -f "$BACKUP/document-storage.tar" ]; then
  docker compose run --rm --no-deps -T --entrypoint tar lapis-server -C /app/document-storage -xf - < "$BACKUP/document-storage.tar"
fi
if [ -f "$BACKUP/branding.tar" ]; then
  mkdir -p branding
  tar -C branding -xf "$BACKUP/branding.tar"
fi
if [ -f "$BACKUP/egress-output.tar" ]; then
  docker compose run --rm --no-deps -T --entrypoint tar lapis-server -C /app/egress-out -xf - < "$BACKUP/egress-output.tar"
fi
if [ -f "$BACKUP/geodata.tar" ]; then
  mkdir -p geodata
  tar -C geodata -xf "$BACKUP/geodata.tar"
fi

echo "6/6 starting lapis-server ..."
docker compose up -d lapis-server
echo "done. Watch 'docker compose logs -f lapis-server': Flyway validates the restored schema history on start."

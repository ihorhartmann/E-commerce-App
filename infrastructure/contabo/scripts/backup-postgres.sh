#!/usr/bin/env bash
#
# Dumps the whole database - every service's schema, Keycloak's and Grafana's views - into
# /var/backups/ecommerce/postgres, one file per run, and then deletes the dumps older than 14 days. Run by cron every
# night (install.sh puts /etc/cron.d/ecommerce-backup in place); by hand:
#
#   infrastructure/contabo/scripts/backup-postgres.sh
#
# * pg_dump runs inside the postgres container, over its local socket: no password anywhere.
# * The custom format (-Fc) is compressed, and pg_restore takes the whole database or a single schema or table out of
#   it - infrastructure/contabo/README.md says how.
# * A dump is written to a temporary file and renamed only once it is complete and pg_restore can read it, so a dump
#   cut short never looks like a backup; the old dumps are deleted only after a new one has succeeded, so days of
#   failing backups never leave none.
# * Everything goes to the system journal as well: journalctl -t ecommerce-backup

set -euo pipefail

readonly backup_dir=/var/backups/ecommerce/postgres
readonly keep_days=14
repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
readonly repository

log() {
  echo "$*"
  logger -t ecommerce-backup -- "$*"
}

trap 'log "postgres backup failed (line ${LINENO})"' ERR

cd "${repository}"
umask 077
mkdir -p "${backup_dir}"
chmod 700 "${backup_dir}"

file="${backup_dir}/ecommerce-$(date -u +%Y-%m-%d_%H%M).dump"
temporary="${file}.partial"
trap 'rm -f "${temporary}"' EXIT

# $POSTGRES_USER and $POSTGRES_DB are expanded inside the container, from its own environment.
# < /dev/null: otherwise `exec -T` swallows this script's stdin.
# shellcheck disable=SC2016
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' < /dev/null > "${temporary}"

# a dump pg_restore can't list is no backup
docker compose exec -T postgres pg_restore --list < "${temporary}" > /dev/null
[[ -s "${temporary}" ]]
mv "${temporary}" "${file}"
log "postgres backup written: ${file} ($(du -h "${file}" | cut -f1))"

deleted="$(find "${backup_dir}" -maxdepth 1 -name 'ecommerce-*.dump' -mtime +$((keep_days - 1)) -print -delete | wc -l)"
log "postgres backups older than ${keep_days} days deleted: ${deleted}"

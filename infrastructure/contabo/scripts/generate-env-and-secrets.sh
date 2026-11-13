#!/usr/bin/env bash
#
# Writes the server's configuration next to docker-compose.yaml: .env, from example.env and prod.example.env, with no
# secret in it, and secrets/, one file per secret (docker-compose.prod.yaml mounts them as Docker secrets). Run it on
# the server, in the repository's folder:
#
#   infrastructure/contabo/scripts/generate-env-and-secrets.sh
#
# * The passwords and client secrets are generated (random, 64 hex characters - nothing a URL or a shell needs to
#   escape); the ones only a person has (Stripe's keys, the alert emails' account) are listed at the end, to be
#   written into their files by hand. The other values only a person knows (the domain, the image tag) are in .env.
# * A secret is generated only while its file is missing or empty: running the script again never changes a password
#   the stack already uses - Postgres keeps the one it was initialized with, so a new one would lock the services out.
#   A variable added to a template later is appended to .env with its template value.
# * Migration: on a server set up when the secrets were still in .env, each secret found in .env is moved into its
#   file in secrets/ and deleted from .env - so the stack keeps the passwords it already uses. A secret whose file is
#   already filled keeps the file's value.
# * Nothing secret is printed: only the names.

set -euo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
readonly env_file="${repository}/.env"
readonly secrets_dir="${repository}/secrets"

# each one in secrets/<its name in lower case>
readonly generated=(
  POSTGRES_PASSWORD
  GRAFANA_DB_PASSWORD
  GRAFANA_ADMIN_PASSWORD
  KEYCLOAK_ADMIN_PASSWORD
  APP_ADMIN_PASSWORD
  GATEWAY_CLIENT_SECRET
  ORDER_SERVICE_CLIENT_SECRET
  GRAFANA_CLIENT_SECRET
  INTERNAL_ADMIN_PASSWORD
)
readonly secrets_by_hand=(
  STRIPE_SECRET_KEY
  STRIPE_WEBHOOK_SECRET
  ALERT_SMTP_PASSWORD
)
readonly env_by_hand=(
  DOMAIN
  IMAGE_TAG
  STRIPE_PUBLISHABLE_KEY
  ALERT_SMTP_USERNAME
  ALERT_EMAIL_FROM
  ALERT_EMAIL_TO
)
# what used to be in .env and is gone: the test users' passwords, before the realm kept them out of production
readonly obsolete=(
  E2E_CLIENT1_PASSWORD
  E2E_CLIENT2_PASSWORD
  E2E_COURIER1_PASSWORD
  E2E_COURIER2_PASSWORD
  E2E_ADMIN_PASSWORD
)
readonly all_secrets=("${generated[@]}" "${secrets_by_hand[@]}")

template="$(cat "${repository}/example.env" "${repository}/infrastructure/contabo/prod.example.env")"
readonly template

# .env, secrets/ and every temporary copy are root's only; the secret files themselves are made readable below
umask 077

value_in() {
  sed -n "s/^$1=//p" | head -n 1
}

is_secret() {
  local name
  for name in "${all_secrets[@]}" "${obsolete[@]}"; do
    [[ "$1" == "${name}" ]] && return 0
  done
  return 1
}

remove_from_env() {
  local temporary
  temporary="$(mktemp "${env_file}.XXXXXX")"
  grep -v "^$1=" "${env_file}" > "${temporary}" || true
  mv "${temporary}" "${env_file}"
}

secret_file() {
  echo "${secrets_dir}/${1,,}"
}

# writes the secret without a trailing line break, readable by the containers' users: secrets/ itself (700) keeps
# everyone but root out on the host
write_secret() {
  printf '%s' "$2" > "$(secret_file "$1")"
  chmod 644 "$(secret_file "$1")"
}

[[ -f "${env_file}" ]] || { : > "${env_file}"; echo "created ${env_file}"; }
chmod 600 "${env_file}"
mkdir -p "${secrets_dir}"
chmod 700 "${secrets_dir}"

# a secret still in .env goes to its file - unless that one is filled already, or it is only the template's placeholder
for name in "${all_secrets[@]}"; do
  current="$(value_in "${name}" < "${env_file}")"
  if [[ -n "${current}" && "${current}" != "$(value_in "${name}" <<< "${template}")" \
        && ! -s "$(secret_file "${name}")" ]]; then
    write_secret "${name}" "${current}"
    echo "moved ${name} from .env to secrets/"
  fi
  if grep -q "^${name}=" "${env_file}"; then
    remove_from_env "${name}"
  fi
done
for name in "${obsolete[@]}"; do
  if grep -q "^${name}=" "${env_file}"; then
    remove_from_env "${name}"
    echo "removed ${name} from .env"
  fi
done

# the template's other variables, those .env doesn't have yet
while IFS= read -r line; do
  [[ "${line}" =~ ^([A-Z0-9_]+)= ]] || continue
  is_secret "${BASH_REMATCH[1]}" && continue
  if ! grep -q "^${BASH_REMATCH[1]}=" "${env_file}"; then
    printf '%s\n' "${line}" >> "${env_file}"
    echo "added ${BASH_REMATCH[1]} to .env"
  fi
done <<< "${template}"

for name in "${generated[@]}"; do
  if [[ ! -s "$(secret_file "${name}")" ]]; then
    write_secret "${name}" "$(openssl rand -hex 32)"
    echo "generated secrets/${name,,}"
  fi
done

missing=()
for name in "${secrets_by_hand[@]}"; do
  file="$(secret_file "${name}")"
  # an empty file, so that docker compose finds every secret it mounts; filled by hand
  [[ -e "${file}" ]] || { : > "${file}"; chmod 644 "${file}"; }
  [[ -s "${file}" ]] || missing+=("secrets/${name,,}")
done
for name in "${env_by_hand[@]}"; do
  current="$(value_in "${name}" < "${env_file}")"
  if [[ -z "${current}" || "${current}" == "$(value_in "${name}" <<< "${template}")" ]]; then
    missing+=(".env: ${name}")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "still to fill in by hand (nano <file>):"
  printf '  %s\n' "${missing[@]}"
else
  echo ".env and secrets/ are complete"
fi

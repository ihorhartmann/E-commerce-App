#!/usr/bin/env bash
#
# Writes the server's .env, next to docker-compose.yaml, from example.env and prod.example.env. Run it on the server,
# in the repository's folder:
#
#   infrastructure/contabo/scripts/generate-env.sh
#
# * The passwords and client secrets are generated (random, 64 hex characters - nothing a URL or a shell needs to
#   escape); the values only a person has (Stripe's keys, the alert emails' account, the domain, the image tag) are
#   listed at the end, to be filled in with an editor.
# * A value is replaced only while it is still the template's placeholder: running the script again never changes a
#   password the stack already uses - Postgres keeps the one it was initialized with, so a new one would lock the
#   services out. A variable added to a template later is appended with its template value.
# * Nothing secret is printed: only the names of the variables.

set -euo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
readonly env_file="${repository}/.env"

readonly generated=(
  POSTGRES_PASSWORD
  KEYCLOAK_ADMIN_PASSWORD
  GATEWAY_CLIENT_SECRET
  ORDER_SERVICE_CLIENT_SECRET
  GRAFANA_DB_PASSWORD
  GRAFANA_ADMIN_PASSWORD
  # the realm's test users (harden-realm.sh): their usernames as passwords are public in the repository
  E2E_CLIENT1_PASSWORD
  E2E_CLIENT2_PASSWORD
  E2E_COURIER1_PASSWORD
  E2E_COURIER2_PASSWORD
  E2E_ADMIN_PASSWORD
)
readonly by_hand=(
  DOMAIN
  IMAGE_TAG
  STRIPE_SECRET_KEY
  STRIPE_PUBLISHABLE_KEY
  STRIPE_WEBHOOK_SECRET
  ALERT_SMTP_USERNAME
  ALERT_SMTP_PASSWORD
  ALERT_EMAIL_FROM
  ALERT_EMAIL_TO
)

template="$(cat "${repository}/example.env" "${repository}/infrastructure/contabo/prod.example.env")"
readonly template

# the file and every temporary copy of it are readable by root only
umask 077

value_in() {
  sed -n "s/^$1=//p" | head -n 1
}

set_value() {
  local temporary
  temporary="$(mktemp "${env_file}.XXXXXX")"
  awk -v name="$1" -v value="$2" 'index($0, name "=") == 1 { print name "=" value; next } { print }' \
    "${env_file}" > "${temporary}"
  mv "${temporary}" "${env_file}"
}

if [[ ! -f "${env_file}" ]]; then
  printf '%s\n' "${template}" > "${env_file}"
  echo "created ${env_file}"
fi
chmod 600 "${env_file}"

while IFS= read -r line; do
  [[ "${line}" =~ ^([A-Z0-9_]+)= ]] || continue
  if ! grep -q "^${BASH_REMATCH[1]}=" "${env_file}"; then
    printf '%s\n' "${line}" >> "${env_file}"
    echo "added ${BASH_REMATCH[1]}"
  fi
done <<< "${template}"

for name in "${generated[@]}"; do
  current="$(value_in "${name}" < "${env_file}")"
  if [[ -z "${current}" || "${current}" == "$(value_in "${name}" <<< "${template}")" ]]; then
    set_value "${name}" "$(openssl rand -hex 32)"
    echo "generated ${name}"
  fi
done

missing=()
for name in "${by_hand[@]}"; do
  current="$(value_in "${name}" < "${env_file}")"
  if [[ -z "${current}" || "${current}" == "$(value_in "${name}" <<< "${template}")" ]]; then
    missing+=("${name}")
  fi
done

if (( ${#missing[@]} > 0 )); then
  echo "still to fill in by hand (nano ${env_file}):"
  printf '  %s\n' "${missing[@]}"
else
  echo "${env_file} is complete"
fi

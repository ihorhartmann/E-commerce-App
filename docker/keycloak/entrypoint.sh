#!/bin/bash
# Keycloak reads no secret from a file: the database password and the bootstrap admin's are options it takes as
# environment variables only, and the realm file's ${...} placeholders (the clients' secrets, the shop admin's
# password) are filled from environment variables too. So this reads the Docker secrets (/run/secrets) into them and
# then runs kc.sh with the container's command. The variables live in Keycloak's process alone - `docker inspect`
# doesn't show them. A missing or empty secret stops the container.
set -euo pipefail

secret() {
  local file="/run/secrets/$1"
  if [[ ! -s "${file}" ]]; then
    echo "entrypoint: the secret $1 is missing or empty" >&2
    exit 1
  fi
  # $(...) drops the file's trailing line breaks
  printf '%s' "$(< "${file}")"
}

KC_DB_PASSWORD="$(secret postgres_password)"
KC_BOOTSTRAP_ADMIN_PASSWORD="$(secret keycloak_admin_password)"
APP_ADMIN_PASSWORD="$(secret app_admin_password)"
GATEWAY_CLIENT_SECRET="$(secret gateway_client_secret)"
ORDER_SERVICE_CLIENT_SECRET="$(secret order_service_client_secret)"
export KC_DB_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD APP_ADMIN_PASSWORD GATEWAY_CLIENT_SECRET ORDER_SERVICE_CLIENT_SECRET

exec /opt/keycloak/bin/kc.sh "$@"

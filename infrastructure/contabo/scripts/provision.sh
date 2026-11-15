#!/usr/bin/env bash
#
# The part of a server's setup only a person can give, run by the Provision workflow once the repository is cloned:
# the values nobody can generate (the domain, Stripe's keys, the alert emails' account) and the CD's SSH key. They
# come on the standard input, one NAME=value a line, so that no secret is ever an argument of a command:
#
#   printf 'DOMAIN=%s\n...' ... | ssh root@<server> bash /opt/ecommerce/infrastructure/contabo/scripts/provision.sh
#
# Like generate-env-and-secrets.sh, which it runs first, it never changes what is set already: a value goes in only
# while its place is empty or still the template's placeholder. It can be run again at any time.

set -euo pipefail

repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
readonly repository
readonly env_file="${repository}/.env"
readonly secrets_dir="${repository}/secrets"
readonly scripts="${repository}/infrastructure/contabo/scripts"

# the CD's key may run ecommerce-deploy and nothing else: no shell, no forwarding, no terminal
readonly deploy_key_options='command="/usr/local/sbin/ecommerce-deploy",restrict'

umask 077

bash "${scripts}/generate-env-and-secrets.sh" > /dev/null
template="$(cat "${repository}/example.env" "${repository}/infrastructure/contabo/prod.example.env")"
readonly template

value_in() {
  sed -n "s/^$1=//p" | head -n 1
}

set_env() {
  local current temporary
  current="$(value_in "$1" < "${env_file}")"
  if [[ -n "${current}" && "${current}" != "$(value_in "$1" <<< "${template}")" ]]; then
    echo "kept $1 in .env"
    return
  fi
  temporary="$(mktemp "${env_file}.XXXXXX")"
  awk -v name="$1" -v value="$2" 'index($0, name "=") == 1 { print name "=" value; found = 1; next } { print }
    END { if (!found) print name "=" value }' "${env_file}" > "${temporary}"
  mv "${temporary}" "${env_file}"
  echo "set $1 in .env"
}

set_secret() {
  local file="${secrets_dir}/${1,,}"
  if [[ -s "${file}" ]]; then
    echo "kept secrets/${1,,}"
    return
  fi
  printf '%s' "$2" > "${file}"
  chmod 644 "${file}"
  echo "set secrets/${1,,}"
}

allow_deploy_key() {
  local line="${deploy_key_options} $1"
  install -m 0700 -d /root/.ssh
  touch /root/.ssh/authorized_keys
  chmod 600 /root/.ssh/authorized_keys
  if grep -qF "$1" /root/.ssh/authorized_keys; then
    echo "kept the deploy key"
  else
    echo "${line}" >> /root/.ssh/authorized_keys
    echo "allowed the deploy key (ecommerce-deploy only)"
  fi
}

while IFS= read -r line || [[ -n "${line}" ]]; do
  [[ -z "${line}" ]] && continue
  name="${line%%=*}"
  value="${line#*=}"
  # an optional value left empty in GitHub: nothing to set
  [[ -z "${value}" ]] && continue
  case "${name}" in
    DOMAIN | ACME_CA | STRIPE_PUBLISHABLE_KEY | ALERT_SMTP_USERNAME | ALERT_EMAIL_FROM | ALERT_EMAIL_TO)
      set_env "${name}" "${value}" ;;
    STRIPE_SECRET_KEY | STRIPE_WEBHOOK_SECRET | ALERT_SMTP_PASSWORD)
      set_secret "${name}" "${value}" ;;
    DEPLOY_SSH_PUBLIC_KEY)
      allow_deploy_key "${value}" ;;
    *)
      echo "provision.sh: unknown value ${name}" >&2
      exit 1 ;;
  esac
done

install -m 0755 "${scripts}/ecommerce-deploy" /usr/local/sbin/ecommerce-deploy

# what is still missing, if anything
bash "${scripts}/generate-env-and-secrets.sh" | grep -A 100 '^still to fill in by hand' || echo ".env and secrets/ are complete"

#!/usr/bin/env bash
#
# The deployment of a platform release, once ecommerce-deploy has switched the repository to its tag - run by that
# script only, never on its own:
#
#   deploy.sh <tag> <the commit deployed before>
#
# Its lines starting with ::warning:: or ::error:: become annotations of the GitHub Actions run that started it.

set -euo pipefail
trap 'echo "::error::deploy.sh failed at line ${LINENO}: ${BASH_COMMAND}"' ERR

readonly tag="$1"
readonly previous="$2"

cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
readonly scripts=infrastructure/contabo/scripts

echo "--- deploying ${tag} (was $(git describe --tags --always --match 'v*' "${previous}"))"

# the entry point of this release, for the next deployment
install -m 0755 "${scripts}/ecommerce-deploy" /usr/local/sbin/ecommerce-deploy

echo '--- configuration'
# a variable or a secret this release adds is added too; nothing that is set is ever changed
configuration="$(bash "${scripts}/generate-env-and-secrets.sh")"
echo "${configuration}"
if grep -q '^still to fill in by hand' <<< "${configuration}"; then
  echo "::error::values only a person knows are missing on the server (listed above) - the Provision workflow sets them"
  exit 1
fi
# the version of each of our services, from versions.env of this tag: a service whose version is new gets its
# container recreated below, the others keep running as they are
while IFS='=' read -r variable version; do
  [[ "${variable}" =~ ^[A-Z_]+_IMAGE_TAG$ ]] || continue
  if [[ ! "${version}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "::error::versions.env: ${variable}=${version} is no version"
    exit 1
  fi
  if grep -q "^${variable}=" .env; then
    sed -i "s/^${variable}=.*/${variable}=${version}/" .env
  else
    echo "${variable}=${version}" >> .env
  fi
  echo "${variable}=${version}"
done < versions.env

echo '--- images'
docker compose pull --quiet

echo '--- containers'
# The one-shot jobs (restart: "no" in docker-compose.yaml): they run to completion and exit. --wait counts any exited
# container as a failure, exit code 0 included, so it waits for the other services only, and the jobs' exit codes are
# checked on their own.
readonly jobs=(kafka-init grafana-views)
mapfile -t all_services < <(docker compose config --services)
services=()
for service in "${all_services[@]}"; do
  if [[ " ${jobs[*]} " != *" ${service} "* ]]; then
    services+=("${service}")
  fi
done
for job in "${jobs[@]}"; do
  if [[ " ${all_services[*]} " != *" ${job} "* ]]; then
    echo "::error::the job ${job} is no service of the compose files any more - update the jobs of deploy.sh"
    exit 1
  fi
done
readonly services

# A container whose definition changed is recreated, the others keep running; the jobs start again.
docker compose up --detach --no-build --remove-orphans
# --wait waits for every healthcheck
docker compose up --detach --no-build --wait --wait-timeout 600 "${services[@]}"

# a job still running, or one that failed, fails the deployment
for job in "${jobs[@]}"; do
  timeout 300 docker compose wait "${job}" > /dev/null || true
  state="$(docker compose ps --all --format '{{.State}} {{.ExitCode}}' "${job}")"
  if [[ "${state}" != "exited 0" ]]; then
    echo "::error::the job ${job} ended as '${state}': docker compose logs ${job}"
    exit 1
  fi
  echo "ok: ${job} done"
done

# A configuration file a container mounts changed: the container is the same, so it is restarted to read it again -
# Docker doesn't notice, and none of these reloads it by itself.
declare -A config_of=(
  [alloy]=docker/alloy/
  [loki]=docker/loki/
  [tempo]=docker/tempo/
  [prometheus]=docker/prometheus/
  [alertmanager]=docker/alertmanager/
  [grafana]=docker/grafana/
  [caddy]=docker/caddy/
  [keycloak]=docker/keycloak/
)
changed="$(git diff --name-only "${previous}" HEAD -- docker/)"
restart=()
for service in "${!config_of[@]}"; do
  if grep -q "^${config_of[${service}]}" <<< "${changed}"; then
    restart+=("${service}")
  fi
done
if (( ${#restart[@]} > 0 )); then
  echo "--- restarting, for their changed configuration: ${restart[*]}"
  docker compose restart "${restart[@]}"
  docker compose up --detach --no-build --wait --wait-timeout 600 "${services[@]}"
fi

# Keycloak imports a realm only once, when it doesn't exist yet: a changed realm file is applied by hand
if grep -q '^docker/keycloak/realm-.*\.json$' <<< "${changed}"; then
  echo "::warning::a realm file changed in ${tag} - Keycloak won't import it again by itself: apply the change by hand (infrastructure/contabo/README.md)"
fi

echo '--- from the internet'
domain="$(sed -n 's/^DOMAIN=//p' .env | head -n 1)"
check() {
  local url="$1"
  for _ in {1..30}; do
    if curl --silent --fail --output /dev/null --max-time 10 "${url}"; then
      echo "ok: ${url}"
      return 0
    fi
    sleep 5
  done
  echo "::error::${url} doesn't answer"
  return 1
}
check "https://${domain}/"
check "https://auth.${domain}/realms/ecommerce/.well-known/openid-configuration"
check "https://grafana.${domain}/api/health"

echo "--- ${tag} deployed"

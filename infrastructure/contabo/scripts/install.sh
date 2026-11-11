#!/usr/bin/env bash
#
# Prepares a fresh Ubuntu 24.04 server for the docker compose stack. The server is ordered by hand (Contabo), with
# the SSH key already in root's authorized_keys - the script turns password logins off. Run it as root, over SSH:
#
#   ssh root@<server> 'bash -s' < infrastructure/contabo/scripts/install.sh
#
# It stops at the first failing command and writes the marker file only as its very last step, so a missing marker
# means a failed installation. Every step can run again: after a fix, or a change, the script is simply re-run.

set -euo pipefail
trap 'echo "install.sh failed at line ${LINENO}: ${BASH_COMMAND}" >&2' ERR

# Docker's packages for Ubuntu 24.04 (noble): another Ubuntu release needs its own versions.
readonly DOCKER_VERSION='5:29.9.3-1~ubuntu.24.04~noble'
readonly CONTAINERD_VERSION='2.4.2-3~ubuntu.24.04~noble'
readonly COMPOSE_PLUGIN_VERSION='5.7.1-1~ubuntu.24.04~noble'

readonly SWAP_FILE=/swapfile
readonly SWAP_SIZE=4G
readonly MARKER_FILE=/var/lib/ecommerce/install.ok

export DEBIAN_FRONTEND=noninteractive

# apt may still be busy with the image's own first-boot updates: wait for its lock instead of failing on it
apt_get() {
  apt-get -o DPkg::Lock::Timeout=300 "$@"
}

echo '--- swap'
# six JVMs start at once: swap turns a memory spike into a slow start instead of an OOM kill
if [[ ! -f "${SWAP_FILE}" ]]; then
  fallocate -l "${SWAP_SIZE}" "${SWAP_FILE}"
  chmod 600 "${SWAP_FILE}"
  mkswap "${SWAP_FILE}"
  echo "${SWAP_FILE} none swap sw 0 0" >> /etc/fstab
fi
swapon --show=NAME --noheadings | grep -qx "${SWAP_FILE}" || swapon "${SWAP_FILE}"
# use it only under real memory pressure
echo 'vm.swappiness = 10' > /etc/sysctl.d/90-swap.conf
sysctl --quiet --load /etc/sysctl.d/90-swap.conf

echo '--- ssh: keys only'
# sshd takes the first value it reads, so this file has to sort before the image's own (50-cloud-init.conf)
cat > /etc/ssh/sshd_config.d/00-keys-only.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
EOF
# Ubuntu starts sshd on the first connection (socket activation): until then its runtime directory, which the
# configuration check needs, doesn't exist, and there is no running service to reload
install -m 0755 -d /run/sshd
sshd -t
systemctl try-reload-or-restart ssh

echo '--- firewall'
# Everything but SSH and the web is closed. This covers what listens on the host itself: a port Docker publishes
# goes around ufw, so the compose file publishes only 80 and 443 to the world and binds every other port to
# 127.0.0.1 - those are reached through an SSH tunnel (`ssh -L`).
apt_get update
apt_get install --yes ufw
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 80/tcp comment 'HTTP: the certificate challenge and the redirect to HTTPS'
ufw allow 443/tcp comment 'HTTPS'
ufw allow 443/udp comment 'HTTP/3'
ufw --force enable

echo '--- docker'
apt_get install --yes ca-certificates curl

install -m 0755 -d /etc/apt/keyrings
curl --fail --silent --show-error --location https://download.docker.com/linux/ubuntu/gpg --output /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

# shellcheck source=/dev/null
ubuntu_codename="$(. /etc/os-release && echo "${VERSION_CODENAME}")"
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${ubuntu_codename} stable" \
  > /etc/apt/sources.list.d/docker.list

# before the daemon first starts: without a limit the JSON logs of some twenty containers fill the disk
install -m 0755 -d /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF

apt_get update
apt_get install --yes \
  "docker-ce=${DOCKER_VERSION}" \
  "docker-ce-cli=${DOCKER_VERSION}" \
  "containerd.io=${CONTAINERD_VERSION}" \
  "docker-compose-plugin=${COMPOSE_PLUGIN_VERSION}"

# the automatic security updates must not replace the pinned versions, nor restart the containers with the daemon
apt-mark hold docker-ce docker-ce-cli containerd.io docker-compose-plugin

systemctl enable --now docker

echo '--- verify'
docker version
docker compose version
# the whole path once: pulling an image, creating and running a container
docker run --rm hello-world > /dev/null
docker image rm hello-world > /dev/null
swapon --show

install -m 0755 -d "$(dirname "${MARKER_FILE}")"
date --utc --iso-8601=seconds > "${MARKER_FILE}"
echo '--- install.sh finished'

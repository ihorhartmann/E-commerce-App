#!/bin/sh
# Alertmanager reads no environment variables in its configuration, so this renders it: alertmanager.yml and the
# templates get the values of .env in place of their ${...}, the SMTP password - a Docker secret, at
# ALERT_SMTP_PASSWORD_FILE - goes to a file of its own without the trailing line break an editor leaves
# (smtp_auth_password_file), and only then Alertmanager starts. A missing variable or an empty secret stops the
# container.
set -eu

: "${ALERT_SMTP_SMARTHOST:?ALERT_SMTP_SMARTHOST is not set}"
: "${ALERT_SMTP_USERNAME:?ALERT_SMTP_USERNAME is not set}"
: "${ALERT_SMTP_PASSWORD_FILE:?ALERT_SMTP_PASSWORD_FILE is not set}"
: "${ALERT_EMAIL_FROM:?ALERT_EMAIL_FROM is not set}"
: "${ALERT_EMAIL_TO:?ALERT_EMAIL_TO is not set}"
: "${GRAFANA_URL:?GRAFANA_URL is not set}"

rendered=/tmp/alertmanager
mkdir -p "$rendered/templates"
umask 077
smtp_password="$(cat "$ALERT_SMTP_PASSWORD_FILE")"
: "${smtp_password:?the secret at ALERT_SMTP_PASSWORD_FILE is empty}"
printf '%s' "$smtp_password" > "$rendered/smtp_password"

render() {
  sed -e "s|\${ALERT_SMTP_SMARTHOST}|$ALERT_SMTP_SMARTHOST|g" \
      -e "s|\${ALERT_SMTP_USERNAME}|$ALERT_SMTP_USERNAME|g" \
      -e "s|\${ALERT_EMAIL_FROM}|$ALERT_EMAIL_FROM|g" \
      -e "s|\${ALERT_EMAIL_TO}|$ALERT_EMAIL_TO|g" \
      -e "s|\${GRAFANA_URL}|$GRAFANA_URL|g" \
      "$1" > "$2"
}

render /etc/alertmanager/alertmanager.yml "$rendered/alertmanager.yml"
for template in /etc/alertmanager/templates/*.tmpl; do
  render "$template" "$rendered/templates/$(basename "$template")"
done

# `entrypoint.sh check` (the CI, with made-up values) checks the rendered configuration offline instead of starting:
# its syntax and templates, and that each severity reaches the receiver with its own email template.
if [ "${1:-}" = "check" ]; then
  config="$rendered/alertmanager.yml"
  amtool check-config "$config"
  amtool config routes test --config.file="$config" --verify.receivers=email-critical severity=critical service=order-service
  amtool config routes test --config.file="$config" --verify.receivers=email-warning severity=warning service=order-service
  amtool config routes test --config.file="$config" --verify.receivers=email-warning service=order-service
  exit 0
fi

# a single instance: no cluster of Alertmanagers to gossip with
exec /bin/alertmanager --config.file="$rendered/alertmanager.yml" --storage.path=/alertmanager --cluster.listen-address=

#!/bin/sh
# Refuses to start without the configuration nginx/default.conf.template needs: envsubst would silently leave the
# placeholders empty, and nginx would start half-configured.
set -eu

missing=""
for name in WEB_UI_PORT GATEWAY_ADDRESS STRIPE_PUBLISHABLE_KEY; do
    eval "value=\${$name:-}"
    if [ -z "$value" ]; then
        missing="$missing $name"
    fi
done

if [ -n "$missing" ]; then
    echo "web-ui: missing required environment variables:$missing" >&2
    exit 1
fi

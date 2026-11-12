# Production on a Contabo VPS

The whole docker compose stack on one server (Ubuntu 24.04), `docker-compose.yaml` merged with
`docker-compose.prod.yaml`. The shop is on `https://<DOMAIN>`, Keycloak's sign-in pages on `https://auth.<DOMAIN>`;
everything else - Grafana, Kafka UI, Alloy, Keycloak's admin console, Postgres - only through an SSH tunnel.

```text
internet ──80/443──> Caddy ──> web-ui (nginx) ──> gateway ──> services
                          └──> keycloak (/realms, /resources only)
```

The server is ordered by hand in Contabo's panel (no Terraform: its provider is unreliable, and creating a server
through it buys a contract). DNS: A records for `@`, `www` and `auth` pointing at the server's IP.

## 1. Preparing the server (once)

```bash
ssh root@<server> 'bash -s' < infrastructure/contabo/scripts/install.sh
```

Swap, SSH with keys only, ufw (22, 80, 443), Docker with log rotation. It can be run again after a change.

## 2. The repository, `.env` and `secrets/`

The configuration files the containers mount (`docker/`, the Keycloak theme) come from a clone of the repository,
e.g. in `/opt/ecommerce`. Then, in that folder:

```bash
infrastructure/contabo/scripts/generate-env-and-secrets.sh
```

It writes `.env` from `example.env` and `prod.example.env` - the configuration, with no secret in it - and
`secrets/`, one file per password, client secret and key, which the containers get as Docker secrets in
`/run/secrets` (`docker-compose.prod.yaml`); `docker inspect` shows none of them. `.env` and `secrets/` are readable
by root only. It generates the passwords and client secrets and lists what is left to fill in by hand (`nano <file>`):
in `.env` `DOMAIN`, `IMAGE_TAG` (a tag the Build Images workflow pushed), Stripe's publishable key and the alert
emails' Gmail account; in `secrets/` Stripe's **test-mode** secret key (`stripe_secret_key`), the webhook's signing
secret (`stripe_webhook_secret`) and the Gmail App Password (`alert_smtp_password`). It never changes a secret that is
already set - Postgres keeps the password it was initialized with - and moves the secrets of a `.env` from before
`secrets/` existed into their files. Keep a copy of `secrets/` in a password manager: without it a new server needs
every secret issued again.

The shop's admin (`admin` in the `ecommerce` realm) signs in with `secrets/app_admin_password`; the demo clients and
couriers with the password `demo` (the repository's README). The realm's clients for the e2e tests and Postman are
off.

`secrets/stripe_webhook_secret` holds the signing secret of the webhook endpoint in the Stripe dashboard (test mode):
Developers → Webhooks → Add destination, events from your account, API version `2026-05-27.dahlia` (the one
`stripe-java` in payment-service is built for), the events `payment_intent.succeeded`,
`payment_intent.payment_failed` and `refund.updated`, a Snapshot payload, the URL
`https://<DOMAIN>/api/v1/webhooks/stripe`; then Signing secret → Reveal. Stripe can reach it only once the real
certificates are issued (section 4). "Send test event" answers 200 when the secret is right - the test event's
PaymentIntent is unknown, which the service acknowledges - and 400 when it isn't.

## 3. Starting and updating

`.env` sets `COMPOSE_FILE`, so every `docker compose` command in the folder uses both files:

```bash
docker compose pull
docker compose up -d --no-build
```

A new release: set `IMAGE_TAG` in `.env`, then the same two commands.

## 4. Certificates

Caddy orders them from Let's Encrypt's **staging** CA while `ACME_CA` is empty: browsers warn about them (accept the
warning for both `<DOMAIN>` and `auth.<DOMAIN>`), but a misconfiguration retried many times can't hit the real CA's
rate limits. Once the staging certificates are issued (`docker compose logs caddy`), switch to the real CA:

```bash
sed -i 's#^ACME_CA=.*#ACME_CA=https://acme-v02.api.letsencrypt.org/directory#' .env
docker compose up -d caddy
```

## 5. The tools, through an SSH tunnel

```bash
ssh -N -L 8089:127.0.0.1:8089 -L 9098:127.0.0.1:9098 -L 12345:127.0.0.1:12345 -L 8090:127.0.0.1:8090 -L 5432:127.0.0.1:5432 <server>
```

| What | URL |
|---|---|
| Grafana (`admin` / `secrets/grafana_admin_password`) | http://localhost:8089 |
| Kafka UI | http://localhost:9098 |
| Alloy | http://localhost:12345 |
| Keycloak admin console (`KEYCLOAK_ADMIN_USER` / `secrets/keycloak_admin_password`) | http://localhost:8090/admin |
| Postgres (`POSTGRES_USER` / `secrets/postgres_password`) | localhost:5432 |

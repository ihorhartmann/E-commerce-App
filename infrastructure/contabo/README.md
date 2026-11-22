# Production on a Contabo VPS

The whole docker compose stack on one server (Ubuntu 24.04), `docker-compose.yaml` merged with
`docker-compose.prod.yaml`. The shop is on `https://<DOMAIN>`, Keycloak's sign-in pages on `https://auth.<DOMAIN>`,
Grafana on `https://grafana.<DOMAIN>` - signed in through Keycloak's `internal` realm: you as `admin`, visitors as the
published `observer` (a viewer). Everything else - Kafka UI, Alloy, Keycloak's admin console and master realm, Postgres - only
through an SSH tunnel.

```text
internet ──80/443──> Caddy ──> gateway (/api, /oauth2, /login, /logout) ──> services
                          ├──> web-ui (nginx, static files: everything else on the shop's host)
                          ├──> keycloak (/realms/ecommerce, /realms/internal, /resources only)
                          └──> grafana
```

The server is ordered by hand in Contabo's panel (no Terraform: its provider is unreliable, and creating a server
through it buys a contract). DNS: A records for `@`, `www`, `auth` and `grafana` pointing at the server's IP.

## 1. Preparing the server (once)

```bash
ssh root@<server> 'bash -s' < infrastructure/contabo/scripts/install.sh
```

Swap, SSH with keys only, ufw (22, 80, 443), Docker with log rotation, the nightly database backup (section 6). It can
be run again after a change.

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
in `.env` `DOMAIN`, Stripe's publishable key and the alert emails' Gmail account (the services' versions are set
by every deployment, from `versions.env`: section 3); in `secrets/` Stripe's **test-mode** secret key
(`stripe_secret_key`), the webhook's signing secret (`stripe_webhook_secret`) and the Gmail App Password
(`alert_smtp_password`). It never changes a secret that is already set - Postgres keeps the password it was
initialized with - and moves the secrets of a `.env` from before `secrets/` existed into their files. Keep a copy
of `secrets/` in a password manager: without it a new server needs every secret issued again.

The shop's admin (`admin` in the `ecommerce` realm) signs in with `secrets/app_admin_password`; the demo clients and
couriers with the password `demo` (the repository's README). The realm's clients for the e2e tests and Postman are
off.
Grafana's admin (`admin` in the `internal` realm) signs in with `secrets/internal_admin_password`.

`secrets/stripe_webhook_secret` holds the signing secret of the webhook endpoint in the Stripe dashboard (test mode):
Developers → Webhooks → Add destination, events from your account, API version `2026-05-27.dahlia` (the one
`stripe-java` in payment-service is built for), the events `payment_intent.succeeded`,
`payment_intent.payment_failed` and `refund.updated`, a Snapshot payload, the URL
`https://<DOMAIN>/api/v1/webhooks/stripe`; then Signing secret → Reveal. Stripe can reach it only once the real
certificates are issued (section 4). "Send test event" answers 200 when the secret is right - the test event's
PaymentIntent is unknown, which the service acknowledges - and 400 when it isn't.

## 3. Releases, deployment, rollback

Two kinds of version, both git tags of commits on `main`:

* **a service's version**, `order-service/v1.3.0`: that service's code, built into `order-service:v1.3.0` in GHCR;
* **a platform version**, `v1.4.0`: what runs on the server - the compose files and configuration of that commit, and
  the version of each service its `versions.env` names.

| Workflow | Started by | Does |
|---|---|---|
| Create Service Release (No Deployment) (`service-release.yml`) | pushing a tag `<service>/vX.Y.Z` | Build Images for that service only, a GitHub release of the service listing the commits that changed `app/<service>/` since its previous tag, and its new version in `versions.env` on the pull request `release/versions` - nothing deployed |
| Create Platform Release and Deploy (`platform-release.yml`) | pushing a tag `vX.Y.Z` | checks that every image of `versions.env` is in GHCR, a GitHub release listing the versions and the changes since the previous platform release, then Deploy |
| Deploy (`deploy.yml`) | Create Platform Release and Deploy, and Provision; by hand with an earlier platform tag to **roll back** | `ecommerce-deploy <tag>` on the server, over SSH |
| Provision (`provision.yml`) | by hand only | a server from scratch: `install.sh`, the clone, `.env` and `secrets/` with the values only a person knows, the deploy key; then Deploy |

A release, in this order:

```bash
git checkout main && git pull
git tag order-service/v1.3.0 && git push origin order-service/v1.3.0      # one or more services
# wait for the pull request "Service versions for the next release" (release/versions): full suite, merge
git checkout main && git pull
git tag -a v1.4.0 -m "v1.4.0" && git push origin v1.4.0                    # deploys the merged versions
```

(a platform release can also be published on GitHub's page, Releases → Draft a new release, with a new tag on `main`).
Push each service's tag on its own (`git push origin <tag>`, one after the other): GitHub starts no workflow at all for
a push of more than three tags. Several services' tags pushed one after the other add their lines to the same pull
request, however many there are; merge it once all of them have. Once it is merged or closed, the next service's tag starts a new one from `main` - closing it
unmerged drops its versions. A platform tag put on a commit
before that pull request is merged deploys the versions of that commit - the old ones - so tag `main` after the merge.
A change of the configuration only (Grafana, Caddy, a third-party image's version) needs no service tag: merge it and
push a platform tag. Versions follow semantic versioning: `MAJOR` for a change that breaks what was there, `MINOR` for
a feature, `PATCH` for a fix. A version is built once - its images are never replaced - so a rollback gets exactly what
ran before: Actions → Deploy → Run workflow, the earlier platform tag, which brings back its configuration and its
service versions together.

The first release: `versions.env` names `v0.1.0` for every service, so tag `main` with the six tags
`inventory-service/v0.1.0`, `order-service/v0.1.0`, `payment-service/v0.1.0`, `shipping-service/v0.1.0`,
`gateway-service/v0.1.0`, `web-ui/v0.1.0` and push them one at a time (no pull request follows: the versions are there
already), then `v0.1.0`.

```bash
for s in inventory-service order-service payment-service shipping-service gateway-service web-ui; do
  git tag "${s}/v0.1.0" && git push origin "${s}/v0.1.0"
done
```

A tag pushed with others and never released (no run in Actions) is pushed again after deleting it on GitHub:
`git push origin --delete <tag>`, then `git push origin <tag>`.

`ecommerce-deploy <tag>` (`scripts/ecommerce-deploy`, installed in `/usr/local/sbin`) switches the clone to the tag and
runs that version's `scripts/deploy.sh`: the variables and secrets the release adds (`generate-env-and-secrets.sh`),
the versions of `versions.env` copied into `.env`, `docker compose pull` and `up --wait` - which recreates only the
services whose version changed - a restart of the containers whose mounted configuration changed (Alloy, Loki, Caddy,
...: Docker doesn't notice that), and the shop, Keycloak and Grafana checked from the internet. It can be run by hand
on the server too. A changed realm file is the one thing it doesn't apply - Keycloak imports a realm once - so the run
warns about it; apply the change in the admin console or by importing the realm again.

### What GitHub needs (once)

Two SSH keys, made for this and nothing else - not your own:

```bash
ssh-keygen -t ed25519 -N '' -C 'ecommerce deploy' -f deploy_key
ssh-keygen -t ed25519 -N '' -C 'ecommerce provision' -f provision_key
ssh-keyscan -t ed25519 <server-ip>          # the server's host key, for SSH_KNOWN_HOSTS
```

Settings → Environments: `production` and `provision` (for `provision`, "Required reviewers" makes every run wait for
your approval). Then:

| Where | Name | Kind | Value |
|---|---|---|---|
| repository | `SERVER_HOST` | variable | the server's IP address |
| repository | `DOMAIN` | variable | the shop's domain, e.g. `ihorhartmann.com` |
| repository | `SSH_KNOWN_HOSTS` | secret | the `ssh-keyscan` line |
| `production` | `DEPLOY_SSH_KEY` | secret | `deploy_key` (the private key) |
| repository | `VERSIONS_PR_TOKEN` | secret | a fine-grained personal access token for this repository only - Contents and Pull requests: Read and write - for the pull request `release/versions` (one made with the workflow's own token would start no full suite) |
| `provision` | `PROVISION_SSH_KEY` | secret | `provision_key` (the private key) |
| `provision` | `DEPLOY_SSH_PUBLIC_KEY` | variable | `deploy_key.pub` |
| repository | `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY` | secrets | Stripe's test-mode keys - there already, for the e2e tests |
| `provision` | `STRIPE_WEBHOOK_SECRET` | secret | the signing secret of production's webhook endpoint (section 2) - not the repository's one, which the e2e tests' Stripe CLI uses: the environment's secret takes its place here |
| `provision` | `ALERT_SMTP_PASSWORD` | secret | the Gmail App Password |
| `provision` | `ALERT_SMTP_USERNAME`, `ALERT_EMAIL_FROM`, `ALERT_EMAIL_TO` | variables | as in `.env` |
| `provision` | `ACME_CA` | variable | optional: `https://acme-v02.api.letsencrypt.org/directory` for real certificates at once |

`provision_key.pub` goes into root's `authorized_keys` - on a new server through Contabo's panel when ordering it, on
this one by hand. The deploy key gets there through Provision (`provision.sh`), restricted to `ecommerce-deploy`: it
can't open a shell. The passwords and client secrets are generated on the server and never leave it; GitHub keeps only
what a person has to type. A reinstalled server has a new host key: update `SSH_KNOWN_HOSTS` first.

`.env` sets `COMPOSE_FILE`, so every `docker compose` command in the folder uses both files.

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
| Kafka UI | http://localhost:9098 |
| Alloy | http://localhost:12345 |
| Keycloak admin console (`KEYCLOAK_ADMIN_USER` / `secrets/keycloak_admin_password`) | http://localhost:8090/admin |
| Postgres (`POSTGRES_USER` / `secrets/postgres_password`) | localhost:5432 |

## 6. Database backups

Every night at 03:15 UTC cron (`/etc/cron.d/ecommerce-backup`, put in place by `install.sh`) runs
`scripts/backup-postgres.sh`: a dump of the whole database - every service's schema and Keycloak's - into
`/var/backups/ecommerce/postgres/ecommerce-<date>_<time>.dump` (readable by root only), in `pg_dump`'s custom format.
A dump is kept only once `pg_restore` can read it, and the dumps older than 14 days are deleted only after a new one
has succeeded. The backups are on the server itself: they cover a mistake or a bad migration, not the loss of the
server.

```bash
journalctl -t ecommerce-backup                                  # what each run did, or why it failed
infrastructure/contabo/scripts/backup-postgres.sh               # a backup right now, e.g. before a risky change
```

A restore replaces what is in the database with the dump's content. Stop the services that write to it first, then:

```bash
docker compose stop gateway-service order-service payment-service inventory-service shipping-service keycloak
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < /var/backups/ecommerce/postgres/<file>.dump
docker compose up -d
```

One schema only - a service's own data - with `--schema <name>` (e.g. `--schema orders`) added to `pg_restore`, and
only that service stopped. To look into a dump without touching anything, restore it into a scratch database
(`createdb restore_check`, `pg_restore -d restore_check`, `dropdb restore_check`).

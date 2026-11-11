# Deploying SecScan to a VM (internal demo)

How to run SecScan on a single Linux VM for an internal demo. Target of record: the Celloscope
internal VM `172.16.6.73` (reachable over VPN on `172.16.4.0/22`). Produced in **M8.9**.

This is an **internal-demo** deployment: plain HTTP, no TLS, single host, Docker-socket worker. It is
**not** hardened for external or multi-tenant use — see "Security posture" below and the D-096 gate in
`docs/DECISIONS.md` before any externally reachable deployment.

## 0. What runs

`docker compose` with four services from `docker-compose.yml` + `docker-compose.prod.yml`:

| Service | Exposure on the VM | Notes |
|---|---|---|
| `ui` (nginx + SPA) | `0.0.0.0:80` → container 80 | the only network-facing port; proxies `/api/` to `api` |
| `api` | `127.0.0.1:8080` | loopback only; reached by the browser via the ui proxy |
| `postgres` | `127.0.0.1:15432` | loopback only; 15432 to dodge the VM's own Postgres on 5432 |
| `worker` | no port | claims scan jobs, launches disposable scanner containers |

The `worker` also mounts `/var/run/docker.sock` (to launch sibling scanner containers) and the scan
work root. The scanner image `secscan-scanner:8.21.2` must exist on the VM's Docker daemon.

## 1. Prerequisites (verified on 172.16.6.73, 2026-11-12)

- Docker Engine + `docker compose` v2, daemon usable by the deploy user **without sudo**.
- Outbound HTTPS to `gitlab.celloscope.net` and `github.com`.
- CPU/RAM/disk headroom (16 vCPU / 23 GiB / 100+ GiB free here; **no swap** — keep scanner limits).
- No host JDK/Node needed: the app image builds in a multi-stage Dockerfile, the ui in its own image.

## 2. Deploy (first time)

```bash
# 2.1 Get the source at the pinned commit onto the VM (~/web-app-security-scanner).
#     From the dev machine (no GitHub auth needed on the VM):
rsync -az --delete \
  --exclude '.git/' --exclude 'build/' --exclude '**/build/' --exclude '.gradle/' \
  --exclude 'node_modules/' --exclude 'ui/dist/' --exclude '.env' --exclude 'scorecard/' \
  ./ ubuntu@172.16.6.73:~/web-app-security-scanner/
# Deploy a PINNED commit, not "main" (main is not a release marker — ARCHITECT_NOTES §3).
# This deploy: develop @ 64b03d4 + the M8.9 overlay/nginx fix (feature/m8.9-vm-deploy @ 428e825).

# 2.2 Scan work root (same absolute path on host and in the worker; owned by the worker's uid:gid).
sudo mkdir -p /var/lib/secscan/scans
sudo chown "$(id -u)":"$(id -g)" /var/lib/secscan/scans && sudo chmod 700 /var/lib/secscan/scans

# 2.3 Secrets: copy the template and generate FRESH values on the VM (never reuse dev secrets,
#     never commit .env). Fill in: SECSCAN_ENCRYPTION_KEY, SECSCAN_FINGERPRINT_KEY, the DB password,
#     and SECSCAN_BOOTSTRAP_ADMIN_PASSWORD. Set SECSCAN_UID/GID to `id -u`/`id -g` and DOCKER_GID to
#     `stat -c %g /var/run/docker.sock`.
cp .env.prod.example .env && chmod 600 .env
#     e.g.  openssl rand -base64 32   (keys)      openssl rand -hex 24   (db pw)
$EDITOR .env

# 2.4 Build the scanner image, then build + start the stack.
docker build -t secscan-scanner:8.21.2 scanner-image/
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

> **Known issue (M8.9, 2026-11-12):** a *fresh* `docker build scanner-image/` currently FAILS — the
> Dockerfile pins exact Alpine package patch versions (`python3=3.12.15-r0`, `py3-yaml=6.0.2-r0`) that
> Alpine has since dropped from its repo (`apk ... exit code 8`). Until the pins are refreshed (follow-up
> fix), transfer a known-good image from a machine that already has it instead of building on the VM:
> ```bash
> # from a machine with secscan-scanner:8.21.2 already built:
> docker save secscan-scanner:8.21.2 | ssh ubuntu@<vm> 'docker load'
> # then (re)start the worker so it re-checks:
> docker compose -f docker-compose.yml -f docker-compose.prod.yml restart worker
> ```
> The worker logs `Scanner environment ready; the worker will claim scan jobs.` once the image is present.

Open `http://<vm-ip>/` and sign in as the bootstrap admin (`SECSCAN_BOOTSTRAP_ADMIN_EMAIL`, the
password you set in `.env`). The admin is created on first boot only, and only while the DB has no
users.

## 3. Day-to-day

```bash
cd ~/web-app-security-scanner
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps           # status
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f api  # follow a service
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart ui   # if login says "Could not reach the server" (see §5)
docker compose -f docker-compose.yml -f docker-compose.prod.yml down          # stop (keeps the DB volume)
```

**Never** run `docker compose down -v` here — `-v` deletes the Postgres volume and all demo data.

### Updating to a newer build
```bash
# re-sync source from the dev machine (§2.1), then on the VM:
docker build -t secscan-scanner:8.21.2 scanner-image/     # only if scanner-image/ changed
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

## 4. Backup / restore (demo-grade)

```bash
# Dump the DB (contains encrypted tokens + findings; store it safely).
docker compose -f docker-compose.yml -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U secscan secscan | gzip > ~/secscan-backup-$(date +%F).sql.gz
# Restore into a fresh stack:
gunzip -c ~/secscan-backup-YYYY-MM-DD.sql.gz | \
  docker compose -f docker-compose.yml -f docker-compose.prod.yml exec -T postgres psql -U secscan secscan
```
`.env` is **not** in the dump — back it up separately (it holds the AES/HMAC keys without which the
stored GitLab tokens can't be decrypted).

## 5. Known behaviours

- **ui ↔ api DNS:** the ui nginx re-resolves the `api` service at request time (Docker DNS
  `127.0.0.11`, M8.9 fix in `ui/nginx.conf`), so restarting `api` alone no longer breaks login. If you
  ever revert that, `docker compose restart ui` is the workaround.
- **Scanner resources:** 4 GB RAM / 2 CPU / 30-min timeout per scan, max 2 parallel (see
  `ScannerProperties`). A large repo (e.g. ib-api, ~2600 Java files) takes ~4–5 min.

## 6. Security posture (internal demo — read before widening access)

- **Plain HTTP, no TLS** (certs waived for this demo). `SECSCAN_SESSION_COOKIE_SECURE=false`, so the
  session cookie travels in cleartext on the internal subnet. Acceptable **only** for an internal demo;
  do not expose this host beyond `172.16.4.0/22`.
- **Docker socket** is mounted into the worker (host-root-equivalent, OD-5 / D-026). Accepted for the
  internal demo; revisit before any external exposure.
- The **D-096 gate** (rule-pack licences, egress restriction, sign-up/connection caps, token
  write-scope checks) is **not triggered** while this is internal-only. It must pass before the first
  external tenant.

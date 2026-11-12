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
| `ui` (nginx + SPA) | `172.16.6.73:80` → container 80 | the only network-facing port; proxies `/api/` to `api` |
| `api` | `127.0.0.1:8080` | loopback only; reached by the browser via the ui proxy |
| `postgres` | `127.0.0.1:15432` | loopback only; 15432 to dodge the VM's own Postgres on 5432 |
| `worker` | no port | claims scan jobs, launches disposable scanner containers |

The `worker` also mounts `/var/run/docker.sock` (to launch sibling scanner containers) and the scan
work root. The scanner image `secscan-scanner:8.21.2` must exist on the VM's Docker daemon.

**Bind interface:** the VM is multi-homed — besides the internal `eth0` it has several Docker bridge
interfaces — so the ui is bound to the **specific internal IP** via `SECSCAN_HTTP_BIND=172.16.6.73` in
`.env` (not `0.0.0.0`, which would also publish on the docker bridges). `ip -br addr` on 172.16.6.73
(2026-11-13):
```
eth0             UP             172.16.6.73/22      <- the one we bind to
docker0          DOWN           172.17.0.1/16
br-*             UP             172.18-20.0.1/16, 10.203.0.1/24   <- docker/compose bridges
lo               UNKNOWN        127.0.0.1/8
```

## 1. Prerequisites (verified on 172.16.6.73, 2026-11-12)

- Docker Engine + `docker compose` v2, daemon usable by the deploy user **without sudo**.
- Outbound HTTPS to `gitlab.celloscope.net` and `github.com`.
- CPU/RAM/disk headroom (16 vCPU / 23 GiB / 100+ GiB free here; **no swap** — keep scanner limits).
- No host JDK/Node needed: the app image builds in a multi-stage Dockerfile, the ui in its own image.

## 2. Deploy (first time)

```bash
# 2.1 Ship the source at a PINNED commit to the VM via `git archive` (reproducible; records provenance;
#     no GitHub auth needed on the VM, and no .git/working-tree cruft). From the dev machine, in the repo:
SHA=$(git rev-parse <ref>)          # the exact commit to deploy (a release tag/commit, NOT main)
git archive --format=tar "$SHA" \
  | ssh ubuntu@172.16.6.73 'mkdir -p ~/web-app-security-scanner && tar -x -C ~/web-app-security-scanner'
ssh ubuntu@172.16.6.73 "printf '%s  %s\n' $SHA \"$(date -Is)\" > ~/web-app-security-scanner/DEPLOYED_COMMIT"
# DEPLOYED_COMMIT records EXACTLY what the VM is running (full sha + date). Deploy a pinned commit, not
# "main" (main is not a release marker — ARCHITECT_NOTES §3). The `.env` is never shipped (it's VM-only).

# 2.2 Scan work root (same absolute path on host and in the worker; owned by the worker's uid:gid).
sudo mkdir -p /var/lib/secscan/scans
sudo chown "$(id -u)":"$(id -g)" /var/lib/secscan/scans && sudo chmod 700 /var/lib/secscan/scans

# 2.3 Secrets: copy the template and generate FRESH values on the VM (never reuse dev secrets,
#     never commit .env). Fill in: SECSCAN_ENCRYPTION_KEY, SECSCAN_FINGERPRINT_KEY, the DB password,
#     and SECSCAN_BOOTSTRAP_ADMIN_PASSWORD. Set SECSCAN_UID/GID to `id -u`/`id -g` and DOCKER_GID to
#     `stat -c %g /var/run/docker.sock`.
cp .env.prod.example .env && chmod 600 .env
#     e.g.  openssl rand -base64 32   (use for EVERY key AND the DB password — one command for all)
$EDITOR .env

# 2.4 Scanner image. NOTE: a *fresh* `docker build scanner-image/` currently FAILS — the Dockerfile pins
#     Alpine package patch versions (python3=3.12.15-r0, py3-yaml=6.0.2-r0) that Alpine dropped from its
#     repo (apk exit 8). Until the pins are refreshed (follow-up fix, M8.16), transfer a known-good image
#     from a host that already has it, and verify it by its content-hash LABEL (stable across save/load;
#     the image Id is re-computed on load and WILL differ):
docker save secscan-scanner:8.21.2 | ssh ubuntu@<vm> 'docker load'
docker image inspect -f '{{ index .Config.Labels "secscan.scanner.content-hash" }}' secscan-scanner:8.21.2
#     expect: 44bd01779241fcc61a374ff21e3b5f0858e34328ae8ddcc67d55479076ce8d28
#     (Only once the Dockerfile is fixed:  docker build -t secscan-scanner:8.21.2 scanner-image/)

# 2.5 Build the app images and start the stack.
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
# If the scanner image was loaded AFTER the stack started, (re)start the worker so it re-checks:
docker compose -f docker-compose.yml -f docker-compose.prod.yml restart worker
```

> The worker logs `Scanner environment ready; the worker will claim scan jobs.` once the image is present.
>
> **Image actually running on 172.16.6.73 (2026-11-12):** `secscan-scanner:8.21.2`, shipped via
> `docker save | docker load` from the dev machine (fresh build broken as above). Identity:
> - content-hash label `44bd01779241fcc61a374ff21e3b5f0858e34328ae8ddcc67d55479076ce8d28`
>   (baked at build time — the stable build identity; matches the dev-machine build).
> - image Id on the VM `sha256:a0859ea10d9609cd891c55dbb57a1a7fa3660753f027c5a6234624a42f1340ae`
>   (the config digest is re-computed by the VM's Docker engine on load, so it differs from the dev
>   host's image id even though the content is identical — compare the content-hash label, not the Id).

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
# Re-ship a newer PINNED commit (this overwrites DEPLOYED_COMMIT with the new sha+date):
SHA=$(git rev-parse <new-ref>)
git archive --format=tar "$SHA" \
  | ssh ubuntu@172.16.6.73 'tar -x -C ~/web-app-security-scanner'
ssh ubuntu@172.16.6.73 "printf '%s  %s\n' $SHA \"$(date -Is)\" > ~/web-app-security-scanner/DEPLOYED_COMMIT"
# then on the VM:
docker build -t secscan-scanner:8.21.2 scanner-image/     # only if scanner-image/ changed
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
# Check what is deployed at any time:  cat ~/web-app-security-scanner/DEPLOYED_COMMIT
```

> **Provenance caveat (current VM state):** the live VM was first brought up by an `rsync` of the dev
> working tree (before this procedure existed), so its exact source is **unverifiable** — it is
> `develop @ 64b03d4` + the `feature/m8.9-vm-deploy` overlay/nginx changes as shipped. `DEPLOYED_COMMIT`
> was seeded to reflect that; the **next** `git archive` deploy restores verifiable provenance.

**Upgrade safety:**
- `up -d --build` recreates the app containers but **keeps the named `postgres-data` volume**, so all DB
  data (users, tenants, repos, scans, findings) survives. **Never** pass `-v` to `down`, and never
  `docker volume rm` the postgres volume — that is the only destructive step, and it wipes everything.
- Sessions are **in-memory** (`HttpSession`, not persisted). Recreating/restarting the **api** container
  **signs everyone out** — every logged-in user must sign in again. This is expected and harmless (no data
  loss); just avoid doing it mid-demo. The `ui`/`worker`/`postgres` restarts do not by themselves drop
  sessions, but an `up --build` that recreates `api` does.

## 4. Backups (installed 2026-11-12)

> Status 2026-11-12: **installed on the VM** — `~/secscan-backup.sh` (`chmod 700`) + the cron entry below,
> verified by one manual run (dump `~/secscan-backups/secscan-2026-11-12.sql.gz`, 112 K, `chmod 600`) and a
> restore test into a scratch DB. Still **TODO: copy dumps off the VM** (destination to be provided) and
> back up `.env` separately in a secrets vault.

**What's in a dump (why it's sensitive):** the DB holds AES-256-GCM-**encrypted** GitLab tokens and
HMAC-SHA256 secret-finding hashes — no plaintext secrets, but still sensitive, so dumps are `chmod 600`.
Crucially, **a dump is useless without the `.env` keys** (`SECSCAN_ENCRYPTION_KEY` decrypts the tokens,
`SECSCAN_FINGERPRINT_KEY` keys the hashes). Back `.env` up **separately and securely** (a secrets vault /
password manager, off the VM), **never** in the same place as the dumps.

**Size:** DB is ~87 MB today, but ~76 MB of that is `epss_score` (plus `kev_entry`) — global reference
data re-fetchable any time via the intel sync, so it need not be backed up. A business-data dump
(excluding those) is a few MB gzipped; a full dump is ~15–25 MB gzipped. 14-day retention ≈ <0.5 GB.

**Backup script** `~/secscan-backup.sh` (owner `ubuntu`, `chmod 700`). It dumps to a `.tmp`, verifies
the gzip, then atomically `mv`s into place; any failure removes the `.tmp`, keeps the previous good
dumps, writes an alert to the log and a `LAST_FAILED` marker, and exits non-zero:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/web-app-security-scanner
DEST=/home/ubuntu/secscan-backups           # OUTSIDE the docker volume
LOG=/home/ubuntu/secscan-backup.log
install -d -m 700 "$DEST"
umask 077
PC="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
OUT="$DEST/secscan-$(date +%F).sql.gz"
TMP="$OUT.tmp"
fail(){ rm -f "$TMP"; echo "$(date -Is) BACKUP FAILED: $1" | tee -a "$LOG" >&2; date -Is > "$DEST/LAST_FAILED"; exit 1; }
# Business data only (skip the re-syncable intel tables to keep it small):
$PC exec -T postgres pg_dump -U secscan --exclude-table-data='public.epss_score' \
  --exclude-table-data='public.kev_entry' secscan 2>>"$LOG" | gzip > "$TMP" || fail "pg_dump|gzip"
gzip -t "$TMP" 2>>"$LOG" || fail "gzip integrity check"
mv -f "$TMP" "$OUT"       || fail "mv into place"
rm -f "$DEST/LAST_FAILED"                                    # clear any prior failure marker
echo "$(date -Is) backup ok: $OUT ($(du -h "$OUT" | cut -f1))" >> "$LOG"
find "$DEST" -name 'secscan-*.sql.gz' -mtime +14 -delete    # 14-day retention (runs only after success)
# TODO (recommended): copy the newest dump OFF the VM, e.g.
#   rsync -a "$DEST/" backup-host:/backups/secscan/
```

**Cron** (`/etc/cron.d/secscan-backup`, root-owned `644`, daily 02:30 — **installed**):
```
30 2 * * *  ubuntu  /home/ubuntu/secscan-backup.sh >> /home/ubuntu/secscan-backup.log 2>&1
```

**Restore — ALWAYS test into a SCRATCH database, never the live `secscan`:**
```bash
PC="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
$PC exec -T postgres psql -U secscan -c "CREATE DATABASE secscan_restore_test;"
gunzip -c /home/ubuntu/secscan-backups/secscan-YYYY-MM-DD.sql.gz \
  | $PC exec -T postgres psql -U secscan -v ON_ERROR_STOP=1 -d secscan_restore_test
$PC exec -T postgres psql -U secscan -d secscan_restore_test -c "select count(*) from app_user;"  # sanity
$PC exec -T postgres psql -U secscan -c "DROP DATABASE secscan_restore_test;"                     # clean up
```
A real disaster-recovery restore into the live DB would restore `.env` first (for the keys), then the
dump into a freshly-created empty `secscan`, then **re-run the intel syncs** — the backup excludes the
`epss_score` and `kev_entry` table data, so KEV and EPSS are empty after a restore and MUST be
re-fetched; run all three for a clean refresh (EOL too):
```
# Pass only the email with -u; curl prompts for the password (so it never lands in shell history):
curl -u <operator-email> -X POST http://127.0.0.1/api/v1/admin/intel/kev/sync
curl -u <operator-email> -X POST http://127.0.0.1/api/v1/admin/intel/epss/sync
curl -u <operator-email> -X POST http://127.0.0.1/api/v1/admin/intel/eol/sync
```
Until they run, scores that depend on KEV/EPSS are stale (a re-score runs automatically after each sync).

**Restore verified (2026-11-12):** the day-0 dump restored into a scratch DB `secscan_restore_test` with
0 errors and matching row counts (`workspace`/`app_user`/`repository`/`sub_issue`), then the scratch DB
was dropped. The live DB was never touched.

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

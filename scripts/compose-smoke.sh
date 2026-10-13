#!/usr/bin/env bash
# Compose-level smoke test for the containerized stack (postgres + api + worker).
#
# SAFETY (why this script exists): a compose smoke test must NEVER run against the default compose project,
# because `docker compose down -v` there would delete the shared `postgres-data` volume and wipe your dev
# database. This script runs everything under a SEPARATE project name (`secscan-smoke`), which namespaces its
# volumes (`secscan-smoke_postgres-data`) and host resources away from the default project. The teardown is
# always scoped to `-p secscan-smoke`, so it can only ever remove the smoke project's own volume.
#
# It also maps api/postgres to EPHEMERAL host ports, so a running dev stack (ports 8080/5432) does not clash.
#
# Usage:
#   scripts/compose-smoke.sh          # build, bring the stack up, wait for api health, tear down (down -v)
#   KEEP_UP=1 scripts/compose-smoke.sh  # leave the smoke stack running for manual poking; tear down later with:
#                                       #   docker compose -p secscan-smoke down -v
set -euo pipefail

# Hard invariant: this script only ever addresses the smoke project. Never change this to the default project.
readonly SMOKE_PROJECT="secscan-smoke"

cd "$(dirname "$0")/.."

# An inline override that (a) excludes the ui and (b) gives postgres/api random loopback host ports so the smoke
# stack cannot collide with a running dev stack on 5432/8080.
OVERRIDE="$(mktemp -t secscan-smoke-override.XXXXXX.yml)"
cat >"$OVERRIDE" <<'YAML'
services:
  postgres:
    ports: ["127.0.0.1:0:5432"]
  api:
    ports: ["127.0.0.1:0:8080"]
YAML

compose() { docker compose -p "$SMOKE_PROJECT" -f docker-compose.yml -f "$OVERRIDE" "$@"; }

cleanup() {
  echo "==> Tearing down the '$SMOKE_PROJECT' project (its volume only; the default project is untouched)"
  # Scoped to -p secscan-smoke: removes secscan-smoke_postgres-data, never the default postgres-data.
  compose down -v --remove-orphans || true
  rm -f "$OVERRIDE"
}
if [[ "${KEEP_UP:-0}" != "1" ]]; then
  trap cleanup EXIT
fi

echo "==> Building and starting postgres + api + worker under project '$SMOKE_PROJECT'"
compose up --build -d postgres api worker

echo "==> Waiting for the api to report healthy (compose healthcheck)"
deadline=$(( $(date +%s) + 180 ))
until [[ "$(compose ps api --format '{{.Health}}' 2>/dev/null || true)" == "healthy" ]]; do
  if [[ "$(date +%s)" -ge "$deadline" ]]; then
    echo "!! api did not become healthy in time; recent api logs:" >&2
    compose logs --tail=50 api >&2 || true
    exit 1
  fi
  sleep 3
done

echo "==> api is healthy. worker status:"
compose ps worker

if [[ "${KEEP_UP:-0}" == "1" ]]; then
  echo "==> KEEP_UP=1: leaving the stack running. Tear down later with:"
  echo "    docker compose -p $SMOKE_PROJECT down -v"
fi

echo "==> Smoke OK"

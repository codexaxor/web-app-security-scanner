#!/usr/bin/env node
// One command for the whole E2E cycle (harness spec): build images, bring up the isolated `secscan-e2e`
// stack (postgres + api + ui; NO worker/scanner), wait healthy, seed, run Playwright INSIDE the pinned
// Playwright image (non-root, no docker socket, ui mounted read-only except artifacts), then tear everything
// down with `-v`. Idempotent and leaves nothing running. Secrets (db password, encryption/fingerprint keys,
// bootstrap admin password) are generated PER RUN into .auth/ (gitignored, chmod 600) and never printed.
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { mkdirSync, writeFileSync, chmodSync, rmSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const E2E_DIR = dirname(fileURLToPath(import.meta.url));
const UI_DIR = resolve(E2E_DIR, "..");
const AUTH_DIR = resolve(E2E_DIR, ".auth");
const ARTIFACTS = resolve(E2E_DIR, "artifacts");
const ENV_FILE = resolve(AUTH_DIR, "e2e.env");
const COMPOSE_FILE = resolve(E2E_DIR, "docker-compose.e2e.yml");
const PROJECT = "secscan-e2e";
const NETWORK = `${PROJECT}_default`;
const API_HEALTH = "http://127.0.0.1:8091/actuator/health";
const UI_URL = "http://127.0.0.1:5274/";
const PW_IMAGE =
  "mcr.microsoft.com/playwright:v1.64.1-noble@sha256:7852dc5a7d72f5fe458a597d690f6c42fbc8e9e26ddfa95f8e6ef552558d9bbf";

const b64 = () => randomBytes(32).toString("base64");
const pw = () => randomBytes(24).toString("base64url");

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { stdio: "inherit", ...opts });
  if (r.status !== 0 && !opts.allowFail) {
    throw new Error(`${cmd} ${args.join(" ")} exited ${r.status}`);
  }
  return r.status ?? 1;
}

const compose = (args, opts = {}) =>
  run("docker", ["compose", "-p", PROJECT, "--env-file", ENV_FILE, "-f", COMPOSE_FILE, ...args], opts);

async function waitFor(label, fn, timeoutMs = 240000) {
  const start = Date.now();
  for (;;) {
    try {
      if (await fn()) {
        console.log(`[e2e] ${label} ready`);
        return;
      }
    } catch {
      /* not ready */
    }
    if (Date.now() - start > timeoutMs) throw new Error(`[e2e] timed out waiting for ${label}`);
    await new Promise((r) => setTimeout(r, 3000));
  }
}

async function main() {
  mkdirSync(AUTH_DIR, { recursive: true, mode: 0o700 });
  mkdirSync(ARTIFACTS, { recursive: true });
  rmSync(resolve(AUTH_DIR, "e2e.env"), { force: true });

  const env = {
    E2E_POSTGRES_PASSWORD: pw(),
    E2E_ENCRYPTION_KEY: b64(),
    E2E_FINGERPRINT_KEY: b64(),
    E2E_ADMIN_EMAIL: "admin@secscan.local",
    E2E_ADMIN_PASSWORD: pw(),
  };
  writeFileSync(ENV_FILE, Object.entries(env).map(([k, v]) => `${k}=${v}`).join("\n") + "\n", { mode: 0o600 });
  chmodSync(ENV_FILE, 0o600);

  let pwStatus = 1;
  try {
    console.log("[e2e] tearing down any prior stack");
    compose(["down", "-v", "--remove-orphans"], { allowFail: true });

    console.log("[e2e] building + starting postgres + api + ui");
    compose(["up", "-d", "--build"]);

    await waitFor("api health", async () => {
      const r = await fetch(API_HEALTH);
      return r.ok && (await r.json()).status === "UP";
    });
    await waitFor("ui", async () => (await fetch(UI_URL)).ok);

    console.log("[e2e] seeding");
    run(
      "docker",
      ["compose", "-p", PROJECT, "--env-file", ENV_FILE, "-f", COMPOSE_FILE, "exec", "-T",
        "-e", `PGPASSWORD=${env.E2E_POSTGRES_PASSWORD}`, "postgres",
        "psql", "-v", "ON_ERROR_STOP=1", "-U", "secscan", "-d", "secscan", "-f", "/dev/stdin"],
      { stdio: ["pipe", "inherit", "inherit"], input: readFileSync(resolve(E2E_DIR, "fixtures", "seed.sql")) },
    );

    // The Playwright container runs as pwuser (uid 1001); make the artifacts bind writable by it.
    chmodSync(ARTIFACTS, 0o777);

    console.log("[e2e] running Playwright (in Docker)");
    // Run the local Playwright binary directly (NOT `npx`, which would try to write an npm cache into the
    // read-only mount). The image provides the browsers; @playwright/test comes from the mounted node_modules.
    pwStatus = run(
      "docker",
      ["run", "--rm", "--network", NETWORK, "--user", "pwuser", "--init",
        "-v", `${UI_DIR}:/ui:ro`, "-v", `${ARTIFACTS}:/ui/e2e/artifacts`,
        "-w", "/ui/e2e",
        "-e", "E2E_BASE_URL=http://ui:80",
        "-e", `E2E_ADMIN_EMAIL=${env.E2E_ADMIN_EMAIL}`, "-e", `E2E_ADMIN_PASSWORD=${env.E2E_ADMIN_PASSWORD}`,
        "-e", "PGHOST=postgres", "-e", "PGPORT=5432", "-e", "PGUSER=secscan",
        "-e", `PGPASSWORD=${env.E2E_POSTGRES_PASSWORD}`, "-e", "PGDATABASE=secscan",
        PW_IMAGE, "node", "/ui/node_modules/@playwright/test/cli.js", "test", "--config=playwright.config.ts"],
      { cwd: UI_DIR, allowFail: true },
    );
  } finally {
    console.log("[e2e] tearing down");
    compose(["down", "-v", "--remove-orphans"], { allowFail: true });
    rmSync(ENV_FILE, { force: true });
  }

  if (pwStatus !== 0) {
    console.error(`[e2e] Playwright failed (exit ${pwStatus})`);
    process.exit(pwStatus);
  }
  console.log("[e2e] all green");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

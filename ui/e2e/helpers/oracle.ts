import { type APIRequestContext, expect } from "@playwright/test";
import pg from "pg";

// The oracle: every count shown on screen is asserted equal to (1) the API response for the same filter and
// (2) a direct SQL count against the e2e Postgres (harness spec). API calls reuse the per-run bootstrap admin
// credentials; SQL connects to the seeded e2e database. Grows as screens land (S4+).

const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? "admin@example.com";
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "";

export function authHeader(): string {
  return "Basic " + Buffer.from(`${ADMIN_EMAIL}:${ADMIN_PASSWORD}`).toString("base64");
}

/** Calls the API with admin Basic auth and returns the parsed JSON body. */
export async function apiGet<T = unknown>(request: APIRequestContext, path: string): Promise<T> {
  const res = await request.get(path, { headers: { Authorization: authHeader() } });
  expect(res.ok(), `GET ${path} → ${res.status()}`).toBeTruthy();
  return (await res.json()) as T;
}

let pool: pg.Pool | null = null;
function db(): pg.Pool {
  if (!pool) {
    pool = new pg.Pool({
      host: process.env.PGHOST ?? "postgres",
      port: Number(process.env.PGPORT ?? "5432"),
      user: process.env.PGUSER ?? "secscan",
      password: process.env.PGPASSWORD ?? "",
      database: process.env.PGDATABASE ?? "secscan",
      max: 2,
    });
  }
  return pool;
}

/** Runs a single-value count/aggregate query against the e2e DB and returns it as a number. */
export async function sqlCount(text: string, params: unknown[] = []): Promise<number> {
  const r = await db().query(text, params);
  return Number(r.rows[0]?.[Object.keys(r.rows[0])[0]] ?? 0);
}

/** Runs a write (insert/update/delete) against the e2e DB — used by the live-status spec to drive a scan. */
export async function sqlExec(text: string, params: unknown[] = []): Promise<void> {
  await db().query(text, params);
}

export async function closeDb(): Promise<void> {
  if (pool) {
    await pool.end();
    pool = null;
  }
}

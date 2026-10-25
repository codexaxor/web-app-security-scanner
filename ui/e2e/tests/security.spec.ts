import { test, expect } from "../fixtures/test-base";

// OD-13c + change 4: the security header set is present (with `always`) on the shell, deep links (SPA
// fallback), hashed assets and 404s; /api/* carries X-Content-Type-Options nosniff; the JSON 401 has no
// WWW-Authenticate (OD-13b / change 9).
const CSP =
  "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

function assertSecurityHeaders(headers: Record<string, string>) {
  expect(headers["content-security-policy"]).toBe(CSP);
  expect(headers["x-content-type-options"]).toBe("nosniff");
  expect(headers["referrer-policy"]).toBe("no-referrer");
}

test("shell / carries the full header set and no-store", async ({ request }) => {
  const res = await request.get("/");
  expect(res.status()).toBe(200);
  assertSecurityHeaders(res.headers());
  expect(res.headers()["cache-control"]).toContain("no-store");
});

test("deep link (SPA fallback) carries the header set", async ({ request }) => {
  const res = await request.get("/feed/some-id");
  expect(res.status()).toBe(200);
  assertSecurityHeaders(res.headers());
  expect(res.headers()["cache-control"]).toContain("no-store");
});

test("hashed asset carries the header set and is cacheable", async ({ request }) => {
  const html = await (await request.get("/")).text();
  const m = html.match(/\/assets\/[^"']+\.js/);
  expect(m, "no hashed asset found in index.html").not.toBeNull();
  const res = await request.get(m![0]);
  expect(res.status()).toBe(200);
  assertSecurityHeaders(res.headers());
  expect(res.headers()["cache-control"]).toContain("immutable");
});

test("404 carries the header set", async ({ request }) => {
  const res = await request.get("/assets/does-not-exist-" + Date.now() + ".js");
  expect(res.status()).toBe(404);
  assertSecurityHeaders(res.headers());
});

test("/api/* carries nosniff and the JSON 401 has no WWW-Authenticate", async ({ request }) => {
  const res = await request.get("/api/v1/repositories?page=0&size=1");
  expect(res.status()).toBe(401);
  expect(res.headers()["x-content-type-options"]).toBe("nosniff");
  expect(res.headers()["www-authenticate"]).toBeUndefined();
});

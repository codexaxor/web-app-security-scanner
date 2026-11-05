import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api";
import { RateLimitedError, loginOutcome, operatorApi, signUp, validateSignUp } from "./signups";

type Call = { url: string; method?: string; headers: Record<string, string>; credentials?: string; body?: string };

function respond(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function stubFetch(calls: Call[], handler: (url: string) => Response) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: { method?: string; headers?: Record<string, string>; credentials?: string; body?: string }) => {
      calls.push({ url, method: init?.method, headers: init?.headers ?? {}, credentials: init?.credentials, body: init?.body });
      return handler(url);
    }),
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("validateSignUp (M8.10, mirrors the server rules)", () => {
  const ok = { email: "a@b.example", password: "twelve-chars", workspaceName: "Acme" };

  it("accepts a well-formed request", () => {
    expect(validateSignUp(ok)).toEqual({});
  });

  it("rejects a bad email, a short password and an empty or control-char workspace name", () => {
    expect(validateSignUp({ ...ok, email: "admin" }).email).toBeDefined();
    expect(validateSignUp({ ...ok, password: "elevenchars" }).password).toMatch(/12/);
    expect(validateSignUp({ ...ok, workspaceName: "  " }).workspaceName).toBeDefined();
    expect(validateSignUp({ ...ok, workspaceName: "a\u0007b" }).workspaceName).toBeDefined();
    expect(validateSignUp({ ...ok, workspaceName: "x".repeat(81) }).workspaceName).toBeDefined();
  });

  it("counts code points for the minimum and UTF-8 bytes for the bcrypt maximum", () => {
    expect(validateSignUp({ ...ok, password: "🔒".repeat(11) }).password).toMatch(/12/); // 22 UTF-16 units, 11 chars
    expect(validateSignUp({ ...ok, password: "🔒".repeat(12) }).password).toBeUndefined(); // 48 bytes
    expect(validateSignUp({ ...ok, password: "🔒".repeat(19) }).password).toMatch(/bytes/); // 76 bytes > 72
  });
});

describe("signUp (D-098: one answer, no enumeration)", () => {
  it("posts JSON same-origin with no Authorization and returns the 202 body", async () => {
    const calls: Call[] = [];
    stubFetch(calls, () => respond({ status: "RECEIVED", message: "If the address can be used…" }, 202));
    const res = await signUp({ email: " a@b.example ", password: "twelve-chars", workspaceName: " Acme " });
    expect(res.status).toBe("RECEIVED");
    expect(calls[0]).toMatchObject({ url: "/api/v1/auth/signup", method: "POST", credentials: "same-origin" });
    expect(calls[0].headers.Authorization).toBeUndefined();
    expect(JSON.parse(calls[0].body ?? "{}")).toMatchObject({ email: "a@b.example", workspaceName: "Acme" });
  });

  it("maps 429 to RateLimitedError with Retry-After and 400 to ApiError with the server code", async () => {
    stubFetch([], () => respond({ error: "RATE_LIMITED", message: "Too many" }, 429, { "Retry-After": "600" }));
    const limited = await signUp({ email: "a@b.example", password: "twelve-chars", workspaceName: "A" }).catch((e) => e);
    expect(limited).toBeInstanceOf(RateLimitedError);
    expect((limited as RateLimitedError).retryAfterSeconds).toBe(600);

    stubFetch([], () => respond({ error: "VALIDATION_FAILED", message: "email: must be valid" }, 400));
    const invalid = await signUp({ email: "x", password: "twelve-chars", workspaceName: "A" }).catch((e) => e);
    expect(invalid).toBeInstanceOf(ApiError);
    expect((invalid as ApiError).code).toBe("VALIDATION_FAILED");
  });
});

describe("loginOutcome (M8.10 login status codes)", () => {
  it("distinguishes wrong credentials, pending, inactive and rate-limited", () => {
    expect(loginOutcome(401, null)).toBe("wrong-credentials");
    expect(loginOutcome(403, "ACCOUNT_PENDING")).toBe("pending");
    expect(loginOutcome(403, "ACCOUNT_INACTIVE")).toBe("inactive");
    expect(loginOutcome(429, "RATE_LIMITED")).toBe("rate-limited");
    expect(loginOutcome(403, "FORBIDDEN")).toBe("error");
  });
});

describe("operatorApi (D-092 session + CSRF)", () => {
  it("GETs carry no CSRF token; unsafe calls echo the session's token; never an Authorization header", async () => {
    const calls: Call[] = [];
    stubFetch(calls, (url) =>
      url === "/api/v1/auth/session"
        ? respond({ username: "op@x.example", demoMode: false, csrfToken: "tok-op" })
        : url.startsWith("/api/v1/admin/sign-ups?")
          ? respond({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
          : respond({ id: "s1", email: "n@x.example", status: "approved" }),
    );

    await operatorApi.listSignUps("pending", 0, 50);
    await operatorApi.approveSignUp("s1");

    const list = calls.find((c) => c.url.startsWith("/api/v1/admin/sign-ups?"))!;
    expect(list.url).toBe("/api/v1/admin/sign-ups?status=pending&page=0&size=50");
    expect(list.headers["X-CSRF-TOKEN"]).toBeUndefined();
    const approve = calls.find((c) => c.url === "/api/v1/admin/sign-ups/s1/approve")!;
    expect(approve.method).toBe("POST");
    expect(approve.headers["X-CSRF-TOKEN"]).toBe("tok-op");
    expect(calls.every((c) => c.headers.Authorization === undefined && c.credentials === "same-origin")).toBe(
      true,
    );
  });

  it("surfaces the operator-guard 409 as an ApiError with its code", async () => {
    stubFetch([], (url) =>
      url === "/api/v1/auth/session"
        ? respond({ username: "op", demoMode: false, csrfToken: "t" })
        : respond({ error: "CANNOT_DISABLE_OPERATOR", message: "The operator cannot be disabled." }, 409),
    );
    const err = await operatorApi.disableUser("u1").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).code).toBe("CANNOT_DISABLE_OPERATOR");
  });
});

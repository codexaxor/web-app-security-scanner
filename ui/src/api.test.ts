import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  AccountInactiveError,
  AccountPendingError,
  ApiError,
  AuthRequiredError,
  RateLimitedError,
  api,
  fetchSession,
  login,
  setUnauthorizedHandler,
  signUp,
} from "./api";

// D-092: the SPA authenticates with a server session cookie + an in-memory CSRF token — never an Authorization
// header. These prove: login stores the CSRF token and sends no Authorization; unsafe requests echo the token
// in X-CSRF-TOKEN while GETs do not; and all requests go out with credentials: "same-origin" (the cookie).
describe("session + CSRF auth (D-092)", () => {
  beforeEach(() => {
    // reset the in-memory CSRF token by signing out of any prior test session
    vi.restoreAllMocks();
  });
  afterEach(() => vi.restoreAllMocks());

  function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  }

  type Captured = { url: string; method?: string; headers: Record<string, string>; credentials?: string };

  function capturing(calls: Captured[], body: unknown, status = 200) {
    return vi.fn(async (url: string, init: { method?: string; headers?: Record<string, string>; credentials?: string }) => {
      calls.push({ url, method: init?.method, headers: init?.headers ?? {}, credentials: init?.credentials });
      return jsonResponse(body, status); // fresh Response per call (a body can only be read once)
    });
  }

  it("login posts JSON, sends no Authorization header, and returns the session info", async () => {
    const calls: Captured[] = [];
    vi.stubGlobal(
      "fetch",
      capturing(calls, { username: "admin@example.com", demoMode: false, csrfToken: "tok-1" }),
    );

    const info = await login("admin@example.com", "pw");

    expect(info).toMatchObject({ username: "admin@example.com", demoMode: false });
    expect(calls[0].url).toBe("/api/v1/auth/login");
    expect(calls[0].method).toBe("POST");
    expect(calls[0].credentials).toBe("same-origin");
    expect(calls[0].headers.Authorization).toBeUndefined();
  });

  it("sends the CSRF token on unsafe requests but not on GETs, and never an Authorization header", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ username: "a", demoMode: false, csrfToken: "tok-9" })));
    await login("a", "b"); // stores tok-9 in memory

    const calls: Captured[] = [];
    vi.stubGlobal(
      "fetch",
      capturing(calls, { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0 }),
    );

    await api.listRepositories({ size: 1 }); // GET
    await api.startScan("00000000-0000-0000-0000-000000000000"); // POST

    const get = calls[0];
    const post = calls[1];
    expect(get.method).toBe("GET");
    expect(get.headers["X-CSRF-TOKEN"]).toBeUndefined();
    expect(post.method).toBe("POST");
    expect(post.headers["X-CSRF-TOKEN"]).toBe("tok-9");
    expect(get.credentials).toBe("same-origin");
    expect(get.headers.Authorization).toBeUndefined();
    expect(post.headers.Authorization).toBeUndefined();
  });

  it("fetchSession returns null on a 401 (not signed in)", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 401 })));
    expect(await fetchSession()).toBeNull();
  });

  it("login throws AuthRequiredError on a 401 without clearing global state", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 401 })));
    await expect(login("x", "y")).rejects.toBeInstanceOf(AuthRequiredError);
  });
});

// SF-3: request() must invoke the registered unauthorized handler on ANY 401 (not only react-query-driven calls),
// so direct callers (ConnectGitLab / SelectRepositories) drop to the sign-in gate too.
describe("unauthorized handling in request() (SF-3)", () => {
  afterEach(() => {
    setUnauthorizedHandler(() => {});
    vi.restoreAllMocks();
  });

  it("fires the unauthorized handler once and throws AuthRequiredError on a 401", async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 401 })));

    await expect(api.listRepositories({ size: 1 })).rejects.toBeInstanceOf(AuthRequiredError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("does not fire the handler on a non-JSON error body, and surfaces a clean ApiError (N-4)", async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("<html>502 Bad Gateway</html>", { status: 502 })),
    );

    await expect(api.listRepositories({ size: 1 })).rejects.toMatchObject({ message: "Request failed (502)" });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});

// M8.10 tenancy client: login status codes, public sign-up, and the operator API through request() (so the
// session's CSRF token is echoed and nothing new handles credentials).
describe("M8.10 login, sign-up and operator API", () => {
  afterEach(() => vi.unstubAllGlobals());

  type Captured = { url: string; method?: string; headers: Record<string, string>; credentials?: string };
  function capturing(calls: Captured[], body: unknown) {
    return vi.fn(async (url: string, init: { method?: string; headers?: Record<string, string>; credentials?: string }) => {
      calls.push({ url, method: init?.method, headers: init?.headers ?? {}, credentials: init?.credentials });
      return json(body, 200);
    });
  }

  function json(body: unknown, status: number, headers: Record<string, string> = {}): Response {
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
  }

  it("login maps 403 ACCOUNT_PENDING / ACCOUNT_INACTIVE and 429 + Retry-After to typed errors", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "ACCOUNT_PENDING", message: "x" }, 403)));
    await expect(login("a@b.example", "pw")).rejects.toBeInstanceOf(AccountPendingError);

    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "ACCOUNT_INACTIVE", message: "x" }, 403)));
    await expect(login("a@b.example", "pw")).rejects.toBeInstanceOf(AccountInactiveError);

    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "RATE_LIMITED", message: "x" }, 429, { "Retry-After": "840" })));
    const limited = await login("a@b.example", "pw").catch((e) => e);
    expect(limited).toBeInstanceOf(RateLimitedError);
    expect((limited as RateLimitedError).retryAfterSeconds).toBe(840);
  });

  it("an unexpected 403 code is a generic ApiError, never a pending/inactive claim", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "FORBIDDEN", message: "x" }, 403)));
    const err = await login("a@b.example", "pw").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(AccountPendingError);
    expect(err).not.toBeInstanceOf(AccountInactiveError);
  });

  it("signUp posts trimmed JSON same-origin with no Authorization or CSRF header and returns the 202 body", async () => {
    const calls: Captured[] = [];
    const fetchMock = vi.fn(async (url: string, init: { method?: string; headers?: Record<string, string>; credentials?: string; body?: string }) => {
      calls.push({ url, method: init.method, headers: init.headers ?? {}, credentials: init.credentials });
      expect(JSON.parse(init.body ?? "{}")).toEqual({ email: "a@b.example", password: "pw-twelve-chars", workspaceName: "Acme" });
      return json({ status: "RECEIVED", message: "received" }, 202);
    });
    vi.stubGlobal("fetch", fetchMock);
    const res = await signUp({ email: " a@b.example ", password: "pw-twelve-chars", workspaceName: " Acme " });
    expect(res.status).toBe("RECEIVED");
    expect(calls[0]).toMatchObject({ url: "/api/v1/auth/signup", method: "POST", credentials: "same-origin" });
    expect(calls[0].headers.Authorization).toBeUndefined();
    expect(calls[0].headers["X-CSRF-TOKEN"]).toBeUndefined();
  });

  it("signUp maps 429 to RateLimitedError and 400 to ApiError with the server code", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "RATE_LIMITED", message: "slow" }, 429, { "Retry-After": "60" })));
    const limited = await signUp({ email: "a@b.example", password: "x", workspaceName: "A" }).catch((e) => e);
    expect((limited as RateLimitedError).retryAfterSeconds).toBe(60);

    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "VALIDATION_FAILED", message: "email" }, 400)));
    const invalid = await signUp({ email: "x", password: "x", workspaceName: "A" }).catch((e) => e);
    expect((invalid as ApiError).code).toBe("VALIDATION_FAILED");
  });

  it("operator calls go through request(): CSRF on POSTs only, never Authorization; 409 keeps its code", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ username: "op", demoMode: false, csrfToken: "tok-op", workspaceName: "Default", operator: true }, 200)));
    await login("op@x.example", "pw"); // stores tok-op in memory

    const calls: Captured[] = [];
    vi.stubGlobal("fetch", capturing(calls, { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    await api.listSignUps("pending", 0, 20);
    await api.approveSignUp("s1");
    await api.listUsers(null, 1, 20);
    expect(calls[0].url).toBe("/api/v1/admin/sign-ups?status=pending&page=0&size=20");
    expect(calls[0].headers["X-CSRF-TOKEN"]).toBeUndefined();
    expect(calls[1]).toMatchObject({ url: "/api/v1/admin/sign-ups/s1/approve", method: "POST" });
    expect(calls[1].headers["X-CSRF-TOKEN"]).toBe("tok-op");
    expect(calls[2].url).toBe("/api/v1/admin/users?page=1&size=20");
    expect(calls.every((c) => c.headers.Authorization === undefined)).toBe(true);

    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "CANNOT_DISABLE_OPERATOR", message: "no" }, 409)));
    const err = await api.disableUser("u1").catch((e) => e);
    expect((err as ApiError).code).toBe("CANNOT_DISABLE_OPERATOR");
  });

  it("request() turns any 429 into RateLimitedError with Retry-After", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "RATE_LIMITED", message: "slow down" }, 429, { "Retry-After": "30" })));
    const err = await api.listUsers(null, 0, 20).catch((e) => e);
    expect(err).toBeInstanceOf(RateLimitedError);
    expect((err as RateLimitedError).retryAfterSeconds).toBe(30);
  });
});

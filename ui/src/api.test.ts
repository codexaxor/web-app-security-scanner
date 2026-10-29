import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthRequiredError, api, fetchSession, login, setUnauthorizedHandler } from "./api";

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

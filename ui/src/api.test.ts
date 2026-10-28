import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  AuthRequiredError,
  api,
  clearCredentials,
  hasCredentials,
  setCredentials,
  setUnauthorizedHandler,
} from "./api";

// A minimal smoke test proving the vitest toolchain runs and the in-memory credential store behaves
// (OD-13a: credentials live only in memory). Screen-level behaviour is covered by Playwright e2e.
describe("in-memory credentials", () => {
  beforeEach(() => clearCredentials());

  it("starts empty", () => {
    expect(hasCredentials()).toBe(false);
  });

  it("holds a credential after setCredentials and drops it on clear", () => {
    setCredentials("admin@example.com", "pw");
    expect(hasCredentials()).toBe(true);
    clearCredentials();
    expect(hasCredentials()).toBe(false);
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

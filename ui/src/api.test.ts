import { describe, it, expect, beforeEach } from "vitest";
import { clearCredentials, hasCredentials, setCredentials } from "./api";

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

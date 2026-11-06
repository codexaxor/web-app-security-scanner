import { describe, expect, it } from "vitest";
import { retryText, validateSignUp } from "./signupValidation";

describe("validateSignUp (M8.10, mirrors SignupService)", () => {
  const ok = { email: "a@b.example", password: "twelve-chars", workspaceName: "Acme" };

  it("accepts a well-formed request", () => {
    expect(validateSignUp(ok)).toEqual({});
  });

  it("rejects a non-email (e.g. a demo-style username), a short password and a bad workspace name", () => {
    expect(validateSignUp({ ...ok, email: "admin" }).email).toBeDefined();
    expect(validateSignUp({ ...ok, password: "elevenchars" }).password).toMatch(/12/);
    expect(validateSignUp({ ...ok, workspaceName: "  " }).workspaceName).toBeDefined();
    expect(validateSignUp({ ...ok, workspaceName: "a\u0007b" }).workspaceName).toBeDefined();
    expect(validateSignUp({ ...ok, workspaceName: "x".repeat(81) }).workspaceName).toBeDefined();
    expect(validateSignUp({ ...ok, workspaceName: "x".repeat(80) }).workspaceName).toBeUndefined();
  });

  it("rejects a non-ASCII email and the reserved workspace name Default (any case) — review N-3/N-6", () => {
    expect(validateSignUp({ ...ok, email: "\u00fcser@b.example" }).email).toMatch(/ASCII/);
    expect(validateSignUp({ ...ok, workspaceName: "Default" }).workspaceName).toMatch(/reserved/);
    expect(validateSignUp({ ...ok, workspaceName: "  dEFAULT " }).workspaceName).toMatch(/reserved/);
    expect(validateSignUp({ ...ok, workspaceName: "Default Two" }).workspaceName).toBeUndefined();
  });

  it("counts code points for the minimum and UTF-8 bytes for the bcrypt maximum", () => {
    expect(validateSignUp({ ...ok, password: "🔒".repeat(11) }).password).toMatch(/12/); // 22 UTF-16 units
    expect(validateSignUp({ ...ok, password: "🔒".repeat(12) }).password).toBeUndefined(); // 48 bytes
    expect(validateSignUp({ ...ok, password: "🔒".repeat(19) }).password).toMatch(/bytes/); // 76 bytes
  });
});

describe("retryText (429 Retry-After → minutes)", () => {
  it("rounds up to whole minutes, at least one", () => {
    expect(retryText(900)).toBe("Try again in 15 minutes.");
    expect(retryText(61)).toBe("Try again in 2 minutes.");
    expect(retryText(5)).toBe("Try again in 1 minute.");
    expect(retryText(null)).toBe("Try again later.");
  });
});

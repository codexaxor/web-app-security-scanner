import { describe, expect, it } from "vitest";
import { secretFixGuidance } from "./secretGuidance";

describe("secretFixGuidance", () => {
  it("gives category-specific guidance for each known secret category", () => {
    expect(secretFixGuidance("Cloud credential")).toContain("provider console");
    expect(secretFixGuidance("API token")).toContain("issuing service");
    expect(secretFixGuidance("Private key")).toContain("rotate the key pair");
    expect(secretFixGuidance("Password")).toContain("Change the password");
  });

  it("falls back to generic revoke/rotate guidance for an unknown or null category", () => {
    for (const c of [null, undefined, "Secret", "Something else"]) {
      expect(secretFixGuidance(c)).toContain("Revoke or rotate the exposed secret first");
    }
  });

  it("always notes that removal does not clear git history", () => {
    for (const c of [null, "Cloud credential", "API token", "Private key", "Password"]) {
      expect(secretFixGuidance(c)).toContain("git history");
    }
  });
});

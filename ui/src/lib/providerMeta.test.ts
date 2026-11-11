import { describe, expect, it } from "vitest";
import { providerMeta, targetLabel } from "./providerMeta";

describe("providerMeta", () => {
  it("maps known providers to a label and monogram", () => {
    expect(providerMeta("github")).toEqual({ id: "github", label: "GitHub", monogram: "GH" });
    expect(providerMeta("gitlab")).toEqual({ id: "gitlab", label: "GitLab", monogram: "GL" });
  });

  it("is case-insensitive", () => {
    expect(providerMeta("GitHub").id).toBe("github");
  });

  it("falls back for an unknown or null provider", () => {
    expect(providerMeta(null).id).toBe("other");
    expect(providerMeta(undefined).label).toBe("Unknown");
    expect(providerMeta("bitbucket").label).toBe("bitbucket");
  });

  it("labels target kinds", () => {
    expect(targetLabel("group")).toBe("Group");
    expect(targetLabel("owner")).toBe("Owner");
    expect(targetLabel("repo")).toBe("Repository");
  });
});

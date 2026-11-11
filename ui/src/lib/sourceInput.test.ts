import { describe, expect, it } from "vitest";
import { isParseError, kindForPath, parseSourceUrl, type ParsedSource } from "./sourceInput";

function ok(raw: string): ParsedSource {
  const r = parseSourceUrl(raw);
  if (isParseError(r)) {
    throw new Error(`expected a parse, got error: ${r.error}`);
  }
  return r;
}

describe("source input parser (M8.8 item 7)", () => {
  it("detects a gitlab.com group/subgroup/project", () => {
    expect(ok("https://gitlab.com/group/subgroup/project")).toMatchObject({
      provider: "gitlab",
      host: "gitlab.com",
      builtinHost: true,
      path: "group/subgroup/project",
      kind: "project",
    });
  });

  it("detects a gitlab.com single-segment group", () => {
    expect(ok("https://gitlab.com/mygroup")).toMatchObject({ kind: "group", path: "mygroup" });
  });

  it("detects a self-managed GitLab host (not built-in)", () => {
    expect(ok("https://gitlab.celloscope.net/team/app")).toMatchObject({
      provider: "gitlab",
      host: "gitlab.celloscope.net",
      builtinHost: false,
      path: "team/app",
      kind: "project",
    });
  });

  it("treats any non-github, non-gitlab.com host as self-managed GitLab (the server allowlist is the gate)", () => {
    expect(ok("https://git.example.org/x/y")).toMatchObject({
      provider: "gitlab",
      host: "git.example.org",
      builtinHost: false,
    });
  });

  it("detects a GitHub owner", () => {
    expect(ok("https://github.com/octocat")).toMatchObject({
      provider: "github",
      host: "github.com",
      builtinHost: true,
      path: "octocat",
      kind: "owner",
    });
  });

  it("detects a GitHub repository", () => {
    expect(ok("https://github.com/octocat/Hello-World")).toMatchObject({
      provider: "github",
      path: "octocat/Hello-World",
      kind: "repo",
    });
  });

  it("strips a trailing .git", () => {
    expect(ok("https://github.com/octocat/Hello-World.git").path).toBe("octocat/Hello-World");
  });

  it("strips a GitLab /-/tree/... suffix and marks it a project", () => {
    expect(ok("https://gitlab.com/group/proj/-/tree/main/src")).toMatchObject({
      path: "group/proj",
      kind: "project",
    });
  });

  it("rejects a non-https (http) URL", () => {
    const r = parseSourceUrl("http://github.com/octocat/Hello-World");
    expect(isParseError(r)).toBe(true);
    if (isParseError(r)) {
      expect(r.error).toContain("https");
    }
  });

  it("rejects a bare word that is not a URL", () => {
    expect(isParseError(parseSourceUrl("not a url"))).toBe(true);
  });

  it("infers kind for a bare path once the provider is known", () => {
    expect(kindForPath("github", "octocat")).toBe("owner");
    expect(kindForPath("github", "octocat/Hello-World")).toBe("repo");
    expect(kindForPath("gitlab", "mygroup")).toBe("group");
    expect(kindForPath("gitlab", "group/subgroup/project")).toBe("project");
  });
});

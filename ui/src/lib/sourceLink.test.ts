import { describe, expect, it } from "vitest";
import { sourceCommitLink, sourceFileLink, type SourceRef } from "./sourceLink";

const SHA = "a".repeat(40);
const ref: SourceRef = { host: "gitlab.celloscope.net", projectPath: "acme/core-keycloak", commitSha: SHA };

describe("sourceFileLink / sourceCommitLink", () => {
  it("builds a GitLab blob link with a line anchor", () => {
    expect(sourceFileLink(ref, "src/main/App.java", 42)).toBe(
      `https://gitlab.celloscope.net/acme/core-keycloak/-/blob/${SHA}/src/main/App.java#L42`,
    );
  });

  it("builds a commit link", () => {
    expect(sourceCommitLink(ref)).toBe(`https://gitlab.celloscope.net/acme/core-keycloak/-/commit/${SHA}`);
  });

  it("supports a self-managed host with a non-default port", () => {
    const link = sourceFileLink({ ...ref, host: "git.internal:8443" }, "a.txt", 1);
    expect(link).toBe(`https://git.internal:8443/acme/core-keycloak/-/blob/${SHA}/a.txt#L1`);
  });

  it("URL-encodes spaces, # and ? per path segment", () => {
    const link = sourceFileLink(ref, "dir name/a b#c?.txt", null);
    expect(link).toBe(
      `https://gitlab.celloscope.net/acme/core-keycloak/-/blob/${SHA}/dir%20name/a%20b%23c%3F.txt`,
    );
    expect(link).not.toContain(" ");
    expect(link).not.toContain("#L");
  });

  it("rejects a non-hex or short SHA", () => {
    expect(sourceFileLink({ ...ref, commitSha: "deadbeef" }, "a.txt", 1)).toBeNull();
    expect(sourceFileLink({ ...ref, commitSha: "g".repeat(40) }, "a.txt", 1)).toBeNull();
    expect(sourceCommitLink({ ...ref, commitSha: "123" })).toBeNull();
  });

  it("rejects path traversal and absolute paths", () => {
    expect(sourceFileLink(ref, "../etc/passwd", 1)).toBeNull();
    expect(sourceFileLink(ref, "a/../b", 1)).toBeNull();
    expect(sourceFileLink(ref, "/etc/passwd", 1)).toBeNull();
  });

  it("rejects javascript: and data: scheme paths", () => {
    expect(sourceFileLink(ref, "javascript:alert(1)", 1)).toBeNull();
    expect(sourceFileLink(ref, "data:text/html,<x>", 1)).toBeNull();
  });

  it("rejects a host that is not a bare hostname (scheme, slash, userinfo, space)", () => {
    expect(sourceFileLink({ ...ref, host: "https://evil.com" }, "a.txt", 1)).toBeNull();
    expect(sourceFileLink({ ...ref, host: "evil.com/path" }, "a.txt", 1)).toBeNull();
    expect(sourceFileLink({ ...ref, host: "good.com@evil.com" }, "a.txt", 1)).toBeNull();
    expect(sourceFileLink({ ...ref, host: "evil .com" }, "a.txt", 1)).toBeNull();
  });

  it("returns null when the host is null (detached repo) — no link rendered", () => {
    expect(sourceFileLink({ ...ref, host: null }, "a.txt", 1)).toBeNull();
    expect(sourceCommitLink({ ...ref, host: null })).toBeNull();
  });

  it("rejects an invalid project path", () => {
    expect(sourceFileLink({ ...ref, projectPath: "acme/../secret" }, "a.txt", 1)).toBeNull();
    expect(sourceFileLink({ ...ref, projectPath: "/acme/x" }, "a.txt", 1)).toBeNull();
  });

  // M8.7 approval 16: GitHub shapes (no `/-/` infix), same validation + rejection rules.
  const gh: SourceRef = { host: "github.com", projectPath: "octocat/Hello-World", commitSha: SHA, provider: "github" };

  it("builds a GitHub blob link with a line anchor", () => {
    expect(sourceFileLink(gh, "src/App.java", 7)).toBe(
      `https://github.com/octocat/Hello-World/blob/${SHA}/src/App.java#L7`,
    );
  });

  it("builds a GitHub commit link", () => {
    expect(sourceCommitLink(gh)).toBe(`https://github.com/octocat/Hello-World/commit/${SHA}`);
  });

  it("encodes a GitHub file path per segment", () => {
    expect(sourceFileLink(gh, "a b/c#d.txt", 1)).toBe(
      `https://github.com/octocat/Hello-World/blob/${SHA}/a%20b/c%23d.txt#L1`,
    );
  });

  it("applies the same rejections to GitHub links", () => {
    expect(sourceFileLink({ ...gh, commitSha: "xyz" }, "a.txt", 1)).toBeNull();
    expect(sourceFileLink(gh, "../etc/passwd", 1)).toBeNull();
    expect(sourceFileLink(gh, "/abs", 1)).toBeNull();
    expect(sourceFileLink(gh, "javascript:alert(1)", 1)).toBeNull();
    expect(sourceFileLink({ ...gh, host: "github.com/evil" }, "a.txt", 1)).toBeNull();
    expect(sourceCommitLink({ ...gh, host: null })).toBeNull();
  });
});

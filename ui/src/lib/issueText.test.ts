import { describe, expect, it } from "vitest";
import type { IssueGroupView, SubIssueView } from "../api";
import { issueFix, issueSummary } from "./issueText";

function group(p: Partial<IssueGroupView>): IssueGroupView {
  return {
    id: "g",
    repositoryId: "r",
    repositoryPath: "acme/x",
    repositoryBranch: "main",
    type: "sca",
    title: "pkg",
    subtitle: null,
    level2Category: null,
    owaspCategory: null,
    status: "open",
    severityScore: 80,
    fixTimeMinutes: 30,
    firstSeenAt: "2026-10-01T00:00:00Z",
    openCount: 1,
    ignoredCount: 0,
    solvedCount: 0,
    fixedVersions: [],
    primaryLocation: null,
    locationCount: 0,
    ...p,
  };
}

function sub(p: Partial<SubIssueView>): SubIssueView {
  return {
    id: "s",
    groupId: "g",
    repositoryId: "r",
    type: "sca",
    scanner: "sca",
    status: "open",
    vulnId: null,
    ruleId: null,
    packageName: null,
    installedVersion: null,
    fixedVersion: null,
    filePath: null,
    line: null,
    secretPreview: null,
    toolSeverity: null,
    ignoreReason: null,
    score: 80,
    band: "high",
    scoreBreakdown: null,
    cvss: null,
    severityLabel: null,
    cwe: null,
    cwePhrase: null,
    inKev: null,
    epss: null,
    epssAttribution: null,
    firstSeenScanId: null,
    lastSeenScanId: null,
    solvedAt: null,
    createdAt: null,
    ignoreRuleId: null,
    ruleScope: null,
    ruleReason: null,
    ruleCreatedBy: null,
    ruleGlobal: false,
    level2Category: null,
    owaspCategory: null,
    advisorySummary: null,
    host: null,
    commitSha: null,
    ...p,
  };
}

describe("issueSummary / issueFix", () => {
  it("SCA single vuln with one fixed version", () => {
    const g = group({ type: "sca", title: "lodash", fixedVersions: ["4.17.21"] });
    const subs = [sub({ type: "sca", vulnId: "CVE-2021-1", installedVersion: "4.17.20", filePath: "package.json" })];
    expect(issueSummary(g, subs)).toContain("lodash 4.17.20 has 1 known vulnerability");
    expect(issueFix(g, subs)).toBe("Upgrade lodash to 4.17.21.");
  });

  it("SCA multiple vulns across manifests, KEV noted, several fixed versions", () => {
    const g = group({ type: "sca", title: "log4j-core", fixedVersions: ["2.16.0", "2.17.1"] });
    const subs = [
      sub({ type: "sca", vulnId: "CVE-A", installedVersion: "2.14.0", filePath: "a/pom.xml", inKev: true }),
      sub({ type: "sca", vulnId: "CVE-B", installedVersion: "2.14.0", filePath: "b/pom.xml" }),
    ];
    const s = issueSummary(g, subs);
    expect(s).toContain("2 known vulnerabilities");
    expect(s).toContain("across 2 manifests");
    expect(s).toContain("CISA KEV");
    expect(issueFix(g, subs)).toContain("differs per advisory");
  });

  it("SCA with no fixed version", () => {
    const g = group({ type: "sca", title: "abandoned-lib", fixedVersions: [] });
    const subs = [sub({ type: "sca", vulnId: "CVE-X", installedVersion: "1.0.0", filePath: "package.json" })];
    expect(issueFix(g, subs)).toContain("No fixed version is available yet");
    expect(issueFix(g, subs)).toContain("abandoned-lib");
  });

  it("SAST uses the CWE phrase and counts locations", () => {
    const g = group({ type: "sast", title: "rules.spring-csrf-disabled", level2Category: "Cross-site request forgery" });
    const subs = [
      sub({ type: "sast", cwePhrase: "Cross-site request forgery", filePath: "A.java" }),
      sub({ type: "sast", cwePhrase: "Cross-site request forgery", filePath: "B.java" }),
    ];
    expect(issueSummary(g, subs)).toBe("Cross-site request forgery was found in 2 locations.");
    expect(issueFix(g, subs)).toContain("remediate the cross-site request forgery");
  });

  it("secret: generic revoke/rotate/history guidance, never stores the value", () => {
    const g = group({ type: "secret", title: "Exposed aws-access-token" });
    const subs = [sub({ type: "secret", ruleId: "aws-access-token", filePath: "config/app.properties", secretPreview: "AKIA" })];
    expect(issueSummary(g, subs)).toBe("A secret (aws-access-token) was detected in config/app.properties.");
    const fix = issueFix(g, subs)!;
    expect(fix).toContain("Revoke or rotate");
    expect(fix).toContain("git history");
    expect(fix).not.toContain("AKIA");
  });

  it("IaC names the rule and file count", () => {
    const g = group({ type: "iac", title: "S3 bucket is public", subtitle: "CKV_AWS_20" });
    const subs = [sub({ type: "iac", filePath: "main.tf" })];
    expect(issueSummary(g, subs)).toContain("S3 bucket is public");
    expect(issueFix(g, subs)).toBe("Correct the flagged configuration (CKV_AWS_20) at each location.");
  });

  it("EOL states past end of life and how to fix", () => {
    const g = group({ type: "eol", title: "Java", subtitle: "8" });
    const subs = [sub({ type: "eol", filePath: ".java-version" })];
    expect(issueSummary(g, subs)).toContain("Java 8 is past its end-of-life date");
    expect(issueFix(g, subs)).toBe("Upgrade to a supported release of Java.");
  });
});

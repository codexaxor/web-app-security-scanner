// Human-readable labels for the wire codes the API returns. One module so copy is consistent everywhere; the
// raw code is always returned alongside so the UI can show it in a tooltip (never hidden). Display-only — no
// data semantics depend on these strings.

/** Auto/rule ignore reason codes (domain rule 5, D-061). */
export const REASON_LABEL: Record<string, string> = {
  DEV_DEPENDENCY: "Dev dependency",
  TEST_SCOPE: "Test-only dependency",
  EXCLUDED_PATH: "Excluded path",
  SECRET_PLACEHOLDER: "Placeholder secret",
  LOW_EPSS: "Low exploit likelihood (EPSS < 1%)",
  IGNORE_RULE: "Ignore rule",
  DUPLICATE_RULE: "Duplicate of a custom rule",
};

export function reasonLabel(code: string | null | undefined): string {
  if (!code) {
    return "—";
  }
  return REASON_LABEL[code] ?? code;
}

/**
 * Issue types — three plain-English representations (S10f). The acronym (SCA/SAST/IaC/EOL) is for TOOLTIPS ONLY
 * and never shown as visible text. API values stay the lowercase codes.
 *   - badge: the short plain name shown in a type badge ("Dependency")
 *   - label: the full plain name for filters, the Dashboard and the panel ("Vulnerable dependency")
 *   - acronym: the technical acronym, tooltip only ("SCA")
 */
export const TYPE_BADGE: Record<string, string> = {
  sca: "Dependency",
  sast: "Code",
  secret: "Secret",
  iac: "Infrastructure",
  eol: "Runtime",
};

export const TYPE_LABEL: Record<string, string> = {
  sca: "Vulnerable dependency",
  sast: "Code weakness",
  secret: "Exposed secret",
  iac: "Infrastructure config",
  eol: "Outdated runtime",
};

export const TYPE_ACRONYM: Record<string, string> = {
  sca: "SCA",
  sast: "SAST",
  secret: "Secret",
  iac: "IaC",
  eol: "EOL",
};

export function typeBadge(type: string): string {
  return TYPE_BADGE[type] ?? type;
}

export function typeLabel(type: string): string {
  return TYPE_LABEL[type] ?? type;
}

export function typeAcronym(type: string): string {
  return TYPE_ACRONYM[type] ?? type.toUpperCase();
}

// Acronyms that should stay upper-cased when humanizing a rule-id segment.
const ACRONYMS: Record<string, string> = {
  csrf: "CSRF",
  xss: "XSS",
  xxe: "XXE",
  ssrf: "SSRF",
  sql: "SQL",
  sqli: "SQLi",
  rce: "RCE",
  jwt: "JWT",
  cors: "CORS",
  ssl: "SSL",
  tls: "TLS",
  api: "API",
  url: "URL",
  uri: "URI",
  id: "ID",
  http: "HTTP",
  https: "HTTPS",
  dos: "DoS",
  aes: "AES",
  rsa: "RSA",
  md5: "MD5",
  sha: "SHA",
  ldap: "LDAP",
  xml: "XML",
};

/**
 * A human SAST title from existing data only: the last dot-segment of the rule id, split on separators,
 * title-cased with known acronyms upper-cased. e.g. "opengrep-rules…spring-csrf-disabled" → "Spring CSRF
 * Disabled". Falls back to the raw string when there is nothing to humanize. Never invents words.
 */
export function humanizeRuleId(ruleId: string | null | undefined): string {
  if (!ruleId) {
    return "—";
  }
  const seg = ruleId.split(".").pop() ?? ruleId;
  const words = seg.split(/[\s_|-]+/).filter(Boolean);
  if (words.length === 0) {
    return ruleId;
  }
  return words
    .map((w) => ACRONYMS[w.toLowerCase()] ?? w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(" ");
}

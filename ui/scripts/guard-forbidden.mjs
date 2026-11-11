#!/usr/bin/env node
// OD-13e security guard: FAIL the build if any forbidden API appears in ui/src. These are the browser-side
// primitives the M8 security baseline (OD-13a/d) forbids: no raw HTML injection, no client-side persistence of
// anything (credentials live in memory only), no dynamic code execution. Runs in the UI gate (npm run guard).
//
// Plain string/regex scan of source text — deliberately simple and dependency-free. A legitimate need to use one
// of these would be a security decision, not a lint exception, so the guard has no inline-ignore escape hatch.
//
// D-099 (M8.11): the theme preference is persisted, so `localStorage` is permitted in EXACTLY ONE file,
// `src/lib/themeStore.ts`, and nowhere else. For that file the guard additionally asserts it uses only the
// `secscan-theme` key, only the values `light`/`dark`, and only getItem/setItem/removeItem — so the exemption
// can never quietly widen to another key, value, member or file. Every other forbidden primitive (incl.
// sessionStorage/indexedDB/document.cookie/btoa/the Basic header literal) stays forbidden EVERYWHERE, the
// theme store included. The core checks are exported as pure functions so the guard can be self-tested.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, extname, relative, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const EXTS = new Set([".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".css", ".html"]);

// The single file allowed to touch localStorage (D-099), as a forward-slash path relative to src/.
export const THEME_STORE_REL = "lib/themeStore.ts";
export const THEME_KEY = "secscan-theme";
export const THEME_ALLOWED_LITERALS = new Set(["secscan-theme", "light", "dark"]);

// Each pattern: a regex and the human reason. Word-boundary / call-shaped where it matters to avoid false hits
// on unrelated identifiers (e.g. a CSS class literally named "eval" is not `eval(`). Patterns are matched against
// the WHOLE file text (N-7), so a construct split across lines (e.g. `document.\nwrite(`) is still caught; `\s`
// spans newlines for that reason. Bracket-access forms (el["innerHTML"]) are covered alongside dot access.
// `allowIn` exempts exactly one file (by its src-relative path) from that one rule.
export const FORBIDDEN = [
  [/dangerouslySetInnerHTML/, "dangerouslySetInnerHTML — render scanned data as text only (OD-13d)"],
  [/\.\s*innerHTML\b/, ".innerHTML — no raw HTML injection (OD-13d)"],
  [/\.\s*outerHTML\b/, ".outerHTML — no raw HTML injection (OD-13d)"],
  [/\[\s*['"]((inner)|(outer))HTML['"]\s*\]/, "[\"innerHTML\"]/[\"outerHTML\"] — no raw HTML injection (OD-13d)"],
  [/\binsertAdjacentHTML\b/, "insertAdjacentHTML — no raw HTML injection (OD-13d)"],
  [/document\s*\.\s*write\s*\(/, "document.write( — no raw HTML injection (OD-13d)"],
  [/\blocalStorage\b/, "localStorage — no client-side persistence except lib/themeStore.ts (OD-13a, D-099)", THEME_STORE_REL],
  [/\bsessionStorage\b/, "sessionStorage — no client-side persistence (OD-13a)"],
  [/\bindexedDB\b/, "indexedDB — no client-side persistence (OD-13a)"],
  [/document\s*\.\s*cookie/, "document.cookie — no cookies (OD-13a)"],
  [/\beval\s*\(/, "eval( — no dynamic code execution (OD-13d)"],
  [/\bnew\s+Function\s*\(/, "new Function( — no dynamic code execution (OD-13d)"],
  // M8.8/D-092: the SPA authenticates with the server session cookie only — it must never build an
  // Authorization: Basic header (a Basic string literal) or base64-encode credentials for one.
  [/['"`]\s*Basic\s/, "\"Basic \" — the SPA must not construct an Authorization: Basic header (D-092)"],
  [/\bbtoa\s*\(/, "btoa( — no client-side base64 credential encoding for Basic auth (D-092)"],
];

// Scan the WHOLE file text (not line-by-line) so a forbidden construct split across lines is still caught (N-7).
// The 1-based line number is derived from the match offset for a readable report.
function lineAt(text, index) {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i++) {
    if (text[i] === "\n") line++;
  }
  return line;
}

function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

function stringLiterals(code) {
  const out = [];
  for (const m of code.matchAll(/(['"`])((?:\\.|(?!\1)[^\\])*)\1/g)) out.push(m[2]);
  return out;
}

// D-099: the extra assertions that constrain the one localStorage-allowed file. Returns a list of reasons
// (empty when the file is well-formed). Run only against THEME_STORE_REL.
export function checkThemeStore(text) {
  const reasons = [];
  const code = stripComments(text);
  if (!/\blocalStorage\b/.test(code)) {
    reasons.push(`${THEME_STORE_REL} is the localStorage-exempt file but does not use localStorage (D-099)`);
  }
  if (/localStorage\s*\[/.test(code)) {
    reasons.push(`${THEME_STORE_REL}: localStorage[...] bracket access is not allowed — use getItem/setItem (D-099)`);
  }
  for (const m of code.matchAll(/localStorage\s*\.\s*([A-Za-z_$][\w$]*)/g)) {
    if (!["getItem", "setItem", "removeItem"].includes(m[1])) {
      reasons.push(`${THEME_STORE_REL}: localStorage.${m[1]} is not allowed — only getItem/setItem/removeItem (D-099)`);
    }
  }
  // Key check. This regex only sees LITERAL keys (getItem("x")). A key passed as a variable/expression is not
  // matched here, but it is still constrained: every string literal in this file must be one of
  // secscan-theme/light/dark (the loop below), so a variable key can only be built from those three strings
  // (or from non-string expressions, which review catches). Limitation (shared by every rule in this text scan, all files): an identifier built at
  // runtime, e.g. globalThis["local" + "Storage"], is not detected — the guard is a tripwire against accidental
  // or casual use, not a sandbox; code review (and the e2e storage assertion in test-base) backs it up.
  for (const m of code.matchAll(/localStorage\s*\.\s*(?:getItem|setItem|removeItem)\s*\(\s*(['"`])([^'"`]*)\1/g)) {
    if (m[2] !== THEME_KEY) {
      reasons.push(`${THEME_STORE_REL}: localStorage key "${m[2]}" — only "${THEME_KEY}" is allowed (D-099)`);
    }
  }
  for (const lit of stringLiterals(code)) {
    if (!THEME_ALLOWED_LITERALS.has(lit)) {
      reasons.push(`${THEME_STORE_REL}: string literal "${lit}" — only secscan-theme/light/dark are allowed (D-099)`);
    }
  }
  return reasons;
}

/** Check one file's text. `relPath` is its path relative to src/, with forward slashes. Returns report lines. */
export function checkFile(relPath, text) {
  const out = [];
  for (const [re, reason, allowIn] of FORBIDDEN) {
    if (allowIn && relPath === allowIn) continue;
    const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
    for (const m of text.matchAll(g)) {
      out.push(`${relPath}:${lineAt(text, m.index)}  ${reason}`);
    }
  }
  if (relPath === THEME_STORE_REL) {
    for (const reason of checkThemeStore(text)) out.push(reason);
  }
  return out;
}

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (EXTS.has(extname(p))) out.push(p);
  }
  return out;
}

function main() {
  // Computed here (not at module top level) so importing this module for the self-test never calls
  // fileURLToPath on a non-file URL (vitest serves modules from a virtual origin).
  const SRC = join(fileURLToPath(new URL(".", import.meta.url)), "..", "src");
  let files = [];
  try {
    files = walk(SRC);
  } catch {
    console.error(`guard: cannot read ${SRC}`);
    process.exit(2);
  }

  let failures = 0;
  for (const file of files) {
    const relPath = relative(SRC, file).split(sep).join("/");
    const text = readFileSync(file, "utf8");
    for (const line of checkFile(relPath, text)) {
      console.error(`FORBIDDEN  ${line}`);
      failures++;
    }
  }

  if (failures > 0) {
    console.error(`\nguard: ${failures} forbidden usage(s) found in ui/src. Build fails (OD-13e).`);
    process.exit(1);
  }
  console.log(`guard: clean (${files.length} files scanned, 0 forbidden usages).`);
}

// Only run the filesystem scan + process.exit when invoked as a script (npm run guard), not when imported
// by the self-test (guard.test.ts), which calls checkFile/checkThemeStore directly.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}

#!/usr/bin/env node
// OD-13e security guard: FAIL the build if any forbidden API appears in ui/src. These are the browser-side
// primitives the M8 security baseline (OD-13a/d) forbids: no raw HTML injection, no client-side persistence of
// anything (credentials live in memory only), no dynamic code execution. Runs in the UI gate (npm run guard).
//
// Plain string/regex scan of source text — deliberately simple and dependency-free. A legitimate need to use one
// of these would be a security decision, not a lint exception, so the guard has no inline-ignore escape hatch.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, extname } from "node:path";
import { fileURLToPath } from "node:url";

const SRC = join(fileURLToPath(new URL(".", import.meta.url)), "..", "src");
const EXTS = new Set([".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".css", ".html"]);

// Each pattern: a regex and the human reason. Word-boundary / call-shaped where it matters to avoid false hits
// on unrelated identifiers (e.g. a CSS class literally named "eval" is not `eval(`). Patterns are matched against
// the WHOLE file text (N-7), so a construct split across lines (e.g. `document.\nwrite(`) is still caught; `\s`
// spans newlines for that reason. Bracket-access forms (el["innerHTML"]) are covered alongside dot access.
const FORBIDDEN = [
  [/dangerouslySetInnerHTML/, "dangerouslySetInnerHTML — render scanned data as text only (OD-13d)"],
  [/\.\s*innerHTML\b/, ".innerHTML — no raw HTML injection (OD-13d)"],
  [/\.\s*outerHTML\b/, ".outerHTML — no raw HTML injection (OD-13d)"],
  [/\[\s*['"]((inner)|(outer))HTML['"]\s*\]/, "[\"innerHTML\"]/[\"outerHTML\"] — no raw HTML injection (OD-13d)"],
  [/\binsertAdjacentHTML\b/, "insertAdjacentHTML — no raw HTML injection (OD-13d)"],
  [/document\s*\.\s*write\s*\(/, "document.write( — no raw HTML injection (OD-13d)"],
  [/\blocalStorage\b/, "localStorage — no client-side persistence (OD-13a)"],
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

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (EXTS.has(extname(p))) out.push(p);
  }
  return out;
}

let failures = 0;
let files = [];
try {
  files = walk(SRC);
} catch {
  console.error(`guard: cannot read ${SRC}`);
  process.exit(2);
}

// Scan the WHOLE file text (not line-by-line) so a forbidden construct split across lines is still caught (N-7).
// The 1-based line number is derived from the match offset for a readable report.
function lineAt(text, index) {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i++) {
    if (text[i] === "\n") line++;
  }
  return line;
}

for (const file of files) {
  const text = readFileSync(file, "utf8");
  for (const [re, reason] of FORBIDDEN) {
    const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
    for (const m of text.matchAll(g)) {
      console.error(`FORBIDDEN  ${file}:${lineAt(text, m.index)}  ${reason}`);
      failures++;
    }
  }
}

if (failures > 0) {
  console.error(`\nguard: ${failures} forbidden usage(s) found in ui/src. Build fails (OD-13e).`);
  process.exit(1);
}
console.log(`guard: clean (${files.length} files scanned, 0 forbidden usages).`);

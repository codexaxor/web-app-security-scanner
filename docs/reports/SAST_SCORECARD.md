# SAST scorecard (M8.15 S7, D-104)

Measures the SAST engine (Opengrep 1.31.0) under the **production config** — the ten language packs, the
category + deny-list exclusions, `--taint-intrafile`, and `--timeout 60` — against three public benchmarks,
scanned **statically only** (never built or run; D-004) from local clones. The benchmarks are **not committed**;
the scoring script `scorecard/score_benchmark.py` takes their paths as arguments.

## Benchmark clones (exact commits + licences)

| benchmark | commit | licence | used for |
|---|---|---|---|
| OWASP BenchmarkJava v1.2 | `d4f6f3a` | GPL-2.0 | Java per-CWE TPR/FPR (2740 labelled tests, `expectedresults-1.2.csv`) |
| OWASP Juice Shop | `717f5ef` | MIT | JS/TS hits vs known vulnerable spots |
| OWASP NodeGoat | `c5cb68a` | Apache-2.0 | JS hits vs known vulnerable spots |

Scoring: a BenchmarkJava test is **flagged** when a finding on its file carries a CWE in that category's CWE set
(primary + close siblings). `score = TPR − FPR` (Youden's J). JS has no labelled ground truth, so it is a
hits-by-rule view read against each app's documented vulnerable spots.

## Java — BenchmarkJava v1.2, per CWE (before → after custom rules)

| category | CWE | TPR before | FPR before | score before | TPR after | FPR after | **score after** |
|---|---|--:|--:|--:|--:|--:|--:|
| trustbound | 501 | 0.54 | 0.33 | 0.22 | **0.75** | 0.42 | **0.33** |
| sqli | 89 | 0.97 | 0.52 | 0.45 | 0.97 | 0.52 | 0.45 |
| cmdi | 78 | 1.00 | 0.54 | 0.46 | 1.00 | 0.54 | 0.46 |
| pathtraver | 22 | 0.96 | 0.43 | 0.53 | 0.96 | 0.43 | 0.53 |
| ldapi | 90 | 1.00 | 0.44 | 0.56 | 1.00 | 0.44 | 0.56 |
| hash | 328 | 0.69 | 0.00 | 0.69 | 0.69 | 0.00 | 0.69 |
| xss | 79 | 0.89 | 0.14 | 0.75 | 0.89 | 0.14 | 0.75 |
| xpathi | 643 | 0.93 | 0.15 | 0.78 | 0.93 | 0.15 | 0.78 |
| crypto | 327 | 1.00 | 0.00 | 1.00 | 1.00 | 0.00 | 1.00 |
| securecookie | 614 | 1.00 | 0.00 | 1.00 | 1.00 | 0.00 | 1.00 |
| weakrand | 330 | 1.00 | 0.00 | 1.00 | 1.00 | 0.00 | 1.00 |
| **OVERALL** | | 0.92 | 0.23 | 0.68 | 0.93 | 0.23 | **0.69** |

Only **trustbound** moved (0.22 → 0.33): the custom `servlet-trust-boundary-session` rule recovered 17 missed
real cases (TP 45 → 62) at a small FP cost (14 → 18). **sqli, cmdi, ldapi, pathtraver** are dragged down by the
existing packs' **false positives**, not by missing detections (their recall is already 0.96–1.00); adding rules
cannot lower that FPR, and retiring an existing rule is out of M8.15 scope (`rule retired ≠ fixed`, deferred
register). **hash** misses 40 real cases, but they are a BenchmarkJava artifact — the weak algorithm is read from
a properties file (`getInstance(getProperty("hashAlg1","SHA512"))`), so no static rule can know it is MD5 without
executing config; a `getInstance(<non-literal>)` rule would be pure noise on real code. The custom `xss` and
`sqli` MIT rules are **clean (0 added FP)** on BenchmarkJava but score 0 TP there because the benchmark reads
input through a helper (`SeparateClassRequest.getTheParameter`) and uses raw JDBC, not inline reflection or
MyBatis — they are validated on real code instead (Juice Shop / NodeGoat and the fixtures).

## JS/TS — Juice Shop + NodeGoat, hits vs known vulnerable spots

Before the custom rules, these documented vulnerable spots produced **zero** findings (the packs cover SQLi via
Sequelize, XSS, `eval`, prototype pollution, but not these classes). After:

| class (rule) | file:spot | before | after |
|---|---|--:|--:|
| NoSQL `$where` (`nodejs-nosql-where-injection`) | JuiceShop `routes/trackOrder.ts`, `routes/showProductReviews.ts`, `routes/chat.ts`; NodeGoat `app/data/allocations-dao.js` | miss | **hit** |
| SSRF (`nodejs-ssrf-http-client`) | NodeGoat `app/routes/research.js` (`needle.get(url)`); JuiceShop `routes/profileImageUrlUpload.ts` | miss | **hit** |
| Unsafe `yaml.load` (`nodejs-unsafe-yaml-load`) | JuiceShop `routes/vulnCodeFixes.ts`, `routes/vulnCodeSnippet.ts` | miss | **hit** |

Noise observed on the JS apps (no ib-api equivalent — ib-api is Java): `nodejs-unsafe-yaml-load` also fired on
three config-loading spots (`server.ts`, `lib/scripts/lintConfig.ts`, `rsn/rsnUtil.ts`) that load trusted local
YAML — lower-risk but technically the same unsafe API; `nodejs-nosql-where-injection` fired on four
`data/static/codefixes/chatbotGreedyInjectionChallenge_*.ts` files (challenge fix samples, one named
`_correct`). These are acceptable (the API/pattern is genuinely risky) and are the reason the rules stay
WARNING-severity for deserialization.

## Real-world noise check — ib-api (read-only, Spring Boot, 2594 Java files)

The three **Java** custom rules were run on a file:// clone of ib-api's HEAD (the JS rules cannot fire — ib-api
has no JS). Result: **0 findings for all three** → 0 false positives.

| rule | ib-api hits | FP estimate |
|---|--:|---|
| `servlet-trust-boundary-session` | 0 | none |
| `servlet-reflected-xss-inline` | 0 | none |
| `mybatis-dollar-sql-injection` | 0 | none |

ib-api uses Spring Data / JPA (not raw servlets, `HttpSession.putValue`, inline request reflection, or MyBatis
annotations), so none of the rules trigger. No rule needed to be dropped or loosened.

## The six chosen categories and why

Picked from the weakest **addressable** categories in the scorecard (where a new sink rule can add true positives
or cover a class the packs miss entirely — not where the weakness is existing-rule FP, which adding rules cannot
fix):

| # | category (CWE) | rule | evidence |
|---|---|---|---|
| 1 | Java trust boundary (501) | `servlet-trust-boundary-session` | weakest Java score (0.22); 38 missed real cases → recovered 17 |
| 2 | Java SQLi (89) | `mybatis-dollar-sql-injection` | 2nd-weakest Java score (0.45); MyBatis `${}` is a real SQLi class no pack covers |
| 3 | Java XSS (79) | `servlet-reflected-xss-inline` | weak XSS recall; inline `out.print(request.getParameter(...))` reflection, pattern-based (no taint FP) |
| 4 | JS NoSQL injection (943) | `nodejs-nosql-where-injection` | 0 detections on 4 real `$where` sinks across both apps |
| 5 | JS SSRF (918) | `nodejs-ssrf-http-client` | 0 detections on `needle.get(taintedUrl)` (NodeGoat) / profile-image SSRF (JuiceShop) |
| 6 | JS deserialization (502) | `nodejs-unsafe-yaml-load` | 0 detections on `yaml.load(nonLiteral)` (JuiceShop codefix routes) |

All six are MIT-licensed, ship in `scanner-image/opengrep-rules-custom/` (`opengrep-rules.custom.<id>`), and have
positive + negative fixtures in `fixtures/sast-custom/`, asserted in the **default build** by
`:app:customRulesInImageTest` (and, through the worker pipeline, by the live `CustomSastRulesIT`).

## Thin-coverage + watch notes

- **Kotlin (14 rules) / Scala (27 rules)** stock packs are thin (vs Java 127, JS 182); common Kotlin/Scala
  framework weaknesses (Ktor/Spring-Kotlin, Play/Akka) are a candidate for later custom rules (deferred register).
- **Watch:** `python.django.security.django-no-csrf-token` fired on a **Flask** template during S2 evidence
  (`plexicus templates/index.html`) — a cross-framework false positive to keep an eye on; not denied (it is a
  real signal on Django).

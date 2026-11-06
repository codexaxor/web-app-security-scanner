import { useId, useState } from "react";
import { Link } from "react-router";
import { useAuth } from "../auth";
import { useTheme } from "../theme";
import BrandMark from "../components/BrandMark";
import Icon, { type IconName } from "../components/icons";

/**
 * M8.13 public marketing homepage at "/". Reachable signed out, it makes NO API calls of its own (it reads only
 * the already-known auth state for the nav/hero variant), uses the same design tokens and navy as the app,
 * defaults to light with a dark toggle, and names no competitor — pipeline-based tools are described, never
 * named. Every factual claim was fact-checked in S0; the copy matches the owner's final approved wording.
 */

function HomeNav({ signedIn }: { signedIn: boolean }) {
  const { theme, toggle } = useTheme();
  return (
    <header className="hp-nav">
      <div className="hp-nav-inner">
        <Link to="/" className="hp-brand" aria-label="SecScan home">
          <BrandMark />
          <span className="brand">SecScan</span>
        </Link>
        <nav className="hp-nav-links" aria-label="Sections">
          <a href="#how">How it works</a>
          <a href="#security">Security</a>
          <a href="#faq">FAQ</a>
        </nav>
        <div className="hp-nav-actions">
          <button
            type="button"
            className="hp-theme-toggle"
            aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
            aria-pressed={theme === "dark"}
            onClick={toggle}
          >
            <Icon name={theme === "dark" ? "sun" : "moon"} />
          </button>
          {signedIn ? (
            <Link to="/dashboard" className="hp-btn hp-btn-primary">
              Open dashboard
            </Link>
          ) : (
            <>
              <Link to="/login" className="hp-btn hp-btn-ghost">
                Sign in
              </Link>
              <Link to="/signup" className="hp-btn hp-btn-primary">
                Create account
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}

function Hero({ signedIn }: { signedIn: boolean }) {
  return (
    <section className="hp-hero">
      <div className="hp-hero-inner">
        <div className="hp-hero-copy">
          <h1 className="hp-hero-title">Scan everything. Touch nothing.</h1>
          <p className="hp-hero-sub">
            Leaked secrets, vulnerable dependencies and risky code in your GitHub and GitLab repositories — found
            in minutes with a read-only token. Nothing is built, run or changed.
          </p>
          <div className="hp-hero-cta">
            {signedIn ? (
              <Link to="/dashboard" className="hp-btn hp-btn-primary hp-btn-lg">
                Open dashboard
              </Link>
            ) : (
              <>
                <Link to="/signup" className="hp-btn hp-btn-primary hp-btn-lg">
                  Create account
                </Link>
                <Link to="/login" className="hp-btn hp-btn-onnavy hp-btn-lg">
                  Sign in
                </Link>
              </>
            )}
          </div>
          <p className="hp-hero-note">New workspaces are approved by an admin.</p>
        </div>
        <ProductPreview />
      </div>
    </section>
  );
}

/**
 * A designed, fictional product-preview mock — pure HTML/CSS, clearly sample data (no screenshot, no real
 * finding). It stands in for the feed so the hero shows what SecScan produces without shipping a bitmap.
 */
function ProductPreview() {
  const rows: { sev: string; label: string; title: string; meta: string }[] = [
    { sev: "critical", label: "Critical", title: "Hardcoded AWS key in config/deploy.yml", meta: "Secret · in KEV" },
    { sev: "high", label: "High", title: "log4j-core 2.14.1 → 2.17.1", meta: "Dependency · CVE-2021-44228" },
    { sev: "high", label: "High", title: "SQL built from request input", meta: "Code · java/sql-injection" },
    { sev: "medium", label: "Medium", title: "S3 bucket without encryption", meta: "Infra · CKV_AWS_19" },
    { sev: "low", label: "Low", title: "Node 16 runtime past end-of-life", meta: "Runtime · EOL" },
  ];
  return (
    <div className="hp-preview" role="img" aria-label="Example of the SecScan issue feed with sample findings">
      <div className="hp-preview-bar" aria-hidden="true">
        <span className="hp-preview-dot" />
        <span className="hp-preview-dot" />
        <span className="hp-preview-dot" />
        <span className="hp-preview-title">SecScan — Feed</span>
      </div>
      <div className="hp-preview-body">
        {rows.map((r, i) => (
          <div className="hp-preview-row" key={i}>
            <span className={`hp-sev hp-sev-${r.sev}`}>{r.label}</span>
            <span className="hp-preview-issue">
              <span className="hp-preview-issue-title">{r.title}</span>
              <span className="hp-preview-issue-meta">{r.meta}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

function TrustStrip() {
  const items: { icon: IconName; title: string; body: string }[] = [
    { icon: "check", title: "Read-only access", body: "A read-only token — it can't push or change settings." },
    { icon: "scan", title: "Nothing is built or run", body: "Files are only read, in a locked-down container." },
    { icon: "secret", title: "Findings, not your code", body: "A secret is kept as a keyed hash, never in full." },
    { icon: "users", title: "Isolated workspaces", body: "Only your workspace sees your findings." },
  ];
  return (
    <section className="hp-trust" aria-label="At a glance">
      <ul className="hp-trust-grid">
        {items.map((it) => (
          <li className="hp-trust-item" key={it.title}>
            <span className="hp-trust-icon" aria-hidden="true">
              <Icon name={it.icon} />
            </span>
            <span className="hp-trust-text">
              <strong>{it.title}</strong>
              <span>{it.body}</span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function HowItWorks() {
  const steps: { n: string; title: string; body: string }[] = [
    {
      n: "1",
      title: "Connect",
      body: "Add a read-only token for the GitHub or GitLab repositories you choose. No pipeline, no app to install.",
    },
    {
      n: "2",
      title: "Copy",
      body: "A shallow copy of one branch goes into a locked-down, throwaway container — read-only and offline to everything else.",
    },
    {
      n: "3",
      title: "Scan",
      body: "Four open-source scanners plus our own end-of-life check read the files. Nothing is built or run.",
    },
    {
      n: "4",
      title: "Review",
      body: "Findings are deduplicated and ranked by real-world exploit data. The copy is deleted when the scan ends.",
    },
  ];
  return (
    <section className="hp-section" id="how">
      <div className="hp-section-inner">
        <h2 className="hp-h2">How a scan works</h2>
        <p className="hp-lead">Connect a token and SecScan does the rest — without ever changing your repository.</p>
        <ol className="hp-steps">
          {steps.map((s) => (
            <li className="hp-step" key={s.n}>
              <span className="hp-step-n" aria-hidden="true">
                {s.n}
              </span>
              <h3 className="hp-step-title">{s.title}</h3>
              <p className="hp-step-body">{s.body}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

function WhatItFinds() {
  const cards: { icon: IconName; title: string; body: string }[] = [
    {
      icon: "secret",
      title: "Leaked secrets",
      body: "API keys, tokens and credentials committed to the repository — stored only as a keyed hash plus the first 4 characters.",
    },
    {
      icon: "sca",
      title: "Vulnerable dependencies",
      body: "Known CVEs in the libraries you depend on, with the version that fixes each one.",
    },
    {
      icon: "sast",
      title: "Risky code",
      body: "Dangerous patterns in your own code — injection, unsafe APIs and similar — found by static analysis.",
    },
    {
      icon: "iac",
      title: "Infrastructure misconfigurations",
      body: "Insecure settings in infrastructure-as-code, such as public buckets or disabled encryption.",
    },
    {
      icon: "eol",
      title: "End-of-life runtimes",
      body: "Frameworks and runtimes past their support date, so you can plan upgrades before they bite.",
    },
  ];
  return (
    <section className="hp-section hp-section-alt" id="what">
      <div className="hp-section-inner">
        <h2 className="hp-h2">What it finds</h2>
        <p className="hp-lead">Five kinds of risk across your repositories, in one deduplicated, prioritized feed.</p>
        <ul className="hp-cards">
          {cards.map((c) => (
            <li className="hp-card" key={c.title}>
              <span className={`hp-card-icon hp-type-${c.icon}`} aria-hidden="true">
                <Icon name={c.icon} />
              </span>
              <h3 className="hp-card-title">{c.title}</h3>
              <p className="hp-card-body">{c.body}</p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function Security() {
  const points: { icon: IconName; title: string; body: string }[] = [
    {
      icon: "check",
      title: "Read-only, least privilege",
      body: "You grant a read-only token for the repositories you choose. SecScan can read code — it can't push, change settings or reach anything else.",
    },
    {
      icon: "scan",
      title: "Your code is never executed",
      body: "A shallow copy of one branch is read inside a locked-down, throwaway container — non-root, read-only filesystem, no network to build services. Nothing is built or run, and the copy is deleted when the scan ends.",
    },
    {
      icon: "secret",
      title: "We store findings, not source",
      body: "A finding keeps the file path, line, rule and package version — not your code. A secret we find is stored only as a one-way keyed hash plus its first 4 characters, never in full.",
    },
    {
      icon: "connect",
      title: "Tokens encrypted at rest",
      body: "You enter a token once, in the app only. It's encrypted with AES-256-GCM before it's saved, never shown again, never logged, and only ever sent to your Git provider over verified HTTPS.",
    },
    {
      icon: "users",
      title: "Every workspace is isolated",
      body: "Your findings belong to your workspace alone. Not even the platform admin can read another workspace's data.",
    },
  ];
  return (
    <section className="hp-section" id="security">
      <div className="hp-section-inner">
        <h2 className="hp-h2">Safe to point at your code</h2>
        <p className="hp-lead">
          SecScan is built to read your repositories without becoming a risk of its own.
        </p>
        <ul className="hp-seclist">
          {points.map((p) => (
            <li className="hp-sec-item" key={p.title}>
              <span className="hp-sec-icon" aria-hidden="true">
                <Icon name={p.icon} />
              </span>
              <div>
                <h3 className="hp-sec-title">{p.title}</h3>
                <p className="hp-sec-body">{p.body}</p>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

const FAQ_ITEMS: { q: string; a: string }[] = [
  {
    q: "If I connect GitHub or GitLab, how is it secured?",
    a: "You give SecScan a read-only token for the repositories you choose. It can read code — it can't push, change settings or see anything else. All traffic uses verified HTTPS.",
  },
  {
    q: "Do you store my data?",
    a: "We store findings, not code: file path, line, rule and package version. A secret we find is stored only as a one-way keyed hash plus its first 4 characters — never in full. Every workspace is isolated.",
  },
  {
    q: "How does scanning work? Is my code copied?",
    a: "Yes, briefly. A shallow copy of one branch goes into a locked-down, throwaway container. Four open-source scanners plus our own end-of-life check read the files — nothing is built or run. When the scan ends, the copy is deleted.",
  },
  {
    q: "How is my API token protected?",
    a: "You enter it once, in the app only. It's encrypted (AES-256-GCM) before it's saved, never shown again, never logged, and only ever sent to your Git provider.",
  },
  {
    q: "Why this instead of pipeline-based tools?",
    a: "No pipeline to set up and nothing to add to your repo — connect a token and scan. Secrets, dependencies, code, infrastructure and outdated runtimes in one place, ranked by real-world exploit data (CISA KEV, EPSS) so you fix what matters first.",
  },
  {
    q: "Who can see my findings?",
    a: "Only your workspace. Not even the platform admin can read them.",
  },
  {
    q: "Will it change my repository?",
    a: "Never. No commits, no CI jobs, no config files required.",
  },
];

function FaqItem({ q, a }: { q: string; a: string }) {
  const [open, setOpen] = useState(false);
  const base = useId();
  const btnId = `${base}-b`;
  const panelId = `${base}-p`;
  return (
    <div className={`hp-faq-item${open ? " open" : ""}`}>
      <h3 className="hp-faq-q">
        <button
          type="button"
          id={btnId}
          className="hp-faq-trigger"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((v) => !v)}
        >
          <span>{q}</span>
          <Icon name="chevronDown" className="hp-faq-caret" />
        </button>
      </h3>
      <div id={panelId} role="region" aria-labelledby={btnId} className="hp-faq-panel" hidden={!open}>
        <p>{a}</p>
      </div>
    </div>
  );
}

function Faq() {
  return (
    <section className="hp-section hp-section-alt" id="faq">
      <div className="hp-section-inner hp-faq-inner">
        <h2 className="hp-h2">Frequently asked</h2>
        <div className="hp-faq">
          {FAQ_ITEMS.map((it) => (
            <FaqItem key={it.q} q={it.q} a={it.a} />
          ))}
        </div>
      </div>
    </section>
  );
}

function Comparison() {
  const rows: { label: string; secscan: string; pipeline: string }[] = [
    {
      label: "Setup",
      secscan: "Connect a read-only token and scan.",
      pipeline: "Add and maintain a pipeline job in every repository.",
    },
    {
      label: "Changes to your repo",
      secscan: "None — no commits, no CI jobs, no config files required.",
      pipeline: "A CI configuration committed to each repository.",
    },
    {
      label: "Where it runs",
      secscan: "In an isolated, throwaway container — nothing is built or run.",
      pipeline: "Inside your build pipeline, alongside your build.",
    },
  ];
  return (
    <section className="hp-section">
      <div className="hp-section-inner">
        <h2 className="hp-h2">SecScan vs pipeline-based tools</h2>
        <p className="hp-lead">Same risks covered — without a pipeline to wire up or anything added to your code.</p>
        <table className="hp-compare">
          <thead>
            <tr>
              <th scope="col">
                <span className="hp-compare-rowhead-label">Approach</span>
              </th>
              <th scope="col" className="hp-compare-us">
                SecScan
              </th>
              <th scope="col">Pipeline-based tools</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.label}>
                <th scope="row">{r.label}</th>
                <td className="hp-compare-us">{r.secscan}</td>
                <td>{r.pipeline}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function CtaBand({ signedIn }: { signedIn: boolean }) {
  return (
    <section className="hp-ctaband">
      <div className="hp-ctaband-inner">
        <h2 className="hp-ctaband-title">See what's hiding in your repositories.</h2>
        <p className="hp-ctaband-sub">Connect a read-only token and get your first scan in minutes.</p>
        <div className="hp-hero-cta">
          {signedIn ? (
            <Link to="/dashboard" className="hp-btn hp-btn-primary hp-btn-lg">
              Open dashboard
            </Link>
          ) : (
            <>
              <Link to="/signup" className="hp-btn hp-btn-primary hp-btn-lg">
                Create account
              </Link>
              <Link to="/login" className="hp-btn hp-btn-onnavy hp-btn-lg">
                Sign in
              </Link>
            </>
          )}
        </div>
        <p className="hp-ctaband-note">New workspaces are approved by an admin.</p>
      </div>
    </section>
  );
}

function HomeFooter({ signedIn }: { signedIn: boolean }) {
  return (
    <footer className="hp-footer">
      <div className="hp-footer-inner">
        <span className="hp-brand">
          <BrandMark />
          <span className="brand">SecScan</span>
        </span>
        <nav className="hp-footer-links" aria-label="Footer">
          <a href="#how">How it works</a>
          <a href="#what">What it finds</a>
          <a href="#security">Security</a>
          <a href="#faq">FAQ</a>
          {signedIn ? <Link to="/dashboard">Dashboard</Link> : <Link to="/login">Sign in</Link>}
        </nav>
        <span className="hp-footer-note">Repository security scanning · read-only · no pipeline</span>
      </div>
    </footer>
  );
}

export default function HomePage() {
  const { signedIn } = useAuth();
  return (
    <div className="hp" data-testid="homepage">
      <a className="skip-link" href="#hp-main">
        Skip to content
      </a>
      <HomeNav signedIn={signedIn} />
      <main id="hp-main">
        <Hero signedIn={signedIn} />
        <TrustStrip />
        <HowItWorks />
        <WhatItFinds />
        <Security />
        <Faq />
        <Comparison />
        <CtaBand signedIn={signedIn} />
      </main>
      <HomeFooter signedIn={signedIn} />
    </div>
  );
}

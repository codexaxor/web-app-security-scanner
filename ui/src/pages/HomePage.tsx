import { Link } from "react-router";
import { useAuth } from "../auth";
import { useTheme } from "../theme";
import BrandMark from "../components/BrandMark";
import Icon from "../components/icons";

/**
 * M8.13 public marketing homepage at "/". It is reachable signed out, makes NO API calls of its own (it reads
 * only the already-known auth state for the nav/hero variant), uses the same design tokens and navy as the app,
 * defaults to light with a dark toggle, and names no competitor — pipeline-based tools are described, never
 * named. Every factual claim was fact-checked in S0; the copy here matches the owner's final wording.
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
    { sev: "critical", label: "Critical", title: "Hardcoded AWS key in config/deploy.yml", meta: "Secret · KEV" },
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

function HomeFooter() {
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
          <Link to="/login">Sign in</Link>
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
      </main>
      <HomeFooter />
    </div>
  );
}

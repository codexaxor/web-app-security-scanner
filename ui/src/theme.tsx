import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

/**
 * Theme selection held IN MEMORY only (OD-13a: no web storage, no cookies — the security guard stays as is;
 * persistence is deferred to M10 user settings, so a refresh resets to System). "system" follows
 * prefers-color-scheme; "light"/"dark" force a theme via a `data-theme` attribute on <html> (setAttribute,
 * never inline <style>). The CSS in styles/tokens.css already selects the right token set by [data-theme] and
 * @media (prefers-color-scheme).
 */
export type Theme = "system" | "light" | "dark";

interface ThemeCtx {
  theme: Theme;
  setTheme: (t: Theme) => void;
}

const Ctx = createContext<ThemeCtx | null>(null);

function apply(theme: Theme) {
  const el = document.documentElement;
  if (theme === "system") {
    el.removeAttribute("data-theme");
  } else {
    el.setAttribute("data-theme", theme);
  }
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>("system"); // resets to System on every refresh (in-memory only)

  useEffect(() => {
    apply(theme);
  }, [theme]);

  const setTheme = useCallback((t: Theme) => setThemeState(t), []);

  return <Ctx.Provider value={{ theme, setTheme }}>{children}</Ctx.Provider>;
}

export function useTheme(): ThemeCtx {
  const c = useContext(Ctx);
  if (!c) {
    throw new Error("useTheme must be used within ThemeProvider");
  }
  return c;
}

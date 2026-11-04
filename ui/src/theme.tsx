import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { loadTheme, saveTheme, type ThemeChoice } from "./lib/themeStore";

/**
 * Theme selection (M8.11, D-099). LIGHT by default; the only other choice is DARK — there is no "system"
 * option. The choice is remembered across reloads via `lib/themeStore` (one key in web storage, wrapped in
 * try/catch, falls back to light). The theme is applied as a `data-theme` attribute on <html> (setAttribute,
 * never an inline <style>); tokens.css defines the dark token set ONLY under [data-theme="dark"] — there is no
 * @media (prefers-color-scheme) block, so the OS setting is never followed and first paint is light (N-4).
 */
export type Theme = ThemeChoice;

interface ThemeCtx {
  theme: Theme;
  setTheme: (t: Theme) => void;
  toggle: () => void;
}

const Ctx = createContext<ThemeCtx | null>(null);

function apply(theme: Theme) {
  document.documentElement.setAttribute("data-theme", theme);
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(() => loadTheme()); // light unless a dark choice was saved

  useEffect(() => {
    apply(theme);
    saveTheme(theme);
  }, [theme]);

  const setTheme = useCallback((t: Theme) => setThemeState(t), []);
  const toggle = useCallback(() => setThemeState((t) => (t === "dark" ? "light" : "dark")), []);

  return <Ctx.Provider value={{ theme, setTheme, toggle }}>{children}</Ctx.Provider>;
}

export function useTheme(): ThemeCtx {
  const c = useContext(Ctx);
  if (!c) {
    throw new Error("useTheme must be used within ThemeProvider");
  }
  return c;
}

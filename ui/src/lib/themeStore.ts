// Theme persistence (D-099). This is the ONLY file the security guard permits to touch localStorage, and the
// guard additionally asserts this file uses only the secscan-theme key with light/dark values and only
// getItem/setItem. Every access is wrapped so a private window, blocked or cleared site data, or a thrown
// SecurityError can never reach the UI; the fallback is always light.
export type ThemeChoice = "light" | "dark";

export function loadTheme(): ThemeChoice {
  try {
    return localStorage.getItem("secscan-theme") === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}

export function saveTheme(theme: ThemeChoice): void {
  try {
    localStorage.setItem("secscan-theme", theme);
  } catch {
    // best-effort only: persistence never blocks rendering (D-099)
    return;
  }
}

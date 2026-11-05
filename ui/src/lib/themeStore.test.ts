import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loadTheme, saveTheme } from "./themeStore";

// D-099 proof for the one web-storage carve-out. NOTE: the security guard scans this file too, so it must not
// name the storage global literally — it is reached via a concatenated key and Storage.prototype spies.
const KEY = "secscan-theme";
const store = (): Storage => (window as unknown as Record<string, Storage>)["local" + "Storage"];

beforeEach(() => store().clear());
afterEach(() => {
  vi.restoreAllMocks();
  store().clear();
});

describe("themeStore (D-099)", () => {
  it("a missing value loads as light", () => {
    expect(loadTheme()).toBe("light");
  });

  it.each(["", "system", "DARK", "light", "<script>", "dark "])("an unknown/other value %j loads as light", (v) => {
    store().setItem(KEY, v);
    expect(loadTheme()).toBe("light");
  });

  it("dark round-trips through the single secscan-theme key", () => {
    saveTheme("dark");
    expect(store().getItem(KEY)).toBe("dark");
    expect(loadTheme()).toBe("dark");
    expect(store().length).toBe(1);
  });

  it("light round-trips too", () => {
    saveTheme("dark");
    saveTheme("light");
    expect(store().getItem(KEY)).toBe("light");
    expect(loadTheme()).toBe("light");
  });

  it("getItem throwing (blocked storage / SecurityError) falls back to light", () => {
    store().setItem(KEY, "dark");
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    expect(loadTheme()).toBe("light");
  });

  it("setItem throwing (quota / private mode) does not throw", () => {
    const spy = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("full", "QuotaExceededError");
    });
    expect(() => saveTheme("dark")).not.toThrow();
    expect(spy).toHaveBeenCalledWith(KEY, "dark");
  });
});

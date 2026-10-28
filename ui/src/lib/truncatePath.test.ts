import { describe, expect, it } from "vitest";
import { truncatePathStart } from "./truncatePath";

describe("truncatePathStart (M8.8 L2)", () => {
  it("leaves a short path unchanged", () => {
    expect(truncatePathStart("src/app.ts", 44)).toBe("src/app.ts");
    expect(truncatePathStart("", 44)).toBe("");
  });

  it("truncates a long path from the start, keeping the file name", () => {
    const r = truncatePathStart("infra/environments/prod/terraform/networking.tf", 25);
    expect(r.startsWith("…")).toBe(true);
    expect(r.endsWith("networking.tf")).toBe(true);
    expect(r).not.toContain("infra"); // the leading segments were dropped
    expect(r.length).toBeLessThanOrEqual(26);
  });

  it("begins the kept tail at a clean path separator", () => {
    const r = truncatePathStart("a/bb/ccc/dddd/eeeee/file.tf", 18);
    expect(r.startsWith("…/")).toBe(true); // "…/<segment>…/file.tf", not "…ddd/file.tf"
    expect(r.endsWith("file.tf")).toBe(true);
  });

  it("keeps the whole file name even when it alone exceeds the budget", () => {
    const r = truncatePathStart("x/a-very-very-long-file-name.tf", 10);
    expect(r).toBe("…/a-very-very-long-file-name.tf");
  });
});

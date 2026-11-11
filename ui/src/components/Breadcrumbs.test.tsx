import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import Breadcrumbs from "./Breadcrumbs";

describe("Breadcrumbs", () => {
  it("links earlier crumbs and marks the last as the current page (not a link)", () => {
    render(
      <MemoryRouter>
        <Breadcrumbs items={[{ label: "Repositories", to: "/repositories" }, { label: "org/app" }]} />
      </MemoryRouter>,
    );
    const link = screen.getByRole("link", { name: "Repositories" });
    expect(link.getAttribute("href")).toBe("/repositories");
    expect(screen.queryByRole("link", { name: "org/app" })).toBeNull();
    expect(screen.getByText("org/app").getAttribute("aria-current")).toBe("page");
  });

  it("renders a Feed › <group title> trail with the title as the current page", () => {
    render(
      <MemoryRouter>
        <Breadcrumbs
          items={[{ label: "Feed", to: "/feed?view=all" }, { label: "CVE-2021-1234 in lodash" }]}
        />
      </MemoryRouter>,
    );
    expect(screen.getByRole("link", { name: "Feed" }).getAttribute("href")).toBe("/feed?view=all");
    expect(screen.queryByRole("link", { name: "CVE-2021-1234 in lodash" })).toBeNull();
    expect(screen.getByText("CVE-2021-1234 in lodash").getAttribute("aria-current")).toBe("page");
  });
});

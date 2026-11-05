import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import SelectRepositories from "./SelectRepositories";
import { api, ApiError, type ConnectionView, type Paged, type ProjectView, type RepositoryView } from "../api";

function conn(id: string, provider: "gitlab" | "github", path: string): ConnectionView {
  return {
    id,
    provider,
    host: provider === "gitlab" ? "gitlab.example.com" : "github.com",
    credentialKind: "token",
    targetType: provider === "gitlab" ? "group" : "repo",
    baseUrl: provider === "gitlab" ? "https://gitlab.example.com" : "https://github.com",
    gitlabGroupId: provider === "gitlab" ? 42 : null,
    groupFullPath: provider === "gitlab" ? path : null,
    gitlabProjectId: null,
    projectFullPath: null,
    ownerLogin: null,
    repoFullName: provider === "github" ? path : null,
    tokenScopes: [],
    tokenExpiresAt: null,
  } as unknown as ConnectionView;
}

function paged<T>(content: T[]): Paged<T> {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 };
}

function setup(connections: ConnectionView[] | Error, projects: Paged<ProjectView> | Error = paged([])) {
  vi.spyOn(api, "listConnections").mockImplementation(() =>
    connections instanceof Error ? Promise.reject(connections) : Promise.resolve(paged(connections)),
  );
  const listProjects = vi
    .spyOn(api, "listProjects")
    .mockImplementation(() => (projects instanceof Error ? Promise.reject(projects) : Promise.resolve(projects)));
  vi.spyOn(api, "listRepositories").mockResolvedValue(paged<RepositoryView>([]));
  render(
    <MemoryRouter>
      <SelectRepositories />
    </MemoryRouter>,
  );
  return { listProjects };
}

afterEach(() => vi.restoreAllMocks());

describe("SelectRepositories (M8.11 review SF-1)", () => {
  it("with no connection shows 'Add a source first' with a link to /connect — not an error", async () => {
    setup([]);
    const empty = await screen.findByTestId("select-no-source");
    expect(within(empty).getByText("Add a source first")).toBeTruthy();
    expect(within(empty).getByRole("link", { name: "Add a source" }).getAttribute("href")).toBe("/connect");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("offers GitLab connections only; a GitHub-only workspace gets the empty state", async () => {
    const { listProjects } = setup([conn("gh1", "github", "octo/widget")]);
    await screen.findByTestId("select-no-source");
    expect(listProjects).not.toHaveBeenCalled();
  });

  it("lists only GitLab connections in the picker and loads the first GitLab one", async () => {
    const { listProjects } = setup([conn("gh1", "github", "octo/widget"), conn("gl1", "gitlab", "acme")]);
    const picker = (await screen.findByLabelText("Connection")) as HTMLSelectElement;
    const options = within(picker).getAllByRole("option");
    expect(options.map((o) => (o as HTMLOptionElement).value)).toEqual(["gl1"]);
    expect(listProjects).toHaveBeenCalledWith("gl1", 0, 50);
  });

  it("a project-list failure is an inline error scoped to the list; the page (picker, selected) stays", async () => {
    setup([conn("gl1", "gitlab", "acme")], new ApiError("Unexpected error.", "INTERNAL"));
    const scoped = await screen.findByTestId("select-list-error");
    expect(within(scoped).getByRole("alert").textContent).toBe("Unexpected error.");
    expect(within(scoped).getByText("Could not load projects from this connection.")).toBeTruthy();
    // Not a page replacement: the connection picker and the Selected section are still rendered.
    expect(screen.getByLabelText("Connection")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Selected repositories" })).toBeTruthy();
  });

  it("a connections-list failure renders an inline error (no crash, no empty-state CTA)", async () => {
    setup(new ApiError("Request failed (503)", null));
    expect((await screen.findByRole("alert")).textContent).toBe("Request failed (503)");
    expect(screen.queryByTestId("select-no-source")).toBeNull();
  });
});

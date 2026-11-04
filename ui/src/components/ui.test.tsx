import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EmptyState } from "./ui";

describe("EmptyState", () => {
  it("renders the title and body", () => {
    render(<EmptyState title="Nothing here">Add something to get started.</EmptyState>);
    expect(screen.getByText("Nothing here")).not.toBeNull();
    expect(screen.getByText("Add something to get started.")).not.toBeNull();
  });

  it("renders a next-action when provided", () => {
    render(
      <EmptyState title="No sources yet" action={<button type="button">Add a source</button>}>
        body
      </EmptyState>,
    );
    expect(screen.getByRole("button", { name: "Add a source" })).not.toBeNull();
  });

  it("omits the action area when no action is given", () => {
    const { container } = render(<EmptyState title="No sources yet" testId="es" />);
    expect(container.querySelector(".empty-action")).toBeNull();
  });
});

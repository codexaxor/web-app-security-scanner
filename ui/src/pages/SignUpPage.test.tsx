import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import SignUpPage from "./SignUpPage";
import { ApiError, RateLimitedError, signUp } from "../api";

vi.mock("../api", async (orig) => {
  const real = await orig<typeof import("../api")>();
  return { ...real, signUp: vi.fn() };
});

afterEach(() => vi.mocked(signUp).mockReset());

function fill(email: string, password: string, workspace: string) {
  render(
    <MemoryRouter>
      <SignUpPage />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText("Work email"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: password } });
  fireEvent.change(screen.getByLabelText("Workspace name"), { target: { value: workspace } });
  fireEvent.click(screen.getByRole("button", { name: "Request account" }));
}

describe("SignUpPage (M8.10)", () => {
  it("explains field problems before submitting and does not call the API", async () => {
    fill("not-an-email", "a", ""); // D-101: 1 char < 2 min
    expect(await screen.findByText("Enter a valid email address.")).not.toBeNull();
    expect(screen.getByText(/at least 2 characters/)).not.toBeNull();
    expect(screen.getByLabelText("Work email").getAttribute("aria-invalid")).toBe("true");
    expect(signUp).not.toHaveBeenCalled();
  });

  it("always ends on the same neutral confirmation, whatever the server message (no enumeration)", async () => {
    vi.mocked(signUp).mockResolvedValue({ status: "RECEIVED", message: "server text that is not shown" });
    fill("a@b.example", "correct-horse-battery", "Acme");
    const done = await screen.findByTestId("signup-done");
    expect(done.textContent).toContain("your request was received");
    expect(done.textContent).not.toContain("server text");
    expect(screen.getByRole("link", { name: "Back to sign in" }).getAttribute("href")).toBe("/");
    expect(signUp).toHaveBeenCalledWith({ email: "a@b.example", password: "correct-horse-battery", workspaceName: "Acme" });
  });

  it("explains a rate limit with the wait in minutes", async () => {
    vi.mocked(signUp).mockRejectedValue(new RateLimitedError("Too many", 900));
    fill("a@b.example", "correct-horse-battery", "Acme");
    expect((await screen.findByRole("alert")).textContent).toBe("Too many sign-up attempts. Try again in 15 minutes.");
  });

  it("shows a server validation message as plain text", async () => {
    vi.mocked(signUp).mockRejectedValue(new ApiError("email: must be a valid email address", "VALIDATION_FAILED"));
    fill("a@b.example", "correct-horse-battery", "Acme");
    expect((await screen.findByRole("alert")).textContent).toContain("must be a valid email address");
  });
});

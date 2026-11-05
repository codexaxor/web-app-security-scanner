import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import SignUpPage from "./SignUpPage";
import AwaitingApprovalPage from "./AwaitingApprovalPage";
import { RateLimitedError, signUp } from "../api/signups";

vi.mock("../api/signups", async (orig) => {
  const real = await orig<typeof import("../api/signups")>();
  return { ...real, signUp: vi.fn() };
});

afterEach(() => vi.mocked(signUp).mockReset());

function fill(email: string, password: string, workspace: string) {
  fireEvent.change(screen.getByLabelText("Work email"), { target: { value: email } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: password } });
  fireEvent.change(screen.getByLabelText("Workspace name"), { target: { value: workspace } });
  fireEvent.click(screen.getByText("Request account"));
}

describe("SignUpPage (M8.10)", () => {
  it("validates on the client and does not call the API for an invalid form", async () => {
    render(<SignUpPage />);
    fill("not-an-email", "short", "");
    expect(await screen.findByText("Enter a valid email address.")).toBeTruthy();
    expect(screen.getByText(/at least 12 characters/)).toBeTruthy();
    expect(signUp).not.toHaveBeenCalled();
  });

  it("shows the server's single confirmation and clears the form (no account enumeration)", async () => {
    vi.mocked(signUp).mockResolvedValue({ status: "RECEIVED", message: "If this address can be used, we got it." });
    render(<SignUpPage />);
    fill("a@b.example", "correct-horse-battery", "Acme");
    const done = await screen.findByTestId("signup-done");
    expect(done.textContent).toContain("If this address can be used, we got it.");
    expect(signUp).toHaveBeenCalledWith({
      email: "a@b.example",
      password: "correct-horse-battery",
      workspaceName: "Acme",
    });
  });

  it("explains a rate limit with the wait time", async () => {
    vi.mocked(signUp).mockRejectedValue(new RateLimitedError("Too many", 900));
    render(<SignUpPage />);
    fill("a@b.example", "correct-horse-battery", "Acme");
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("Try again in 15 min"));
  });
});

describe("AwaitingApprovalPage (M8.10)", () => {
  it("distinguishes a pending request from a disabled account", () => {
    const { rerender } = render(<AwaitingApprovalPage reason="pending" />);
    expect(screen.getByTestId("account-pending").textContent).toContain("not been approved yet");
    rerender(<AwaitingApprovalPage reason="inactive" />);
    expect(screen.getByTestId("account-inactive").textContent).toContain("disabled");
  });
});

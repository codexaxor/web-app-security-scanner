import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import SignInGate, { signInErrorMessage } from "./SignInGate";
import { AccountInactiveError, AccountPendingError, AuthRequiredError, RateLimitedError, login } from "../api";

vi.mock("../auth", () => ({ useAuth: () => ({ signIn: vi.fn() }) }));
vi.mock("../api", async (orig) => {
  const real = await orig<typeof import("../api")>();
  return { ...real, login: vi.fn() };
});

afterEach(() => vi.mocked(login).mockReset());

function submit() {
  render(
    <MemoryRouter>
      <SignInGate />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: "a@b.example" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "pw" } });
  fireEvent.click(screen.getByRole("button", { name: "Continue" }));
}

describe("sign-in outcomes (M8.10)", () => {
  it("maps each login error to a message that never reveals whether the email exists", () => {
    expect(signInErrorMessage(new AuthRequiredError("x"))).toBe("Wrong email or password.");
    expect(signInErrorMessage(new AccountInactiveError())).toMatch(/not active/);
    expect(signInErrorMessage(new RateLimitedError("x", 840))).toBe(
      "Too many sign-in attempts. Try again in 14 minutes.",
    );
    expect(signInErrorMessage(new Error("network"))).toMatch(/Could not reach the server/);
  });

  it("a pending account shows the awaiting-approval screen, with a way back to the form", async () => {
    vi.mocked(login).mockRejectedValue(new AccountPendingError());
    submit();
    expect(await screen.findByTestId("account-pending")).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Back to sign in" }));
    expect(screen.getByRole("heading", { name: "Sign in" })).not.toBeNull();
    expect((screen.getByLabelText("Password") as HTMLInputElement).value).toBe(""); // password not kept
  });

  it("a disabled account stays on the form with the not-active message", async () => {
    vi.mocked(login).mockRejectedValue(new AccountInactiveError());
    submit();
    expect((await screen.findByRole("alert")).textContent).toMatch(/not active/);
  });

  it("offers the Create account link in the footer", () => {
    render(
      <MemoryRouter>
        <SignInGate />
      </MemoryRouter>,
    );
    expect(screen.getByRole("link", { name: "Create account" }).getAttribute("href")).toBe("/signup");
  });
});

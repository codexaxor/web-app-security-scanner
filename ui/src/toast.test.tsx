import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ToastProvider, useToast } from "./toast";

// A tiny harness that exposes the toast actions as buttons so tests can enqueue without a real feature screen.
function Harness() {
  const toast = useToast();
  return (
    <div>
      <button onClick={() => toast.success("Saved!")}>success</button>
      <button onClick={() => toast.error("It failed")}>error</button>
    </div>
  );
}

describe("toast system (M8.8 item 6)", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("renders one polite, non-atomic aria-live region", () => {
    render(
      <ToastProvider>
        <Harness />
      </ToastProvider>,
    );
    const region = screen.getByRole("status");
    expect(region.getAttribute("aria-live")).toBe("polite");
    expect(region.className).toContain("toast-region");
  });

  it("shows an enqueued toast with its kind class, then auto-dismisses it", () => {
    render(
      <ToastProvider>
        <Harness />
      </ToastProvider>,
    );
    act(() => fireEvent.click(screen.getByText("success")));

    const toast = screen.getByText("Saved!").closest(".toast")!;
    expect(toast.className).toContain("toast-success"); // the class the reduced-motion CSS also targets

    act(() => vi.advanceTimersByTime(4000));
    expect(screen.queryByText("Saved!")).toBeNull();
  });

  it("keeps an error toast longer than a success toast", () => {
    render(
      <ToastProvider>
        <Harness />
      </ToastProvider>,
    );
    act(() => fireEvent.click(screen.getByText("error")));

    act(() => vi.advanceTimersByTime(4000)); // success window elapsed…
    expect(screen.queryByText("It failed")).not.toBeNull(); // …but the error is still shown (7s)
    act(() => vi.advanceTimersByTime(3000));
    expect(screen.queryByText("It failed")).toBeNull();
  });

  it("can be dismissed manually before the timer", () => {
    render(
      <ToastProvider>
        <Harness />
      </ToastProvider>,
    );
    act(() => fireEvent.click(screen.getByText("success")));
    act(() => fireEvent.click(screen.getByLabelText("Dismiss notification")));
    expect(screen.queryByText("Saved!")).toBeNull();
  });

  it("does not auto-dismiss when autoDismiss is disabled", () => {
    render(
      <ToastProvider autoDismiss={false}>
        <Harness />
      </ToastProvider>,
    );
    act(() => fireEvent.click(screen.getByText("success")));
    act(() => vi.advanceTimersByTime(60000));
    expect(screen.queryByText("Saved!")).not.toBeNull();
  });
});

import { renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider, focusManager } from "@tanstack/react-query";
import { createElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { anyActive, POLL_MS, useScanPolling } from "./scanPolling";

describe("scan polling (M8.8 item 5 / SF-3)", () => {
  afterEach(() => {
    focusManager.setFocused(undefined);
    vi.useRealTimers();
  });

  it("detects an active scan among statuses", () => {
    expect(anyActive(["completed", "failed"])).toBe(false);
    expect(anyActive(["completed", "running"])).toBe(true);
    expect(anyActive(["queued"])).toBe(true);
    expect(anyActive([null, undefined])).toBe(false);
  });

  it("polls while active + visible, pauses when the tab is hidden, and resumes when visible", async () => {
    vi.useFakeTimers();
    const queryFn = vi.fn(async () => ["running"]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client }, children);

    focusManager.setFocused(true); // tab visible
    renderHook(() => useScanPolling({ queryKey: ["scan-poll-test"], queryFn }, (d) => anyActive(d ?? [])), {
      wrapper,
    });

    await vi.advanceTimersByTimeAsync(0); // initial fetch
    expect(queryFn).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(POLL_MS); // one interval → one poll
    expect(queryFn).toHaveBeenCalledTimes(2);

    focusManager.setFocused(false); // tab hidden
    await vi.advanceTimersByTimeAsync(POLL_MS * 3);
    expect(queryFn).toHaveBeenCalledTimes(2); // paused: no fetches while hidden

    focusManager.setFocused(true); // tab visible again
    await vi.advanceTimersByTimeAsync(POLL_MS);
    expect(queryFn.mock.calls.length).toBeGreaterThan(2); // resumed within one interval
  });
});

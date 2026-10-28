import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import Pager from "./Pager";

describe("Pager (M8.8 item 8)", () => {
  it("renders nothing for an empty list", () => {
    const { container } = render(
      <Pager page={0} size={50} totalElements={0} totalPages={0} onPage={() => {}} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("shows the 1-based range and total", () => {
    render(<Pager page={0} size={50} totalElements={282} totalPages={6} onPage={() => {}} />);
    expect(screen.getByTestId("pager-range").textContent).toBe("1–50 of 282");
  });

  it("shows the range of a middle page", () => {
    render(<Pager page={2} size={50} totalElements={282} totalPages={6} onPage={() => {}} />);
    expect(screen.getByTestId("pager-range").textContent).toBe("101–150 of 282");
  });

  it("clamps the last page's upper bound to the total", () => {
    render(<Pager page={5} size={50} totalElements={282} totalPages={6} onPage={() => {}} />);
    expect(screen.getByTestId("pager-range").textContent).toBe("251–282 of 282");
  });

  it("disables Previous on the first page and Next on the last", () => {
    const { rerender } = render(
      <Pager page={0} size={50} totalElements={282} totalPages={6} onPage={() => {}} />,
    );
    expect((screen.getByText("Previous") as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByText("Next") as HTMLButtonElement).disabled).toBe(false);
    rerender(<Pager page={5} size={50} totalElements={282} totalPages={6} onPage={() => {}} />);
    expect((screen.getByText("Next") as HTMLButtonElement).disabled).toBe(true);
  });

  it("calls onPage when navigating", () => {
    const onPage = vi.fn();
    render(<Pager page={1} size={50} totalElements={282} totalPages={6} onPage={onPage} />);
    fireEvent.click(screen.getByText("Next"));
    fireEvent.click(screen.getByText("Previous"));
    expect(onPage).toHaveBeenNthCalledWith(1, 2);
    expect(onPage).toHaveBeenNthCalledWith(2, 0);
  });

  it("offers 25/50/100 and reports a size change", () => {
    const onSize = vi.fn();
    render(
      <Pager page={0} size={50} totalElements={282} totalPages={6} onPage={() => {}} onSize={onSize} />,
    );
    fireEvent.change(screen.getByLabelText("Rows per page"), { target: { value: "100" } });
    expect(onSize).toHaveBeenCalledWith(100);
  });
});

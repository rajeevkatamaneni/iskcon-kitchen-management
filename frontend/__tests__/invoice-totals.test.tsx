import { describe, expect, it } from "vitest";
import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import {
  EMPTY_TOTALS,
  InvoiceTotals,
  addsUpMessage,
  totalsCheck,
  type InvoiceTotalsDraft,
} from "@/components/InvoiceTotals";

/**
 * The invoice's totals block (R-INV-5, T-273): the five labels, the check that Sub total + GST +
 * Other charges − Discount = Grand total, the green tick with its name and tip when it holds, and the
 * one red line when it does not. Both modes: typed (the create form) and read-only (the invoice's own
 * page, R-INV-7), which since T-277 is design D's compact list rather than the form's boxes.
 */

function Typed({ subTotal, start }: { subTotal: number; start?: Partial<InvoiceTotalsDraft> }) {
  const [value, setValue] = useState<InvoiceTotalsDraft>({ ...EMPTY_TOTALS, ...start });
  return <InvoiceTotals subTotal={subTotal} value={value} onChange={setValue} />;
}

const box = (name: string) => document.querySelector(`input[name="${name}"]`) as HTMLInputElement;
const type = (name: string, value: string) => fireEvent.change(box(name), { target: { value } });

describe("the totals check", () => {
  it("adds the sub total, GST and other charges, takes off the discount, and compares to the paisa", () => {
    expect(totalsCheck(958, 24.5, 50, 2.5, 1030)).toEqual({ computed: 1030, grand: 1030, matches: true });
    expect(totalsCheck(958, 24.5, 50, 2.5, 1000).matches).toBe(false);
    expect(totalsCheck(0.1, 0.2, null, null, 0.3).matches).toBe(true);
    expect(totalsCheck(10, null, null, null, null).matches).toBe(false);
  });

  it("words the mismatch exactly as R-INV-5 does", () => {
    expect(addsUpMessage(1030, 1000)).toBe("Adds up to ₹1,030, not ₹1,000");
  });
});

describe("InvoiceTotals, typed", () => {
  it("uses the five labels, and the Sub total is the lines' sum, never typed", () => {
    render(<Typed subTotal={958} />);
    for (const label of ["Sub total", "GST", "Other charges", "Discount", "Grand total"]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getByTestId("sub-total")).toHaveTextContent("958");
    expect(document.querySelector('input[name="subTotal"]')).toBeNull();
    expect(screen.getByLabelText("GST")).toBe(box("gst"));
    expect(screen.getByLabelText("Grand total")).toBe(box("grandTotal"));
    expect(screen.getByLabelText("What the other charges are for")).toBe(box("otherChargesNote"));
  });

  it("puts a green tick beside the Grand total when it adds up, named and explained, with no sentence", () => {
    render(<Typed subTotal={958} start={{ gst: "24.50", otherCharges: "50", discount: "2.50" }} />);
    expect(screen.queryByRole("img", { name: "Adds up to the grand total" })).not.toBeInTheDocument();
    type("grandTotal", "1030");

    const tick = screen.getByRole("img", { name: "Adds up to the grand total" });
    expect(tick).toHaveClass("text-success");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // The tip, on keyboard focus, in the same words.
    fireEvent.focus(tick);
    expect(screen.getByRole("tooltip")).toHaveTextContent("Adds up to the grand total");
  });

  it("says in one short red line by how much it is out, and drops the tick", () => {
    render(<Typed subTotal={958} start={{ gst: "24.50", otherCharges: "50", discount: "2.50" }} />);
    type("grandTotal", "1000");
    const line = screen.getByRole("alert");
    expect(line).toHaveTextContent(/^Adds up to ₹1,030, not ₹1,000$/);
    expect(line).toHaveClass("text-danger");
    expect(box("grandTotal")).toHaveAttribute("aria-invalid", "true");
    expect(screen.queryByRole("img", { name: "Adds up to the grand total" })).not.toBeInTheDocument();
  });

  it("says nothing either way until the Grand total is typed", () => {
    render(<Typed subTotal={958} />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(box("grandTotal")).toBeRequired();
  });
});

describe("InvoiceTotals, read-only", () => {
  const saved = (over: Partial<Parameters<typeof InvoiceTotals>[0]> = {}) => (
    <InvoiceTotals
      readOnly
      subTotal={958}
      gstAmount={24.5}
      otherCharges={50}
      otherChargesNote="Delivery"
      discount={2.5}
      grandTotal={1030}
      {...(over as object)}
    />
  );

  it("shows the saved figures with no boxes, the note, and the tick when they add up", () => {
    render(saved());
    expect(document.querySelector("input")).toBeNull();
    expect(screen.getByTestId("grand-total")).toHaveTextContent("1,030");
    expect(screen.getByText("Delivery")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Adds up to the grand total" })).toBeInTheDocument();
  });

  // T-277: design D's foot, not the form's. Each figure carries its rupee sign, the discount says it
  // is taken off, there is no "Amount (₹)" heading, and no figure sits in a 44px box.
  it("is design D's compact list: the same five labels, a rupee sign on every figure, the discount taken off", () => {
    render(saved());
    const list = screen.getByTestId("invoice-totals");
    expect(list.tagName).toBe("DL");
    expect([...list.querySelectorAll("dt")].map((d) => d.textContent)).toEqual([
      "Sub total",
      "GST",
      "Other charges",
      "Discount",
      "Grand total",
    ]);
    const figures = [...list.querySelectorAll("dd.tabular-nums")].map((d) => d.textContent);
    expect(figures).toEqual(["₹958", "₹24.50", "₹50", "− ₹2.50", "₹1,030"]);
    expect(screen.queryByText("Amount (₹)")).not.toBeInTheDocument();
    expect(list.querySelector(".min-h-touch")).toBeNull();
  });

  it("shows a zero discount as ₹0, not as something taken off", () => {
    render(saved({ discount: 0, grandTotal: 1032.5 }));
    expect(screen.getByText("Discount").nextElementSibling).toHaveTextContent(/^₹0$/);
  });

  it("leaves the note out when the bill gave none", () => {
    render(saved({ otherChargesNote: null }));
    expect(screen.queryByText("Delivery")).not.toBeInTheDocument();
  });

  it("shows the red line on a saved invoice that does not add up, and no tick", () => {
    render(
      <InvoiceTotals readOnly subTotal={958} gstAmount={0} otherCharges={0} otherChargesNote={null} discount={0} grandTotal={1000} />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Adds up to ₹958, not ₹1,000");
    expect(screen.queryByRole("img", { name: "Adds up to the grand total" })).not.toBeInTheDocument();
  });
});

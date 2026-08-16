import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { openCheckout } from "@/lib/checkout";
import type { DonationCheckout } from "@/lib/api";

/**
 * The payment window itself. Everything here is what the browser hands to Razorpay and what it does
 * with the several ways that window can close — the part no integration test can see, because by
 * then the provider's own script has taken over the screen.
 */

const CHECKOUT: DonationCheckout = {
  donationId: "d1",
  orderId: "order_ABC123",
  publicKey: "rzp_test_key",
  amountInr: 1100,
  currency: "INR",
  provider: "razorpay",
};

interface Opened {
  options: Record<string, any>;
  events: Record<string, (r: unknown) => void>;
}

let opened: Opened[] = [];
let openCalls = 0;

class FakeRazorpay {
  private readonly events: Record<string, (r: unknown) => void> = {};

  constructor(options: Record<string, any>) {
    opened.push({ options, events: this.events });
  }

  on(event: string, handler: (r: unknown) => void) {
    this.events[event] = handler;
  }

  open() {
    openCalls += 1;
  }
}

/** The options the window was opened with, once the provider's script has been handed them. */
async function lastOpened(): Promise<Opened> {
  await vi.waitFor(() => expect(opened.length).toBeGreaterThan(0));
  return opened[opened.length - 1];
}

describe("opening the payment window", () => {
  beforeEach(() => {
    opened = [];
    openCalls = 0;
    // Present already, so nothing has to be fetched over the network for these tests.
    window.Razorpay = FakeRazorpay as unknown as typeof window.Razorpay;
  });

  afterEach(() => {
    delete window.Razorpay;
  });

  it("hands the provider the order the server created, in paise", async () => {
    void openCheckout(CHECKOUT, { templeName: "Sri Sri Radha Govinda Temple", name: "Radha Devi" });

    const { options } = await lastOpened();
    expect(options.key).toBe("rzp_test_key");
    expect(options.order_id).toBe("order_ABC123");
    expect(options.amount).toBe(110000); // ₹1,100 is 110000 paise
    expect(options.currency).toBe("INR");
    expect(options.name).toBe("Sri Sri Radha Govinda Temple");
    expect(options.prefill.name).toBe("Radha Devi");
    expect(openCalls).toBe(1);
  });

  it("rounds to the nearest paisa rather than letting a fraction of one through", async () => {
    void openCheckout({ ...CHECKOUT, amountInr: 100.555 }, { templeName: "Temple" });
    expect((await lastOpened()).options.amount).toBe(10056);
  });

  it("says paid when the payment went through", async () => {
    const outcome = openCheckout(CHECKOUT, { templeName: "Temple" });
    (await lastOpened()).options.handler({});
    await expect(outcome).resolves.toBe("paid");
  });

  it("says dismissed when the donor closed the window", async () => {
    const outcome = openCheckout(CHECKOUT, { templeName: "Temple" });
    (await lastOpened()).options.modal.ondismiss();
    await expect(outcome).resolves.toBe("dismissed");
  });

  it("treats a declined payment as dismissed — the donor is still here and may try again", async () => {
    const outcome = openCheckout(CHECKOUT, { templeName: "Temple" });
    (await lastOpened()).events["payment.failed"]({});
    await expect(outcome).resolves.toBe("dismissed");
  });

  it("keeps the first answer when the window closes twice", async () => {
    // Razorpay fires ondismiss after a successful payment as well; the gift was still paid.
    const outcome = openCheckout(CHECKOUT, { templeName: "Temple" });
    const { options } = await lastOpened();
    options.handler({});
    options.modal.ondismiss();
    await expect(outcome).resolves.toBe("paid");
  });

  it("opens nothing for a temple whose gateway cannot take money", async () => {
    // No provider configured: the server falls back to a stub, and there is no window to open.
    await expect(
      openCheckout({ ...CHECKOUT, provider: "stub", orderId: "", publicKey: "" }, { templeName: "T" })
    ).resolves.toBe("unavailable");
    expect(openCalls).toBe(0);
  });
});

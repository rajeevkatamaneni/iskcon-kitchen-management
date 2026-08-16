/**
 * Opening the payment window, which is the step that actually takes the money.
 *
 * <p>Everything before this exists already: the server creates a PENDING donation against the
 * temple's own Razorpay account and hands back the order id and that temple's publishable key. Until
 * now the page threw both away and said thank you, so a devotee who pressed Give was never asked to
 * pay. This is the missing half.
 *
 * <p>What comes back from the window is not proof of anything. The donation is confirmed by the
 * signed webhook the provider sends the server, never by the browser saying it went well — a page
 * can be lied to and a signature cannot. So the outcome here decides only what the donor is told.
 */

import type { DonationCheckout } from "@/lib/api";

const SCRIPT_SRC = "https://checkout.razorpay.com/v1/checkout.js";

/**
 * What became of the payment window.
 *
 * <p>`dismissed` is not a failure — it is a devotee changing their mind, and the PENDING donation it
 * leaves behind is swept later. `unavailable` is a temple that has configured no gateway: the server
 * falls back to a stub that takes no money, and telling the donor so beats opening a window that
 * cannot charge them.
 */
export type CheckoutOutcome = "paid" | "dismissed" | "unavailable";

export interface CheckoutDonor {
  templeName: string;
  description?: string;
  name?: string | null;
  email?: string | null;
  phone?: string | null;
}

interface RazorpayInstance {
  open(): void;
  on(event: string, handler: (response: unknown) => void): void;
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayInstance;
  }
}

/** One load for the life of the tab, shared by every donation made on it. */
let loading: Promise<void> | null = null;

function loadCheckoutScript(): Promise<void> {
  if (typeof window === "undefined") {
    return Promise.reject(new Error("Checkout can only be opened in a browser."));
  }
  if (window.Razorpay) {
    return Promise.resolve();
  }
  if (loading) {
    return loading;
  }
  loading = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
    const script = existing ?? document.createElement("script");
    script.src = SCRIPT_SRC;
    script.async = true;
    script.addEventListener("load", () => resolve());
    script.addEventListener("error", () => {
      // Let the next attempt try again rather than caching the failure for the tab's lifetime.
      loading = null;
      reject(new Error("Could not load the payment window."));
    });
    if (!existing) {
      document.body.appendChild(script);
    }
  });
  return loading;
}

/**
 * Opens hosted checkout for a donation the server has already created, and resolves once the donor
 * has either paid or closed the window.
 */
export async function openCheckout(
  checkout: DonationCheckout,
  donor: CheckoutDonor
): Promise<CheckoutOutcome> {
  if (checkout.provider !== "razorpay" || !checkout.orderId || !checkout.publicKey) {
    return "unavailable";
  }

  await loadCheckoutScript();
  const Razorpay = window.Razorpay;
  if (!Razorpay) {
    throw new Error("Could not load the payment window.");
  }

  return new Promise<CheckoutOutcome>((resolve, reject) => {
    // The window can close in more than one way and each way fires its own callback, so the first
    // one to arrive is the answer and the rest are ignored.
    let settled = false;
    const settle = (outcome: CheckoutOutcome) => {
      if (!settled) {
        settled = true;
        resolve(outcome);
      }
    };

    const instance = new Razorpay({
      key: checkout.publicKey,
      order_id: checkout.orderId,
      // Razorpay counts in paise. The rupee figure is the temple's, so round rather than truncate.
      amount: Math.round(Number(checkout.amountInr) * 100),
      currency: checkout.currency || "INR",
      name: donor.templeName,
      description: donor.description ?? "Donation",
      prefill: {
        name: donor.name ?? undefined,
        email: donor.email ?? undefined,
        contact: donor.phone ?? undefined,
      },
      notes: { donationId: checkout.donationId },
      // The design system's terracotta, so the provider's window reads as part of the same page.
      theme: { color: "#BE6444" },
      handler: () => settle("paid"),
      modal: { ondismiss: () => settle("dismissed") },
    });

    // A card declined or a UPI request that timed out: the donor is still on the page and may try
    // again, so this reads as dismissed rather than as something that went wrong on our side.
    instance.on("payment.failed", () => settle("dismissed"));

    try {
      instance.open();
    } catch (e) {
      settled = true;
      reject(e instanceof Error ? e : new Error("Could not open the payment window."));
    }
  });
}

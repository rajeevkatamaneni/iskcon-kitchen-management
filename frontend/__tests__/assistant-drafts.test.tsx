import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ReadableStream as NodeReadableStream } from "node:stream/web";
import type { AssistantProposal } from "@/lib/api";

/*
 * E10-S4-UI (T18): the draft card's Confirm and Reject. The api is a fetch stub: the message endpoint
 * streams a `proposal`, the confirm and reject endpoints answer with whatever the test sets, and the
 * conversation endpoint answers a reload. The card must show what the server sent back, never what
 * the press was meant to do.
 */

const { authRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", userId: "u1", tenantId: "t1", fullName: "Govinda Das" },
      getToken: async () => "token-1",
    },
  },
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));

import { AssistantLauncher } from "@/components/assistant/AssistantPanel";
import { DraftCard } from "@/components/assistant/AssistantParts";

const STORAGE_KEY = "kms.assistant.u1.t1";
const encoder = new TextEncoder();
const frame = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const inADay = () => new Date(Date.now() + 24 * 3600_000).toISOString();

function draft(over: Partial<AssistantProposal> = {}): AssistantProposal {
  return {
    actionId: "p1",
    kind: "PURCHASE_ORDER",
    status: "PROPOSED",
    title: "Purchase order for Sri Balaji Traders",
    facts: [
      { label: "Vendor", value: "Sri Balaji Traders" },
      { label: "Total", value: "₹6,600" },
    ],
    lines: [{ name: "Toor dal", localName: "ತೊಗರಿ ಬೇಳೆ", quantity: 50, unit: "KG", rate: 132, amount: 6600 }],
    confirmSays: "Confirm saves a draft order. Nothing is sent.",
    expiresAt: inADay(),
    result: null,
    ...over,
  };
}

const CONFIRMED = () => draft({ status: "CONFIRMED", result: { label: "PO-0047", href: "/orders/o47" } });

type Reply = { ok: boolean; status: number; json: () => Promise<unknown> };
const ok = (body: unknown): Reply => ({ ok: true, status: 200, json: async () => body });
const refuse = (code: string, message: string, action: string): Reply => ({
  ok: false,
  status: 409,
  json: async () => ({ code, message, action, fieldErrors: [] }),
});

type Call = { url: string; init: RequestInit };
let calls: Call[];
/** What the confirm or reject endpoint answers; a promise lets a test hold the answer back. */
let decision: () => Reply | Promise<Reply>;
/** What GET conversation answers (a reload, or the re-read after KMS-400196). */
let saved: () => unknown;

beforeEach(() => {
  calls = [];
  window.sessionStorage.clear();
  saved = () => ({ id: "c1", title: "t", createdAt: "", updatedAt: "", messages: [] });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      if (url.endsWith("/api/v1/assistant/conversations") && init.method === "POST") {
        return { ok: true, status: 201, json: async () => ({ id: "c1", title: null, createdAt: "", updatedAt: "" }) };
      }
      if (url.endsWith("/api/v1/assistant/conversations/c1") && init.method === "GET") return ok(saved());
      if (url.endsWith("/messages")) {
        const body = new NodeReadableStream<Uint8Array>({
          start(c) {
            c.enqueue(encoder.encode(frame("text", { text: "Here is the draft." }) + frame("proposal", draft())));
            c.enqueue(encoder.encode(frame("done", { messageId: "m2", runId: "r1" })));
            c.close();
          },
        });
        return { ok: true, status: 200, body };
      }
      if (/\/api\/v1\/assistant\/actions\/p1\/(confirm|reject)$/.test(url)) return decision();
      throw new Error(`unexpected ${url}`);
    })
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const log = () => screen.getByTestId("assistant-messages");
const actionCalls = () => calls.filter((c) => c.url.includes("/actions/"));

/** Opens the panel and asks for an order; the stream answers with one PROPOSED draft. */
async function askForADraft() {
  render(<AssistantLauncher />);
  fireEvent.click(screen.getByRole("button", { name: "Ask the assistant" }));
  fireEvent.change(screen.getByLabelText("Message the assistant"), { target: { value: "Order toor dal" } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
  });
  await waitFor(() => expect(within(log()).getByText("Not saved yet")).toBeInTheDocument());
}

describe("Confirm", () => {
  it("sends one confirm, shows it busy, then shows the saved order with a link to it", async () => {
    let answer!: (r: Reply) => void;
    decision = () => new Promise<Reply>((r) => (answer = r));
    await askForADraft();

    const confirm = within(log()).getByRole("button", { name: "Confirm" });
    await act(async () => {
      fireEvent.click(confirm);
    });
    // In flight: Confirm is busy, and Reject cannot be pressed under it.
    expect(confirm).toHaveAttribute("aria-busy", "true");
    expect(confirm).toBeDisabled();
    expect(within(log()).getByRole("button", { name: "Reject" })).toBeDisabled();

    const sent = actionCalls();
    expect(sent).toHaveLength(1);
    expect(sent[0].url).toMatch(/\/api\/v1\/assistant\/actions\/p1\/confirm$/);
    expect(sent[0].init.method).toBe("POST");
    expect((sent[0].init.headers as Record<string, string>).Authorization).toBe("Bearer token-1");

    await act(async () => answer(ok(CONFIRMED())));
    expect(within(log()).getByText("Saved")).toBeInTheDocument();
    expect(log()).toHaveTextContent("Saved as draft PO-0047. Not sent to Sri Balaji Traders yet. Open PO-0047");
    expect(within(log()).getByRole("link", { name: "Open PO-0047" })).toHaveAttribute("href", "/orders/o47");
    expect(within(log()).queryByRole("button", { name: "Confirm" })).not.toBeInTheDocument();
    expect(within(log()).queryByRole("button", { name: "Reject" })).not.toBeInTheDocument();
  });

  it("a double-click sends one request", async () => {
    let answer!: (r: Reply) => void;
    decision = () => new Promise<Reply>((r) => (answer = r));
    await askForADraft();

    const confirm = within(log()).getByRole("button", { name: "Confirm" });
    // Both clicks in the same tick, before React has re-rendered the button as disabled.
    await act(async () => {
      fireEvent.click(confirm);
      fireEvent.click(confirm);
      fireEvent.click(within(log()).getByRole("button", { name: "Reject" }));
    });
    expect(actionCalls()).toHaveLength(1);
    await act(async () => answer(ok(CONFIRMED())));
    expect(actionCalls()).toHaveLength(1);
  });
});

describe("Reject", () => {
  it("sends reject and shows the draft rejected, with nothing saved", async () => {
    decision = () => ok(draft({ status: "REJECTED" }));
    await askForADraft();

    await act(async () => {
      fireEvent.click(within(log()).getByRole("button", { name: "Reject" }));
    });
    expect(actionCalls()).toHaveLength(1);
    expect(actionCalls()[0].url).toMatch(/\/actions\/p1\/reject$/);
    await waitFor(() => expect(within(log()).getByText("Rejected")).toBeInTheDocument());
    expect(within(log()).getByText("You rejected this draft. Nothing was saved.")).toBeInTheDocument();
    expect(within(log()).queryByRole("button", { name: "Confirm" })).not.toBeInTheDocument();
  });
});

describe("refusals", () => {
  it("KMS-400194: shows the refusal and the draft as expired, with no buttons", async () => {
    decision = () => refuse("KMS-400194", "This draft has expired.", "Ask the assistant again for a fresh one.");
    await askForADraft();
    await act(async () => {
      fireEvent.click(within(log()).getByRole("button", { name: "Confirm" }));
    });

    await waitFor(() => expect(within(log()).getByText("KMS-400194")).toBeInTheDocument());
    expect(within(log()).getByText("This draft has expired.")).toBeInTheDocument();
    expect(within(log()).getByText("Ask the assistant again for a fresh one.")).toBeInTheDocument();
    expect(within(log()).getByText("Expired")).toBeInTheDocument();
    expect(within(log()).queryByRole("button", { name: "Confirm" })).not.toBeInTheDocument();
  });

  it("KMS-400195: shows the refusal and takes the buttons away, since it would be refused again", async () => {
    decision = () =>
      refuse("KMS-400195", "Something in this draft has changed since it was made.", "Ask the assistant for a fresh draft.");
    await askForADraft();
    await act(async () => {
      fireEvent.click(within(log()).getByRole("button", { name: "Confirm" }));
    });

    await waitFor(() => expect(within(log()).getByText("KMS-400195")).toBeInTheDocument());
    expect(within(log()).getByText("Something in this draft has changed since it was made.")).toBeInTheDocument();
    expect(within(log()).getByText("Ask the assistant for a fresh draft.")).toBeInTheDocument();
    expect(within(log()).queryByRole("button", { name: "Confirm" })).not.toBeInTheDocument();
    expect(within(log()).queryByRole("button", { name: "Reject" })).not.toBeInTheDocument();
  });

  it("KMS-400196: shows the refusal and reads the card back from the conversation", async () => {
    decision = () => refuse("KMS-400196", "This draft was already handled.", "Its card shows what happened to it.");
    // Confirmed somewhere else (another tab) before this press reached the server.
    saved = () => ({
      id: "c1",
      title: "t",
      createdAt: "",
      updatedAt: "",
      messages: [
        { id: "m1", role: "USER", content: "Order toor dal", createdAt: "" },
        { id: "m2", role: "ASSISTANT", content: "Here is the draft.", createdAt: "", proposals: [CONFIRMED()] },
      ],
    });
    await askForADraft();
    await act(async () => {
      fireEvent.click(within(log()).getByRole("button", { name: "Confirm" }));
    });

    await waitFor(() => expect(within(log()).getByText("KMS-400196")).toBeInTheDocument());
    expect(within(log()).getByText("This draft was already handled.")).toBeInTheDocument();
    expect(within(log()).getByText("Its card shows what happened to it.")).toBeInTheDocument();
    expect(calls.some((c) => c.url.endsWith("/conversations/c1") && c.init.method === "GET")).toBe(true);
    expect(within(log()).getByText("Saved")).toBeInTheDocument();
    expect(within(log()).getByRole("link", { name: "Open PO-0047" })).toHaveAttribute("href", "/orders/o47");
  });

  it("a request that never reached the server keeps the buttons, so it can be pressed again", async () => {
    decision = () => {
      throw new TypeError("Failed to fetch");
    };
    await askForADraft();
    await act(async () => {
      fireEvent.click(within(log()).getByRole("button", { name: "Confirm" }));
    });

    await waitFor(() => expect(within(log()).getByText("KMS-0000")).toBeInTheDocument());
    expect(within(log()).getByRole("button", { name: "Confirm" })).not.toBeDisabled();
    // Pressed again, the old refusal goes and the new answer shows.
    decision = () => ok(CONFIRMED());
    await act(async () => {
      fireEvent.click(within(log()).getByRole("button", { name: "Confirm" }));
    });
    await waitFor(() => expect(within(log()).getByText("Saved")).toBeInTheDocument());
    expect(within(log()).queryByText("KMS-0000")).not.toBeInTheDocument();
  });
});

describe("expiry by the clock", () => {
  it("a PROPOSED draft whose time has passed shows Expired and offers no Confirm", () => {
    render(<DraftCard proposal={draft({ expiresAt: new Date(Date.now() - 1000).toISOString() })} onDecide={async () => {}} />);
    expect(screen.getByText("Expired")).toBeInTheDocument();
    expect(screen.getByText("This draft expired after 24 hours. Nothing was saved. Ask again for a fresh one.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Confirm" })).not.toBeInTheDocument();
  });

  it("turns to Expired by itself when its time passes on screen", () => {
    vi.useFakeTimers();
    render(<DraftCard proposal={draft({ expiresAt: new Date(Date.now() + 60_000).toISOString() })} onDecide={async () => {}} />);
    expect(screen.getByText("Not saved yet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm" })).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(61_000);
    });
    expect(screen.getByText("Expired")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Confirm" })).not.toBeInTheDocument();
  });
});

describe("a reload", () => {
  it("brings the cards back from the conversation, each with its current status", async () => {
    window.sessionStorage.setItem(STORAGE_KEY, "c1");
    saved = () => ({
      id: "c1",
      title: "t",
      createdAt: "",
      updatedAt: "",
      messages: [
        { id: "m1", role: "USER", content: "Order toor dal", createdAt: "" },
        { id: "m2", role: "ASSISTANT", content: "Here is the draft.", createdAt: "", proposals: [CONFIRMED()] },
        { id: "m3", role: "USER", content: "And one for jaggery", createdAt: "" },
        {
          id: "m4",
          role: "ASSISTANT",
          content: "Here it is.",
          createdAt: "",
          proposals: [draft({ actionId: "p2", title: "Purchase order for Mandya Jaggery Co." })],
        },
      ],
    });
    render(<AssistantLauncher />);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Ask the assistant" }));
    });

    await waitFor(() => expect(within(log()).getByText("Purchase order for Mandya Jaggery Co.")).toBeInTheDocument());
    expect(within(log()).getByRole("link", { name: "Open PO-0047" })).toHaveAttribute("href", "/orders/o47");
    expect(within(log()).getByText("Saved")).toBeInTheDocument();
    expect(within(log()).getByText("Not saved yet")).toBeInTheDocument();
    // One live draft, so one Confirm; the confirmed one has none.
    expect(within(log()).getAllByRole("button", { name: "Confirm" })).toHaveLength(1);
    // Each card follows its reply's words.
    const text = log().textContent ?? "";
    expect(text.indexOf("Here is the draft.")).toBeLessThan(text.indexOf("Open PO-0047"));
    expect(text.indexOf("Open PO-0047")).toBeLessThan(text.indexOf("And one for jaggery"));
  });
});

describe("the empty conversation", () => {
  it("offers a stock question and a purchase order", () => {
    render(<AssistantLauncher />);
    fireEvent.click(screen.getByRole("button", { name: "Ask the assistant" }));
    expect(screen.getByText("Ask about stock or orders")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "How much ತೊಗರಿ ಬೇಳೆ do we have?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Make a purchase order for toor dal" })).toBeInTheDocument();
  });
});

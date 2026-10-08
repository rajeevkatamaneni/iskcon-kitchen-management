import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ReadableStream as NodeReadableStream } from "node:stream/web";
import { parseAssistantFrame } from "@/lib/api";

/*
 * E10-T14: the Kitchen Assistant panel. The api is replaced by a fetch stub whose message endpoint
 * answers with a stream this test feeds by hand, chunk by chunk, so "the words appear in the order
 * they arrive" is checked between chunks rather than only at the end.
 */

const { authRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", userId: "u1", tenantId: "t1", fullName: "Govinda Das" },
      getToken: async () => "token-1",
    } as { status: string; appUser: { role: string; userId: string; tenantId: string; fullName: string } | null; getToken: () => Promise<string> },
  },
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));

import { AssistantLauncher } from "@/components/assistant/AssistantPanel";

const encoder = new TextEncoder();
const frame = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

/** A message endpoint whose body this test writes to, one chunk at a time. */
function controlledStream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const body = new NodeReadableStream<Uint8Array>({
    start(c) {
      controller = c as unknown as ReadableStreamDefaultController<Uint8Array>;
    },
  });
  return {
    body,
    push: async (text: string) => {
      await act(async () => {
        controller.enqueue(encoder.encode(text));
        await new Promise((r) => setTimeout(r, 0));
      });
    },
    end: async () => {
      await act(async () => {
        controller.close();
        await new Promise((r) => setTimeout(r, 0));
      });
    },
  };
}

type Call = { url: string; init: RequestInit };
let calls: Call[];
let messageResponse: () => unknown;

beforeEach(() => {
  calls = [];
  authRef.current = {
    status: "signed-in",
    appUser: { role: "KITCHEN_MANAGER", userId: "u1", tenantId: "t1", fullName: "Govinda Das" },
    getToken: async () => "token-1",
  };
  window.sessionStorage.clear();
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      if (url.endsWith("/api/v1/assistant/conversations") && init.method === "POST") {
        return { ok: true, status: 201, json: async () => ({ id: "c1", title: null, createdAt: "", updatedAt: "" }) };
      }
      if (url.endsWith("/messages")) return messageResponse();
      throw new Error(`unexpected ${url}`);
    })
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function openAndAsk(question: string) {
  render(<AssistantLauncher />);
  fireEvent.click(screen.getByRole("button", { name: "Ask the assistant" }));
  const box = screen.getByLabelText("Message the assistant");
  fireEvent.change(box, { target: { value: question } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
  });
  return box;
}

const log = () => screen.getByTestId("assistant-messages");

describe("who sees the assistant", () => {
  it.each(["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"])("%s gets the button", (role) => {
    authRef.current.appUser!.role = role;
    render(<AssistantLauncher />);
    expect(screen.getByRole("button", { name: "Ask the assistant" })).toBeInTheDocument();
  });

  it.each(["VOLUNTEER", "SUPER_ADMIN"])("%s does not", (role) => {
    authRef.current.appUser!.role = role;
    render(<AssistantLauncher />);
    expect(screen.queryByRole("button", { name: "Ask the assistant" })).not.toBeInTheDocument();
  });

  it("nobody signed out does", () => {
    authRef.current = { ...authRef.current, status: "signed-out", appUser: null };
    render(<AssistantLauncher />);
    expect(screen.queryByRole("button", { name: "Ask the assistant" })).not.toBeInTheDocument();
  });
});

describe("a streamed answer", () => {
  it("shows the words in the order they arrive, including a frame split across two chunks", async () => {
    const stream = controlledStream();
    messageResponse = () => ({ ok: true, status: 200, body: stream.body });
    await openAndAsk("How much toor dal do we have?");

    // Sent with the person's token, and accepting JSON as well as the stream (so a refusal keeps its code).
    const sent = calls.find((c) => c.url.endsWith("/conversations/c1/messages"))!;
    const headers = sent.init.headers as Record<string, string>;
    expect(headers.Accept).toBe("text/event-stream, application/json");
    expect(headers.Authorization).toBe("Bearer token-1");
    expect(JSON.parse(String(sent.init.body))).toEqual({ text: "How much toor dal do we have?" });

    await stream.push(": run r1\n\n" + frame("text", { text: "There are " }));
    expect(log()).toHaveTextContent("There are");
    expect(screen.getByRole("button", { name: "Stop" })).toBeInTheDocument();

    // Half a frame: nothing new may show until the rest of it arrives.
    const second = frame("text", { text: "42 Kg " });
    await stream.push(second.slice(0, 15));
    expect(log()).not.toHaveTextContent("42 Kg");
    await stream.push(second.slice(15) + frame("text", { text: "in the Main Store." }));
    expect(log()).toHaveTextContent("There are 42 Kg in the Main Store.");

    await stream.push(frame("done", { messageId: "m2", runId: "r1" }));
    await stream.end();
    expect(screen.getByRole("button", { name: "Send" })).toBeInTheDocument();
    // The question sits above the answer.
    const text = log().textContent ?? "";
    expect(text.indexOf("How much toor dal")).toBeLessThan(text.indexOf("There are 42 Kg"));
  });

  it("shows a lookup while it runs, then a quiet record of what was checked", async () => {
    const stream = controlledStream();
    messageResponse = () => ({ ok: true, status: 200, body: stream.body });
    await openAndAsk("How much toor dal?");

    await stream.push(frame("tool_call", { tool: "get_inventory", summary: "item_name=toor dal" }));
    expect(within(log()).getByText("Checking stock…")).toBeInTheDocument();

    await stream.push(frame("text", { text: "42 Kg." }) + frame("done", { messageId: "m", runId: "r" }));
    await stream.end();
    expect(within(log()).queryByText("Checking stock…")).not.toBeInTheDocument();
    expect(within(log()).getByText("Checked stock: toor dal")).toBeInTheDocument();
    // The tool's own name is a programmer's word and never reaches the screen.
    expect(log()).not.toHaveTextContent("get_inventory");
  });
});

describe("failures", () => {
  it("an error event shows the server's words, its next step, its code and Try again", async () => {
    const stream = controlledStream();
    messageResponse = () => ({ ok: true, status: 200, body: stream.body });
    await openAndAsk("What is running low?");

    await stream.push(frame("tool_call", { tool: "list_low_stock", summary: "" }));
    await stream.push(
      frame("error", {
        code: "KMS-500009",
        message: "The assistant took too long to answer, so it was stopped.",
        action: "Nothing was changed. Try again, or ask for one thing at a time.",
        runId: "r9",
      })
    );
    await stream.end();

    expect(within(log()).getByText("The assistant took too long to answer, so it was stopped.")).toBeInTheDocument();
    expect(within(log()).getByText("Nothing was changed. Try again, or ask for one thing at a time.")).toBeInTheDocument();
    expect(within(log()).getByText("KMS-500009")).toBeInTheDocument();
    expect(within(log()).getByText("Checking what is running low stopped")).toBeInTheDocument();
    expect(within(log()).getByRole("button", { name: "Try again" })).toBeInTheDocument();
    // The box stays open: a timeout is not a reason to stop asking.
    expect(screen.getByLabelText("Message the assistant")).not.toBeDisabled();
  });

  it("a daily limit comes back as JSON with its code, and closes the box for today", async () => {
    messageResponse = () => ({
      ok: false,
      status: 429,
      body: null,
      json: async () => ({
        code: "KMS-400192",
        message: "You've asked the assistant as much as one person can in a day.",
        action:
          "It's available to you again tomorrow. Until then, use the app's own pages, or ask a Temple Admin to raise the daily limit for each person.",
        fieldErrors: [],
      }),
    });
    const box = await openAndAsk("What is running low?");

    await waitFor(() => expect(within(log()).getByText("KMS-400192")).toBeInTheDocument());
    expect(within(log()).getByText("You've asked the assistant as much as one person can in a day.")).toBeInTheDocument();
    expect(within(log()).getByText(/available to you again tomorrow/)).toBeInTheDocument();
    // Asking again would fail the same way, so there is no Try again, and the box is closed.
    expect(within(log()).queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
    expect(box).toBeDisabled();
    expect(box).toHaveAttribute("placeholder", "Available again tomorrow");
  });
});

describe("reading the stream's frames", () => {
  it("skips the opening comment and unknown events, and reads the known ones", () => {
    expect(parseAssistantFrame(": run 123")).toBeNull();
    expect(parseAssistantFrame('event: something_new\ndata: {"a":1}')).toBeNull();
    expect(parseAssistantFrame('event: text\ndata: {"text":"ತೊಗರಿ ಬೇಳೆ"}')).toEqual({ type: "text", text: "ತೊಗರಿ ಬೇಳೆ" });
    expect(parseAssistantFrame('event: error\ndata: {"code":"KMS-500008","message":"m","action":"a","runId":"r"}')).toEqual({
      type: "error",
      code: "KMS-500008",
      message: "m",
      action: "a",
    });
  });
});

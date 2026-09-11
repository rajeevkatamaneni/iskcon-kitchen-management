import { beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * Every refusal the session layer understands still says its own words to the person it refuses.
 *
 * <p><strong>The defect this exists to stop from coming back.</strong> `/whoami` answers a refusal
 * with the whole error contract — code, message, next step. `auth-context` read the code, mapped it
 * through `REFUSALS` to an `AuthStatus`, and dropped the rest, so `KMS-400020`'s sentence had never
 * in the product's life appeared on a screen. `KMS-400019`'s did, but only because somebody had
 * retyped it by hand into `AccountDisabled` — a coincidence kept up by hand, not a mechanism. The
 * proof that it was only a coincidence is one file away: T-114 reworded `KMS-400020`'s next step on
 * 2026-09-10 and no screen in the application changed, because no screen was reading it.
 *
 * <p><strong>Why `ErrorCodeTest` could not have caught it.</strong> It walks the enum and proves
 * every code *has* plain-language text and a next step. Nothing proved the text arrives anywhere.
 * That is the third unpoliced channel to the same reader found in a week, after validation field
 * errors (T-098) and stock-movement labels (T-125); in all three the single instance was the
 * smaller half of the problem, and in all three the fix was a check that reads both ends.
 *
 * <p><strong>Why it reads source text, which this codebase otherwise avoids.</strong> The two
 * things that have to agree — the Java enum and a TypeScript screen — are in different languages
 * and different build systems, so no compiler can hold them together. `movement-labels.test.tsx`
 * (T-125) and `CommunicationSendGuardSourceTest.java` (T-104) are the precedents for writing one of
 * these so it cannot pass vacuously: locate the thing first, prove the parse found something real,
 * and fail loudly with an instruction rather than guessing when the shape it reads changes.
 *
 * <p><strong>The scope rule is structural, not a list.</strong> A code is in scope if and only if
 * `REFUSALS` in `lib/auth-context.tsx` names it. Nothing here enumerates codes by hand, so adding a
 * refusal to that table puts it under this guard in the same commit, which is exactly how the
 * defect got in: a line was added to a routing table and nobody asked what the reader would be
 * told when they arrived.
 */

const AUTH_CONTEXT_PATH = join("lib", "auth-context.tsx");
const ERROR_CODE_PATH = join(
  "..", "backend", "src", "main", "java", "org", "iskcon", "kms", "error", "ErrorCode.java"
);

/**
 * Comments out, before anything is matched.
 *
 * <p>Both files talk about themselves at length — `auth-context`'s own javadoc names `REFUSALS` and
 * quotes `KMS-400019`, and `ErrorCode.java` carries paragraphs naming constants that have been
 * deleted. Read naively, each file "declares" things it does not have.
 */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

// ---------------------------------------------------------------------------
// What the session layer routes, read out of the session layer.
// ---------------------------------------------------------------------------

/** Every code `REFUSALS` names, against the `AuthStatus` it is mapped to. */
function refusalTable(): Map<string, string> {
  const source = withoutComments(readFileSync(AUTH_CONTEXT_PATH, "utf8"));

  const block = source.match(/const REFUSALS\s*:[^=]*=\s*\{([\s\S]*?)\}/);
  if (!block) {
    throw new Error(
      `${AUTH_CONTEXT_PATH} no longer declares "const REFUSALS: ... = { ... }". This test reads that ` +
        `table to decide which codes are in scope; rewrite the parse deliberately rather than ` +
        `deleting it, or every assertion below silently starts checking nothing.`
    );
  }

  const table = new Map<string, string>();
  for (const [, constant, status] of block[1].matchAll(
    /\[\s*([A-Za-z_][A-Za-z0-9_]*)\s*\]\s*:\s*"([a-z-]+)"/g
  )) {
    const declared = source.match(
      new RegExp(`const\\s+${constant}\\s*=\\s*"(KMS-\\d{6})"`)
    );
    if (!declared) {
      throw new Error(
        `REFUSALS routes ${constant}, but ${AUTH_CONTEXT_PATH} has no ` +
          `\`const ${constant} = "KMS-nnnnnn"\` for it. Codes are compared as literals on purpose ` +
          `(see the comment above those constants); if the shape has changed, update this parse.`
      );
    }
    table.set(declared[1], status);
  }
  return table;
}

// ---------------------------------------------------------------------------
// What the catalogue says, read out of the catalogue.
// ---------------------------------------------------------------------------

interface Words {
  message: string;
  action: string;
}

/** A Java string expression — one literal, or several joined with `+` — as the string it evaluates to. */
function javaString(expression: string): string {
  const literals = [...expression.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map(([, body]) => body);
  return literals
    .join("")
    .replace(/\\n/g, "\n")
    .replace(/\\t/g, "\t")
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, "\\");
}

/**
 * Splits a Java argument list on its top-level commas.
 *
 * <p>Written as a scan rather than a regex because the arguments are user-facing prose: a message
 * is free to contain a comma, a bracket or an escaped quote, and every one of those has already
 * been tried by somebody writing copy.
 */
function splitArguments(inner: string): string[] {
  const args: string[] = [];
  let current = "";
  let inString = false;
  let escaped = false;
  let depth = 0;

  for (const character of inner) {
    if (inString) {
      current += character;
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === '"') inString = false;
      continue;
    }
    if (character === '"') { inString = true; current += character; continue; }
    if (character === "(") { depth += 1; current += character; continue; }
    if (character === ")") { depth -= 1; current += character; continue; }
    if (character === "," && depth === 0) { args.push(current); current = ""; continue; }
    current += character;
  }
  args.push(current);
  return args;
}

/** `KMS-nnnnnn` → the message and next step `ErrorCode.java` holds against it. */
function catalogue(): Map<string, Words> {
  const source = withoutComments(readFileSync(ERROR_CODE_PATH, "utf8"));
  const words = new Map<string, Words>();

  for (const match of source.matchAll(/\b[A-Z][A-Z0-9_]*\(\s*(\d{6})\s*,/g)) {
    const open = source.indexOf("(", match.index!);
    let depth = 0;
    let inString = false;
    let escaped = false;
    let close = -1;

    for (let i = open; i < source.length; i += 1) {
      const character = source[i];
      if (inString) {
        if (escaped) escaped = false;
        else if (character === "\\") escaped = true;
        else if (character === '"') inString = false;
        continue;
      }
      if (character === '"') inString = true;
      else if (character === "(") depth += 1;
      else if (character === ")") {
        depth -= 1;
        if (depth === 0) { close = i; break; }
      }
    }
    if (close === -1) continue;

    const args = splitArguments(source.slice(open + 1, close));
    if (args.length < 4) continue;
    words.set(`KMS-${match[1]}`, {
      message: javaString(args[2]),
      action: javaString(args[3]),
    });
  }

  if (words.size < 50) {
    throw new Error(
      `Only ${words.size} constants parsed out of ${ERROR_CODE_PATH}, which cannot be right — it ` +
        `holds well over a hundred. The parse has stopped matching the file's shape; fix it rather ` +
        `than lowering this number, because a parse that finds nothing makes every assertion below ` +
        `compare an empty string against an empty string and report success.`
    );
  }
  return words;
}

// ---------------------------------------------------------------------------
// The provider, driven for real. Firebase and the API are the only stubs.
// ---------------------------------------------------------------------------

const { whoami, temples, joinTemple, authState } = vi.hoisted(() => ({
  whoami: vi.fn(),
  temples: vi.fn(),
  joinTemple: vi.fn(),
  authState: { listener: null as null | ((u: unknown) => void) },
}));

vi.mock("firebase/auth", () => ({
  onAuthStateChanged: (_auth: unknown, cb: (u: unknown) => void) => {
    authState.listener = cb;
    return () => undefined;
  },
  signInWithPopup: vi.fn(),
  signOut: vi.fn(),
  GoogleAuthProvider: class {},
}));

vi.mock("@/lib/firebase", () => ({
  firebaseConfigured: true,
  getFirebaseAuth: () => ({ currentUser: { getIdToken: async () => "token" } }),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, whoami, temples, joinTemple },
    setActiveTempleId: vi.fn(),
  };
});

vi.mock("@/lib/nav", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/nav")>();
  return { ...actual, forgetSidebarScroll: vi.fn() };
});

const { replaceMock } = vi.hoisted(() => ({ replaceMock: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

import { render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";
import { AuthProvider } from "@/lib/auth-context";
import { RequireRole } from "@/components/RequireRole";
import ChooseTemplePage from "@/app/choose-temple/page";

/**
 * Where a refused reader ends up, per status, and what is on the screen when they get there.
 *
 * <p>A status missing from here fails loudly rather than being skipped. That is the whole point:
 * the way this defect arrives is somebody adding a refusal and nobody asking what its reader is
 * told, so a new `AuthStatus` has to stop the build until somebody names the screen.
 */
const ARRIVALS: Record<
  string,
  { where: string; redirectsTo: string | null; render: () => void }
> = {
  disabled: {
    where: "the disabled screen (components/RequireRole.tsx → AccountDisabled)",
    // Stays put on purpose: the temple picker would offer to sign this person up somewhere.
    redirectsTo: null,
    render: () =>
      void render(
        <AuthProvider>
          <RequireRole roles={["TEMPLE_ADMIN"]}>
            <p>Secret</p>
          </RequireRole>
        </AuthProvider>
      ),
  },
  "no-account": {
    where: "the temple picker (app/choose-temple/page.tsx)",
    redirectsTo: "/choose-temple",
    // The guard and its destination in one tree, because the claim spans both: the guard sends
    // this reader away, and the screen it sends them to is where the words have to be. Rendering
    // only the picker would prove the copy and quietly assume the routing.
    render: () =>
      void render(
        <AuthProvider>
          <RequireRole roles={["TEMPLE_ADMIN"]}>
            <p>Secret</p>
          </RequireRole>
          <ChooseTemplePage />
        </AuthProvider>
      ),
  },
};

/**
 * Codes whose **next step** is the screen itself rather than a sentence on it.
 *
 * <p>A written-down exemption and not a silent one. `KMS-400020`'s next step reads *"Choose your
 * temple to join it."*, and the screen the reader arrives on is headed *"Which temple do you serve
 * at?"* with the form that does it directly underneath — printing the sentence there would be the
 * same instruction three times over, which is noise and not copy. Its **message** is still
 * required, because the message is the reason the reader was sent and that reason is the half the
 * picker never gave them.
 *
 * <p>Entries here are checked against `REFUSALS` below, so an exemption for a code that is no
 * longer routed fails rather than sitting here quietly excusing nothing.
 */
const NEXT_STEP_IS_THE_SCREEN: Record<string, string> = {
  "KMS-400020":
    "\"Choose your temple to join it.\" is /choose-temple's own heading asked as a question, " +
    "directly above the form that does it.",
};

/** Words no catalogue would ever hold, to tell "rendered what arrived" from "rendered a literal". */
const SENTINEL: Words = {
  message: "T-116 sentinel — this sentence came off the wire and nowhere else.",
  action: "T-116 sentinel — and so did this next step.",
};

function refuse(code: string, words: Words) {
  whoami.mockRejectedValue(
    new ApiError(
      { code, message: words.message, action: words.action, fieldErrors: [] },
      401
    )
  );
}

function signIntoFirebase() {
  authState.listener?.({
    uid: "u1",
    email: "gopal@example.org",
    phoneNumber: null,
    displayName: "Gopal Das",
    getIdToken: async () => "token",
  });
}

const REFUSALS = refusalTable();
const CATALOGUE = catalogue();
const CODES = [...REFUSALS.keys()];

beforeEach(() => {
  vi.clearAllMocks();
  authState.listener = null;
  temples.mockResolvedValue([]);
  joinTemple.mockResolvedValue({});
});

// ---------------------------------------------------------------------------
// The parse itself is sound. Without these, everything below could be comparing
// an empty set against an empty set and reporting success.
// ---------------------------------------------------------------------------

describe("the two files were really read", () => {
  it("found the codes the session layer routes", () => {
    expect(CODES.length).toBeGreaterThanOrEqual(2);
    for (const code of CODES) expect(code).toMatch(/^KMS-\d{6}$/);
  });

  it("found words in the catalogue for every one of them", () => {
    for (const code of CODES) {
      const words = CATALOGUE.get(code);
      expect(words, `${code} is routed by REFUSALS but not declared in ErrorCode.java`).toBeDefined();
      expect(words!.message.length, `${code} message`).toBeGreaterThan(10);
      expect(words!.action.length, `${code} next step`).toBeGreaterThan(5);
    }
  });

  it("names the screen every routed status sends its reader to", () => {
    for (const [code, status] of REFUSALS) {
      expect(
        ARRIVALS[status],
        `REFUSALS routes ${code} to the "${status}" status and this test does not know which ` +
          `screen that reader lands on. Add it to ARRIVALS — naming the screen is the point, ` +
          `because a refusal nobody named a screen for is a refusal nobody wrote words for.`
      ).toBeDefined();
    }
  });

  it("excuses only codes that are still routed", () => {
    for (const code of Object.keys(NEXT_STEP_IS_THE_SCREEN)) {
      expect(
        REFUSALS.has(code),
        `${code} is excused from showing its next step but REFUSALS no longer routes it. ` +
          `Delete the exemption rather than leaving it to excuse nothing.`
      ).toBe(true);
    }
  });
});

// ---------------------------------------------------------------------------
// The guard.
// ---------------------------------------------------------------------------

describe.each(CODES)("%s, on the screen its reader actually reaches", (code) => {
  const status = REFUSALS.get(code)!;
  const arrival = ARRIVALS[status];
  const words = CATALOGUE.get(code)!;
  const exempt = NEXT_STEP_IS_THE_SCREEN[code];

  it(`says what ErrorCode.java says, on ${arrival.where}`, async () => {
    refuse(code, words);
    arrival.render();
    signIntoFirebase();

    expect(await screen.findByText(words.message)).toBeInTheDocument();

    // Body copy, never the heading. A catalogue message is a sentence and ends in a full stop; no
    // h1 in this application does. Rendering one as a heading forces a choice between a punctuated
    // heading and trimming the stored text on its way to the screen, and trimming is the worse of
    // the two — it puts a transformation between what support reads and what the reader reads,
    // which is a smaller version of the gap this whole test exists to close. Both screens keep
    // their own short title and put the sentence underneath it.
    expect(
      screen.queryByRole("heading", { name: words.message }),
      `${code}'s message is being used as a heading. Give the screen its own short title and ` +
        `render the catalogue's sentence as body copy beneath it.`
    ).not.toBeInTheDocument();

    if (exempt) {
      // Named, not skipped — see NEXT_STEP_IS_THE_SCREEN.
      expect(exempt.length).toBeGreaterThan(0);
    } else {
      expect(screen.getByText(words.action)).toBeInTheDocument();
    }

    if (arrival.redirectsTo) {
      // The routing is part of the claim: these are the words on the screen the guard sends this
      // reader to, so the guard has to be sending them there.
      await waitFor(() => expect(replaceMock).toHaveBeenCalledWith(arrival.redirectsTo));
    }
  });

  it("renders the words that arrived, not a literal that happens to match them", async () => {
    // The half a hand-typed copy passes without noticing. `AccountDisabled` held two string
    // literals that matched `ErrorCode.java` exactly, so "the catalogue's words are on the screen"
    // was true of a screen that had never read the catalogue — and would have stayed true the day
    // the catalogue changed underneath it.
    refuse(code, SENTINEL);
    arrival.render();
    signIntoFirebase();

    expect(await screen.findByText(SENTINEL.message)).toBeInTheDocument();
    if (!exempt) expect(screen.getByText(SENTINEL.action)).toBeInTheDocument();

    // And it is showing that instead of, not as well as, whatever it used to say.
    expect(screen.queryByText(words.message)).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// The refusal that is not one.
// ---------------------------------------------------------------------------

/**
 * A 401 the server declined to explain has no body, so `api.ts` gives it the same synthesised
 * envelope a dropped connection gets — `KMS-0000`, *"We couldn't reach the server."* That sentence
 * is false about a refusal, and the temple picker telling an expired session it has no account here
 * would be a statement about the person that nobody has made. Both are worse than silence, which is
 * what this asserts they get.
 */
describe("an unexplained 401 is given no words at all", () => {
  it("still lands on the temple picker, and it still says nothing about why", async () => {
    refuse("KMS-0000", {
      message: "We couldn't reach the server.",
      action: "Check your connection and try again.",
    });
    ARRIVALS["no-account"].render();
    signIntoFirebase();

    expect(
      await screen.findByRole("heading", { name: /which temple do you serve at\?/i })
    ).toBeInTheDocument();
    expect(screen.queryByText("We couldn't reach the server.")).not.toBeInTheDocument();
    expect(screen.queryByText("Check your connection and try again.")).not.toBeInTheDocument();
    // Nor the sentence belonging to the code that *is* routed to this screen, which it did not send.
    expect(screen.queryByText(CATALOGUE.get("KMS-400020")!.message)).not.toBeInTheDocument();
  });
});

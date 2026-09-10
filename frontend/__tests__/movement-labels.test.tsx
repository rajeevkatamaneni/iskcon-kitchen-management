import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { StockDetail, StockItemView, StockMovement } from "@/lib/api";

/**
 * The Type column on a stock movement never shows a stored constant.
 *
 * <p>Curd's stock page on staging showed a row whose Type read `USED_BEYOND_RECORDED_STOCK`, in
 * capitals, between rows saying "Cooked" and "Adjustment" (2026-09-09, signed in as the Temple
 * Admin). `TYPE_LABEL` on the item screen held four of the seven movement kinds; the other three
 * arrived with later work — `ISSUE`, `RETURN_TO_VENDOR` (T-103) and `USED_BEYOND_RECORDED_STOCK`
 * (T-122) — and each fell through the `?? m.type` fallback to print itself.
 *
 * <p><strong>Why a source-text assertion, which this codebase otherwise avoids.</strong>
 * `StockMovement.type` is `string` in `lib/api.ts` — it is a stored value that arrives over JSON,
 * not a union — so no amount of TypeScript can notice that the map is missing a member of a Java
 * enum. The two files that have to agree are in different languages and different build systems,
 * and the only thing that can hold them together is something that reads both. `CLAUDE.md` makes
 * this rule explicit for error codes and `ErrorCodeTest` enforces it there; this is the same reader
 * reached through a second channel that had nothing watching it.
 *
 * <p>The precedent for writing one of these so it cannot pass vacuously is
 * `communication/CommunicationSendGuardSourceTest.java` (T-104): locate the thing first, prove the
 * parse found something real, and fail loudly with an instruction rather than guessing when the
 * shape it depends on changes. The three "the parse itself is sound" cases below are that: without
 * them, a regex that silently matched nothing would leave every other assertion comparing an empty
 * set against an empty set and reporting success.
 */

const ENUM_PATH = join("..", "backend", "src", "main", "java", "org", "iskcon", "kms", "inventory", "MovementType.java");
const SCREEN_PATH = join("app", "inventory", "[id]", "page.tsx");

/**
 * The constants declared by `MovementType.java`.
 *
 * <p>Comments are stripped before anything is matched, because that enum carries several pages of
 * javadoc naming its own constants in `{@link #ISSUE}` form — read naively, the file "declares"
 * each of them several times over and one that had been deleted would still appear.
 */
function movementTypeConstants(): string[] {
  const source = readFileSync(ENUM_PATH, "utf8");
  const withoutComments = source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
  const body = withoutComments.match(/enum\s+MovementType\s*\{([\s\S]*)\}/);
  if (!body) {
    throw new Error(
      `${ENUM_PATH} no longer looks like "public enum MovementType { ... }". This test compares its ` +
        `constants against the screen's label map; rewrite the parse deliberately rather than deleting it.`
    );
  }
  return body[1]
    .split(/[,;}]/)
    .map((entry) => entry.trim())
    .filter((entry) => /^[A-Z][A-Z0-9_]*$/.test(entry));
}

/** The keys of `TYPE_LABEL` on the stock item screen, read out of the object literal itself. */
function labelledTypes(): string[] {
  const source = readFileSync(SCREEN_PATH, "utf8");
  const literal = source.match(/const TYPE_LABEL: Record<string, string> = \{([\s\S]*?)\n\};/);
  if (!literal) {
    throw new Error(
      `${SCREEN_PATH} no longer declares "const TYPE_LABEL: Record<string, string> = { ... };". If the ` +
        `map moved or was renamed, point this test at its new home — do not delete it, or the Type ` +
        `column goes back to printing whatever the database stored.`
    );
  }
  return [...literal[1].matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*:/gm)].map((m) => m[1]);
}

describe("every stock movement kind has a word a person reads", () => {
  it("the parse itself is sound — the enum really was read", () => {
    const constants = movementTypeConstants();
    // Anchors, so a parse that quietly matched nothing cannot report success by comparing two
    // empty sets. These three are the oldest, the newest, and the one this defect was found on.
    expect(constants).toContain("PO_RECEIPT");
    expect(constants).toContain("CONSUMPTION");
    expect(constants).toContain("USED_BEYOND_RECORDED_STOCK");
    expect(constants.length).toBeGreaterThanOrEqual(7);
    // Javadoc naming a constant must not be counted as declaring it: the file mentions ADJUSTMENT
    // half a dozen times in prose and declares it once.
    expect(constants.filter((c) => c === "ADJUSTMENT")).toHaveLength(1);
  });

  it("the parse itself is sound — the label map really was read", () => {
    const labelled = labelledTypes();
    expect(labelled).toContain("PO_RECEIPT");
    expect(labelled.length).toBeGreaterThanOrEqual(7);
    // REASON_LABEL sits a few lines above TYPE_LABEL with the same shape. Picking up its keys
    // would make this test pass on labels belonging to a different column.
    expect(labelled).not.toContain("SPOILAGE");
  });

  it("no constant in MovementType.java is missing from the screen's TYPE_LABEL", () => {
    const missing = movementTypeConstants().filter((c) => !labelledTypes().includes(c));
    expect(
      missing,
      `these stock movement kinds would print as raw constants on the item screen's Type column — ` +
        `add each one to TYPE_LABEL in app/inventory/[id]/page.tsx:\n  ${missing.join("\n  ")}`
    ).toEqual([]);
  });

  it("no label is left behind for a constant that no longer exists", () => {
    // The other direction, and the reason this is not merely tidiness: a renamed constant leaves a
    // label that still matches nothing, so the screen shouts again while the map looks complete.
    const stale = labelledTypes().filter((k) => !movementTypeConstants().includes(k));
    expect(
      stale,
      `TYPE_LABEL names movement kinds MovementType.java does not declare:\n  ${stale.join("\n  ")}`
    ).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// And what the screen actually paints, which is the fact the source assertion is a proxy for.

const { authRef, movementsRef, getItemMock, listMovementsMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me", fullName: "Radha" },
      getToken: async () => "test-token",
      refresh: () => {},
    },
  },
  movementsRef: { current: [] as StockMovement[] },
  getItemMock: vi.fn(),
  listMovementsMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({ id: "item-1" }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/inventory/item-1",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, getInventoryItem: getItemMock, listMovements: listMovementsMock },
  };
});

import InventoryItemPage from "@/app/inventory/[id]/page";

function item(): StockItemView {
  return {
    itemId: "item-1",
    ingredientId: "ing-1",
    ingredientName: "Curd",
    category: "Dairy",
    storageLocation: "Cold room",
    unit: "L",
    onHand: 0,
    committed: 0,
    available: 0,
    reorderThreshold: 15,
    belowThreshold: true,
    expiringSoon: false,
    soonestExpiry: null,
    notes: null,
  };
}

function detail(): StockDetail {
  return { item: item(), batches: [], committed: [] };
}

function movement(type: string, id: string): StockMovement {
  return {
    id,
    ingredientId: "ing-1",
    ingredientName: "Curd",
    storageLocation: "Cold room",
    batchId: "b1",
    quantity: -2,
    unit: "L",
    type,
    expiryDate: null,
    receivedDate: null,
    reason: null,
    referenceType: null,
    referenceId: null,
    note: null,
    actorUserId: "u1",
    actorName: "Gopal Das",
    createdAt: "2026-09-01T04:00:00Z",
  };
}

describe("the Type column, on the screen", () => {
  beforeEach(() => {
    getItemMock.mockReset().mockImplementation(async () => detail());
    listMovementsMock.mockReset().mockImplementation(async () => movementsRef.current);
  });

  it("shows a word for every kind the ledger can hold, and no stored constant anywhere", async () => {
    const kinds = movementTypeConstants();
    movementsRef.current = kinds.map((k, i) => movement(k, `mv-${i}`));

    render(<InventoryItemPage />);
    await waitFor(() => expect(screen.getAllByText("Gopal Das")).toHaveLength(kinds.length));

    // The defect exactly as it was seen: the constant itself, on the page.
    for (const kind of kinds) {
      expect(screen.queryAllByText(kind), `${kind} is on the screen as its stored constant`).toHaveLength(0);
    }
    for (const word of ["Received", "Donation", "Cooked", "Adjustment", "Issued", "Sent back", "Used beyond the books"]) {
      expect(screen.getAllByText(word).length, `no row says "${word}"`).toBeGreaterThan(0);
    }
  });

  it("humanises a kind it has never heard of rather than shouting it", async () => {
    // The eighth kind, added to the enum by somebody who did not read the comment above TYPE_LABEL.
    // The test above fails that day; this is what the temple admin sees in the meantime.
    movementsRef.current = [movement("SPILLED_IN_TRANSIT", "mv-x")];

    render(<InventoryItemPage />);
    await waitFor(() => expect(screen.getAllByText("Gopal Das")).toHaveLength(1));

    expect(screen.getAllByText("Spilled in transit").length).toBeGreaterThan(0);
    expect(screen.queryAllByText("SPILLED_IN_TRANSIT")).toHaveLength(0);
  });
});

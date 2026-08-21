"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Add a wish-list item — five fields, so a screen of its own.
 *
 * <p>What is typed here is what a devotee reads before deciding to pay for it, which is the reason
 * the title and the price are asked for plainly and the description is given the width of the form.
 */

const FORM = "add-wishlist-item";
const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export default function NewWishlistItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <NewWishlistItemView />
    </RequireRole>
  );
}

function NewWishlistItemView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const title = String(f.get("title") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      await api.createWishlistItem(
        {
          title,
          description: emptyToNull(String(f.get("description") ?? "")),
          priceInr: Number(f.get("priceInr") ?? 0),
          category: String(f.get("category") ?? "OTHER"),
          quantityWanted: Number(f.get("quantityWanted") ?? 1),
          note: null,
        },
        await getToken()
      );
      router.push(`/wishlist?added=${encodeURIComponent(title)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn't add that item."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Add a wish-list item"
      who="Something devotees can fund for this temple"
      activeHref="/wishlist"
      actions={
        <>
          <ButtonLink href="/wishlist" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Add item
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      <form id={FORM} className="grid grid-cols-2 gap-4" aria-label="Add wish-list item" onSubmit={add}>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Title</span>
          <input name="title" required className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Price (₹)</span>
          <input name="priceInr" type="number" min="1" step="any" required className={FIELD} />
          <span className="pl-field-inset text-sm text-ink-secondary">What one of them costs</span>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Quantity wanted</span>
          <input name="quantityWanted" type="number" min="1" defaultValue="1" required className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Category</span>
          <select name="category" className={FIELD}>
            <option value="CONSUMABLE">Consumable</option>
            <option value="EQUIPMENT">Equipment</option>
            <option value="OTHER">Other</option>
          </select>
        </label>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Description</span>
          <input name="description" className={FIELD} />
          <span className="pl-field-inset text-sm text-ink-secondary">Devotees read this before they give</span>
        </label>
      </form>
    </FocusScreen>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}

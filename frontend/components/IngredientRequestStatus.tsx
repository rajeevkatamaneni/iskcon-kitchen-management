"use client";

import { Badge } from "@/components/ds/Badge";
import type { IngredientRequestStatus } from "@/lib/api";

/**
 * What each stored status is called where a person reads it (E10-S8).
 *
 * <p>`SUBMITTED` is the one that has to be translated rather than merely sentence-cased. To the
 * database it is "the requester has finished with it"; to everybody looking at the list it is
 * "somebody has to answer this", and the filter an approver reaches for is the second of those.
 */
export const REQUEST_STATUS_LABEL: Record<IngredientRequestStatus, string> = {
  DRAFT: "Draft",
  SUBMITTED: "Awaiting review",
  APPROVED: "Approved",
  DENIED: "Denied",
  ISSUED: "Issued",
};

/**
 * Colour is reserved for status, and each of these is genuinely a state somebody acts on.
 *
 * <p>A draft is nobody's business but its author's, so it stays neutral. Awaiting review is the
 * only one with work in it and reads as a warning. Approved is accented rather than green because
 * it is not finished — the goods have not moved yet, and issuing is what closes it.
 */
const TONE: Record<IngredientRequestStatus, "neutral" | "success" | "warning" | "danger" | "accent"> = {
  DRAFT: "neutral",
  SUBMITTED: "warning",
  APPROVED: "accent",
  DENIED: "danger",
  ISSUED: "success",
};

export function RequestStatusBadge({ status }: { status: IngredientRequestStatus }) {
  return <Badge tone={TONE[status]}>{REQUEST_STATUS_LABEL[status]}</Badge>;
}

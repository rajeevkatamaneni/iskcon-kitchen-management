"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";

/**
 * The way back from a recipe, to wherever the reader actually came from.
 *
 * <p>A recipe is reached two ways: from the Recipes page standing still, and from a search somebody
 * typed. Sending both back to a bare list throws away the second one's work — they typed "palya",
 * read one of the six results, and came back to all four hundred recipes and an empty box.
 *
 * <p>So the search travels with them. If there was text in the box, this says "Back to search" and
 * returns them to those exact results; if there was not, it says "Recipes" and goes to the list. The
 * term rides in the address, which is where the Recipes page already keeps it, so nothing has to be
 * remembered or restored.
 */
export function BackToRecipes() {
  const query = useSearchParams().get("q");
  const searching = (query ?? "").trim() !== "";

  return (
    <Link
      href={searching ? `/recipes?q=${encodeURIComponent(query as string)}` : "/recipes"}
      className="text-sm text-ink-secondary hover:text-ink"
    >
      ← {searching ? "Back to search" : "Recipes"}
    </Link>
  );
}

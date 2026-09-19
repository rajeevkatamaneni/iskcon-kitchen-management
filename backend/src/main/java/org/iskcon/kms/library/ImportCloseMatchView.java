package org.iskcon.kms.library;

import java.util.UUID;

/**
 * One ingredient name in a library recipe that is <em>close</em> to — not the same as — an
 * ingredient the temple already has (Q-11, Rajeev 2026-09-19; T-287).
 *
 * <p>The copy screen lists every one of these before anything is written and asks, for each, "Use
 * Tomato, ripe" or "It's a different ingredient". An exact match is never listed: it is the same
 * ingredient by the matcher's own rule and is mapped silently, as it always was.
 *
 * <p>The JSON names are the ones reserved in {@code frontend/lib/api.ts} and must not drift from
 * them: {@code libraryName}, {@code note}, {@code existingIngredientId}, {@code existingIngredientName}.
 *
 * @param libraryName the base name as the library writes it, after the preparation is split off
 *     (R-DUP-1) — "Tomatos" from "Tomatos, chopped". It is also the key an answer is given by.
 * @param note the note the recipe line gets if the person answers "Use", or null: the preparation
 *     split off the name ("chopped") together with whatever else the library wrote that is not the
 *     existing ingredient's name ("Ginger, peeled" with Ginger gives "peeled"; A-N5, T-297). It is
 *     shown on the screen before the person answers, so "Use Ginger" is chosen knowing the line will
 *     read Ginger · peeled. "It's a different ingredient" creates {@code libraryName} whole, so the
 *     line then keeps only the split-off preparation. Where two lines of one recipe share a base name
 *     they share one answer, and this is the first line's note; each line still gets its own.
 * @param existingIngredientId the temple's ingredient it looks like
 * @param existingIngredientName that ingredient's name, as the temple wrote it
 */
public record ImportCloseMatchView(
		String libraryName, String note, UUID existingIngredientId, String existingIngredientName) {
}

package org.iskcon.kms.library;

import java.util.UUID;

/**
 * The answer to one {@link ImportCloseMatchView} (Q-11, T-287). Exactly one of two shapes:
 *
 * <ul>
 *   <li><strong>"Use Tomato, ripe"</strong>: {@code useIngredientId} is the listed ingredient's id,
 *       and {@code confirmDifferent} is false. The line goes on that ingredient with its note kept
 *       (R-DUP-1), and nothing is created.</li>
 *   <li><strong>"It's a different ingredient"</strong>: {@code useIngredientId} is null and
 *       {@code confirmDifferent} is true — the same deliberate flag the ingredient form sends
 *       (R-DUP-2). The ingredient is created as an unmatched one is, and the override is audited.</li>
 * </ul>
 *
 * <p>Null with {@code confirmDifferent: false} is no answer at all, exactly as
 * {@code confirmDifferent: false} is on the ingredient form, and is refused as unanswered.
 *
 * <p>JSON names reserved in {@code frontend/lib/api.ts}: {@code libraryName},
 * {@code useIngredientId}, {@code confirmDifferent}.
 */
public record ImportCloseMatchDecision(String libraryName, UUID useIngredientId, boolean confirmDifferent) {
}

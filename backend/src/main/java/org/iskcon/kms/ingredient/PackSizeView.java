package org.iskcon.kms.ingredient;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One of an ingredient's alternate units (R-ING-1), as the client reads it. Mirrors
 * {@code PackSizeView} in {@code frontend/lib/api.ts}.
 *
 * @param name "Bag", "Tin", "Pack"… or null for a plain size
 * @param quantity the size as it was entered, in {@code unit} — 0.5 for "Pack = 0.5 Kg"
 * @param unit the unit it was entered in, as its stored name ("KG")
 * @param baseQuantity the same amount in the ingredient's <em>canonical</em> unit, for arithmetic:
 *     a 500 gm pack of rice kept in Kg is 0.5. Not the database's {@code base_quantity}, which is in
 *     the family's base unit (grams); the shopping list divides a need held in the canonical unit by
 *     this, so this is the figure it needs.
 * @param label the chip text, written by the server so every screen says it the same way:
 *     "250 gm", "1 Kg", "Bag = 25 Kg". The size is said in readable units (kg/L from 1,000 gm/ml
 *     up, PROCUREMENT-REQUIREMENTS §1), so a pack entered as 0.5 Kg reads "500 gm" and one entered
 *     as 1000 gm reads "1 Kg".
 */
public record PackSizeView(
		UUID id,
		String name,
		BigDecimal quantity,
		String unit,
		BigDecimal baseQuantity,
		String label) {
}

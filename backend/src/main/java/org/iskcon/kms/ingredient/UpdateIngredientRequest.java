package org.iskcon.kms.ingredient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A request to edit an ingredient's descriptive fields, and — since T-121 — its Ekadashi-prohibited
 * flag.
 *
 * <p><strong>The flag arrived here because Rajeev took it off the row (2026-09-10).</strong> It used
 * to be a button in the catalogue's Ekadashi cell that flipped the flag on one click, and his ruling
 * was that an ingredient is either in or out of the restriction for good: <em>"Ingredients don't go
 * in and out of Ekadashi restriction EVER. They are either IN or OUT. Once set CORRECTLY, there is
 * no reason to change it."</em> So the cell became a label and the flag became a checkbox inside the
 * editing row, which means it now has to ride on this body — the alternative was the client firing
 * two requests per save, with a window in which one of them has landed and the other has not.
 *
 * <p><strong>It is a boxed {@code Boolean}, and that is load-bearing rather than an oversight.</strong>
 * {@code null} means "leave the flag exactly as it is", which is the only honest thing a client
 * without {@code MANAGE_DIETARY_POLICY} can say: a Kitchen Manager may rename a prohibited
 * ingredient, and a primitive here would deserialise their absent key to {@code false} and quietly
 * un-prohibit it — or, once the service started checking, refuse every such edit with a 403 that
 * named a field they were never shown. {@code supply} below is a primitive precisely because the
 * opposite is true of it: everyone who can edit may set it, so every client can always state it.
 *
 * <p>The permission split is unchanged and is enforced in {@code IngredientService.update}, exactly
 * as {@code create} has always enforced it: moving the flag needs {@code MANAGE_DIETARY_POLICY},
 * whatever route it arrives by. The dedicated {@code PATCH /{id}/ekadashi-flag} endpoint still
 * exists and still works; it simply has no caller in this application any more.
 *
 * <p>The supply flag <em>is</em> here, and the difference is the point of D-1. A thing can stop
 * being a supply or start being one — a temple that catalogued its leaf plates as food fixes that
 * by editing the row — and there is no religious-policy decision in it, so it rides along with the
 * name and the category under {@code MANAGE_RECIPES} rather than getting an endpoint and a
 * permission of its own the way the Ekadashi flag has.
 */
public record UpdateIngredientRequest(

		@NotBlank(message = "Enter the ingredient's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@NotBlank(message = "Choose a category.")
		@Size(max = 100, message = "That category name is too long.")
		String category,

		@NotBlank(message = "Choose a unit.")
		String unit,

		/**
		 * Whether this is a supply rather than food (D-1). Primitive, so omitting it from the JSON
		 * silently turns a supply back into food — which is why the client type requires it and the
		 * catalogue's edit row always sends the value it is showing.
		 */
		boolean supply,

		/**
		 * Whether this ingredient is prohibited on Ekadashi, or {@code null} to leave the stored
		 * value alone (T-121). See the class comment: nullable is the whole mechanism, not a
		 * convenience, and there is no {@code @NotNull} here on purpose.
		 */
		Boolean ekadashiProhibited,

		List<@Size(max = 200, message = "That alias is too long.") String> aliases) {
}

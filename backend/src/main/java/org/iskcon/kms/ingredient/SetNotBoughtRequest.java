package org.iskcon.kms.ingredient;

/**
 * Marks, or unmarks, an ingredient the temple never buys — water, ice (T-402).
 *
 * <p>A body of its own on a route of its own, rather than a field on {@link UpdateIngredientRequest},
 * and the reason is concrete. {@code frontend/app/supplies/page.tsx} has an editing row that sends a
 * whole update payload built from the fields it knows about, so a boolean riding on
 * {@code PUT /ingredients/{id}} and missing from that payload would be un-set by somebody renaming a
 * mop. The Ekadashi flag survives that only because its field there is a boxed {@code Boolean} whose
 * null means "leave alone", a subtlety it took T-121 to get right. This flag does not join the PUT at
 * all, so there is nothing to get wrong.
 *
 * <p>A primitive, like {@link SetEkadashiFlagRequest} beside it: this route exists to state the value,
 * so an absent key meaning {@code false} is the right reading of an empty body and is not a trap the
 * way it would be on a payload carrying six other fields.
 */
public record SetNotBoughtRequest(boolean notBought) {
}

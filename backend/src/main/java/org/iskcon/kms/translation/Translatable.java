package org.iskcon.kms.translation;

import java.util.Locale;
import java.util.Map;
import org.iskcon.kms.ingredient.IngredientNames;

/**
 * One string on its way into another language: what the glossary is looked up on, and what the
 * machine is handed. They are not always the same string, and forgetting that is how a defect
 * Rajeev reported on 2026-09-04 survived its own fix for a fortnight.
 *
 * <p><b>What went wrong without this.</b> The recipe library files a name the way a reference book
 * does — {@code Water, hot} — and 882 of its 6,333 names are written that way. Translated faithfully
 * into Kannada that is {@code ನೀರು, ಬಿಸಿ}, which reads straight back as "Water Hot". So
 * {@link IngredientNames#readable} un-inverts the name before it is sent. On 2026-09-06 that was
 * wired into recipe translation and nowhere else, and two paths written since did the same thing the
 * same way and missed it: the purchase-order sheet a vendor receives over WhatsApp
 * ({@code DocumentGenerationService}) and the ingredient-request sheet a cook works from
 * ({@code WorkOrderService}). Each had hand-rolled "glossary first, then one MT batch", and each
 * handed the machine the filed name.
 *
 * <p>So the choice is no longer a line a caller can forget to write. A caller says what <em>kind</em>
 * of string it has — {@link #ingredientName} or {@link #text} — and the un-inversion happens inside.
 *
 * <p><b>Why the glossary is looked up on the filed name.</b> The glossary is what a temple typed in,
 * and it typed in the name as it appears on its own screens. Looking it up on the un-inverted form
 * would miss every entry a temple has written. Only the text handed to the machine is un-inverted.
 */
public record Translatable(String glossaryKey, String forMachine) {

	/**
	 * A filed ingredient name: the glossary sees it as the temple filed it, the machine sees it the
	 * way somebody says it aloud.
	 */
	public static Translatable ingredientName(String filedName) {
		return new Translatable(filedName, IngredientNames.readable(filedName));
	}

	/**
	 * Anything that is not a filed name — a dish name, a note, a reason, a free-text purpose. There
	 * is no inversion to undo, so both halves are the same string.
	 */
	public static Translatable text(String value) {
		return new Translatable(value, value);
	}

	/**
	 * The temple's own word for this, or null to send it to the machine.
	 *
	 * <p>{@link Locale#ROOT} rather than the default locale: in a Turkish locale {@code "I"} lower-cases
	 * to a dotless {@code "ı"} and the lookup silently stops matching. Two of the three call sites this
	 * replaced used the default.
	 */
	public String override(Map<String, String> glossary) {
		return glossaryKey == null ? null : glossary.get(glossaryKey.toLowerCase(Locale.ROOT));
	}
}

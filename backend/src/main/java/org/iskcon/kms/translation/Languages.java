package org.iskcon.kms.translation;

import java.util.List;

/**
 * The languages this application will render content in: English, and the 22 languages of the
 * Eighth Schedule of the Indian Constitution.
 *
 * <p>The list is fixed and is not a function of what happens to be translated already. A temple
 * cooks with whoever turns up, and which languages its cooks read has nothing to do with which
 * state it stands in — a Bengaluru kitchen with three Odia cooks and two from Assam is the ordinary
 * case, not the exception. So the choice is offered in full and the translation is produced when
 * somebody asks for it, rather than the offer being narrowed to what was translated in advance.
 *
 * <p>The codes are the ones the translation provider expects (Google Cloud Translation today), and
 * the same ones the web application's own list carries.
 */
public final class Languages {

	public static final String ENGLISH = "en";

	/** The 22 scheduled languages, by their English name, ordered alphabetically. */
	public static final List<String> SCHEDULED = List.of(
			"as", "bn", "brx", "doi", "gu", "hi", "kn", "ks", "gom", "mai", "ml",
			"mni-Mtei", "mr", "ne", "or", "pa", "sa", "sat", "sd", "ta", "te", "ur");

	/** English first, then the 22 — the full set any picker offers. */
	public static final List<String> ALL = concat();

	private static List<String> concat() {
		return java.util.stream.Stream.concat(java.util.stream.Stream.of(ENGLISH), SCHEDULED.stream())
				.toList();
	}

	private Languages() {
	}
}

package org.iskcon.kms.translation;

import java.util.List;
import java.util.Map;

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

	/**
	 * Each code's English name, the same labels {@code frontend/lib/languages.ts} shows. Added for the
	 * Kitchen Assistant (E10-LANG), whose prompt names the conversation's language in words — "Reply
	 * only in Kannada (kn)" — because a model follows a language it is told by name more reliably than
	 * a bare code, and "mni-Mtei" or "gom" means little on its own.
	 */
	private static final Map<String, String> NAMES = Map.ofEntries(
			Map.entry("en", "English"),
			Map.entry("as", "Assamese"),
			Map.entry("bn", "Bengali"),
			Map.entry("brx", "Bodo"),
			Map.entry("doi", "Dogri"),
			Map.entry("gu", "Gujarati"),
			Map.entry("hi", "Hindi"),
			Map.entry("kn", "Kannada"),
			Map.entry("ks", "Kashmiri"),
			Map.entry("gom", "Konkani"),
			Map.entry("mai", "Maithili"),
			Map.entry("ml", "Malayalam"),
			Map.entry("mni-Mtei", "Manipuri (Meitei)"),
			Map.entry("mr", "Marathi"),
			Map.entry("ne", "Nepali"),
			Map.entry("or", "Odia"),
			Map.entry("pa", "Punjabi"),
			Map.entry("sa", "Sanskrit"),
			Map.entry("sat", "Santali"),
			Map.entry("sd", "Sindhi"),
			Map.entry("ta", "Tamil"),
			Map.entry("te", "Telugu"),
			Map.entry("ur", "Urdu"));

	/** True for a code in {@link #ALL}. Exact match: codes are case-sensitive ("mni-Mtei"). */
	public static boolean isSupported(String code) {
		return code != null && NAMES.containsKey(code);
	}

	/** The English name of a supported code, e.g. "Kannada" for "kn"; the code itself if unknown. */
	public static String englishName(String code) {
		return NAMES.getOrDefault(code, code);
	}

	private static List<String> concat() {
		return java.util.stream.Stream.concat(java.util.stream.Stream.of(ENGLISH), SCHEDULED.stream())
				.toList();
	}

	private Languages() {
	}
}

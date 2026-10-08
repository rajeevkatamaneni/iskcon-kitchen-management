package org.iskcon.kms.translation;

import java.lang.Character.UnicodeScript;
import java.text.Normalizer;
import java.util.Map;
import java.util.Set;

/**
 * Which script each of {@link Languages#SCHEDULED} is written in, so a name a machine hands back can
 * be checked before it is stored (E10-NAMES-A).
 *
 * <h2>Why check the script at all</h2>
 *
 * <p>A name written into the glossary is shown to a cook every time they ask about that item, and is
 * never rewritten by a later fill. So the cheap mistakes have to be caught on the way in: an English
 * word returned untranslated, a name in the wrong language's script (Hindi asked for, Marathi is
 * Devanagari too and passes, but Kannada asked for and Telugu returned does not), a blank, a stray
 * bracket. E10-T8d made the same check for Kannada alone ({@code ^[ಀ-೿‌‍ ]+$});
 * this is that check for every language the app offers.
 *
 * <p>It is a plausibility check, not a spell-check: it cannot tell a good Telugu word from a bad
 * one. That is what the machine mark on the row is for: a person can find and correct it.
 *
 * <p>The scripts are the ones Google Cloud Translation writes for these codes, which is what the
 * glossary already holds for languages a temple has used: Sindhi and Kashmiri in Perso-Arabic,
 * Santali in Ol Chiki, Manipuri in Meetei Mayek (the code says so: {@code mni-Mtei}).
 */
public final class LanguageScripts {

	private record Script(String name, Set<UnicodeScript> scripts) {
	}

	private static final Script DEVANAGARI = new Script("Devanagari", Set.of(UnicodeScript.DEVANAGARI));
	private static final Script ARABIC = new Script("Perso-Arabic", Set.of(UnicodeScript.ARABIC));

	private static final Map<String, Script> BY_LANGUAGE = Map.ofEntries(
			Map.entry("as", new Script("Assamese (Bengali-Assamese)", Set.of(UnicodeScript.BENGALI))),
			Map.entry("bn", new Script("Bengali", Set.of(UnicodeScript.BENGALI))),
			Map.entry("brx", DEVANAGARI),
			Map.entry("doi", DEVANAGARI),
			Map.entry("gu", new Script("Gujarati", Set.of(UnicodeScript.GUJARATI))),
			Map.entry("hi", DEVANAGARI),
			Map.entry("kn", new Script("Kannada", Set.of(UnicodeScript.KANNADA))),
			Map.entry("ks", ARABIC),
			Map.entry("gom", DEVANAGARI),
			Map.entry("mai", DEVANAGARI),
			Map.entry("ml", new Script("Malayalam", Set.of(UnicodeScript.MALAYALAM))),
			Map.entry("mni-Mtei", new Script("Meetei Mayek", Set.of(UnicodeScript.MEETEI_MAYEK))),
			Map.entry("mr", DEVANAGARI),
			Map.entry("ne", DEVANAGARI),
			Map.entry("or", new Script("Odia", Set.of(UnicodeScript.ORIYA))),
			Map.entry("pa", new Script("Gurmukhi", Set.of(UnicodeScript.GURMUKHI))),
			Map.entry("sa", DEVANAGARI),
			Map.entry("sat", new Script("Ol Chiki", Set.of(UnicodeScript.OL_CHIKI))),
			Map.entry("sd", ARABIC),
			Map.entry("ta", new Script("Tamil", Set.of(UnicodeScript.TAMIL))),
			Map.entry("te", new Script("Telugu", Set.of(UnicodeScript.TELUGU))),
			Map.entry("ur", ARABIC));

	/** Longer than any sack label; a name past this is a sentence, not a name. */
	static final int MAX_NAME_LENGTH = 80;

	/** The script's name for a prompt ("Telugu", "Devanagari"); null for English or an unknown code. */
	public static String scriptName(String language) {
		Script s = BY_LANGUAGE.get(language);
		return s == null ? null : s.name();
	}

	/**
	 * The name as it should be stored: Unicode NFC (so two spellings that look identical are
	 * identical), runs of whitespace collapsed to one space, trimmed.
	 */
	public static String tidy(String name) {
		if (name == null) {
			return "";
		}
		return Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
	}

	/**
	 * True when a tidied name is written wholly in the language's script: every character is a
	 * letter or mark of that script, a combining mark shared between scripts (zero-width joiners,
	 * which several Indic spellings need), or a space; and there is at least one letter. No Latin,
	 * digits, brackets or punctuation, which the prompt also forbids.
	 */
	public static boolean isWrittenIn(String language, String tidied) {
		Script s = BY_LANGUAGE.get(language);
		if (s == null || tidied == null || tidied.isEmpty() || tidied.length() > MAX_NAME_LENGTH) {
			return false;
		}
		boolean letter = false;
		for (int i = 0; i < tidied.length(); ) {
			int cp = tidied.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == ' ' || cp == 0x200C || cp == 0x200D) {
				continue;
			}
			UnicodeScript script = UnicodeScript.of(cp);
			if (script == UnicodeScript.INHERITED) {
				continue;
			}
			if (!s.scripts().contains(script) || Character.isDigit(cp)) {
				return false;
			}
			letter |= Character.isLetter(cp);
		}
		return letter;
	}

	private LanguageScripts() {
	}
}

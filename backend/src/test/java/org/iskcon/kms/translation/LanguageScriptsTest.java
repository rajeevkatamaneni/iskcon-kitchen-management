package org.iskcon.kms.translation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The script check a machine name must pass before it is stored (E10-NAMES-A). */
class LanguageScriptsTest {

	@Test
	@DisplayName("every scheduled language has a script")
	void everyLanguageHasAScript() {
		for (String code : Languages.SCHEDULED) {
			assertThat(LanguageScripts.scriptName(code)).as(code).isNotBlank();
		}
		assertThat(LanguageScripts.scriptName(Languages.ENGLISH)).isNull();
	}

	@Test
	@DisplayName("a name in the language's own script passes, joiners and spaces included")
	void ownScriptPasses() {
		assertThat(LanguageScripts.isWrittenIn("kn", "ತೊಗರಿ ಬೇಳೆ")).isTrue();
		assertThat(LanguageScripts.isWrittenIn("te", "కంది పప్పు")).isTrue();
		assertThat(LanguageScripts.isWrittenIn("hi", "अरहर दाल")).isTrue();
		assertThat(LanguageScripts.isWrittenIn("mr", "तूर डाळ")).as("Marathi is Devanagari too").isTrue();
		assertThat(LanguageScripts.isWrittenIn("ml", "തുവര‍പ്പരിപ്പ്")).isTrue();
		assertThat(LanguageScripts.isWrittenIn("ur", "ارہر دال")).isTrue();
	}

	@Test
	@DisplayName("English, another script, digits, punctuation and blanks fail")
	void otherThingsFail() {
		assertThat(LanguageScripts.isWrittenIn("te", "Toor dal")).isFalse();
		assertThat(LanguageScripts.isWrittenIn("te", "ತೊಗರಿ ಬೇಳೆ")).as("Kannada asked as Telugu").isFalse();
		assertThat(LanguageScripts.isWrittenIn("kn", "ತೊಗರಿ (dal)")).isFalse();
		assertThat(LanguageScripts.isWrittenIn("kn", "ತೊಗರಿ 2")).isFalse();
		assertThat(LanguageScripts.isWrittenIn("kn", "")).isFalse();
		assertThat(LanguageScripts.isWrittenIn("en", "Rice")).isFalse();
		assertThat(LanguageScripts.isWrittenIn("kn", "ಅ".repeat(LanguageScripts.MAX_NAME_LENGTH + 1))).isFalse();
	}

	@Test
	@DisplayName("tidy collapses spacing and normalises to NFC")
	void tidy() {
		assertThat(LanguageScripts.tidy("  నూక   బియ్యం ")).isEqualTo("నూక బియ్యం");
		assertThat(LanguageScripts.tidy(null)).isEmpty();
	}
}

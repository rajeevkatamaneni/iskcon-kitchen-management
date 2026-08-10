package org.iskcon.kms.translation;

import java.util.List;

/**
 * Machine translation, behind a port (E2-S6). The real engine is Google Cloud Translation; a stub
 * stands in for the hermetic test suite; Bhashini can be added later as another implementation.
 * TECH_STACK.md's "Bhashini primary, Google fallback" is a matter of which provider (or ordered
 * pair) is wired — callers only see this interface.
 */
public interface TranslationProvider {

	/** Translates each text from source to target language, preserving order. */
	List<String> translate(List<String> texts, String sourceLanguage, String targetLanguage);

	/** Short provenance name recorded on a translation: 'google', 'stub', … */
	String name();
}

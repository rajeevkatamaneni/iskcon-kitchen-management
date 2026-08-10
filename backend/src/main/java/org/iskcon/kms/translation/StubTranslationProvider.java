package org.iskcon.kms.translation;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default translation provider: a deterministic fake that tags each string with the target
 * language, so the whole translation pipeline — glossary, caching, versioning, translated PDF — is
 * testable without a network or credentials. Real translation is {@link GoogleCloudTranslationProvider}
 * ({@code kms.translation.provider=google}).
 */
@Component
@ConditionalOnProperty(name = "kms.translation.provider", havingValue = "stub", matchIfMissing = true)
public class StubTranslationProvider implements TranslationProvider {

	@Override
	public List<String> translate(List<String> texts, String sourceLanguage, String targetLanguage) {
		List<String> out = new ArrayList<>(texts.size());
		for (String text : texts) {
			out.add("[" + targetLanguage + "] " + text);
		}
		return out;
	}

	@Override
	public String name() {
		return "stub";
	}
}

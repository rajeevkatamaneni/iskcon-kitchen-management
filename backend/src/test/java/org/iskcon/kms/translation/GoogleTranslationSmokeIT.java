package org.iskcon.kms.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exercises {@link GoogleCloudTranslationProvider} against the real API (E2-S6). Runs only when
 * TRANSLATION_SMOKE is set (local dev with ADC + GCP_PROJECT_ID) — never in CI, keeping the suite
 * hermetic and offline.
 */
@EnabledIfEnvironmentVariable(named = "TRANSLATION_SMOKE", matches = ".+")
class GoogleTranslationSmokeIT {

	@Test
	@DisplayName("translates English to Kannada via the real Cloud Translation API")
	void translatesToKannada() {
		String project = System.getenv("GCP_PROJECT_ID");
		try (GoogleCloudTranslationProvider provider = new GoogleCloudTranslationProvider(project, "global")) {
			List<String> out = provider.translate(List.of("Rice", "Wash the rice."), "en", "kn");

			assertThat(out).hasSize(2);
			assertThat(out.get(0)).isNotBlank().isNotEqualTo("Rice");
			// Kannada text is outside ASCII — a real translation, not a passthrough.
			assertThat(out.get(0).chars().anyMatch(c -> c > 0x0C80 && c < 0x0D00))
					.as("output is in the Kannada script block").isTrue();
			assertThat(provider.name()).isEqualTo("google");
		}
	}
}

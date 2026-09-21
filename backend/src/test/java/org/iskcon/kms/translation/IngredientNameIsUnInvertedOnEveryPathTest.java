package org.iskcon.kms.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweep that stops a fourth translation path handing the machine a filed ingredient name.
 *
 * <p><b>Why a sweep and not three tests.</b> {@code IngredientNames.readable} was written on
 * 2026-09-06 for the defect Rajeev reported from the demo — Kannada coming back as "Water Hot"
 * because the library files "Water, hot". It was wired into recipe translation and nowhere else.
 * Two paths written <em>after</em> it did the same thing the same way and missed it: the
 * purchase-order sheet a vendor receives over WhatsApp, and the ingredient-request sheet a cook
 * works from. Nobody noticed, because {@code IngredientNamesTest} tests the function and nothing
 * tested that it is reached.
 *
 * <p>It stayed invisible because the curated catalogue has no inverted names at all — 0 of the 112
 * ingredients on staging carry a comma. The library has 882 of 6,333, so the first import arms it.
 *
 * <p><b>What this asserts.</b> Any file that hands text to a {@link TranslationProvider} and also
 * mentions an ingredient name must go through {@link Translatable}, which is the only place the
 * un-inversion lives. A new path either uses it or fails here — which is the conversation worth
 * having, in the shape the frontend's `counted-units` sweep already uses.
 */
class IngredientNameIsUnInvertedOnEveryPathTest {

	/**
	 * Files that translate, mention a name, and legitimately do not need {@link Translatable}, each
	 * with the reason. Empty today, and an entry here should be argued rather than added to quiet a
	 * failure.
	 */
	private static final List<String> NOT_A_FILED_NAME = List.of();

	@Test
	@DisplayName("every path that translates an ingredient name goes through Translatable")
	void everyPathUnInverts() throws IOException {
		Path root = Path.of("src", "main", "java");
		List<String> offenders = new ArrayList<>();

		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String rel = root.relativize(file).toString();
				String source = Files.readString(file, StandardCharsets.UTF_8);

				// Translatable itself is where the un-inversion lives, and the provider interface and
				// its implementations only carry text somebody else prepared.
				if (rel.endsWith("Translatable.java")
						|| rel.endsWith("TranslationProvider.java")
						|| rel.endsWith("StubTranslationProvider.java")
						|| rel.endsWith("GoogleCloudTranslationProvider.java")) {
					continue;
				}
				if (NOT_A_FILED_NAME.contains(rel)) {
					continue;
				}

				boolean translates = source.contains("translationProvider.translate")
						|| source.contains("provider.translate(");
				boolean handlesAName = source.contains("ingredientName()")
						|| source.contains("PurchaseOrderLineView");
				if (translates && handlesAName && !source.contains("Translatable")) {
					offenders.add(rel);
				}
			}
		}

		assertThat(offenders)
				.as("these translate an ingredient name without un-inverting it, so a filed name "
						+ "like \"Water, hot\" reaches the reader as \"Water Hot\"")
				.isEmpty();
	}
}

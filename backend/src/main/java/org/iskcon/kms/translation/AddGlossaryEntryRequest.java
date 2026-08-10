package org.iskcon.kms.translation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A request to add or update a glossary override. */
public record AddGlossaryEntryRequest(

		@NotBlank(message = "Choose a language.")
		@Size(max = 20)
		String language,

		@NotBlank(message = "Enter the English term.")
		@Size(max = 200)
		String sourceTerm,

		@NotBlank(message = "Enter the preferred translation.")
		@Size(max = 200)
		String targetTerm) {
}

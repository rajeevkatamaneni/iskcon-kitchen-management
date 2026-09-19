package org.iskcon.kms.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The optional body of {@code POST /api/v1/recipes/import/{masterRecipeId}}: the person's answer to
 * every close match the copy screen listed (T-287). A recipe with no close match is still copied
 * with no body at all, which is how every caller before T-287 sends it.
 *
 * <p>JSON: {@code { "decisions": [ ImportCloseMatchDecision, … ] }}.
 */
public record ImportRecipeRequest(List<ImportCloseMatchDecision> decisions) {

	public ImportRecipeRequest {
		// Not List.copyOf: that throws on a null element, which would reach the person as an
		// unexpected failure. A null entry is kept and refused by the service as a field error.
		decisions = decisions == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(decisions));
	}
}

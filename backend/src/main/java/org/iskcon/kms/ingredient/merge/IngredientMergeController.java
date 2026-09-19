package org.iskcon.kms.ingredient.merge;

import jakarta.validation.Valid;
import java.util.List;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The duplicate-ingredient merge tool (R-DUP-3), behind {@code MERGE_INGREDIENTS} — the Temple
 * Admin's alone ({@code RolePermissions}). Merging rewrites where the temple's stock, prices and
 * recipes point, which is a decision about the whole catalogue rather than ordinary kitchen editing.
 *
 * <p>Literal paths under {@code /api/v1/ingredients}, beside {@code IngredientController}'s
 * {@code /{id}}: Spring prefers a literal segment to a variable one, so "merge-proposals" and
 * "merges" are never read as an ingredient id.
 *
 * <p>The temple is never a parameter. Every read and write runs on the request's tenant-scoped
 * connection, so the merge sees — and can merge — only this temple's ingredients.
 */
@RestController
@RequestMapping("/api/v1/ingredients")
public class IngredientMergeController {

	private final IngredientMergeService mergeService;

	public IngredientMergeController(IngredientMergeService mergeService) {
		this.mergeService = mergeService;
	}

	/** The proposed groups ("Curd, fresh / Curd, sour / Curd, whisked → Curd"). */
	@GetMapping("/merge-proposals")
	@PreAuthorize("hasAuthority('MERGE_INGREDIENTS')")
	public List<MergeProposalView> proposals() {
		return mergeService.proposals();
	}

	/** What merging this group would do: stock after, price conflicts, and any unit problem. */
	@PostMapping("/merges/preview")
	@PreAuthorize("hasAuthority('MERGE_INGREDIENTS')")
	public MergePreviewView preview(@Valid @RequestBody MergeGroupInput input) {
		return mergeService.preview(input);
	}

	/** Merges one group, in one transaction. Refused with KMS-400171, 400172 or 400173. */
	@PostMapping("/merges")
	@PreAuthorize("hasAuthority('MERGE_INGREDIENTS')")
	public MergeResultView merge(
			@AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody MergeGroupInput input) {
		return mergeService.merge(actor, input);
	}
}

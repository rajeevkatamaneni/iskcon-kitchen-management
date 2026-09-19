package org.iskcon.kms.ingredient.merge;

import java.util.List;

/**
 * A proposed group: keep one ingredient and merge the others into it ("Curd, fresh / Curd, sour /
 * Curd, whisked → Curd"). Only a proposal — the Temple Admin approves or edits it, and whatever they
 * send back is checked from scratch by {@link IngredientMergeService#merge}.
 */
public record MergeProposalView(MergeCandidateView keep, List<MergeCandidateView> merge) {
}

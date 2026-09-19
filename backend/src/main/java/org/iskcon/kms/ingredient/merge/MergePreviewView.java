package org.iskcon.kms.ingredient.merge;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a merge would do, before it is done. Mirrors {@code MergePreviewView} in
 * {@code frontend/lib/api.ts}.
 *
 * @param onHandAfter on-hand stock after the merge, in the kept ingredient's unit. When the group
 *     mixes kinds of unit it is the sum over the members that could be merged — those in the kept
 *     ingredient's kind — since pieces cannot be added to kilograms, and {@code unitProblem} says so.
 * @param conflicts every vendor with different list prices on two of the group, whatever choices the
 *     request already carries, so the screen can show the choice it is making
 * @param unitProblem non-null when the group mixes kinds of unit (the merge itself refuses with
 *     KMS-400171), naming both: "Curd is in Kg, Curd pieces is in pieces"
 */
public record MergePreviewView(
		MergeCandidateView keep,
		List<MergeCandidateView> merge,
		BigDecimal onHandAfter,
		List<MergeSupplyConflictView> conflicts,
		String unitProblem) {
}

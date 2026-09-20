package org.iskcon.kms.staff;

import java.util.List;

/**
 * One person's whole record, read in a single request (T-428).
 *
 * <p><b>Why this exists at all.</b> Until now the four screens about one person — their record,
 * updating it, terminating them, paying them — found that person by reading the whole staff register
 * and filtering it in the browser. That worked while a record was a row of a list. It stops working
 * now: the record carries a photograph, identity documents and a list of previous jobs, and none of
 * those belong on {@link StaffProfileView}, which is also served to the roster and to somebody
 * reading their own schedule, and is deliberately lean.
 *
 * <p>The other candidate was {@code GET /api/v1/staff/profiles/{id}}, which already existed. It sits
 * behind {@code MANAGE_STAFF_SCHEDULE}, and that is the whole reason it could not be used: the split
 * between the two permissions exists so a kitchen manager can be given the roster without being
 * given everybody's date of birth, and reaching a staff record through the roster permission would
 * quietly undo it. So this is a second read, behind {@code MANAGE_STAFF}, and the two stay apart.
 *
 * <p>Pay is not on here. It has its own request already, three of the four screens want it, and
 * folding it in would make the record screen wait on the payment history to draw a name.
 *
 * @param banned whether this temple has a standing record against them (B9). On the register this is
 *     a wrapper round the profile rather than a field on it, for the same reason it is a field here
 *     and not on {@link StaffProfileView}: this shape is served behind MANAGE_STAFF alone.
 */
public record StaffRecordView(
		StaffProfileView profile,
		boolean banned,
		/** Their photograph and identity documents (V155). At most one of each kind. */
		List<StaffDocumentView> documents,
		/** Where they worked before, in the order the admin entered them. */
		List<PreviousEmploymentView> previousEmployment) {
}

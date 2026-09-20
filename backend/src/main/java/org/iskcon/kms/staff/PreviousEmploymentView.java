package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One job somebody held before this temple (T-428). Rajeev's seven fields, in his order: "employer,
 * their title there, manager's name, manager's phone number, from date, to date, reason for leaving".
 *
 * <p>Everything but the employer may be missing, and that is not laxity — it is what an interview
 * actually produces. Somebody who cooked at a wedding caterer for two years often cannot name the
 * month they started or find the manager's number, and refusing the row for it would mean the temple
 * records nothing at all rather than the part it knows.
 *
 * <p><b>Nothing here is verified.</b> It is what the person said. There is no flag claiming otherwise
 * and there should not be one: a tick box nobody is accountable for is worse than no tick box.
 */
public record PreviousEmploymentView(
		UUID id,
		String employer,
		/** What that employer called the job, in their words — not one of this temple's job titles. */
		String theirTitle,
		String managerName,
		String managerPhone,
		LocalDate fromDate,
		LocalDate toDate,
		String reasonForLeaving) {
}

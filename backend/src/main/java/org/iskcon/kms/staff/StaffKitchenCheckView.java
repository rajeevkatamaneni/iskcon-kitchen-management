package org.iskcon.kms.staff;

import java.util.UUID;

/**
 * One row of the Temple Admin's "Check these kitchen assignments" list (Epic 12): somebody currently
 * employed whose kitchen the system chose — V150 put everybody already on the staff in the main
 * kitchen — and nobody has looked at since.
 *
 * <p>Its own small shape rather than the whole {@link StaffProfileView}: the list shows a name, a job
 * and a kitchen, and a row that carried a date of birth and a PAN's last four for a screen that
 * prints neither would be handing out more than the screen needs.
 *
 * @param staffId       the staff record
 * @param fullName      their name as the record holds it
 * @param jobTitleLabel what to print for their job — the temple's own words if it gave any
 * @param kitchenId     the kitchen they are in now
 * @param kitchenName   its name
 */
public record StaffKitchenCheckView(
		UUID staffId, String fullName, String jobTitleLabel, UUID kitchenId, String kitchenName) {
}

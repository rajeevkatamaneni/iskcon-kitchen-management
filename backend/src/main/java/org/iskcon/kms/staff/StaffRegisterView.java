package org.iskcon.kms.staff;

import java.util.List;

/**
 * The staff register (E6-S8): who works here now, and who used to.
 *
 * <p>Split on the server rather than left to the screen, because the two lists answer different
 * questions and the second is the one an admin goes looking for deliberately — who was the cook last
 * Janmashtami, why did they leave. Mixing them into one list with a status column buries that.
 */
public record StaffRegisterView(List<StaffProfileView> current, List<StaffProfileView> former) {
}

package org.iskcon.kms.equipment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Set — or clear — how often a piece of equipment needs servicing, and who services it (E3-S10 D3).
 *
 * <p>Its own endpoint rather than fields on {@link UpdateEquipmentRequest}, and that is the whole
 * point of it. D10 splits the two acts: kitchen staff keep {@code MANAGE_INVENTORY} and go on
 * registering equipment and changing its condition, because they are the ones standing in front of
 * the grinder when it stops; deciding that this machine needs looking at every six months and
 * naming the firm that will do it is a Temple Admin's act, behind
 * {@code MANAGE_EQUIPMENT_SERVICING}. Putting the interval on the edit form would have handed it to
 * everybody who may rename a table.
 *
 * <p>Both halves nullable, together: a null count with a null unit clears the schedule, which is
 * what a temple that has decided a trestle table needs no servicing wants to be able to say. One
 * without the other is refused — a day count with no unit cannot be shown back in the words it was
 * entered in, and a unit with no count is not an interval.
 *
 * <p>The count is bounded above so that the interval in days cannot overflow anything or express a
 * schedule nobody means: a hundred years is not a service contract.
 */
public record ServiceScheduleRequest(
		@Positive @Max(100) Integer intervalCount,
		ServiceInterval intervalUnit,
		UUID serviceProviderId) {
}

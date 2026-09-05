package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One piece of equipment given in kind: it becomes a DONATED asset in the equipment register.
 *
 * <p>A name and, if anybody wants to say more, a note. The kind it was — machine, tool, furniture —
 * went with the register's category on 2026-09-04 (V91): a closed vocabulary of three that the
 * temple could not extend was worse than none, and the name of the thing already says what it is.
 */
public record EquipmentDonationLine(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 1000) String notes) {
}

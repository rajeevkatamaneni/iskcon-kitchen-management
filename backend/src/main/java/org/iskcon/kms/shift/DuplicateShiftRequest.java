package org.iskcon.kms.shift;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Duplicate a shift onto a new date (E6-S2), carrying all its other settings. */
public record DuplicateShiftRequest(@NotNull LocalDate shiftDate) {
}

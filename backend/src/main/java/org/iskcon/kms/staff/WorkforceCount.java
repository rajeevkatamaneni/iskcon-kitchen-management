package org.iskcon.kms.staff;

import java.time.LocalDate;

/**
 * Who is actually in, on one date. Staff and volunteers are counted separately and never summed —
 * a full-time cook and a two-hour evening volunteer are not interchangeable.
 *
 * <p>This is the one figure three screens read (build brief §6b): the foot of each week-grid column,
 * the Today tile, and the pebbles on the meal planner. Computed once, in {@link WorkforceService},
 * because three screens each working it out for themselves is three screens that disagree by one and
 * nobody able to say which is right.
 */
public record WorkforceCount(LocalDate date, int staffIn, int volunteers) {
}

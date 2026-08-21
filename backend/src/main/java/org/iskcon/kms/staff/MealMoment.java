package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A meal, reduced to the only two things the head count needs to know about it: the day it is cooked
 * on and the time its food must be ready (item 19).
 *
 * <p>It lives here rather than in the planner because it is the question the roster is asked, not
 * the answer the planner keeps. {@link WorkforceService} takes a batch of these and resolves the
 * week once for all of them; passing whole meal records across the boundary would hand the roster a
 * recipe, a client and a head count it has no business reading.
 */
public record MealMoment(LocalDate date, LocalTime readyBy) {
}

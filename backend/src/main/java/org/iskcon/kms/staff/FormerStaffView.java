package org.iskcon.kms.staff;

/**
 * Somebody who used to work here, and whether this temple raised a record about them (E6-S8, B9).
 *
 * <p>The flag exists so that the register can draw a termination with a ban one glance apart from an
 * ordinary one. It replaced the link at the top of the page that used to be the only way to know
 * (item 2 of the 2026-08-21 brief): a list somewhere else answers "who have we ever recorded", but
 * it cannot answer "which of these people did we record", which is the question somebody reading the
 * former-staff table is actually asking.
 *
 * <p><b>It is a wrapper here rather than a field on {@link StaffProfileView} on purpose.</b> That
 * shape is shared with the roster and with a person's own schedule, both of which sit behind
 * {@code MANAGE_STAFF_SCHEDULE} — a permission a kitchen manager is meant to be given without being
 * handed everyone's dismissal history with it. The register is {@code MANAGE_STAFF}, and this view
 * is served from there and nowhere else, so the flag cannot follow the profile into a screen that
 * has no business with it.
 *
 * <p>A retracted record does not count. It has stopped being shown at hires, so a name drawn as
 * banned because of one would say something about that person that the platform itself no longer
 * says.
 */
public record FormerStaffView(StaffProfileView profile, boolean banned) {
}

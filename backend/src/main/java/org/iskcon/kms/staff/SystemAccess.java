package org.iskcon.kms.staff;

import org.iskcon.kms.user.User;

/**
 * What a member of staff may do in the app (E6-S8) — held apart from what they are called.
 *
 * <p>Only two of the four roles can be granted here. A platform operator is minted out of band and
 * never from inside a temple; a volunteer is what a devotee already is, and hiring someone into it
 * would mean granting them nothing. So a hire either gets one of these, or {@link #none()} — no
 * account at all.
 */
public enum SystemAccess {

	TEMPLE_ADMIN("Temple admin", User.Role.TEMPLE_ADMIN),
	KITCHEN_STAFF("Kitchen staff", User.Role.KITCHEN_STAFF);

	private final String label;
	private final User.Role role;

	SystemAccess(String label, User.Role role) {
		this.label = label;
		this.role = role;
	}

	public String label() {
		return label;
	}

	public User.Role role() {
		return role;
	}

	/** Reads better at a call site than a bare null, and says what a null there means. */
	public static SystemAccess none() {
		return null;
	}

	public static SystemAccess of(User.Role role) {
		return switch (role) {
			case TEMPLE_ADMIN -> TEMPLE_ADMIN;
			case KITCHEN_STAFF -> KITCHEN_STAFF;
			case VOLUNTEER, SUPER_ADMIN -> null;
		};
	}
}

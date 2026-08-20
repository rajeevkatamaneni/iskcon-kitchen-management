package org.iskcon.kms.staff;

import java.util.Arrays;
import java.util.List;
import org.iskcon.kms.user.User;

/**
 * What a member of staff is called (E6-S8).
 *
 * <p>A controlled vocabulary rather than free text, because free text gives a temple "Head cook",
 * "head Cook", "HC" and "Head Chef" inside a month, and then the staff list cannot be grouped and
 * the schedule cannot be read. {@link #OTHER} carries the temple's own words for a job this list
 * does not have.
 *
 * <p><b>A title is a label and gates nothing.</b> What someone may do is their {@code users.role},
 * held separately and deliberately: the moment a job title granted permissions, adding "Pujari" to a
 * dropdown would become an edit to the authorisation policy. {@link #suggestedAccess()} only
 * pre-fills the access field on the hire form — the admin chooses, and may choose otherwise.
 *
 * <p>Kept in code rather than a database CHECK, for the same reason as {@code AuditAction}: a temple
 * naming a job we did not think of should not need a migration. Names are stored as text on
 * {@code staff_profiles.job_title}, so treat an existing name as permanent.
 */
public enum JobTitle {

	// --- Administration ---
	TEMPLE_ADMINISTRATOR("Temple Administrator", Group.ADMINISTRATION, User.Role.TEMPLE_ADMIN),

	// --- Kitchen ---
	// The one title whose suggested access is its own role. The title still grants nothing — the
	// admin may override it on the hire form — but a temple appointing a kitchen manager almost
	// always means the person who runs the roster and answers leave (build brief 2026-08-20, §5).
	KITCHEN_MANAGER("Kitchen Manager", Group.KITCHEN, User.Role.KITCHEN_MANAGER),
	HEAD_COOK("Head Cook", Group.KITCHEN, User.Role.KITCHEN_STAFF),
	COOK("Cook", Group.KITCHEN, User.Role.KITCHEN_STAFF),
	ASSISTANT_COOK("Assistant Cook", Group.KITCHEN, User.Role.KITCHEN_STAFF),
	SWEET_MAKER("Sweet Maker", Group.KITCHEN, User.Role.KITCHEN_STAFF),
	PRASADAM_SERVER("Prasadam Server", Group.KITCHEN, null),

	// --- Store ---
	STORE_MANAGER("Store Manager", Group.STORE, User.Role.KITCHEN_STAFF),
	STOREKEEPER("Storekeeper", Group.STORE, User.Role.KITCHEN_STAFF),

	// --- Support ---
	HOUSEKEEPING("Housekeeping", Group.SUPPORT, null),
	DISHWASHER("Dishwasher", Group.SUPPORT, null),
	DRIVER("Driver", Group.SUPPORT, null),
	SECURITY("Security", Group.SUPPORT, null),
	OFFICE_ASSISTANT("Office Assistant", Group.SUPPORT, null),
	ACCOUNTANT("Accountant", Group.SUPPORT, null),

	OTHER("Other", Group.OTHER, null),

	/**
	 * Hired before this record existed. Written only by V57's backfill, which knew someone was
	 * employed and did not know as what — an honest gap the screen asks an admin to fill, rather than
	 * a guess that would read as a fact.
	 */
	UNRECORDED("Not recorded", Group.OTHER, null);

	/** Where the title sits on the hire form's list, so fifteen options read as four short ones. */
	public enum Group {
		ADMINISTRATION, KITCHEN, STORE, SUPPORT, OTHER
	}

	private final String label;
	private final Group group;
	private final User.Role suggestedAccess;

	JobTitle(String label, Group group, User.Role suggestedAccess) {
		this.label = label;
		this.group = group;
		this.suggestedAccess = suggestedAccess;
	}

	public String label() {
		return label;
	}

	public Group group() {
		return group;
	}

	/** The access this job usually needs, or null for a job that needs no app account at all. */
	public User.Role suggestedAccess() {
		return suggestedAccess;
	}

	/** The picklist as the hire form shows it — UNRECORDED excluded, since nobody chooses it. */
	public static List<JobTitle> choosable() {
		return Arrays.stream(values()).filter(t -> t != UNRECORDED).toList();
	}
}

package org.iskcon.kms.staff;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dated, attributed, permanent notes about a member of staff's conduct (E6-S16).
 *
 * <p>Two operations, and there will never be a third. A note is written, and notes are read. There
 * is no edit and no delete, here or in the database — {@code staff_conduct_notes} is append-only by
 * trigger (V84, using the mechanism from V49/V50), and the integration test proves the refusal
 * against the unprivileged application role rather than trusting this class to behave. A note
 * written in error is corrected by writing another one that says so.
 *
 * <p><b>Why this is not {@code staff_profiles.notes}.</b> That column has no author, no date and no
 * history, and the next person to press Save destroys what was there. For a preference or a
 * reminder that is fine, and it stays exactly as it is for those. For a record of how a real person
 * behaved it is worse than writing nothing down: on the day it matters nobody can say who wrote it,
 * when, or what it said before.
 *
 * <p><b>Who may read this.</b> {@code MANAGE_STAFF_CONDUCT_NOTES}, and only the Temple Admin holds
 * it. Not the Kitchen Manager, not Kitchen Staff. The reading is the danger rather than the writing:
 * these notes are about colleagues who work in the same room, and a permission that arrived with the
 * roster or with hiring would mean a manager reading their colleague's warning on the way to
 * somewhere else. The same argument that split {@code MANAGE_STAFF} from
 * {@code MANAGE_STAFF_SCHEDULE} (E6-S8 D9), one step further in.
 *
 * <p><b>What this is deliberately not wired to.</b> The cross-temple employment ban (E9-S2,
 * {@code employment_bans}, V65). Nothing here reads into the ban path and nothing in the ban path
 * reads from here. A ban is raised at a dismissal out of words the administrator writes at that
 * moment and stands behind; it must not be assembled from remarks other people wrote for another
 * purpose months earlier, and a ban travelling to another temple must not start carrying an internal
 * remarks file with it. Connecting the two would be its own decision, with its own story. See the
 * V84 header, and BL-6 for why an accusation about a private individual is handled this carefully.
 */
@Service
public class StaffConductNoteService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public StaffConductNoteService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * Every note on one person, newest first.
	 *
	 * <p>Newest first because the question being asked is almost always "what has happened lately",
	 * and because the oldest note is the one least likely to be the reason somebody opened the panel.
	 */
	@Transactional(readOnly = true)
	public List<StaffConductNoteView> notesFor(UUID staffId) {
		requireStaff(staffId);
		return jdbc.query("""
				SELECT n.id, n.body, n.author_user_id, u.full_name AS author_name, n.created_at
				FROM staff_conduct_notes n
				JOIN users u ON u.id = n.author_user_id
				WHERE n.staff_profile_id = ?
				ORDER BY n.created_at DESC, n.id DESC
				""",
				(rs, row) -> new StaffConductNoteView(
						rs.getObject("id", UUID.class),
						rs.getString("body"),
						rs.getObject("author_user_id", UUID.class),
						rs.getString("author_name"),
						instant(rs.getObject("created_at", OffsetDateTime.class))),
				staffId);
	}

	/**
	 * Writes one note, permanently.
	 *
	 * <p>The author is the signed-in user and the timestamp is the database's. Neither is taken from
	 * the request, because a note whose author and date a caller could choose would prove nothing.
	 *
	 * <p>The audit entry records that a note was added and by whom, and deliberately does not carry
	 * the words. The log is read behind {@code VIEW_AUDIT_LOG}, which is a different permission from
	 * the one guarding the note; copying the text there would hand it to a second audience by the
	 * back door.
	 */
	@Transactional
	public UUID add(AuthenticatedUser actor, UUID staffId, AddConductNoteRequest request) {
		StaffRow staff = requireStaff(staffId);

		String body = request.body() == null ? "" : request.body().strip();
		if (body.isEmpty()) {
			throw new ApplicationException(ErrorCode.CONDUCT_NOTE_EMPTY, Map.of("staffId", staffId));
		}

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_conduct_notes (id, tenant_id, staff_profile_id, body, author_user_id)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				""", id, staffId, body, actor.getUserId());

		auditService.record(actor, AuditAction.STAFF_CONDUCT_NOTE_ADDED, AuditEntityType.STAFF_MEMBER,
				staffId,
				null,
				Map.of("noteId", id.toString()),
				"A conduct note was added to " + staff.fullName() + "'s record.");
		return id;
	}

	// ---------------------------------------------------------------------

	private StaffRow requireStaff(UUID staffId) {
		return jdbc.query("SELECT full_name FROM staff_profiles WHERE id = ?",
						(rs, n) -> new StaffRow(rs.getString("full_name")), staffId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("staffId", staffId)));
	}

	private static Instant instant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}

	/** Just the name, for the audit line. Nothing else about the person is needed to write a note. */
	private record StaffRow(String fullName) {
	}
}

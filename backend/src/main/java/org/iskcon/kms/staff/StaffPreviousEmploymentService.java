package org.iskcon.kms.staff;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where somebody worked before this temple (T-428).
 *
 * <h2>The whole list, replaced</h2>
 *
 * <p>The edit screen shows every past job at once and sends them all back with the rest of the
 * record, so this replaces rather than merges. That is not laziness about ids: it is what the form
 * does. An admin who deletes the middle row of three and corrects the first has performed one edit,
 * and one DELETE followed by one INSERT per row, inside the same transaction as the profile update,
 * is exactly one edit. Merging by id would give the same answer by a longer route and would leave
 * open the question of what an unknown id means.
 *
 * <p><b>An absent list leaves the stored one alone.</b> {@code null} and an empty list are different
 * answers here, for the reason {@code pan} already is: something that sends an update without this
 * field — an older client, a script correcting a phone number — must not wipe somebody's work
 * history as a side effect. An empty list is the admin having deleted the last row, and that is
 * honoured.
 *
 * <h2>Dates</h2>
 *
 * <p>Either may be missing. When both are given, the end cannot fall before the start, and the
 * refusal is a field error on that row's own end date so the form can point at the box.
 */
@Service
public class StaffPreviousEmploymentService {

	/**
	 * A ceiling, so one record cannot become a career history. Twenty jobs is more than anybody will
	 * type, and the number exists to stop a broken client writing rows forever rather than to tell a
	 * real person they have worked too much.
	 */
	static final int MAX_JOBS = 20;

	private static final String COLUMNS =
			"id, employer, their_title, manager_name, manager_phone, from_date, to_date, reason_for_leaving";

	private final JdbcTemplate jdbc;

	public StaffPreviousEmploymentService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** One person's past jobs, in the order the admin entered them. */
	@Transactional(readOnly = true)
	public List<PreviousEmploymentView> listFor(UUID staffProfileId) {
		return jdbc.query("SELECT " + COLUMNS
				+ " FROM staff_previous_employment WHERE staff_profile_id = ? ORDER BY sort_order",
				VIEW, staffProfileId);
	}

	/**
	 * Replaces this person's past jobs with the list given.
	 *
	 * <p>Called from inside {@code StaffEmploymentService.update}'s transaction, so a refusal here
	 * takes the whole save with it and the admin is never left with the jobs written and the phone
	 * number not.
	 *
	 * @param jobs the whole list, or null to leave what is stored exactly as it is
	 * @throws ApplicationException {@code KMS-400001} with a field error for an end date before its
	 *     start, or for more rows than anybody could have worked
	 */
	@Transactional
	public void replace(UUID staffProfileId, List<PreviousEmploymentInput> jobs) {
		if (jobs == null) {
			return;
		}
		if (jobs.size() > MAX_JOBS) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("staffProfileId", staffProfileId, "jobs", jobs.size()),
					List.of(new ErrorResponse.FieldError("previousEmployment",
							"Record at most " + MAX_JOBS + " previous jobs.")), null);
		}

		List<ErrorResponse.FieldError> wrong = new ArrayList<>();
		for (int i = 0; i < jobs.size(); i++) {
			PreviousEmploymentInput job = jobs.get(i);
			LocalDate from = job.fromDate();
			LocalDate to = job.toDate();
			if (from != null && to != null && to.isBefore(from)) {
				wrong.add(new ErrorResponse.FieldError("previousEmployment[" + i + "].toDate",
						"The day they left has to fall on or after the day they started."));
			}
		}
		if (!wrong.isEmpty()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("staffProfileId", staffProfileId), wrong, null);
		}

		jdbc.update("DELETE FROM staff_previous_employment WHERE staff_profile_id = ?", staffProfileId);
		for (int i = 0; i < jobs.size(); i++) {
			PreviousEmploymentInput job = jobs.get(i);
			jdbc.update("""
					INSERT INTO staff_previous_employment (
						tenant_id, staff_profile_id, employer, their_title, manager_name, manager_phone,
						from_date, to_date, reason_for_leaving, sort_order)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?, ?)
					""",
					staffProfileId, job.employer().trim(), trimToNull(job.theirTitle()),
					trimToNull(job.managerName()), trimToNull(job.managerPhone()),
					job.fromDate(), job.toDate(), trimToNull(job.reasonForLeaving()), i);
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static final RowMapper<PreviousEmploymentView> VIEW = (rs, n) -> new PreviousEmploymentView(
			rs.getObject("id", UUID.class),
			rs.getString("employer"),
			rs.getString("their_title"),
			rs.getString("manager_name"),
			rs.getString("manager_phone"),
			rs.getObject("from_date", LocalDate.class),
			rs.getObject("to_date", LocalDate.class),
			rs.getString("reason_for_leaving"));
}

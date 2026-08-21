package org.iskcon.kms.ban;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.security.PanCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ban record raised at a dismissal, and the check run at a hire (B9).
 *
 * <p>This is the only feature in the product that deliberately crosses tenant isolation, and it is
 * the only one that can hurt somebody who has done nothing wrong. Nearly every decision in this class
 * is a constraint accepted on purpose rather than a capability, so read the four that matter before
 * changing anything:
 *
 * <p><b>1. It flags. It never blocks.</b> A finding is handed to the hiring admin with the raising
 * temple named and their account of it quoted, and the admin decides. <em>Hired anyway</em> is
 * recorded as the legitimate answer it usually is. A hard block would move the judgement from the
 * person in the room — who can telephone the other temple and hear the other half of the story — to
 * a similarity threshold in {@link BanMatcher}, and a confident false positive against a devotee who
 * has done nothing is the exact failure this feature was designed against.
 *
 * <p><b>2. It is queried at a hire and nowhere else.</b> There is no search endpoint, and adding one
 * would defeat the design however convenient it looked. The check runs as part of creating a staff
 * record, so a query cannot exist without a hire attempt behind it; a hire attempt leaves a staff
 * record at the asking temple; and every query, including every query that found nothing, lands on
 * the platform audit log. Those three together are what stop this becoming a lookup service that any
 * temple can fish through. Editing an existing record runs no check — re-hiring somebody is a new
 * hire and is checked, correcting a phone number is not.
 *
 * <p><b>3. The subject is never shown any of this in the app.</b> They lose access at termination
 * anyway, and disclosure at the moment of firing invites retaliation — a real risk in India, borne by
 * the people this product is for. The DPDP Act's right here is to information <em>on request</em>,
 * satisfied by a documented out-of-band process, not by proactive disclosure. The consequence is the
 * important part and it shapes the rest of this class: because the subject is no longer a check on a
 * wrong entry, {@link #retract} , the ten-year fade and naming the raising temple on every single
 * finding carry the <em>whole</em> of the error correction between them. There is no subject-facing
 * surface here and none should be added without revisiting that argument.
 *
 * <p><b>4. Nothing is deleted.</b> A retracted record stays on file. Erasing it would erase the
 * evidence that a wrong entry had ever been made, which is the opposite of correcting one.
 */
@Service
public class EmploymentBanService {

	/**
	 * How long a ban goes on appearing at hires.
	 *
	 * <p><b>Confirmed 2026-08-20. Revisit if the temple objects.</b> Ten years is long enough that
	 * somebody cannot simply wait out a serious dismissal, and short enough that a record does not
	 * follow a person for the rest of their working life. It is a constant here, and a parameter to
	 * {@code match_employment_bans} rather than an interval baked into the SQL, precisely so that
	 * changing it is one line and not a migration.
	 */
	public static final Period BAN_LIFETIME = Period.ofYears(10);

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final PanCipher panCipher;
	private final ObjectMapper objectMapper;

	public EmploymentBanService(
			JdbcTemplate jdbc, AuditService auditService, PanCipher panCipher, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.panCipher = panCipher;
		this.objectMapper = objectMapper;
	}

	// ---- Raising, at the dismissal --------------------------------------

	/**
	 * Records a ban against somebody whose employment is ending. Called from within
	 * {@code StaffEmploymentService.endEmployment}, in that same transaction, because it is a decision
	 * made at the dismissal and the two must stand or fall together — an employment ended without the
	 * ban the admin asked for is worse than neither.
	 *
	 * <p>The status is not required to be {@code TERMINATED}. Somebody resigning one step ahead of
	 * being dismissed is common, and forcing an admin to record the wrong ending in order to raise
	 * the record would corrupt the field that the temple's own history actually depends on.
	 */
	@Transactional
	public UUID raise(AuthenticatedUser actor, UUID staffProfileId, RaiseEmploymentBanRequest request) {
		BanCategory category = request.category();
		String account = trimToNull(request.account());
		// Both halves, always. A category with no account is an allegation with nothing behind it;
		// an account with no category cannot be compared with anything at another temple.
		if (category == null || account == null) {
			throw new ApplicationException(ErrorCode.BAN_REASON_REQUIRED,
					Map.of("hasCategory", category != null, "hasAccount", account != null));
		}

		Map<String, Object> staff = staffRow(staffProfileId);
		PersonSignals signals = signalsFor(staffProfileId, staff, request.aadhaar());

		UUID id = UUID.randomUUID();
		try {
			jdbc.update(connection -> {
				PreparedStatement ps = connection.prepareStatement("""
						INSERT INTO employment_bans (
							id, tenant_id, staff_profile_id, raised_by_user_id,
							category, account,
							pan_fingerprint, full_name, name_normalised, name_tokens,
							phone_digits, address_normalised,
							aadhaar_name_normalised, aadhaar_date_of_birth, aadhaar_last4)
						VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
							?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						""");
				ps.setObject(1, id);
				ps.setObject(2, staffProfileId);
				ps.setObject(3, actor.getUserId());
				ps.setString(4, category.name());
				ps.setString(5, account);
				ps.setString(6, signals.panFingerprint());
				ps.setString(7, signals.fullName());
				ps.setString(8, signals.nameNormalised());
				ps.setArray(9, textArray(connection, signals.nameTokenArray()));
				ps.setString(10, signals.phoneDigits());
				ps.setString(11, signals.addressNormalised());
				AadhaarIdentity aadhaar = signals.aadhaar();
				ps.setString(12, aadhaar == null ? null : PersonSignals.normalise(aadhaar.name()));
				if (aadhaar == null) {
					ps.setNull(13, Types.DATE);
				} else {
					ps.setObject(13, aadhaar.dateOfBirth());
				}
				ps.setString(14, aadhaar == null ? null : aadhaar.last4());
				return ps;
			});
		} catch (DuplicateKeyException e) {
			// The partial unique index: one live record per person per raising temple. A retracted
			// one does not stand in the way of a fresh one, which is how a temple undoes a retraction
			// it made in error — both stay on file, and that is the point.
			throw new ApplicationException(ErrorCode.BAN_ALREADY_EXISTS,
					Map.of("staffId", staffProfileId), e);
		}

		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("category", category.name());
		shape.put("personName", signals.fullName());
		shape.put("matchedOnPan", signals.panFingerprint() != null);

		// Both logs, deliberately. The temple's own, because it is their act and their people should
		// be able to see who did it. The platform's, because the record is global, it outlives the
		// temple's own log if that temple is ever purged, and an operator asked to look into an abuse
		// of this feature has nowhere else to look.
		auditService.record(actor, AuditAction.EMPLOYMENT_BAN_RAISED, AuditEntityType.EMPLOYMENT_BAN,
				id, null, shape, account);
		auditService.recordPlatform(actor, AuditAction.EMPLOYMENT_BAN_RAISED,
				AuditEntityType.EMPLOYMENT_BAN, id, null, shape, account);
		return id;
	}

	// ---- Amending and retracting, by the temple that raised it -----------

	/** Correcting the record. Only the raising temple may, and the change is on both audit logs. */
	@Transactional
	public void amend(AuthenticatedUser actor, UUID banId, RaiseEmploymentBanRequest request) {
		BanCategory category = request.category();
		String account = trimToNull(request.account());
		if (category == null || account == null) {
			throw new ApplicationException(ErrorCode.BAN_REASON_REQUIRED,
					Map.of("hasCategory", category != null, "hasAccount", account != null));
		}
		EmploymentBanView before = requireOurs(actor, banId);

		jdbc.update("UPDATE employment_bans SET category = ?, account = ?, updated_at = now() WHERE id = ?",
				category.name(), account, banId);

		Map<String, Object> was = Map.of("category", before.category().name(), "account", before.account());
		Map<String, Object> now = Map.of("category", category.name(), "account", account);
		auditService.record(actor, AuditAction.EMPLOYMENT_BAN_AMENDED, AuditEntityType.EMPLOYMENT_BAN,
				banId, was, now, null);
		auditService.recordPlatform(actor, AuditAction.EMPLOYMENT_BAN_AMENDED,
				AuditEntityType.EMPLOYMENT_BAN, banId, was, now, null);
	}

	/**
	 * Takes a record back. It stops appearing at hires from this moment and stays on file for ever,
	 * because the trail of a wrong entry is what makes it correctable rather than deniable.
	 */
	@Transactional
	public void retract(AuthenticatedUser actor, UUID banId, RetractEmploymentBanRequest request) {
		EmploymentBanView before = requireOurs(actor, banId);
		if (before.retracted()) {
			throw new ApplicationException(ErrorCode.BAN_ALREADY_RETRACTED, Map.of("banId", banId));
		}
		String reason = trimToNull(request == null ? null : request.reason());

		jdbc.update("""
				UPDATE employment_bans
				SET retracted_at = now(), retracted_by_user_id = ?, retraction_reason = ?, updated_at = now()
				WHERE id = ? AND retracted_at IS NULL
				""", actor.getUserId(), reason, banId);

		Map<String, Object> shape = Map.of("retracted", true, "category", before.category().name());
		auditService.record(actor, AuditAction.EMPLOYMENT_BAN_RETRACTED, AuditEntityType.EMPLOYMENT_BAN,
				banId, Map.of("retracted", false), shape, reason);
		auditService.recordPlatform(actor, AuditAction.EMPLOYMENT_BAN_RETRACTED,
				AuditEntityType.EMPLOYMENT_BAN, banId, Map.of("retracted", false), shape, reason);
	}

	// ---- Reading — this temple's own records, and nobody else's ----------

	/**
	 * The records this temple raised.
	 *
	 * <p>Confined by the row policy on {@code employment_bans.tenant_id}, not by any {@code WHERE}:
	 * this query would return another temple's rows only if the database itself let it, which it does
	 * not. There is no counterpart to this method for anybody else's records and there must not be —
	 * the nearest thing the platform offers is a {@link BanFinding}, which arrives only as the result
	 * of an actual hire.
	 */
	@Transactional(readOnly = true)
	public List<EmploymentBanView> raisedByThisTemple() {
		return jdbc.query(OWN_SELECT + " ORDER BY b.raised_at DESC", OWN_MAPPER);
	}

	/**
	 * Which former staff of this temple have a record standing against them, by staff profile.
	 *
	 * <p>For the register alone, which draws a banned former employee differently from one who simply
	 * left (item 2 of the 2026-08-21 brief). It answers a question about <em>our own</em> people and
	 * is bounded by the same row policy as everything else here, so it is not a search: an id it
	 * knows nothing about is an id it says nothing about, and there is no name, no category and no
	 * account in what it returns.
	 *
	 * <p>Retracted records are left out. A retraction stops a record being shown at any hire, so a
	 * name drawn as banned on the strength of one would go on saying something the platform itself
	 * has stopped saying.
	 */
	@Transactional(readOnly = true)
	public Set<UUID> staffProfilesWithARecord() {
		return Set.copyOf(jdbc.queryForList(
				"SELECT staff_profile_id FROM employment_bans WHERE retracted_at IS NULL", UUID.class));
	}

	public List<BanCategoryOption> categories() {
		return BanCategory.all().stream().map(c -> new BanCategoryOption(c, c.label())).toList();
	}

	// ---- The check, at a hire and nowhere else ---------------------------

	/**
	 * Asks the platform whether anybody has raised a record about the person being hired.
	 *
	 * <p>Runs in its own transaction, before the hire, so that the audit of the query survives a hire
	 * that then fails for some unrelated reason. A query that was made is a query that was made.
	 *
	 * @param panInClear the PAN as typed on the hire form; fingerprinted here and never stored by this
	 *                   method. Null when the temple did not ask for one, which is common
	 * @param aadhaar    the signed-QR triple. Always null in this build — see {@link AadhaarIdentity}
	 */
	@Transactional
	public BanCheckResult check(
			AuthenticatedUser actor,
			String fullName,
			String phone,
			String address,
			String panInClear,
			AadhaarIdentity aadhaar) {

		PersonSignals candidate = PersonSignals.of(
				panInClear == null || panInClear.isBlank()
						? null
						: panCipher.fingerprint(panInClear.trim().toUpperCase(Locale.ROOT)),
				fullName, phone, address, aadhaar);

		Instant cutoff = Instant.now().atOffset(ZoneOffset.UTC).toLocalDate()
				.minus(BAN_LIFETIME).atStartOfDay(ZoneOffset.UTC).toInstant();

		List<BanRow> candidates = jdbc.query(connection -> {
			PreparedStatement ps = connection.prepareStatement(
					"SELECT * FROM match_employment_bans(?, ?, ?, ?, ?, ?, ?)");
			ps.setString(1, candidate.panFingerprint());
			ps.setArray(2, textArray(connection, candidate.nameTokenArray()));
			ps.setString(3, candidate.phoneDigits());
			AadhaarIdentity a = candidate.aadhaar();
			ps.setString(4, a == null ? null : PersonSignals.normalise(a.name()));
			if (a == null) {
				ps.setNull(5, Types.DATE);
			} else {
				ps.setObject(5, a.dateOfBirth());
			}
			ps.setString(6, a == null ? null : a.last4());
			ps.setObject(7, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
			return ps;
		}, ROW_MAPPER);

		// The blocking key in SQL is deliberately generous — a shared name token pulls a row back.
		// This is where that generosity is spent: only the rows the matcher stands behind become
		// findings, so probing with a common surname returns candidates the caller never sees.
		List<BanFinding> findings = new ArrayList<>();
		for (BanRow row : candidates) {
			BanMatcher.match(candidate, row.signals()).ifPresent(signals ->
					findings.add(new BanFinding(
							row.id(),
							templeName(row.raisingTenantId()),
							row.category(),
							row.category().label(),
							row.signals().fullName(),
							row.account(),
							row.raisedAt().atOffset(ZoneOffset.UTC).toLocalDate(),
							signals,
							signals.stream().map(MatchSignal::label).toList(),
							BanMatcher.isExact(signals))));
		}

		UUID checkId = UUID.randomUUID();
		recordCheckOnPlatformLog(actor, checkId, candidate.fullName(), findings);
		return new BanCheckResult(checkId, List.copyOf(findings));
	}

	/**
	 * Files what the check found, and what the admin did about it, against the hire it belonged to.
	 *
	 * <p>The findings are frozen as they were shown. The records themselves may be amended or
	 * retracted later by the temple that owns them, and what this admin was looking at when they
	 * decided must not change underneath them afterwards.
	 */
	@Transactional
	public void recordAgainstHire(UUID staffProfileId, BanCheckResult check) {
		BanCheckDecision decision =
				check.foundSomething() ? BanCheckDecision.PROCEEDED : BanCheckDecision.NO_FINDINGS;
		jdbc.update(connection -> {
			PreparedStatement ps = connection.prepareStatement("""
					UPDATE staff_profiles
					SET ban_check_id = ?, ban_check_at = now(), ban_check_findings = ?,
						ban_check_decision = ?, updated_at = now()
					WHERE id = ?
					""");
			ps.setObject(1, check.checkId());
			ps.setObject(2, toJson(check.findings()), Types.OTHER);
			ps.setString(3, decision.name());
			ps.setObject(4, staffProfileId);
			return ps;
		});
	}

	/**
	 * Records that the admin saw findings and stopped.
	 *
	 * <p>Its own small endpoint because this is the one outcome with nowhere else to live: nobody was
	 * hired, so there is no staff record to file it against. Without it, "we looked and walked away"
	 * would be indistinguishable in the log from never having looked — and it is the more responsible
	 * of the two answers, so it should not be the one that leaves no trace.
	 */
	@Transactional
	public void recordAbandoned(AuthenticatedUser actor, UUID checkId) {
		auditService.recordPlatform(actor, AuditAction.BAN_CHECK_DECIDED,
				AuditEntityType.EMPLOYMENT_BAN_CHECK, checkId, null,
				Map.of("decision", BanCheckDecision.ABANDONED.name()),
				"The hiring temple saw the findings and did not proceed.");
	}

	// ---------------------------------------------------------------------

	/**
	 * Every check, whether it found anything or not.
	 *
	 * <p>The one that found nothing is the important one: it is exactly the query somebody fishing
	 * would run, and a log that recorded only the hits would be blind to precisely the abuse it is
	 * kept for.
	 *
	 * <p>It goes on the <em>platform</em> log and never on the temple's own, and that is not an
	 * oversight either. {@code audit_events} is readable by the temple, so writing findings there
	 * would hand every temple a permanent, searchable cache of what the ban list says about people it
	 * did not hire — the lookup service this whole design exists to prevent, rebuilt inside the audit
	 * viewer. A temple can write to the platform log and can never read it, which is the correct
	 * asymmetry for a record kept to catch the writer.
	 */
	private void recordCheckOnPlatformLog(
			AuthenticatedUser actor, UUID checkId, String candidateName, List<BanFinding> findings) {

		Map<String, Object> shape = new LinkedHashMap<>();
		shape.put("candidateName", candidateName);
		shape.put("findings", findings.size());
		shape.put("matchedBanIds", findings.stream().map(f -> f.banId().toString()).toList());
		shape.put("hiringTenantId", actor.getTenantId() == null ? null : actor.getTenantId().toString());

		auditService.recordPlatform(actor, AuditAction.BAN_CHECK_RUN,
				AuditEntityType.EMPLOYMENT_BAN_CHECK, checkId, null, shape,
				findings.isEmpty() ? "Checked at hire; nothing found." : "Checked at hire; findings shown.");
	}

	/**
	 * Reads the person's own record and turns it into the signals a ban is matched on.
	 *
	 * <p>Where the fingerprint comes from is the interesting part. {@code pan_fingerprint} is null on
	 * every record hired before V65, because a SQL migration cannot compute an HMAC under a key the
	 * database has never seen, over a plaintext it has never seen either. Rather than sweeping the
	 * whole estate at boot — decrypting hundreds of PANs to serve the handful that will ever be
	 * banned, in a system whose whole posture is that decryption is rare and deliberate — the one row
	 * that needs it is filled here, at the one moment it is needed, and remembered.
	 *
	 * <p>The clear value never leaves this method and is never returned to anybody, so this is not a
	 * PAN read and does not raise {@code STAFF_PAN_VIEWED}. That distinction is the reason the column
	 * exists at all: with it, raising a ban never has to decrypt anything ever again.
	 */
	private PersonSignals signalsFor(
			UUID staffProfileId, Map<String, Object> staff, AadhaarIdentity requestAadhaar) {

		String fingerprint = (String) staff.get("pan_fingerprint");
		byte[] ciphertext = (byte[]) staff.get("pan_ciphertext");
		if (fingerprint == null && ciphertext != null) {
			fingerprint = panCipher.fingerprint(panCipher.decrypt(ciphertext));
			jdbc.update("UPDATE staff_profiles SET pan_fingerprint = ? WHERE id = ?",
					fingerprint, staffProfileId);
		}

		// The temple's own Aadhaar triple where it has one, or the one supplied with the request.
		// Neither is populated in this build; both are the seam a signed-QR reader would land on.
		AadhaarIdentity aadhaar = requestAadhaar;
		if (aadhaar == null && staff.get("aadhaar_last4") != null) {
			aadhaar = new AadhaarIdentity(
					(String) staff.get("aadhaar_name"),
					((java.sql.Date) staff.get("aadhaar_date_of_birth")).toLocalDate(),
					(String) staff.get("aadhaar_last4"));
		}

		return PersonSignals.of(
				fingerprint,
				(String) staff.get("full_name"),
				(String) staff.get("phone"),
				(String) staff.get("address"),
				aadhaar);
	}

	private Map<String, Object> staffRow(UUID staffProfileId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT id, full_name, phone, address, pan_ciphertext, pan_fingerprint,
				       aadhaar_name, aadhaar_date_of_birth, aadhaar_last4
				FROM staff_profiles WHERE id = ?
				""", staffProfileId);
		if (rows.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("staffId", staffProfileId));
		}
		return rows.get(0);
	}

	/**
	 * Refuses anybody but the temple that raised the record, and tells them so plainly.
	 *
	 * <p>The row policy already hides other temples' records, so this could simply have been a
	 * not-found. It is not, and the case that decides it is real: a hiring temple shown a finding
	 * knows the record's id and may quite reasonably try to take it down. "Not found" would leave them
	 * hunting a bug; KMS-4307 tells them whose record it is and that the raising temple's name is on
	 * it, which is the conversation the design wants them to have. The ownership question is asked
	 * through a function that returns the owning temple's id and nothing else — no reason, no name, no
	 * detail of any kind.
	 */
	private EmploymentBanView requireOurs(AuthenticatedUser actor, UUID banId) {
		UUID owner = jdbc.query("SELECT employment_ban_raising_tenant(?) AS owner",
				(rs, n) -> rs.getObject("owner", UUID.class), banId).stream().findFirst().orElse(null);
		if (owner == null) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("banId", banId));
		}
		if (!owner.equals(actor.getTenantId())) {
			throw new ApplicationException(ErrorCode.NOT_THE_RAISING_TEMPLE, Map.of("banId", banId));
		}
		return ours(banId).orElseThrow(
				() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("banId", banId)));
	}

	private Optional<EmploymentBanView> ours(UUID banId) {
		return jdbc.query(OWN_SELECT + " WHERE b.id = ?", OWN_MAPPER, banId).stream().findFirst();
	}

	private String templeName(UUID tenantId) {
		// tenants carries no row policy — it is the registry that defines tenants (V1) — so the
		// raising temple can be named to the hiring admin, which is the point of the whole finding.
		return jdbc.query("SELECT name FROM tenants WHERE id = ?",
				(rs, n) -> rs.getString("name"), tenantId).stream().findFirst().orElse("Another temple");
	}

	private static Array textArray(java.sql.Connection connection, String[] values) throws java.sql.SQLException {
		return connection.createArrayOf("text", values);
	}

	private String toJson(List<BanFinding> findings) {
		try {
			return objectMapper.writeValueAsString(findings);
		} catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	// ---------------------------------------------------------------------

	/** One row of {@code employment_bans}, as much of it as matching and reporting need. */
	private record BanRow(
			UUID id, UUID raisingTenantId, BanCategory category, String account,
			Instant raisedAt, PersonSignals signals) {
	}

	private static final RowMapper<BanRow> ROW_MAPPER = (rs, n) -> {
		java.sql.Date aadhaarDob = rs.getDate("aadhaar_date_of_birth");
		AadhaarIdentity aadhaar = aadhaarDob == null ? null : new AadhaarIdentity(
				rs.getString("aadhaar_name_normalised"), aadhaarDob.toLocalDate(), rs.getString("aadhaar_last4"));

		Array tokens = rs.getArray("name_tokens");
		List<String> nameTokens = tokens == null ? List.of() : List.of((String[]) tokens.getArray());

		// Built from the stored, already-normalised columns rather than re-normalising the printed
		// name: the normalisation that wrote them is the one that has to be compared against.
		PersonSignals signals = new PersonSignals(
				rs.getString("pan_fingerprint"),
				rs.getString("full_name"),
				rs.getString("name_normalised"),
				nameTokens,
				rs.getString("phone_digits"),
				rs.getString("address_normalised"),
				aadhaar);

		return new BanRow(
				rs.getObject("id", UUID.class),
				rs.getObject("tenant_id", UUID.class),
				BanCategory.valueOf(rs.getString("category")),
				rs.getString("account"),
				rs.getObject("raised_at", OffsetDateTime.class).toInstant(),
				signals);
	};

	private static final String OWN_SELECT = """
			SELECT b.id, b.staff_profile_id, b.full_name, b.category, b.account, b.raised_at,
			       b.retracted_at, b.retraction_reason, u.full_name AS raised_by
			FROM employment_bans b LEFT JOIN users u ON u.id = b.raised_by_user_id
			""";

	private static final RowMapper<EmploymentBanView> OWN_MAPPER = (rs, n) -> {
		BanCategory category = BanCategory.valueOf(rs.getString("category"));
		OffsetDateTime raisedAt = rs.getObject("raised_at", OffsetDateTime.class);
		OffsetDateTime retractedAt = rs.getObject("retracted_at", OffsetDateTime.class);
		LocalDate fadesOn = raisedAt.toLocalDate().plus(BAN_LIFETIME);
		return new EmploymentBanView(
				rs.getObject("id", UUID.class),
				rs.getObject("staff_profile_id", UUID.class),
				rs.getString("full_name"),
				category,
				category.label(),
				rs.getString("account"),
				raisedAt.toInstant(),
				rs.getString("raised_by"),
				fadesOn,
				retractedAt != null,
				retractedAt == null ? null : retractedAt.toInstant(),
				rs.getString("retraction_reason"));
	};
}

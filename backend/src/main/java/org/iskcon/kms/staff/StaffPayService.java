package org.iskcon.kms.staff;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * What the temple pays its staff: salary, payments, advances and docking (B8).
 *
 * <p><strong>This records; it does not work out what is owed.</strong> The admin types the figure —
 * every figure, including the settlement at termination. Computing salary owed would need a pay
 * period, a start date and a ledger of periods already settled, which is payroll, and nobody asked
 * for payroll. What it does instead is hold facts an administrator can check against a bank
 * statement, and be exact about the one number that genuinely is arithmetic.
 *
 * <p>That number is the <strong>cash-advance balance</strong>: advances given minus deductions
 * recovered, both of which are rows in this schema. Nothing keeps a running total, here or in the
 * database — a stored balance is a second version of the truth, and the entries are the version an
 * administrator is asked to defend. So the termination screen can state the balance flatly while
 * refusing to guess at months of salary, and the difference between the two is honest rather than
 * arbitrary.
 *
 * <p><strong>Who may see any of this.</strong> Every entry point is {@code MANAGE_STAFF}, which the
 * temple administrator holds and the kitchen manager deliberately does not. Pay is served through
 * {@link StaffPayView} alone and never added to {@link StaffProfileView}, because that view is
 * shared with the roster and with a staff member's own schedule.
 *
 * <p>Nothing is ever edited or deleted. A payment entered wrongly is voided, which leaves the row,
 * names who struck it, and is refused outright once advances have been docked against it.
 */
@Service
public class StaffPayService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public StaffPayService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	// ---- Reading --------------------------------------------------------

	/** Salary, advance balance, the last salary payment, and the full history behind them. */
	@Transactional(readOnly = true)
	public StaffPayView pay(UUID staffId) {
		StaffRow staff = requireStaff(staffId);
		List<StaffPaymentView> payments = payments(staffId);
		List<StaffAdvanceView> advances = advances(staffId);

		return new StaffPayView(
				staffId,
				staff.fullName(),
				currency(),
				staff.monthlySalary(),
				advanceBalance(advances),
				lastSalaryPayment(payments),
				payments,
				advances);
	}

	// ---- Recording ------------------------------------------------------

	/**
	 * A payment, with whatever it recovered from earlier advances.
	 *
	 * <p>Recorded for former staff as readily as for current: a final settlement is normally paid
	 * after somebody's last working day, and refusing it would leave the one payment that most needs
	 * writing down with nowhere to go.
	 */
	@Transactional
	public UUID recordPayment(AuthenticatedUser actor, UUID staffId, RecordStaffPaymentRequest request) {
		StaffRow staff = requireStaff(staffId);
		requirePositive(request.amount());
		requireReference(request.mode(), request.reference());

		Map<UUID, BigDecimal> byAdvance = totalPerAdvance(request.deductionsOrEmpty());
		BigDecimal deducted = byAdvance.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		if (deducted.compareTo(request.amount()) > 0) {
			throw new ApplicationException(ErrorCode.DEDUCTIONS_EXCEED_GROSS,
					Map.of("gross", request.amount(), "deductions", deducted));
		}
		byAdvance.forEach((advanceId, amount) -> requireRecoverable(staffId, advanceId, amount));

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_payments (
					id, tenant_id, staff_profile_id, paid_on, gross_amount, mode, reference,
					purpose, note, recorded_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, ?)
				""", id, staffId, request.paidOn(), request.amount(), request.mode().name(),
				trimToNull(request.reference()), request.purpose().name(),
				trimToNull(request.note()), actor.getUserId());

		byAdvance.forEach((advanceId, amount) -> jdbc.update("""
				INSERT INTO staff_payment_deductions (id, tenant_id, payment_id, advance_id, amount)
				VALUES (gen_random_uuid(), NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				""", id, advanceId, amount));

		auditService.record(actor, AuditAction.STAFF_PAYMENT_RECORDED, AuditEntityType.STAFF_MEMBER, staffId,
				null,
				Map.of("paymentId", id.toString(),
						"paidOn", request.paidOn().toString(),
						"gross", request.amount().toPlainString(),
						"deducted", deducted.toPlainString(),
						"mode", request.mode().name(),
						"purpose", request.purpose().name()),
				staff.fullName() + " paid " + request.amount().toPlainString() + ".");
		return id;
	}

	/** An advance. The same act as a payment, with a balance behind it that later payments repay. */
	@Transactional
	public UUID recordAdvance(AuthenticatedUser actor, UUID staffId, RecordStaffAdvanceRequest request) {
		StaffRow staff = requireStaff(staffId);
		requirePositive(request.amount());
		if (!request.mode().canPayAnAdvance()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "mode", "reason", "an advance is handed over, not run through payroll"));
		}
		requireReference(request.mode(), request.reference());

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO staff_advances (
					id, tenant_id, staff_profile_id, paid_on, amount, mode, reference, note, recorded_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
				""", id, staffId, request.paidOn(), request.amount(), request.mode().name(),
				trimToNull(request.reference()), trimToNull(request.note()), actor.getUserId());

		auditService.record(actor, AuditAction.STAFF_ADVANCE_RECORDED, AuditEntityType.STAFF_MEMBER, staffId,
				null,
				Map.of("advanceId", id.toString(),
						"paidOn", request.paidOn().toString(),
						"amount", request.amount().toPlainString(),
						"mode", request.mode().name()),
				staff.fullName() + " given an advance of " + request.amount().toPlainString() + ".");
		return id;
	}

	// ---- Striking a mistake ---------------------------------------------

	/**
	 * Voids a payment entered wrongly. The row stays and every total ignores it.
	 *
	 * <p>Refused once advances have been docked against it: voiding would hand the balance back
	 * silently, and an administrator who wanted that should say so in an entry of its own.
	 */
	@Transactional
	public void voidPayment(AuthenticatedUser actor, UUID staffId, UUID paymentId) {
		Map<String, Object> payment = jdbc.queryForList("""
				SELECT gross_amount, voided_at FROM staff_payments WHERE id = ? AND staff_profile_id = ?
				""", paymentId, staffId).stream().findFirst()
				.orElseThrow(() -> notFound("paymentId", paymentId));

		// Striking the same entry twice is the same outcome as striking it once, and an admin
		// double-clicking should not be shown a failure for having got what they asked for.
		if (payment.get("voided_at") != null) {
			return;
		}
		Integer deductions = jdbc.queryForObject(
				"SELECT count(*) FROM staff_payment_deductions WHERE payment_id = ?", Integer.class, paymentId);
		if (deductions != null && deductions > 0) {
			throw new ApplicationException(ErrorCode.STAFF_PAYMENT_NOT_VOIDABLE,
					Map.of("paymentId", paymentId, "deductions", deductions));
		}

		jdbc.update("UPDATE staff_payments SET voided_at = now(), voided_by = ? WHERE id = ?",
				actor.getUserId(), paymentId);

		auditService.record(actor, AuditAction.STAFF_PAYMENT_VOIDED, AuditEntityType.STAFF_MEMBER, staffId,
				Map.of("paymentId", paymentId.toString(),
						"gross", ((BigDecimal) payment.get("gross_amount")).toPlainString()),
				null, null);
	}

	/**
	 * Voids an advance entered wrongly — only while nothing has been recovered against it. Once a
	 * payment has docked it, the advance is part of that payment's arithmetic and striking it would
	 * make the net of a payment that has already been made stop adding up.
	 */
	@Transactional
	public void voidAdvance(AuthenticatedUser actor, UUID staffId, UUID advanceId) {
		Map<String, Object> advance = jdbc.queryForList("""
				SELECT amount, voided_at FROM staff_advances WHERE id = ? AND staff_profile_id = ?
				""", advanceId, staffId).stream().findFirst()
				.orElseThrow(() -> notFound("advanceId", advanceId));

		if (advance.get("voided_at") != null) {
			return;
		}
		Integer deductions = jdbc.queryForObject(
				"SELECT count(*) FROM staff_payment_deductions WHERE advance_id = ?", Integer.class, advanceId);
		if (deductions != null && deductions > 0) {
			throw new ApplicationException(ErrorCode.STAFF_PAYMENT_NOT_VOIDABLE,
					Map.of("advanceId", advanceId, "deductions", deductions));
		}

		jdbc.update("UPDATE staff_advances SET voided_at = now(), voided_by = ? WHERE id = ?",
				actor.getUserId(), advanceId);

		auditService.record(actor, AuditAction.STAFF_ADVANCE_VOIDED, AuditEntityType.STAFF_MEMBER, staffId,
				Map.of("advanceId", advanceId.toString(),
						"amount", ((BigDecimal) advance.get("amount")).toPlainString()),
				null, null);
	}

	// ---------------------------------------------------------------------

	/**
	 * The balance, computed from the entries every time it is asked for.
	 *
	 * <p>A voided advance is not owed and a voided payment recovered nothing — both are already
	 * excluded by the queries that build these rows, which is why this is a plain sum.
	 */
	private static BigDecimal advanceBalance(List<StaffAdvanceView> advances) {
		return advances.stream()
				.filter(a -> !a.isVoided())
				.map(StaffAdvanceView::outstanding)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/** The most recent salary payment that still stands; a settlement is not one. */
	private static StaffPaymentView lastSalaryPayment(List<StaffPaymentView> payments) {
		return payments.stream()
				.filter(p -> !p.isVoided() && p.purpose() == PaymentPurpose.SALARY)
				.findFirst()
				.orElse(null);
	}

	private List<StaffPaymentView> payments(UUID staffId) {
		Map<UUID, List<StaffPaymentView.Deduction>> byPayment = deductionsByPayment(staffId);
		return jdbc.query("""
				SELECT p.id, p.paid_on, p.gross_amount, p.mode, p.reference, p.purpose, p.note,
				       p.voided_at, u.full_name AS recorded_by_name,
				       COALESCE((SELECT SUM(d.amount) FROM staff_payment_deductions d
				                 WHERE d.payment_id = p.id), 0) AS deducted
				FROM staff_payments p LEFT JOIN users u ON u.id = p.recorded_by
				WHERE p.staff_profile_id = ?
				ORDER BY p.paid_on DESC, p.created_at DESC
				""", (rs, n) -> {
			UUID id = rs.getObject("id", UUID.class);
			BigDecimal gross = rs.getBigDecimal("gross_amount");
			BigDecimal deducted = rs.getBigDecimal("deducted");
			PaymentMode mode = PaymentMode.valueOf(rs.getString("mode"));
			PaymentPurpose purpose = PaymentPurpose.valueOf(rs.getString("purpose"));
			return new StaffPaymentView(id, rs.getObject("paid_on", LocalDate.class),
					gross, deducted, gross.subtract(deducted),
					mode, mode.label(), rs.getString("reference"), purpose, purpose.label(),
					rs.getString("note"), rs.getString("recorded_by_name"),
					instant(rs.getObject("voided_at", OffsetDateTime.class)),
					byPayment.getOrDefault(id, List.of()));
		}, staffId);
	}

	private Map<UUID, List<StaffPaymentView.Deduction>> deductionsByPayment(UUID staffId) {
		Map<UUID, List<StaffPaymentView.Deduction>> out = new LinkedHashMap<>();
		jdbc.query("""
				SELECT d.payment_id, d.advance_id, d.amount, a.paid_on AS advance_paid_on
				FROM staff_payment_deductions d
				JOIN staff_payments p ON p.id = d.payment_id
				JOIN staff_advances a ON a.id = d.advance_id
				WHERE p.staff_profile_id = ?
				ORDER BY a.paid_on
				""", rs -> {
			out.computeIfAbsent(rs.getObject("payment_id", UUID.class), k -> new ArrayList<>())
					.add(new StaffPaymentView.Deduction(
							rs.getObject("advance_id", UUID.class),
							rs.getObject("advance_paid_on", LocalDate.class),
							rs.getBigDecimal("amount")));
		}, staffId);
		return out;
	}

	/**
	 * Advances with what has come back on each. A deduction sitting on a voided payment never
	 * happened, so the join drops it and the outstanding figure rises again — which is the correct
	 * consequence of striking a payment that had docked something.
	 */
	private List<StaffAdvanceView> advances(UUID staffId) {
		return jdbc.query("""
				SELECT a.id, a.paid_on, a.amount, a.mode, a.reference, a.note, a.voided_at,
				       u.full_name AS recorded_by_name,
				       COALESCE((SELECT SUM(d.amount)
				                 FROM staff_payment_deductions d
				                 JOIN staff_payments p ON p.id = d.payment_id AND p.voided_at IS NULL
				                 WHERE d.advance_id = a.id), 0) AS recovered
				FROM staff_advances a LEFT JOIN users u ON u.id = a.recorded_by
				WHERE a.staff_profile_id = ?
				ORDER BY a.paid_on DESC, a.created_at DESC
				""", (rs, n) -> {
			BigDecimal amount = rs.getBigDecimal("amount");
			BigDecimal recovered = rs.getBigDecimal("recovered");
			PaymentMode mode = PaymentMode.valueOf(rs.getString("mode"));
			return new StaffAdvanceView(rs.getObject("id", UUID.class),
					rs.getObject("paid_on", LocalDate.class), amount, recovered, amount.subtract(recovered),
					mode, mode.label(), rs.getString("reference"), rs.getString("note"),
					rs.getString("recorded_by_name"),
					instant(rs.getObject("voided_at", OffsetDateTime.class)));
		}, staffId);
	}

	/**
	 * Two lines against one advance on one payment are one deduction written twice, so they are
	 * added together before anything is checked — otherwise each half would pass a test the whole
	 * fails, and the pair would overdraw the advance between them.
	 */
	private static Map<UUID, BigDecimal> totalPerAdvance(List<PaymentDeductionRequest> deductions) {
		Map<UUID, BigDecimal> byAdvance = new LinkedHashMap<>();
		for (PaymentDeductionRequest deduction : deductions) {
			requirePositive(deduction.amount());
			byAdvance.merge(deduction.advanceId(), deduction.amount(), BigDecimal::add);
		}
		return byAdvance;
	}

	/**
	 * Checks one advance can take the deduction, holding the row while it does.
	 *
	 * <p>The lock is the point of doing this per advance rather than in the sum above: two admins
	 * recording payments at the same desk could otherwise each find ₹2,000 outstanding and each
	 * recover it, leaving the advance ₹2,000 overdrawn and the balance negative.
	 */
	private void requireRecoverable(UUID staffId, UUID advanceId, BigDecimal amount) {
		BigDecimal advance = jdbc.query("""
				SELECT amount FROM staff_advances
				WHERE id = ? AND staff_profile_id = ? AND voided_at IS NULL
				FOR UPDATE
				""", (rs, n) -> rs.getBigDecimal("amount"), advanceId, staffId)
				.stream().findFirst().orElseThrow(() -> notFound("advanceId", advanceId));

		BigDecimal recovered = jdbc.queryForObject("""
				SELECT COALESCE(SUM(d.amount), 0)
				FROM staff_payment_deductions d
				JOIN staff_payments p ON p.id = d.payment_id AND p.voided_at IS NULL
				WHERE d.advance_id = ?
				""", BigDecimal.class, advanceId);

		BigDecimal outstanding = advance.subtract(recovered == null ? BigDecimal.ZERO : recovered);
		if (outstanding.signum() <= 0) {
			throw new ApplicationException(ErrorCode.ADVANCE_ALREADY_RECOVERED,
					Map.of("advanceId", advanceId, "amount", advance));
		}
		if (amount.compareTo(outstanding) > 0) {
			throw new ApplicationException(ErrorCode.DEDUCTION_EXCEEDS_ADVANCE,
					Map.of("advanceId", advanceId, "outstanding", outstanding, "deduction", amount));
		}
	}

	private static void requirePositive(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new ApplicationException(ErrorCode.AMOUNT_NOT_POSITIVE,
					Map.of("amount", amount == null ? "none" : amount));
		}
	}

	private static void requireReference(PaymentMode mode, String reference) {
		if (mode.needsReference() && trimToNull(reference) == null) {
			throw new ApplicationException(ErrorCode.PAYMENT_REFERENCE_REQUIRED, Map.of("mode", mode.name()));
		}
	}

	/** The temple's own currency. Not a preference on a settings screen — a property of the temple. */
	private String currency() {
		return jdbc.queryForObject("""
				SELECT currency FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", String.class);
	}

	private StaffRow requireStaff(UUID staffId) {
		return jdbc.query("SELECT full_name, monthly_salary FROM staff_profiles WHERE id = ?",
						(rs, n) -> new StaffRow(rs.getString("full_name"), rs.getBigDecimal("monthly_salary")),
						staffId)
				.stream().findFirst().orElseThrow(() -> notFound("staffId", staffId));
	}

	private static ApplicationException notFound(String field, UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of(field, id));
	}

	private static Instant instant(OffsetDateTime odt) {
		return odt == null ? null : odt.toInstant();
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	/**
	 * The two things about a person this service needs. {@code monthlySalary} arrives as a genuine
	 * null when nothing has been agreed — {@code getBigDecimal} returns null rather than zero, which
	 * is the whole reason the column has no default.
	 */
	private record StaffRow(String fullName, BigDecimal monthlySalary) {
	}
}

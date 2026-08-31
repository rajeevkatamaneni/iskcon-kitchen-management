package org.iskcon.kms.ingredientrequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.error.ErrorResponse;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.AllocatedLine;
import org.iskcon.kms.inventory.BatchDraw;
import org.iskcon.kms.inventory.BatchOverride;
import org.iskcon.kms.inventory.FefoAllocator;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.inventory.MovementReference;
import org.iskcon.kms.inventory.MovementType;
import org.iskcon.kms.inventory.RecordMovement;
import org.iskcon.kms.inventory.StockAllocation;
import org.iskcon.kms.inventory.StockMovementService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording what actually went over the counter (E10-S7), and the moment the temple's stock falls.
 *
 * <p><strong>Approval is a decision; issuing is a physical event.</strong> Nothing moves when a
 * request is approved — the same distinction the system already draws between sending a purchase
 * order and receiving one. What moves stock is a storekeeper standing at the shelf saying what they
 * handed over, and this service is that act.
 *
 * <p><strong>Issuing is a drawdown, not a transfer.</strong> The goods leave the temple's books.
 * They do not arrive in a second balance called "Deity kitchen stock", because a kitchen that only
 * wants ingredients is not running this application and nothing would ever draw that balance down;
 * within a month it would be a number insisting the Deity kitchen still holds rice it ate in
 * September (design D2).
 *
 * <p><strong>The kitchen is not written onto the movement.</strong> Each movement carries
 * {@code reference_type = INGREDIENT_REQUEST} and the request's id, and the request carries the
 * kitchen — so the two can never come to disagree, and there is no new column. {@code
 * storage_location} stays null, as it is on every consumption movement: it says where in the store a
 * thing sits, not where it went, and writing a receiving kitchen into it would make the store room's
 * rice relocate itself to the Deity kitchen the first time anything was issued.
 *
 * <p><strong>All or nothing.</strong> The whole issue is worked out before a single row is written,
 * and if any line is short the entire act is refused with an itemised shortfall. A part-written issue
 * that took the dal but not the rice would leave two stock figures wrong and no way to tell which.
 * Refusing to record something that has physically happened is arguably the wrong behaviour, and it
 * is still the right one: allowing it would drive stock negative, which the ledger forbids
 * everywhere else. A store whose books say 2 kg while its shelf holds 20 kg has a counting problem,
 * and the fix is a count correction on a screen that already exists, before the issue is recorded.
 *
 * <p><strong>FEFO is not reimplemented here.</strong> {@link FefoAllocator} holds the one copy of
 * "take the lot that goes off first, and tell me if there is not enough", lifted out of
 * {@code InventoryConsumptionService} when this became its second caller.
 */
@Service
public class IngredientIssueService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final IngredientRequestService requestService;
	private final FefoAllocator fefoAllocator;
	private final StockMovementService stockMovementService;

	public IngredientIssueService(
			JdbcTemplate jdbc, AuditService auditService, IngredientRequestService requestService,
			FefoAllocator fefoAllocator, StockMovementService stockMovementService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.requestService = requestService;
		this.fefoAllocator = fefoAllocator;
		this.stockMovementService = stockMovementService;
	}

	/**
	 * Records the issue and closes the request. One transaction, and the only one in this epic that
	 * writes to the stock ledger.
	 */
	@Transactional
	public void issue(AuthenticatedUser actor, UUID id, RecordIssueRequest input) {
		IngredientRequestService.RequestRow request = requestService.row(id);
		requireApproved(request);

		List<LineRow> lines = lines(id);
		Map<UUID, Issued> issued = resolveIssuedQuantities(lines, input.lines());

		// Aggregated per ingredient, not per line. A request may name the same ingredient twice, and
		// two lines drawing independently would each see the full stock and between them take more
		// than the shelf holds.
		Map<UUID, BigDecimal> requiredBase = new LinkedHashMap<>();
		Map<UUID, String> names = new LinkedHashMap<>();
		for (LineRow line : lines) {
			Issued amount = issued.get(line.id());
			if (amount.quantity().signum() == 0) {
				// Zero writes no movement at all — the store handed over nothing for this line, the
				// same rule a dish recorded as not made already follows. The zero is still persisted:
				// "we could not fill this" is a fact worth keeping.
				continue;
			}
			requiredBase.merge(line.ingredientId(),
					InventoryUnits.toBase(amount.quantity(), amount.unit()), BigDecimal::add);
			names.putIfAbsent(line.ingredientId(), line.ingredientName());
		}

		StockAllocation allocation = fefoAllocator.allocate(requiredBase, names, overrides(input));
		if (!allocation.sufficient()) {
			// The shortfall travels to the storekeeper, not only to the log. "There is not enough
			// stock" tells somebody holding a request for eight ingredients to go and check all
			// eight by hand; naming the two that are short is the whole use of the message. These
			// are the temple's own ingredients and its own quantities, which is what makes them
			// safe to say — see ApplicationException's note on `details`.
			List<ErrorResponse.FieldError> shortfalls = allocation.shortfalls().stream()
					.map(shortfall -> new ErrorResponse.FieldError(
							shortfall.ingredientName(),
							"Need %s, and the store holds %s.".formatted(
									Quantities.cooks(shortfall.required(), shortfall.unit()),
									Quantities.cooks(shortfall.available(), shortfall.unit()))))
					.toList();

			// Its own code rather than the one meal cooking uses. That message ends "cook a smaller
			// quantity", which is right for a cook and wrong for the person this refusal reaches:
			// a storekeeper at a shelf is not cooking anything, and telling them to is the kind of
			// small wrongness that makes a system feel like it is talking past you.
			throw new ApplicationException(
					ErrorCode.INSUFFICIENT_STOCK_TO_ISSUE,
					Map.of("ingredientRequestId", id),
					shortfalls,
					null);
		}

		String note = trimToNull(input.note());
		int movements = 0;
		for (AllocatedLine line : allocation.lines()) {
			Unit base = InventoryUnits.baseUnit(line.canonicalUnit().family());
			for (BatchDraw draw : line.draws()) {
				BigDecimal takeBase = draw.takeBase().setScale(3, RoundingMode.HALF_UP);
				stockMovementService.record(actor, new RecordMovement(
						line.ingredientId(), null, draw.batchId(),
						takeBase.negate(), base, MovementType.ISSUE,
						null, null, null, MovementReference.INGREDIENT_REQUEST, id, note));
				movements++;
			}
		}

		for (LineRow line : lines) {
			Issued amount = issued.get(line.id());
			jdbc.update("""
					UPDATE ingredient_request_lines
					SET issued_quantity = ?, issued_unit = ?
					WHERE id = ?
					""", amount.quantity(), amount.unit().name(), line.id());
		}

		jdbc.update("""
				UPDATE ingredient_requests
				SET status = 'ISSUED', issued_by = ?, issued_at = now(), updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), id);

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("status", "ISSUED");
		after.put("linesIssued", (int) lines.stream()
				.filter(l -> issued.get(l.id()).quantity().signum() > 0).count());
		after.put("movements", movements);
		auditService.record(actor, AuditAction.INGREDIENT_REQUEST_ISSUED,
				AuditEntityType.INGREDIENT_REQUEST, id,
				Map.of("status", "APPROVED"), after, note);

		requestService.recordEvent(id, "ISSUED",
				request.reference() + " issued from the store" + (note == null ? "" : " — " + note),
				actor);
	}

	// ---------------------------------------------------------------------

	/**
	 * Only an approved request can be issued, and only once.
	 *
	 * <p>The two refusals are separate codes because they are two different situations for the person
	 * holding the sheet: one means go and get it approved, the other means somebody has already been
	 * to the shelf and the stock has already fallen.
	 */
	private void requireApproved(IngredientRequestService.RequestRow request) {
		if (request.status() == IngredientRequestStatus.ISSUED) {
			throw new ApplicationException(ErrorCode.INGREDIENT_REQUEST_ALREADY_ISSUED,
					Map.of("ingredientRequestId", request.id()));
		}
		if (request.status() != IngredientRequestStatus.APPROVED) {
			throw new ApplicationException(ErrorCode.INGREDIENT_REQUEST_NOT_APPROVED,
					Map.of("ingredientRequestId", request.id(), "status", request.status().name()));
		}
	}

	/**
	 * What is actually going out on each line: what the storekeeper typed, or — for a line they did
	 * not touch — what was approved.
	 *
	 * <p>Defaulting rather than demanding every line back is what makes "I handed over exactly what
	 * was approved" one button. The unit is held to the same rule the request line was: same family
	 * as the ingredient, so a kilogram figure may come back in grams but never in litres.
	 */
	private Map<UUID, Issued> resolveIssuedQuantities(List<LineRow> lines, List<IssuedLineInput> input) {
		Map<UUID, IssuedLineInput> byLine = new LinkedHashMap<>();
		if (input != null) {
			for (IssuedLineInput entry : input) {
				byLine.put(entry.lineId(), entry);
			}
		}

		Map<UUID, Issued> resolved = new LinkedHashMap<>();
		for (LineRow line : lines) {
			IssuedLineInput entry = byLine.remove(line.id());
			if (entry == null) {
				resolved.put(line.id(), new Issued(line.quantity(), line.unit()));
				continue;
			}
			if (entry.quantity().signum() < 0) {
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
						Map.of("field", "lines.quantity", "lineId", line.id()));
			}
			if (entry.unit().family() != line.canonicalUnit().family()) {
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
						"field", "lines.unit", "lineId", line.id(), "value", entry.unit().name(),
						"expectedFamily", line.canonicalUnit().family().name()));
			}
			resolved.put(line.id(), new Issued(entry.quantity(), entry.unit()));
		}

		// Anything left over named a line that is not on this request — another request's, or another
		// temple's. Said out loud rather than ignored, because the storekeeper typed a number against
		// it and would otherwise never learn it went nowhere.
		if (!byLine.isEmpty()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "lines.lineId", "value", byLine.keySet().iterator().next()));
		}
		return resolved;
	}

	private Map<UUID, UUID> overrides(RecordIssueRequest input) {
		Map<UUID, UUID> overrides = new LinkedHashMap<>();
		if (input.batchOverrides() != null) {
			for (BatchOverride override : input.batchOverrides()) {
				overrides.put(override.ingredientId(), override.batchId());
			}
		}
		return overrides;
	}

	/**
	 * The request's lines with each ingredient's canonical unit alongside.
	 *
	 * <p>The join is what makes the unit check possible, and it runs under RLS on both tables, so a
	 * line pointing at another temple's ingredient would simply not come back — the same defence the
	 * request made when the line was written.
	 */
	private List<LineRow> lines(UUID requestId) {
		return jdbc.query("""
				SELECT l.id, l.line_no, l.ingredient_id, i.name AS ingredient_name,
					   i.canonical_unit, l.quantity, l.unit
				FROM ingredient_request_lines l
				JOIN ingredients i ON i.id = l.ingredient_id
				WHERE l.request_id = ?
				ORDER BY l.line_no
				""", LINE_MAPPER, requestId);
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** What one line is actually issuing, after the defaults have been filled in. */
	private record Issued(BigDecimal quantity, Unit unit) {
	}

	private record LineRow(
			UUID id, int lineNo, UUID ingredientId, String ingredientName,
			Unit canonicalUnit, BigDecimal quantity, Unit unit) {
	}

	private static final RowMapper<LineRow> LINE_MAPPER = (rs, n) -> new LineRow(
			rs.getObject("id", UUID.class),
			rs.getInt("line_no"),
			rs.getObject("ingredient_id", UUID.class),
			rs.getString("ingredient_name"),
			Unit.valueOf(rs.getString("canonical_unit")),
			rs.getBigDecimal("quantity"),
			Unit.valueOf(rs.getString("unit")));
}

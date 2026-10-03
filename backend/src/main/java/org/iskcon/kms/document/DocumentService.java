package org.iskcon.kms.document;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.donation.DonationReceiptService;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.recipe.RecipeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requesting and fetching generated documents (E2-S5). Requesting one writes its PENDING record and
 * returns its id; the caller then renders it with {@link DocumentGenerationService#generate} once
 * this transaction has committed, and the UI downloads it through {@link #openForDownload} — an
 * authorized backend stream, so a temple's documents stay behind the same access control as its
 * data (no public URLs).
 *
 * <p><strong>Rendered in the request, not on the worker</strong> (changed 2026-10-02). It used to be
 * queued as a Quartz job and the screen asked every second whether it was ready. When it worked that
 * took 5 to 37 seconds on staging, and when the worker was starved of CPU no PDF was made at all.
 * The request now holds for the render and nothing about a download depends on the scheduler. The two steps stay separate because the
 * render must see a committed row: {@code generate} is deliberately not transactional, so it has to
 * run after this method returns rather than inside it.
 */
@Service
public class DocumentService {

	private static final BigDecimal MAX_TARGET_YIELD = BigDecimal.valueOf(50_000);

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final DocumentStorage storage;
	private final JobCardService jobCardService;
	private final WorkOrderService workOrderService;
	private final DonationReceiptService donationReceiptService;

	public DocumentService(
			JdbcTemplate jdbc, RecipeService recipeService, DocumentStorage storage,
			JobCardService jobCardService,
			WorkOrderService workOrderService, DonationReceiptService donationReceiptService) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.storage = storage;
		this.jobCardService = jobCardService;
		this.workOrderService = workOrderService;
		this.donationReceiptService = donationReceiptService;
	}

	@Transactional
	public UUID requestRecipePdf(UUID recipeId, BigDecimal targetYield, String language) {
		// Confirms the recipe exists in this tenant (RLS) before anything is written.
		recipeService.get(recipeId);
		if (targetYield != null && (targetYield.signum() <= 0 || targetYield.compareTo(MAX_TARGET_YIELD) > 0)) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "targetYield"));
		}
		String lang = (language == null || language.isBlank()) ? "en" : language;

		UUID id = UUID.randomUUID();
		UUID createdBy = requesterHere();
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, recipe_id, language, target_yield, status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'RECIPE_PDF', ?, ?, ?, 'PENDING', ?)
				""", id, recipeId, lang, targetYield, createdBy);

		return id;
	}

	/**
	 * Requests a PO sheet (E5-S4). Versioned: each request is a new version so a re-render after a
	 * post-SENT correction keeps the earlier sheets retrievable.
	 */
	@Transactional
	public UUID requestPurchaseOrderPdf(UUID purchaseOrderId, String language) {
		requirePurchaseOrder(purchaseOrderId);
		// No explicit language → the vendor's preferred language (E5-S1); an explicit value overrides.
		String lang = (language == null || language.isBlank())
				? vendorLanguageFor(purchaseOrderId) : language;

		int version = jdbc.queryForObject(
				"SELECT COALESCE(MAX(version), 0) + 1 FROM documents WHERE po_id = ?",
				Integer.class, purchaseOrderId);
		UUID id = UUID.randomUUID();
		UUID createdBy = requesterHere();
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, po_id, version, language, status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'PURCHASE_ORDER_PDF', ?, ?, ?, 'PENDING', ?)
				""", id, purchaseOrderId, version, lang, createdBy);

		return id;
	}

	/**
	 * Requests a job card for one meal (B5). Versioned like a PO sheet rather than overwritten like a
	 * recipe card: a card reprinted after a dish was swapped is a different sheet, and the kitchen may
	 * still be holding the earlier one.
	 *
	 * <p>The language is the recipes appendix's, not the sheet's — the worksheet is always English
	 * (item 17). No explicit choice means the temple's own where this meal's recipes are actually
	 * translated into it, so a PDF and a browser print of the same meal come out the same.
	 * Print it twice if the head cook wants English and the line cooks do not.
	 *
	 * <p>{@code kitchenId} is whose card this is (Epic 12), already resolved by
	 * {@link JobCardService#kitchenFor} — named or not — and stored on the row, so the render draws
	 * exactly the kitchen that was checked here rather than working it out a second time. The document's version stays one sequence per meal: it numbers the PDFs made
	 * for the meal, and the per-kitchen card version is the one printed on the sheet.
	 */
	@Transactional
	public UUID requestJobCardPdf(UUID mealId, UUID kitchenId, String language) {
		String lang = (language == null || language.isBlank())
				? jobCardService.appendixLanguages(mealId).defaultLanguage() : language;

		// The card points at the meal's own row (D-27, V136's documents.meal_id).
		int version = jdbc.queryForObject(
				"SELECT COALESCE(MAX(version), 0) + 1 FROM documents WHERE meal_id = ?",
				Integer.class, mealId);
		UUID id = UUID.randomUUID();
		UUID createdBy = requesterHere();
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, meal_id, kitchen_id, version, language, status,
						created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'JOB_CARD_PDF', ?, ?, ?, ?, 'PENDING', ?)
				""", id, mealId, kitchenId, version, lang, createdBy);

		return id;
	}

	/**
	 * Requests a work order for one approved request (E10-S11). Versioned like a job card rather than
	 * overwritten like a recipe card, and for a sharper reason: the batch list is worked out when the
	 * sheet is rendered, so a sheet reprinted after a lot was spoilt names different lots from the one
	 * somebody in the store room is already holding. Both were true when they were printed, and the
	 * version number on the paper is how you tell which is which.
	 *
	 * <p>Refused before anything is written where the request has no work order — a draft, a
	 * submitted request or a denied one. Writing a document that would only fail to render would turn
	 * a clear "this has not been approved" into a FAILED row somebody has to interpret.
	 *
	 * <p>No explicit language means the temple's own, so a PDF and a browser print of the same
	 * request come out as the same sheet.
	 */
	@Transactional
	public UUID requestWorkOrderPdf(UUID ingredientRequestId, String language) {
		workOrderService.requireWorkOrderAvailable(ingredientRequestId);
		String lang = (language == null || language.isBlank())
				? workOrderService.languages().defaultLanguage() : language;

		int version = jdbc.queryForObject(
				"SELECT COALESCE(MAX(version), 0) + 1 FROM documents WHERE ingredient_request_id = ?",
				Integer.class, ingredientRequestId);
		UUID id = UUID.randomUUID();
		UUID createdBy = requesterHere();
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, ingredient_request_id, version, language,
						status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'WORK_ORDER_PDF', ?, ?, ?, 'PENDING', ?)
				""", id, ingredientRequestId, version, lang, createdBy);

		return id;
	}

	/**
	 * Issues the 80G receipt for one gift (T-110) — or hands back the one that already exists.
	 *
	 * <p><strong>This is the method behind "re-sending does not create a second document".</strong>
	 * Three of the four kinds above are versioned, because each describes a world that moves under
	 * it. A receipt is the opposite kind of paper: it reports a payment that has already happened,
	 * and the copy in the donor's file and the copy in the temple's must be the same document, or
	 * the temple has issued two receipts for one gift. So there is exactly one row per donation,
	 * enforced by V117's unique index behind this lookup rather than only by this lookup.
	 *
	 * <p>A receipt already READY is returned <em>untouched</em> — not re-rendered. That is the sharp
	 * difference from a recipe card, which overwrites in place quite happily. Re-rendering would
	 * quietly reissue the document with whatever the donor's details say today, so a donor who
	 * changed address in June would find their April receipt had changed under them. A row still
	 * PENDING, or one that FAILED, is put back to PENDING for the caller to render again: there are no
	 * bytes to protect in either case.
	 *
	 * <p>{@link DonationReceiptService#issueNumber} runs first and does two jobs — it refuses a
	 * struck gift, and it issues the permanent number. Refusing before anything is written keeps a
	 * clear "this gift was voided" from arriving as a FAILED row somebody has to interpret.
	 */
	@Transactional
	public UUID requestDonationReceiptPdf(UUID donationId, AuthenticatedUser actor) {
		donationReceiptService.issueNumber(donationId, actor);

		Map<String, Object> existing = jdbc.query("""
				SELECT id, status FROM documents
				WHERE donation_id = ? AND kind = 'DONATION_RECEIPT_PDF'
				""", rs -> rs.next() ? Map.of("id", rs.getObject("id", UUID.class),
						"status", rs.getString("status")) : null, donationId);
		if (existing != null) {
			UUID id = (UUID) existing.get("id");
			if (!"READY".equals(existing.get("status"))) {
				jdbc.update("""
						UPDATE documents SET status = 'PENDING', error = NULL, updated_at = now() WHERE id = ?
						""", id);
			}
			return id;
		}

		UUID id = UUID.randomUUID();
		UUID createdBy = requesterHere();
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, donation_id, language, status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'DONATION_RECEIPT_PDF', ?, 'en', 'PENDING', ?)
				""", id, donationId, createdBy);

		return id;
	}

	/**
	 * The receipt issued for a gift, or null where none has been.
	 *
	 * <p>Null rather than a refusal: "has this gift been receipted yet" is the first thing the
	 * donation screen asks, and on most gifts the honest answer is no. A 404 for the ordinary case
	 * would make every screen treat an expected answer as an error.
	 */
	@Transactional(readOnly = true)
	public DocumentView receiptFor(UUID donationId) {
		return jdbc.query(SELECT_COLUMNS + " WHERE donation_id = ? AND kind = 'DONATION_RECEIPT_PDF'",
				MAPPER, donationId).stream().findFirst().orElse(null);
	}

	/** Every work order printed for a request, latest version first. */
	@Transactional(readOnly = true)
	public List<DocumentView> listForIngredientRequest(UUID ingredientRequestId) {
		return jdbc.query(SELECT_COLUMNS + " WHERE ingredient_request_id = ? ORDER BY version DESC",
				MAPPER, ingredientRequestId);
	}

	/** Every card printed for a meal, latest version first. */
	@Transactional(readOnly = true)
	public List<DocumentView> listForMeal(UUID mealId) {
		return jdbc.query(SELECT_COLUMNS + " WHERE meal_id = ? ORDER BY version DESC",
				MAPPER, mealId);
	}

	/** Every generated sheet for a PO, latest version first — the latest is the current sheet. */
	@Transactional(readOnly = true)
	public List<DocumentView> listForPurchaseOrder(UUID purchaseOrderId) {
		return jdbc.query(SELECT_COLUMNS + " WHERE po_id = ? ORDER BY version DESC", MAPPER, purchaseOrderId);
	}

	@Transactional(readOnly = true)
	public DocumentView get(UUID id) {
		return jdbc.query(SELECT_COLUMNS + " WHERE id = ?", MAPPER, id).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("documentId", id)));
	}

	/**
	 * The requesting person's account at this temple, recorded as the document's author.
	 *
	 * <p>Looked up by the signed-in uid <em>and</em> this temple (T-190). By uid alone it relied on the
	 * row policy to supply the temple, and the policy on {@code users} is the one that does not: its
	 * {@code firebase_uid = app.auth_uid} branch shows a person every account they hold (V2), and since
	 * V52 a person may hold one at each of several temples. For anybody with a second temple that
	 * returned two rows and {@code queryForObject} threw before anything was written, so every document
	 * they asked for — recipe card, PO sheet, job card, work order, receipt — failed. It never picked
	 * the wrong account; it refused outright, which is why it went unnoticed only while nobody belonged
	 * to two temples.
	 */
	private UUID requesterHere() {
		return jdbc.queryForObject("""
				SELECT id FROM users
				WHERE firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')
				  AND tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", UUID.class);
	}

	private void requirePurchaseOrder(UUID poId) {
		Integer n = jdbc.queryForObject(
				"SELECT count(*) FROM purchase_orders WHERE id = ?", Integer.class, poId);
		if (n == null || n == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("purchaseOrderId", poId));
		}
	}

	/** The preferred language of the PO's vendor, defaulting to English. */
	private String vendorLanguageFor(UUID poId) {
		String lang = jdbc.queryForObject("""
				SELECT v.preferred_language FROM purchase_orders po
				JOIN vendors v ON v.id = po.vendor_id WHERE po.id = ?
				""", String.class, poId);
		return (lang == null || lang.isBlank()) ? "en" : lang;
	}

	/** The stored bytes for download, or a clear error if the document isn't READY yet. */
	@Transactional(readOnly = true)
	public InputStream openForDownload(UUID id) {
		Map<String, Object> row;
		try {
			row = jdbc.queryForMap("SELECT status, storage_key FROM documents WHERE id = ?", id);
		} catch (org.springframework.dao.EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("documentId", id), e);
		}
		if (!"READY".equals(row.get("status")) || row.get("storage_key") == null) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("documentId", id, "status", row.get("status")));
		}
		return storage.open((String) row.get("storage_key"));
	}

	private static final String SELECT_COLUMNS = """
			SELECT id, kind, recipe_id, po_id, version, language, target_yield, status, error,
				   created_at, ready_at
			FROM documents""";

	private static final RowMapper<DocumentView> MAPPER = (rs, rowNum) -> new DocumentView(
			rs.getObject("id", UUID.class),
			rs.getString("kind"),
			rs.getObject("recipe_id", UUID.class),
			rs.getObject("po_id", UUID.class),
			rs.getInt("version"),
			rs.getString("language"),
			rs.getBigDecimal("target_yield"),
			rs.getString("status"),
			rs.getString("error"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("ready_at", OffsetDateTime.class) == null
					? null : rs.getObject("ready_at", OffsetDateTime.class).toInstant());
}

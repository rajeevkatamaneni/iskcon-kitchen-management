package org.iskcon.kms.document;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.observability.LogContext;
import org.iskcon.kms.jobs.KmsJob;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.tenancy.TenantContext;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requesting and fetching generated documents (E2-S5). Requesting a recipe PDF creates a PENDING
 * record and enqueues the work off the request thread; the UI polls {@link #get} until READY, then
 * downloads through {@link #openForDownload} — an authorized backend stream, so a temple's documents
 * stay behind the same access control as its data (no public URLs).
 */
@Service
public class DocumentService {

	private static final BigDecimal MAX_TARGET_YIELD = BigDecimal.valueOf(50_000);
	private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final DocumentStorage storage;
	private final ObjectProvider<Scheduler> scheduler;
	private final JobCardService jobCardService;
	private final WorkOrderService workOrderService;

	public DocumentService(
			JdbcTemplate jdbc, RecipeService recipeService, DocumentStorage storage,
			ObjectProvider<Scheduler> scheduler, JobCardService jobCardService,
			WorkOrderService workOrderService) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.storage = storage;
		this.scheduler = scheduler;
		this.jobCardService = jobCardService;
		this.workOrderService = workOrderService;
	}

	@Transactional
	public UUID requestRecipePdf(UUID recipeId, BigDecimal targetYield, String language) {
		// Confirms the recipe exists in this tenant (RLS) before we queue anything.
		recipeService.get(recipeId);
		if (targetYield != null && (targetYield.signum() <= 0 || targetYield.compareTo(MAX_TARGET_YIELD) > 0)) {
			throw new ApplicationException(
					ErrorCode.VALIDATION_FAILED, Map.of("field", "targetYield"));
		}
		String lang = (language == null || language.isBlank()) ? "en" : language;

		UUID id = UUID.randomUUID();
		UUID createdBy = jdbc.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')",
				UUID.class);
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, recipe_id, language, target_yield, status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'RECIPE_PDF', ?, ?, ?, 'PENDING', ?)
				""", id, recipeId, lang, targetYield, createdBy);

		enqueue(id);
		return id;
	}

	/**
	 * Requests a PO sheet (E5-S4). Versioned: each request is a new version so a re-render after a
	 * post-SENT correction keeps the earlier sheets retrievable. The on-demand path — requires a
	 * scheduler/worker, like a recipe PDF.
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
		UUID createdBy = jdbc.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')",
				UUID.class);
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, po_id, version, language, status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'PURCHASE_ORDER_PDF', ?, ?, ?, 'PENDING', ?)
				""", id, purchaseOrderId, version, lang, createdBy);

		enqueue(id);
		return id;
	}

	/**
	 * Requests a job card for one meal (B5). Versioned like a PO sheet rather than overwritten like a
	 * recipe card: a card reprinted after a dish was swapped is a different sheet, and the kitchen may
	 * still be holding the earlier one.
	 *
	 * <p>The language is the recipes appendix's, not the sheet's — the worksheet is always English
	 * (item 17). No explicit choice means the temple's own where this meal's recipes are actually
	 * translated into it, so a queued PDF and a browser print of the same meal come out the same.
	 * Print it twice if the head cook wants English and the line cooks do not.
	 */
	@Transactional
	public UUID requestJobCardPdf(UUID mealServiceId, String language) {
		String lang = (language == null || language.isBlank())
				? jobCardService.appendixLanguages(mealServiceId).defaultLanguage() : language;

		int version = jdbc.queryForObject(
				"SELECT COALESCE(MAX(version), 0) + 1 FROM documents WHERE meal_service_id = ?",
				Integer.class, mealServiceId);
		UUID id = UUID.randomUUID();
		UUID createdBy = jdbc.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')",
				UUID.class);
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, meal_service_id, version, language, status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'JOB_CARD_PDF', ?, ?, ?, 'PENDING', ?)
				""", id, mealServiceId, version, lang, createdBy);

		enqueue(id);
		return id;
	}

	/**
	 * Requests a work order for one approved request (E10-S11). Versioned like a job card rather than
	 * overwritten like a recipe card, and for a sharper reason: the batch list is worked out when the
	 * sheet is rendered, so a sheet reprinted after a lot was spoilt names different lots from the one
	 * somebody in the store room is already holding. Both were true when they were printed, and the
	 * version number on the paper is how you tell which is which.
	 *
	 * <p>Refused before anything is queued where the request has no work order — a draft, a submitted
	 * request or a denied one. Queueing a document that the worker would only fail to render would
	 * turn a clear "this has not been approved" into a FAILED row somebody has to interpret.
	 *
	 * <p>No explicit language means the temple's own, so a queued PDF and a browser print of the same
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
		UUID createdBy = jdbc.queryForObject(
				"SELECT id FROM users WHERE firebase_uid = NULLIF(current_setting('app.auth_uid', true), '')",
				UUID.class);
		jdbc.update("""
				INSERT INTO documents (id, tenant_id, kind, ingredient_request_id, version, language,
						status, created_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						'WORK_ORDER_PDF', ?, ?, ?, 'PENDING', ?)
				""", id, ingredientRequestId, version, lang, createdBy);

		enqueue(id);
		return id;
	}

	/** Every work order printed for a request, latest version first. */
	@Transactional(readOnly = true)
	public List<DocumentView> listForIngredientRequest(UUID ingredientRequestId) {
		return jdbc.query(SELECT_COLUMNS + " WHERE ingredient_request_id = ? ORDER BY version DESC",
				MAPPER, ingredientRequestId);
	}

	/** Every card printed for a meal, latest version first. */
	@Transactional(readOnly = true)
	public List<DocumentView> listForMealService(UUID mealServiceId) {
		return jdbc.query(SELECT_COLUMNS + " WHERE meal_service_id = ? ORDER BY version DESC",
				MAPPER, mealServiceId);
	}

	/**
	 * Auto-generation on a state change (a PO being sent, E5-S3). Best-effort: where no scheduler is
	 * available — the hermetic test context, or an API node without a worker — it logs and skips
	 * rather than failing the send. The sheet can still be produced on demand.
	 */
	@Transactional
	public void autoGeneratePurchaseOrderPdf(UUID purchaseOrderId) {
		if (scheduler.getIfAvailable() == null) {
			log.info("No scheduler available; skipping auto PO sheet for {}", purchaseOrderId);
			return;
		}
		requestPurchaseOrderPdf(purchaseOrderId, null);
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

	private void enqueue(UUID documentId) {
		Scheduler quartz = scheduler.getIfAvailable();
		if (quartz == null) {
			throw new IllegalStateException(
					"No scheduler available to generate the document — is the worker enabled?");
		}
		UUID tenantId = TenantContext.get().orElseThrow(() ->
				new IllegalStateException("requestRecipePdf must run within a tenant context"));

		JobBuilder builder = JobBuilder.newJob(GenerateDocumentJob.class)
				.withIdentity("generate-document-" + documentId)
				.usingJobData(GenerateDocumentJob.DOCUMENT_ID_KEY, documentId.toString())
				.usingJobData(KmsJob.TENANT_KEY, tenantId.toString())
				.requestRecovery();

		String requestId = MDC.get(LogContext.REQUEST_ID);
		if (requestId != null) {
			builder.usingJobData(KmsJob.REQUEST_ID_KEY, requestId);
		}

		JobDetail job = builder.build();
		Trigger trigger = TriggerBuilder.newTrigger().forJob(job).startNow().build();
		try {
			quartz.scheduleJob(job, trigger);
		} catch (org.quartz.SchedulerException e) {
			throw new ApplicationException(ErrorCode.UNEXPECTED_FAILURE, Map.of(), e);
		}
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

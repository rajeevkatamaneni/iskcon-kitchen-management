package org.iskcon.kms.document;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
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

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final DocumentStorage storage;
	private final ObjectProvider<Scheduler> scheduler;

	public DocumentService(
			JdbcTemplate jdbc, RecipeService recipeService, DocumentStorage storage,
			ObjectProvider<Scheduler> scheduler) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.storage = storage;
		this.scheduler = scheduler;
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

	@Transactional(readOnly = true)
	public DocumentView get(UUID id) {
		return jdbc.query("""
				SELECT id, kind, recipe_id, language, target_yield, status, error, created_at, ready_at
				FROM documents WHERE id = ?
				""", MAPPER, id).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("documentId", id)));
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

	private static final RowMapper<DocumentView> MAPPER = (rs, rowNum) -> new DocumentView(
			rs.getObject("id", UUID.class),
			rs.getString("kind"),
			rs.getObject("recipe_id", UUID.class),
			rs.getString("language"),
			rs.getBigDecimal("target_yield"),
			rs.getString("status"),
			rs.getString("error"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("ready_at", OffsetDateTime.class) == null
					? null : rs.getObject("ready_at", OffsetDateTime.class).toInstant());
}

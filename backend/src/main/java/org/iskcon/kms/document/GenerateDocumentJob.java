package org.iskcon.kms.document;

import java.util.UUID;
import org.iskcon.kms.jobs.KmsJob;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Background job that renders a requested document (E2-S5). A thin Quartz wrapper over
 * {@link DocumentGenerationService}, which does the idempotent work; KmsJob supplies the tenant
 * context and the shared logging/metrics.
 */
public class GenerateDocumentJob extends KmsJob {

	/** Job-data key holding the document id (a UUID string) to generate. */
	public static final String DOCUMENT_ID_KEY = "kms.documentId";

	@Autowired
	private DocumentGenerationService documentGenerationService;

	@Override
	protected void run(JobExecutionContext context) {
		String documentId = context.getMergedJobDataMap().getString(DOCUMENT_ID_KEY);
		documentGenerationService.generate(UUID.fromString(documentId));
	}

	@Override
	protected String jobName() {
		return "generate-document";
	}
}

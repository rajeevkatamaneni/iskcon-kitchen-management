package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exercises {@link GcsDocumentStorage} against a real bucket (E2-S5). Runs only when DOCUMENTS_BUCKET
 * is set (local dev with ADC) — never in CI, which has no GCS credentials, keeping the suite
 * hermetic. Uploads under {@code generated/}, which the bucket lifecycle auto-expires.
 */
@EnabledIfEnvironmentVariable(named = "DOCUMENTS_BUCKET", matches = ".+")
class GcsDocumentStorageSmokeIT {

	@Test
	@DisplayName("stores and reads back bytes against the real GCS bucket")
	void roundTrip() throws Exception {
		String bucket = System.getenv("DOCUMENTS_BUCKET");
		String project = System.getenv("GCP_PROJECT_ID");
		GcsDocumentStorage storage = new GcsDocumentStorage(bucket, project == null ? "" : project);

		String key = "generated/dev/smoke-" + UUID.randomUUID() + ".pdf";
		byte[] content = ("%PDF-1.4\n% GCS smoke test\n%%EOF\n").getBytes(StandardCharsets.UTF_8);

		storage.store(key, content, "application/pdf");
		try (InputStream in = storage.open(key)) {
			assertThat(in.readAllBytes()).isEqualTo(content);
		}
	}
}

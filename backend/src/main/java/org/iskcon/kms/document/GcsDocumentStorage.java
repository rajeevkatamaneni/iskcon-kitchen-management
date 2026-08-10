package org.iskcon.kms.document;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.InputStream;
import java.nio.channels.Channels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Google Cloud Storage document storage (E2-S5). Enabled with {@code kms.documents.storage=gcs}
 * and a bucket name. Authenticates via Application Default Credentials — the runtime service
 * account when deployed, the developer's ADC locally. Downloads are served through an authorized
 * backend endpoint (see {@link #open}), not public/signed URLs, so a temple's documents stay behind
 * the same access control as the rest of its data.
 */
@Component
@ConditionalOnProperty(name = "kms.documents.storage", havingValue = "gcs")
public class GcsDocumentStorage implements DocumentStorage {

	private final Storage storage;
	private final String bucket;

	public GcsDocumentStorage(
			@Value("${kms.documents.bucket}") String bucket,
			@Value("${kms.gcp.project-id:}") String projectId) {
		this.bucket = bucket;
		StorageOptions.Builder options = StorageOptions.newBuilder();
		if (projectId != null && !projectId.isBlank()) {
			options.setProjectId(projectId);
		}
		this.storage = options.build().getService();
	}

	@Override
	public String store(String key, byte[] content, String contentType) {
		BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, key))
				.setContentType(contentType)
				.build();
		storage.create(info, content);
		return key;
	}

	@Override
	public InputStream open(String key) {
		return Channels.newInputStream(storage.reader(BlobId.of(bucket, key)));
	}
}

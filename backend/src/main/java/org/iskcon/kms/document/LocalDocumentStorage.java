package org.iskcon.kms.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default storage: a local directory. Lets the pipeline run and be tested with no cloud
 * dependency. Real deployments use {@link GcsDocumentStorage} ({@code kms.documents.storage=gcs}).
 */
@Component
@ConditionalOnProperty(name = "kms.documents.storage", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorage implements DocumentStorage {

	private final Path baseDir;

	public LocalDocumentStorage(
			@Value("${kms.documents.local-dir:${java.io.tmpdir}/kms-documents}") String dir) {
		this.baseDir = Path.of(dir);
	}

	@Override
	public String store(String key, byte[] content, String contentType) {
		try {
			Path target = baseDir.resolve(key);
			Files.createDirectories(target.getParent());
			Files.write(target, content);
			return key;
		} catch (IOException e) {
			throw new ApplicationException(ErrorCode.DOCUMENT_GENERATION_FAILED, java.util.Map.of(), e);
		}
	}

	@Override
	public InputStream open(String key) {
		try {
			return Files.newInputStream(baseDir.resolve(key));
		} catch (IOException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, java.util.Map.of("key", key), e);
		}
	}
}

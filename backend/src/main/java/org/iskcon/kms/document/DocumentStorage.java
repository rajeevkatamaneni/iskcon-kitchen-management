package org.iskcon.kms.document;

import java.io.InputStream;

/**
 * Object storage for generated documents (E2-S5). Real deployments use GCS; local dev/tests use a
 * temp directory. Reads flow back through {@link #open} so downloads are served by an authorized
 * backend endpoint rather than a public URL — keeping tenant documents behind the same access
 * control as everything else.
 */
public interface DocumentStorage {

	/** Stores bytes under a key, returning the key actually used. */
	String store(String key, byte[] content, String contentType);

	/** Opens a stored object for streaming. Caller closes the stream. */
	InputStream open(String key);
}

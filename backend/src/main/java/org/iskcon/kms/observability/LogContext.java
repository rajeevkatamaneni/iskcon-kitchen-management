package org.iskcon.kms.observability;

/**
 * The MDC keys that appear on every log line (E1-S11). One place for the names so the filter, the
 * authentication filter, and the job runner all agree, and so the JSON log fields are stable for
 * anything querying Cloud Logging.
 */
public final class LogContext {

	public static final String REQUEST_ID = "request_id";
	public static final String TENANT_ID = "tenant_id";
	public static final String USER_ID = "user_id";

	private LogContext() {
	}
}

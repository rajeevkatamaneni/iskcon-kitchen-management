package org.iskcon.kms.assistant.api;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the runner is, how long a run may take, and the two daily limits (E10-T10, T11).
 *
 * <p><strong>Why the fields can be changed after start-up.</strong> Only the tests change them, and
 * only through package-private setters. The alternative was a property per test class, and every
 * distinct property set is another Spring context in the suite's cache (memory note "test context
 * cache explosion": CI once died of heap with no failing assertion). Pointing the runner at a fake,
 * at a dead port, or shortening the deadline is done on the shared context and put back afterwards.
 *
 * <p><strong>The limits are platform-wide defaults for now, not per-temple settings.</strong> Plan §9
 * proposes 100 runs per person and 500 per temple a day "as settings", and the app's per-temple
 * settings are columns on {@code tenant_settings} with a default, read by
 * {@code shift.TenantSettingsService} and written by {@code PUT /api/v1/settings/…} under
 * {@code MANAGE_TEMPLE_SETTINGS}. Following that needs a migration, and no migration number was
 * reserved for this task. So the limits are read here, through {@link #perPersonDailyLimit()} and
 * {@link #perTempleDailyLimit()} only, and the per-temple column is a follow-up that changes those
 * two methods' callers and nothing else. Until it lands, the error texts' "ask a Temple Admin to
 * raise the limit" is not yet true: the limit is raised by setting the environment variables.
 */
@Component
public class AssistantSettings {

	private volatile URI runnerUrl;
	private volatile String mcpUrl;
	private volatile Duration runTimeout;
	private volatile Duration connectTimeout;
	private final String profile;
	private final String model;
	private volatile int perPersonDailyLimit;
	private volatile int perTempleDailyLimit;

	public AssistantSettings(
			@Value("${kms.assistant.runner.url:http://localhost:8090}") String runnerUrl,
			@Value("${kms.assistant.runner.mcp-url:http://localhost:8080/mcp}") String mcpUrl,
			@Value("${kms.assistant.runner.timeout:PT75S}") Duration runTimeout,
			@Value("${kms.assistant.runner.connect-timeout:PT5S}") Duration connectTimeout,
			@Value("${kms.assistant.runner.profile:kitchen-assistant}") String profile,
			@Value("${kms.assistant.runner.model:sonnet}") String model,
			@Value("${kms.assistant.budget.per-person-daily:100}") int perPersonDailyLimit,
			@Value("${kms.assistant.budget.per-temple-daily:500}") int perTempleDailyLimit) {
		if (perPersonDailyLimit < 0 || perTempleDailyLimit < 0) {
			throw new IllegalStateException("kms.assistant.budget limits cannot be negative");
		}
		this.runnerUrl = URI.create(stripTrailingSlash(runnerUrl));
		this.mcpUrl = mcpUrl;
		this.runTimeout = runTimeout;
		this.connectTimeout = connectTimeout;
		this.profile = profile;
		this.model = model;
		this.perPersonDailyLimit = perPersonDailyLimit;
		this.perTempleDailyLimit = perTempleDailyLimit;
	}

	/** The runner's base URL; {@code POST /run} is appended. */
	URI runnerUrl() {
		return runnerUrl;
	}

	/** The URL {@code claude -p} calls back on for tools: this api's own {@code /mcp}, as the runner reaches it. */
	String mcpUrl() {
		return mcpUrl;
	}

	/**
	 * The api's own deadline for one run. Longer than the runner's 60 s kill on purpose: the runner's
	 * timeout arrives as a tidy {@code runner_error} line and is the normal path. This one is for a
	 * runner that has stopped answering altogether.
	 */
	Duration runTimeout() {
		return runTimeout;
	}

	Duration connectTimeout() {
		return connectTimeout;
	}

	String profile() {
		return profile;
	}

	/** Written on the run row when it starts; replaced by the model the CLI reports, when it reports one. */
	String model() {
		return model;
	}

	int perPersonDailyLimit() {
		return perPersonDailyLimit;
	}

	int perTempleDailyLimit() {
		return perTempleDailyLimit;
	}

	// --- For tests only; see the class comment. ---

	void setRunnerUrl(String url) {
		this.runnerUrl = URI.create(stripTrailingSlash(url));
	}

	void setRunTimeout(Duration timeout) {
		this.runTimeout = timeout;
	}

	void setPerPersonDailyLimit(int limit) {
		this.perPersonDailyLimit = limit;
	}

	void setPerTempleDailyLimit(int limit) {
		this.perTempleDailyLimit = limit;
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}

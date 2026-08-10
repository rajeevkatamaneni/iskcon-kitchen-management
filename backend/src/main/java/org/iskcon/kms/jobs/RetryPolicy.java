package org.iskcon.kms.jobs;

import java.time.Duration;

/**
 * How many times a job may run before it is given up on, and how long to wait between tries.
 *
 * <p>Backoff is exponential from {@code baseBackoff}: the wait after the first failure is the base,
 * then double, then double again. Quartz has no built-in retry-with-backoff, so {@link KmsJob}
 * applies this by scheduling the next attempt itself.
 */
public record RetryPolicy(int maxAttempts, Duration baseBackoff) {

	/** No retry: one attempt, and a failure is a failure. The default for a job that says nothing. */
	public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO);

	public RetryPolicy {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		if (baseBackoff.isNegative()) {
			throw new IllegalArgumentException("baseBackoff cannot be negative");
		}
	}

	public static RetryPolicy of(int maxAttempts, Duration baseBackoff) {
		return new RetryPolicy(maxAttempts, baseBackoff);
	}

	/**
	 * How long to wait after a given attempt fails before the next one. {@code base × 2^(n-1)} —
	 * base after attempt 1, double after attempt 2, and so on. The shift is capped so a large
	 * attempt count cannot overflow.
	 */
	public Duration backoffAfterAttempt(int failedAttempt) {
		long factor = 1L << Math.min(Math.max(failedAttempt - 1, 0), 16);
		return baseBackoff.multipliedBy(factor);
	}
}

package org.iskcon.kms.shift;

import java.util.UUID;

/**
 * The outcome of a successful signup (E6-S3). {@code overlapWarning} is true when the volunteer is
 * now on another shift whose time overlaps this one — surfaced, never blocked (families share duties).
 */
public record SignupResult(UUID signupId, boolean overlapWarning) {
}

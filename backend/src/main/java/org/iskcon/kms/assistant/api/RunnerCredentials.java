package org.iskcon.kms.assistant.api;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What the api shows the runner to be let in: the seam for E10-S6, deliberately left empty.
 *
 * <p>Locally the runner listens on localhost and takes anyone (T9). On Cloud Run it will be an
 * internal service that only the api's service account may invoke (plan §1 row H), and every call
 * then needs {@code Authorization: Bearer <Google identity token for the runner's URL>}, fetched from
 * the metadata server and cached until shortly before it expires. That belongs with the Terraform that
 * creates the service and grants the invoker role (T24), so it is not written here: S6 replaces this
 * bean with one that returns the header, and {@link RunnerClient} needs no change.
 *
 * <p>Returning nothing is the safe default in both directions: a local runner accepts it, and a
 * locked-down Cloud Run runner refuses it with a 403, which the api turns into "the assistant isn't
 * available" (KMS-500008) rather than anything that leaks.
 */
@Component
public class RunnerCredentials {

	/** The {@code Authorization} header value for one call to the runner, if one is needed. */
	public Optional<String> authorizationHeader() {
		return Optional.empty();
	}
}

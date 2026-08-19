package org.iskcon.kms.notification;

import java.util.Map;

/**
 * Where a message's body comes from when it is not in the message's own parameters.
 *
 * <p>Every notification the system sends is a short sentence assembled from a handful of values, so
 * the values travel with it and the template renders them — that is the ordinary case and it needs
 * no seam. A communication a temple wrote is the exception this exists for: the body is a newsletter,
 * and putting a copy of it in each of four hundred recipients' parameter rows would store the same
 * letter four hundred times.
 *
 * <p>So exactly one kind of message resolves its body from elsewhere, and this names which. It is a
 * seam for a case that exists, not a general plugin point.
 */
public interface OutboundBodySource {

	boolean handles(NotificationTemplate template);

	/** The message, given the parameters that were stored — which say where the body lives. */
	RenderedMessage render(Map<String, Object> params);
}

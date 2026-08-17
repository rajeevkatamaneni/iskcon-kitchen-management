package org.iskcon.kms.notification;

import java.util.List;
import java.util.Map;

/**
 * One message on its way out, in both the forms a channel might need.
 *
 * <p>SMS and email want a rendered sentence. WhatsApp cannot have one: Meta will not carry
 * business-initiated free text, only a template it has already approved, filled in positionally. So
 * a channel adapter is handed the template and its values as well as the rendered result, and each
 * takes the form it can actually send.
 *
 * @param template the message being sent, which is also the name of the approved WhatsApp template
 * @param params   its values by name, as the rest of the system addresses them
 * @param rendered the sentence, for the channels that carry sentences
 */
public record OutboundMessage(
		NotificationTemplate template, Map<String, Object> params, RenderedMessage rendered) {

	/**
	 * The values in the order the template's placeholders expect them.
	 *
	 * <p>The whole reason {@link NotificationTemplate#parameterOrder()} exists: everything else here
	 * addresses a parameter by name, and Meta addresses it by position.
	 */
	public List<String> orderedParameters() {
		return template.parameterOrder().stream()
				.map(name -> {
					Object value = params == null ? null : params.get(name);
					return value == null ? "" : value.toString();
				})
				.toList();
	}
}

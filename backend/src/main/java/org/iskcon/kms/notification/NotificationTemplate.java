package org.iskcon.kms.notification;

import java.util.Map;

/**
 * The messages the system is allowed to send. Kept as a small enum rather than free text because a
 * WhatsApp utility message must correspond to a template Meta has approved — the name here maps to
 * that approved template, and the body is what the SMS and email channels render locally.
 *
 * <p>Epic 1 ships the two the story names; later epics add their own the same way.
 */
public enum NotificationTemplate {

	SHIFT_REMINDER("shift_reminder") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Shift reminder",
					"Reminder: your %s shift at %s is on %s at %s.".formatted(
							value(params, "role"), value(params, "temple"),
							value(params, "date"), value(params, "time")));
		}
	},

	PO_DELIVERY("po_delivery") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Purchase order " + value(params, "poNumber"),
					"Purchase order %s for %s is ready: %s.".formatted(
							value(params, "poNumber"), value(params, "vendor"),
							value(params, "summary")));
		}
	};

	private final String whatsappTemplateName;

	NotificationTemplate(String whatsappTemplateName) {
		this.whatsappTemplateName = whatsappTemplateName;
	}

	/** The name of the corresponding Meta-approved WhatsApp template. */
	public String whatsappTemplateName() {
		return whatsappTemplateName;
	}

	public abstract RenderedMessage render(Map<String, Object> params);

	static String value(Map<String, Object> params, String key) {
		Object raw = params == null ? null : params.get(key);
		return raw == null ? "" : raw.toString();
	}
}

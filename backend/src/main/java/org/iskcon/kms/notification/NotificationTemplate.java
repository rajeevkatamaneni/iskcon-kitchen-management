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
	},

	DONATION_THANK_YOU("donation_thank_you") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Thank you for your donation",
					"Dear %s, thank you for your generous donation to %s on %s. Hare Krishna."
							.formatted(value(params, "donor"), value(params, "temple"), value(params, "date")));
		}
	},

	SHIFT_SIGNUP_CONFIRMED("shift_signup_confirmed") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"You're signed up: " + value(params, "title"),
					"Hare Krishna! You're signed up for %s on %s, %s at %s. Thank you for your seva."
							.formatted(value(params, "title"), value(params, "date"),
									value(params, "time"), value(params, "location")));
		}
	},

	WAITLIST_PROMOTED("waitlist_promoted") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"A spot opened: you're in for " + value(params, "title"),
					"Good news! A spot opened and you're now signed up for %s on %s, %s at %s. See you there!"
							.formatted(value(params, "title"), value(params, "date"),
									value(params, "time"), value(params, "location")));
		}
	},

	SHIFT_CANCELLED("shift_cancelled") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Shift cancelled: " + value(params, "title"),
					"We're sorry — the %s shift on %s at %s has been cancelled. Thank you for offering to serve; please check the app for other shifts."
							.formatted(value(params, "title"), value(params, "date"), value(params, "temple")));
		}
	},

	STAFF_SCHEDULE_UPDATED("staff_schedule_updated") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Your schedule at " + value(params, "temple") + " has changed",
					"Hare Krishna %s, your work schedule at %s has been updated. Please check the app for your latest hours."
							.formatted(value(params, "name"), value(params, "temple")));
		}
	},

	LOW_STOCK_DIGEST("low_stock_digest") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Low stock at " + value(params, "temple"),
					"%s item(s) at %s are below their reorder level: %s.".formatted(
							value(params, "count"), value(params, "temple"), value(params, "items")));
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

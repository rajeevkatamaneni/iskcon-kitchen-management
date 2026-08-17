package org.iskcon.kms.notification;

import java.util.LinkedHashMap;
import java.util.List;
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

		@Override
		public List<String> parameterOrder() {
			return List.of("role", "temple", "date", "time");
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

		@Override
		public List<String> parameterOrder() {
			return List.of("poNumber", "vendor", "summary");
		}
	},

	RECURRING_CHARGE_FAILED("recurring_charge_failed") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"A recurring donation payment to " + value(params, "temple") + " didn't go through",
					"A cycle of your recurring donation to %s couldn't be collected. Your bank may retry it automatically; you can also update your payment method or restart the plan in the app."
							.formatted(value(params, "temple")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("temple");
		}
	},

	WISHLIST_SPONSORSHIP_CONVERTED("wishlist_sponsorship_converted") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Thank you — your gift to " + value(params, "temple"),
					"That wish-list item was just fully sponsored by the time your payment completed, so your generous gift to %s has been received as a general donation instead. Thank you for your seva. Hare Krishna."
							.formatted(value(params, "temple")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("temple");
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

		@Override
		public List<String> parameterOrder() {
			return List.of("donor", "temple", "date");
		}
	},

	SHIFT_BROADCAST("shift_broadcast") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Update about your shift: " + value(params, "title"),
					"Update about your %s shift: %s".formatted(value(params, "title"), value(params, "message")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "message");
		}
	},

	VOLUNTEER_SHIFT_REMINDER("volunteer_shift_reminder") {
		@Override
		public RenderedMessage render(Map<String, Object> params) {
			return new RenderedMessage(
					"Reminder: " + value(params, "title"),
					"Reminder: your %s shift is on %s, %s at %s. If you can't make it, please release your spot in the app."
							.formatted(value(params, "title"), value(params, "date"),
									value(params, "time"), value(params, "location")));
		}

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "time", "location");
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

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "time", "location");
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

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "time", "location");
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

		@Override
		public List<String> parameterOrder() {
			return List.of("title", "date", "temple");
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

		@Override
		public List<String> parameterOrder() {
			return List.of("name", "temple");
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

		@Override
		public List<String> parameterOrder() {
			return List.of("count", "temple", "items");
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

	/**
	 * The parameters this message's body reads, in the order the body reads them.
	 *
	 * <p>Meta's templates are positional — {@code {{1}}}, {@code {{2}}} — while everything else here
	 * addresses parameters by name, and this is the one place the two meet. Getting the order wrong
	 * does not fail: it sends somebody a shift reminder with the date where the temple should be.
	 */
	public abstract List<String> parameterOrder();

	/**
	 * The body as Meta stores it, with numbered placeholders — derived by rendering the message with
	 * each parameter set to its own placeholder.
	 *
	 * <p>Derived rather than written out a second time on purpose. A hand-copied WhatsApp body would
	 * drift from the SMS one the first time a sentence was reworded, and nothing would notice: the
	 * WhatsApp copy lives at Meta, months from the code that no longer matches it. This way there is
	 * one sentence, and WhatsApp gets a view of it.
	 */
	public String whatsappBodyText() {
		Map<String, Object> placeholders = new LinkedHashMap<>();
		List<String> order = parameterOrder();
		for (int i = 0; i < order.size(); i++) {
			placeholders.put(order.get(i), "{{" + (i + 1) + "}}");
		}
		return render(placeholders).body();
	}

	/**
	 * Every message here is UTILITY: each one is the consequence of something the temple or the
	 * person already did — a shift taken, an order placed, a gift given. None of it is marketing,
	 * which Meta prices differently and judges more harshly, and calling it so would be untrue as
	 * well as expensive.
	 */
	public String whatsappCategory() {
		return "UTILITY";
	}

	/** Sample values for Meta's reviewer, who will not approve a template without them. */
	public List<String> whatsappExampleValues() {
		return parameterOrder().stream().map(NotificationTemplate::example).toList();
	}

	private static String example(String parameter) {
		return switch (parameter) {
			case "temple" -> "ISKCON South Bengaluru";
			case "date" -> "12 August";
			case "time" -> "6:00 am";
			case "role", "title" -> "Kitchen seva";
			case "location" -> "Main kitchen";
			case "donor", "name" -> "Radha Devi";
			case "poNumber" -> "PO-1042";
			case "vendor" -> "Sri Balaji Traders";
			case "summary" -> "25 kg rice, 10 kg dal";
			case "message" -> "Please arrive fifteen minutes early";
			case "count" -> "3";
			case "items" -> "rice, toor dal, ghee";
			default -> parameter;
		};
	}

	static String value(Map<String, Object> params, String key) {
		Object raw = params == null ? null : params.get(key);
		return raw == null ? "" : raw.toString();
	}
}

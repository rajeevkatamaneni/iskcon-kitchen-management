package org.iskcon.kms.notification;

import java.util.UUID;

/**
 * Who a notification is for: either a user of this temple (whose channel preference, contact
 * details and consent we resolve from their account) or a raw contact with no account — a vendor
 * reached at a phone number for a purchase order.
 */
public record NotificationRecipient(UUID userId, String rawPhone, String rawEmail) {

	public static NotificationRecipient user(UUID userId) {
		return new NotificationRecipient(userId, null, null);
	}

	public static NotificationRecipient vendor(String phone, String email) {
		return contact(phone, email);
	}

	/**
	 * A raw contact with no account, reached for a specific transaction: a vendor for a purchase
	 * order, a donor for a thank-you (E3-S5). Like a vendor, it carries no consent gate — it is a
	 * one-off transactional acknowledgement, not personal communication a person opted into.
	 */
	public static NotificationRecipient contact(String phone, String email) {
		return new NotificationRecipient(null, phone, email);
	}

	public boolean isUser() {
		return userId != null;
	}
}

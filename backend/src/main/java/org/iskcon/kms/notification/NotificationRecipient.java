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
		return new NotificationRecipient(null, phone, email);
	}

	public boolean isUser() {
		return userId != null;
	}
}

package org.iskcon.kms.notification;

/**
 * The delivery outcomes we act on. Providers report more (sent, read, …); those we do not model map
 * to {@link #OTHER} and are ignored rather than guessed at.
 */
public enum DeliveryStatus {

	DELIVERED,
	FAILED,
	OTHER;

	/** Maps a provider's status word to what we act on. Read counts as delivered. */
	public static DeliveryStatus from(String providerStatus) {
		if (providerStatus == null) {
			return OTHER;
		}
		return switch (providerStatus.trim().toLowerCase()) {
			case "delivered", "read" -> DELIVERED;
			case "failed" -> FAILED;
			default -> OTHER;
		};
	}
}

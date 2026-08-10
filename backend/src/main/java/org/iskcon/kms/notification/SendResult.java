package org.iskcon.kms.notification;

/** The outcome of one channel attempt. */
public record SendResult(boolean sent, String providerMessageId, String detail) {

	public static SendResult sent(String providerMessageId) {
		return new SendResult(true, providerMessageId, null);
	}

	public static SendResult failed(String detail) {
		return new SendResult(false, null, detail);
	}
}

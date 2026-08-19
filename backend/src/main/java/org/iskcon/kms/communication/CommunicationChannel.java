package org.iskcon.kms.communication;

/**
 * How a communication travels (E8-S2). Two, and they are not equivalent.
 *
 * <p>{@link #EMAIL} carries the letter itself. {@link #WHATSAPP} cannot: Meta delivers only
 * business-initiated messages matching a template it has already approved, so what goes out there is
 * a short approved announcement with a link to the web copy. That is a real limit of the channel, not
 * a shortcut we took, and the compose screen says so rather than letting somebody discover it after
 * writing six hundred words.
 */
public enum CommunicationChannel {

	EMAIL("Email"),
	WHATSAPP("WhatsApp");

	private final String label;

	CommunicationChannel(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}

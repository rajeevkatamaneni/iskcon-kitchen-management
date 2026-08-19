package org.iskcon.kms.communication;

/**
 * Where a communication has got to (E8-S2). Two states, because there are two: written, and gone.
 *
 * <p>No SENDING state on purpose. Queueing four hundred messages happens inside one transaction and
 * the messages themselves are delivered by background jobs that already record their own progress
 * per recipient, so a third state here would be a status nothing could reliably leave.
 */
public enum CommunicationStatus {
	DRAFT, SENT
}

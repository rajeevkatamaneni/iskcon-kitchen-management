package org.iskcon.kms.assistant.action;

import com.fasterxml.jackson.databind.JsonNode;
import org.iskcon.kms.auth.AuthenticatedUser;

/**
 * One kind of change the assistant can draft (E10-S4, decision D-a): how to carry it out when the
 * person presses Confirm.
 *
 * <p>The mechanism around it — storing the draft with its hash, the 24-hour expiry, refusing a changed
 * or already-handled draft, only the conversation's owner, the audit entry — lives once, in
 * {@link PendingActionService}. A handler is only the part that differs by kind, so a later kind
 * (a meal plan, a volunteer shift, sending an order) is one class implementing this, plus its propose
 * tool and the migration that allows its {@code kind}.
 *
 * <p>Drafting is deliberately not on this interface. What a propose tool is given differs for every
 * kind (a vendor's name and some lines; a date, a meal and some dishes), so each handler offers its
 * own drafting method to its own tool and hands the result to
 * {@link PendingActionService#propose}. What every kind shares is the other half: a stored request,
 * run as the confirming person.
 */
public interface PendingActionHandler {

	/** The {@code kind} column's value, e.g. {@code PURCHASE_ORDER}. */
	String kind();

	/**
	 * The permission the thing itself needs on the screen that does it, e.g.
	 * {@code MANAGE_PURCHASE_ORDERS}. Checked against the confirming person's own authorities before
	 * {@link #execute} runs, on top of {@code CONFIRM_ASSISTANT_ACTION}: confirming a draft never lets
	 * anybody do what they could not do by hand.
	 */
	String permission();

	/**
	 * Carries the draft out through the existing service, as {@code actor}, inside the transaction
	 * that marks the draft confirmed. Throws the service's own refusal (with its own KMS code) if the
	 * request is no longer acceptable, and then nothing is saved and the draft stays PROPOSED.
	 *
	 * @param request exactly the request stored when the draft was made, already checked against its
	 *     hash
	 */
	AssistantProposal.Result execute(AuthenticatedUser actor, JsonNode request);
}

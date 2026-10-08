package org.iskcon.kms.assistant.action;

import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirm and Reject on an assistant's draft card (E10-S4, contract
 * {@code docs/work/E10-S4-CONTRACT.md}). Both answer with the card in its new state.
 *
 * <p>Both need {@code CONFIRM_ASSISTANT_ACTION}, held by every role that has the assistant at all.
 * Confirm then needs the thing's own permission as well (for an order, {@code MANAGE_PURCHASE_ORDERS}),
 * checked by {@link PendingActionService} once it knows which kind of draft this is: confirming never
 * lets anybody do what they could not do on the screen. Only the person whose conversation it is may
 * press either; anyone else is answered 404.
 *
 * <p>Called by the person, with their own sign-in, never by the assistant: the agent token is only
 * accepted on {@code /mcp}, so there is no way for the model to press Confirm for them.
 *
 * <p>Refusals: KMS-400194 (more than 24 hours old), KMS-400195 (changed since it was made),
 * KMS-400196 (already rejected, or a Reject of a draft already decided). A second Confirm of a
 * confirmed draft is not a refusal: it runs nothing and answers with the same card and result.
 */
@RestController
@RequestMapping("/api/v1/assistant/actions")
public class AssistantActionController {

	private final PendingActionService actions;

	public AssistantActionController(PendingActionService actions) {
		this.actions = actions;
	}

	@PostMapping("/{actionId}/confirm")
	@PreAuthorize("hasAuthority('CONFIRM_ASSISTANT_ACTION')")
	public AssistantProposal confirm(@PathVariable UUID actionId, @AuthenticationPrincipal AuthenticatedUser actor) {
		return actions.confirm(actor, actionId);
	}

	@PostMapping("/{actionId}/reject")
	@PreAuthorize("hasAuthority('CONFIRM_ASSISTANT_ACTION')")
	public AssistantProposal reject(@PathVariable UUID actionId, @AuthenticationPrincipal AuthenticatedUser actor) {
		return actions.reject(actor, actionId);
	}
}

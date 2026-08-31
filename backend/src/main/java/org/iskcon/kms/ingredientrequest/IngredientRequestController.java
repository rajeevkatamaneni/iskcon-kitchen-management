package org.iskcon.kms.ingredientrequest;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asking the store for ingredients, answering, and recording what went out (E10-S5 to S7).
 *
 * <p>Three permissions, and each one is a different job rather than a different rank.
 * {@code REQUEST_INGREDIENTS} is held by everybody who works in a kitchen, because anybody may need
 * something from the store and a request nobody can raise is a shelf people help themselves from.
 * {@code APPROVE_INGREDIENT_REQUESTS} and {@code ISSUE_INGREDIENTS} are the storekeeper's, and there
 * is no Storekeeper role: a temple that has one appoints them Kitchen Manager, which is what that
 * role is for (design D4).
 *
 * <p><strong>Reading is open to anybody who can raise one.</strong> There is no owner filter on the
 * list and none on the detail, drafts included. That is deliberate and it is stated in the design:
 * the alternative is two people separately drafting a request for the same feast. What a person may
 * <em>do</em> to a request they did not write is a separate question, answered in the service.
 *
 * <p>Withdrawing sits on {@code REQUEST_INGREDIENTS} rather than on the approver's permission,
 * because taking your own request back is something the person who raised it must be able to do.
 * The service then decides whether this particular person may: its author, or somebody who could
 * have answered it.
 */
@RestController
@RequestMapping("/api/v1/ingredient-requests")
public class IngredientRequestController {

	private final IngredientRequestService requestService;
	private final IngredientIssueService issueService;

	public IngredientRequestController(
			IngredientRequestService requestService, IngredientIssueService issueService) {
		this.requestService = requestService;
		this.issueService = issueService;
	}

	/** Every request, newest first, optionally narrowed to one status for the list's filter. */
	@GetMapping
	@PreAuthorize("hasAuthority('REQUEST_INGREDIENTS')")
	public List<IngredientRequestSummary> list(
			@RequestParam(name = "status", required = false) IngredientRequestStatus status) {
		return requestService.list(status);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('REQUEST_INGREDIENTS')")
	public IngredientRequestView get(@PathVariable UUID id) {
		return requestService.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('REQUEST_INGREDIENTS')")
	public ResponseEntity<Map<String, Object>> create(
			@Valid @RequestBody CreateIngredientRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		UUID id = requestService.create(actor, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('REQUEST_INGREDIENTS')")
	public ResponseEntity<Void> update(
			@PathVariable UUID id,
			@Valid @RequestBody UpdateIngredientRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		requestService.update(actor, id, request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('REQUEST_INGREDIENTS')")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		requestService.delete(actor, id);
		return ResponseEntity.noContent().build();
	}

	/** Sends it for review. Refused if it asks for nothing, or does not say what is being cooked. */
	@PostMapping("/{id}/submit")
	@PreAuthorize("hasAuthority('REQUEST_INGREDIENTS')")
	public ResponseEntity<Void> submit(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		requestService.submit(actor, id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize("hasAuthority('APPROVE_INGREDIENT_REQUESTS')")
	public ResponseEntity<Void> approve(
			@PathVariable UUID id,
			@RequestBody(required = false) @Valid DecisionNote decision,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		requestService.approve(actor, id, decision == null ? null : decision.note());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/deny")
	@PreAuthorize("hasAuthority('APPROVE_INGREDIENT_REQUESTS')")
	public ResponseEntity<Void> deny(
			@PathVariable UUID id,
			@RequestBody(required = false) @Valid DecisionNote decision,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		requestService.deny(actor, id, decision == null ? null : decision.note());
		return ResponseEntity.noContent().build();
	}

	/** Back to a draft while nobody has answered it. Its author's, or an approver's, to do. */
	@PostMapping("/{id}/withdraw")
	@PreAuthorize("hasAuthority('REQUEST_INGREDIENTS')")
	public ResponseEntity<Void> withdraw(
			@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		requestService.withdraw(actor, id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Records what went over the counter, and the only call in this controller that moves stock. An
	 * empty body issues every line at the quantity that was approved.
	 */
	@PostMapping("/{id}/issue")
	@PreAuthorize("hasAuthority('ISSUE_INGREDIENTS')")
	public ResponseEntity<Void> issue(
			@PathVariable UUID id,
			@RequestBody(required = false) @Valid RecordIssueRequest issue,
			@AuthenticationPrincipal AuthenticatedUser actor) {
		issueService.issue(actor, id,
				issue == null ? new RecordIssueRequest(null, null, null) : issue);
		return ResponseEntity.noContent().build();
	}
}

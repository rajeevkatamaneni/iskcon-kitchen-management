package org.iskcon.kms.assistant.api;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.assistant.api.AssistantViews.ConversationSummary;
import org.iskcon.kms.assistant.api.AssistantViews.ConversationView;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Kitchen Assistant's chat API (E10-T10, plan §1 row D): start a conversation, list yours, read
 * one, and send a message, whose answer comes back as a {@code text/event-stream}.
 *
 * <p>Everything here is behind {@code USE_KITCHEN_ASSISTANT}, which Temple Admin and Kitchen Manager
 * hold (T12). Holding it lets you use the assistant; it does not let you read anybody else's
 * conversations, including as a Temple Admin. A conversation is the private working of the person
 * who had it, and an admin who wants to know how the assistant is used has the audit log, which
 * records every run without its words.
 *
 * <p>Confirming and rejecting drafts ({@code CONFIRM_ASSISTANT_ACTION}) are S4's and not here yet.
 */
@RestController
@RequestMapping("/api/v1/assistant/conversations")
public class AssistantController {

	/** A message longer than this is not a question; the runner would take it, the history would not. */
	static final int MAX_MESSAGE_LENGTH = 4000;

	private final AssistantConversationStore store;
	private final AssistantChatService chat;

	public AssistantController(AssistantConversationStore store, AssistantChatService chat) {
		this.store = store;
		this.chat = chat;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('USE_KITCHEN_ASSISTANT')")
	public ConversationSummary create(@AuthenticationPrincipal AuthenticatedUser actor) {
		return store.create(actor);
	}

	/** Your own conversations, the most recently used first. */
	@GetMapping
	@PreAuthorize("hasAuthority('USE_KITCHEN_ASSISTANT')")
	public List<ConversationSummary> list(@AuthenticationPrincipal AuthenticatedUser actor) {
		return store.list(actor);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('USE_KITCHEN_ASSISTANT')")
	public ConversationView get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser actor) {
		return store.get(actor, id);
	}

	/**
	 * Sends one message. Refusals (no permission, not your conversation, a daily limit reached, an
	 * empty message) are ordinary JSON errors; once the answer has started, it and any failure arrive
	 * as events. See {@link AssistantChatService}.
	 *
	 * <p>The caller should send {@code Accept: text/event-stream, application/json}. A refusal is
	 * written as JSON, and Spring will not write JSON to a request that accepts only an event stream:
	 * it answers 406 with no body, and the person would lose the code that says what happened.
	 *
	 * <p>The events: {@code text {text}}, {@code tool_call {tool, summary}}, {@code language
	 * {language, languageName}} (the person asked to switch language during this run, E10-LANG; sent
	 * just before the last event), and last {@code done {messageId, runId}} or {@code error {code,
	 * message, action, runId}}. A conversation's current language is also on its summary and detail.
	 */
	@PostMapping("/{id}/messages")
	@PreAuthorize("hasAuthority('USE_KITCHEN_ASSISTANT')")
	public void send(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest request,
			@AuthenticationPrincipal AuthenticatedUser actor, HttpServletResponse response) throws IOException {
		chat.send(actor, id, request.text().strip(), response);
	}

	public record SendMessageRequest(
			@NotBlank(message = "Type a message.")
			@Size(max = MAX_MESSAGE_LENGTH, message = "Keep a message under " + MAX_MESSAGE_LENGTH + " characters.")
			String text) {
	}
}

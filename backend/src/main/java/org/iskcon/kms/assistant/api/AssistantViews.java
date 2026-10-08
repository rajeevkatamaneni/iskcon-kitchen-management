package org.iskcon.kms.assistant.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the assistant's endpoints return. Records only; nothing here reads the database. */
public final class AssistantViews {

	private AssistantViews() {
	}

	/**
	 * One row of "my conversations". {@code title} is null until the first message gives it one.
	 * {@code language} is the code the assistant replies in (E10-LANG): the person's preference when
	 * the conversation was started, until they ask the assistant to switch.
	 */
	public record ConversationSummary(UUID id, String title, String language, Instant createdAt, Instant updatedAt) {
	}

	/** One conversation with everything said in it, oldest first. */
	public record ConversationView(
			UUID id, String title, String language, Instant createdAt, Instant updatedAt, List<MessageView> messages) {
	}

	/** {@code role} is {@code USER} or {@code ASSISTANT}. A question whose run failed has no reply after it. */
	public record MessageView(UUID id, String role, String content, Instant createdAt) {
	}
}

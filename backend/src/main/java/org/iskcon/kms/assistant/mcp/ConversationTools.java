package org.iskcon.kms.assistant.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.translation.Languages;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tools that change the state of the conversation itself rather than anything in the kitchen
 * (E10-LANG). Today there is one, {@code set_conversation_language}.
 *
 * <h2>The rule it serves</h2>
 *
 * <p>Rajeev, 2026-10-06: "the agent starts the conversation in english. If the user's account has
 * his language preference set to something other than english, then the agent has to start in that
 * language. Once a language is set, the agent has to fully communicate in that language only UNTIL
 * the user asks to switch to a different language." The conversation's language is a column
 * (V162), set from the person's preference when the conversation is started, and the api puts it at
 * the head of every prompt. This tool is the only way it changes once the conversation exists: the
 * model calls it when, and only when, the person asks for another language. Writing in another
 * language is not asking (Rajeev took the literal reading on 2026-10-07), and the system prompt and
 * this tool's description both say so.
 *
 * <h2>Which conversation, and whose</h2>
 *
 * <p>The conversation is the one named in the agent token's {@code conv} claim, never an argument:
 * the model cannot point this at another conversation however it is prompted. The update also names
 * the token's person ({@code user_id = ?}) and runs under the token's temple (RLS), so a token for
 * one person cannot change a conversation that belongs to somebody else, even if one were minted
 * naming it. No row matched means refused, in words.
 *
 * <h2>Why {@code agent:read} is enough</h2>
 *
 * <p>The endpoint checks {@code agent:read} for every tool. {@code agent:propose} exists for drafts of
 * kitchen data that a person must confirm (S4). This changes nothing in the kitchen, only how the
 * assistant talks to the person who asked, at their own request, so it needs no draft and no
 * permission beyond the one that lets the person use the assistant in the first place, which the
 * chat api checked before it minted the token.
 */
@Component
public class ConversationTools {

	static final String NOT_FOUND_MESSAGE =
			"This conversation could not be found for this person, so its language was not changed. "
					+ "Carry on in the current language.";

	static final String UNKNOWN_LANGUAGE_MESSAGE =
			"\"%s\" is not a language this assistant can switch to. Use one of these codes: %s. "
					+ "Carry on in the current language and tell the person which languages are available.";

	private final JdbcTemplate jdbc;

	public ConversationTools(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** What the model is handed back: the language now in force, by code and by name. */
	@Transactional
	public Map<String, Object> setConversationLanguage(String language) {
		if (!Languages.isSupported(language)) {
			throw new McpServerConfiguration.ToolRefusal(
					UNKNOWN_LANGUAGE_MESSAGE.formatted(language == null ? "" : language, String.join(", ", Languages.ALL)));
		}
		AgentPrincipal agent = currentAgent();
		int changed = jdbc.update("""
				UPDATE assistant_conversations SET language = ?
				WHERE id = ? AND user_id = ?
				""", language, agent.conversationId(), agent.userId());
		if (changed == 0) {
			throw new McpServerConfiguration.ToolRefusal(NOT_FOUND_MESSAGE);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("language", language);
		result.put("language_name", Languages.englishName(language));
		result.put("instruction", "From now on reply only in " + Languages.englishName(language)
				+ ", including the rest of this answer.");
		return result;
	}

	/**
	 * The agent the token named. Anything else here is a defect between the filter and this class:
	 * {@code /mcp} accepts only agent tokens. It is refused rather than guessed at.
	 */
	private static AgentPrincipal currentAgent() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AgentPrincipal agent) {
			return agent;
		}
		throw new McpServerConfiguration.ToolRefusal(NOT_FOUND_MESSAGE);
	}
}

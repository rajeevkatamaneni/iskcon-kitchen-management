package org.iskcon.kms.assistant.names;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.translation.GlossaryService;
import org.iskcon.kms.translation.Languages;
import org.iskcon.kms.translation.StockNameFillService;
import org.iskcon.kms.translation.Translatable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Item names in the conversation's language, for every assistant tool that hands the model items
 * (E10-NAMES-B).
 *
 * <h2>Why</h2>
 *
 * <p>Rajeev asked for a Telugu conversation and got English item names, because the tools only ever
 * carried a Kannada name and the prompt told the model to fall back to English rather than translate.
 * His decision (2026-10-08): item names appear in the conversation's language, and they are stable.
 * Stable is the half that rules out letting the model translate freely: measured in E10-T8b, it spelt
 * 12 of 28 items more than one way across five repeats of the same question. So the name comes from
 * the temple's glossary for that language, written once by a person or by the one-off Claude fill
 * ({@link StockNameFillService#ensureNames}), and every tool reads the same stored word.
 *
 * <h2>Which language</h2>
 *
 * <p>The conversation's, read from {@code assistant_conversations.language} for the conversation in
 * the agent token's {@code conv} claim. Never an argument: the model cannot ask for another
 * language's names, and the language a person sees names in is the one the conversation is in.
 * The read also names the token's person and runs under the token's temple (RLS), the same rule
 * {@code set_conversation_language} uses. A conversation that cannot be found, or a caller that is
 * not an agent at all, is treated as English: no local names, which is the safe answer, never another
 * conversation's language.
 *
 * <h2>While names are missing</h2>
 *
 * <p>A temple's first conversation in a language starts the fill, which takes about a minute (70 s
 * measured for 119 Telugu names). Until it lands, and for an item added since, or one the fill could
 * not name, the item is marked {@code name_pending: true} with no {@code name_local}. The prompt tells
 * the model what to do then: give its own translation followed by the English in brackets, so the
 * cook can see which words are the temple's stored names and which are a stand-in. Finding such an
 * item also asks for the fill again, without waiting; the service decides whether a fill is actually
 * needed (one already running, or one that failed in the last ten minutes, is not repeated).
 */
@Component
public class ConversationNames {

	private static final Logger log = LoggerFactory.getLogger(ConversationNames.class);

	private final JdbcTemplate jdbc;
	private final GlossaryService glossary;
	private final StockNameFillService fill;

	public ConversationNames(JdbcTemplate jdbc, GlossaryService glossary, StockNameFillService fill) {
		this.jdbc = jdbc;
		this.glossary = glossary;
		this.fill = fill;
	}

	/** Names for the conversation the current agent token speaks for; English when there is none. */
	public Names forCurrentAgent() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AgentPrincipal agent) {
			return forAgent(agent);
		}
		return english();
	}

	/** Names for {@code agent}'s conversation, in its language now. */
	public Names forAgent(AgentPrincipal agent) {
		List<String> found = jdbc.queryForList(
				"SELECT language FROM assistant_conversations WHERE id = ? AND user_id = ?",
				String.class, agent.conversationId(), agent.userId());
		String language = found.isEmpty() ? Languages.ENGLISH : found.get(0);
		if (Languages.ENGLISH.equals(language)) {
			return english();
		}
		return new Names(language, agent.tenantId(), glossary.lookup(language));
	}

	/**
	 * Asks for the temple's stock names in {@code language}, without waiting. Called when a
	 * conversation starts in, or switches to, a language other than English, and by
	 * {@link Names#fillIfMissing}. Never fails the caller: a conversation or a tool answer is worth
	 * more than the names, which the next call asks for again.
	 */
	public void ensure(UUID tenant, String language) {
		if (language == null || Languages.ENGLISH.equals(language)) {
			return;
		}
		try {
			fill.ensureNames(tenant, language);
		} catch (RuntimeException e) {
			log.warn("could not start the {} name fill: {}", language, e.toString());
		}
	}

	private Names english() {
		return new Names(Languages.ENGLISH, null, Map.of());
	}

	/**
	 * The names for one tool call. Not thread-safe and not to be kept: one per call, so a name stored
	 * by the fill a moment ago is in the next call's answer.
	 */
	public final class Names {

		private final String language;
		private final UUID tenant;
		private final Map<String, String> stored;
		private boolean missing;

		private Names(String language, UUID tenant, Map<String, String> stored) {
			this.language = language;
			this.tenant = tenant;
			this.stored = stored;
		}

		public String language() {
			return language;
		}

		/** English needs no local names: the English name is the name. */
		public boolean english() {
			return Languages.ENGLISH.equals(language);
		}

		/**
		 * The temple's stored name for a filed item name in the conversation's language, or null:
		 * always null in English, and null while the item has no name yet (which is remembered, for
		 * {@link #fillIfMissing}).
		 */
		public String local(String filedName) {
			if (english()) {
				return null;
			}
			String name = Translatable.ingredientName(filedName).override(stored);
			if (name == null) {
				missing = true;
			}
			return name;
		}

		/**
		 * True when the item ought to have a local name and has none yet; null otherwise, so that
		 * {@code name_pending} is left out of English answers and of items that are named.
		 */
		public Boolean pending(String filedName) {
			return english() || local(filedName) != null ? null : Boolean.TRUE;
		}

		/** Puts {@code name_local}, or {@code name_pending: true}, on a tool's row; nothing in English. */
		public void putInto(Map<String, Object> row, String filedName) {
			if (english()) {
				return;
			}
			String name = local(filedName);
			if (name != null) {
				row.put("name_local", name);
			} else {
				row.put("name_pending", true);
			}
		}

		/** If any item asked about had no name, asks for the fill without waiting. */
		public void fillIfMissing() {
			if (missing && tenant != null) {
				ensure(tenant, language);
			}
		}
	}
}

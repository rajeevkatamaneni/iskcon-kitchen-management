package org.iskcon.kms.notification;

import java.sql.PreparedStatement;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * At every application start, records each WhatsApp template's current wording and, the first time a
 * wording appears, when a running app first saw it (T-177, migration V130).
 *
 * <p><strong>Why.</strong> The platform operator's catalogue of templates shows "Wording first seen by
 * the app" and "Wording last changed". Nothing stored held a date per template, and git history is not
 * available at runtime, so this starts keeping one. The fingerprint is the same
 * {@link NotificationTemplate#whatsappFingerprint(String)} T-169a stores per temple, so "the wording
 * changed" means here exactly what "changed since last sent" means on a temple's settings screen.
 *
 * <p><strong>Many processes start at once, and the write is built for it.</strong> A deploy starts the
 * api and the worker together, and several instances of each. Every one of them runs this, with the
 * same twenty wordings. So the write is a single {@code INSERT … ON CONFLICT (template_name,
 * fingerprint) DO NOTHING}, and never a read of what is there followed by an insert of what is
 * missing. With a read first, two processes can both read "absent" before either inserts, and one of
 * them then fails on the primary key. Here there is no gap between deciding and writing: PostgreSQL's
 * primary key decides, inside the one statement. A process whose insert meets another's uncommitted
 * row waits for it, and once that commits it inserts nothing and still succeeds.
 *
 * <p><strong>Which time is kept.</strong> The row keeps the time of the first insert to reach the
 * table, and nothing later can move it. A start that arrives later, even one second later, adds
 * nothing. Two starts that race inside the same few milliseconds keep whichever insert reached the
 * table first. Keeping the other one's time would mean editing a first-seen time, which is exactly
 * what the table forbids (V130 makes it append-only). The time is the database's {@code now()}, so
 * every process writes on one clock.
 *
 * <p><strong>The rows go in name order.</strong> Two processes inserting the same rows in different
 * orders can each wait on a row the other holds, and PostgreSQL then aborts one as a deadlock. The
 * enum's order is the same in every process of one release, but two releases side by side during a
 * rolling deploy need not agree. The name order does.
 *
 * <p><strong>A failure is logged, and the application starts anyway.</strong> Unlike
 * {@code LibraryLoadRunner}, whose load is the only reason its deployment exists, this records a
 * date for an operator's screen. Nothing sends, bills or protects anything based on it. Refusing to
 * start the api because this one insert failed would turn a missing date into an outage for every
 * temple. The cost of carrying on is stated on the screen's side: a wording nobody recorded shows as
 * "Not recorded", and the next start that succeeds records it.
 */
@Component
public class TemplateWordingRecorder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TemplateWordingRecorder.class);

	/**
	 * The language every template is registered with Meta in. The same value Save, Reload and the
	 * Meta comparison use, taken from the comparison rather than typed a fourth time, because a
	 * fingerprint in another language would never match the one a temple stores.
	 */
	public static final String LANGUAGE = WhatsAppTemplateComparison.TEMPLATE_LANGUAGE;

	static final String RECORD = """
			INSERT INTO whatsapp_template_wording_seen (template_name, fingerprint)
			SELECT seen.name, seen.fingerprint
			FROM unnest(?::text[], ?::text[]) AS seen(name, fingerprint)
			ORDER BY seen.name
			ON CONFLICT (template_name, fingerprint) DO NOTHING
			""";

	private final JdbcTemplate jdbc;

	public TemplateWordingRecorder(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		record(currentWording());
	}

	/** Every template's current fingerprint, by Meta name, in name order. */
	public static Map<String, String> currentWording() {
		Map<String, String> wording = new TreeMap<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			wording.put(template.whatsappTemplateName(), template.whatsappFingerprint(LANGUAGE));
		}
		return wording;
	}

	/**
	 * Records these wordings where they are new, in one statement.
	 *
	 * @param wording fingerprint by template name; taken as given so a test can present a changed one
	 * @return true if the statement ran, false if it failed and was logged
	 */
	boolean record(Map<String, String> wording) {
		Map<String, String> sorted = new TreeMap<>(wording);
		try {
			int added = jdbc.update(con -> insertIfAbsent(con, sorted));
			log.info("WhatsApp template wording recorded: {} of {} wordings were new", added, sorted.size());
			return true;
		} catch (RuntimeException e) {
			// Deliberately every runtime failure, not only a database one: whatever went wrong, the
			// application must still start. See the class comment.
			log.warn("Could not record WhatsApp template wording at start-up; the catalogue's dates "
					+ "will show as not recorded until a later start succeeds", e);
			return false;
		}
	}

	private static PreparedStatement insertIfAbsent(java.sql.Connection con, Map<String, String> sorted)
			throws java.sql.SQLException {
		PreparedStatement ps = con.prepareStatement(RECORD);
		ps.setArray(1, con.createArrayOf("text", sorted.keySet().toArray()));
		ps.setArray(2, con.createArrayOf("text", sorted.values().toArray()));
		return ps;
	}
}

package org.iskcon.kms.ops;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.notification.TemplateWordingRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The platform operator's catalogue of WhatsApp templates (T-177): for each template, what Meta is
 * given and what in the app sends it, with when a running app first saw its wording.
 *
 * <p>Rajeev, 2026-09-13: <em>"We have no way of seeing the Text in each of these templates. I feel
 * like it is black box no one can see into."</em> This is step 1 of two, and holds no temple data.
 * Meta's status per temple is step 2 (T-178).
 *
 * <p><strong>No temple data, and how that is kept true.</strong> Everything but the dates comes from
 * {@link NotificationTemplate} itself, which is source code. The dates come from one statement,
 * {@link #READ}, against {@code whatsapp_template_wording_seen} (V130), a platform table with no
 * {@code tenant_id}. This class holds nothing but a {@link JdbcTemplate}, and the IT pins both: the
 * fields by reflection, and every statement the read issues by asking PostgreSQL which relations its
 * plan touches.
 *
 * <p><strong>The dates, per template name.</strong>
 * <ul>
 *   <li>{@code wordingFirstSeenAt}: the earliest first-seen time of any wording recorded for the name.
 *   <li>{@code wordingLastChangedAt}: the first-seen time of the <em>current</em> wording, when more
 *       than one wording has been recorded for the name. Null when only one has, which the screen
 *       says as "Not changed since tracking began".
 *   <li>{@code trackingSince}: the earliest first-seen time in the table.
 * </ul>
 *
 * <p><strong>One case the definitions above do not cover, and what is returned for it.</strong> If
 * the wording running now was never recorded (this process's start-up write failed; see
 * {@link TemplateWordingRecorder}), both dates are null, so the screen says "Not recorded" twice. The
 * definitions taken literally would give an earliest date and a null change, and the screen would then
 * say the wording had not changed since tracking began, about a wording nobody recorded. Saying less
 * is the truthful choice. The next start that succeeds records it and the dates return.
 */
@Service
public class WhatsAppTemplateCatalogue {

	/** The only statement this class issues. A platform table; no temple table is named. */
	static final String READ = """
			SELECT template_name, fingerprint, first_seen_at
			FROM whatsapp_template_wording_seen
			""";

	private final JdbcTemplate jdbc;

	public WhatsAppTemplateCatalogue(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The whole catalogue, one entry per template in the enum's order. */
	@Transactional(readOnly = true)
	public Catalogue read() {
		List<Seen> seen = jdbc.query(READ, (rs, n) -> new Seen(
				rs.getString("template_name"),
				rs.getString("fingerprint"),
				rs.getObject("first_seen_at", OffsetDateTime.class).toInstant()));
		return build(seen, TemplateWordingRecorder.currentWording());
	}

	/**
	 * The catalogue from the recorded wordings and the wording running now. Separate from the query so
	 * the date rules are one piece of code with no database in it.
	 */
	static Catalogue build(List<Seen> seen, Map<String, String> currentWording) {
		Map<String, List<Seen>> byName = new LinkedHashMap<>();
		Instant trackingSince = null;
		for (Seen row : seen) {
			byName.computeIfAbsent(row.name(), k -> new ArrayList<>()).add(row);
			if (trackingSince == null || row.firstSeenAt().isBefore(trackingSince)) {
				trackingSince = row.firstSeenAt();
			}
		}

		List<Entry> entries = new ArrayList<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			String name = template.whatsappTemplateName();
			List<Seen> rows = byName.getOrDefault(name, List.of());
			String current = currentWording.get(name);
			Seen currentRow = rows.stream()
					.filter(r -> Objects.equals(r.fingerprint(), current))
					.findFirst()
					.orElse(null);

			Instant firstSeen = null;
			Instant lastChanged = null;
			if (currentRow != null) {
				firstSeen = rows.stream().map(Seen::firstSeenAt).min(Instant::compareTo).orElseThrow();
				lastChanged = rows.size() > 1 ? currentRow.firstSeenAt() : null;
			}

			entries.add(new Entry(
					name,
					template.whatsappCategory(),
					TemplateWordingRecorder.LANGUAGE,
					template.whatsappBodyText(),
					template.whatsappExampleValues(),
					template.usedBy(),
					firstSeen,
					lastChanged));
		}
		return new Catalogue(trackingSince, List.copyOf(entries));
	}

	/** What the screen reads. Matches {@code WhatsAppTemplateCatalogue} in {@code frontend/lib/api.ts}. */
	public record Catalogue(Instant trackingSince, List<Entry> templates) {
	}

	/** One template. Matches {@code WhatsAppTemplateCatalogueEntry} in {@code frontend/lib/api.ts}. */
	public record Entry(
			String name,
			String category,
			String language,
			String body,
			List<String> exampleValues,
			List<String> usedBy,
			Instant wordingFirstSeenAt,
			Instant wordingLastChangedAt) {
	}

	/** One recorded wording: a row of {@code whatsapp_template_wording_seen}. */
	record Seen(String name, String fingerprint, Instant firstSeenAt) {
	}
}

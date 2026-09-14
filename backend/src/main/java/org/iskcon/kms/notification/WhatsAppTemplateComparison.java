package org.iskcon.kms.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TenantContext;
import org.iskcon.kms.tenancy.TenantSecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks Meta what wording it holds for each of our templates and sets it beside ours, changing
 * nothing anywhere (T-173).
 *
 * <p><strong>Why it exists.</strong> Reload (T-169a) edits a template whose body Meta holds differently
 * from ours, and Meta allows one edit a day per template. Its comparison assumes Meta hands back the
 * body exactly as it was registered. If Meta normalises the text instead (whitespace, quotes,
 * placeholders), the first Reload on a temple that sent its templates before V129 would reword every
 * template that did not need it and spend each one's edit for the day. Nobody had confirmed which. The
 * condition set on Reload was that this be settled by a read, never an edit, before Reload is pressed on
 * staging. This is that read.
 *
 * <p><strong>Why a class of its own, and why these three collaborators only.</strong> It holds a
 * {@link JdbcTemplate} for one SELECT, the {@link TenantSecretStore} for the token, and
 * {@link MetaWhatsAppClient} for {@link MetaWhatsAppClient#findTemplate}. It deliberately does not hold
 * {@link TenantWhatsAppSettingsService}: every path in that service that talks to Meta about templates
 * ends in a registration POST and a write to {@code tenant_settings}, and a class that cannot reach
 * {@code submitTemplates}, {@code bringUpToDate} or {@code reloadTemplates} cannot call them by mistake.
 * {@code WhatsAppTemplateComparisonIT} pins the collaborators by reflection.
 *
 * <p><strong>The lookup is Reload's own lookup, one template at a time, not one list call.</strong> A
 * single {@code GET /{WABA_ID}/message_templates} without a name would be one request instead of twenty,
 * but it would be a different request from the one Reload makes, parsed by new code, and paged by Meta.
 * The question this answers is "what does Reload read when it compares", and the only honest answer comes
 * from the same call, the same URL, the same name-and-language match and the same BODY parsing. Twenty
 * sequential GETs is a few seconds for a request made once by hand.
 *
 * <p><strong>What {@code findTemplate}'s parsing does to the body, checked so this read and Reload's
 * comparison cannot agree for the wrong reason.</strong> It takes the BODY component's {@code text} with
 * Jackson's {@code asText()}: JSON escapes are decoded, which is what Meta holds, and nothing is trimmed,
 * joined or normalised. So a body differing only in outer whitespace reaches here with that whitespace,
 * and {@code metaBody} shows it. Three things it does that could make a template read as not held, never
 * as matching: it keeps only an entry whose name and language are exactly ours; it takes the last BODY
 * component if Meta ever listed two; and it reads only the first page of Meta's answer.
 *
 * <p><strong>The two comparisons.</strong> {@code bodyMatchesExactly} is {@link String#equals}, character
 * for character. {@code bodyMatchesAfterTrim} is exactly the test {@code bringUpToDate} makes before it
 * decides to edit, {@code ours.strip().equals(theirs.strip())}, so a template with
 * {@code bodyMatchesAfterTrim: false} is precisely one Reload would try to reword.
 */
@Component
public class WhatsAppTemplateComparison {

	private static final Logger log = LoggerFactory.getLogger(WhatsAppTemplateComparison.class);

	/**
	 * The language every template is registered and looked up in. The same literal as
	 * {@code TenantWhatsAppSettingsService.TEMPLATE_LANGUAGE}, which is private to that service; repeated
	 * rather than shared because reaching into that service is what this class exists not to do. It is
	 * returned on the report, so a reader sees which language was asked about.
	 */
	static final String TEMPLATE_LANGUAGE = "en";

	static final String NOT_REACHED = "Meta could not be reached for this message.";

	static final String META_ANSWERED_WITH_AN_ERROR =
			"Meta answered with an error when asked for this message. The reason is in the log.";

	private final JdbcTemplate jdbc;
	private final TenantSecretStore secrets;
	private final MetaWhatsAppClient meta;

	public WhatsAppTemplateComparison(JdbcTemplate jdbc, TenantSecretStore secrets, MetaWhatsAppClient meta) {
		this.jdbc = jdbc;
		this.secrets = secrets;
		this.meta = meta;
	}

	/**
	 * One entry per {@link NotificationTemplate}, in declaration order.
	 *
	 * <p>Read-only in the database's sense, not only in intent: with Spring's JPA transaction manager a
	 * read-only transaction marks the JDBC connection read-only, and PostgreSQL then refuses any INSERT,
	 * UPDATE or DELETE inside it. The transaction does hold one connection while Meta is asked twenty
	 * times; Reload holds one for longer, and this is pressed once, by hand.
	 *
	 * <p>Not connected, or connected with no stored token, is refused exactly as Test and Reload refuse
	 * it, with {@code KMS-400001} and the field named in the log's context.
	 */
	@Transactional(readOnly = true)
	public Report compare() {
		UUID tenantId = TenantContext.get().orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "tenant")));
		Optional<Account> connected = jdbc.query("""
				SELECT whatsapp_phone_number_id, whatsapp_waba_id FROM tenant_settings
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> new Account(rs.getString("whatsapp_phone_number_id"), rs.getString("whatsapp_waba_id")))
				.stream().filter(a -> a.phoneNumberId() != null).findFirst();
		Account account = connected.orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "phoneNumberId")));
		String token = secrets.get(tenantId, TenantSecretStore.Kind.WHATSAPP_ACCESS_TOKEN).orElseThrow(
				() -> new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "accessToken")));

		List<Entry> entries = new ArrayList<>();
		for (NotificationTemplate template : NotificationTemplate.values()) {
			entries.add(entryFor(template, account.wabaId(), token));
		}

		// Counts only, never a body and never the token: the report itself is the place to read wording.
		log.info("Compared {} WhatsApp templates with Meta for temple {}: {} identical, {} identical only after "
						+ "trimming, {} worded differently, {} not held, {} not answered, {} with a different header",
				entries.size(), tenantId,
				entries.stream().filter(e -> Boolean.TRUE.equals(e.bodyMatchesExactly())).count(),
				entries.stream().filter(e -> Boolean.FALSE.equals(e.bodyMatchesExactly())
						&& Boolean.TRUE.equals(e.bodyMatchesAfterTrim())).count(),
				entries.stream().filter(e -> Boolean.FALSE.equals(e.bodyMatchesAfterTrim())).count(),
				entries.stream().filter(e -> Boolean.FALSE.equals(e.held())).count(),
				entries.stream().filter(e -> e.held() == null).count(),
				entries.stream().filter(e -> Boolean.FALSE.equals(e.headerMatches())).count());
		return new Report(account.wabaId(), TEMPLATE_LANGUAGE, entries);
	}

	/**
	 * One template. Meta failing for this one is recorded on its entry, and the rest still answer.
	 *
	 * <p>What is logged on a failure is the exception's message: for Meta unreachable a fixed sentence
	 * from {@link MetaWhatsAppClient}, for an error answer Meta's own readable sentence about our request.
	 * Neither carries the token, which travels only in the request's Authorization header.
	 */
	private Entry entryFor(NotificationTemplate template, String wabaId, String token) {
		String name = template.whatsappTemplateName();
		String ourCategory = template.whatsappCategory();
		Optional<MetaWhatsAppClient.HeldTemplate> found;
		try {
			found = meta.findTemplate(wabaId, token, name, TEMPLATE_LANGUAGE);
		} catch (MetaWhatsAppClient.WhatsAppCredentialsRejected e) {
			log.warn("Could not reach Meta to compare template {}: {}", name, e.getMessage());
			return Entry.notAnswered(name, ourCategory, NOT_REACHED);
		} catch (RuntimeException e) {
			log.warn("Meta would not describe template {} for the comparison: {}", name, e.getMessage());
			return Entry.notAnswered(name, ourCategory, META_ANSWERED_WITH_AN_ERROR);
		}
		String ourHeader = template.whatsappHeaderFormat();
		if (found.isEmpty()) {
			return new Entry(name, ourCategory, null, null, false, null, null, null, null, null, null,
					ourHeader, null, null);
		}
		MetaWhatsAppClient.HeldTemplate held = found.get();
		String ours = template.whatsappBodyText();
		String theirs = held.bodyText();
		boolean exact = ours.equals(theirs);
		boolean afterTrim = theirs != null && ours.strip().equals(theirs.strip());
		// T-200: the same test bringUpToDate makes on the header before it decides a template is current.
		boolean headerMatches = java.util.Objects.equals(ourHeader, held.headerFormat());
		return new Entry(name, ourCategory, held.category(), held.status(), true, exact, afterTrim,
				exact ? null : theirs, exact ? null : ours, null, held.rejectedReason(),
				ourHeader, held.headerFormat(), headerMatches);
	}

	private record Account(String phoneNumberId, String wabaId) {
	}

	/**
	 * The answer to {@code GET /api/v1/settings/whatsapp/templates/meta-comparison}.
	 *
	 * @param wabaId    the business account Meta was asked about, as saved on Settings; not a secret, the
	 *                  settings GET returns it too
	 * @param language  the language each template was looked up in
	 * @param templates one entry per template in this release
	 */
	public record Report(String wabaId, String language, List<Entry> templates) {
	}

	/**
	 * One template, ours beside Meta's.
	 *
	 * @param name                 the template name
	 * @param ourCategory          the category this release registers it under
	 * @param metaCategory         the category Meta holds it under; null when not held or not answered
	 * @param metaStatus           Meta's review status, e.g. {@code APPROVED}; null when not held, not
	 *                             answered, or Meta listed none
	 * @param held                 whether Meta lists a template of exactly this name and language; null when
	 *                             Meta did not answer, because then nobody knows
	 * @param bodyMatchesExactly   Meta's body equals ours character for character; null unless held
	 * @param bodyMatchesAfterTrim equal once outer whitespace is stripped from both, the test Reload makes
	 *                             before it edits; null unless held
	 * @param metaBody             Meta's body exactly as it came, only where the exact match fails; null
	 *                             there as well if Meta listed no BODY component
	 * @param ourBody              our body, only where the exact match fails
	 * @param lookupProblem        a plain sentence when Meta was not reached or answered with an error for
	 *                             this template; null when it answered
	 * @param metaRejectedReason   Meta's {@code rejected_reason} exactly as sent, e.g. {@code INVALID_FORMAT};
	 *                             null unless held and Meta sent one (T-178, which stores it to tell a
	 *                             formatting refusal from any other)
	 * @param ourHeaderFormat      the header format this release registers, e.g. {@code DOCUMENT}; null for a
	 *                             template with no header (T-200)
	 * @param metaHeaderFormat     the header format Meta lists, upper-cased; null unless held and Meta lists a
	 *                             header
	 * @param headerMatches        the two formats are the same, both null included; null unless held. With
	 *                             {@code bodyMatchesAfterTrim}, this is the whole of Reload's test: a template
	 *                             where either is false is one Reload would edit
	 */
	public record Entry(String name, String ourCategory, String metaCategory, String metaStatus, Boolean held,
			Boolean bodyMatchesExactly, Boolean bodyMatchesAfterTrim, String metaBody, String ourBody,
			String lookupProblem, String metaRejectedReason, String ourHeaderFormat, String metaHeaderFormat,
			Boolean headerMatches) {

		static Entry notAnswered(String name, String ourCategory, String problem) {
			return new Entry(name, ourCategory, null, null, null, null, null, null, null, problem, null,
					null, null, null);
		}
	}
}

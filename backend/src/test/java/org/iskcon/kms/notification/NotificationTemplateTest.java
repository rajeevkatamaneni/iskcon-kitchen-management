package org.iskcon.kms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seam between our messages and Meta's.
 *
 * <p>Everything here guards the same failure, and it is a quiet one. A WhatsApp template is
 * positional and our parameters are named, so a wrong order does not throw, does not fail to send,
 * and does not look wrong in any log — it puts a devotee's name where the temple should be and
 * sends it. Nobody finds out except the person who received it.
 */
class NotificationTemplateTest {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\d+)}}");

	@Test
	@DisplayName("every template's placeholders are numbered from one, in order, with none repeated")
	void placeholdersAreSequential() {
		for (NotificationTemplate template : NotificationTemplate.values()) {
			List<Integer> found = placeholdersIn(template.whatsappBodyText());
			List<Integer> expected = java.util.stream.IntStream
					.rangeClosed(1, template.parameterOrder().size()).boxed().toList();

			// Meta rejects a template whose placeholders skip a number or start above one, and this
			// is also the check that catches a parameter the body never actually reads.
			assertThat(found)
					.as("%s reads %s", template, template.parameterOrder())
					.isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("the WhatsApp body is the same sentence as the SMS one, with the values taken out")
	void bodyIsDerivedFromTheOneSentence() {
		// Rendering with real values and with placeholders must differ only in the values, or the two
		// channels have quietly become two different messages.
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("title", "Kitchen");
		values.put("date", "12 August");
		values.put("time", "6:00 am");
		values.put("location", "Main kitchen");

		// T-180: this checked the older shift reminder until that template was removed, and now checks the reminder
		// volunteers are actually sent, in its T-180 wording.
		String sms = NotificationTemplate.VOLUNTEER_SHIFT_REMINDER.render(values).body();
		String whatsapp = NotificationTemplate.VOLUNTEER_SHIFT_REMINDER.whatsappBodyText();

		assertThat(sms).isEqualTo(
				"Reminder: your Kitchen shift is on 12 August, 6:00 am at Main kitchen. "
						+ "If you can't make it, please release your spot as soon as possible so others can sign up.");
		assertThat(whatsapp).isEqualTo(
				"Reminder: your {{1}} shift is on {{2}}, {{3}} at {{4}}. "
						+ "If you can't make it, please release your spot as soon as possible so others can sign up.");
	}

	@Test
	@DisplayName("a parameter order that does not match the sentence is caught, not sent")
	void orderMustMatchTheSentence() {
		// The regression this whole file exists for: the volunteer reminder's body says title, date,
		// time, location. Any other order would still render, and would still send.
		assertThat(NotificationTemplate.VOLUNTEER_SHIFT_REMINDER.parameterOrder())
				.containsExactly("title", "date", "time", "location");
		// T-180's wording names the applied amount before the item, so the order moved with it.
		assertThat(NotificationTemplate.WISHLIST_GIFT_SPLIT.parameterOrder())
				.containsExactly("donor", "amount", "applied", "item", "remainder", "temple");
		assertThat(NotificationTemplate.DONATION_THANK_YOU.parameterOrder())
				.containsExactly("donor", "temple", "date");
	}

	@Test
	@DisplayName("every template offers Meta an example for each placeholder, or it will not be approved")
	void examplesMatchPlaceholderCount() {
		for (NotificationTemplate template : NotificationTemplate.values()) {
			assertThat(template.whatsappExampleValues())
					.as("%s", template)
					.hasSameSizeAs(template.parameterOrder());
			assertThat(template.whatsappTemplateName()).matches("[a-z0-9_]+");
		}
	}

	@Test
	@DisplayName("every template says in words what in the app sends it, for the operator's catalogue")
	void everyTemplateSaysWhatSendsIt() {
		// T-177. The switch in usedBy() has no default, so a new constant cannot compile without an
		// entry. This catches the other way it could go wrong: an entry that says nothing.
		for (NotificationTemplate template : NotificationTemplate.values()) {
			assertThat(template.usedBy()).as("%s", template).isNotEmpty();
			assertThat(template.usedBy()).as("%s", template)
					.allSatisfy(use -> assertThat(use).isNotBlank().matches("[A-Z].*"));
		}
	}

	@Test
	@DisplayName("what Meta is told a message is agrees with what a devotee may decline")
	void metaCategoryAgreesWithOurs() {
		// Two vocabularies for the same fact, and they must not drift. A message somebody may turn
		// off is marketing by any honest reading — Meta prices it higher and reviews it harder, and
		// declaring it UTILITY to dodge that would be a lie told to a company that audits. A message
		// they cannot turn off is the consequence of something they already did, which is precisely
		// what UTILITY means.
		for (NotificationTemplate template : NotificationTemplate.values()) {
			// A null category means the template carries whatever a temple admin wrote, which is
			// never operational and therefore always marketing.
			String expected = template.category() == null || template.category().isOptional()
					? "MARKETING" : "UTILITY";
			assertThat(template.whatsappCategory())
					.as("%s is %s to us, so it must be %s to Meta",
							template, template.category(), expected)
					.isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("the leave withdrawal messages read as reviewed, in both channels' forms (T-184)")
	void leaveWithdrawalWording() {
		// Pinned word for word, because both go to the main session for approval before release and a
		// later edit must be a visible change to this test rather than a quiet one.
		Map<String, Object> person = Map.of(
				"name", "Radha Devi", "temple", "ISKCON South Bengaluru", "dates", "12 to 14 August 2026");
		assertThat(NotificationTemplate.LEAVE_WITHDRAWN.render(person).subject())
				.isEqualTo("Your leave at ISKCON South Bengaluru has been withdrawn");
		assertThat(NotificationTemplate.LEAVE_WITHDRAWN.render(person).body()).isEqualTo(
				"Hare Krishna Radha Devi, your request to withdraw your leave at ISKCON South Bengaluru "
						+ "for 12 to 14 August 2026 has been successfully completed.");
		assertThat(NotificationTemplate.LEAVE_WITHDRAWN.whatsappBodyText()).isEqualTo(
				"Hare Krishna {{1}}, your request to withdraw your leave at {{2}} for {{3}} has been successfully completed.");

		Map<String, Object> notice = new LinkedHashMap<>(person);
		notice.put("state", "still waiting for an answer");
		assertThat(NotificationTemplate.LEAVE_WITHDRAWN_NOTICE.render(notice).subject())
				.isEqualTo("Radha Devi has withdrawn their leave at ISKCON South Bengaluru");
		assertThat(NotificationTemplate.LEAVE_WITHDRAWN_NOTICE.render(notice).body()).isEqualTo(
				"Hare Krishna. Radha Devi has withdrawn their leave at ISKCON South Bengaluru for 12 to 14 August 2026 "
						+ "before it began. That leave was still waiting for an answer, and their usual schedule stands for those dates.");
		assertThat(NotificationTemplate.LEAVE_WITHDRAWN_NOTICE.whatsappBodyText()).isEqualTo(
				"Hare Krishna. {{1}} has withdrawn their leave at {{2}} for {{3}} before it began. "
						+ "That leave was {{4}}, and their usual schedule stands for those dates.");
		assertThat(NotificationTemplate.LEAVE_WITHDRAWN_NOTICE.whatsappExampleValues())
				.containsExactly("Radha Devi", "ISKCON South Bengaluru", "12 to 14 August 2026", "approved");
	}

	@Test
	@DisplayName("only the withdrawal confirmation opts out of the fallback cascade (T-184)")
	void onlyTheWithdrawalConfirmationSkipsTheCascade() {
		// Off by default: every template sent before T-184 still cascades to SMS and email, and a new one
		// has to say otherwise deliberately.
		for (NotificationTemplate template : NotificationTemplate.values()) {
			assertThat(template.fallsBack()).as("%s", template)
					.isEqualTo(template != NotificationTemplate.LEAVE_WITHDRAWN);
		}
	}

	@Test
	@DisplayName("only the purchase order has a header, a DOCUMENT filled from its sheet, and its body is still the approved one (T-200)")
	void onlyThePurchaseOrderHasADocumentHeader() {
		for (NotificationTemplate template : NotificationTemplate.values()) {
			if (template == NotificationTemplate.PO_DELIVERY) {
				assertThat(template.whatsappHeaderFormat()).isEqualTo("DOCUMENT");
				assertThat(template.whatsappHeaderParameter()).isEqualTo("documentId");
				// The header fills no body placeholder, so the body's numbering is untouched by it.
				assertThat(template.parameterOrder()).doesNotContain("documentId");
			} else {
				assertThat(template.whatsappHeaderFormat()).as("%s", template).isNull();
				assertThat(template.whatsappHeaderParameter()).as("%s", template).isNull();
			}
		}
		// Word for word as approved: the header is the only change.
		assertThat(NotificationTemplate.PO_DELIVERY.whatsappBodyText()).isEqualTo(
				"Purchase order {{1}} for {{2}} is ready: {{3}}. It was raised on {{4}}, and the items are needed by "
						+ "{{5}} at the latest.");
	}

	@Test
	@DisplayName("the header changes the purchase order's fingerprint and no other template's (T-200)")
	void theHeaderChangesOnlyThePurchaseOrdersFingerprint() throws Exception {
		for (NotificationTemplate template : NotificationTemplate.values()) {
			// The four-part fingerprint every template had before T-200, computed here independently.
			String before = "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest
					.getInstance("SHA-256").digest(String.join("", template.whatsappTemplateName(),
							template.whatsappCategory(), "en", template.whatsappBodyText())
							.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			if (template == NotificationTemplate.PO_DELIVERY) {
				assertThat(template.whatsappFingerprint("en")).as("po_delivery gained a header").isNotEqualTo(before);
			} else {
				assertThat(template.whatsappFingerprint("en")).as("%s is unchanged", template).isEqualTo(before);
			}
		}
	}

	private static List<Integer> placeholdersIn(String body) {
		List<Integer> numbers = new java.util.ArrayList<>();
		Matcher matcher = PLACEHOLDER.matcher(body);
		while (matcher.find()) {
			numbers.add(Integer.parseInt(matcher.group(1)));
		}
		return numbers;
	}
}

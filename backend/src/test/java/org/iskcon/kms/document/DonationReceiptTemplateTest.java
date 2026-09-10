package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.iskcon.kms.document.DonationReceiptTemplate.EightyGStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The receipt's own layer, tested without a database (T-110).
 *
 * <p>What is worth proving here is the part that is a claim about tax rather than a piece of
 * layout. The sheet says one of three things and there is no fourth branch and no default: a donor
 * who files a document headed "80G" and then finds the temple was never registered finds out at
 * assessment, and that is the failure this test exists to make impossible to reintroduce.
 *
 * <p>The service decides which of the three applies; {@code DonationReceiptIT} proves it decides
 * correctly against a real temple row. This proves each decision prints what it promises.
 */
class DonationReceiptTemplateTest {

	@Test
	@DisplayName("an eligible gift names the section, and the sheet calls itself an 80G receipt")
	void eligibleNamesTheSection() {
		String html = DonationReceiptTemplate.render(model(EightyGStatus.ELIGIBLE));

		assertThat(html).contains("Donation receipt · 80G");
		assertThat(html).contains("Eligible for deduction under Section 80G of the Income-tax Act, 1961.");
		// The captured section is a row of its own only where the gift actually qualifies. Printing
		// it beside a sentence saying no deduction is supported would be two answers on one sheet.
		assertThat(html).contains("<th>Section</th><td>80G</td>");
	}

	@Test
	@DisplayName("goods at an 80G temple are acknowledged and told plainly they are not deductible")
	void goodsAreNotAn80gReceipt() {
		String html = DonationReceiptTemplate.render(model(EightyGStatus.IN_KIND_NOT_ELIGIBLE));

		// Not headed 80G. The heading is the first thing read and the last thing remembered.
		assertThat(html).doesNotContain("Donation receipt · 80G");
		assertThat(html).contains("acknowledgement of goods received, not an 80G receipt");
		assertThat(html).contains("does not qualify for deduction under it");
		// The eligible sentence must be nowhere on this sheet.
		assertThat(html).doesNotContain("Eligible for deduction under Section 80G");
	}

	@Test
	@DisplayName("a temple with no 80G registration issues a receipt that says it supports no deduction")
	void unapprovedTempleClaimsNothing() {
		String html = DonationReceiptTemplate.render(model(EightyGStatus.TEMPLE_NOT_APPROVED));

		assertThat(html).doesNotContain("Donation receipt · 80G");
		assertThat(html).contains("This receipt does not support a tax deduction.");
		assertThat(html).contains("no Section 80G registration recorded");
		assertThat(html).doesNotContain("Eligible for deduction under Section 80G");
	}

	@Test
	@DisplayName("an anonymous gift is receipted to nobody, in words rather than as a blank line")
	void anonymousSaysSo() {
		String html = DonationReceiptTemplate.render(new DonationReceiptTemplate.ReceiptModel(
				"Sri Sri Radha Govinda Temple", "12 Temple Road, Bengaluru", "R-2026-0007",
				"14 Aug 2026", null, null, null, "₹2,500", "12 Aug 2026", "Cash", null,
				"General kitchen", EightyGStatus.ELIGIBLE, "80G", "14 Aug 2026"));

		assertThat(html).contains("A donor who gave anonymously");
		// No empty labelled rows for the facts an anonymous gift does not have.
		assertThat(html).doesNotContain(">PAN<");
		assertThat(html).doesNotContain(">Payment reference<");
	}

	@Test
	@DisplayName("everything a person typed is escaped — a donor name is not markup")
	void escapesWhatPeopleTyped() {
		String html = DonationReceiptTemplate.render(new DonationReceiptTemplate.ReceiptModel(
				"Temple & Trust", null, "R-2026-0001", "14 Aug 2026",
				"<script>alert(1)</script>", "12 \"Old\" Road", "ABCDE1234F", "₹14,000",
				"12 Aug 2026", "Card", "pay_abc", "A wet grinder", EightyGStatus.ELIGIBLE, "80G",
				"14 Aug 2026"));

		assertThat(html).doesNotContain("<script>");
		assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
		assertThat(html).contains("Temple &amp; Trust");
		assertThat(html).contains("12 &quot;Old&quot; Road");
	}

	@Test
	@DisplayName("the figure on the sheet is the payment, and it is the only figure on it")
	void oneFigureAndOneOnly() {
		// The split gift from T-081, as the receipt must show it: ₹14,000 given, of which ₹4,000 was
		// all the grinder still needed. The receipt reports the payment. Neither of the other two
		// figures belongs anywhere near it — a donor's accountant reading two would have to guess
		// which is deductible.
		String html = DonationReceiptTemplate.render(new DonationReceiptTemplate.ReceiptModel(
				"Sri Sri Radha Govinda Temple", null, "R-2026-0011", "14 Aug 2026", "Gopal Das",
				"12 Temple Road", "ABCDE1234F", "₹14,000", "12 Aug 2026", "Card", "pay_split",
				"A wet grinder", EightyGStatus.ELIGIBLE, "80G", "14 Aug 2026"));

		assertThat(html).contains("₹14,000");
		assertThat(html).doesNotContain("₹4,000");
		assertThat(html).doesNotContain("₹10,000");
	}

	private static DonationReceiptTemplate.ReceiptModel model(EightyGStatus status) {
		return new DonationReceiptTemplate.ReceiptModel(
				"Sri Sri Radha Govinda Temple", "12 Temple Road, Bengaluru", "R-2026-0042",
				"14 Aug 2026", "Gopal Das", "12 Temple Road, Bengaluru", "ABCDE1234F", "₹5,000",
				"12 Aug 2026", "Bank transfer", "pay_9f2", "General kitchen", status, "80G",
				"14 Aug 2026");
	}
}

package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The PO sheet template (E5-S4): A4, optional price column, and HTML-escaping of vendor text. */
class PurchaseOrderSheetTemplateTest {

	private static final PurchaseOrderSheetTemplate.VendorBlock VENDOR =
			new PurchaseOrderSheetTemplate.VendorBlock(
					"Govind Wholesale", "12 Market Rd, Bengaluru", "29ABCDE1234F1Z5", "+919812345678");

	@Test
	@DisplayName("renders an A4 sheet with the temple, PO number, vendor block and GSTIN")
	void rendersCoreBlocks() {
		String html = PurchaseOrderSheetTemplate.render(model(true));
		assertThat(html).contains("@page{size:A4");
		assertThat(html).contains("Sri Sri Radha Govinda Temple");
		assertThat(html).contains("PO-2026-0042");
		assertThat(html).contains("Govind Wholesale");
		assertThat(html).contains("GSTIN: 29ABCDE1234F1Z5");
		assertThat(html).contains("Authorised signature");
	}

	@Test
	@DisplayName("the price column renders only when a line carries a price")
	void priceColumnOnlyWhenPriced() {
		String priced = PurchaseOrderSheetTemplate.render(model(true));
		assertThat(priced).contains(">Price<");
		assertThat(priced).contains("₹");

		String unpriced = PurchaseOrderSheetTemplate.render(model(false));
		assertThat(unpriced).doesNotContain(">Price<");
		assertThat(unpriced).doesNotContain("₹");
	}

	@Test
	@DisplayName("vendor and item text is HTML-escaped")
	void escapesText() {
		var evil = new PurchaseOrderSheetTemplate.VendorBlock("A & B <script>", null, null, null);
		var m = new PurchaseOrderSheetTemplate.SheetModel(
				"Temple", "Purchase Order", "PO-1", "1 Aug 2026", null, null, evil, null, null,
				List.of(new PurchaseOrderSheetTemplate.Line("Rice", "10 KG", null)),
				false, null, "1 Aug 2026",
				PurchaseOrderSheetTemplate.Labels.english().asList());
		String html = PurchaseOrderSheetTemplate.render(m);
		assertThat(html).contains("A &amp; B &lt;script&gt;");
		assertThat(html).doesNotContain("<script>");
	}

	@Test
	@DisplayName("rupees on the sheet are written the screen's way: ₹1,500, ₹1,00,000, ₹71.20 (T-268)")
	void rupeesAreWrittenTheScreensWay() {
		// Whole rupees take no paise: the vendor page says "₹1,500 / bag", so the sheet does too.
		assertThat(SheetRupees.format(new BigDecimal("1500"))).isEqualTo("₹1,500");
		assertThat(SheetRupees.format(new BigDecimal("1500.0000"))).isEqualTo("₹1,500");
		assertThat(SheetRupees.format(new BigDecimal("60.00"))).isEqualTo("₹60");
		// Indian grouping: lakhs and crores, not thousands.
		assertThat(SheetRupees.format(new BigDecimal("100000"))).isEqualTo("₹1,00,000");
		assertThat(SheetRupees.format(new BigDecimal("12345678.5"))).isEqualTo("₹1,23,45,678.50");
		assertThat(SheetRupees.format(new BigDecimal("1234567"))).isEqualTo("₹12,34,567");
		assertThat(SheetRupees.format(new BigDecimal("99999"))).isEqualTo("₹99,999");
		// T-279: the crore boundary and a negative lakh, now that the grouping is IndianNumbers.
		assertThat(SheetRupees.format(new BigDecimal("10000000"))).isEqualTo("₹1,00,00,000");
		assertThat(SheetRupees.format(new BigDecimal("9999999.99"))).isEqualTo("₹99,99,999.99");
		assertThat(SheetRupees.format(new BigDecimal("-100000"))).isEqualTo("-₹1,00,000");
		assertThat(SheetRupees.format(new BigDecimal("999"))).isEqualTo("₹999");
		assertThat(SheetRupees.format(new BigDecimal("-1500"))).isEqualTo("-₹1,500");
		// Paise, when there are any, are shown as both digits.
		assertThat(SheetRupees.format(new BigDecimal("71.2"))).isEqualTo("₹71.20");
		assertThat(SheetRupees.format(new BigDecimal("6598.7904"))).isEqualTo("₹6,598.79");
		// A per-gm rate held to four places rounds to the nearest paisa and stays readable.
		assertThat(SheetRupees.format(new BigDecimal("0.0712"))).isEqualTo("₹0.07");
		assertThat(SheetRupees.format(new BigDecimal("0.4"))).isEqualTo("₹0.40");
		// Half a paisa rounds up, as the old formatter did; a sum that rounds to whole rupees
		// takes no paise.
		assertThat(SheetRupees.format(new BigDecimal("0.005"))).isEqualTo("₹0.01");
		assertThat(SheetRupees.format(new BigDecimal("1499.995"))).isEqualTo("₹1,500");
		assertThat(SheetRupees.format(BigDecimal.ZERO)).isEqualTo("₹0");
	}

	private PurchaseOrderSheetTemplate.SheetModel model(boolean priced) {
		List<PurchaseOrderSheetTemplate.Line> lines = List.of(
				new PurchaseOrderSheetTemplate.Line("Rice", "30 KG", priced ? "₹45 / Kg" : null),
				new PurchaseOrderSheetTemplate.Line("Toor Dal", "10 KG", priced ? "₹120 / Kg" : null));
		return new PurchaseOrderSheetTemplate.SheetModel(
				"Sri Sri Radha Govinda Temple", "Purchase Order", "PO-2026-0042", "1 Aug 2026",
				"2 Aug 2026", "5 Aug 2026", VENDOR, "Main kitchen store", "Deliver before noon", lines,
				priced, priced ? "₹2,550" : null, "1 Aug 2026",
				PurchaseOrderSheetTemplate.Labels.english().asList());
	}
}

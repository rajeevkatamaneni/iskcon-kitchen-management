package org.iskcon.kms.document;

import static org.assertj.core.api.Assertions.assertThat;

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
				"Temple", "Purchase Order", "PO-1", "1 Aug 2026", null, evil, null, null,
				List.of(new PurchaseOrderSheetTemplate.Line("Rice", "10 KG", null)),
				false, null, "1 Aug 2026",
				PurchaseOrderSheetTemplate.Labels.english().asList());
		String html = PurchaseOrderSheetTemplate.render(m);
		assertThat(html).contains("A &amp; B &lt;script&gt;");
		assertThat(html).doesNotContain("<script>");
	}

	private PurchaseOrderSheetTemplate.SheetModel model(boolean priced) {
		List<PurchaseOrderSheetTemplate.Line> lines = List.of(
				new PurchaseOrderSheetTemplate.Line("Rice", "30 KG", priced ? "₹45.00" : null),
				new PurchaseOrderSheetTemplate.Line("Toor Dal", "10 KG", priced ? "₹120.00" : null));
		return new PurchaseOrderSheetTemplate.SheetModel(
				"Sri Sri Radha Govinda Temple", "Purchase Order", "PO-2026-0042", "1 Aug 2026",
				"5 Aug 2026", VENDOR, "Main kitchen store", "Deliver before noon", lines,
				priced, priced ? "₹2700.00" : null, "1 Aug 2026",
				PurchaseOrderSheetTemplate.Labels.english().asList());
	}
}

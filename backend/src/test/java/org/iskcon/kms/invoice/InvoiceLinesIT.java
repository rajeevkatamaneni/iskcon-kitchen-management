package org.iskcon.kms.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.iskcon.kms.AbstractIntegrationTest;
import org.iskcon.kms.testsupport.StubTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * An itemised invoice (stage 6, T-271; R-INV-2..5, R-INV-7): its lines, the deliveries it bills, its
 * totals and the required copy of the bill, and the rules that hold them together — one standing bill
 * per delivery (released by a void, and held by the database against two saves at once), a delivery's
 * lines billed exactly, a pack that agrees with its quantity, and a total that adds up.
 *
 * <p>Every request runs as the application's own unprivileged role ({@code kms_app}, NOBYPASSRLS, see
 * {@link AbstractIntegrationTest}), so "another temple's delivery is refused" is row-level security
 * doing it, not a WHERE clause.
 *
 * <p>The price step each saved line runs is {@code InvoicePriceStepIT}'s.
 */
@AutoConfigureMockMvc
class InvoiceLinesIT extends AbstractIntegrationTest {

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private StubTokenVerifier stubVerifier;

	@Autowired
	private ObjectMapper json;

	private JdbcTemplate admin;
	private UUID tenant;
	private UUID otherTenant;
	private UUID staff;
	private UUID rice;
	private UUID ghee;
	private UUID bag;
	private UUID vendor;
	private UUID otherVendor;

	@BeforeEach
	void setUp() {
		admin = new JdbcTemplate(adminDataSource());
		tenant = temple("invoice-lines-a");
		otherTenant = temple("invoice-lines-b");
		staff = user(tenant, "uid-inv-staff", "Govinda Das", "KITCHEN_STAFF", "+919876500401");
		user(tenant, "uid-inv-manager", "Madhava Das", "KITCHEN_MANAGER", "+919876500402");
		user(tenant, "uid-inv-admin", "Radha Devi", "TEMPLE_ADMIN", "+919876500403");
		user(tenant, "uid-inv-vol", "Vol", "VOLUNTEER", "+919876500404");
		rice = ingredient(tenant, "Rice", "KG");
		ghee = ingredient(tenant, "Ghee", "L");
		bag = admin.queryForObject("""
				INSERT INTO ingredient_pack_sizes (tenant_id, ingredient_id, name, quantity, unit)
				VALUES (?, ?, 'Bag', 25, 'KG') RETURNING id
				""", UUID.class, tenant, rice);
		vendor = vendor(tenant, "Govind Wholesale");
		otherVendor = vendor(tenant, "Sri Traders");
		signIn("uid-inv-staff");
	}

	@AfterEach
	void tearDown() {
		for (UUID id : List.of(tenant, otherTenant)) {
			admin.execute("SELECT delete_tenant_cascade('" + id + "')");
		}
	}

	// ---- Saving a bill for a delivery ------------------------------------------------------

	@Test
	@DisplayName("a bill for a delivery saves its lines, its link, its totals and claims the copy of the bill")
	void aDeliveryBillSavesEverything() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0044");
		UUID billFile = bill(tenant);

		// 4 × Bag (25 Kg) of rice for ₹6,000 and 10 L of ghee for ₹5,200: sub total ₹11,200, with
		// ₹560 GST, ₹100 transport and ₹60 off, is a grand total of ₹11,800.
		MvcResult result = mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "100", "KG", bag, "4", "6000"),
						deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, "5200")),
						"560", "100", "60", "11800", billFile, "Transport")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.duplicateWarning").value(false))
				.andExpect(jsonPath("$.invoice.amount").value(11800.0))
				.andExpect(jsonPath("$.invoice.direct").value(false))
				.andExpect(jsonPath("$.invoice.purchaseOrderId").value(d.poId().toString()))
				.andExpect(jsonPath("$.invoice.poNumber").value("PO-2026-0044"))
				.andReturn();
		UUID id = UUID.fromString(read(result).at("/invoice/id").asText());

		// The row: amount is the grand total, which is what every sum of invoices already reads.
		Map<String, Object> row = admin.queryForMap("""
				SELECT amount, sub_total, gst_amount, other_charges, other_charges_note, discount, grand_total,
					   po_id, direct, scan_ref
				FROM vendor_invoices WHERE id = ?
				""", id);
		assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("11800");
		assertThat((BigDecimal) row.get("sub_total")).isEqualByComparingTo("11200");
		assertThat((BigDecimal) row.get("grand_total")).isEqualByComparingTo("11800");
		assertThat(row.get("other_charges_note")).isEqualTo("Transport");
		assertThat(row.get("scan_ref")).isNull();
		assertThat(count("SELECT count(*) FROM vendor_invoice_lines WHERE invoice_id = ?", id)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM vendor_invoice_deliveries WHERE invoice_id = ? AND receipt_id = ?"
				+ " AND released_at IS NULL", id, d.receiptId())).isEqualTo(1);
		assertThat(admin.queryForObject("SELECT invoice_id FROM attachments WHERE id = ?", UUID.class, billFile))
				.as("the copy of the bill is claimed by this invoice").isEqualTo(id);

		// The page (R-INV-7): lines with ordered, delivered, billed, amount, rate and rate per pack; the
		// delivery in the words the screen prints; the totals; the bill.
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.vendorName").value("Govind Wholesale"))
				.andExpect(jsonPath("$.creditedAmount").value(0.0))
				.andExpect(jsonPath("$.lines.length()").value(2))
				.andExpect(jsonPath("$.lines[0].itemName").value("Rice"))
				.andExpect(jsonPath("$.lines[0].goodsReceiptLineId").value(d.riceLine().toString()))
				.andExpect(jsonPath("$.lines[0].orderedQty").value(100))
				.andExpect(jsonPath("$.lines[0].deliveredQty").value(100))
				.andExpect(jsonPath("$.lines[0].billedQty").value(100))
				.andExpect(jsonPath("$.lines[0].unit").value("KG"))
				.andExpect(jsonPath("$.lines[0].packSizeId").value(bag.toString()))
				.andExpect(jsonPath("$.lines[0].packLabel").value("Bag (25 Kg)"))
				.andExpect(jsonPath("$.lines[0].packQuantity").value(25))
				.andExpect(jsonPath("$.lines[0].packCount").value(4))
				.andExpect(jsonPath("$.lines[0].amount").value(6000.0))
				.andExpect(jsonPath("$.lines[0].rate").value(60.0))
				.andExpect(jsonPath("$.lines[0].ratePerPack").value(1500.0))
				.andExpect(jsonPath("$.lines[1].itemName").value("Ghee"))
				.andExpect(jsonPath("$.lines[1].rate").value(520.0))
				.andExpect(jsonPath("$.lines[1].ratePerPack").value(nullValue()))
				.andExpect(jsonPath("$.lines[1].packLabel").value(nullValue()))
				.andExpect(jsonPath("$.deliveries.length()").value(1))
				.andExpect(jsonPath("$.deliveries[0].receiptId").value(d.receiptId().toString()))
				.andExpect(jsonPath("$.deliveries[0].purchaseOrderId").value(d.poId().toString()))
				.andExpect(jsonPath("$.deliveries[0].poNumber").value("PO-2026-0044"))
				.andExpect(jsonPath("$.deliveries[0].receivedOn").value(today().toString()))
				.andExpect(jsonPath("$.deliveries[0].receivedByName").value("Govinda Das"))
				.andExpect(jsonPath("$.subTotal").value(11200.0))
				.andExpect(jsonPath("$.gstAmount").value(560.0))
				.andExpect(jsonPath("$.otherCharges").value(100.0))
				.andExpect(jsonPath("$.otherChargesNote").value("Transport"))
				.andExpect(jsonPath("$.discount").value(60.0))
				.andExpect(jsonPath("$.grandTotal").value(11800.0))
				.andExpect(jsonPath("$.bill.id").value(billFile.toString()))
				.andExpect(jsonPath("$.bill.kind").value("INVOICE_BILL"))
				// R-INV-7, recomputed from the lines: items billed ₹11,200 against the delivered goods at
				// the order's prices, 100 Kg × ₹58 + 10 L × ₹500 = ₹10,800. GST and transport are not on
				// the order, so they are not in the comparison.
				.andExpect(jsonPath("$.expectedValue").value(10800.0))
				.andExpect(jsonPath("$.variance").value(400.0));

		// Every field the client's VendorInvoiceDetailView declares, and nothing else.
		JsonNode page = read(mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id))).andReturn());
		assertThat(fieldNames(page)).containsExactlyInAnyOrder("id", "vendorId", "vendorName",
				"purchaseOrderId", "poNumber", "direct", "description", "invoiceNumber", "invoiceDate", "amount",
				"dueDate", "scanRef", "status", "expectedValue", "variance", "overdue", "voidedAt", "voidReason",
				"creditedAmount", "createdAt", "lines", "deliveries", "subTotal", "gstAmount", "otherCharges",
				"otherChargesNote", "discount", "grandTotal", "bill");
		assertThat(fieldNames(page.at("/lines/0"))).containsExactlyInAnyOrder("id", "goodsReceiptLineId",
				"ingredientId", "itemName", "orderedQty", "deliveredQty", "billedQty", "unit", "packSizeId",
				"packLabel", "packQuantity", "packCount", "amount", "rate", "ratePerPack");
		assertThat(fieldNames(page.at("/deliveries/0"))).containsExactlyInAnyOrder("receiptId",
				"purchaseOrderId", "poNumber", "receivedOn", "receivedByName");
	}

	@Test
	@DisplayName("figures that don't add up to the grand total are refused with KMS-400168, and nothing is saved")
	void totalsMustAddUp() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0045");
		UUID billFile = bill(tenant);

		// ₹11,200 + ₹560 + ₹100 − ₹60 is ₹11,800, not ₹11,000.
		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "100", "KG", bag, "4", "6000"),
						deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, "5200")),
						"560", "100", "60", "11000", billFile, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400168"));

		assertNothingSaved(billFile);
	}

	@Test
	@DisplayName("the copy of the bill is required: a field error with the standard required message")
	void theBillIsRequired() throws Exception {
		Map<String, Object> body = body(vendor, List.of(), List.of(directLine(rice, null, "10", "KG", "600")),
				"0", "0", "0", "600", null, null);
		mvc.perform(invoice(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("billAttachmentId"))
				.andExpect(jsonPath("$.fieldErrors[0].message").value("Copy of the bill is required."));

		// And a bill with no items at all is not a bill.
		mvc.perform(invoice(body(vendor, List.of(), List.of(), "0", "0", "0", "600", bill(tenant), null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("lines"));
		assertThat(count("SELECT count(*) FROM vendor_invoices")).isZero();
	}

	// ---- One standing bill per delivery (R-INV-3) -------------------------------------------

	@Test
	@DisplayName("a delivery already on a standing bill is refused with KMS-400169, and a void releases it")
	void aDeliveryIsBilledOnceUntilVoided() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0046");
		UUID first = recordFor(d, "KAL-1", "6000", "5200");

		mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries").param("vendorId", vendor.toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		UUID secondBill = bill(tenant);
		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "100", "KG", null, null, "6000"),
						deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, "5200")),
						"0", "0", "0", "11200", secondBill, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400169"));
		assertThat(admin.queryForObject("SELECT invoice_id FROM attachments WHERE id = ?", UUID.class, secondBill))
				.as("the refused bill's upload stays unclaimed, to be used again").isNull();

		// Struck: the delivery is offered again and can be billed correctly.
		signIn("uid-inv-admin");
		mvc.perform(authed(post("/api/v1/vendor-invoices/{id}/void", first))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Keyed the ghee wrong.\"}"))
				.andExpect(status().isNoContent());
		signIn("uid-inv-staff");
		assertThat(count("SELECT count(*) FROM vendor_invoice_deliveries WHERE invoice_id = ? AND released_at IS NOT NULL",
				first)).as("the struck bill's link is kept, marked released").isEqualTo(1);

		mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries").param("vendorId", vendor.toString())))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].receiptId").value(d.receiptId().toString()));

		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "100", "KG", null, null, "6000"),
						deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, "5000")),
						"0", "0", "0", "11000", secondBill, null)))
				.andExpect(status().isCreated());

		// The struck bill's page still names the delivery it was keyed against.
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", first)))
				.andExpect(jsonPath("$.status").value("VOIDED"))
				.andExpect(jsonPath("$.deliveries[0].receiptId").value(d.receiptId().toString()))
				.andExpect(jsonPath("$.expectedValue").value(nullValue()))
				.andExpect(jsonPath("$.variance").value(nullValue()));
	}

	@Test
	@DisplayName("another vendor's delivery, or another temple's, is refused with KMS-400169")
	void onlyThisVendorsDeliveries() throws Exception {
		Delivery sri = deliveredOrder(otherVendor, "PO-2026-0047");
		mvc.perform(invoice(body(vendor, List.of(sri.receiptId()), List.of(
						deliveredLine(sri.riceLine(), rice, "100", "KG", null, null, "6000"),
						deliveredLine(sri.gheeLine(), ghee, "10", "L", null, null, "5200")),
						"0", "0", "0", "11200", bill(tenant), null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400169"));

		// Another temple's delivery is invisible to this one under row-level security, so it reads as
		// not billable rather than as somebody else's.
		UUID foreignReceipt = foreignDelivery();
		mvc.perform(invoice(body(vendor, List.of(foreignReceipt), List.of(
						deliveredLine(UUID.randomUUID(), rice, "1", "KG", null, null, "60")),
						"0", "0", "0", "60", bill(tenant), null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400169"));
		assertThat(count("SELECT count(*) FROM vendor_invoices")).isZero();
	}

	@Test
	@DisplayName("two bills for one delivery saved at the same moment: the database lets exactly one through")
	void twoConcurrentBillsForOneDelivery() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0048");

		// The first bill, held open on a second connection as the application's own role: its link is
		// written and not yet committed, which is the moment a second save cannot see it.
		DriverManagerDataSource app = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
		try (Connection held = app.getConnection()) {
			held.setAutoCommit(false);
			try (PreparedStatement ps = held.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
				ps.setString(1, tenant.toString());
				ps.executeQuery().close();
			}
			UUID heldInvoice = UUID.randomUUID();
			try (PreparedStatement ps = held.prepareStatement("""
					INSERT INTO vendor_invoices (id, tenant_id, vendor_id, po_id, invoice_number, invoice_date,
						amount, created_by, sub_total, gst_amount, other_charges, discount, grand_total)
					VALUES (?, ?, ?, ?, 'HELD-1', CURRENT_DATE, 11200, ?, 11200, 0, 0, 0, 11200)
					""")) {
				ps.setObject(1, heldInvoice);
				ps.setObject(2, tenant);
				ps.setObject(3, vendor);
				ps.setObject(4, d.poId());
				ps.setObject(5, staff);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = held.prepareStatement(
					"INSERT INTO vendor_invoice_deliveries (tenant_id, invoice_id, receipt_id) VALUES (?, ?, ?)")) {
				ps.setObject(1, tenant);
				ps.setObject(2, heldInvoice);
				ps.setObject(3, d.receiptId());
				ps.executeUpdate();
			}

			// The second bill, through the endpoint. Its early check reads committed links only, sees
			// none, and goes on to write its own — where the unique index makes it wait.
			UUID secondBill = bill(tenant);
			Map<String, Object> body = body(vendor, List.of(d.receiptId()), List.of(
					deliveredLine(d.riceLine(), rice, "100", "KG", null, null, "6000"),
					deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, "5200")),
					"0", "0", "0", "11200", secondBill, null);
			CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(() -> {
				try {
					signIn("uid-inv-staff");
					return mvc.perform(invoice(body)).andReturn();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});

			// Proved waiting, not assumed: a backend blocked on a lock, as pg_stat_activity reports it.
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
			while (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
					+ " AND query LIKE '%INSERT INTO vendor_invoice_deliveries%'") == 0) {
				assertThat(System.nanoTime()).as("the second save never reached the index").isLessThan(deadline);
				assertThat(second.isDone()).as("the second save finished without waiting: %s",
						second.isDone() ? second.join().getResponse().getContentAsString() : "").isFalse();
				Thread.sleep(50);
			}
			held.commit();

			MvcResult result = second.get(20, TimeUnit.SECONDS);
			assertThat(result.getResponse().getStatus()).isEqualTo(409);
			assertThat(read(result).at("/code").asText()).isEqualTo("KMS-400169");
			assertThat(admin.queryForObject("SELECT invoice_id FROM attachments WHERE id = ?", UUID.class, secondBill))
					.as("the refused save rolled back its claim").isNull();
		}
		assertThat(count("SELECT count(*) FROM vendor_invoices")).as("only the first bill exists").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM vendor_invoice_lines")).isZero();
	}

	// ---- A delivery's lines, exactly (R-INV-3) ---------------------------------------------

	@Test
	@DisplayName("a line removed from, added to, or repeated on a delivery's bill is refused with KMS-400170")
	void aDeliveryBillCarriesExactlyItsLines() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0049");
		UUID billFile = bill(tenant);

		// Removed: the ghee is missing.
		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "100", "KG", null, null, "6000")),
						"0", "0", "0", "6000", billFile, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400170"));

		// Added: a transport line typed beside the delivery's two.
		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "100", "KG", null, null, "6000"),
						deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, "5200"),
						directLine(null, "Transport", "1", "PIECES", "100")),
						"0", "0", "0", "11300", billFile, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400170"));

		// Repeated: the rice twice, the ghee not at all.
		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "50", "KG", null, null, "3000"),
						deliveredLine(d.riceLine(), rice, "50", "KG", null, null, "3000")),
						"0", "0", "0", "6000", billFile, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400170"));

		// The delivered line re-labelled as another ingredient.
		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), ghee, "100", "L", null, null, "6000"),
						deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, "5200")),
						"0", "0", "0", "11200", billFile, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400170"));

		assertNothingSaved(billFile);
	}

	@Test
	@DisplayName("billed more than delivered is allowed (the screen warns), and an item not billed stays at 0")
	void billedMoreOrNothingIsAllowed() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0050");
		mvc.perform(invoice(body(vendor, List.of(d.receiptId()), List.of(
						deliveredLine(d.riceLine(), rice, "110", "KG", null, null, "6600"),
						deliveredLine(d.gheeLine(), ghee, "0", "L", null, null, "0")),
						"0", "0", "0", "6600", bill(tenant), null)))
				.andExpect(status().isCreated());
	}

	// ---- A direct bill ---------------------------------------------------------------------

	@Test
	@DisplayName("a direct bill takes an ingredient line and a one-off line, needs no description, and has no difference")
	void aDirectBill() throws Exception {
		UUID billFile = bill(tenant);
		MvcResult result = mvc.perform(invoice(body(vendor, List.of(), List.of(
						directLine(rice, null, "10", "KG", "650"),
						directLine(null, "Plastic stool", "2", "PIECES", "400")),
						"0", "0", "0", "1050", billFile, null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.invoice.direct").value(true))
				.andExpect(jsonPath("$.invoice.purchaseOrderId").value(nullValue()))
				.andExpect(jsonPath("$.invoice.description").value(nullValue()))
				.andReturn();
		UUID id = UUID.fromString(read(result).at("/invoice/id").asText());

		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id)))
				.andExpect(jsonPath("$.lines.length()").value(2))
				.andExpect(jsonPath("$.lines[0].itemName").value("Rice"))
				.andExpect(jsonPath("$.lines[0].goodsReceiptLineId").value(nullValue()))
				.andExpect(jsonPath("$.lines[0].orderedQty").value(nullValue()))
				.andExpect(jsonPath("$.lines[0].deliveredQty").value(nullValue()))
				.andExpect(jsonPath("$.lines[0].rate").value(65.0))
				.andExpect(jsonPath("$.lines[1].itemName").value("Plastic stool"))
				.andExpect(jsonPath("$.lines[1].ingredientId").value(nullValue()))
				.andExpect(jsonPath("$.lines[1].rate").value(200.0))
				.andExpect(jsonPath("$.deliveries.length()").value(0))
				.andExpect(jsonPath("$.subTotal").value(1050.0))
				// No order, so no basis for a difference: null, shown as "—", never ₹0.
				.andExpect(jsonPath("$.expectedValue").value(nullValue()))
				.andExpect(jsonPath("$.variance").value(nullValue()));
	}

	@Test
	@DisplayName("a direct line must be an ingredient or described, never both, and never name a delivered line")
	void directLineShape() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0051");
		UUID billFile = bill(tenant);
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(rice, "Rice, loose", "10", "KG", "600")),
						"0", "0", "0", "600", billFile, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].ingredientId"));
		mvc.perform(invoice(body(vendor, List.of(), List.of(
						deliveredLine(d.riceLine(), rice, "100", "KG", null, null, "6000")),
						"0", "0", "0", "6000", billFile, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("KMS-400170"));
		assertNothingSaved(billFile);
	}

	// ---- Packs (R-INV-4) -------------------------------------------------------------------

	@Test
	@DisplayName("a pack line must agree with its quantity, be the ingredient's own, and come as a pair")
	void packLinesAreChecked() throws Exception {
		UUID billFile = bill(tenant);

		// 4 × Bag (25 Kg) is 100 Kg, not 90.
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(rice, null, "90", "KG", bag, "4", "6000")),
						"0", "0", "0", "6000", billFile, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].billedQty"));

		// The same amount in grams agrees: 100,000 gm is 4 bags.
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(rice, null, "100000", "GM", bag, "4", "6000")),
						"0", "0", "0", "6000", billFile, null)))
				.andExpect(status().isCreated());

		UUID another = bill(tenant);
		// Ghee in rice's bag.
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(ghee, null, "25", "L", bag, "1", "6000")),
						"0", "0", "0", "6000", another, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400162"));
		// A one-off in a bag.
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(null, "Sacks", "25", "KG", bag, "1", "60")),
						"0", "0", "0", "60", another, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400162"));
		// Packs with no pack.
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(rice, null, "100", "KG", null, "4", "6000")),
						"0", "0", "0", "6000", another, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400001"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].packSizeId"));
		// Rice billed in litres.
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(rice, null, "10", "L", "600")),
						"0", "0", "0", "600", another, null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("KMS-400013"));
		assertThat(count("SELECT count(*) FROM vendor_invoices")).as("only the agreeing bill").isEqualTo(1);
	}

	// ---- The billable list (R-INV-3) -------------------------------------------------------

	@Test
	@DisplayName("the billable list: this vendor's unbilled deliveries, by order number, with ordered, delivered and the order's pack")
	void billableDeliveries() throws Exception {
		Delivery older = deliveredOrder(vendor, "PO-2026-0052", "2026-09-10T09:00:00+05:30");
		Delivery newer = deliveredOrder(vendor, "PO-2026-0053", "2026-09-12T09:00:00+05:30");
		Delivery billed = deliveredOrder(vendor, "PO-2026-0054", "2026-09-11T09:00:00+05:30");
		deliveredOrder(otherVendor, "PO-2026-0055"); // another vendor's
		foreignDelivery(); // another temple's
		recordFor(billed, "KAL-9", "6000", "5200");

		MvcResult result = mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries")
						.param("vendorId", vendor.toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				// By order number (T-290), PO-2026-0052 then PO-2026-0053. The detail is checked on the second.
				.andExpect(jsonPath("$[0].receiptId").value(older.receiptId().toString()))
				.andExpect(jsonPath("$[1].receiptId").value(newer.receiptId().toString()))
				.andExpect(jsonPath("$[1].poNumber").value("PO-2026-0053"))
				.andExpect(jsonPath("$[1].receivedOn").value("2026-09-12"))
				.andExpect(jsonPath("$[1].receivedByName").value("Govinda Das"))
				.andExpect(jsonPath("$[1].purchaseOrderId").value(newer.poId().toString()))
				.andExpect(jsonPath("$[1].lines.length()").value(2))
				// In the order's own line order: the rice, then the ghee.
				.andExpect(jsonPath("$[1].lines[0].itemName").value("Rice"))
				.andExpect(jsonPath("$[1].lines[0].goodsReceiptLineId").value(newer.riceLine().toString()))
				.andExpect(jsonPath("$[1].lines[0].orderedQty").value(100))
				.andExpect(jsonPath("$[1].lines[0].deliveredQty").value(100))
				.andExpect(jsonPath("$[1].lines[0].unit").value("KG"))
				.andExpect(jsonPath("$[1].lines[0].packSizeId").value(bag.toString()))
				.andExpect(jsonPath("$[1].lines[0].packLabel").value("Bag (25 Kg)"))
				.andExpect(jsonPath("$[1].lines[0].packQuantity").value(25))
				.andExpect(jsonPath("$[1].lines[1].itemName").value("Ghee"))
				// 12 L ordered, 10 L kept (2 L damaged at the gate): the bill starts from what was kept.
				.andExpect(jsonPath("$[1].lines[1].orderedQty").value(12))
				.andExpect(jsonPath("$[1].lines[1].deliveredQty").value(10))
				.andExpect(jsonPath("$[1].lines[1].packLabel").value(nullValue()))
				.andReturn();
		JsonNode row = read(result).at("/1");
		assertThat(fieldNames(row)).containsExactlyInAnyOrder("receiptId", "purchaseOrderId", "poNumber",
				"receivedOn", "receivedByName", "lines");
		assertThat(fieldNames(row.at("/lines/0"))).containsExactlyInAnyOrder("goodsReceiptLineId",
				"ingredientId", "itemName", "orderedQty", "deliveredQty", "unit", "packSizeId", "packLabel",
				"packQuantity");
	}

	@Test
	@DisplayName("the billable list is in order-number order, and one order's deliveries oldest first, to the minute (T-290)")
	void billableDeliveriesAreSorted() throws Exception {
		// Made out of order on purpose. PO-2026-0062 came first of all but is listed last, because a
		// person matching a bill looks for the order number. PO-2026-0061 came twice on 12 Sept, the
		// later one made first: the form tells two such deliveries apart by this order.
		Delivery late61 = deliveredOrder(vendor, "PO-2026-0061", "2026-09-12T10:00:00+05:30");
		Delivery only60 = deliveredOrder(vendor, "PO-2026-0060", "2026-09-11T09:00:00+05:30");
		Delivery only62 = deliveredOrder(vendor, "PO-2026-0062", "2026-09-10T09:00:00+05:30");
		UUID early61 = anotherDelivery(late61, "2026-09-12T08:00:00+05:30");

		mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries").param("vendorId", vendor.toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[*].poNumber").value(contains("PO-2026-0060", "PO-2026-0061", "PO-2026-0061",
						"PO-2026-0062")))
				.andExpect(jsonPath("$[*].receiptId").value(contains(only60.receiptId().toString(), early61.toString(),
						late61.receiptId().toString(), only62.receiptId().toString())))
				.andExpect(jsonPath("$[1].receivedOn").value("2026-09-12"))
				.andExpect(jsonPath("$[2].receivedOn").value("2026-09-12"));
	}

	// ---- Who may, and another temple -------------------------------------------------------

	@Test
	@DisplayName("Kitchen Staff, Kitchen Manager and Temple Admin hold MANAGE_PURCHASE_ORDERS and may; a volunteer may not")
	void permissions() throws Exception {
		int n = 0;
		for (String uid : List.of("uid-inv-staff", "uid-inv-manager", "uid-inv-admin")) {
			signIn(uid);
			MvcResult result = mvc.perform(invoice(body(vendor, List.of(),
							List.of(directLine(rice, null, "10", "KG", "600")), "0", "0", "0", "600", bill(tenant), null)))
					.andExpect(status().isCreated())
					.andReturn();
			n++;
			UUID id = UUID.fromString(read(result).at("/invoice/id").asText());
			mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id))).andExpect(status().isOk());
			mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries").param("vendorId", vendor.toString())))
					.andExpect(status().isOk());
		}
		assertThat(count("SELECT count(*) FROM vendor_invoices")).isEqualTo(n);

		signIn("uid-inv-vol");
		UUID any = admin.queryForObject("SELECT id FROM vendor_invoices LIMIT 1", UUID.class);
		mvc.perform(invoice(body(vendor, List.of(), List.of(directLine(rice, null, "10", "KG", "600")),
						"0", "0", "0", "600", bill(tenant), null)))
				.andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", any))).andExpect(status().isForbidden());
		mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries").param("vendorId", vendor.toString())))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("another temple's invoice, lines and deliveries are invisible to this one")
	void anotherTempleIsInvisible() throws Exception {
		Delivery d = deliveredOrder(vendor, "PO-2026-0056");
		UUID id = recordFor(d, "KAL-2", "6000", "5200");

		UUID otherVendorOfB = vendor(otherTenant, "Other temple's supplier");
		user(otherTenant, "uid-inv-b", "B Staff", "KITCHEN_STAFF", "+919876500405");
		signIn("uid-inv-b");
		mvc.perform(authed(get("/api/v1/vendor-invoices/{id}", id))).andExpect(status().isNotFound());
		mvc.perform(authed(get("/api/v1/vendor-invoices"))).andExpect(jsonPath("$.length()").value(0));
		// Temple A's vendor id, asked for from temple B: nothing, because nothing of A's is visible.
		mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries").param("vendorId", vendor.toString())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(authed(get("/api/v1/vendor-invoices/billable-deliveries").param("vendorId", otherVendorOfB.toString())))
				.andExpect(jsonPath("$.length()").value(0));
	}

	// ---------------------------------------------------------------------

	/** A sent order and one delivery against it: 4 × Bag (25 Kg) of rice at ₹58/Kg, and 10 of 12 L of ghee at ₹500/L. */
	record Delivery(UUID poId, UUID receiptId, UUID riceLine, UUID gheeLine) {
	}

	private Delivery deliveredOrder(UUID forVendor, String poNumber) {
		return deliveredOrder(forVendor, poNumber, null);
	}

	private Delivery deliveredOrder(UUID forVendor, String poNumber, String receivedAt) {
		UUID poId = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, ?, ?, 'PARTIALLY_RECEIVED', now(), ?) RETURNING id
				""", UUID.class, tenant, poNumber, forVendor, staff);
		UUID riceOrder = admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price,
					pack_size_id, pack_count, line_order)
				VALUES (?, ?, ?, 100, 'KG', 58, ?, 4, 0) RETURNING id
				""", UUID.class, tenant, poId, rice, bag);
		UUID gheeOrder = admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit, expected_price, line_order)
				VALUES (?, ?, ?, 12, 'L', 500, 1) RETURNING id
				""", UUID.class, tenant, poId, ghee);
		UUID receipt = admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by, received_at)
				VALUES (?, ?, ?, ?, COALESCE(?::timestamptz, now())) RETURNING id
				""", UUID.class, tenant, poId, "seed-" + poNumber, staff, receivedAt);
		UUID riceLine = admin.queryForObject("""
				INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, unit)
				VALUES (?, ?, ?, ?, 100, 'KG') RETURNING id
				""", UUID.class, tenant, receipt, riceOrder, rice);
		UUID gheeLine = admin.queryForObject("""
				INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty,
					rejected_qty, reject_reason, unit)
				VALUES (?, ?, ?, ?, 10, 2, 'DAMAGED', 'L') RETURNING id
				""", UUID.class, tenant, receipt, gheeOrder, ghee);
		return new Delivery(poId, receipt, riceLine, gheeLine);
	}

	/** A second delivery against the same order: 20 Kg more of its rice, received at {@code receivedAt}. */
	private UUID anotherDelivery(Delivery d, String receivedAt) {
		UUID riceOrder = admin.queryForObject("SELECT po_line_id FROM goods_receipt_lines WHERE id = ?", UUID.class,
				d.riceLine());
		UUID receipt = admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by, received_at)
				VALUES (?, ?, ?, ?, ?::timestamptz) RETURNING id
				""", UUID.class, tenant, d.poId(), "seed-more-" + UUID.randomUUID(), staff, receivedAt);
		admin.update("""
				INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, unit)
				VALUES (?, ?, ?, ?, 20, 'KG')
				""", tenant, receipt, riceOrder, rice);
		return receipt;
	}

	/** A delivery in the other temple, for the invisibility tests. */
	private UUID foreignDelivery() {
		List<UUID> existing = admin.queryForList("SELECT id FROM users WHERE tenant_id = ?", UUID.class, otherTenant);
		UUID u = existing.isEmpty()
				? user(otherTenant, "uid-inv-foreign", "Foreign", "KITCHEN_STAFF", "+919876500406")
				: existing.get(0);
		UUID v = vendor(otherTenant, "Foreign supplier");
		UUID i = ingredient(otherTenant, "Rice", "KG");
		UUID po = admin.queryForObject("""
				INSERT INTO purchase_orders (tenant_id, po_number, vendor_id, status, sent_at, created_by)
				VALUES (?, 'PO-2026-0900', ?, 'RECEIVED', now(), ?) RETURNING id
				""", UUID.class, otherTenant, v, u);
		UUID pol = admin.queryForObject("""
				INSERT INTO purchase_order_lines (tenant_id, po_id, ingredient_id, quantity, unit)
				VALUES (?, ?, ?, 1, 'KG') RETURNING id
				""", UUID.class, otherTenant, po, i);
		UUID r = admin.queryForObject("""
				INSERT INTO goods_receipts (tenant_id, po_id, idempotency_key, received_by)
				VALUES (?, ?, 'foreign', ?) RETURNING id
				""", UUID.class, otherTenant, po, u);
		admin.update("""
				INSERT INTO goods_receipt_lines (tenant_id, receipt_id, po_line_id, ingredient_id, received_qty, unit)
				VALUES (?, ?, ?, ?, 1, 'KG')
				""", otherTenant, r, pol, i);
		return r;
	}

	/** Records a plain bill for the delivery (no packs, no extras) and returns its id. */
	private UUID recordFor(Delivery d, String number, String riceAmount, String gheeAmount) throws Exception {
		BigDecimal total = new BigDecimal(riceAmount).add(new BigDecimal(gheeAmount));
		Map<String, Object> body = body(vendor, List.of(d.receiptId()), List.of(
				deliveredLine(d.riceLine(), rice, "100", "KG", null, null, riceAmount),
				deliveredLine(d.gheeLine(), ghee, "10", "L", null, null, gheeAmount)),
				"0", "0", "0", total.toPlainString(), bill(tenant), null);
		body.put("invoiceNumber", number);
		MvcResult result = mvc.perform(invoice(body)).andExpect(status().isCreated()).andReturn();
		return UUID.fromString(read(result).at("/invoice/id").asText());
	}

	/** An uploaded copy of a bill, not yet claimed: what POST /bill-uploads leaves behind. */
	private UUID bill(UUID temple) {
		UUID by = admin.queryForList("SELECT id FROM users WHERE tenant_id = ?", UUID.class, temple).get(0);
		return admin.queryForObject("""
				INSERT INTO attachments (tenant_id, kind, storage_key, content_type, size_bytes, original_name, uploaded_by)
				VALUES (?, 'INVOICE_BILL', ?, 'application/pdf', 1024, 'bill.pdf', ?) RETURNING id
				""", UUID.class, temple, "tenants/" + temple + "/attachments/" + UUID.randomUUID(), by);
	}

	private void assertNothingSaved(UUID billFile) {
		assertThat(count("SELECT count(*) FROM vendor_invoices")).as("no invoice").isZero();
		assertThat(count("SELECT count(*) FROM vendor_invoice_lines")).as("no line").isZero();
		assertThat(count("SELECT count(*) FROM vendor_invoice_deliveries")).as("no link").isZero();
		assertThat(count("SELECT count(*) FROM vendor_price_history")).as("no price").isZero();
		assertThat(admin.queryForObject("SELECT invoice_id FROM attachments WHERE id = ?", UUID.class, billFile))
				.as("the upload is left unclaimed").isNull();
	}

	static Map<String, Object> body(UUID vendorId, List<UUID> receiptIds, List<Map<String, Object>> lines,
			String gst, String other, String discount, String grandTotal, UUID billAttachmentId, String otherNote) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("vendorId", vendorId);
		body.put("invoiceNumber", "KAL-" + UUID.randomUUID().toString().substring(0, 6));
		body.put("invoiceDate", LocalDate.now(TEMPLE_ZONE).toString());
		body.put("dueDate", null);
		body.put("receiptIds", receiptIds);
		body.put("lines", new ArrayList<>(lines));
		body.put("gstAmount", new BigDecimal(gst));
		body.put("otherCharges", new BigDecimal(other));
		body.put("otherChargesNote", otherNote);
		body.put("discount", new BigDecimal(discount));
		body.put("grandTotal", new BigDecimal(grandTotal));
		body.put("billAttachmentId", billAttachmentId);
		return body;
	}

	static Map<String, Object> deliveredLine(UUID receiptLine, UUID ingredient, String qty, String unit,
			UUID pack, String packCount, String amount) {
		Map<String, Object> line = directLine(ingredient, null, qty, unit, pack, packCount, amount);
		line.put("goodsReceiptLineId", receiptLine);
		return line;
	}

	static Map<String, Object> directLine(UUID ingredient, String description, String qty, String unit, String amount) {
		return directLine(ingredient, description, qty, unit, null, null, amount);
	}

	static Map<String, Object> directLine(UUID ingredient, String description, String qty, String unit,
			UUID pack, String packCount, String amount) {
		Map<String, Object> line = new LinkedHashMap<>();
		line.put("ingredientId", ingredient);
		line.put("description", description);
		line.put("billedQty", new BigDecimal(qty));
		line.put("unit", unit);
		line.put("packSizeId", pack);
		line.put("packCount", packCount == null ? null : new BigDecimal(packCount));
		line.put("amount", new BigDecimal(amount));
		return line;
	}

	private MockHttpServletRequestBuilder invoice(Map<String, Object> body) throws Exception {
		return authed(post("/api/v1/vendor-invoices"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body));
	}

	private JsonNode read(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString());
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private long count(String sql, Object... args) {
		Long n = admin.queryForObject(sql, Long.class, args);
		return n == null ? 0 : n;
	}

	private static LocalDate today() {
		return LocalDate.now(TEMPLE_ZONE);
	}

	private UUID temple(String slug) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata') RETURNING id
				""", UUID.class, slug, slug);
	}

	private UUID user(UUID temple, String uid, String name, String role, String phone) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE') RETURNING id
				""", UUID.class, temple, uid, name, uid + "@example.com", phone, role);
	}

	private UUID ingredient(UUID temple, String name, String unit) {
		return admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, 'Grains', ?) RETURNING id
				""", UUID.class, temple, name, unit);
	}

	private UUID vendor(UUID temple, String name) {
		return admin.queryForObject("INSERT INTO vendors (tenant_id, name) VALUES (?, ?) RETURNING id",
				UUID.class, temple, name);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
		return b.header("Authorization", "Bearer valid-token");
	}

	private void signIn(String uid) {
		stubVerifier.accept(uid);
	}
}

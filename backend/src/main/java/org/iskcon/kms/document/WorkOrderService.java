package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.ingredientrequest.IngredientRequestDishView;
import org.iskcon.kms.ingredientrequest.IngredientRequestLineView;
import org.iskcon.kms.ingredientrequest.IngredientRequestService;
import org.iskcon.kms.ingredientrequest.IngredientRequestStatus;
import org.iskcon.kms.ingredientrequest.IngredientRequestSummary;
import org.iskcon.kms.ingredientrequest.IngredientRequestView;
import org.iskcon.kms.inventory.AllocatedLine;
import org.iskcon.kms.inventory.BatchDraw;
import org.iskcon.kms.inventory.FefoAllocator;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.inventory.StockAllocation;
import org.iskcon.kms.inventory.StockShortfall;
import org.iskcon.kms.translation.GlossaryService;
import org.iskcon.kms.translation.Languages;
import org.iskcon.kms.translation.TranslationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the work order for one approved request (E10-S11).
 *
 * <p>It gathers rather than computes, the way {@link JobCardService} does: the request, its lines
 * and its dishes come from {@link IngredientRequestService}, and the batches come from
 * {@link FefoAllocator} — the one copy of "take the lot that goes off first". So the sheet cannot
 * disagree with the screen it was printed from, and it cannot disagree with what issuing will
 * actually draw, because both of them ask the same allocator the same question.
 *
 * <p><strong>The batch list is computed at render time and is never frozen.</strong> Approval
 * decides that the kitchen may have the food; the sheet says where today's is. An afternoon's
 * cooking can empty the lot a sheet printed this morning would have named, and a work order that
 * sends a storekeeper to a bare shelf is worse than no work order — so the sheet is a rendered view
 * of the request rather than a snapshot taken at approval (design D3, and V79's header says the same
 * thing about why the document row is versioned while its batch list is not).
 *
 * <p><strong>Only an approved or issued request has one.</strong> A draft, a submitted request and a
 * denied one are all refused with {@link ErrorCode#INGREDIENT_REQUEST_NOT_APPROVED}. An issued one
 * keeps its sheet because somebody will reprint the paper they signed, and re-rendering it then
 * shows today's shelf rather than the one it was picked from — which is honest, and is why the
 * generated-on stamp is in the footer.
 *
 * <p><strong>The whole sheet translates, and a translation failure costs one word rather than the
 * sheet.</strong> Fixed wording goes through {@link DocumentLabelTranslator}; ingredient and dish
 * names are tenant content and take the same glossary-then-provider path the job card and the PO
 * sheet take for recipe content. Where the provider will not answer, the item keeps its English name
 * and the rest of the sheet still prints — a storekeeper with nine names in Kannada and one in
 * English can still do the round, and one with no sheet at all cannot.
 */
@Service
public class WorkOrderService {

	private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

	/** The label set the sheet's fixed wording is cached under. */
	static final String LABEL_SET = "WORK_ORDER";

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter DATE_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");
	private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("d MMM yyyy");
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(TEMPLE_ZONE);

	private final JdbcTemplate jdbc;
	private final IngredientRequestService requestService;
	private final FefoAllocator fefoAllocator;
	private final DocumentLabelTranslator labelTranslator;
	private final GlossaryService glossaryService;
	private final TranslationProvider translationProvider;
	private final JobCardService jobCardService;

	public WorkOrderService(
			JdbcTemplate jdbc, IngredientRequestService requestService, FefoAllocator fefoAllocator,
			DocumentLabelTranslator labelTranslator, GlossaryService glossaryService,
			TranslationProvider translationProvider, JobCardService jobCardService) {
		this.jdbc = jdbc;
		this.requestService = requestService;
		this.fefoAllocator = fefoAllocator;
		this.labelTranslator = labelTranslator;
		this.glossaryService = glossaryService;
		this.translationProvider = translationProvider;
		this.jobCardService = jobCardService;
	}

	/** The sheet for a request, rendered to HTML — the same document the PDF is made from. */
	@Transactional
	public String render(UUID requestId, String language) {
		return WorkOrderTemplate.render(build(requestId, language));
	}

	/**
	 * What languages this sheet can be printed in, and which the picker opens on.
	 *
	 * <p>All 23 — English and the 22 scheduled — every time, and offered from a list held in the
	 * application rather than assembled from what happens to be translated already. That correction
	 * is {@link JobCardService#appendixLanguages}'s, learnt the hard way: an offer narrowed to the
	 * cache made the picker on a fresh temple hold one entry and look broken. The default is the
	 * temple's own language.
	 */
	@Transactional(readOnly = true)
	public PrintLanguages languages() {
		String temple = jobCardService.templeLanguage();
		String preselected = Languages.ALL.contains(temple) ? temple : Languages.ENGLISH;
		return new PrintLanguages(Languages.ALL, preselected);
	}

	/** The languages a work order can print in, and the one the picker opens on. */
	public record PrintLanguages(List<String> languages, String defaultLanguage) {
	}

	/**
	 * Refuses a request that has no work order, before anything is queued or rendered.
	 *
	 * <p>An existing code rather than a new one: {@code INGREDIENT_REQUEST_NOT_APPROVED} already says
	 * the exact thing that is wrong — "it has to be approved before the store can issue against it" —
	 * and a work order is the paper the store issues against.
	 */
	@Transactional(readOnly = true)
	public void requireWorkOrderAvailable(UUID requestId) {
		IngredientRequestSummary request = requestService.get(requestId).request();
		requireApprovedOrIssued(request);
	}

	private static void requireApprovedOrIssued(IngredientRequestSummary request) {
		if (request.status() != IngredientRequestStatus.APPROVED
				&& request.status() != IngredientRequestStatus.ISSUED) {
			throw new ApplicationException(ErrorCode.INGREDIENT_REQUEST_NOT_APPROVED, Map.of(
					"ingredientRequestId", request.id(), "status", request.status().name()));
		}
	}

	// ---------------------------------------------------------------------

	WorkOrderTemplate.SheetModel build(UUID requestId, String language) {
		IngredientRequestView view = requestService.get(requestId);
		IngredientRequestSummary request = view.request();
		requireApprovedOrIssued(request);

		// Nobody chose at the printer, so the temple's own language — resolved here rather than in the
		// controller, so the print view and the queued PDF resolve it identically and come out as the
		// same sheet. It is the default and not the rule: whoever prints can still ask for English.
		String resolved = language == null || language.isBlank()
				? languages().defaultLanguage() : language.trim();

		boolean translating = !DocumentLabelTranslator.isEnglish(resolved);
		List<String> labels = translating
				? labelTranslator.labels(LABEL_SET, WorkOrderTemplate.Labels.VERSION,
						WorkOrderTemplate.Labels.englishList(), resolved)
				: WorkOrderTemplate.Labels.englishList();

		Header header = header(requestId);

		// One MT round for everything on the sheet that is tenant content — the ingredient names, the
		// dish names and the reason — rather than one per section. Names are asked for in the order
		// they are printed in and handed back in the same order.
		List<String> content = new ArrayList<>();
		for (IngredientRequestLineView line : view.lines()) {
			content.add(line.ingredientName());
		}
		for (IngredientRequestDishView dish : view.dishes()) {
			content.add(dish.dishName());
		}
		String purpose = request.purpose();
		if (purpose != null && !purpose.isBlank()) {
			content.add(purpose);
		}
		List<String> local = translating ? translateContent(content, resolved) : content;

		Map<UUID, String> localNames = new LinkedHashMap<>();
		for (int i = 0; i < view.lines().size(); i++) {
			localNames.putIfAbsent(view.lines().get(i).ingredientId(), local.get(i));
		}

		List<WorkOrderTemplate.Dish> dishes = new ArrayList<>();
		for (int i = 0; i < view.dishes().size(); i++) {
			IngredientRequestDishView dish = view.dishes().get(i);
			String localName = local.get(view.lines().size() + i);
			dishes.add(new WorkOrderTemplate.Dish(
					dish.dishName(),
					translating && !localName.equals(dish.dishName()) ? localName : null,
					// A dish is measured in servings as often as in kilos, and it is a figure somebody
					// cooks to rather than reconciles against, so it is the cook's form like the rest
					// of the sheet.
					Quantities.cooks(dish.quantity(), dish.unit())));
		}
		String localPurpose = purpose == null || purpose.isBlank()
				? purpose : local.get(local.size() - 1);

		List<WorkOrderTemplate.Line> lines = picking(view.lines(), localNames, translating);

		return new WorkOrderTemplate.SheetModel(
				templeName(),
				request.kitchenName(),
				header.kitchenLocation(),
				request.reference(),
				request.neededOn() == null ? null : DATE_LONG.format(request.neededOn()),
				localPurpose,
				dishes,
				lines,
				request.requestedByName(),
				stampDate(request.submittedAt()),
				request.decidedByName(),
				stampDate(request.decidedAt()),
				header.selfApproved(),
				translating ? languageLabel(resolved) : null,
				STAMP.format(Instant.now()),
				labels);
	}

	/**
	 * The picking list, worked out now against the shelf as it stands.
	 *
	 * <p><strong>Aggregated per ingredient, exactly as issuing aggregates it.</strong> A request may
	 * name rice twice — once for the khichdi and once for the pulao — and two rows each drawing
	 * independently would each see the whole of the earliest lot and between them name more of it
	 * than it holds. {@code IngredientIssueService} merges the lines before it allocates, and this
	 * merges them the same way, which is what makes the sheet's answer the same answer.
	 *
	 * <p>Where the store cannot cover a line, the lots there are still print — they are still the
	 * ones to pick — and the shortfall is said on the row and again at the top of the sheet, so the
	 * walk is not made in ignorance. Issuing will refuse the whole act; that refusal belongs there,
	 * not here, and a sheet is not the place to decide it.
	 */
	private List<WorkOrderTemplate.Line> picking(
			List<IngredientRequestLineView> requestLines, Map<UUID, String> localNames,
			boolean translating) {

		Map<UUID, BigDecimal> requiredBase = new LinkedHashMap<>();
		Map<UUID, String> names = new LinkedHashMap<>();
		for (IngredientRequestLineView line : requestLines) {
			requiredBase.merge(line.ingredientId(),
					InventoryUnits.toBase(line.quantity(), Unit.valueOf(line.unit())), BigDecimal::add);
			names.putIfAbsent(line.ingredientId(), line.ingredientName());
		}
		if (requiredBase.isEmpty()) {
			return List.of();
		}

		StockAllocation allocation = fefoAllocator.allocate(requiredBase, names, Map.of());
		Map<UUID, StockShortfall> shortfalls = new LinkedHashMap<>();
		for (StockShortfall shortfall : allocation.shortfalls()) {
			shortfalls.put(shortfall.ingredientId(), shortfall);
		}
		Map<UUID, LocalDate> arrivals = arrivalDates(allocation);

		List<WorkOrderTemplate.Line> lines = new ArrayList<>();
		for (AllocatedLine allocated : allocation.lines()) {
			Unit canonical = allocated.canonicalUnit();

			List<WorkOrderTemplate.Batch> batches = new ArrayList<>();
			for (BatchDraw draw : allocated.draws()) {
				batches.add(new WorkOrderTemplate.Batch(
						cooks(draw.takeBase(), canonical),
						draw.expiry() == null ? null : DATE_SHORT.format(draw.expiry()),
						arrivals.get(draw.batchId()) == null
								? null : DATE_SHORT.format(arrivals.get(draw.batchId()))));
			}

			String shortfallText = null;
			StockShortfall shortfall = shortfalls.get(allocated.ingredientId());
			if (shortfall != null) {
				// What is there against what is wanted, both in the cook's form: they are read beside
				// the quantity on the same row, and two forms of one number on a line is how a sheet
				// gets misread. The words in front of them are the template's, because they translate.
				shortfallText = "%s / %s".formatted(
						Quantities.cooks(shortfall.available(), canonical),
						Quantities.cooks(shortfall.required(), canonical));
			}

			String localName = localNames.get(allocated.ingredientId());
			lines.add(new WorkOrderTemplate.Line(
					allocated.ingredientName(),
					translating && localName != null && !localName.equals(allocated.ingredientName())
							? localName : null,
					// A work order is weighed against, never reconciled against, so every quantity on
					// it is the cook's form: a 0.1344 Kg line reads "135 gm" and not "0.1344 Kg".
					cooks(allocated.requiredBase(), canonical),
					batches,
					shortfallText));
		}
		return lines;
	}

	/**
	 * When each drawn lot arrived, in one query.
	 *
	 * <p>A batch has no code — {@code stock_movements.batch_id} is a UUID, and a hex id is not
	 * something anybody can recognise on a shelf. The expiry comes back on the draw itself; the
	 * arrival date is the other half of how a storekeeper tells one sack from another, and it is the
	 * pair the inventory screen already names a lot by.
	 */
	private Map<UUID, LocalDate> arrivalDates(StockAllocation allocation) {
		List<UUID> batchIds = new ArrayList<>();
		for (AllocatedLine line : allocation.lines()) {
			for (BatchDraw draw : line.draws()) {
				batchIds.add(draw.batchId());
			}
		}
		Map<UUID, LocalDate> arrivals = new LinkedHashMap<>();
		if (batchIds.isEmpty()) {
			return arrivals;
		}
		String placeholders = String.join(", ", Collections.nCopies(batchIds.size(), "?"));
		jdbc.query("""
				SELECT batch_id, MAX(received_date) AS received_date
				FROM stock_movements WHERE batch_id IN (""" + placeholders + """
				) GROUP BY batch_id
				""", rs -> {
			arrivals.put(rs.getObject("batch_id", UUID.class),
					rs.getObject("received_date", LocalDate.class));
		}, batchIds.toArray());
		return arrivals;
	}

	/**
	 * The heading facts the request's own view does not carry: where the kitchen is, and whether the
	 * person who approved this is the person who raised it.
	 *
	 * <p>Self-approval is allowed — forbidding it would deadlock a temple whose administrator is its
	 * only approver, which is most of them — and it is printed here rather than left in the audit
	 * log, because the auditor this sheet is filed for reads paper.
	 */
	private Header header(UUID requestId) {
		List<Header> rows = jdbc.query("""
				SELECT k.location,
					   (r.decided_by IS NOT NULL AND r.decided_by = r.requested_by) AS self_approved
				FROM ingredient_requests r
				JOIN kitchens k ON k.id = r.kitchen_id
				WHERE r.id = ?
				""", (rs, n) -> new Header(rs.getString("location"), rs.getBoolean("self_approved")),
				requestId);
		return rows.isEmpty() ? new Header(null, false) : rows.get(0);
	}

	private record Header(String kitchenLocation, boolean selfApproved) {
	}

	// ---- Language -------------------------------------------------------

	/**
	 * The sheet's tenant content in the chosen language: the glossary first, so a temple can pin a
	 * culinary term it wants left alone, then one machine-translation round for the rest.
	 *
	 * <p><strong>A failure costs one item, not the sheet.</strong> The batch call is tried first
	 * because it is one round trip for a whole sheet; if it will not answer, each remaining item is
	 * asked for on its own and anything still refused keeps its English name. A storekeeper with nine
	 * names translated and one in English can do the round. One holding no sheet cannot, and a print
	 * is not the moment to discover the provider is down.
	 */
	private List<String> translateContent(List<String> english, String language) {
		if (english.isEmpty()) {
			return english;
		}
		Map<String, String> glossary = glossaryService.lookup(language);
		String[] out = new String[english.size()];
		List<String> pending = new ArrayList<>();
		List<Integer> pendingAt = new ArrayList<>();
		for (int i = 0; i < english.size(); i++) {
			String override = glossary.get(english.get(i).toLowerCase(Locale.ROOT));
			if (override != null) {
				out[i] = override;
			} else {
				pendingAt.add(i);
				pending.add(english.get(i));
			}
		}
		if (pending.isEmpty()) {
			return List.of(out);
		}

		try {
			List<String> translated = translationProvider.translate(pending, "en", language);
			for (int i = 0; i < pendingAt.size(); i++) {
				out[pendingAt.get(i)] = translated.get(i);
			}
			return List.of(out);
		} catch (RuntimeException e) {
			log.warn("Work order content translation to {} failed as a batch; falling back per item",
					language, e);
		}

		for (int i = 0; i < pendingAt.size(); i++) {
			int at = pendingAt.get(i);
			try {
				out[at] = translationProvider.translate(List.of(pending.get(i)), "en", language).get(0);
			} catch (RuntimeException e) {
				log.warn("Work order keeping the English text for one item in {}: {}",
						language, e.toString());
				out[at] = english.get(at);
			}
		}
		return List.of(out);
	}

	/**
	 * What to call the sheet's language on the sheet, in that language's own script where the JDK
	 * knows it — somebody who does not read English should not have to read "Kannada" to find out
	 * that this is the Kannada copy.
	 */
	private static String languageLabel(String language) {
		Locale locale = Locale.forLanguageTag(language);
		String own = locale.getDisplayLanguage(locale);
		return own == null || own.isBlank() ? language : own;
	}

	// ---------------------------------------------------------------------

	/** A base-unit figure written the way somebody at a scale reads it. */
	private static String cooks(BigDecimal base, Unit canonical) {
		return Quantities.cooks(InventoryUnits.fromBase(base, canonical), canonical);
	}

	private static String stampDate(Instant instant) {
		return instant == null ? null : DATE_SHORT.format(instant.atZone(TEMPLE_ZONE));
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "Temple";
		}
	}
}

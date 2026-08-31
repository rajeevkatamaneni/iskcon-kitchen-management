package org.iskcon.kms.today;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.calendar.CalendarDayView;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.costing.MaterialsCostService;
import org.iskcon.kms.inventory.InventoryItemService;
import org.iskcon.kms.inventory.StockItemView;
import org.iskcon.kms.invoice.VendorInvoiceService;
import org.iskcon.kms.invoice.VendorInvoiceView;
import org.iskcon.kms.meal.MealCrewService;
import org.iskcon.kms.meal.MealPlanView;
import org.iskcon.kms.meal.MealStatus;
import org.iskcon.kms.meal.ServedMeal;
import org.iskcon.kms.meal.ServedMealService;
import org.iskcon.kms.purchaseorder.PoStatus;
import org.iskcon.kms.purchaseorder.PurchaseOrderService;
import org.iskcon.kms.purchaseorder.PurchaseOrderView;
import org.iskcon.kms.staff.WorkforceCount;
import org.iskcon.kms.staff.WorkforceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the Today screen (E4-S8).
 *
 * <p>It reads and never writes. Every figure comes from the service that owns it, so this screen
 * cannot drift from the screens it summarises — the alternative, its own queries, is how a dashboard
 * ends up quietly disagreeing with the page it links to.
 *
 * <p>What a reader may not see is left out rather than zeroed: kitchen staff do not hold
 * {@code MANAGE_VENDOR_PAYMENTS}. Endpoints declare one permission, so the finer-grained check
 * belongs here, keyed on the same policy document ({@link RolePermissions}) the endpoints are
 * enforced from.
 *
 * <p>Two figures on this screen are read from elsewhere on purpose rather than worked out here.
 * The workforce count comes from {@link WorkforceService}, the same source the week grid's column
 * totals and the planner's pebbles use, because three screens each counting for themselves is three
 * screens that disagree by one and nobody able to say which is right. The plate count comes from
 * {@link ServedMealService}, which knows a meal from a dish.
 */
@Service
public class TodayService {

	/** The temples are in India; the same assumption the rest of the product already makes. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	/** How far ahead the screen looks for the next fast or festival. A month of notice is enough to
	 * order for one and to roster for the other; further out is noise on a morning screen. */
	private static final int AHEAD_DAYS = 30;

	/** How far back the unrecorded-meal nudge looks. A week is what somebody can still remember. */
	private static final int NUDGE_DAYS = 7;

	private final ServedMealService servedMealService;
	private final MealCrewService mealCrewService;
	private final InventoryItemService inventoryItemService;
	private final WorkforceService workforceService;
	private final MaterialsCostService materialsCostService;
	private final PurchaseOrderService purchaseOrderService;
	private final VendorInvoiceService vendorInvoiceService;
	private final CalendarService calendarService;

	public TodayService(
			ServedMealService servedMealService, MealCrewService mealCrewService,
			InventoryItemService inventoryItemService,
			WorkforceService workforceService, MaterialsCostService materialsCostService,
			PurchaseOrderService purchaseOrderService, VendorInvoiceService vendorInvoiceService,
			CalendarService calendarService) {
		this.servedMealService = servedMealService;
		this.mealCrewService = mealCrewService;
		this.inventoryItemService = inventoryItemService;
		this.workforceService = workforceService;
		this.materialsCostService = materialsCostService;
		this.purchaseOrderService = purchaseOrderService;
		this.vendorInvoiceService = vendorInvoiceService;
		this.calendarService = calendarService;
	}

	@Transactional(readOnly = true)
	public TodayView today(AuthenticatedUser actor) {
		LocalDate today = LocalDate.now(TEMPLE_ZONE);
		LocalDate tomorrow = today.plusDays(1);

		List<TodayView.Meal> meals = mealsOf(today);
		List<StockItemView> stock = inventoryItemService.list(null, null, null);

		return new TodayView(
				today,
				calendarNote(today, tomorrow),
				meals,
				plates(meals),
				(int) stock.stream().filter(StockItemView::belowThreshold).count(),
				stock.size(),
				workforce(today),
				materialsCost(today),
				servedMealService.unrecordedCount(today.minusDays(NUDGE_DAYS), today.minusDays(1)),
				deliveries(actor, today));
	}

	// ---- The kitchen's day ----------------------------------------------

	/**
	 * Today's meals in ready-by order — the order the kitchen actually works in (E4-S7 D4) — each
	 * carrying its own dishes.
	 *
	 * <p>A meal that was cancelled outright, every dish of it, is not work the kitchen has to do
	 * today and does not appear. A meal with one dish called off keeps its place: the rest of it is
	 * still being cooked.
	 */
	private List<TodayView.Meal> mealsOf(LocalDate today) {
		return servedMealService.list(today, today).stream()
				.filter(meal -> meal.dishes().stream()
						.anyMatch(dish -> dish.status() != MealStatus.CANCELLED))
				.sorted(Comparator.comparing(ServedMeal::readyBy))
				.map(meal -> new TodayView.Meal(
						meal.mealKind(),
						meal.readyBy(),
						meal.plates(),
						meal.recorded(),
						meal.awaitingRecord(),
						meal.occasionName(),
						meal.dishes().stream().map(TodayService::dish).toList()))
				.toList();
	}

	private static TodayView.Dish dish(MealPlanView plan) {
		return new TodayView.Dish(
				plan.id(),
				plan.recipeName(),
				plan.targetYield(),
				plan.targetYieldUnit(),
				plan.actualServings(),
				plan.notMade(),
				plan.status().name());
	}

	/**
	 * How many plates the kitchen is cooking today: the sum over its <em>meals</em>, each of which
	 * reports its own head count.
	 *
	 * <p>This used to sum every dish's servings, so a lunch of three dishes at 250 each reported 750
	 * plates (build brief §1d). Summing across meal kinds is a different and legitimate thing —
	 * breakfast, lunch and dinner are three plates for the same person, and the kitchen serves all
	 * three.
	 */
	private int plates(List<TodayView.Meal> meals) {
		return meals.stream().mapToInt(TodayView.Meal::plates).sum();
	}

	// ---- Whether there is a kitchen to cook with -------------------------

	/**
	 * Staff in today and volunteers signed up today, counted apart (B1).
	 *
	 * <p>Replaces the old *Shifts unfilled* tile, which warned about a shift on an unnamed date and
	 * gave an admin nothing they could act on. What they actually want is a read on today: is there
	 * enough of a kitchen to cook with?
	 *
	 * <p>And per meal as well as per day (item 24), because the day figure cannot answer the question.
	 * Seven people working today says nothing about whether lunch has enough hands: they are not all
	 * there at midday, and lunch may take eight. Each meal is read at the moment its food is due.
	 */
	private TodayView.Workforce workforce(LocalDate today) {
		WorkforceCount count = workforceService.countFor(today);
		return new TodayView.Workforce(
				count.staffIn(), count.volunteers(), mealCrewService.crewFor(today, today));
	}

	// ---- What today's food costs ----------------------------------------

	/**
	 * An estimate, from vendors' last-known prices, and said to be one on the screen.
	 *
	 * <p>Perfect costing was rejected on its merits: a true figure needs inventory valuation, and the
	 * store room holds donated goods, which have an estimated value and no purchase price at all — so
	 * a "perfect" number would be part fiction the moment a gift in kind is cooked.
	 */
	private TodayView.MaterialsCost materialsCost(LocalDate today) {
		var cost = materialsCostService.costFor(today);
		return new TodayView.MaterialsCost(cost.estimatedTotal(), cost.ingredientsWithoutPrice());
	}

	// ---- What is arriving -----------------------------------------------

	/**
	 * Orders due today or already late, plus — for someone who handles money — invoices past their
	 * due date. Both answer the store keeper's first question of the morning without opening
	 * Purchase orders.
	 */
	private List<TodayView.Delivery> deliveries(AuthenticatedUser actor, LocalDate today) {
		List<TodayView.Delivery> awaited = purchaseOrderService.list(PoStatus.SENT).stream()
				.filter(po -> po.neededBy() != null && !po.neededBy().isAfter(today))
				.sorted(Comparator.comparing(PurchaseOrderView::neededBy))
				.map(po -> new TodayView.Delivery(
						po.id(), po.poNumber(), po.vendorName(), po.neededBy(), "AWAITED"))
				.toList();

		if (!may(actor, Permission.MANAGE_VENDOR_PAYMENTS)) {
			return awaited;
		}

		List<TodayView.Delivery> overdue = vendorInvoiceService.list(null, true).stream()
				.map(invoice -> new TodayView.Delivery(
						invoice.purchaseOrderId(),
						invoice.poNumber(),
						invoice.vendorName(),
						invoice.dueDate(),
						"INVOICE_OVERDUE"))
				.toList();

		return java.util.stream.Stream.concat(awaited.stream(), overdue.stream()).toList();
	}

	// ---- What the day asks of the kitchen -------------------------------

	/**
	 * Today and tomorrow on the temple's calendar. Null when a temple has no calendar computed yet —
	 * the screen then says nothing about fasting rather than asserting there is none.
	 */
	private TodayView.CalendarNote calendarNote(LocalDate today, LocalDate tomorrow) {
		Optional<CalendarDayView> todayDay = calendarService.day(today);
		Optional<CalendarDayView> tomorrowDay = calendarService.day(tomorrow);

		if (todayDay.isEmpty() && tomorrowDay.isEmpty()) {
			return null;
		}

		return new TodayView.CalendarNote(
				todayDay.map(CalendarDayView::isEkadashi).orElse(false),
				tomorrowDay.map(CalendarDayView::isEkadashi).orElse(false),
				todayDay.map(TodayService::dayName).orElse(null),
				tomorrowDay.map(TodayService::dayName).orElse(null),
				todayDay.map(CalendarDayView::sunrise).orElse(null),
				todayDay.map(CalendarDayView::tithi).orElse(0),
				todayDay.map(CalendarDayView::paksa).orElse(0),
				todayDay.map(CalendarDayView::masa).orElse(0),
				todayDay.map(CalendarDayView::naksatra).orElse(null),
				ahead(today, tomorrow.plusDays(1)));
	}

	/**
	 * The next day within a month that the kitchen has to cook differently for. Today and tomorrow
	 * have their own banner, so the search starts the day after: a screen that says "a fast tomorrow"
	 * and "a fast tomorrow, in 1 day" is saying one thing twice.
	 */
	private TodayView.Ahead ahead(LocalDate today, LocalDate from) {
		LocalDate to = from.plusDays(AHEAD_DAYS);
		for (CalendarDayView day : calendarService.range(from, to)) {
			String festival = day.festivals().stream()
					.min(Comparator.comparingInt(CalendarDayView.CalendarFestivalView::priority))
					.map(CalendarDayView.CalendarFestivalView::text)
					.orElse(null);
			if (festival == null && !day.isEkadashi()) {
				continue;
			}
			// A fast is the more consequential of the two when a day is both: it takes food off the
			// menu, where a festival only adds people to the hall.
			boolean fast = day.isEkadashi();
			return new TodayView.Ahead(
					day.date(),
					fast ? (day.ekadashiName() != null ? day.ekadashiName() : "Ekadasi") : festival,
					fast ? "FAST" : "FESTIVAL",
					(int) ChronoUnit.DAYS.between(today, day.date()));
		}
		return null;
	}

	/**
	 * What to call the day: its most prominent festival, else the fast that defines it. GCAL orders
	 * festivals by priority, lower being more prominent, and a day can carry several.
	 */
	private static String dayName(CalendarDayView day) {
		return day.festivals().stream()
				.min(Comparator.comparingInt(CalendarDayView.CalendarFestivalView::priority))
				.map(CalendarDayView.CalendarFestivalView::text)
				.orElseGet(() -> day.isEkadashi() ? day.ekadashiName() : null);
	}

	private static boolean may(AuthenticatedUser actor, Permission permission) {
		return RolePermissions.has(actor.getRole(), permission);
	}
}

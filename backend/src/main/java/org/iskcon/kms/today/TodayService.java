package org.iskcon.kms.today;

import java.math.BigDecimal;
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
import org.iskcon.kms.donation.DonationLedgerService;
import org.iskcon.kms.inventory.InventoryItemService;
import org.iskcon.kms.inventory.StockItemView;
import org.iskcon.kms.invoice.VendorInvoiceService;
import org.iskcon.kms.invoice.VendorInvoiceView;
import org.iskcon.kms.meal.MealPlanService;
import org.iskcon.kms.meal.MealPlanView;
import org.iskcon.kms.meal.MealStatus;
import org.iskcon.kms.purchaseorder.PoStatus;
import org.iskcon.kms.purchaseorder.PurchaseOrderService;
import org.iskcon.kms.purchaseorder.PurchaseOrderView;
import org.iskcon.kms.shift.ShiftService;
import org.iskcon.kms.shift.ShiftView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the Today screen (E4-S8).
 *
 * <p>It reads and never writes. Every figure comes from the service that owns it, so this screen
 * cannot drift from the screens it summarises — the alternative, its own queries, is how a dashboard
 * ends up quietly disagreeing with the page it links to.
 *
 * <p>What a reader may not see is left out rather than zeroed: kitchen staff hold neither
 * {@code VIEW_DONATIONS} nor {@code MANAGE_VENDOR_PAYMENTS}. Endpoints declare one permission, so
 * the finer-grained check belongs here, keyed on the same policy document
 * ({@link RolePermissions}) the endpoints are enforced from.
 */
@Service
public class TodayService {

	/** The temples are in India; the same assumption the rest of the product already makes. */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	/** How far ahead the screen looks for the next fast or festival. A month of notice is enough to
	 * order for one and to roster for the other; further out is noise on a morning screen. */
	private static final int AHEAD_DAYS = 30;

	private final MealPlanService mealPlanService;
	private final InventoryItemService inventoryItemService;
	private final ShiftService shiftService;
	private final DonationLedgerService donationLedgerService;
	private final PurchaseOrderService purchaseOrderService;
	private final VendorInvoiceService vendorInvoiceService;
	private final CalendarService calendarService;

	public TodayService(
			MealPlanService mealPlanService, InventoryItemService inventoryItemService,
			ShiftService shiftService, DonationLedgerService donationLedgerService,
			PurchaseOrderService purchaseOrderService, VendorInvoiceService vendorInvoiceService,
			CalendarService calendarService) {
		this.mealPlanService = mealPlanService;
		this.inventoryItemService = inventoryItemService;
		this.shiftService = shiftService;
		this.donationLedgerService = donationLedgerService;
		this.purchaseOrderService = purchaseOrderService;
		this.vendorInvoiceService = vendorInvoiceService;
		this.calendarService = calendarService;
	}

	@Transactional(readOnly = true)
	public TodayView today(AuthenticatedUser actor) {
		LocalDate today = LocalDate.now(TEMPLE_ZONE);
		LocalDate tomorrow = today.plusDays(1);

		List<TodayView.PlannedMeal> meals = mealsOf(today);
		List<StockItemView> stock = inventoryItemService.list(null, null, null);
		List<ShiftView> shifts = shiftsToWatch(today, tomorrow);

		return new TodayView(
				today,
				calendarNote(today, tomorrow),
				meals,
				plates(meals),
				(int) stock.stream().filter(StockItemView::belowThreshold).count(),
				stock.size(),
				unfilledSpots(shifts),
				shifts.size(),
				nextUnfilledShift(shifts),
				may(actor, Permission.VIEW_DONATIONS) ? giving() : null,
				deliveries(actor, today));
	}

	// ---- The kitchen's day ----------------------------------------------

	/** Today's meals in ready-by order — the order the kitchen actually works in (E4-S7 D4). */
	private List<TodayView.PlannedMeal> mealsOf(LocalDate today) {
		return mealPlanService.list(today, today, null, null).stream()
				// A cancelled meal is not work the kitchen has to do today.
				.filter(plan -> plan.status() != MealStatus.CANCELLED)
				.sorted(Comparator.comparing(MealPlanView::readyBy))
				.map(plan -> new TodayView.PlannedMeal(
						plan.id(),
						plan.mealKind(),
						plan.readyBy(),
						plan.recipeName(),
						plan.targetServings(),
						plan.status().name(),
						plan.occasionName()))
				.toList();
	}

	/** How many plates the kitchen is cooking today, across every meal on it. */
	private int plates(List<TodayView.PlannedMeal> meals) {
		return meals.stream()
				.map(TodayView.PlannedMeal::targetServings)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.intValue();
	}

	// ---- Who is missing -------------------------------------------------

	/**
	 * Today and tomorrow. Tomorrow is included deliberately: a spot unfilled for tomorrow morning
	 * can still be filled by asking someone today, which is the whole point of noticing it.
	 */
	private List<ShiftView> shiftsToWatch(LocalDate today, LocalDate tomorrow) {
		return shiftService.list(today, tomorrow, false);
	}

	private int unfilledSpots(List<ShiftView> shifts) {
		return shifts.stream()
				.mapToInt(shift -> Math.max(0, shift.capacity() - shift.signedUpCount()))
				.sum();
	}

	/** The first shift still short of people, named so the tile says something actionable. */
	private String nextUnfilledShift(List<ShiftView> shifts) {
		return shifts.stream()
				.filter(shift -> shift.signedUpCount() < shift.capacity())
				.min(Comparator.comparing(ShiftView::shiftDate).thenComparing(ShiftView::startTime))
				.map(shift -> shift.title() + ", " + shift.startTime())
				.orElse(null);
	}

	// ---- What has come in -----------------------------------------------

	private TodayView.Giving giving() {
		var summary = donationLedgerService.summary();
		BigDecimal total = summary.monthToDateByCategory().values().stream()
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new TodayView.Giving(total, LocalDate.now(TEMPLE_ZONE).withDayOfMonth(1));
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

package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unified donations ledger (E7-S7): every donation — one-time, recurring, wish-list, in-kind, and
 * whatever a person recorded by hand — in one filterable view, with anonymity-safe donor display (no
 * PII ever, export included), period totals compared against the same point a year earlier, and a
 * donor drill-down. "Properly accounted for" is a screen, not an aspiration.
 */
@Service
public class DonationLedgerService {

	/**
	 * The temple's own day, not the server's. The service runs in UTC, where "today" turns over at
	 * 05:30 IST — so between midnight and dawn a temple's month-to-date would have been missing the
	 * gifts of what it still calls today, and the financial-year boundary could land a day early.
	 */
	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");

	private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

	private final JdbcTemplate jdbc;

	public DonationLedgerService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<LedgerRow> ledger(LocalDate from, LocalDate to, String category, String status) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (from != null) {
			sql.append(" AND d.donated_on >= ?");
			args.add(from);
		}
		if (to != null) {
			sql.append(" AND d.donated_on <= ?");
			args.add(to);
		}
		sql.append(categoryClause(category));
		// Default to the completed gifts the accountant reconciles; an explicit status overrides.
		sql.append(" AND d.status = ?");
		args.add(status != null ? status : "COMPLETED");
		sql.append(" ORDER BY d.created_at DESC");
		return jdbc.query(sql.toString(), MAPPER, args.toArray());
	}


	/**
	 * The same tiles, for whichever period the accountant picked, each carrying what it came to by the
	 * same point a year earlier (§8 of the 2026-08-20 brief).
	 *
	 * <p>Both figures are counted by {@code donated_on} — the day the gift was actually given — and
	 * never by {@code created_at}, the day somebody got round to typing it in. That is deliberate and
	 * costs something real: a cash gift from last Tuesday, recorded on Friday, moves last week's total
	 * after the fact, so a figure read on Wednesday and again on Monday need not agree. The
	 * alternative is a screen that credits August with a July gift because that is when the office
	 * opened the envelope, and a total that cannot be reconciled against a receipt book is worth less
	 * than one that moves. Please do not "fix" this to {@code created_at}.
	 *
	 * <p>The window arithmetic is in {@link LedgerPeriod}; this method only asks for the two windows
	 * and divides one set of totals by the other.
	 *
	 * @param period        WEEK, MONTH, FINANCIAL_YEAR or YEAR; null reads as MONTH
	 * @param financialYear the opening calendar year, required by YEAR and ignored by the rest
	 */
	@Transactional(readOnly = true)
	public PeriodSummary periodSummary(String period, Integer financialYear) {
		LocalDate today = LocalDate.now(TEMPLE_ZONE);
		LedgerPeriod window = LedgerPeriod.resolve(period, financialYear, today);

		Map<String, BigDecimal> current = totalsByCategory(window.from(), window.to());
		Map<String, BigDecimal> prior = totalsByCategory(window.previousFrom(), window.previousTo());

		// Every category that had money in either window, so a kind of giving that has dried up since
		// last year still shows its fall rather than dropping off the screen and looking like nothing
		// happened. A grouped total only names the categories it found rows for, hence the union.
		Map<String, CategoryComparison> byCategory = new LinkedHashMap<>();
		Set<String> categories = new LinkedHashSet<>(current.keySet());
		categories.addAll(prior.keySet());
		for (String category : categories) {
			BigDecimal now = current.getOrDefault(category, BigDecimal.ZERO);
			BigDecimal then = prior.getOrDefault(category, BigDecimal.ZERO);
			byCategory.put(category, new CategoryComparison(now, then, changePercent(now, then)));
		}

		LocalDate earliest = earliestGift();
		// A percentage needs something to have been there to grow from. A temple whose first gift was
		// given after the prior window had already closed did not receive less last year — it was not
		// keeping books last year, and the screen has to say so rather than report a fall of 100%.
		boolean hasPriorYear = earliest != null && !earliest.isAfter(window.previousTo());

		return new PeriodSummary(window, hasPriorYear, byCategory, financialYearsWithGifts(earliest, today));
	}

	/** Every gift matching this donation's donor identity (E7-S7): account, else PAN, else exact contact. */
	@Transactional(readOnly = true)
	public List<LedgerRow> donorHistory(UUID donationId) {
		Map<String, Object> d;
		try {
			d = jdbc.queryForMap("""
					SELECT donor_account_user_id, pan_fingerprint, donor_phone, donor_email
					FROM donations WHERE id = ?
					""", donationId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("donationId", donationId), e);
		}
		if (d.get("donor_account_user_id") != null) {
			return jdbc.query(SELECT + " WHERE d.donor_account_user_id = ? ORDER BY d.created_at DESC",
					MAPPER, d.get("donor_account_user_id"));
		}
		if (d.get("pan_fingerprint") != null) {
			return jdbc.query(SELECT + " WHERE d.pan_fingerprint = ? ORDER BY d.created_at DESC",
					MAPPER, d.get("pan_fingerprint"));
		}
		if (d.get("donor_phone") != null || d.get("donor_email") != null) {
			return jdbc.query(SELECT + """
					 WHERE d.is_anonymous = false AND d.donor_phone IS NOT DISTINCT FROM ?
					   AND d.donor_email IS NOT DISTINCT FROM ? ORDER BY d.created_at DESC
					""", MAPPER, d.get("donor_phone"), d.get("donor_email"));
		}
		return List.of();
	}

	/** CSV of the same rows the on-screen filters show — the accountant's real interface. */
	@Transactional(readOnly = true)
	public String exportCsv(LocalDate from, LocalDate to, String category, String status) {
		StringBuilder csv = new StringBuilder("Date,Category,Donor,Amount,Currency,Mode,Reference,Status,Linked\n");
		for (LedgerRow r : ledger(from, to, category, status)) {
			csv.append(r.donatedOn()).append(',')
					.append(r.category()).append(',')
					.append(csv(r.donorDisplay())).append(',')
					.append(r.amountInr() == null ? "" : r.amountInr().toPlainString()).append(',')
					.append(nullTo(r.currency())).append(',')
					.append(nullTo(r.paymentMode())).append(',')
					.append(nullTo(r.providerRef())).append(',')
					.append(r.status()).append(',')
					.append(csv(r.linkedTo())).append('\n');
		}
		return csv.toString();
	}

	// ---------------------------------------------------------------------

	private Map<String, BigDecimal> totalsByCategory(LocalDate from, LocalDate to) {
		Map<String, BigDecimal> totals = new LinkedHashMap<>();
		jdbc.query("""
				SELECT %s AS category,
					   COALESCE(SUM(COALESCE(d.amount_inr, d.estimated_value_inr)), 0) AS total
				FROM donations d WHERE d.status = 'COMPLETED' AND d.donated_on BETWEEN ? AND ?
				GROUP BY 1
				""".formatted(CATEGORY_CASE),
				rs -> { totals.put(rs.getString("category"), rs.getBigDecimal("total")); }, from, to);
		return totals;
	}

	/**
	 * The change from the prior window to this one, as a whole percent, or null where no percentage
	 * can be justified.
	 *
	 * <p>Rounded to whole percent because the second decimal place of a comparison between two
	 * part-years is false precision — nobody acts differently on 17.6% than on 18%. Null when the
	 * prior window came to nothing: there is no denominator, and a screen that answers that with
	 * "up ∞%" or silently with "up 100%" has invented a baseline that was never there.
	 */
	private static Integer changePercent(BigDecimal now, BigDecimal then) {
		if (then == null || then.signum() == 0) {
			return null;
		}
		return now.subtract(then)
				.multiply(ONE_HUNDRED)
				.divide(then, 0, RoundingMode.HALF_UP)
				.intValue();
	}

	/**
	 * The day of the temple's first completed gift, or null if it has none. RLS scopes this to the
	 * one tenant, as it does every other query here, which is why there is no tenant clause.
	 */
	private LocalDate earliestGift() {
		return jdbc.queryForObject(
				"SELECT MIN(d.donated_on) FROM donations d WHERE d.status = 'COMPLETED'", LocalDate.class);
	}

	/**
	 * The financial years the period picker may offer, newest first.
	 *
	 * <p>From the year of the temple's first gift up to the one it is living in, rather than a fixed
	 * span of recent years: a temple that joined last month has no use for a list reaching back to
	 * 2019, and one that has been running for a decade should not have its earlier books hidden
	 * behind an arbitrary cut-off. The run is contiguous even where a year saw nothing, because
	 * proving a year was empty is a thing an accountant is sometimes asked to do, and a year missing
	 * from the list cannot be proved either way.
	 */
	private static List<Integer> financialYearsWithGifts(LocalDate earliest, LocalDate today) {
		int current = LedgerPeriod.financialYearStart(today).getYear();
		int first = earliest == null ? current : LedgerPeriod.financialYearStart(earliest).getYear();
		List<Integer> years = new ArrayList<>();
		for (int year = current; year >= first; year--) {
			years.add(year);
		}
		return years;
	}

	/**
	 * The filter must select exactly the rows the Type column labels with that word, or the accountant
	 * is reading two different classifications on one screen. Each arm is therefore the negation of
	 * everything above it in {@link #CATEGORY_CASE}.
	 */
	private static String categoryClause(String category) {
		if (category == null) {
			return "";
		}
		return switch (category) {
			case "IN_KIND" -> " AND d.type = 'IN_KIND'";
			case "RECURRING" -> " AND d.type = 'RECURRING'";
			case "WISHLIST" -> " AND d.wishlist_item_id IS NOT NULL AND d.type NOT IN ('IN_KIND', 'RECURRING')";
			case "MANUAL" -> " AND d.provider IS NULL AND d.type = 'ONE_TIME' AND d.wishlist_item_id IS NULL";
			case "ONE_TIME" -> " AND d.provider IS NOT NULL AND d.type = 'ONE_TIME' AND d.wishlist_item_id IS NULL";
			default -> "";
		};
	}

	private static String csv(String value) {
		if (value == null) {
			return "";
		}
		String escaped = value.replace("\"", "\"\"");
		return escaped.contains(",") || escaped.contains("\"") ? "\"" + escaped + "\"" : escaped;
	}

	private static String nullTo(String s) {
		return s == null ? "" : s;
	}

	/**
	 * What kind of gift this was, in one expression so the summary cards and the rows beneath them can
	 * never drift apart. MANUAL is last before the fallback and needs no column of its own: a payment
	 * gateway always stamps {@code provider}, so its absence means a person sat down and wrote the row —
	 * cash in the hundi, most often. Goods keep their own IN_KIND label; they are hand-recorded too, but
	 * "what was given" is the more useful thing to say about them.
	 */
	private static final String CATEGORY_CASE = """
			CASE WHEN d.type = 'IN_KIND' THEN 'IN_KIND' WHEN d.type = 'RECURRING' THEN 'RECURRING'
				 WHEN d.wishlist_item_id IS NOT NULL THEN 'WISHLIST'
				 WHEN d.provider IS NULL THEN 'MANUAL' ELSE 'ONE_TIME' END""";

	private static final String SELECT = """
			SELECT d.id, d.donated_on, %s AS category,
				   d.is_anonymous, d.donor_name,
				   COALESCE(d.amount_inr, d.estimated_value_inr) AS amount, d.currency, d.payment_mode,
				   COALESCE(d.provider_payment_id, d.provider_order_id) AS provider_ref, d.status,
				   wi.title AS wishlist_title, d.recurring_plan_id
			FROM donations d LEFT JOIN wishlist_items wi ON wi.id = d.wishlist_item_id
			""".formatted(CATEGORY_CASE);

	/**
	 * What the gift is attached to. Only two things can be earmarked — a wish-list item and a
	 * recurring plan — and a gift attached to neither used to read as a blank, which said nothing
	 * about a row that means something quite definite: money the kitchen may spend on whatever it
	 * needs next, which is what the donate page promises a general gift does. So the last arm names
	 * that rather than leaving the column empty. A blank belongs to a missing fact, and this one is
	 * not missing.
	 */
	private static final String GENERAL = "General kitchen";

	private static final RowMapper<LedgerRow> MAPPER = (rs, n) -> {
		boolean anon = rs.getBoolean("is_anonymous");
		String category = rs.getString("category");
		String linked = rs.getString("wishlist_title") != null ? "Wish list: " + rs.getString("wishlist_title")
				: rs.getObject("recurring_plan_id") != null ? "Recurring plan"
				: "IN_KIND".equals(category) ? "In-kind intake" : GENERAL;
		return new LedgerRow(
				rs.getObject("id", UUID.class), rs.getObject("donated_on", LocalDate.class), category,
				anon ? "Anonymous" : (rs.getString("donor_name") == null ? "—" : rs.getString("donor_name")),
				rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("payment_mode"),
				rs.getString("provider_ref"), rs.getString("status"), linked);
	};
}

package org.iskcon.kms.meal;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The kinds of meal a temple cooks (E4-S7), and when each is due.
 *
 * <p>Seeded on provisioning: Breakfast, Lunch and Dinner with the temple's usual times, and Deity
 * offering, Catering order and Outside event with none. That absence is the design, not an omission —
 * an everyday meal has a known hour, an occasional one does not, and a guessed time for a catering
 * order is worse than being asked for one.
 *
 * <p>Flags say what a kind needs beyond a recipe: {@code needsClient} for food someone outside the
 * temple asked and is paying for, {@code needsVenue} for food that leaves the building, and
 * {@code needsOccasion} for a feast, which must name the festival it is for (item 26). They are
 * flags rather than known names so a temple can add kinds of its own without the application having
 * to recognise them.
 *
 * <p>A feast being a kind rather than a day type is the point of it. A kind says when in the day a
 * meal happens and what it needs; a day type says what sort of day it is, derived and never chosen.
 * On Janmashtami the temple serves an ordinary breakfast and then a feast — one day, two meals, one
 * of them the big one — and only a per-meal fact can say which.
 */
@Service
public class MealKindService {

	private final JdbcTemplate jdbc;

	public MealKindService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<MealKindView> list() {
		return jdbc.query(SELECT + " ORDER BY sort_order, name", MAPPER);
	}

	/** One kind by name, as a meal plan refers to it. Empty when the temple has no such kind. */
	@Transactional(readOnly = true)
	public Optional<MealKindView> byName(String name) {
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		return jdbc.query(SELECT + " WHERE lower(name) = lower(?)", MAPPER, name.trim())
				.stream().findFirst();
	}

	/** The kind a plan names, or a refusal naming what the temple actually has. */
	@Transactional(readOnly = true)
	public MealKindView require(String name) {
		return byName(name).orElseThrow(() -> new ApplicationException(
				ErrorCode.MEAL_KIND_UNKNOWN,
				Map.of("mealKind", String.valueOf(name), "known", list().stream().map(MealKindView::name).toList())));
	}

	@Transactional
	public UUID create(CreateMealKindRequest request) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO meal_kinds (
						id, tenant_id, name, sort_order, default_ready_time, needs_client, needs_venue,
						needs_purpose, needs_occasion)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
					""", id, request.name().trim(), request.sortOrder(), request.defaultReadyTime(),
					request.needsClient(), request.needsVenue(), request.needsPurpose(),
					request.needsOccasion());
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(
					ErrorCode.MEAL_KIND_ALREADY_EXISTS, Map.of("name", request.name()), e);
		}
		return id;
	}

	/**
	 * Changes a kind's name, order and — the point of the screen — the time its meals are due. A
	 * default of null is meaningful: it makes the kind always ask.
	 */
	@Transactional
	public void update(UUID id, CreateMealKindRequest request) {
		int rows = jdbc.update("""
				UPDATE meal_kinds
				SET name = ?, sort_order = ?, default_ready_time = ?, needs_client = ?, needs_venue = ?,
					needs_purpose = ?, needs_occasion = ?
				WHERE id = ?
				""", request.name().trim(), request.sortOrder(), request.defaultReadyTime(),
				request.needsClient(), request.needsVenue(), request.needsPurpose(),
				request.needsOccasion(), id);

		if (rows == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealKindId", id));
		}
	}

	@Transactional
	public void delete(UUID id) {
		// A plan records its kind by name, not by reference, so removing a kind never breaks the
		// meals already planned under it — they keep reading as what they were.
		jdbc.update("DELETE FROM meal_kinds WHERE id = ?", id);
	}

	@Transactional
	public void seedForCurrentTenant() {
		// The last two are ordered Outside event then Catering order (A7). They are ordered by
		// sort_order alone, so this list and V64's per-tenant backfill are the whole change — one for
		// temples provisioned from here on, one for those that already exist.
		// Festival feast sits at 35 so the picker reads: the three everyday meals, the feast, then the
		// kinds that are not a sitting at all. Its ready time is null like the other occasional kinds
		// — a feast is never at the same hour twice, so it always asks (item 26).
		Object[][] defaults = {
			{"Breakfast", 10, LocalTime.of(7, 30), false, false, false, false},
			{"Lunch", 20, LocalTime.of(12, 0), false, false, false, false},
			{"Dinner", 30, LocalTime.of(19, 30), false, false, false, false},
			{"Festival feast", 35, null, false, false, false, true},
			{"Deity Offering", 40, null, false, false, false, false},
			{"Outside event", 50, null, false, true, true, false},
			{"Catering order", 60, null, true, true, false, false},
		};
		for (Object[] k : defaults) {
			jdbc.update("""
					INSERT INTO meal_kinds (
						tenant_id, name, sort_order, default_ready_time, needs_client, needs_venue,
						needs_purpose, needs_occasion)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (tenant_id, lower(name)) DO NOTHING
					""", k[0], k[1], k[2], k[3], k[4], k[5], k[6]);
		}
	}

	private static final String SELECT = """
			SELECT id, name, sort_order, default_ready_time, needs_client, needs_venue, needs_purpose,
				   needs_occasion
			FROM meal_kinds""";

	private static final RowMapper<MealKindView> MAPPER = (rs, n) -> new MealKindView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getInt("sort_order"),
			rs.getObject("default_ready_time", LocalTime.class),
			rs.getBoolean("needs_client"),
			rs.getBoolean("needs_venue"),
			rs.getBoolean("needs_purpose"),
			rs.getBoolean("needs_occasion"));
}

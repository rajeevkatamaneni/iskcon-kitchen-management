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
 * <p>Two flags say what a kind needs beyond a recipe: {@code needsClient} for food someone outside
 * the temple asked and is paying for, {@code needsVenue} for food that leaves the building. They are
 * flags rather than known names so a temple can add kinds of its own without the application having
 * to recognise them.
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
						id, tenant_id, name, sort_order, default_ready_time, needs_client, needs_venue)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""", id, request.name().trim(), request.sortOrder(), request.defaultReadyTime(),
					request.needsClient(), request.needsVenue());
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
				SET name = ?, sort_order = ?, default_ready_time = ?, needs_client = ?, needs_venue = ?
				WHERE id = ?
				""", request.name().trim(), request.sortOrder(), request.defaultReadyTime(),
				request.needsClient(), request.needsVenue(), id);

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
		Object[][] defaults = {
			{"Breakfast", 10, LocalTime.of(7, 30), false, false},
			{"Lunch", 20, LocalTime.of(12, 0), false, false},
			{"Dinner", 30, LocalTime.of(19, 30), false, false},
			{"Deity Offering", 40, null, false, false},
			{"Catering order", 50, null, true, true},
			{"Outside event", 60, null, false, true},
		};
		for (Object[] k : defaults) {
			jdbc.update("""
					INSERT INTO meal_kinds (
						tenant_id, name, sort_order, default_ready_time, needs_client, needs_venue)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					ON CONFLICT (tenant_id, lower(name)) DO NOTHING
					""", k[0], k[1], k[2], k[3], k[4]);
		}
	}

	private static final String SELECT =
			"SELECT id, name, sort_order, default_ready_time, needs_client, needs_venue FROM meal_kinds";

	private static final RowMapper<MealKindView> MAPPER = (rs, n) -> new MealKindView(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			rs.getInt("sort_order"),
			rs.getObject("default_ready_time", LocalTime.class),
			rs.getBoolean("needs_client"),
			rs.getBoolean("needs_venue"));
}

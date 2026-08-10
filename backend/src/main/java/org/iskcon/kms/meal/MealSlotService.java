package org.iskcon.kms.meal;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's configurable meal slots (E4-S4). Seeded on provisioning with Lunch, Dinner, and a
 * Deity Offering slot; the temple can add or remove its own. Meal plans reference a slot by name.
 */
@Service
public class MealSlotService {

	private final JdbcTemplate jdbc;

	public MealSlotService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<MealSlotView> list() {
		return jdbc.query("SELECT id, name, sort_order FROM meal_slots ORDER BY sort_order, name", MAPPER);
	}

	/** The slot names, for validating a meal plan's slot. */
	@Transactional(readOnly = true)
	public Set<String> names() {
		return jdbc.query("SELECT name FROM meal_slots", (rs, n) -> rs.getString("name"))
				.stream().collect(Collectors.toSet());
	}

	@Transactional
	public UUID create(CreateMealSlotRequest request) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("""
					INSERT INTO meal_slots (id, tenant_id, name, sort_order)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
					""", id, request.name().trim(), request.sortOrder());
		} catch (DuplicateKeyException e) {
			throw new ApplicationException(ErrorCode.MEAL_SLOT_ALREADY_EXISTS, java.util.Map.of("name", request.name()), e);
		}
		return id;
	}

	@Transactional
	public void delete(UUID id) {
		// Slot is a plain label on meal plans (no FK), so removing it never breaks existing plans.
		jdbc.update("DELETE FROM meal_slots WHERE id = ?", id);
	}

	@Transactional
	public void seedForCurrentTenant() {
		String[][] defaults = {{"Lunch", "1"}, {"Dinner", "2"}, {"Deity Offering", "0"}};
		for (String[] s : defaults) {
			jdbc.update("""
					INSERT INTO meal_slots (tenant_id, name, sort_order)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
					ON CONFLICT (tenant_id, lower(name)) DO NOTHING
					""", s[0], Integer.parseInt(s[1]));
		}
	}

	private static final RowMapper<MealSlotView> MAPPER = (rs, n) ->
			new MealSlotView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("sort_order"));
}

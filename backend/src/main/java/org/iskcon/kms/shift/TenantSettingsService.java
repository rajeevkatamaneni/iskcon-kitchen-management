package org.iskcon.kms.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant configuration (E6-S7): the daily cap on shift broadcasts, the language the temple
 * works in, the colours it works in, and how much notice it wants before a batch expires or a
 * vendor's agreement runs out. A tenant with no row yet uses the default, so a setting reads
 * correctly before it has ever been changed.
 *
 * <p>The language lives on {@code tenants.locale} rather than here, because it predates this table
 * and is the only statement of language a temple makes anywhere in the schema. It has been
 * unwritable since V1 — every temple carried the {@code en-IN} the column defaulted to — which was
 * fine while nothing read it and stopped being fine when the job card started printing in "the
 * temple's own language" (build brief §3). Adding a second "kitchen language" beside it was the
 * alternative and was rejected: two places to say the same thing is two places to keep in step.
 */
@Service
public class TenantSettingsService {

	static final int DEFAULT_BROADCAST_DAILY_LIMIT = 3;

	/** What the stock screens have always used, and what they keep using (V85). */
	static final int DEFAULT_STOCK_EXPIRY_WARNING_DAYS = 7;

	/**
	 * A month's notice that a supplier agreement is running out (V85).
	 *
	 * <p>Deliberately not the seven days the stock screens use, which is what it was until this
	 * setting existed. Seven days is enough to cook a sack of flour before it turns; it is not
	 * enough to renegotiate an agreement, price the alternatives, or take it to a committee.
	 */
	static final int DEFAULT_CONTRACT_END_WARNING_DAYS = 30;

	/**
	 * A month's notice that a machine is coming up for service (V87, E3-S10 D5).
	 *
	 * <p>Thirty rather than the seven the stock screens use, for the reason the contract horizon is
	 * thirty. Booking an engineer is closer to renegotiating an agreement than to cooking a sack of
	 * flour before it turns: the temple has to find the firm, agree a date, and have somebody there
	 * when they come. Red on the morning a service falls due is a fire alarm — this is the horizon
	 * that defines the amber state, and the amber state is the one that does the work.
	 */
	static final int DEFAULT_EQUIPMENT_SERVICE_WARNING_DAYS = 30;

	/**
	 * What a warning horizon is allowed to be, in days.
	 *
	 * <p>Below one it cannot warn in advance: zero fires on the morning the thing has already
	 * expired, and a negative horizon points at the past. Above a year it cannot warn usefully
	 * either — a temple stocks almost nothing that keeps longer than that and supplier agreements
	 * are annual, so every batch and every vendor would carry the badge from the day it was
	 * entered. The same bounds for both, because there is no case for two.
	 */
	static final int MIN_WARNING_DAYS = 1;

	static final int MAX_WARNING_DAYS = 365;

	/** What V1 gives every temple, and what a temple that has never chosen still works in. */
	static final String DEFAULT_LOCALE = "en-IN";

	/** An ISO 639-1 code. The region is ours to add — every temple in this release is in India. */
	private static final Pattern LANGUAGE_TAG = Pattern.compile("^[A-Za-z]{2}$");

	/**
	 * The shape of a theme's identifier, and only the shape.
	 *
	 * <p>Whether a given theme exists is not a question this side can answer, and deliberately so:
	 * knowing would mean holding a second copy of the catalogue in step with the one in the
	 * frontend. What this refuses is a value that could not be a theme at all, which is the part
	 * that is worth refusing at the boundary rather than storing and puzzling over later.
	 */
	private static final Pattern THEME_ID = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

	/**
	 * The shape of a menu group's identifier and of a menu item's, which are the same shape.
	 *
	 * <p>Shape only, for the reason spelled out on {@link #THEME_ID} above: the destinations that
	 * exist live in {@code frontend/lib/nav.ts}, beside the pages they point at, and a copy of that
	 * list on this side would be a copy that drifts. So an item this application has never heard of
	 * is stored without complaint — the merge in the browser passes over it — while something that
	 * could not be an identifier at all is refused at the boundary.
	 *
	 * <p>Hyphens are allowed inside and at the end, unlike a theme's, because a group's id is minted
	 * by the browser from whatever the temple types and a trailing hyphen is an ordinary outcome of
	 * that. What matters is that it starts with a letter or a digit and holds nothing that would
	 * need escaping.
	 */
	private static final Pattern MENU_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

	/** The one arrangement shape this release writes and reads. See V154. */
	static final int MENU_LAYOUT_VERSION = 1;

	/** Long enough for any id the menu has ever used, short enough that nothing long lands here. */
	static final int MAX_MENU_ID_LENGTH = 64;

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public TenantSettingsService(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(readOnly = true)
	public int volunteerBroadcastDailyLimit() {
		Integer limit = jdbc.query("SELECT volunteer_broadcast_daily_limit FROM tenant_settings",
				(rs, n) -> rs.getInt("volunteer_broadcast_daily_limit")).stream().findFirst().orElse(null);
		return limit == null ? DEFAULT_BROADCAST_DAILY_LIMIT : limit;
	}

	/**
	 * The temple's own language as a BCP-47 tag ({@code en-IN}, {@code kn-IN}). Never null: V1 gives
	 * the column a NOT NULL default, and a temple that has never chosen works in English.
	 */
	@Transactional(readOnly = true)
	public String locale() {
		return jdbc.query("""
				SELECT locale FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("locale")).stream().findFirst().orElse(DEFAULT_LOCALE);
	}

	/**
	 * Sets the temple's language. Stored as a region-qualified tag so the column keeps the shape it
	 * has always had; the callers that want a language ask for the subtag before the dash.
	 */
	@Transactional
	public void setLanguage(String language) {
		if (language == null || language.isBlank() || !LANGUAGE_TAG.matcher(language).matches()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "language", "reason", "a language is a two-letter code such as kn or hi"));
		}
		jdbc.update("""
				UPDATE tenants SET locale = ?, updated_at = now()
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", language.toLowerCase() + "-IN");
	}

	/**
	 * Which colour scheme this temple works in, or null if it has never chosen.
	 *
	 * <p>Opaque here. The themes themselves live in {@code frontend/lib/themes.ts}, beside the
	 * interface they colour, and this application has no opinion about which of them exist — an
	 * identifier that no longer matches one is resolved to the default on the other side rather
	 * than refused on this one. See V72 for why they are not a table.
	 *
	 * <p>Null for a platform operator too, and without a special case: they carry no
	 * {@code app.tenant_id}, so the policy on this table matches nothing.
	 *
	 * <p>Read off the list rather than through {@code stream().findFirst()}, and that is not a
	 * style choice. {@code findFirst} builds an {@code Optional} of the first element, and
	 * {@code Optional.of(null)} throws — so a temple with a settings row whose theme is still null
	 * took down every request that reached here. Which was all of them: {@code /whoami} calls this
	 * on every session.
	 *
	 * <p>It survived the tests because the case has three states and the obvious two were covered.
	 * No row at all gives an empty list and is fine. A row naming a theme is fine. A row that
	 * exists because the temple once set some other preference, with the theme still null, is the
	 * one that breaks — and it is the state every temple that has ever touched settings is in.
	 * Shipped to live 2026-08-30, found within the hour.
	 */
	@Transactional(readOnly = true)
	public String themeId() {
		List<String> chosen = jdbc.query("SELECT selected_theme_id FROM tenant_settings",
				(rs, n) -> rs.getString("selected_theme_id"));
		return chosen.isEmpty() ? null : chosen.get(0);
	}

	/**
	 * Records the temple's choice, for everybody who serves there.
	 *
	 * <p>Deliberately not audited, which is worth saying because a change everybody in the temple
	 * can see looks like something that should be. Nothing else on the settings screen is audited
	 * either — not the language, not the broadcast cap, not the payment provider — and auditing one
	 * preference while three sit unaudited beside it would read as a decision about this one rather
	 * than the accident it would be. If settings become auditable, they become auditable together.
	 */
	@Transactional
	public void setThemeId(String themeId) {
		if (themeId == null || !THEME_ID.matcher(themeId).matches()) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "themeId", "reason", "a theme is named in lower case with hyphens"));
		}
		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, selected_theme_id)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?)
				ON CONFLICT (tenant_id)
				DO UPDATE SET selected_theme_id = EXCLUDED.selected_theme_id, updated_at = now()
				""", themeId);
	}

	/**
	 * How this temple has arranged its own left-hand menu, as the JSON object stored in V154, or
	 * null where it has never arranged one.
	 *
	 * <p>Returned as text rather than as a parsed model, and that is deliberate. Nothing on this
	 * side reads inside an arrangement: it is written by one endpoint, handed back on the session,
	 * and merged with the standard menu in the browser, which is the only place that knows what
	 * destinations exist. Parsing it here would mean a second model of a document this application
	 * has no opinion about — see {@link #MENU_ID}. The caller that puts it on the wire parses it
	 * there, because the client's type says {@code MenuLayout | null} and a JSON string in that slot
	 * would be a lie the type system cannot see.
	 *
	 * <p>Null is not the same as "arranged it to look standard", which is why the column is nullable
	 * and why reset writes null rather than today's standard arrangement. V154 §2 has the argument.
	 *
	 * <p>Null for a platform operator too, and without a special case: they carry no
	 * {@code app.tenant_id}, so the policy on this table matches nothing.
	 *
	 * <p>Read off the list rather than through {@code stream().findFirst()}, for the reason spelled
	 * out at length on {@link #themeId()}: this column is nullable, a settings row written for some
	 * other preference holds a null here, and {@code Optional.of(null)} throws. That is the exact
	 * shape that took the application down on 2026-08-30.
	 */
	@Transactional(readOnly = true)
	public String menuLayout() {
		// ::text so the driver hands back a String rather than a PGobject the caller would have to
		// know about.
		List<String> arranged = jdbc.query("SELECT menu_layout::text FROM tenant_settings",
				(rs, n) -> rs.getString(1));
		return arranged.isEmpty() ? null : arranged.get(0);
	}

	/**
	 * Records how the temple has arranged its menu, for everybody who serves there.
	 *
	 * <p>Takes the arrangement as JSON and checks it here, before the write, rather than trusting
	 * the record the controller bound. The two are not the same thing: what is checked is what is
	 * about to be stored, and what is stored is rebuilt from the check — version, groups, and on
	 * each group an id, a title and its items, in the order they were sent and nothing else. So a
	 * document that reached this method by any route is stored in one canonical shape.
	 *
	 * <p>Not audited, for the reason set out on {@link #setThemeId}: nothing else on the settings
	 * screen is, and auditing one preference while the rest sit unaudited beside it would read as a
	 * decision about this one rather than the accident it would be.
	 */
	@Transactional
	public void setMenuLayout(String arrangement) {
		String checked = checkedArrangement(arrangement);
		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, menu_layout)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?::jsonb)
				ON CONFLICT (tenant_id)
				DO UPDATE SET menu_layout = EXCLUDED.menu_layout, updated_at = now()
				""", checked);
	}

	/**
	 * Puts the standard menu back.
	 *
	 * <p>A plain UPDATE, not an upsert, and the difference is the honest answer rather than a saving:
	 * a temple that has never had a settings row has nothing to clear, and writing a row to record
	 * that it has stopped doing something it never started would be a row that says nothing. The
	 * statement matches no rows there and the endpoint still answers 204 — reset is never a 404,
	 * because "it is already standard" is the outcome the person asked for.
	 *
	 * <p>NULL rather than today's standard arrangement written out, which is the whole of V154 §2:
	 * a temple that resets is asking to follow the standard menu from here on, including the parts
	 * of it that have not been decided yet.
	 */
	@Transactional
	public void clearMenuLayout() {
		jdbc.update("""
				UPDATE tenant_settings SET menu_layout = NULL, updated_at = now()
				WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""");
	}

	/**
	 * Reads an arrangement, refuses the ones that could not be acted on, and gives back the
	 * canonical text to store.
	 *
	 * <p>Two refusals and no more, because there are only two things a person can be told here that
	 * are not already a field error on the form. Group and item counts, a blank heading, a heading
	 * too long to show — those come back from bean validation against the box that holds them, which
	 * is a better answer than a code. What is left is the arrangement as a whole: an id that could
	 * not be an id, two groups claiming the same id, a document that cannot be read
	 * ({@code KMS-400189}); and one destination placed twice, which is a real arrangement saying
	 * something contradictory ({@code KMS-400190}).
	 *
	 * <p>The repeated id goes into the exception's context, which reaches the log and not the
	 * screen. The message the person reads says to take the repeated item out of one of the groups,
	 * and they are looking at the arrangement while they read it.
	 */
	private String checkedArrangement(String arrangement) {
		JsonNode root = readOrRefuse(arrangement);
		if (!root.isObject()) {
			throw notUnderstood("the arrangement is not an object");
		}
		if (!root.path("version").isInt() || root.path("version").asInt() != MENU_LAYOUT_VERSION) {
			throw notUnderstood("version is not " + MENU_LAYOUT_VERSION);
		}
		if (!root.path("groups").isArray()) {
			throw notUnderstood("groups is not a list");
		}

		ObjectNode canonical = json.createObjectNode();
		canonical.put("version", MENU_LAYOUT_VERSION);
		ArrayNode groups = canonical.putArray("groups");

		Set<String> groupIds = new HashSet<>();
		Set<String> placed = new HashSet<>();
		for (JsonNode group : root.path("groups")) {
			if (!group.isObject()) {
				throw notUnderstood("a group is not an object");
			}
			String id = requireId(group.path("id"), "group id");
			if (!groupIds.add(id)) {
				throw notUnderstood("two groups share the id " + id);
			}

			ObjectNode written = groups.addObject();
			written.put("id", id);
			JsonNode title = group.path("title");
			if (title.isNull() || title.isMissingNode()) {
				written.putNull("title");
			}
			else if (title.isTextual()) {
				written.put("title", title.asText().trim());
			}
			else {
				throw notUnderstood("a group heading is neither text nor absent");
			}

			if (!group.path("items").isArray()) {
				throw notUnderstood("a group's items are not a list");
			}
			ArrayNode items = written.putArray("items");
			for (JsonNode item : group.path("items")) {
				String itemId = requireId(item, "item id");
				if (!placed.add(itemId)) {
					// Not "the last one wins". Which copy the temple meant is a question only the
					// person arranging can answer, and quietly dropping one would move a destination
					// somebody had just placed on purpose.
					throw new ApplicationException(ErrorCode.MENU_ITEM_IN_TWO_GROUPS,
							Map.of("item", itemId));
				}
				items.add(itemId);
			}
		}

		try {
			return json.writeValueAsString(canonical);
		}
		catch (JsonProcessingException e) {
			// A tree this method built itself, so this cannot happen for a reason the caller could
			// act on; it is here because the checked exception is.
			throw new ApplicationException(ErrorCode.MENU_LAYOUT_NOT_UNDERSTOOD,
					Map.of("reason", "the checked arrangement would not serialise"), e);
		}
	}

	private JsonNode readOrRefuse(String arrangement) {
		if (arrangement == null || arrangement.isBlank()) {
			throw notUnderstood("the arrangement is empty");
		}
		try {
			return json.readTree(arrangement);
		}
		catch (JsonProcessingException e) {
			throw new ApplicationException(ErrorCode.MENU_LAYOUT_NOT_UNDERSTOOD,
					Map.of("reason", "the arrangement could not be read"), e);
		}
	}

	/** Shape and length only — never whether the thing it names exists. See {@link #MENU_ID}. */
	private String requireId(JsonNode node, String what) {
		if (!node.isTextual()) {
			throw notUnderstood(what + " is not text");
		}
		String id = node.asText();
		if (id.isBlank() || id.length() > MAX_MENU_ID_LENGTH || !MENU_ID.matcher(id).matches()) {
			throw notUnderstood(what + " is not an identifier");
		}
		return id;
	}

	private ApplicationException notUnderstood(String reason) {
		return new ApplicationException(ErrorCode.MENU_LAYOUT_NOT_UNDERSTOOD, Map.of("reason", reason));
	}

	/**
	 * How many days ahead a batch nearing its use-by date is badged on the stock screens (V85).
	 *
	 * <p>Seven for a temple that has never chosen, which is what the constant this replaced said.
	 */
	@Transactional(readOnly = true)
	public int stockExpiryWarningDays() {
		return horizon("stock_expiry_warning_days", DEFAULT_STOCK_EXPIRY_WARNING_DAYS);
	}

	/**
	 * How many days ahead a vendor whose agreement is running out is badged (V85).
	 *
	 * <p>Thirty for a temple that has never chosen. This is the one value the change to settings
	 * actually moved: it was seven, borrowed from stock expiry, and seven days is not enough notice
	 * to renegotiate a contract.
	 */
	@Transactional(readOnly = true)
	public int contractEndWarningDays() {
		return horizon("contract_end_warning_days", DEFAULT_CONTRACT_END_WARNING_DAYS);
	}

	/**
	 * How many days ahead a machine approaching its next service is badged amber (V87).
	 *
	 * <p>Thirty for a temple that has never chosen. The third of these, and it joins the other two
	 * here rather than becoming a constant in the equipment code, because that is precisely the
	 * mistake V85 existed to undo.
	 */
	@Transactional(readOnly = true)
	public int equipmentServiceWarningDays() {
		return horizon("equipment_service_warning_days", DEFAULT_EQUIPMENT_SERVICE_WARNING_DAYS);
	}

	/**
	 * Sets all three horizons at once, because they are one decision.
	 *
	 * <p>They are saved together rather than one endpoint each — the pattern every other setting
	 * here follows — precisely because this exists to stop them drifting apart. They are presented as
	 * one section on the settings screen, saved by one button, and a caller cannot move one without
	 * stating the others.
	 *
	 * <p><strong>All three, and the servicing one is no longer optional.</strong> It was nullable for
	 * exactly one reason (E3-S10 D5): the settings form was built for two horizons, and a plain
	 * {@code int} would have had every save from that form silently reset the third to a figure it
	 * was not showing. E3-S11 gave it a control and the screen now posts all three, so the transition
	 * is over and the leniency goes with it. A body that omits one is refused rather than half
	 * applied — the alternative, keeping the null branch for its own sake, is a way for a future
	 * caller to reset a setting by forgetting it.
	 *
	 * <p>The bounds are checked here as well as by the request record and by the database. The
	 * database is the one that cannot be got round; this is the one that says why in words a
	 * temple administrator can act on.
	 */
	@Transactional
	public void setWarningHorizons(int stockExpiryDays, int contractEndDays, int equipmentServiceDays) {
		requireHorizon("stockExpiryWarningDays", stockExpiryDays);
		requireHorizon("contractEndWarningDays", contractEndDays);
		requireHorizon("equipmentServiceWarningDays", equipmentServiceDays);
		jdbc.update("""
				INSERT INTO tenant_settings (
					tenant_id, stock_expiry_warning_days, contract_end_warning_days,
					equipment_service_warning_days)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				ON CONFLICT (tenant_id)
				DO UPDATE SET stock_expiry_warning_days = EXCLUDED.stock_expiry_warning_days,
					contract_end_warning_days = EXCLUDED.contract_end_warning_days,
					equipment_service_warning_days = EXCLUDED.equipment_service_warning_days,
					updated_at = now()
				""", stockExpiryDays, contractEndDays, equipmentServiceDays);
	}

	/**
	 * One horizon column, or the default when this temple has no settings row yet.
	 *
	 * <p>Read off the list rather than through {@code stream().findFirst()}, for the reason spelled
	 * out on {@link #themeId()}: the column is NOT NULL, but the habit is what keeps the next
	 * nullable one from taking the site down.
	 *
	 * <p>The column name is a literal from this class and never from a caller — there is no
	 * parameter shape that would let it be anything else.
	 */
	private int horizon(String column, int fallback) {
		List<Integer> chosen = jdbc.query(
				"SELECT " + column + " AS days FROM tenant_settings", (rs, n) -> rs.getInt("days"));
		return chosen.isEmpty() ? fallback : chosen.get(0);
	}

	private void requireHorizon(String field, int days) {
		if (days < MIN_WARNING_DAYS || days > MAX_WARNING_DAYS) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
					"field", field,
					"reason", "a warning is between " + MIN_WARNING_DAYS + " and "
							+ MAX_WARNING_DAYS + " days ahead"));
		}
	}

	@Transactional
	public void setVolunteerBroadcastDailyLimit(int limit) {
		jdbc.update("""
				INSERT INTO tenant_settings (tenant_id, volunteer_broadcast_daily_limit)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?)
				ON CONFLICT (tenant_id)
				DO UPDATE SET volunteer_broadcast_daily_limit = EXCLUDED.volunteer_broadcast_daily_limit,
					updated_at = now()
				""", limit);
	}
}

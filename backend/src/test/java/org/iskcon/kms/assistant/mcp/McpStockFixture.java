package org.iskcon.kms.assistant.mcp;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two temples' stock, seeded through the privileged connection, for the MCP tool tests (E10-T6).
 *
 * <p>Shaped so that every assertion that matters has a way to fail:
 *
 * <ul>
 *   <li><strong>Both temples hold a "Toor Dal"</strong>, with different quantities and different
 *       Kannada glossary entries. A tool that leaked across temples would return two rows for
 *       {@code "dal"}, or the other temple's figure, or the other temple's Kannada.
 *   <li><strong>Temple A holds 55 consumables, 54 of them low</strong>, so both tools must cut at 50
 *       and say so. Temple B holds two, one low.
 *   <li>Everything is read back through the application's own unprivileged connection, so if the
 *       temple were not set on the thread doing the read, RLS would hide the whole fixture and the
 *       assertions on content would fail rather than pass on an empty answer.
 * </ul>
 */
final class McpStockFixture {

	static final String DAL_KN_A = "ತೊಗರಿ ಬೇಳೆ";
	static final String DAL_KN_B = "ತೊಗರಿ ಬೇಳೆ (ಬಿ)";
	static final String GHEE_KN_A = "ತುಪ್ಪ";
	static final int FILLERS_A = 53;

	final UUID templeA;
	final UUID templeB;
	final UUID userA;
	final UUID userB;

	private final JdbcTemplate admin;

	McpStockFixture(JdbcTemplate admin) {
		this.admin = admin;
		templeA = tenant("mcp-temple-a", "MCP Temple A");
		templeB = tenant("mcp-temple-b", "MCP Temple B");
		UUID adminA = user(templeA, "uid-mcp-a", "mcp-a@example.com");
		UUID adminB = user(templeB, "uid-mcp-b", "mcp-b@example.com");
		userA = adminA;
		userB = adminB;

		// Temple A: Toor Dal low (2 of 5), Ghee fine (40 of 10), and 53 fillers holding nothing
		// against a level of 1, so all of them are low.
		stock(templeA, adminA, "Toor Dal", "Pulses", "KG", "5", "2");
		stock(templeA, adminA, "Ghee", "Dairy", "L", "10", "40");
		for (int i = 1; i <= FILLERS_A; i++) {
			stock(templeA, adminA, "Spice %02d".formatted(i), "Spices", "KG", "1", null);
		}
		glossary(templeA, "Toor Dal", DAL_KN_A);
		glossary(templeA, "Ghee", GHEE_KN_A);

		// Temple B: its own Toor Dal, plentiful, and one low rice.
		stock(templeB, adminB, "Toor Dal", "Pulses", "KG", "5", "100");
		stock(templeB, adminB, "Basmati Rice", "Grains", "KG", "50", "3");
		glossary(templeB, "Toor Dal", DAL_KN_B);
	}

	static void tearDown(JdbcTemplate admin) {
		admin.execute("DELETE FROM assistant_pending_actions");
		admin.execute("DELETE FROM assistant_messages");
		admin.execute("DELETE FROM assistant_runs");
		admin.execute("DELETE FROM assistant_conversations");
		admin.execute("DELETE FROM translation_glossary");
		admin.execute("DELETE FROM stock_movements");
		admin.execute("DELETE FROM inventory_items");
		admin.execute("DELETE FROM ingredients");
		admin.execute("DELETE FROM users");
		admin.execute("DELETE FROM tenants");
	}

	/**
	 * A conversation for {@code user} in {@code language}: the tools name items in the language of
	 * the conversation the agent token names (E10-NAMES-B), so a test of the Kannada names needs a
	 * Kannada conversation behind its token.
	 */
	UUID conversation(UUID tenant, UUID user, String language) {
		return admin.queryForObject("""
				INSERT INTO assistant_conversations (tenant_id, user_id, language) VALUES (?, ?, ?) RETURNING id
				""", UUID.class, tenant, user, language);
	}

	/** A glossary name in any language, as a person or the fill would have stored it. */
	void name(UUID tenant, String language, String source, String target) {
		admin.update("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term)
				VALUES (?, ?, ?, ?)
				""", tenant, language, source, target);
	}

	private UUID tenant(String slug, String name) {
		return admin.queryForObject("""
				INSERT INTO tenants (slug, name, latitude, longitude, timezone)
				VALUES (?, ?, 12.9716, 77.5946, 'Asia/Kolkata')
				RETURNING id
				""", UUID.class, slug, name);
	}

	private UUID user(UUID tenant, String uid, String email) {
		return admin.queryForObject("""
				INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role, status)
				VALUES (?, ?, 'Test Admin', ?, '+919876500091', 'TEMPLE_ADMIN', 'ACTIVE')
				RETURNING id
				""", UUID.class, tenant, uid, email);
	}

	private void stock(
			UUID tenant, UUID actor, String name, String category, String unit, String threshold,
			String onHand) {
		UUID ingredient = admin.queryForObject("""
				INSERT INTO ingredients (tenant_id, name, category, canonical_unit)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, tenant, name, category, unit);
		admin.update("""
				INSERT INTO inventory_items (tenant_id, ingredient_id, reorder_threshold)
				VALUES (?, ?, ?::numeric)
				""", tenant, ingredient, threshold);
		if (onHand != null) {
			admin.update("""
					INSERT INTO stock_movements (
						tenant_id, ingredient_id, batch_id, quantity, unit, movement_type, actor_user_id)
					VALUES (?, ?, ?, ?::numeric, ?, 'PO_RECEIPT', ?)
					""", tenant, ingredient, UUID.randomUUID(), onHand, unit, actor);
		}
	}

	private void glossary(UUID tenant, String source, String target) {
		admin.update("""
				INSERT INTO translation_glossary (tenant_id, language, source_term, target_term)
				VALUES (?, 'kn', ?, ?)
				""", tenant, source, target);
	}
}

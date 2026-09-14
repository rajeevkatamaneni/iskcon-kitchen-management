package org.iskcon.kms.meal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Request bodies for {@code POST /api/v1/meals} and {@code PUT /api/v1/meals/{id}}, written the way
 * the older tests were written: one dish, with the meal's facts beside it (D-27).
 *
 * <p><strong>What this is and is not.</strong> Before D-27 planning a meal was one request per dish,
 * and a great many tests say what they mean in exactly that shape — <em>a Lunch on 17 March, 100 of
 * khichdi, for 100 adults</em>. The endpoint now takes a meal with a list of dishes and a kind by id.
 * Rewriting every one of those bodies by hand would bury what each test is about under a change of
 * shape that is not what it tests, so this reads the old shape and writes the new one: {@code mealKind}
 * becomes the temple's {@code mealKindId}, and {@code recipeId}/{@code targetYield} become the one
 * entry in {@code dishes}. Everything else passes through untouched, and the request still goes to the
 * real endpoint and through its real validation.
 *
 * <p>It is not how the new behaviour is tested. Saving several dishes and a volunteer shift in one
 * transaction, find-or-create and cancelling with a shift are in {@code MealSaveIT}, with their bodies
 * written out in full.
 *
 * <p>A kind name the temple does not have becomes a random id, so a test that sends "Brunch" still
 * meets the unknown-kind refusal it was written to meet.
 */
public final class MealRequests {

	private static final ObjectMapper JSON = new ObjectMapper();

	private MealRequests() {
	}

	/** A one-dish plan in the old shape, as a {@code SaveMealRequest} body. */
	public static String save(String oldShape, JdbcTemplate admin, UUID tenant) {
		ObjectNode node = parse(oldShape);
		JsonNode kind = node.remove("mealKind");
		if (kind != null) {
			UUID id = MealFixture.kindId(admin, tenant, kind.asText());
			node.put("mealKindId", (id == null ? UUID.randomUUID() : id).toString());
		}
		moveDish(node, null);
		return node.toString();
	}

	/**
	 * A one-dish edit in the old shape, as an {@code UpdateMealRequest} body that keeps that dish (by
	 * its id) and changes it. The date and the kind are dropped: a meal is not moved by an update.
	 */
	public static String update(String oldShape, UUID dishId) {
		ObjectNode node = parse(oldShape);
		node.remove("planDate");
		node.remove("mealKind");
		moveDish(node, dishId);
		return node.toString();
	}

	/** The {@code id} field of a save's answer. */
	public static UUID idOf(String responseBody) {
		return UUID.fromString(parse(responseBody).get("id").asText());
	}

	private static void moveDish(ObjectNode node, UUID dishId) {
		JsonNode recipe = node.remove("recipeId");
		JsonNode target = node.remove("targetYield");
		if (node.has("dishes") || (recipe == null && target == null)) {
			return;
		}
		ArrayNode dishes = node.putArray("dishes");
		ObjectNode dish = dishes.addObject();
		if (dishId != null) {
			dish.put("id", dishId.toString());
		}
		if (recipe != null) {
			dish.set("recipeId", recipe);
		}
		if (target != null) {
			dish.set("targetYield", target);
		}
	}

	private static ObjectNode parse(String json) {
		try {
			return (ObjectNode) JSON.readTree(json);
		} catch (Exception e) {
			throw new IllegalArgumentException("Not a JSON object: " + json, e);
		}
	}
}

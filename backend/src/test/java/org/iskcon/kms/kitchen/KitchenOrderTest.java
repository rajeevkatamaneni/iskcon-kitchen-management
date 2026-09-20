package org.iskcon.kms.kitchen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.kitchen.KitchenOrder.KitchenRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ordering and the two decisions in {@link KitchenOrder}, without a database (Epic 12, T-350).
 *
 * <p>The rule, as Rajeev set it on 2026-09-19: a meal's kitchens read with the viewer's own kitchen
 * first if it is on the meal; else the main kitchen first if it is on the meal; then Settings order.
 * Every case below is one clause of that sentence, so a change to any clause fails exactly one test.
 *
 * <p>The temple here has four kitchens, listed as Settings lists them: Main (main, plans meals), Deity
 * (does not plan), Health (plans), and an archived Old kitchen, which sorts after every open one even
 * though "Old" would come before "Health" by name alone. What the database returns is the SQL's job and
 * WhichKitchenMigrationIT's to check; this class is handed that list and checks what is done with it.
 */
class KitchenOrderTest {

	private static final KitchenRef MAIN = ref("Main Kitchen", true, true, true);
	private static final KitchenRef DEITY = ref("Deity Kitchen", false, false, true);
	private static final KitchenRef HEALTH = ref("Health Kitchen", false, true, true);
	private static final KitchenRef OLD = ref("Old kitchen", false, true, false);

	private static final List<KitchenRef> SETTINGS = List.of(MAIN, DEITY, HEALTH, OLD);

	// ---- forViewer --------------------------------------------------------------------------------

	@Test
	@DisplayName("the viewer's own kitchen comes first when it is on the meal, ahead of the main kitchen")
	void ownKitchenFirst() {
		assertThat(KitchenOrder.forViewer(ids(MAIN, DEITY, HEALTH), HEALTH.id(), SETTINGS))
				.containsExactly(HEALTH.id(), MAIN.id(), DEITY.id());
	}

	@Test
	@DisplayName("when the viewer's kitchen is not on the meal, the main kitchen comes first")
	void ownKitchenNotOnTheMealMainFirst() {
		assertThat(KitchenOrder.forViewer(ids(HEALTH, DEITY, MAIN), OLD.id(), SETTINGS))
				.containsExactly(MAIN.id(), DEITY.id(), HEALTH.id());
	}

	@Test
	@DisplayName("a viewer with no kitchen sees the main kitchen first")
	void noKitchenMainFirst() {
		assertThat(KitchenOrder.forViewer(ids(HEALTH, MAIN), null, SETTINGS))
				.containsExactly(MAIN.id(), HEALTH.id());
	}

	@Test
	@DisplayName("with neither the viewer's nor the main kitchen on the meal, the rest are in Settings order")
	void restInSettingsOrder() {
		assertThat(KitchenOrder.forViewer(ids(HEALTH, DEITY), null, SETTINGS))
				.containsExactly(DEITY.id(), HEALTH.id());
	}

	@Test
	@DisplayName("an archived kitchen on an old meal comes after every open one")
	void archivedLast() {
		assertThat(KitchenOrder.forViewer(ids(OLD, HEALTH, MAIN, DEITY), null, SETTINGS))
				.containsExactly(MAIN.id(), DEITY.id(), HEALTH.id(), OLD.id());
	}

	@Test
	@DisplayName("the viewer's own kitchen leads even when it has since been archived")
	void ownArchivedKitchenStillLeads() {
		assertThat(KitchenOrder.forViewer(ids(MAIN, OLD), OLD.id(), SETTINGS))
				.containsExactly(OLD.id(), MAIN.id());
	}

	@Test
	@DisplayName("the generic form orders the caller's own section objects and keeps them whole")
	void ordersArbitrarySections() {
		record Section(UUID kitchenId, String label) {
		}
		List<Section> sections = List.of(
				new Section(DEITY.id(), "deity"), new Section(HEALTH.id(), "health"), new Section(MAIN.id(), "main"));
		assertThat(KitchenOrder.forViewer(sections, Section::kitchenId, DEITY.id(), SETTINGS))
				.extracting(Section::label)
				.containsExactly("deity", "main", "health");
	}

	// ---- defaultPlanningKitchen --------------------------------------------------------------------

	@Test
	@DisplayName("a meal with no kitchen named goes to the saver's kitchen when it plans meals")
	void defaultIsOwnPlannerKitchen() {
		assertThat(KitchenOrder.defaultPlanningKitchen(HEALTH.id(), SETTINGS)).contains(HEALTH.id());
	}

	@Test
	@DisplayName("a saver whose kitchen does not plan meals, or who has none, gets the main kitchen")
	void defaultFallsBackToMain() {
		assertThat(KitchenOrder.defaultPlanningKitchen(DEITY.id(), SETTINGS)).contains(MAIN.id());
		assertThat(KitchenOrder.defaultPlanningKitchen(null, SETTINGS)).contains(MAIN.id());
		assertThat(KitchenOrder.defaultPlanningKitchen(OLD.id(), SETTINGS))
				.as("archived kitchens plan nothing, whatever the flag says")
				.contains(MAIN.id());
	}

	@Test
	@DisplayName("where the main kitchen does not plan meals, the first planner kitchen in Settings order")
	void defaultFirstPlannerWhenMainDoesNotPlan() {
		KitchenRef storeOnlyMain = new KitchenRef(MAIN.id(), MAIN.name(), true, false, true);
		assertThat(KitchenOrder.defaultPlanningKitchen(null, List.of(storeOnlyMain, DEITY, HEALTH, OLD)))
				.contains(HEALTH.id());
		assertThat(KitchenOrder.defaultPlanningKitchen(null, List.of(storeOnlyMain, DEITY)))
				.as("no kitchen plans meals at all")
				.isEqualTo(Optional.empty());
	}

	// ---- mayPlan ----------------------------------------------------------------------------------

	@Test
	@DisplayName("the Temple Admin may plan, with or without a kitchen")
	void templeAdminMayPlan() {
		assertThat(KitchenOrder.mayPlan(true, null, SETTINGS)).isTrue();
		assertThat(KitchenOrder.mayPlan(true, DEITY.id(), SETTINGS)).isTrue();
	}

	@Test
	@DisplayName("anyone else may plan only from an open kitchen that uses the planner; no kitchen is a no")
	void othersNeedAPlannerKitchen() {
		assertThat(KitchenOrder.mayPlan(false, HEALTH.id(), SETTINGS)).isTrue();
		assertThat(KitchenOrder.mayPlan(false, DEITY.id(), SETTINGS)).as("a store-only kitchen").isFalse();
		assertThat(KitchenOrder.mayPlan(false, OLD.id(), SETTINGS)).as("an archived kitchen").isFalse();
		assertThat(KitchenOrder.mayPlan(false, null, SETTINGS)).as("no staff record").isFalse();
		assertThat(KitchenOrder.mayPlan(false, UUID.randomUUID(), SETTINGS))
				.as("a kitchen id this temple does not have").isFalse();
	}

	// ---------------------------------------------------------------------

	private static KitchenRef ref(String name, boolean main, boolean planner, boolean active) {
		return new KitchenRef(UUID.randomUUID(), name, main, planner, active);
	}

	private static List<UUID> ids(KitchenRef... kitchens) {
		return java.util.Arrays.stream(kitchens).map(KitchenRef::id).toList();
	}
}

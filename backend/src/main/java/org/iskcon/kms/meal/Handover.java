package org.iskcon.kms.meal;

/**
 * How food that is leaving the temple gets to the people eating it (E4-S15 D6).
 *
 * <p>The distinction earns its place because it decides what else the planner is asked. Somebody
 * collecting their own food does not need us to know where they are taking it, so a PICKUP stops at
 * a contact name and a phone number. A DELIVERY is ours to get there, so it asks where it is going
 * and when the guests sit down to eat — which is what E4-S16 works backwards from to say when to
 * leave the temple.
 */
public enum Handover {
	PICKUP,
	DELIVERY
}

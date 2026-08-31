package org.iskcon.kms.kitchen;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Recording another of the temple's kitchens (E10-S2).
 *
 * <p>Deliberately short. Rajeev asked for "any other pertinent information you can think of" and the
 * design stopped here: a head count, a cuisine, an opening time and a photo all suggested themselves
 * and none of them is read by anything, and an unread field is a field somebody fills in every time
 * for no one.
 */
public record CreateKitchenRequest(

		@NotBlank(message = "Enter the kitchen's name.")
		@Size(max = 200, message = "That name is too long.")
		String name,

		@Size(max = 2000, message = "That description is too long.")
		String description,

		/**
		 * Where in the temple it is, in the temple's own words — "behind the Deity hall". Free text
		 * because nobody is going to maintain a map, and a person reading a work order needs to know
		 * where to carry the sack.
		 */
		@Size(max = 500, message = "That location is too long.")
		String location,

		/**
		 * Whether this is the temple's principal kitchen. Ignored on a temple's very first kitchen,
		 * which is made main whatever the caller said: there is no other kitchen for the flag to sit
		 * on, and a temple whose only kitchen is not its main one is a state nobody can explain.
		 */
		boolean isMain,

		/**
		 * Whether this kitchen plans its meals here. It is the flag that changes behaviour: one
		 * store, two doors, and a kitchen uses one or the other, so its stock cannot leave twice for
		 * the same food (design D5).
		 */
		boolean usesMealPlanner,

		/** Who runs it. Optional — a temple may record the kitchen before it has decided. */
		UUID inChargeUserId,

		/**
		 * A number to reach the kitchen on. Not held to E.164 as a vendor's is: this one is dialled
		 * by somebody standing in the temple, and an internal extension is a legitimate answer.
		 */
		@Size(max = 30, message = "That phone number is too long.")
		String contactPhone) {
}

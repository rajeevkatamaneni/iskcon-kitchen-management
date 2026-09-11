package org.iskcon.kms.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Turns a place a person typed — "Jayanagar", "Mysuru", "Hare Krishna Hill" — into coordinates, so
 * temples can be offered by how far away they are rather than by how the address happens to be
 * spelled.
 *
 * <p>A port, like the translation and payment providers: the registration screen should not care
 * which map service answers, and the test suite should not need one at all.
 */
public interface GeocodingProvider {

	/** Where that place is, or empty when it cannot be found — which is not an error worth raising. */
	Optional<Coordinates> locate(String place);

	/**
	 * The same lookup, plus the geocoder's own rendering of what it decided the place was (T-042).
	 *
	 * <p><strong>Why this exists at all.</strong> A caller that has to show a person the answer needs
	 * something a person can recognise. Coordinates are not that: {@code 12.905125} is obvious to
	 * nobody, so an operator asked to confirm a pair of them will confirm anything. "Mysuru,
	 * Karnataka" when they meant Mysore Road, Bengaluru is obvious to anybody. That difference is the
	 * whole of what T-042 needed and the reason this sits beside {@link #locate} rather than
	 * replacing it.
	 *
	 * <p><strong>Defaulted, and that is the point.</strong> Every existing implementation and every
	 * existing caller is unchanged: {@link NoGeocodingProvider} keeps answering empty, the temple
	 * search and the delivery estimate keep calling {@link #locate}, and a provider with nothing to
	 * add says nothing by not overriding this. The interface also stays a functional one, so the test
	 * stubs written as lambdas still compile.
	 *
	 * <p><strong>A description is optional even on a hit.</strong> {@link Located#resolvedAddress()}
	 * may be null from any provider, including one that usually supplies it — a service can answer
	 * with coordinates and no label, and that is a hit with nothing to show, not a miss. Callers show
	 * what they were given and never require it.
	 *
	 * <p>Implementations keep {@link #locate}'s contract exactly: empty for every failure, and never
	 * a raised exception.
	 */
	default Optional<Located> describe(String place) {
		return locate(place).map(at -> new Located(at, null));
	}

	/**
	 * Whether this deployment has a map service at all.
	 *
	 * <p>An empty answer means two different things and the caller sometimes has to tell them apart:
	 * a service that looked and found nothing is worth reporting to whoever typed the address
	 * (KMS-400078, E4-S16) — they can fix it. No service at all is not their doing and not their
	 * problem, and telling them an address could not be found when nobody looked would be a lie.
	 *
	 * <p>Defaulted true, so a provider that answers questions never has to say so.
	 */
	default boolean configured() {
		return true;
	}

	/**
	 * A point on the earth, always at six decimal places (T-063).
	 *
	 * <p><strong>The promise is the port's, not each provider's.</strong> Both degrees are rounded to
	 * six decimals here, in the compact constructor, so there is no way to hold a {@code Coordinates}
	 * that carries more. That is deliberately stronger than a static factory would be: a factory
	 * leaves {@code new Coordinates(...)} reachable, so the next map service somebody wires in keeps
	 * the promise only by remembering to call it — which is exactly the arrangement this replaces.
	 * A provider that does nothing at all now keeps it.
	 *
	 * <p><strong>Why six.</strong> The sixth decimal of a degree is about eleven centimetres, and
	 * both columns that store one of these are {@code NUMERIC(9,6)} — {@code tenants.latitude} since
	 * V1 and {@code meal_plans.delivery_latitude} since V88 — so six is what the database was always
	 * going to keep. The only machines that read a coordinate are the Vaishnava calendar, which wants
	 * degrees for a sunrise, and the Routes call, which wants a street; neither can tell eleven
	 * centimetres from nothing. The human who reads one is an operator being asked <em>is this the
	 * right place?</em>, and seventeen significant digits is not a number a person checks — they nod
	 * at it. T-059 made that cut in the two Google providers after Rajeev picked ISKCON - Mysuru on
	 * the provisioning screen and got a fifteen-digit latitude to confirm; this moves it one level up
	 * so it cannot be forgotten rather than merely not being forgotten twice.
	 *
	 * <p><strong>Rounded, never formatted.</strong> {@code BigDecimal.valueOf} reads the double's own
	 * shortest rendering and {@link RoundingMode#HALF_UP} cuts it; going back to a double drops any
	 * trailing zeros the scale introduced. So a coordinate that was already short stays short, where
	 * a {@code %.6f} would have padded it out to six places it never had and made a neatly typed
	 * number look machine-generated.
	 *
	 * <p><strong>A value that is not finite is handed back untouched rather than rounded, and this
	 * constructor never raises.</strong> {@code BigDecimal.valueOf} throws on those, and every
	 * provider in this package promises that a bad reply from a map service is an empty answer and
	 * never an exception — a promise that would be broken from inside the record they answer with if
	 * this threw. Such a value fails the column's range check a moment later, which is the right
	 * place for it to fail.
	 */
	record Coordinates(double latitude, double longitude) {

		public Coordinates {
			latitude = sixDecimals(latitude);
			longitude = sixDecimals(longitude);
		}

		private static double sixDecimals(double degrees) {
			if (!Double.isFinite(degrees)) {
				return degrees;
			}
			return BigDecimal.valueOf(degrees).setScale(6, RoundingMode.HALF_UP).doubleValue();
		}
	}

	/**
	 * A place that was found, and how the geocoder itself writes it.
	 *
	 * @param resolvedAddress the service's normalised rendering — Google's
	 *                        {@code formatted_address} — or null where it gave none. Never a rewrite
	 *                        of what the caller typed:
	 *                        echoing the query back would look like a confirmation while confirming
	 *                        nothing, which is worse than showing nothing at all.
	 */
	record Located(Coordinates at, String resolvedAddress) {
	}
}

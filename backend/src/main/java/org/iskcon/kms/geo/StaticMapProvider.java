package org.iskcon.kms.geo;

import java.util.Optional;

/**
 * A picture of where the food is going, for the job card's delivery sheet.
 *
 * <p><strong>Zoomed out, deliberately.</strong> Rajeev, 2026-09-05: <em>"a Map of the destination
 * zoomed out just enough so we can see some prominent landmarks to help the dleivery driver incase
 * they need it."</em> A map tight on the pin is a photograph of a rooftop; what helps somebody at
 * the wheel is the main road it comes off and the junction before it. {@link #DEFAULT_ZOOM} is that
 * distance — roughly a neighbourhood, not a building.
 *
 * <p><strong>It returns bytes, not a URL.</strong> The renderer draws the card with no network at
 * all, so a {@code <img src="https://…">} would be a broken box on paper. The image is fetched here
 * and inlined into the document.
 *
 * <p><strong>Nothing here raises, and a missing map costs the card its picture and nothing else.</strong>
 * The same contract as {@link TravelTimeProvider}: a map service having a bad minute must never
 * stand between a driver and the address, which is printed as text directly above it.
 */
public interface StaticMapProvider {

	/**
	 * Wide enough for landmarks. 15 is about a neighbourhood; 17 and up is a rooftop, and 13 and
	 * below loses the street names that make a map worth printing.
	 */
	int DEFAULT_ZOOM = 15;

	/** Whether a map can be produced at all. False means the card simply prints without one. */
	boolean configured();

	/**
	 * A PNG of the area around these coordinates, or empty if one could not be had.
	 *
	 * @param widthPx  the pixel width to ask for. The sheet scales it to the column, so this decides
	 *                 how sharp it is on paper rather than how big it looks.
	 */
	Optional<byte[]> map(GeocodingProvider.Coordinates at, int widthPx, int heightPx);
}

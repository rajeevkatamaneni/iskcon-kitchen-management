/**
 * The Vaishnava calendar engine: an astronomical computation of tithi, paksa, masa, Ekadashi (with
 * Maha-Dvadashi postponement), fasting, and festivals for a tenant's location (E4-S1).
 *
 * <p><strong>Attribution.</strong> The astronomy ({@code org.iskcon.kms.calendar.astro}) and the
 * Gaudiya rule logic in this package are a faithful Java port of the MIT-licensed Python reference
 * <a href="https://github.com/gopa810/gaurabda-calendar">gopa810/gaurabda-calendar</a>, itself a port
 * of the official ISKCON <em>Gaurabda Calendar Program</em> (GCAL) by the ISKCON GBC Vaishnava
 * Calendar Committee. Coefficient tables, rules, and the festival data ({@code events.json},
 * {@code eventfast.json}, {@code strings.json}) are used under the MIT licence. Correctness is gated
 * against GCAL's own output for Bengaluru — see {@code docs/CALENDAR-CORRECTNESS.md}.
 */
package org.iskcon.kms.calendar.engine;

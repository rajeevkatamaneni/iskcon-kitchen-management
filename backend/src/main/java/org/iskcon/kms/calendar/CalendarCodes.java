package org.iskcon.kms.calendar;

/**
 * Maps the GCAL numeric codes the engine emits (fast type, Maha-Dvadashi type) to the short stable
 * names we store and show. Kept apart from the engine so the astronomical port stays a verbatim
 * translation and the presentation vocabulary lives on our side.
 */
final class CalendarCodes {

	/** FAST_EKADASI in GCAL — the Ekadashi fast the Ekadashi violation check keys on (E4-S6). */
	static final int FAST_EKADASI = 0x206;

	private CalendarCodes() {
	}

	/** The kind of fast, or null when the day carries none. */
	static String fastTypeName(int fastCode) {
		return switch (fastCode) {
			case 0x201 -> "NOON";
			case 0x202 -> "SUNSET";
			case 0x203 -> "MOONRISE";
			case 0x204 -> "DUSK";
			case 0x205 -> "MIDNIGHT";
			case 0x206 -> "EKADASI";
			case 0x207 -> "FULL_DAY";
			default -> null;
		};
	}

	/**
	 * The Maha-Dvadashi variant when the Ekadashi fast was moved to Dvadashi, or null. EV_NULL (no
	 * Ekadashi) and EV_SUDDHA (a plain, un-postponed Ekadashi) both map to null — there is nothing
	 * special to name.
	 */
	static String mahadvadashiName(int code) {
		return switch (code) {
			case 0x102 -> "UNMILANI";
			case 0x103 -> "VYANJULI";
			case 0x104 -> "TRISPRSA";
			case 0x105 -> "UNMILANI_TRISPRSA";
			case 0x106 -> "PAKSAVARDHINI";
			case 0x107 -> "JAYA";
			case 0x108 -> "JAYANTI";
			case 0x109 -> "PAPA_NASINI";
			case 0x110 -> "VIJAYA";
			default -> null; // 0x100 EV_NULL, 0x101 EV_SUDDHA
		};
	}
}

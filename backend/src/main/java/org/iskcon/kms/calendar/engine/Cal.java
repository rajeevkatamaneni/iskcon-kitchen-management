package org.iskcon.kms.calendar.engine;

/** Constants mirroring the GCEnums used by the calendar rule layer. */
final class Cal {

    private Cal() {
    }

    // FastType
    static final int FAST_NULL = 0x000;
    static final int FAST_NOON = 0x201;
    static final int FAST_SUNSET = 0x202;
    static final int FAST_MOONRISE = 0x203;
    static final int FAST_DUSK = 0x204;
    static final int FAST_MIDNIGHT = 0x205;
    static final int FAST_EKADASI = 0x206;
    static final int FAST_DAY = 0x207;

    // MahadvadasiType
    static final int EV_NULL = 0x100;
    static final int EV_SUDDHA = 0x101;
    static final int EV_UNMILANI = 0x102;
    static final int EV_VYANJULI = 0x103;
    static final int EV_TRISPRSA = 0x104;
    static final int EV_UNMILANI_TRISPRSA = 0x105;
    static final int EV_PAKSAVARDHINI = 0x106;
    static final int EV_JAYA = 0x107;
    static final int EV_JAYANTI = 0x108;
    static final int EV_PAPA_NASINI = 0x109;
    static final int EV_VIJAYA = 0x110;

    // MasaId
    static final int VAMANA_MASA = 2;
    static final int SRIDHARA_MASA = 3;
    static final int HRSIKESA_MASA = 4;
    static final int PADMANABHA_MASA = 5;
    static final int DAMODARA_MASA = 6;
    static final int GOVINDA_MASA = 10;
    static final int VISNU_MASA = 11;
    static final int ADHIKA_MASA = 12;

    // TithiId
    static final int TITHI_KRSNA_ASTAMI = 7;
    static final int TITHI_AMAVASYA = 14;
    static final int TITHI_GAURA_PRATIPAT = 15;
    static final int TITHI_GAURA_DVITIYA = 16;
    static final int TITHI_GAURA_NAVAMI = 23;
    static final int TITHI_GAURA_DVADASI = 26;
    static final int TITHI_GAURA_CATURDASI = 28;
    static final int TITHI_PURNIMA = 29;
    static final int TITHI_KRSNA_PRATIPAT = 0;

    // NaksatraId
    static final int ROHINI_NAKSATRA = 3;

    // PaksaId
    static final int GAURA_PAKSA = 1;

    // SankrantiId
    static final int VRSABHA_SANKRANTI = 1;
    static final int MAKARA_SANKRANTI = 9;
    static final int MESHA_SANKRANTI = 0;

    // SpecialFestivalId
    static final int SPEC_RETURNRATHA = 3;
    static final int SPEC_HERAPANCAMI = 4;
    static final int SPEC_GUNDICAMARJANA = 5;
    static final int SPEC_GOVARDHANPUJA = 6;
    static final int SPEC_RAMANAVAMI = 9;
    static final int SPEC_JANMASTAMI = 10;
    static final int SPEC_RATHAYATRA = 11;
    static final int SPEC_GAURAPURNIMA = 12;
    static final int SPEC_NANDAUTSAVA = 13;
    static final int SPEC_MISRAFESTIVAL = 14;
    static final int SPEC_PRABHAPP = 15;

    // GCDS (display indices used as event 'disp')
    static final int DISP_ALWAYS = -1;
    static final int CATURMASYA_PURNIMA = 13;
    static final int CATURMASYA_PRATIPAT = 14;
    static final int CATURMASYA_EKADASI = 15;
    static final int CAL_EKADASI_PARANA = 17;
    static final int CAL_FEST_0 = 22;
    static final int CAL_FEST_1 = 23;
    static final int CAL_FEST_2 = 24;
    static final int CAL_FEST_3 = 25;
    static final int CAL_FEST_5 = 27;
    static final int CAL_FEST_6 = 28;

    // DisplayPriorities
    static final int PRIO_MAHADVADASI = 10;
    static final int PRIO_EKADASI = 20;
    static final int PRIO_EKADASI_PARANA = 90;
    static final int PRIO_FESTIVALS_0 = 100;
    static final int PRIO_FESTIVALS_5 = 600;
    static final int PRIO_FASTING = 900;
    static final int PRIO_CM_DAY = 972;
    static final int PRIO_CM_DAYNOTE = 973;

    // FeastType
    static final int FEAST_NULL = 0;
    static final int FEAST_TODAY_FAST_YESTERDAY = 1;
    static final int FEAST_TOMMOROW_FAST_TODAY = 2;

    // EkadasiParanaType
    static final int EP_TYPE_NULL = 0;
    static final int EP_TYPE_3DAY = 1;
    static final int EP_TYPE_4TITHI = 2;
    static final int EP_TYPE_NAKEND = 3;
    static final int EP_TYPE_SUNRISE = 4;
    static final int EP_TYPE_TEND = 5;

    // Caturmasya day/month bits (only need mask constants that appear in logic)
    static final int CMASYA_SYSTEM_PURNIMA = 13;
    static final int CMASYA_SYSTEM_PRATIPAT = 14;
    static final int CMASYA_SYSTEM_EKADASI = 15;
    static final int CMASYA_MONTH_1 = 0x100;
    static final int CMASYA_MONTH_2 = 0x200;
    static final int CMASYA_MONTH_3 = 0x300;
    static final int CMASYA_MONTH_4 = 0x400;
    static final int CMASYA_DAY_FIRST = 0x1000;
    static final int CMASYA_DAY_LAST = 0x2000;
}

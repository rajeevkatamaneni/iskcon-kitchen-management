package org.iskcon.kms.calendar.engine;

import java.util.ArrayList;
import java.util.List;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.iskcon.kms.calendar.astro.GcTime;

/** Faithful port of GCCalendarDay.py (the parts used by calendar building). */
final class CalendarDay {

    /** Mirror of a dayEvents entry (a dict in Python). */
    static final class DayEvent {
        int prio;
        int disp;
        String text;
        Integer fasttype; // null when absent
        String fastsubject;
        String spec;

        DayEvent(int prio, int disp, String text) {
            this.prio = prio;
            this.disp = disp;
            this.text = text;
        }
    }

    final GcGregorianDate date = new GcGregorianDate();
    GcTime moonrise = new GcTime();
    GcTime moonset = new GcTime();
    final DayData astrodata = new DayData();

    int nCaturmasya = 0;
    int hasDST = 0;
    int nFeasting = Cal.FEAST_NULL;
    final List<DayEvent> dayEvents = new ArrayList<>();

    int nFastType = Cal.FAST_NULL;
    int nMhdType = Cal.EV_NULL;
    String ekadasiVrataName = "";
    boolean ekadasiParana = false;
    double eparanaTime1 = 0.0;
    double eparanaTime2 = 0.0;
    int eparanaType1 = 0;
    int eparanaType2 = 0;
    int sankrantiZodiac = -1;
    final GcGregorianDate sankrantiDay = new GcGregorianDate();

    void clear() {
        this.nFastType = Cal.FAST_NULL;
        this.nFeasting = Cal.FEAST_NULL;
        this.nMhdType = Cal.EV_NULL;
        this.ekadasiParana = false;
        this.ekadasiVrataName = "";
        this.eparanaTime1 = 0.0;
        this.eparanaTime2 = 0.0;
        this.sankrantiZodiac = -1;
        this.sankrantiDay.day = 0;
        this.sankrantiDay.shour = 0.0;
        this.sankrantiDay.month = 0;
        this.sankrantiDay.year = 0;
        this.nCaturmasya = 0;
    }

    DayEvent addEvent(int priority, int dispItem, String text) {
        DayEvent dc = new DayEvent(priority, dispItem, text);
        this.dayEvents.add(dc);
        return dc;
    }

    boolean hasEventsOfDisplayIndex(int dispIndex) {
        for (DayEvent md : dayEvents) {
            if (md.disp == dispIndex) {
                return true;
            }
        }
        return false;
    }

    DayEvent findEventsText(String text) {
        for (DayEvent md : dayEvents) {
            if (md.text != null && md.text.contains(text)) {
                return md;
            }
        }
        return null;
    }

    boolean addSpecFestival(int nSpecialFestival, int nFestClass) {
        String str;
        int fasting = -1;
        String fastingSubject = null;

        if (nSpecialFestival == Cal.SPEC_JANMASTAMI) {
            str = GcStrings.getString(741);
            fasting = 5;
            fastingSubject = "Sri Krsna";
        } else if (nSpecialFestival == Cal.SPEC_GAURAPURNIMA) {
            str = GcStrings.getString(742);
            fasting = 3;
            fastingSubject = "Sri Caitanya Mahaprabhu";
        } else if (nSpecialFestival == Cal.SPEC_RETURNRATHA) {
            str = GcStrings.getString(743);
        } else if (nSpecialFestival == Cal.SPEC_HERAPANCAMI) {
            str = GcStrings.getString(744);
        } else if (nSpecialFestival == Cal.SPEC_GUNDICAMARJANA) {
            str = GcStrings.getString(745);
        } else if (nSpecialFestival == Cal.SPEC_GOVARDHANPUJA) {
            str = GcStrings.getString(746);
        } else if (nSpecialFestival == Cal.SPEC_RAMANAVAMI) {
            str = GcStrings.getString(747);
            fasting = 2;
            fastingSubject = "Sri Ramacandra";
        } else if (nSpecialFestival == Cal.SPEC_RATHAYATRA) {
            str = GcStrings.getString(748);
        } else if (nSpecialFestival == Cal.SPEC_NANDAUTSAVA) {
            str = GcStrings.getString(749);
        } else if (nSpecialFestival == Cal.SPEC_PRABHAPP) {
            str = GcStrings.getString(759);
            fasting = 1;
            fastingSubject = "Srila Prabhupada";
        } else if (nSpecialFestival == Cal.SPEC_MISRAFESTIVAL) {
            str = GcStrings.getString(750);
        } else {
            return false;
        }

        DayEvent md = addEvent(Cal.PRIO_FESTIVALS_0 + (nFestClass - Cal.CAL_FEST_0) * 100,
                nFestClass, str);
        if (fasting > 0) {
            md.fasttype = fasting;
            md.fastsubject = fastingSubject;
        }

        return false;
    }

    /** Mirror of GetTextEP (EPDR display setting defaults to 0). */
    String getTextEP() {
        double t1 = this.eparanaTime1;
        double h1 = Math.floor(t1);
        double m1 = t1 - h1;
        if (this.eparanaTime2 >= 0.0) {
            double t2 = this.eparanaTime2;
            double h2 = Math.floor(t2);
            double m2 = t2 - h2;
            return String.format("%s %02d:%02d - %02d:%02d (%s)", GcStrings.getString(60),
                    (int) h1, (int) (m1 * 60), (int) h2, (int) (m2 * 60), dstSignature());
        } else if (this.eparanaTime1 >= 0.0) {
            return String.format("%s %02d:%02d (%s)", GcStrings.getString(61),
                    (int) h1, (int) (m1 * 60), dstSignature());
        } else {
            return GcStrings.getString(62);
        }
    }

    private String dstSignature() {
        return this.hasDST != 0 ? "DST" : "LT";
    }
}

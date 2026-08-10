package org.iskcon.kms.calendar.engine;

import java.util.List;
import org.iskcon.kms.calendar.astro.EarthData;
import org.iskcon.kms.calendar.astro.GcGregorianDate;
import org.iskcon.kms.calendar.astro.MoonData;

/**
 * Faithful port of TCalendar.CalculateCalendar and its helpers, restricted to
 * the rule outputs the facade exposes (tithi/masa/naksatra/yoga/gaurabda year,
 * fast type, maha-dvadasi type, ekadasi vrata name, ekadasi parana and the
 * festival application). Non-festival display events (sun/moon/arunodaya/ksaya/
 * vriddhi/masa-change/caturmasya/sankranti-info text) are omitted: they never
 * affect any exposed field and are gated off by default display settings.
 */
final class CalendarBuilder {

    private static final int BEFORE_DAYS = 8;

    private CalendarDay[] mData;
    private int mPureCount;
    private int mNCount;

    CalendarDay[] calculate(EarthData earth, GcGregorianDate begDate, int iCount) {
        int nTotalCount = iCount + 2 * BEFORE_DAYS;
        this.mNCount = nTotalCount;
        this.mPureCount = iCount;
        this.mData = new CalendarDay[nTotalCount];
        for (int i = 0; i < nTotalCount; i++) {
            mData[i] = new CalendarDay();
        }

        GcGregorianDate date = begDate.copy();
        date.shour = 0.0;
        date.tzone = earth.tzone;
        date.initWeekDay();
        date.addDays(-BEFORE_DAYS);

        // initialization of days
        for (CalendarDay mp : mData) {
            mp.date.set(date);
            date.nextDay();
            mp.moonrise.setValue(-1);
            mp.moonset.setValue(-1);
        }

        // hasDST (no DST database available here -> 0, matching Asia/Calcutta)
        for (CalendarDay mp : mData) {
            mp.hasDST = 0;
        }

        // init of astro data
        for (CalendarDay mp : mData) {
            mp.astrodata.dayCalc(mp.date, earth);
        }

        // init of masa (computed on paksa change, carried forward)
        int prevPaksa = -1;
        int lastMasa = 0;
        int lastGYear = 0;
        for (int i = 0; i < mData.length; i++) {
            CalendarDay mp = mData[i];
            boolean calcMasa = (mp.astrodata.getPaksa() != prevPaksa);
            prevPaksa = mp.astrodata.getPaksa();
            if (i == 0) {
                calcMasa = true;
            }
            if (calcMasa) {
                mp.astrodata.masaCalc(mp.date, earth);
                lastMasa = mp.astrodata.nMasa;
                lastGYear = mp.astrodata.nGaurabdaYear;
            }
            mp.astrodata.nMasa = lastMasa;
            mp.astrodata.nGaurabdaYear = lastGYear;
        }

        // init of mahadvadasis
        for (int i = 2; i < mPureCount + BEFORE_DAYS + 3; i++) {
            mData[i].clear();
            mahadvadasiCalc(i, earth);
        }

        // init for Ekadasis
        for (int i = 3; i < mPureCount + BEFORE_DAYS + 3; i++) {
            ekadasiCalc(i, earth);
        }

        // init of festivals
        for (int i = BEFORE_DAYS; i < mPureCount + BEFORE_DAYS + 3; i++) {
            completeCalc(i, earth);
        }

        // extended festivals
        for (int i = BEFORE_DAYS; i < mPureCount + BEFORE_DAYS; i++) {
            extendedCalc(i, earth);
        }

        // resolve festivals fasting (hasDST == 0 so no time shifts)
        for (int i = BEFORE_DAYS; i < mPureCount + BEFORE_DAYS; i++) {
            resolveFestivalsFasting(i);
        }

        // sankranti
        computeSankranti(earth);

        // festivals dependent on sankranti
        for (int i = BEFORE_DAYS; i < mPureCount + BEFORE_DAYS; i++) {
            if (mData[i].sankrantiZodiac == Cal.MAKARA_SANKRANTI) {
                mData[i].addEvent(Cal.PRIO_FESTIVALS_5, Cal.CAL_FEST_5, GcStrings.getString(78));
            } else if (mData[i].sankrantiZodiac == Cal.MESHA_SANKRANTI) {
                mData[i].addEvent(Cal.PRIO_FESTIVALS_5, Cal.CAL_FEST_5, GcStrings.getString(79));
            } else if (mData[i + 1].sankrantiZodiac == Cal.VRSABHA_SANKRANTI) {
                mData[i].addEvent(Cal.PRIO_FESTIVALS_5, Cal.CAL_FEST_5, GcStrings.getString(80));
            }
        }

        return mData;
    }

    int beforeDays() {
        return BEFORE_DAYS;
    }

    int pureCount() {
        return mPureCount;
    }

    // ------------------------------------------------------------------
    private boolean nextNewFullIsVriddhi(int nIndex) {
        int nPrevTithi = 100;
        for (int i = 0; i < BEFORE_DAYS; i++) {
            if (nIndex >= mData.length) {
                return false;
            }
            int nTithi = mData[nIndex].astrodata.nTithi;
            if ((nTithi == nPrevTithi) && GcTithi.tithiFullNewMoon(nTithi)) {
                return true;
            }
            nPrevTithi = nTithi;
            nIndex += 1;
        }
        return false;
    }

    private int isMhd58(int nIndex) {
        CalendarDay t = mData[nIndex];
        CalendarDay u = mData[nIndex + 1];

        if (t.astrodata.nNaksatra != u.astrodata.nNaksatra) {
            return Cal.EV_NULL;
        }
        if (t.astrodata.getPaksa() != 1) {
            return Cal.EV_NULL;
        }

        if (t.astrodata.nTithi == t.astrodata.nTithiSunset) {
            if (t.astrodata.nNaksatra == 6) {
                return Cal.EV_JAYA;
            } else if (t.astrodata.nNaksatra == 3) {
                return Cal.EV_JAYANTI;
            } else if (t.astrodata.nNaksatra == 7) {
                return Cal.EV_PAPA_NASINI;
            } else if (t.astrodata.nNaksatra == 21) {
                return Cal.EV_VIJAYA;
            } else {
                return Cal.EV_NULL;
            }
        } else {
            if (t.astrodata.nNaksatra == 21) {
                return Cal.EV_VIJAYA;
            }
        }
        return Cal.EV_NULL;
    }

    private void mahadvadasiCalc(int nIndex, EarthData earth) {
        int nMhdDay = -1;

        CalendarDay s = mData[nIndex - 1];
        CalendarDay t = mData[nIndex];
        CalendarDay u = mData[nIndex + 1];

        if (GcTithi.tithiDvadasi(s.astrodata.nTithi)) {
            return;
        }

        int nMahaType = isMhd58(nIndex);
        if (Cal.TITHI_GAURA_DVADASI == t.astrodata.nTithi
                && Cal.TITHI_GAURA_DVADASI == t.astrodata.nTithiSunset && nMahaType != 0) {
            t.nMhdType = nMahaType;
            nMhdDay = nIndex;
        } else if (GcTithi.tithiDvadasi(t.astrodata.nTithi)) {
            if (GcTithi.tithiDvadasi(u.astrodata.nTithi)
                    && GcTithi.tithiEkadasi(s.astrodata.nTithi)
                    && GcTithi.tithiEkadasi(s.astrodata.nTithiArunodaya)) {
                t.nMhdType = Cal.EV_VYANJULI;
                nMhdDay = nIndex;
            } else if (nextNewFullIsVriddhi(nIndex)) {
                t.nMhdType = Cal.EV_PAKSAVARDHINI;
                nMhdDay = nIndex;
            } else if (GcTithi.tithiLessEkadasi(s.astrodata.nTithiArunodaya)) {
                t.nMhdType = Cal.EV_SUDDHA;
                nMhdDay = nIndex;
            }
        }

        if (nMhdDay >= 0) {
            mData[nMhdDay].nFastType = Cal.FAST_EKADASI;
            mData[nMhdDay].ekadasiVrataName =
                    GcStrings.getEkadasiName(t.astrodata.nMasa, t.astrodata.getPaksa());
            mData[nMhdDay].ekadasiParana = false;
            mData[nMhdDay].eparanaTime1 = 0.0;
            mData[nMhdDay].eparanaTime2 = 0.0;

            mData[nMhdDay + 1].nFastType = Cal.FAST_NULL;
            mData[nMhdDay + 1].ekadasiParana = true;
            mData[nMhdDay + 1].eparanaTime1 = 0.0;
            mData[nMhdDay + 1].eparanaTime2 = 0.0;
        }
    }

    private void ekadasiCalc(int nIndex, EarthData earth) {
        CalendarDay s = mData[nIndex - 1];
        CalendarDay t = mData[nIndex];
        CalendarDay u = mData[nIndex + 1];

        if (GcTithi.tithiEkadasi(t.astrodata.nTithi)) {
            if (GcTithi.tithiLessEkadasi(t.astrodata.nTithiArunodaya)) {
                t.nMhdType = Cal.EV_NULL;
                t.ekadasiVrataName = "";
                t.nFastType = Cal.FAST_NULL;
            } else {
                if (GcTithi.tithiEkadasi(s.astrodata.nTithi)
                        && GcTithi.tithiEkadasi(s.astrodata.nTithiArunodaya)) {
                    if (GcTithi.tithiTrayodasi(u.astrodata.nTithi)) {
                        t.nMhdType = Cal.EV_UNMILANI_TRISPRSA;
                    } else {
                        t.nMhdType = Cal.EV_UNMILANI;
                    }
                    t.ekadasiVrataName =
                            GcStrings.getEkadasiName(t.astrodata.nMasa, t.astrodata.getPaksa());
                    t.nFastType = Cal.FAST_EKADASI;
                } else {
                    if (GcTithi.tithiTrayodasi(u.astrodata.nTithi)) {
                        t.nMhdType = Cal.EV_TRISPRSA;
                        t.ekadasiVrataName =
                                GcStrings.getEkadasiName(t.astrodata.nMasa, t.astrodata.getPaksa());
                        t.nFastType = Cal.FAST_EKADASI;
                    } else {
                        if (GcTithi.tithiEkadasi(u.astrodata.nTithi)
                                || (u.nMhdType >= Cal.EV_SUDDHA)) {
                            t.nMhdType = Cal.EV_NULL;
                            t.ekadasiVrataName = "";
                            t.nFastType = Cal.FAST_NULL;
                        } else if (u.nMhdType == Cal.EV_NULL) {
                            t.nMhdType = Cal.EV_SUDDHA;
                            t.ekadasiVrataName =
                                    GcStrings.getEkadasiName(t.astrodata.nMasa, t.astrodata.getPaksa());
                            t.nFastType = Cal.FAST_EKADASI;
                        }
                    }
                }
            }
        }
        if (s.nFastType == Cal.FAST_EKADASI) {
            calculateEParana(s, t, earth);
        }
    }

    private boolean isFestivalDay(CalendarDay yesterday, CalendarDay today, int nTithi) {
        return ((today.astrodata.nTithi == nTithi)
                && GcTithi.tithiLessThan(yesterday.astrodata.nTithi, nTithi))
                || (GcTithi.tithiLessThan(yesterday.astrodata.nTithi, nTithi)
                        && GcTithi.tithiGreatThan(today.astrodata.nTithi, nTithi));
    }

    private void checkFestivals(CalendarDay s, CalendarDay t, CalendarDay u, CalendarDay v) {
        int masaFrom = -1;
        int masaTo = -1;
        int tithiFrom = -1;
        int tithiTo = -1;

        if (t.astrodata.nMasa > 11) {
            return;
        }

        if (s.astrodata.nTithi != t.astrodata.nTithi) {
            tithiTo = t.astrodata.nTithi;
            masaTo = t.astrodata.nMasa;
        }

        // getValue(71) FKSP == 0
        if ((t.astrodata.nTithi != s.astrodata.nTithi)
                && (t.astrodata.nTithi != (s.astrodata.nTithi + 1) % 30)) {
            int n2 = (t.astrodata.nMasa * 30 + t.astrodata.nTithi + 359) % 360;
            tithiFrom = n2 % 30;
            masaFrom = (int) Math.floor(n2 / 30.0);
        }

        int currFestTop = 0;
        for (CalEventList.SourceEvent pEvx : CalEventList.getList()) {
            boolean eventSelected = false;
            if (masaFrom >= 0 && pEvx.masa == masaFrom && pEvx.tithi == tithiFrom
                    && pEvx.used != 0 && pEvx.visible != 0) {
                eventSelected = true;
            } else if (masaTo >= 0 && pEvx.masa == masaTo && pEvx.tithi == tithiTo
                    && pEvx.used != 0 && pEvx.visible != 0) {
                eventSelected = true;
            }

            if (!eventSelected) {
                continue;
            }
            CalendarDay.DayEvent md = t.addEvent(
                    Cal.PRIO_FESTIVALS_0 + pEvx.cls * 100 + currFestTop, Cal.CAL_FEST_0 + pEvx.cls,
                    pEvx.text);
            currFestTop += 5;
            if (pEvx.fast > 0) {
                md.fasttype = pEvx.fast;
                md.fastsubject = pEvx.fastSubj;
            }

            // getValue(51) ANIV == 0; anniversary only when start > -7000 (never here)
            if (pEvx.start > -7000) {
                int years = t.astrodata.nGaurabdaYear - (pEvx.start - 1496);
                String appx = "th";
                if (years % 10 == 1) {
                    appx = "st";
                } else if (years % 10 == 2) {
                    appx = "nd";
                } else if (years % 10 == 3) {
                    appx = "rd";
                }
                md.text = String.format("%s (%d%s anniversary)", pEvx.text, years, appx);
            }
        }
    }

    private void completeCalc(int nIndex, EarthData earth) {
        CalendarDay s = mData[nIndex - 1];
        CalendarDay t = mData[nIndex];
        CalendarDay u = mData[nIndex + 1];
        CalendarDay v = mData[nIndex + 2];

        // Govardhan-puja
        if (t.astrodata.nMasa == Cal.DAMODARA_MASA) {
            if (t.astrodata.nTithi == Cal.TITHI_GAURA_PRATIPAT) {
                MoonData.MoonTimes smt = MoonData.calcMoonTimes(earth, u.date, s.hasDST);
                s.moonrise = smt.rise;
                s.moonset = smt.set;
                MoonData.MoonTimes tmt = MoonData.calcMoonTimes(earth, t.date, t.hasDST);
                t.moonrise = tmt.rise;
                t.moonset = tmt.set;
                if (s.astrodata.nTithi == Cal.TITHI_GAURA_PRATIPAT) {
                    // nothing
                } else if (u.astrodata.nTithi == Cal.TITHI_GAURA_PRATIPAT) {
                    if (t.moonrise.hour >= 0) {
                        if (t.moonrise.isGreaterThan(t.astrodata.sun.rise)) {
                            t.addSpecFestival(Cal.SPEC_GOVARDHANPUJA, Cal.CAL_FEST_1);
                        } else {
                            u.addSpecFestival(Cal.SPEC_GOVARDHANPUJA, Cal.CAL_FEST_1);
                        }
                    } else if (u.moonrise.hour >= 0) {
                        if (u.moonrise.isLessThan(u.astrodata.sun.rise)) {
                            t.addSpecFestival(Cal.SPEC_GOVARDHANPUJA, Cal.CAL_FEST_1);
                        } else {
                            u.addSpecFestival(Cal.SPEC_GOVARDHANPUJA, Cal.CAL_FEST_1);
                        }
                    } else {
                        t.addSpecFestival(Cal.SPEC_GOVARDHANPUJA, Cal.CAL_FEST_1);
                    }
                } else {
                    t.addSpecFestival(Cal.SPEC_GOVARDHANPUJA, Cal.CAL_FEST_1);
                }
            } else if ((t.astrodata.nTithi == Cal.TITHI_GAURA_DVITIYA)
                    && (s.astrodata.nTithi == Cal.TITHI_AMAVASYA)) {
                t.addSpecFestival(Cal.SPEC_GOVARDHANPUJA, Cal.CAL_FEST_1);
            }
        }

        if (t.astrodata.nMasa == Cal.HRSIKESA_MASA) {
            // Janmastami
            if (isFestivalDay(s, t, Cal.TITHI_KRSNA_ASTAMI)) {
                if (u.astrodata.nTithi != Cal.TITHI_KRSNA_ASTAMI) {
                    t.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                    u.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                    u.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                } else {
                    if ((t.astrodata.nNaksatra == Cal.ROHINI_NAKSATRA)
                            && (u.astrodata.nNaksatra == Cal.ROHINI_NAKSATRA)) {
                        double midNakT = GcNaksatra.calculateMidnightNaksatra(t.date, earth);
                        double midNakU = GcNaksatra.calculateMidnightNaksatra(u.date, earth);
                        if ((Cal.ROHINI_NAKSATRA == midNakU) && (midNakT == Cal.ROHINI_NAKSATRA)) {
                            if ((u.date.dayOfWeek == 1) || (u.date.dayOfWeek == 3)) {
                                u.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                                v.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                                v.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                            } else {
                                t.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                                u.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                                u.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                            }
                        } else if (midNakT == Cal.ROHINI_NAKSATRA) {
                            t.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                            u.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                            u.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                        } else if (midNakU == Cal.ROHINI_NAKSATRA) {
                            u.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                            v.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                            v.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                        } else {
                            if ((u.date.dayOfWeek == 1) || (u.date.dayOfWeek == 3)) {
                                u.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                                v.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                                v.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                            } else {
                                t.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                                u.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                                u.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                            }
                        }
                    } else if (t.astrodata.nNaksatra == Cal.ROHINI_NAKSATRA) {
                        t.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                        u.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                        u.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                    } else if (u.astrodata.nNaksatra == Cal.ROHINI_NAKSATRA) {
                        u.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                        v.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                        v.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                    } else {
                        if ((u.date.dayOfWeek == 1) || (u.date.dayOfWeek == 3)) {
                            u.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                            v.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                            v.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                        } else {
                            t.addSpecFestival(Cal.SPEC_JANMASTAMI, Cal.CAL_FEST_0);
                            u.addSpecFestival(Cal.SPEC_NANDAUTSAVA, Cal.CAL_FEST_1);
                            u.addSpecFestival(Cal.SPEC_PRABHAPP, Cal.CAL_FEST_2);
                        }
                    }
                }
            }
        }

        // RathaYatra group
        if (t.astrodata.nMasa == Cal.VAMANA_MASA) {
            if (isFestivalDay(s, t, Cal.TITHI_GAURA_DVITIYA)) {
                t.addSpecFestival(Cal.SPEC_RATHAYATRA, Cal.CAL_FEST_1);
            }
            if (nIndex > 4) {
                if (isFestivalDay(mData[nIndex - 5], mData[nIndex - 4], Cal.TITHI_GAURA_DVITIYA)) {
                    t.addSpecFestival(Cal.SPEC_HERAPANCAMI, Cal.CAL_FEST_1);
                }
            }
            if (nIndex > 8) {
                if (isFestivalDay(mData[nIndex - 9], mData[nIndex - 8], Cal.TITHI_GAURA_DVITIYA)) {
                    t.addSpecFestival(Cal.SPEC_RETURNRATHA, Cal.CAL_FEST_1);
                }
            }
            if (isFestivalDay(mData[nIndex], mData[nIndex + 1], Cal.TITHI_GAURA_DVITIYA)) {
                t.addSpecFestival(Cal.SPEC_GUNDICAMARJANA, Cal.CAL_FEST_1);
            }
        }

        // Gaura Purnima
        if (s.astrodata.nMasa == Cal.GOVINDA_MASA) {
            if (isFestivalDay(s, t, Cal.TITHI_PURNIMA)) {
                t.addSpecFestival(Cal.SPEC_GAURAPURNIMA, Cal.CAL_FEST_0);
            }
        }

        // Jagannatha Misra festival
        if (mData[nIndex - 2].astrodata.nMasa == Cal.GOVINDA_MASA) {
            if (isFestivalDay(mData[nIndex - 2], s, Cal.TITHI_PURNIMA)) {
                t.addSpecFestival(Cal.SPEC_MISRAFESTIVAL, Cal.CAL_FEST_1);
            }
        }

        // events.json festivals
        checkFestivals(s, t, u, v);
    }

    private void extendedCalc(int nIndex, EarthData earth) {
        CalendarDay s = mData[nIndex - 1];
        CalendarDay t = mData[nIndex];
        CalendarDay u = mData[nIndex + 1];

        if ((t.astrodata.nMasa == Cal.VISNU_MASA) && (t.astrodata.getPaksa() == Cal.GAURA_PAKSA)) {
            if (isFestivalDay(s, t, Cal.TITHI_GAURA_NAVAMI)) {
                if (u.nFastType >= Cal.FAST_EKADASI) {
                    s.addSpecFestival(Cal.SPEC_RAMANAVAMI, Cal.CAL_FEST_0);
                } else {
                    t.addSpecFestival(Cal.SPEC_RAMANAVAMI, Cal.CAL_FEST_0);
                }
            }
        }
    }

    private void calculateEParana(CalendarDay s, CalendarDay t, EarthData earth) {
        t.nMhdType = Cal.EV_NULL;
        t.ekadasiParana = true;
        t.nFastType = Cal.FAST_NULL;

        double parBeg = -1.0;
        double parEnd = -1.0;

        double sunRise = t.astrodata.sun.sunriseDeg / 360.0 + earth.tzone / 24.0;
        double thirdDay = sunRise + t.astrodata.sun.lengthDeg / 1080.0;
        double[] tt = GcTithi.getTithiTimes(earth, t.date, sunRise);
        double titEnd = tt[2];
        double tithiLen = tt[0];
        double titBeg = tt[1];
        double tithiQuart = tithiLen / 4.0 + titBeg;

        if (s.nMhdType == Cal.EV_UNMILANI) {
            parEnd = titEnd;
            t.eparanaType2 = Cal.EP_TYPE_TEND;
            if (parEnd > thirdDay) {
                parEnd = thirdDay;
                t.eparanaType2 = Cal.EP_TYPE_3DAY;
            }
            parBeg = sunRise;
            t.eparanaType1 = Cal.EP_TYPE_SUNRISE;
        } else if (s.nMhdType == Cal.EV_VYANJULI) {
            parBeg = sunRise;
            t.eparanaType1 = Cal.EP_TYPE_SUNRISE;
            parEnd = Math.min(titEnd, thirdDay);
            t.eparanaType2 = (parEnd == titEnd) ? Cal.EP_TYPE_TEND : Cal.EP_TYPE_3DAY;
        } else if (s.nMhdType == Cal.EV_TRISPRSA) {
            parBeg = sunRise;
            parEnd = thirdDay;
            t.eparanaType1 = Cal.EP_TYPE_SUNRISE;
            t.eparanaType2 = Cal.EP_TYPE_3DAY;
        } else if (s.nMhdType == Cal.EV_JAYANTI || s.nMhdType == Cal.EV_VIJAYA) {
            double naksEnd = GcNaksatra.getEndHour(earth, s.date, t.date);
            if (GcTithi.tithiDvadasi(t.astrodata.nTithi)) {
                if (naksEnd < titEnd) {
                    if (naksEnd < thirdDay) {
                        parBeg = naksEnd;
                        t.eparanaType1 = Cal.EP_TYPE_NAKEND;
                        parEnd = Math.min(titEnd, thirdDay);
                        t.eparanaType2 = (parEnd == titEnd) ? Cal.EP_TYPE_TEND : Cal.EP_TYPE_3DAY;
                    } else {
                        parBeg = naksEnd;
                        t.eparanaType1 = Cal.EP_TYPE_NAKEND;
                        parEnd = titEnd;
                        t.eparanaType2 = Cal.EP_TYPE_TEND;
                    }
                } else {
                    parBeg = sunRise;
                    t.eparanaType1 = Cal.EP_TYPE_SUNRISE;
                    parEnd = Math.min(titEnd, thirdDay);
                    t.eparanaType2 = (parEnd == titEnd) ? Cal.EP_TYPE_TEND : Cal.EP_TYPE_3DAY;
                }
            } else {
                parBeg = sunRise;
                t.eparanaType1 = Cal.EP_TYPE_SUNRISE;
                parEnd = Math.min(naksEnd, thirdDay);
                t.eparanaType2 = (parEnd == naksEnd) ? Cal.EP_TYPE_NAKEND : Cal.EP_TYPE_3DAY;
            }
        } else if (s.nMhdType == Cal.EV_JAYA || s.nMhdType == Cal.EV_PAPA_NASINI) {
            double naksEnd = GcNaksatra.getEndHour(earth, s.date, t.date);
            if (GcTithi.tithiDvadasi(t.astrodata.nTithi)) {
                if (naksEnd < titEnd) {
                    if (naksEnd < thirdDay) {
                        parBeg = naksEnd;
                        t.eparanaType1 = Cal.EP_TYPE_NAKEND;
                        parEnd = Math.min(titEnd, thirdDay);
                        t.eparanaType2 = (parEnd == titEnd) ? Cal.EP_TYPE_TEND : Cal.EP_TYPE_3DAY;
                    } else {
                        parBeg = naksEnd;
                        t.eparanaType1 = Cal.EP_TYPE_NAKEND;
                        parEnd = titEnd;
                        t.eparanaType2 = Cal.EP_TYPE_TEND;
                    }
                } else {
                    parBeg = sunRise;
                    t.eparanaType1 = Cal.EP_TYPE_SUNRISE;
                    parEnd = Math.min(titEnd, thirdDay);
                    t.eparanaType2 = (parEnd == titEnd) ? Cal.EP_TYPE_TEND : Cal.EP_TYPE_3DAY;
                }
            } else {
                if (naksEnd < thirdDay) {
                    parBeg = naksEnd;
                    t.eparanaType1 = Cal.EP_TYPE_NAKEND;
                    parEnd = thirdDay;
                    t.eparanaType2 = Cal.EP_TYPE_3DAY;
                } else {
                    parBeg = naksEnd;
                    t.eparanaType1 = Cal.EP_TYPE_NAKEND;
                    parEnd = -1.0;
                    t.eparanaType2 = Cal.EP_TYPE_NULL;
                }
            }
        } else {
            parEnd = Math.min(titEnd, thirdDay);
            t.eparanaType2 = (parEnd == titEnd) ? Cal.EP_TYPE_TEND : Cal.EP_TYPE_3DAY;
            parBeg = Math.max(sunRise, tithiQuart);
            t.eparanaType1 = (parBeg == sunRise) ? Cal.EP_TYPE_SUNRISE : Cal.EP_TYPE_4TITHI;

            if (GcTithi.tithiDvadasi(s.astrodata.nTithi)) {
                parBeg = sunRise;
                t.eparanaType1 = Cal.EP_TYPE_SUNRISE;
            }

            if (parBeg > parEnd) {
                parEnd = -1.0;
                t.eparanaType2 = Cal.EP_TYPE_NULL;
            }
        }

        double begin = parBeg;
        double end = parEnd;
        if (begin > 0.0) {
            begin *= 24.0;
        }
        if (end > 0.0) {
            end *= 24.0;
        }
        t.eparanaTime1 = begin;
        t.eparanaTime2 = end;
    }

    private void resolveFestivalsFasting(int nIndex) {
        CalendarDay s = mData[nIndex - 1];
        CalendarDay t = mData[nIndex];
        CalendarDay u = mData[nIndex + 1];

        int fasting = 0;

        if (t.nMhdType != Cal.EV_NULL) {
            t.addEvent(Cal.PRIO_EKADASI, Cal.CAL_EKADASI_PARANA,
                    GcStrings.getString(87) + " " + t.ekadasiVrataName);
        }

        String ch = GcStrings.getMahadvadasiName(t.nMhdType);
        if (ch != null) {
            t.addEvent(Cal.PRIO_MAHADVADASI, Cal.CAL_EKADASI_PARANA, ch);
        }

        if (t.ekadasiParana) {
            t.addEvent(Cal.PRIO_EKADASI_PARANA, Cal.CAL_EKADASI_PARANA, t.getTextEP());
        }

        // Iterate by index so events appended during iteration are visited (as in Python).
        for (int k = 0; k < t.dayEvents.size(); k++) {
            CalendarDay.DayEvent md = t.dayEvents.get(k);
            int nftype = 0;
            String subject = null;
            if (md.fasttype != null) {
                nftype = md.fasttype;
                subject = md.fastsubject;
            }

            if (nftype != 0) {
                if (s.nFastType == Cal.FAST_EKADASI) {
                    // getValue(42) == 0
                    s.addEvent(Cal.PRIO_FASTING, Cal.DISP_ALWAYS,
                            "(Fast today for " + subject + ")");
                    t.addEvent(md.prio + 1, md.disp, GcStrings.getString(860));
                } else if (t.nFastType == Cal.FAST_EKADASI) {
                    // getValue(42) == 0 -> getString(756)
                    t.addEvent(md.prio + 1, md.disp, GcStrings.getString(756));
                } else {
                    // getValue(42) == 0
                    if (nftype > 1) {
                        nftype = 7;
                    } else {
                        nftype = 0;
                    }
                    if (nftype != 0) {
                        t.addEvent(md.prio + 1, md.disp, GcStrings.getFastingName(0x200 + nftype));
                    }
                }
            }
            if (fasting < nftype) {
                fasting = nftype;
            }
        }

        if (fasting != 0) {
            if (s.nFastType == Cal.FAST_EKADASI) {
                t.nFeasting = Cal.FEAST_TODAY_FAST_YESTERDAY;
                s.nFeasting = Cal.FEAST_TOMMOROW_FAST_TODAY;
            } else if (t.nFastType == Cal.FAST_EKADASI) {
                u.nFeasting = Cal.FEAST_TODAY_FAST_YESTERDAY;
                t.nFeasting = Cal.FEAST_TOMMOROW_FAST_TODAY;
            } else {
                t.nFastType = 0x200 + fasting;
            }
        }
    }

    private void computeSankranti(EarthData earth) {
        // Faithful port of TCalendar's sankranti loop with GetSankrantiType() == 2.
        // In the reference, the type-2 branch executes `break` unconditionally on the
        // first for-iteration (i == 0), before the `if i_target >= 0:` assignment block
        // is reached, so bFoundSan never becomes True and no sankranti is ever recorded.
        // Replicated exactly here (the outer while therefore runs a single pass).
        GcGregorianDate date = new GcGregorianDate(mData[0].date);
        boolean bFoundSan = true;

        while (bFoundSan) {
            GcSankranti.Result r = GcSankranti.getNextSankranti(date);
            date.set(r.date);
            // determineDaylightStatus == 0
            date.normalizeValues();

            bFoundSan = false;
            for (int i = 0; i < mNCount - 1; i++) {
                // GetSankrantiType() == 2 (noon-to-noon): compute i_target then break,
                // unconditionally, at the first iteration.
                break;
            }
            date.nextDay();
            date.nextDay();
        }
    }
}

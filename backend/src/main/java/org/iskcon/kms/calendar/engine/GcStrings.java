package org.iskcon.kms.calendar.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Faithful port of the GCStrings.py lookups needed by calendar building. */
final class GcStrings {

    private static final Map<String, String> STRINGS = load();

    private GcStrings() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load() {
        try (InputStream in = GcStrings.class.getResourceAsStream("/calendar/strings.json")) {
            if (in == null) {
                throw new IllegalStateException("calendar/strings.json not found on classpath");
            }
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> raw = mapper.readValue(in, Map.class);
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
            return out;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load calendar/strings.json", ex);
        }
    }

    static String getString(int i) {
        return STRINGS.getOrDefault(String.valueOf(i), "");
    }

    static String getEkadasiName(int nMasa, int nPaksa) {
        return getString(560 + nMasa * 2 + nPaksa);
    }

    /** Mirror of GetMahadvadasiName; returns {@code null} for NULL/SUDDHA. */
    static String getMahadvadasiName(int i) {
        if (i == Cal.EV_NULL || i == Cal.EV_SUDDHA) {
            return null;
        } else if (i == Cal.EV_UNMILANI) {
            return getString(733);
        } else if (i == Cal.EV_TRISPRSA || i == Cal.EV_UNMILANI_TRISPRSA) {
            return getString(734);
        } else if (i == Cal.EV_PAKSAVARDHINI) {
            return getString(735);
        } else if (i == Cal.EV_JAYA) {
            return getString(736);
        } else if (i == Cal.EV_VIJAYA) {
            return getString(737);
        } else if (i == Cal.EV_PAPA_NASINI) {
            return getString(738);
        } else if (i == Cal.EV_JAYANTI) {
            return getString(739);
        } else if (i == Cal.EV_VYANJULI) {
            return getString(740);
        }
        return null;
    }

    /** Mirror of GetFastingName; returns {@code null} when no match. */
    static String getFastingName(int i) {
        if (i == Cal.FAST_NOON) {
            return getString(751);
        }
        if (i == Cal.FAST_SUNSET) {
            return getString(752);
        }
        if (i == Cal.FAST_MOONRISE) {
            return getString(753);
        }
        if (i == Cal.FAST_DUSK) {
            return getString(754);
        }
        if (i == Cal.FAST_MIDNIGHT) {
            return getString(755);
        }
        if (i == Cal.FAST_DAY) {
            return getString(756);
        }
        return null;
    }
}

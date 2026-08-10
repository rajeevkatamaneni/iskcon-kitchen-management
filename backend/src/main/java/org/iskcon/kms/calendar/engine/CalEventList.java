package org.iskcon.kms.calendar.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Faithful port of GCEventList.py (events.json + eventfast.json 'newfast' override). */
final class CalEventList {

    /** One festival definition from events.json (mirror of GCEvent). */
    static final class SourceEvent {
        int cls;
        int tithi;
        int masa;
        int fast;
        int visible;
        int used;
        int spec;
        int start;
        String text = "";
        String fastSubj = "";
    }

    private static final List<SourceEvent> EVENTS = load();

    private CalEventList() {
    }

    static List<SourceEvent> getList() {
        return EVENTS;
    }

    private static List<SourceEvent> load() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<SourceEvent> list = new ArrayList<>();
            try (InputStream in = CalEventList.class.getResourceAsStream("/calendar/events.json")) {
                if (in == null) {
                    throw new IllegalStateException("calendar/events.json not found");
                }
                JsonNode arr = mapper.readTree(in);
                for (JsonNode e : arr) {
                    SourceEvent se = new SourceEvent();
                    se.cls = e.path("cls").asInt(0);
                    se.tithi = e.path("tithi").asInt(0);
                    se.masa = e.path("masa").asInt(0);
                    se.fast = e.path("fast").asInt(0);
                    se.visible = e.path("visible").asInt(1);
                    se.used = e.path("used").asInt(1);
                    se.spec = e.path("spec").asInt(0);
                    se.start = e.path("start").asInt(-10000);
                    se.text = e.path("text").asText("");
                    se.fastSubj = e.path("fastSubj").asText("");
                    list.add(se);
                }
            }
            // SetOldStyleFasting(GCDS.getValue(42) == 0 -> use 'newfast')
            applyFasting(mapper, list);
            return list;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load calendar events", ex);
        }
    }

    private static void applyFasting(ObjectMapper mapper, List<SourceEvent> list) throws Exception {
        try (InputStream in = CalEventList.class.getResourceAsStream("/calendar/eventfast.json")) {
            if (in == null) {
                throw new IllegalStateException("calendar/eventfast.json not found");
            }
            JsonNode arr = mapper.readTree(in);
            // key = 'newfast' because OSFA display setting defaults to 0 (old style off)
            for (JsonNode a : arr) {
                int masa = a.path("masa").asInt();
                int tithi = a.path("tithi").asInt();
                int cls = a.path("cls").asInt();
                int newfast = a.path("newfast").asInt();
                for (SourceEvent pce : list) {
                    if (pce.masa == masa && pce.tithi == tithi && pce.cls == cls) {
                        pce.fast = newfast;
                        break;
                    }
                }
            }
        }
    }
}

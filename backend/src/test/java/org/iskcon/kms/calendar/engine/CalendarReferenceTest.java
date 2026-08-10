package org.iskcon.kms.calendar.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The acceptance gate: reproduce the reference GCAL output for Bengaluru
 * day-for-day over 400 days.
 *
 * <p>Plain JUnit 5 — no Spring, no Testcontainers, no Docker.
 *
 * <p>Festival comparison: a reference {@code events[]} entry is treated as a
 * festival iff its {@code disp} is a festival class (CAL_FEST_0..CAL_FEST_6,
 * i.e. 22..28) — these are exactly the lines TCalendar emits as {@code
 * <festival>} (priority PRIO_FESTIVALS_0..6). The engine's festival set is the
 * {@code text} of every day event with the same disp range. Non-festival event
 * lines (ekadasi-info disp 17, ksaya disp 7, caturmasya disp 13, always-notes
 * disp -1) are excluded on both sides.
 */
class CalendarReferenceTest {

    private static final int FEST_DISP_MIN = 22;
    private static final int FEST_DISP_MAX = 28;

    @Test
    void reproducesBengaluru400DayReference() throws Exception {
        JsonNode root;
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in =
                getClass().getResourceAsStream("/calendar/blr-reference-2025.json")) {
            assertThat(in).as("reference JSON on classpath").isNotNull();
            root = mapper.readTree(in);
        }

        JsonNode refDays = root.get("days");
        int count = root.get("count").asInt();
        assertThat(count).isEqualTo(400);
        assertThat(refDays.size()).isEqualTo(400);

        List<CalendarDayResult> result = new VaishnavaCalendarEngine()
                .computeRange(12.9716, 77.5946, 5.5, LocalDate.of(2024, 12, 20), 400);
        assertThat(result).hasSize(400);

        int matched = 0;
        for (int i = 0; i < 400; i++) {
            JsonNode rd = refDays.get(i);
            JsonNode astro = rd.get("astrodata");
            CalendarDayResult cd = result.get(i);

            String where = "day " + i + " (" + rd.get("date").get("year").asInt() + "-"
                    + rd.get("date").get("month").asInt() + "-" + rd.get("date").get("day").asInt()
                    + ")";

            assertThat(cd.tithi()).as("tithi " + where).isEqualTo(astro.get("tithi").asInt());
            assertThat(cd.masa()).as("masa " + where).isEqualTo(astro.get("masa").asInt());
            assertThat(cd.naksatra()).as("naksatra " + where)
                    .isEqualTo(astro.get("naksatra").asInt());
            assertThat(cd.yoga()).as("yoga " + where).isEqualTo(astro.get("yoga").asInt());
            assertThat(cd.gaurabdaYear()).as("gaurabda_year " + where)
                    .isEqualTo(astro.get("gaurabda_year").asInt());
            assertThat(cd.paksa()).as("paksa " + where).isEqualTo(cd.tithi() >= 15 ? 1 : 0);

            assertThat(cd.fastCode()).as("fast " + where).isEqualTo(rd.get("fast").asInt());
            assertThat(cd.mahadvadashiCode()).as("ekadashiType " + where)
                    .isEqualTo(rd.get("ekadashiType").asInt());
            assertThat(cd.ekadashiName()).as("ekadashiName " + where)
                    .isEqualTo(rd.get("ekadashiName").asText());

            assertThat(cd.sunriseDeg()).as("rise_deg " + where)
                    .isCloseTo(astro.get("sun").get("rise_deg").asDouble(), within(1e-6));
            assertThat(cd.sunsetDeg()).as("set_deg " + where)
                    .isCloseTo(astro.get("sun").get("set_deg").asDouble(), within(1e-6));

            Set<String> refFest = new HashSet<>();
            if (rd.has("events")) {
                for (JsonNode e : rd.get("events")) {
                    int disp = e.path("disp").asInt(-999);
                    if (disp >= FEST_DISP_MIN && disp <= FEST_DISP_MAX) {
                        refFest.add(e.get("text").asText());
                    }
                }
            }
            Set<String> engFest = new HashSet<>();
            for (CalendarFestival f : cd.festivals()) {
                engFest.add(f.text());
            }
            assertThat(engFest).as("festivals " + where).isEqualTo(refFest);

            matched++;
        }
        assertThat(matched).isEqualTo(400);
    }
}

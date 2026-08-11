package org.iskcon.kms.staff;

import java.time.LocalTime;

/** One weekday of the recurring template (E6-S1). ISO {@code dayOfWeek}: 1=Mon … 7=Sun. */
public record ScheduleDay(int dayOfWeek, boolean working, LocalTime startTime, LocalTime endTime) {
}

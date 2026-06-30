package com.example.webboard.content.train;

/**
 * DepartureRecord — one historical arrival or departure event at a station.
 *
 * <p>Built either from CRN's {@code TrainArrivalAndDepartureEvent} (when CRN is installed)
 * or from our own polling of {@code TrainSnapshot.navigationTarget} transitions (degraded
 * mode). The dashboard renders these as a per-station timetable + a Gantt-style strip
 * showing each train's recent dwell time.
 *
 * <p>{@code direction} is {@code "arrival"} (train just stopped at the station) or
 * {@code "departure"} (train just left). One stop generates one arrival + one departure
 * record (the departure's ts - the arrival's ts = dwell time).
 */
public record DepartureRecord(
        long ts,
        String trainId,
        String trainName,
        String stationName,
        String lineId,
        String direction,
        String platform
) {
    public static final String ARRIVAL = "arrival";
    public static final String DEPARTURE = "departure";

    public static DepartureRecord arrival(long ts, String trainId, String trainName,
                                          String stationName, String lineId, String platform) {
        return new DepartureRecord(ts, trainId, trainName, stationName, lineId, ARRIVAL, platform);
    }

    public static DepartureRecord departure(long ts, String trainId, String trainName,
                                            String stationName, String lineId, String platform) {
        return new DepartureRecord(ts, trainId, trainName, stationName, lineId, DEPARTURE, platform);
    }

    public boolean isArrival() { return ARRIVAL.equals(direction); }
    public boolean isDeparture() { return DEPARTURE.equals(direction); }
}

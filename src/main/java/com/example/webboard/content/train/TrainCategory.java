package com.example.webboard.content.train;

/**
 * TrainCategory — user-defined grouping for trains (e.g. "Freight", "Express Passenger").
 *
 * <p>Conceptually mirrors CRN's {@code TrainCategory} but lives in our own storage so it works
 * without CRN installed. When CRN is present, {@code CrnBridge} syncs our categories into CRN's
 * data model (one-way: our store is authoritative for the dashboard).
 *
 * <p>{@code freightType} is one of:
 * <ul>
 *   <li>{@code "freight"} — cargo trains</li>
 *   <li>{@code "passenger"} — passenger trains</li>
 *   <li>{@code "mixed"} — both</li>
 *   <li>{@code "other"} — service / shunting / decoration</li>
 * </ul>
 */
public record TrainCategory(
        String id,
        String name,
        int color,
        String freightType
) {
    public static final String FREIGHT = "freight";
    public static final String PASSENGER = "passenger";
    public static final String MIXED = "mixed";
    public static final String OTHER = "other";

    public static TrainCategory create(String id, String name, int color, String freightType) {
        return new TrainCategory(id, name, color, normalize(freightType));
    }

    /** Coerce arbitrary input to a valid freightType; defaults to {@link #OTHER}. */
    public static String normalize(String t) {
        if (t == null) return OTHER;
        return switch (t) {
            case FREIGHT, PASSENGER, MIXED, OTHER -> t;
            default -> OTHER;
        };
    }
}

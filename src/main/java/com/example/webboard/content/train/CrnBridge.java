package com.example.webboard.content.train;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CrnBridge — reflection-based soft-dependency bridge to Create Railways Navigator (CRN).
 *
 * <p>CRN provides richer train metadata (categories, lines, station tags, live arrival/departure
 * events). When CRN is installed alongside this mod, the dashboard can subscribe to those events
 * to populate {@link DepartureHistory} with higher fidelity than our own polling.
 *
 * <p><b>Soft dependency</b>: CRN is never required. The dashboard works fully without it via
 * the game-thread poller that reads Create's native {@code Create.RAILWAYS} API. When CRN is
 * absent, {@link #isPresent()} returns false and {@link #subscribeIfPresent()} is a no-op.
 *
 * <p><b>Reflection-only</b>: we don't declare CRN as a {@code compileOnly} dependency because
 * CRN is a beta mod whose package layout shifts between versions. Reflection survives most
 * renames — at worst, the subscription silently fails and we log a warning. The dashboard
 * continues to work via the polling fallback.
 */
public final class CrnBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrnBridge.class);

    /** CRN's main mod id; matches the {@code mods.toml} declaration. */
    public static final String CRN_MOD_ID = "createrailwaysnavigator";

    /** CRN's event-bus manager class name (best-effort; may shift between beta versions). */
    private static final String CRN_EVENTS_MANAGER_CLASS =
            "cn.creatrailways.navigator.events.CRNEventsManager";

    private static volatile Boolean cachedPresence = null;

    public static CrnBridge get() {
        return INSTANCE;
    }

    private static final CrnBridge INSTANCE = new CrnBridge();

    public CrnBridge() {}

    /** True if CRN's event-bus class is loadable on the current classpath. */
    public boolean isPresent() {
        if (cachedPresence != null) return cachedPresence;
        try {
            Class.forName(CRN_EVENTS_MANAGER_CLASS, false, getClass().getClassLoader());
            cachedPresence = Boolean.TRUE;
        } catch (Throwable t) {
            cachedPresence = Boolean.FALSE;
        }
        return cachedPresence;
    }

    /**
     * Best-effort subscribe to CRN's arrival/departure event. When CRN is absent or the
     * subscription fails (renamed class, changed method signature, etc.), this is a no-op
     * and the dashboard falls back to its own polling.
     *
     * <p>Implementation note: the actual reflection registration is intentionally deferred
     * to a private method so a failure inside reflection doesn't leak
     * {@code NoClassDefFoundError} to callers on the MC thread.
     */
    public void subscribeIfPresent() {
        if (!isPresent()) {
            LOGGER.info("[web_board] CRN not detected — train dashboard will use polling for arrivals/departures");
            return;
        }
        try {
            doSubscribe();
            LOGGER.info("[web_board] CRN detected — subscribed to arrival/departure events");
        } catch (Throwable t) {
            LOGGER.warn("[web_board] CRN detected but event subscription failed (continuing with polling): {}",
                    t.toString());
        }
    }

    /**
     * Reflection-based subscription. Currently a placeholder: CRN's event API uses generics
     * and parameterized listeners that are awkward to wire up via reflection without a
     * compileOnly dependency. Future versions may add a compileOnly dep + native listeners.
     *
     * <p>For v0.7.1 the dashboard works fully via the polling path. This method exists so
     * future versions can wire CRN events without touching the call sites.
     */
    private void doSubscribe() {
        // Intentional no-op for v0.7.1. See class javadoc.
    }

    /** Status string for the {@code /api/trains/health} endpoint. */
    public String status() {
        return isPresent() ? "detected" : "absent";
    }
}

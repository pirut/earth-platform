package io.earthplatform.minecraft.cubic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server thread. Which trip each player is on now. A trip sets off with a token of its own; every
 * later step of it (its terrain fetch, a random trip's land search, its arrival) checks the token
 * is still the player's and is dropped if not, so a stale step never lands anyone. A newer trip
 * supersedes an older one, and calling a trip off (a personal spawn given up for the server's, a
 * death, another dimension) supersedes it with none. A trip dropped before it landed leaves its
 * wait, so a new try of the same place keeps its clock (see {@link ArrivalWait.Wait#carried}).
 */
public final class Trips {
    private record Carried(double latitude, double longitude, ArrivalWait.Wait waited) {}

    private final Map<UUID, Long> current = new HashMap<>();
    private final Map<UUID, Carried> carried = new HashMap<>();
    private long next;

    /** A player sets off: a new token, which supersedes any trip they were on. */
    public long begin(UUID player) {
        long token = ++next;
        current.put(player, token);
        return token;
    }

    /** Whether {@code token} is still the trip the player is on. */
    public boolean current(UUID player, long token) {
        Long now = current.get(player);
        return now != null && now == token;
    }

    /** Whether the player is on a trip still to land. */
    public boolean pending(UUID player) { return current.containsKey(player); }

    /** The trip landed or failed: the player is on none, if it was still theirs. */
    public void end(UUID player, long token) {
        if (current(player, token)) current.remove(player);
    }

    /** Calls off whatever trip the player is on; its later steps are dropped. */
    public void cancel(UUID player) {
        current.remove(player);
        carried.remove(player);
    }

    /** A trip there was dropped before it landed: its wait goes to a new try of the same place. */
    public void remember(UUID player, double latitude, double longitude, ArrivalWait.Wait wait) {
        carried.put(player, new Carried(latitude, longitude, wait));
    }

    /** The wait for a new arrival there: what a dropped try of the same place had waited, else a new one. */
    public ArrivalWait.Wait resume(UUID player, double latitude, double longitude) {
        Carried had = carried.remove(player);
        return had != null && had.latitude == latitude && had.longitude == longitude ? had.waited.carried() : ArrivalWait.Wait.NEW;
    }

    /** The player arrived: nothing to carry. */
    public void landed(UUID player) { carried.remove(player); }

    public void clear() {
        current.clear();
        carried.clear();
    }

    /**
     * Whether a pending trip is called off: its player left the server, died (and lies on the death
     * screen), or went to another dimension (a Nether portal) since it set off, where landing them
     * would pull them back.
     */
    public static boolean calledOff(boolean removed, boolean dead, Object setOffFrom, Object now) {
        return whyCalledOff(removed, dead, setOffFrom, now) != null;
    }

    /** As {@link #calledOff}: the one line telling the player why, or null when the trip goes on. */
    @javax.annotation.Nullable public static String whyCalledOff(boolean removed, boolean dead, Object setOffFrom, Object now) {
        if (removed) return "The trip was called off: you left.";
        if (dead) return "The trip was called off: you died.";
        if (!Objects.equals(setOffFrom, now)) return "The trip was called off: you went to another dimension.";
        return null;
    }
}

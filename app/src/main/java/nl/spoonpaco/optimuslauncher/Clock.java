package nl.spoonpaco.optimuslauncher;

/**
 * Tijd met correctie: zonder internet valt de tabletklok terug naar 2017.
 * Optimus Link stuurt de telefoontijd mee; daarmee corrigeren we datum/tijd
 * voor het scherm, de logbestanden en de ritten (de systeemklok zelf mag een app niet zetten).
 */
public final class Clock {

    /** Verschil telefoon - tablet in ms (0 = geen correctie nodig/bekend). */
    public static volatile long offsetMs = 0L;
    public static volatile boolean corrected = false;

    private Clock() { }

    public static long now() {
        return System.currentTimeMillis() + offsetMs;
    }

    /** Aanroepen met de tijd die de telefoon meestuurt. */
    public static void fromPhone(long phoneMillis) {
        long diff = phoneMillis - System.currentTimeMillis();
        // Kleine verschillen (netwerkvertraging) negeren; grote (verkeerde klok) corrigeren.
        if (Math.abs(diff) > 5000) {
            offsetMs = diff;
            corrected = true;
        } else if (corrected && Math.abs(diff - offsetMs) > 5000) {
            offsetMs = 0;
            corrected = false;
        }
    }
}

package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tijd met correctie: zonder internet valt de tabletklok terug naar 2017.
 * De GPS-satellieten geven de echte tijd mee; daarmee corrigeren we datum/tijd
 * voor het scherm, de logbestanden en de ritten (de systeemklok zelf mag een app niet zetten).
 * Tot de eerste GPS-fix gebruiken we de laatst bekende goede tijd als schatting.
 */
public final class Clock {

    /** Verschil echte tijd - tabletklok in ms. */
    public static volatile long offsetMs = 0L;
    /** "geen", "schatting" (laatst bekende tijd), "GPS". */
    public static volatile String source = "geen";
    public static volatile long lastGpsSync = 0L;

    private static final long MIN_VALID = 1704067200000L; // 1-1-2024
    private static final long MAX_VALID = 2208988800000L; // 1-1-2040
    private static SharedPreferences prefs;
    private static long lastSave = 0L;

    private Clock() { }

    public static void init(Context c) {
        prefs = c.getSharedPreferences("clock", Context.MODE_PRIVATE);
        long known = prefs.getLong("lastGood", 0L);
        long sys = System.currentTimeMillis();
        if (sys < MIN_VALID && known > sys) {
            offsetMs = known - sys;
            source = "schatting";
        }
    }

    public static long now() {
        return System.currentTimeMillis() + offsetMs;
    }

    /** Aanroepen met de tijd van een GPS-positie (Location.getTime()). */
    public static void fromGps(long gpsMillis) {
        if (gpsMillis < MIN_VALID || gpsMillis > MAX_VALID) return; // onzin/weekrollover negeren
        long sys = System.currentTimeMillis();
        long diff = gpsMillis - sys;
        offsetMs = Math.abs(diff) > 5000 ? diff : 0L;
        source = "GPS";
        lastGpsSync = sys;
        if (prefs != null && sys - lastSave > 60_000L) {
            lastSave = sys;
            prefs.edit().putLong("lastGood", gpsMillis).apply();
        }
    }
}

package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Alle bewaarde gegevens: tank, verbruik-kalibratie, prijs en ritdata.
 * Liters in tank = -1 betekent "nog nooit ingevoerd".
 */
public class Store {

    public static final float TANK_LITERS = 57f;
    public static final float CITY_L100 = 9.4f;
    public static final float HIGHWAY_L100 = 6.2f;
    public static final float COMBINED_L100 = 7.4f;
    public static final long NEW_TRIP_AFTER_MS = 30L * 60L * 1000L;

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getSharedPreferences("optimus", Context.MODE_PRIVATE);
    }

    // ---- verbruik ----

    /** Geschat verbruik (l/100km) bij een snelheid, met kalibratiefactor. */
    public float litersPer100At(float kmh) {
        float base;
        if (kmh <= 50f) base = CITY_L100;
        else if (kmh >= 90f) base = HIGHWAY_L100;
        else base = CITY_L100 + (kmh - 50f) / 40f * (HIGHWAY_L100 - CITY_L100);
        return base * calFactor();
    }

    public float calFactor() { return p.getFloat("cal", 1f); }

    // ---- tank ----

    public float tankLiters() { return p.getFloat("tank", -1f); }
    public float price() { return p.getFloat("price", -1f); }

    /** Geschatte resterende km, of -1 als de tank onbekend is. */
    public int rangeKm() {
        float t = tankLiters();
        if (t < 0) return -1;
        return Math.round(t / (COMBINED_L100 * calFactor()) * 100f);
    }

    /**
     * Tankbeurt invoeren. Bij "vol" wordt de kalibratie bijgesteld
     * als er sinds de vorige volle tank genoeg gereden is.
     */
    public void fill(float liters, float pricePerLiter, boolean full) {
        SharedPreferences.Editor e = p.edit();
        if (pricePerLiter > 0) e.putFloat("price", pricePerLiter);

        float addedSinceFull = p.getFloat("addedSinceFull", 0f) + Math.max(0f, liters);

        if (full) {
            boolean hadFull = p.getBoolean("hadFull", false);
            float km = p.getFloat("kmSinceFull", 0f);
            float est = p.getFloat("estSinceFull", 0f);
            if (hadFull && km >= 100f && est > 0.5f && addedSinceFull > 0.5f) {
                float factor = calFactor() * (addedSinceFull / est);
                factor = Math.max(0.6f, Math.min(1.6f, factor));
                // rustig bijsturen: half oud, half nieuw
                e.putFloat("cal", (calFactor() + factor) / 2f);
            }
            e.putFloat("tank", TANK_LITERS);
            e.putBoolean("hadFull", true);
            e.putFloat("kmSinceFull", 0f);
            e.putFloat("estSinceFull", 0f);
            e.putFloat("addedSinceFull", 0f);
        } else {
            float t = tankLiters();
            if (t < 0) t = 0f;
            e.putFloat("tank", Math.min(TANK_LITERS, t + Math.max(0f, liters)));
            e.putFloat("addedSinceFull", addedSinceFull);
        }
        e.apply();
    }

    // ---- rit ----

    public float tripKm() { return p.getFloat("tripKm", 0f); }
    public float tripMovingH() { return p.getFloat("tripH", 0f); }
    public float tripMaxKmh() { return p.getFloat("tripMax", 0f); }
    public float tripLiters() { return p.getFloat("tripL", 0f); }
    public long lastMoveTime() { return p.getLong("lastMove", 0L); }

    public float tripAvgKmh() {
        float h = tripMovingH();
        return h > 0.0005f ? tripKm() / h : 0f;
    }

    /** Ritkosten in euro, of -1 als er geen prijs bekend is. */
    public float tripCost() {
        float pr = price();
        return pr > 0 ? tripLiters() * pr : -1f;
    }

    public void startNewTrip() {
        p.edit()
            .putFloat("lastTripKm", tripKm())
            .putFloat("lastTripAvg", tripAvgKmh())
            .putFloat("lastTripMax", tripMaxKmh())
            .putFloat("lastTripL", tripLiters())
            .putFloat("tripKm", 0f).putFloat("tripH", 0f)
            .putFloat("tripMax", 0f).putFloat("tripL", 0f)
            .apply();
    }

    /** Eén stukje gereden afstand verwerken. */
    public void addSegment(float km, float hours, float kmh, long now) {
        float liters = litersPer100At(kmh) / 100f * km;
        SharedPreferences.Editor e = p.edit();
        e.putFloat("tripKm", tripKm() + km);
        e.putFloat("tripH", tripMovingH() + hours);
        e.putFloat("tripMax", Math.max(tripMaxKmh(), kmh));
        e.putFloat("tripL", tripLiters() + liters);
        e.putFloat("kmSinceFull", p.getFloat("kmSinceFull", 0f) + km);
        e.putFloat("estSinceFull", p.getFloat("estSinceFull", 0f) + liters);
        float t = tankLiters();
        if (t >= 0) e.putFloat("tank", Math.max(0f, t - liters));
        e.putLong("lastMove", now);
        e.apply();
    }

    public void touchMove(long now) {
        p.edit().putLong("lastMove", now).apply();
    }
}

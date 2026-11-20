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

    /**
     * Verbruik (l/100km) bij CONSTANTE snelheid, met kalibratiefactor.
     * Stationair draaien en optrekken worden apart opgeteld (addFuel), zodat
     * stad (veel stoppen/optrekken) vanzelf hoger uitkomt dan snelweg.
     * ~5,5 bij 20 km/u, ~5,3 bij 50, ~6,7 bij 100, ~8,1 bij 130.
     */
    public float litersPer100At(float kmh) {
        float v = Math.max(kmh, 10f);
        float base = 4.2f + 0.00022f * v * v + 25f / v;
        return base * calFactor();
    }

    /** Stationair verbruik in l/uur. */
    public static final float IDLE_LPH = 0.8f;

    /** Extra brandstof (stationair/optrekken) bij de rit en de tank optellen; kalibratie wordt toegepast. */
    public void addFuel(float liters) {
        if (liters <= 0f) return;
        liters *= calFactor();
        SharedPreferences.Editor e = p.edit();
        e.putFloat("tripL", tripLiters() + liters);
        e.putFloat("estSinceFull", p.getFloat("estSinceFull", 0f) + liters);
        float t = tankLiters();
        if (t >= 0) e.putFloat("tank", Math.max(0f, t - liters));
        e.apply();
    }

    public float calFactor() { return p.getFloat("cal", 1f); }

    // ---- tank ----

    public float tankLiters() { return p.getFloat("tank", -1f); }
    public float kmSinceFull() { return p.getFloat("kmSinceFull", 0f); }
    public float estSinceFull() { return p.getFloat("estSinceFull", 0f); }
    public float addedSinceFull() { return p.getFloat("addedSinceFull", 0f); }
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

    // ---- ritsamenvatting ----

    public boolean tripEnded() { return p.getBoolean("tripEnded", true); }
    public boolean summaryPending() { return p.getBoolean("sumPending", false); }
    public void clearSummary() { p.edit().putBoolean("sumPending", false).apply(); }

    /** Rit afsluiten: samenvatting vastleggen (rit-cijfers blijven zichtbaar tot de volgende rit). */
    public void endTrip() {
        float pr = price();
        p.edit()
            .putBoolean("tripEnded", true)
            .putBoolean("sumPending", true)
            .putFloat("sumKm", tripKm())
            .putFloat("sumH", tripMovingH())
            .putFloat("sumAvg", tripAvgKmh())
            .putFloat("sumMax", tripMaxKmh())
            .putFloat("sumL", tripLiters())
            .putFloat("sumCost", pr > 0 ? tripLiters() * pr : -1f)
            .apply();
    }

    public String summaryText() {
        java.util.Locale nl = new java.util.Locale("nl");
        float h = p.getFloat("sumH", 0f);
        int min = Math.round(h * 60f);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(nl, "Afstand: %.1f km\n", p.getFloat("sumKm", 0f)));
        sb.append(min >= 60 ? String.format(nl, "Rijtijd: %d u %02d min\n", min / 60, min % 60)
                            : String.format(nl, "Rijtijd: %d min\n", min));
        sb.append(String.format(nl, "Gemiddeld: %.0f km/u\n", p.getFloat("sumAvg", 0f)));
        sb.append(String.format(nl, "Hoogste: %.0f km/u\n", p.getFloat("sumMax", 0f)));
        sb.append(String.format(nl, "Verbruik: ~%.1f liter", p.getFloat("sumL", 0f)));
        float c = p.getFloat("sumCost", -1f);
        if (c >= 0) sb.append(String.format(nl, "\nKosten: ~€ %.2f", c));
        else sb.append("\nKosten: voer bij Tanken een prijs in");
        return sb.toString();
    }

    /** Regels voor het ritsamenvatting-display. */
    public String[][] summaryRows() {
        java.util.Locale us = java.util.Locale.US;
        float km = p.getFloat("sumKm", 0f), l = p.getFloat("sumL", 0f), c = p.getFloat("sumCost", -1f);
        int min = Math.round(p.getFloat("sumH", 0f) * 60f);
        return new String[][]{
                {"AFSTAND KM", String.format(us, "%.1f", km)},
                {"RIJTIJD", String.format(us, "%d:%02d", min / 60, min % 60)},
                {"GEM KM/U", String.format(us, "%.0f", p.getFloat("sumAvg", 0f))},
                {"MAX KM/U", String.format(us, "%.0f", p.getFloat("sumMax", 0f))},
                {"LITER", String.format(us, "%.2f", l)},
                {"KM/L", l > 0.01f ? String.format(us, "%.1f", km / l) : "--"},
                {"EURO", c >= 0 ? String.format(us, "%.2f", c) : "--"}};
    }

    public String summaryShort() {
        java.util.Locale nl = new java.util.Locale("nl");
        float c = p.getFloat("sumCost", -1f);
        return String.format(nl, "%.1f km · gem %.0f · max %.0f km/u · ~%.1f l",
                p.getFloat("sumKm", 0f), p.getFloat("sumAvg", 0f), p.getFloat("sumMax", 0f), p.getFloat("sumL", 0f))
                + (c >= 0 ? String.format(nl, " · € %.2f", c) : "");
    }

    public void startNewTrip() {
        p.edit()
            .putFloat("lastTripKm", tripKm())
            .putFloat("lastTripAvg", tripAvgKmh())
            .putFloat("lastTripMax", tripMaxKmh())
            .putFloat("lastTripL", tripLiters())
            .putFloat("tripKm", 0f).putFloat("tripH", 0f)
            .putFloat("tripMax", 0f).putFloat("tripL", 0f)
            .putBoolean("tripEnded", false)
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

    // ---- zwevend venster ----

    public boolean overlayEnabled() { return p.getBoolean("overlay", true); }
    public void setOverlayEnabled(boolean on) { p.edit().putBoolean("overlay", on).apply(); }
    public boolean overlayCollapsed() { return p.getBoolean("ovCollapsed", false); }
    public void setOverlayCollapsed(boolean c) { p.edit().putBoolean("ovCollapsed", c).apply(); }
    public int overlayX() { return p.getInt("ovX", 24); }
    public int overlayY() { return p.getInt("ovY", 90); }
    public void setOverlayPos(int x, int y) { p.edit().putInt("ovX", x).putInt("ovY", y).apply(); }

    public void touchMove(long now) {
        p.edit().putLong("lastMove", now).apply();
    }
}

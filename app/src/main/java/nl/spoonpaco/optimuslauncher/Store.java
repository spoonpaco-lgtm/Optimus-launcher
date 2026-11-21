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

    /**
     * Startwaarde van de kalibratie: de oude radio toonde bij Mick vaak 12,2 km/l (= 8,2 l/100 km),
     * het boekje zegt 7,4 gecombineerd -> 8,2 / 7,4 = 1,11. Na de eerste volle tankbeurt neemt
     * de echte meting het over.
     */
    public static final float START_CAL = 1.11f;

    public float calFactor() { return p.getFloat("cal", START_CAL); }

    /** Laatste correctie voor het menu: {km, geschat, getankt, oud, nieuw, datum} of null. */
    public float[] lastCalibration() {
        if (p.getInt("calCount", 0) == 0) return null;
        return new float[]{p.getFloat("calKm", 0), p.getFloat("calEst", 0), p.getFloat("calAdded", 0),
                p.getFloat("calOld", 0), calFactor(), p.getLong("calDate", 0) / 1000f};
    }
    public boolean hadFull() { return p.getBoolean("hadFull", false); }

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
        // zelfde bron als het verbruik op het scherm: gemiddelde van je gelogde ritten (vanaf 50 km)
        if (avgKmPerL > 0) return Math.round(t * avgKmPerL);
        return Math.round(t / (COMBINED_L100 * calFactor()) * 100f);
    }

    /** Gemiddeld verbruik over alle gelogde ritten (km/l), of -1 als er nog te weinig is. Gezet door MainActivity. */
    public static volatile float avgKmPerL = -1f;

    /**
     * Tankbeurt invoeren. Bij "vol" wordt de kalibratie bijgesteld
     * als er sinds de vorige volle tank genoeg gereden is.
     */
    public void fill(float liters, float pricePerLiter, boolean full) { fill(liters, pricePerLiter, full, -1); }

    /**
     * Met kilometerstand van de teller (of -1): bij twee volle tanks met een tellerstand rekent de
     * kalibratie met de echte km in plaats van de GPS-km (de GPS mist het begin van elke rit).
     */
    public void fill(float liters, float pricePerLiter, boolean full, int odo) {
        if (odo > 0) setOdometer(odo);   // tellerstand is de echte stand: GPS-schatting gelijkzetten
        SharedPreferences.Editor e = p.edit();
        if (pricePerLiter > 0) e.putFloat("price", pricePerLiter);

        float addedSinceFull = p.getFloat("addedSinceFull", 0f) + Math.max(0f, liters);

        if (full) {
            boolean hadFull = p.getBoolean("hadFull", false);
            float km = p.getFloat("kmSinceFull", 0f);
            float est = p.getFloat("estSinceFull", 0f);
            int odoPrev = p.getInt("odoAtFull", -1);
            float realKm = odo > 0 && odoPrev > 0 ? odo - odoPrev : -1f;
            boolean useReal = realKm >= 100f && realKm <= 3000f && km >= 50f;
            if (hadFull && (useReal || km >= 100f) && est > 0.5f && addedSinceFull > 0.5f) {
                float old = calFactor();
                // geschat per GPS-km vs echt per echte km (zonder tellerstand: GPS-km voor allebei)
                float ratio = useReal ? (addedSinceFull / realKm) / (est / km) : addedSinceFull / est;
                float factor = Math.max(0.6f, Math.min(1.6f, old * ratio));
                int n = p.getInt("calCount", 0);
                // eerste echte meting: in één keer overnemen; daarna rustig bijsturen (half oud, half nieuw)
                float nw = n == 0 ? factor : (old + factor) / 2f;
                e.putFloat("cal", nw).putInt("calCount", n + 1)
                 .putFloat("calEst", useReal ? est * realKm / km : est).putFloat("calAdded", addedSinceFull).putFloat("calKm", useReal ? realKm : km)
                 .putFloat("calOld", old).putLong("calDate", System.currentTimeMillis()).putBoolean("calReal", useReal);
                CarLogger.note(String.format(java.util.Locale.US,
                        "kalibratie: %.0f km %s (GPS %.0f km), geschat %.1f l, getankt %.1f l -> factor %.3f -> %.3f (%.1f km/l echt)",
                        useReal ? realKm : km, useReal ? "volgens teller" : "volgens GPS", km, est, addedSinceFull, old, nw,
                        (useReal ? realKm : km) / addedSinceFull));
            } else if (hadFull) {
                CarLogger.note(String.format(java.util.Locale.US, "kalibratie overgeslagen: %.0f km sinds vorige volle tank (min. 100), getankt %.1f l",
                        useReal ? realKm : km, addedSinceFull));
            }
            e.putInt("odoAtFull", odo > 0 ? odo : -1);
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
            .putInt("sumScore", tripScore())
            .putString("newRecord", "")
            .apply();
    }

    // ---- rijstijlscore ----

    public void addHarsh() { p.edit().putInt("tripHarsh", p.getInt("tripHarsh", 0) + 1).apply(); }
    public int tripHarsh() { return p.getInt("tripHarsh", 0); }

    /**
     * Score 0-100 voor de lopende rit (-1 bij korter dan 2 km).
     * 60% rustig rijden (hard optrekken/remmen per 10 km), 40% zuinigheid t.o.v. je normale 12,2 km/l.
     */
    public int tripScore() {
        float km = tripKm(), l = tripLiters();
        if (km < 2f) return -1;
        float per10 = tripHarsh() / km * 10f;
        float smooth = 100f - Math.min(70f, per10 * 12f);
        float kmpl = l > 0.01f ? km / l : 12.2f;
        float econ = Math.max(0f, Math.min(100f, 50f + (kmpl - 12.2f) * 8f));
        return Math.round(0.6f * smooth + 0.4f * econ);
    }

    public int summaryScore() { return p.getInt("sumScore", -1); }
    public String newRecord() { return p.getString("newRecord", ""); }
    public void setNewRecord(String r) { p.edit().putString("newRecord", r).apply(); }

    public String summaryText() {
        java.util.Locale nl = new java.util.Locale("nl");
        float h = p.getFloat("sumH", 0f);
        int min = Math.round(h * 60f);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(nl, Lang.t("Afstand: %.1f km\n", "Distance: %.1f km\n"), p.getFloat("sumKm", 0f)));
        sb.append(min >= 60 ? String.format(nl, Lang.t("Rijtijd: %d u %02d min\n", "Drive time: %d h %02d min\n"), min / 60, min % 60)
                            : String.format(nl, Lang.t("Rijtijd: %d min\n", "Drive time: %d min\n"), min));
        sb.append(String.format(nl, Lang.t("Gemiddeld: %.0f km/u\n", "Average: %.0f km/h\n"), p.getFloat("sumAvg", 0f)));
        sb.append(String.format(nl, Lang.t("Hoogste: %.0f km/u\n", "Top: %.0f km/h\n"), p.getFloat("sumMax", 0f)));
        sb.append(String.format(nl, Lang.t("Verbruik: ~%.1f liter", "Fuel: ~%.1f litres"), p.getFloat("sumL", 0f)));
        float c = p.getFloat("sumCost", -1f);
        if (c >= 0) sb.append(String.format(nl, Lang.t("\nKosten: ~€ %.2f", "\nCost: ~€ %.2f"), c));
        else sb.append(Lang.t("\nKosten: voer bij Tanken een prijs in", "\nCost: enter a price under Fuel"));
        return sb.toString();
    }

    /** Regels voor het ritsamenvatting-display. */
    public String[][] summaryRows() {
        java.util.Locale us = java.util.Locale.US;
        float km = p.getFloat("sumKm", 0f), l = p.getFloat("sumL", 0f), c = p.getFloat("sumCost", -1f);
        int min = Math.round(p.getFloat("sumH", 0f) * 60f);
        return new String[][]{
                {Lang.t("AFSTAND KM", "DISTANCE KM"), String.format(us, "%.1f", km)},
                {Lang.t("RIJTIJD", "DRIVE TIME"), String.format(us, "%d:%02d", min / 60, min % 60)},
                {Lang.t("GEM KM/U", "AVG KM/H"), String.format(us, "%.0f", p.getFloat("sumAvg", 0f))},
                {Lang.t("MAX KM/U", "MAX KM/H"), String.format(us, "%.0f", p.getFloat("sumMax", 0f))},
                {Lang.t("LITER", "LITRES"), String.format(us, "%.2f", l)},
                {"KM/L", l > 0.01f ? String.format(us, "%.1f", km / l) : "--"},
                {"EURO", c >= 0 ? String.format(us, "%.2f", c) : "--"},
                {"SCORE", summaryScore() >= 0 ? String.valueOf(summaryScore()) : "--"}};
    }

    public String summaryShort() {
        java.util.Locale nl = new java.util.Locale("nl");
        float c = p.getFloat("sumCost", -1f);
        return String.format(nl, Lang.t("%.1f km · gem %.0f · max %.0f km/u · ~%.1f l", "%.1f km · avg %.0f · max %.0f km/h · ~%.1f l"),
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
            .putFloat("tripMax", 0f).putFloat("tripL", 0f).putInt("tripHarsh", 0)
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
        e.putFloat("totalKm", p.getFloat("totalKm", 0f) + km);
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

    /** Cijfers van de laatst afgesloten rit: km, uren, gem, max, liter, kosten (-1 = onbekend). */
    public float[] summaryNumbers() {
        return new float[]{p.getFloat("sumKm", 0f), p.getFloat("sumH", 0f), p.getFloat("sumAvg", 0f),
                p.getFloat("sumMax", 0f), p.getFloat("sumL", 0f), p.getFloat("sumCost", -1f)};
    }

    // ---- onderhoud ----

    public static final int OIL_KM = 15000;      // Toyota: olie elke 15.000 km
    public static final int OIL_MONTHS = 12;     // of elk jaar

    /** Door GPS gereden km sinds installatie. */
    public float totalKm() { return p.getFloat("totalKm", 0f); }

    /** Kilometerstand (ingevoerd + sindsdien gereden), of -1 als nooit ingevoerd. */
    public int odometer() {
        float base = p.getFloat("odoBase", -1f);
        if (base < 0) return -1;
        return Math.round(base + totalKm() - p.getFloat("odoAt", 0f));
    }

    public void setOdometer(int km) {
        p.edit().putFloat("odoBase", km).putFloat("odoAt", totalKm()).apply();
    }

    public int oilKm() { return p.getInt("oilKm", -1); }
    public long oilDate() { return p.getLong("oilDate", 0L); }
    public long apkDate() { return p.getLong("apkDate", 0L); }
    public void setOil(int km, long date) { p.edit().putInt("oilKm", km).putLong("oilDate", date).apply(); }
    public void setApk(long date) { p.edit().putLong("apkDate", date).apply(); }

    /** Km tot de volgende olieverversing, of Integer.MIN_VALUE als onbekend. */
    public int oilKmLeft() {
        int odo = odometer(), oil = oilKm();
        if (odo < 0 || oil < 0) return Integer.MIN_VALUE;
        return oil + OIL_KM - odo;
    }

    /** Dagen tot de volgende olieverversing op datum, of Integer.MIN_VALUE. */
    public int oilDaysLeft(long now) {
        long d = oilDate();
        if (d <= 0) return Integer.MIN_VALUE;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(d); c.add(java.util.Calendar.MONTH, OIL_MONTHS);
        return (int) Math.floor((c.getTimeInMillis() - now) / 86_400_000d);
    }

    public int apkDaysLeft(long now) {
        long d = apkDate();
        if (d <= 0) return Integer.MIN_VALUE;
        return (int) Math.floor((d - now) / 86_400_000d);
    }

    /** Is er onderhoud nodig of bijna nodig? (olie binnen 500 km of 14 dagen, APK binnen 30 dagen) */
    public boolean serviceDue(long now) {
        int k = oilKmLeft(), od = oilDaysLeft(now), a = apkDaysLeft(now);
        return (k != Integer.MIN_VALUE && k <= 500) || (od != Integer.MIN_VALUE && od <= 14)
                || (a != Integer.MIN_VALUE && a <= 30);
    }

    // ---- rittenlog aan/uit ----

    public boolean tripLogOn() { return p.getBoolean("tripLog", true); }
    public void setTripLogOn(boolean on) { p.edit().putBoolean("tripLog", on).apply(); }
}

package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Werkritten voor spoonpaco. Alle ritten staan in ritten.csv; werkritten zijn daar gemarkeerd
 * en worden telkens opnieuw weggeschreven naar spoonpaco-ritten.csv (zodat later gevonden adressen
 * en achteraf gemarkeerde ritten er vanzelf in komen).
 */
public final class WorkLog {
    public static final String FILE = "spoonpaco-ritten.csv";
    public static final String MERK = "Toyota", TYPE = "RAV4 (SUV)", KENTEKEN = "86-LN-LF";

    private WorkLog() { }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("worklog", Context.MODE_PRIVATE); }

    // ---- knop: werkrit voor de huidige/volgende rit, gaat na elke rit vanzelf uit ----

    public static boolean flag(Context c) { return sp(c).getBoolean("flag", false); }
    public static void setFlag(Context c, boolean on) { sp(c).edit().putBoolean("flag", on).apply(); }

    // ---- welke ritten zijn werkritten ----

    public static Set<String> ids(Context c) { return new HashSet<>(sp(c).getStringSet("ids", new HashSet<>())); }

    public static boolean isWork(Context c, long id) { return ids(c).contains(String.valueOf(id)); }

    public static void setWork(Context c, long id, boolean work) {
        Set<String> s = ids(c);
        if (work) s.add(String.valueOf(id)); else s.remove(String.valueOf(id));
        sp(c).edit().putStringSet("ids", s).apply();
        write(c);
    }

    /** Aanroepen als een rit is afgesloten (vanuit TripLog.end). */
    public static void onTripEnded(Context c, long id) { onTripEnded(c, id, 0, 0, 0, 0, 0f); }

    public static void onTripEnded(Context c, long id, double sLat, double sLon, double eLat, double eLon, float km) {
        if (!flag(c) && km >= 1f) {
            // geen werkrit aangezet, maar begin of eind is een opgeslagen locatie (niet thuis): straks vragen
            Locations.Loc l = Locations.match(c, eLat, eLon);
            if (l == null || isHome(l)) l = Locations.match(c, sLat, sLon);
            if (l != null && !isHome(l)) {
                sp(c).edit().putLong("ask", id).putString("askName", l.name).putFloat("askKm", km).apply();
                CarLogger.note("werkrit-vraag klaargezet (" + l.name + ")");
            }
        }
        if (flag(c)) {
            Set<String> s = ids(c);
            s.add(String.valueOf(id));
            sp(c).edit().putStringSet("ids", s).putBoolean("flag", false).apply();
            CarLogger.note("werkrit opgeslagen voor spoonpaco");
            write(c);
        }
    }

    private static boolean isHome(Locations.Loc l) {
        String n = l.name == null ? "" : l.name.trim().toLowerCase(java.util.Locale.ROOT);
        return n.equals("thuis") || n.equals("home") || n.startsWith("thuis ") || n.startsWith("home ");
    }

    /** Openstaande vraag "Was dit een werkrit?": rit-id, of 0. */
    public static long pendingAsk(Context c) { return sp(c).getLong("ask", 0); }
    public static String pendingName(Context c) { return sp(c).getString("askName", ""); }
    public static float pendingKm(Context c) { return sp(c).getFloat("askKm", 0f); }
    public static void clearAsk(Context c) { sp(c).edit().remove("ask").remove("askName").remove("askKm").apply(); }

    public static List<TripLog.Trip> trips(Context c) {
        Set<String> s = ids(c);
        List<TripLog.Trip> out = new ArrayList<>();
        for (TripLog.Trip t : TripLog.all(c)) if (s.contains(String.valueOf(t.id))) out.add(t);
        Collections.reverse(out); // oudste eerst
        return out;
    }

    /** Coördinaten van werkritten waarvan nog geen adres of vaste locatie bekend is. */
    public static List<double[]> coordsNeedingAddress(Context c) {
        List<double[]> out = new ArrayList<>();
        for (TripLog.Trip t : trips(c)) {
            if (Locations.match(c, t.sLat, t.sLon) == null) out.add(new double[]{t.sLat, t.sLon});
            if (Locations.match(c, t.eLat, t.eLon) == null) out.add(new double[]{t.eLat, t.eLon});
        }
        return out;
    }

    /** Adres voor in het bestand: vaste locatie (naam — adres), anders opgezocht adres, anders coördinaten. */
    public static String address(Context c, double lat, double lon) {
        Locations.Loc l = Locations.match(c, lat, lon);
        if (l != null) {
            String a = !l.addr.isEmpty() ? l.addr : Geo.cached(c, l.lat, l.lon);
            return l.name.isEmpty() ? a : (a.isEmpty() ? l.name : l.name + " — " + a);
        }
        String a = Geo.cached(c, lat, lon);
        return a.isEmpty() ? Lang.t("(adres volgt) ", "(address pending) ") + String.format(Locale.US, "%.5f %.5f", lat, lon) : a;
    }

    /** Aftrek per km (privéauto, ondernemer). */
    public static float ratePerKm(int year) { return year <= 2025 ? 0.23f : 0.25f; }

    private static String q(String s) { return "\"" + s.replace("\"", "'") + "\""; }

    /** spoonpaco-ritten.csv opnieuw opbouwen. */
    public static synchronized File write(Context c) {
        File f = new File(TripLog.dir(c), FILE);
        SimpleDateFormat day = new SimpleDateFormat("dd-MM-yyyy", Locale.US), hm = new SimpleDateFormat("HH:mm", Locale.US);
        Map<Integer, float[]> perYear = new LinkedHashMap<>();
        StringBuilder s = new StringBuilder();
        s.append("Rittenregistratie spoonpaco\n");
        s.append("Merk;").append(MERK).append('\n');
        s.append("Type;").append(TYPE).append('\n');
        s.append("Kenteken;").append(KENTEKEN).append('\n');
        s.append('\n');
        s.append("Datum;Vertrek;Aankomst;Km-stand begin;Km-stand eind;Afstand km;Vertrekadres;Aankomstadres;Zakelijk/privé;Doel;Vertrek lat;Vertrek lon;Aankomst lat;Aankomst lon\n");
        for (TripLog.Trip t : trips(c)) {
            Locations.Loc dest = Locations.match(c, t.eLat, t.eLon);
            s.append(day.format(new Date(t.start))).append(';')
             .append(hm.format(new Date(t.start))).append(';')
             .append(hm.format(new Date(t.end))).append(';')
             .append(t.odoStart > 0 ? String.valueOf(t.odoStart) : "").append(';')
             .append(t.odoEnd > 0 ? String.valueOf(t.odoEnd) : "").append(';')
             .append(String.format(Locale.GERMANY, "%.1f", t.km)).append(';')
             .append(q(address(c, t.sLat, t.sLon))).append(';')
             .append(q(address(c, t.eLat, t.eLon))).append(';')
             .append("Zakelijk;")
             .append(q(dest != null ? dest.name : "")).append(';')
             .append(String.format(Locale.US, "%.6f;%.6f;%.6f;%.6f", t.sLat, t.sLon, t.eLat, t.eLon)).append('\n');
            Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(t.start);
            int y = cal.get(Calendar.YEAR);
            float[] sum = perYear.get(y);
            if (sum == null) { sum = new float[2]; perYear.put(y, sum); }
            sum[0] += t.km; sum[1] += 1;
        }
        s.append('\n');
        for (Map.Entry<Integer, float[]> e : perYear.entrySet()) {
            float km = e.getValue()[0], rate = ratePerKm(e.getKey());
            s.append(String.format(Locale.GERMANY, "Totaal %d;%d ritten;%.1f zakelijke km;x € %.2f;= € %.2f aftrek\n",
                    e.getKey(), (int) e.getValue()[1], km, rate, km * rate));
        }
        // UTF-8 met BOM zodat Excel € en é goed toont
        try (FileWriter w = new FileWriter(f, false)) { w.write('﻿'); w.write(s.toString()); } catch (Exception ignored) { }
        return f;
    }

    /** Zakelijke km dit jaar (voor het rittenscherm). */
    public static float kmThisYear(Context c) {
        int y = Calendar.getInstance().get(Calendar.YEAR);
        float km = 0;
        Calendar cal = Calendar.getInstance();
        for (TripLog.Trip t : trips(c)) { cal.setTimeInMillis(t.start); if (cal.get(Calendar.YEAR) == y) km += t.km; }
        return km;
    }
}

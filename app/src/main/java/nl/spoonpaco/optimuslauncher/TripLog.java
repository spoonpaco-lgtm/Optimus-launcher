package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Rittenregistratie: elke rit als regel in files/trips/ritten.csv, met de route (punt per ~100 m)
 * in files/trips/<id>.pts. Alleen lokaal op de tablet; export naar USB-stick vanuit het rittenscherm.
 */
public final class TripLog {
    public static final String HEADER = "id;start;eind;start_lat;start_lon;eind_lat;eind_lon;km;minuten;gem_kmh;max_kmh;liter;kosten_eur;kmstand_start;kmstand_eind;score";

    public static final class Trip {
        public long id, start, end;
        public double sLat, sLon, eLat, eLon;
        public float km, avg, max, liters, cost;
        public int minutes, odoStart, odoEnd, score = -1;
    }

    private TripLog() { }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("triplog", Context.MODE_PRIVATE); }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "trips");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Nieuwe rit begint (alleen als de registratie aan staat). */
    public static void begin(Context c, Store store, Location loc) {
        if (!store.tripLogOn()) { sp(c).edit().putLong("id", 0).apply(); return; }
        long now = Clock.now();
        sp(c).edit().putLong("id", now)
                .putString("sLat", String.valueOf(loc.getLatitude())).putString("sLon", String.valueOf(loc.getLongitude()))
                .putString("lLat", String.valueOf(loc.getLatitude())).putString("lLon", String.valueOf(loc.getLongitude()))
                .putInt("odoStart", store.odometer())
                .apply();
        append(new File(dir(c), now + ".pts"), loc.getLatitude() + "," + loc.getLongitude() + "\n");
    }

    /** Routepunt toevoegen als we ~100 m verder zijn dan het vorige. */
    public static void point(Context c, Location loc) {
        SharedPreferences s = sp(c);
        long id = s.getLong("id", 0);
        if (id == 0) return;
        try {
            float[] d = new float[1];
            Location.distanceBetween(Double.parseDouble(s.getString("lLat", "0")), Double.parseDouble(s.getString("lLon", "0")),
                    loc.getLatitude(), loc.getLongitude(), d);
            if (d[0] < 100f) return;
        } catch (Exception ignored) { }
        s.edit().putString("lLat", String.valueOf(loc.getLatitude())).putString("lLon", String.valueOf(loc.getLongitude())).apply();
        append(new File(dir(c), id + ".pts"), loc.getLatitude() + "," + loc.getLongitude() + "\n");
    }

    /** Rit afgesloten: regel in het logboek zetten. Gebruikt de cijfers van de ritsamenvatting. */
    public static void end(Context c, Store store, Location last) {
        SharedPreferences s = sp(c);
        long id = s.getLong("id", 0);
        if (id == 0) return;
        s.edit().putLong("id", 0).apply();
        double eLat, eLon;
        if (last != null) { eLat = last.getLatitude(); eLon = last.getLongitude(); }
        else { eLat = Double.parseDouble(s.getString("lLat", "0")); eLon = Double.parseDouble(s.getString("lLon", "0")); }
        if (last != null) append(new File(dir(c), id + ".pts"), eLat + "," + eLon + "\n");
        float[] sum = store.summaryNumbers(); // km, uren, gem, max, liter, kosten
        // eindtijd = laatste beweging (niet het moment dat de 5 min stilstand verstreken is)
        long end = Clock.now();
        long lm = store.lastMoveTime();
        if (lm > 0) end -= Math.max(0L, System.currentTimeMillis() - lm);
        if (end < id) end = id;
        // records vergelijken met eerdere ritten (vóór deze rit wordt toegevoegd)
        int score = store.summaryScore();
        float kmpl = sum[4] > 0.01f && sum[0] >= 2f ? sum[0] / sum[4] : -1f;
        int bestScore = -1; float bestKmpl = -1f, longest = 0f; int n = 0;
        for (Trip t : all(c)) {
            n++;
            bestScore = Math.max(bestScore, t.score);
            if (t.km >= 2f && t.liters > 0.01f) bestKmpl = Math.max(bestKmpl, t.km / t.liters);
            longest = Math.max(longest, t.km);
        }
        StringBuilder rec = new StringBuilder();
        if (n > 0) {
            if (score >= 0 && score > bestScore) rec.append("SCORE ");
            if (kmpl > 0 && kmpl > bestKmpl) rec.append("KM/L ");
            if (sum[0] > longest && sum[0] >= 5f) rec.append("KM ");
        }
        store.setNewRecord(rec.toString().trim());
        String line = String.format(Locale.US, "%d;%d;%d;%s;%s;%.6f;%.6f;%.2f;%d;%.0f;%.0f;%.2f;%.2f;%d;%d;%d\n",
                id, id, end, s.getString("sLat", "0"), s.getString("sLon", "0"), eLat, eLon,
                sum[0], Math.round((end - id) / 60000f), sum[2], sum[3], sum[4], sum[5],
                s.getInt("odoStart", -1), store.odometer(), score);
        File f = new File(dir(c), "ritten.csv");
        if (!f.exists()) append(f, HEADER + "\n");
        append(f, line);
        CarLogger.note("rittenlog: rit opgeslagen (" + String.format(Locale.US, "%.1f km", sum[0]) + ")");
    }

    public static boolean active(Context c) { return sp(c).getLong("id", 0) != 0; }

    private static synchronized void append(File f, String s) {
        try (FileWriter w = new FileWriter(f, true)) { w.write(s); } catch (Exception ignored) { }
    }

    /** Alle ritten, nieuwste eerst. */
    public static List<Trip> all(Context c) {
        List<Trip> out = new ArrayList<>();
        File f = new File(dir(c), "ritten.csv");
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("id;") || l.trim().isEmpty()) continue;
                String[] p = l.split(";");
                if (p.length < 15) continue;
                Trip t = new Trip();
                t.id = Long.parseLong(p[0]); t.start = Long.parseLong(p[1]); t.end = Long.parseLong(p[2]);
                t.sLat = Double.parseDouble(p[3]); t.sLon = Double.parseDouble(p[4]);
                t.eLat = Double.parseDouble(p[5]); t.eLon = Double.parseDouble(p[6]);
                t.km = Float.parseFloat(p[7]); t.minutes = Integer.parseInt(p[8]);
                t.avg = Float.parseFloat(p[9]); t.max = Float.parseFloat(p[10]);
                t.liters = Float.parseFloat(p[11]); t.cost = Float.parseFloat(p[12]);
                t.odoStart = Integer.parseInt(p[13]); t.odoEnd = Integer.parseInt(p[14]);
                if (p.length > 15) t.score = Integer.parseInt(p[15].trim());
                out.add(t);
            }
        } catch (Exception ignored) { }
        Collections.reverse(out);
        return out;
    }

    /** Highscores: top 5 scores, plus zuinigste en langste rit, als display-regels. */
    public static String[][] highscores(Context c) {
        List<Trip> all = all(c);
        List<Trip> byScore = new ArrayList<>();
        for (Trip t : all) if (t.score >= 0) byScore.add(t);
        Collections.sort(byScore, (a, b) -> b.score - a.score);
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("d MMM", Lang.locale());
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(5, byScore.size()); i++) {
            Trip t = byScore.get(i);
            rows.add(new String[]{(i + 1) + "  " + df.format(new java.util.Date(t.start)).replace(".", "").toUpperCase(Lang.locale()), String.valueOf(t.score)});
        }
        while (rows.size() < 5) rows.add(new String[]{(rows.size() + 1) + "  ---", "--"});
        Trip eco = null, lng = null;
        for (Trip t : all) {
            if (t.km >= 2f && t.liters > 0.01f && (eco == null || t.km / t.liters > eco.km / eco.liters)) eco = t;
            if (lng == null || t.km > lng.km) lng = t;
        }
        rows.add(new String[]{Lang.t("ZUINIGST KM/L", "BEST KM/L"), eco == null ? "--" : String.format(Locale.US, "%.1f", eco.km / eco.liters)});
        rows.add(new String[]{Lang.t("LANGSTE KM", "LONGEST KM"), lng == null ? "--" : String.format(Locale.US, "%.1f", lng.km)});
        return rows.toArray(new String[0][]);
    }

    /** Routepunten van een rit als {lat, lon}. */
    public static List<double[]> route(Context c, long id) {
        List<double[]> out = new ArrayList<>();
        File f = new File(dir(c), id + ".pts");
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) {
                int k = l.indexOf(',');
                if (k > 0) out.add(new double[]{Double.parseDouble(l.substring(0, k)), Double.parseDouble(l.substring(k + 1))});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Logboek naar USB-stick (of anders naar de downloadmap). Geeft het doelpad terug. */
    public static String export(Context c) throws Exception {
        File src = new File(dir(c), "ritten.csv");
        if (!src.exists()) throw new Exception(Lang.t("nog geen ritten", "no trips yet"));
        File target = null;
        File[] ext = c.getExternalFilesDirs(null);
        if (ext != null && ext.length > 1 && ext[1] != null) target = ext[1];          // USB-stick / SD
        if (target == null) target = c.getExternalFilesDir(null);
        File out = new File(target, "ritten-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date(Clock.now())) + ".csv");
        try (java.io.FileInputStream in = new java.io.FileInputStream(src); java.io.FileOutputStream o = new java.io.FileOutputStream(out)) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        return out.getAbsolutePath();
    }
}

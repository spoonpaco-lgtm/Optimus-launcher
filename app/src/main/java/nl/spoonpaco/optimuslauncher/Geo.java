package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Adressen bij coördinaten via OpenStreetMap (Nominatim), alleen als er internet is (thuis op wifi).
 * Resultaten worden bewaard, dus elke plek wordt maar één keer opgezocht. Max 1 verzoek per 1,5 s.
 */
public final class Geo {
    private static volatile boolean running;

    private Geo() { }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("addresses", Context.MODE_PRIVATE); }

    private static String key(double lat, double lon) { return String.format(Locale.US, "%.4f,%.4f", lat, lon); }

    /** Bekend adres, of "" als het nog niet is opgezocht. */
    public static String cached(Context c, double lat, double lon) { return sp(c).getString(key(lat, lon), ""); }

    public static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) { return false; }
    }

    /** Ontbrekende adressen (werkritten + vaste locaties) op de achtergrond aanvullen. */
    public static void fillMissing(final Context ctx) {
        if (running || !online(ctx)) return;
        running = true;
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                boolean changed = false;
                for (double[] p : WorkLog.coordsNeedingAddress(c)) {
                    if (!cached(c, p[0], p[1]).isEmpty()) continue;
                    String a = lookup(p[0], p[1]);
                    if (a != null) { sp(c).edit().putString(key(p[0], p[1]), a).apply(); changed = true; }
                    Thread.sleep(1500);
                }
                List<Locations.Loc> locs = Locations.all(c);
                boolean locChanged = false;
                for (Locations.Loc l : locs) {
                    if (!l.addr.isEmpty()) continue;
                    String a = cached(c, l.lat, l.lon);
                    if (a.isEmpty()) { a = lookup(l.lat, l.lon); Thread.sleep(1500); }
                    if (a != null && !a.isEmpty()) { l.addr = a; sp(c).edit().putString(key(l.lat, l.lon), a).apply(); locChanged = true; }
                }
                if (locChanged) Locations.save(c, locs);
                if (changed || locChanged) { WorkLog.write(c); CarLogger.note("adressen aangevuld via OpenStreetMap"); }
            } catch (Exception e) {
                CarLogger.note("adressen opzoeken mislukt: " + e.getMessage());
            } finally { running = false; }
        }, "geo").start();
    }

    /** "Straat 12, 1234 AB Plaats" of null. */
    static String lookup(double lat, double lon) {
        HttpURLConnection h = null;
        try {
            URL u = new URL(String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=%.6f&lon=%.6f", lat, lon));
            h = (HttpURLConnection) u.openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(10000);
            h.setRequestProperty("User-Agent", "OptimusLauncher (spoonpaco.nl, carlauncher)");
            h.setRequestProperty("Accept-Language", "nl");
            if (h.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"))) {
                String l; while ((l = r.readLine()) != null) sb.append(l);
            }
            JSONObject a = new JSONObject(sb.toString()).optJSONObject("address");
            if (a == null) return null;
            String road = a.optString("road", a.optString("pedestrian", a.optString("footway", "")));
            String nr = a.optString("house_number", "");
            String pc = a.optString("postcode", "");
            String town = a.optString("city", a.optString("town", a.optString("village", a.optString("municipality", ""))));
            StringBuilder s = new StringBuilder();
            if (!road.isEmpty()) s.append(road).append(nr.isEmpty() ? "" : " " + nr);
            if (!pc.isEmpty() || !town.isEmpty()) {
                if (s.length() > 0) s.append(", ");
                s.append(pc).append(pc.isEmpty() || town.isEmpty() ? "" : " ").append(town);
            }
            return s.length() == 0 ? null : s.toString();
        } catch (Exception e) {
            return null;
        } finally { if (h != null) h.disconnect(); }
    }
}

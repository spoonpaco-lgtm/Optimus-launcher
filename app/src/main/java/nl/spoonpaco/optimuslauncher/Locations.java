package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Vaste locaties (thuis, scholen, klanten). Begint/eindigt een werkrit binnen 150 m, dan komt naam + adres in het bestand. */
public final class Locations {
    public static final float RADIUS_M = 150f;

    public static final class Loc {
        public String name = "", addr = "";
        public double lat, lon;
        public String label() { return !name.isEmpty() ? name : !addr.isEmpty() ? addr : String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon); }
    }

    private Locations() { }

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("locations", Context.MODE_PRIVATE); }

    public static synchronized List<Loc> all(Context c) {
        List<Loc> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Loc l = new Loc();
                l.name = o.optString("name"); l.addr = o.optString("addr");
                l.lat = o.getDouble("lat"); l.lon = o.getDouble("lon");
                out.add(l);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static synchronized void save(Context c, List<Loc> list) {
        JSONArray a = new JSONArray();
        try {
            for (Loc l : list) {
                JSONObject o = new JSONObject();
                o.put("name", l.name); o.put("addr", l.addr); o.put("lat", l.lat); o.put("lon", l.lon);
                a.put(o);
            }
        } catch (Exception ignored) { }
        sp(c).edit().putString("list", a.toString()).apply();
    }

    /** Huidige plek opslaan (één druk). Geeft de nieuwe locatie terug, of null als er nog geen GPS-positie is. */
    public static Loc addHere(Context c) {
        if (TrackerService.lastLat == 0 && TrackerService.lastLon == 0) return null;
        List<Loc> list = all(c);
        Loc l = new Loc();
        l.lat = TrackerService.lastLat; l.lon = TrackerService.lastLon;
        l.name = Lang.t("Locatie ", "Location ") + (list.size() + 1);
        l.addr = Geo.cached(c, l.lat, l.lon);
        list.add(l);
        save(c, list);
        return l;
    }

    /** Dichtstbijzijnde vaste locatie binnen 150 m, of null. */
    public static Loc match(Context c, double lat, double lon) {
        Loc best = null; float bestD = RADIUS_M;
        float[] d = new float[1];
        for (Loc l : all(c)) {
            Location.distanceBetween(lat, lon, l.lat, l.lon, d);
            if (d[0] <= bestD) { bestD = d[0]; best = l; }
        }
        return best;
    }
}

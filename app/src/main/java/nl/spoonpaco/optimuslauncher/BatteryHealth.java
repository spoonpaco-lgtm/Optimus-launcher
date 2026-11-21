package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Accubewaker. Meet bij elke start:
 *  - rustspanning vlak na contact aan (vóór het starten),
 *  - laagste spanning tijdens het starten (startdip),
 * en tijdens het rijden de laadspanning van de dynamo.
 * Waarschuwt pas bij een duidelijk patroon, niet bij één slechte meting.
 *
 * Grenzen (12 V loodaccu):
 *  startdip  >= 10,0 V goed | 9,6 - 10,0 V oud worden | < 9,6 V slecht
 *  rust      >= 12,4 V goed | 12,0 - 12,4 V half leeg  | < 12,0 V leeg
 *  laden     13,2 - 14,9 V goed, daarbuiten dynamo/regelaar verdacht
 */
public final class BatteryHealth {
    private static SharedPreferences sp;

    private static boolean window;          // tussen contact aan en motor draait
    private static long windowStart;
    private static boolean restPending;
    private static float rest = -1f, crankMin = 99f;
    private static long runningSince;
    private static float chargeSum; private static int chargeN;

    /** 0 = goed/onbekend, 1 = let op (geel), 2 = slecht (rood). */
    public static volatile int level = 0;
    public static volatile String reason = "";

    private BatteryHealth() { }

    public static synchronized void init(Context c) {
        sp = c.getSharedPreferences("battery", Context.MODE_PRIVATE);
        if (!sp.getBoolean("cleaned1", false)) {
            StringBuilder ok = new StringBuilder();
            for (String x : sp.getString("charge", "").split(";")) {
                try { if (Float.parseFloat(x) >= 13.0f) ok.append(ok.length() == 0 ? "" : ";").append(x); } catch (Exception ignored) { }
            }
            sp.edit().putString("charge", ok.toString()).putBoolean("cleaned1", true).apply();
        }
        evaluate();
    }

    public static synchronized void onContact(int v) {
        long now = System.currentTimeMillis();
        if (v == 1 && (!window || now - windowStart > 60_000L)) {
            // contact flikkert tijdens het starten: alleen een nieuw venster na > 1 min
            window = true; windowStart = now; restPending = true; rest = -1f; crankMin = 99f;
        }
        if (v == 0) { runningSince = 0; chargeN = 0; chargeSum = 0; }
    }

    public static synchronized void onVolts(float v) {
        if (sp == null || v <= 5f) return;
        long now = System.currentTimeMillis();
        if (window) {
            if (restPending) { rest = v; restPending = false; }
            crankMin = Math.min(crankMin, v);
            if (v >= 13.3f) {
                // motor draait: start vastleggen
                window = false;
                runningSince = now; chargeN = 0; chargeSum = 0;
                boolean dipSeen = rest > 0 && crankMin < rest - 0.4f && crankMin < 12.0f;
                if (dipSeen) push("crank", crankMin);
                // rustspanning alleen als de accu echt rustte (net na een rit staat er nog ~13 V oppervlaktelading op)
                if (rest > 0 && rest < 13.0f) push("rest", rest);
                if (dipSeen || (rest > 0 && rest < 13.0f))
                    CarLogger.note(String.format(Locale.US, "accu: start, rust %.2f V, startdip %s",
                            rest, dipSeen ? String.format(Locale.US, "%.2f V", crankMin) : "niet gezien"));
                evaluate();
            } else if (now - windowStart > 10 * 60_000L) {
                window = false; // geen start binnen 10 min
            }
        } else if (runningSince > 0 && v < 13.0f) {
            // motor uit (contact nog aan): geen laadspanning meer meten
            runningSince = 0; chargeN = 0; chargeSum = 0;
        } else if (runningSince == 0 && v >= 13.5f && !window) {
            runningSince = now; chargeN = 0; chargeSum = 0;   // motor (weer) gestart zonder contactwissel
        } else if (runningSince > 0 && now - runningSince > 120_000L) {
            chargeSum += v; chargeN++;
            if (chargeN >= 30) { // ~5 min metingen
                float avg = chargeSum / chargeN;
                push("charge", avg);
                CarLogger.note(String.format(Locale.US, "accu: laadspanning gem %.2f V", avg));
                chargeN = 0; chargeSum = 0;
                evaluate();
            }
        }
    }

    /** Laatste 5 waarden per soort bewaren, nieuwste eerst. */
    private static void push(String key, float v) {
        String old = sp.getString(key, "");
        StringBuilder s = new StringBuilder(String.format(Locale.US, "%.2f", v));
        String[] parts = old.isEmpty() ? new String[0] : old.split(";");
        for (int i = 0; i < Math.min(4, parts.length); i++) s.append(';').append(parts[i]);
        sp.edit().putString(key, s.toString()).apply();
    }

    private static float[] get(String key) {
        String s = sp == null ? "" : sp.getString(key, "");
        if (s.isEmpty()) return new float[0];
        String[] p = s.split(";");
        float[] r = new float[p.length];
        for (int i = 0; i < p.length; i++) try { r[i] = Float.parseFloat(p[i]); } catch (Exception e) { r[i] = -1f; }
        return r;
    }

    /** Telt hoeveel van de laatste 3 (geldige) metingen onder de grens zitten. */
    private static int below(float[] a, float limit) {
        int n = 0, seen = 0;
        for (float v : a) { if (v <= 0) continue; if (v < limit) n++; if (++seen == 3) break; }
        return n;
    }

    private static void evaluate() {
        float[] crank = get("crank"), rst = get("rest"), chg = get("charge");
        int lv = 0; String why = "";
        if (below(crank, 9.6f) >= 2) { lv = 2; why = "startdip onder 9,6 V: accu is op"; }
        else if (below(rst, 12.0f) >= 2) { lv = 2; why = "rustspanning onder 12,0 V: accu loopt leeg"; }
        else if (chg.length > 0 && chg[0] > 0 && (chg[0] < 13.2f || chg[0] > 14.9f)
                && chg.length > 1 && chg[1] > 0 && (chg[1] < 13.2f || chg[1] > 14.9f)) {
            lv = 2; why = "laadspanning buiten 13,2-14,9 V: dynamo/regelaar nakijken";
        }
        else if (below(crank, 10.0f) >= 2) { lv = 1; why = "startdip onder 10 V: accu wordt oud"; }
        else if (below(rst, 12.4f) >= 2) { lv = 1; why = "rustspanning onder 12,4 V: accu niet vol"; }
        level = lv; reason = why;
    }

    /** Laatste meting van een soort ("rest", "crank", "charge"), of -1. */
    public static float last(String key) {
        float[] a = get(key);
        for (float v : a) if (v > 0) return v;
        return -1f;
    }

    public static String report() {
        return "accu: " + (level == 0 ? "goed" : level == 1 ? "LET OP — " + reason : "SLECHT — " + reason) + "\n"
                + "  startdip (V): " + (sp == null ? "" : sp.getString("crank", "-")) + "\n"
                + "  rust (V):     " + (sp == null ? "" : sp.getString("rest", "-")) + "\n"
                + "  laden (V):    " + (sp == null ? "" : sp.getString("charge", "-")) + "\n";
    }
}

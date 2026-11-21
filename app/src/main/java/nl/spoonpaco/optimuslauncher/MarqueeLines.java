package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Een paar regels tekst die doorlopen als ze te lang zijn. Eigen klein vlak bovenop een scherm:
 * alleen dit vlak wordt steeds opnieuw getekend, niet het hele scherm eronder.
 */
final class MarqueeLines extends View {
    private final CockpitView pt;
    private float u;                        // px per ontwerp-eenheid
    private final String[] txt = new String[4];
    private final float[] size = new float[4], base = new float[4], maxW = new float[4];
    private final int[] col = new int[4];
    private final long[] since = new long[4];
    private int n = 0;

    MarqueeLines(Context c, Theme t, float unit) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(false); setFocusable(false);
        pt = new CockpitView(c, null); pt.setTheme(t); theme = t;
        u = unit;
    }

    @Override public boolean onTouchEvent(MotionEvent e) { return false; }

    void setUnit(float unit) { if (unit != u) { u = unit; invalidate(); } }

    /** Regel i: tekst, grootte, kleur, basislijn (ontwerp-eenheden vanaf de bovenkant), maximale breedte (0 = hele vlak). */
    void set(int i, String t, float sz, int c, float baseline, float max) {
        if (t == null) t = "";
        if (!t.equals(txt[i])) { txt[i] = t; since[i] = System.currentTimeMillis(); invalidate(); }
        if (size[i] != sz || col[i] != c || base[i] != baseline || maxW[i] != max) { size[i] = sz; col[i] = c; base[i] = baseline; maxW[i] = max; invalidate(); }
        n = Math.max(n, i + 1);
    }

    void clear() { if (n == 0) return; for (int i = 0; i < n; i++) txt[i] = ""; n = 0; invalidate(); }

    /** Ander thema (letters/lettertype van het thema). */
    void setTheme(Theme t) { if (t != null && t != theme) { theme = t; pt.setTheme(t); invalidate(); } }
    private Theme theme;
    /** Scanlines over de tekst tekenen (als het vlak op een cockpitvenster ligt). */
    boolean scanlines = false;
    void leds(boolean ledTheme) { boolean sc = !ledTheme; if (sc != scanlines) { scanlines = sc; invalidate(); } }
    private final Paint sp = new Paint();

    @Override protected void onDraw(Canvas c) {
        if (getWidth() == 0) return;
        c.save(); c.scale(u, u);
        float W = getWidth() / u;
        long now = System.currentTimeMillis();
        boolean moving = false;
        for (int i = 0; i < n; i++) {
            if (txt[i] == null || txt[i].isEmpty()) continue;
            float avail = maxW[i] > 0 ? Math.min(maxW[i], W) : W;
            float tw = pt.uiTextWidth(txt[i], size[i]);
            c.save();
            c.clipRect(0, 0, avail, getHeight() / u);
            if (tw <= avail - 4) {
                pt.uiText(c, txt[i], 0, base[i], size[i], col[i], Paint.Align.LEFT);
            } else {
                // 2 s stil, dan 28 eenheden per seconde, naadloos rond; aan het begin van elke ronde weer even stil
                float gap = 60, cycle = tw + gap;
                float t = (now - since[i] - 2000) / 1000f * 28f;
                if (t >= cycle) { since[i] = now; t = -1; }
                float off = Math.max(0, t);
                pt.uiText(c, txt[i], -off, base[i], size[i], col[i], Paint.Align.LEFT);
                pt.uiText(c, txt[i], -off + cycle, base[i], size[i], col[i], Paint.Align.LEFT);
                moving = true;
            }
            c.restore();
        }
        if (scanlines) {   // zelfde scanlines als het glas van de cockpit eronder
            sp.setColor(0x4D000000);
            float hh = getHeight() / u;
            for (float y = 3; y < hh - 2; y += 3) c.drawRect(0, y, W, y + 1, sp);
        }
        c.restore();
        if (moving) postInvalidateDelayed(50);
    }
}

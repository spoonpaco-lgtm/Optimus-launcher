package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Bouwstenen om gewone schermen (rittenlog e.d.) in de cockpitstijl te tekenen:
 * achtergrond, bezel-panelen, displayvensters, drukknoppen met chromen lampje, tekst en scanlines.
 * Alles in dp-eenheden; tekenen gebeurt met dezelfde painter (CockpitView) als het startscherm.
 */
public final class RetroUi {
    private RetroUi() { }

    /** Zelfde maat als de cockpit en het menu: 1240 eenheden over de schermbreedte. */
    public static float unit(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.max(m.widthPixels, m.heightPixels) / 1240f;
    }

    public static CockpitView painter(Context c, Theme t) {
        CockpitView p = new CockpitView(c, null);
        p.setTheme(t);
        return p;
    }

    /** Achtergrond (BG), bezelpaneel (BEZEL) of display (WINDOW, met glas en scanlines). */
    public static class Panel extends Drawable {
        public static final int BG = 0, BEZEL = 1, WINDOW = 2;
        private final CockpitView pt; private final int type, col; private final float d;
        public Panel(CockpitView pt, int type, int col, float density) { this.pt = pt; this.type = type; this.col = col; this.d = density; }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            c.save();
            c.translate(b.left, b.top);
            c.scale(d, d);
            float w = b.width() / d, h = b.height() / d;
            RectF r = new RectF(0, 0, w, h);
            if (type == BG) pt.uiBackground(c, w, h);
            else if (type == BEZEL) pt.uiBezel(c, r);
            else { pt.uiWindow(c, r, col); pt.uiGlass(c, r); }
            c.restore();
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Drukknop met chromen lampje; on = brandt. */
    public static class Btn extends View {
        private final CockpitView pt; private final float d;
        public String label; public boolean on; public int key; public float size = 15;
        public Btn(Context c, CockpitView pt, String label, boolean on, int key) {
            super(c); this.pt = pt; this.label = label; this.on = on; this.key = key;
            d = unit(c);
            setClickable(true);
            setContentDescription(label);
        }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.scale(d, d);
            pt.uiButton(c, new RectF(2, 2, getWidth() / d - 2, getHeight() / d - 6), label, on, key, size);
            c.restore();
        }
        public void set(String l, boolean o) { label = l; on = o; setContentDescription(l); invalidate(); }
    }

    /** Eén of meer regels tekst in cockpitletters. */
    public static class Text extends View {
        private final CockpitView pt; private final float d;
        public String[] lines = new String[0]; public int[] cols; public float size; public int col;
        public Text(Context c, CockpitView pt, float size, int col) {
            super(c); this.pt = pt; this.size = size; this.col = col;
            d = unit(c);
        }
        public void set(String text) { lines = text.split("\n"); cols = null; setContentDescription(text); requestLayout(); invalidate(); }
        public void set(String[] l, int[] c) { lines = l; cols = c; setContentDescription(String.join(" / ", l)); requestLayout(); invalidate(); }
        @Override protected void onMeasure(int ws, int hs) {
            int h = (int) Math.ceil(lines.length * size * 1.75f * d + 6 * d);
            setMeasuredDimension(MeasureSpec.getSize(ws), resolveSize(h, hs));
        }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.scale(d, d);
            float y = size * 1.25f + 2;
            for (int i = 0; i < lines.length; i++) {
                pt.uiText(c, lines[i], 2, y, size, cols != null && i < cols.length ? cols[i] : col, Paint.Align.LEFT);
                y += size * 1.75f;
            }
            c.restore();
        }
    }

    /** Scanlines + vignet over alles heen (vangt geen aanrakingen). */
    public static class Scanlines extends View {
        private final CockpitView pt; private final float d;
        public Scanlines(Context c, CockpitView pt) {
            super(c); this.pt = pt; d = unit(c);
            setClickable(false); setFocusable(false);
        }
        @Override protected void onDraw(Canvas c) {
            c.save(); c.scale(d, d);
            pt.uiScanlines(c, getWidth() / d, getHeight() / d);
            c.restore();
        }
    }
}

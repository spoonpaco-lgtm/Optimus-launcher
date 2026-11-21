package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Invoerscherm in cockpitstijl (tanken, onderhoud): velden als displays, eigen toetsenblok
 * met chromen toetsen, optionele aan/uit-knop, OPSLAAN / ANNULEREN. Alles getekend met de painter.
 */
public final class InputForm {

    public static final class Field {
        final String label, hint; final boolean date; final StringBuilder v = new StringBuilder();
        public Field(String label, String value, String hint, boolean date) {
            this.label = label; this.hint = hint == null ? "" : hint; this.date = date;
            if (value != null) v.append(value);
        }
    }

    /** Geeft null bij succes (venster sluit), of een foutmelding die rood in beeld komt. */
    public interface Save { String save(String[] values, boolean toggle); }

    public static void show(Activity a, String title, String sub, Field[] fields, String toggleLabel, boolean toggleOn,
                            String extraKey, Save save) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FormView v = new FormView(a, MainActivity.currentTheme(), title, sub, fields, toggleLabel, toggleOn, extraKey, save, dlg::dismiss);
        dlg.setContentView(v);
        dlg.show();
    }

    private static final class FormView extends View {
        private final CockpitView pt; private final Theme theme;
        private final String title, sub, toggleLabel, extraKey;
        private final Field[] fields; private final Save save; private final Runnable close;
        private boolean toggle; private int sel = 0; private boolean fresh = true;
        private String error = ""; private String pressed = ""; private long pressedAt;
        private float s = 1f, H = 600f;
        private final List<Object[]> hits = new ArrayList<>();

        FormView(Context c, Theme t, String title, String sub, Field[] f, String toggleLabel, boolean toggleOn,
                 String extraKey, Save save, Runnable close) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            pt = new CockpitView(c, null); pt.setTheme(t); theme = t;
            this.title = title; this.sub = sub; this.fields = f; this.toggleLabel = toggleLabel; this.toggle = toggleOn;
            this.extraKey = extraKey; this.save = save; this.close = close;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { s = w / 1240f; H = h / s; }

        private void hit(RectF r, Runnable a) { hits.add(new Object[]{r, a}); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (e.getAction() == MotionEvent.ACTION_UP) {
                float x = e.getX() / s, y = e.getY() / s;
                for (int i = hits.size() - 1; i >= 0; i--) {
                    RectF r = (RectF) hits.get(i)[0];
                    if (r.contains(x, y)) { ((Runnable) hits.get(i)[1]).run(); invalidate(); return true; }
                }
            }
            return true;
        }

        private void key(String k) {
            pressed = k; pressedAt = System.currentTimeMillis();
            postInvalidateDelayed(160);
            error = "";
            Field f = fields[sel];
            if (k.equals("⌫")) {
                if (f.v.length() > 0) f.v.setLength(f.v.length() - 1);
                if (f.date && f.v.length() > 0 && f.v.charAt(f.v.length() - 1) == '-') f.v.setLength(f.v.length() - 1);
                fresh = false; return;
            }
            if (k.equals("WIS") || k.equals("CLR")) { f.v.setLength(0); fresh = false; return; }
            if (fresh) { f.v.setLength(0); fresh = false; }
            if (k.equals(".")) {
                if (f.v.indexOf(".") < 0) f.v.append(f.v.length() == 0 ? "0." : ".");
                return;
            }
            if (f.date) {
                if (f.v.length() >= 10) return;
                f.v.append(k);
                if (f.v.length() == 2 || f.v.length() == 5) f.v.append('-');
            } else if (f.v.length() < 9) f.v.append(k);
        }

        @Override protected void onDraw(Canvas c) {
            hits.clear();
            c.save(); c.scale(s, s);
            int key = theme.key, AMB = pt.uiAmb(), RED = pt.uiRed();
            pt.uiBackground(c, 1240, H);
            RectF left = new RectF(0, 0, 720, H - 2), right = new RectF(736, 0, 1240, H - 2);
            pt.uiBezel(c, left); pt.uiBezel(c, right);

            // ---- titel ----
            pt.uiLamp(c, 44, 46, 11, key, true);
            pt.uiText(c, title, 68, 56, 26, key, Paint.Align.LEFT);
            if (!sub.isEmpty()) pt.uiText(c, sub, 70, 82, 10, 0xFF6C6C78, Paint.Align.LEFT);

            // ---- velden ----
            float avail = H - 110 - (toggleLabel != null ? 90 : 0) - 40;
            float fh = Math.min(108, avail / fields.length);
            float y = 104;
            for (int i = 0; i < fields.length; i++) {
                final int idx = i;
                Field f = fields[i];
                boolean on = i == sel;
                pt.uiText(c, f.label, 40, y + 14, 12, on ? AMB : 0xFF8E8E9A, Paint.Align.LEFT);
                RectF w = new RectF(36, y + 22, 684, y + fh - 12);
                if (on) pt.uiWindow(c, w, AMB); else pt.uiWindow(c, w, 0xFF55555E);
                pt.uiLamp(c, w.left + 22, w.centerY(), 7, AMB, on);
                String val = f.v.toString();
                float ts = Math.min(30, w.height() * 0.55f);
                if (val.isEmpty()) pt.uiText(c, f.hint, w.left + 44, w.centerY() + ts * 0.3f, Math.min(14, ts), 0xFF55555E, Paint.Align.LEFT);
                else pt.uiText(c, val + (on && !fresh && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : ""), w.left + 44, w.centerY() + ts * 0.36f, ts, on ? AMB : pt.uiAmb() & 0x99FFFFFF, Paint.Align.LEFT);
                pt.uiGlass(c, w);
                hit(new RectF(w.left, y, w.right, w.bottom), () -> { sel = idx; fresh = true; error = ""; });
                y += fh;
            }
            if (fields.length > 0) postInvalidateDelayed(500);

            // ---- aan/uit-knop ----
            if (toggleLabel != null) {
                RectF tb = new RectF(36, y + 6, 684, y + 66);
                pt.uiButton(c, tb, toggleLabel, toggle, key, 16);
                hit(tb, () -> { toggle = !toggle; error = ""; });
                y += 80;
            }
            if (!error.isEmpty()) pt.uiText(c, error, 40, Math.min(y + 18, H - 16), 12, RED, Paint.Align.LEFT);

            // ---- toetsenblok ----
            String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", extraKey, "0", "⌫"};
            float kx0 = 764, kx1 = 1212, ky0 = 26, gap = 12;
            float bottomH = 72;
            float kh = (H - ky0 - bottomH - 40 - gap * 3) / 4f, kw = (kx1 - kx0 - gap * 2) / 3f;
            boolean fresh2 = System.currentTimeMillis() - pressedAt < 150;
            for (int i = 0; i < 12; i++) {
                final String k = keys[i];
                float kx = kx0 + (i % 3) * (kw + gap), ky = ky0 + (i / 3) * (kh + gap);
                RectF r = new RectF(kx, ky, kx + kw, ky + kh);
                pt.uiKey(c, r, k, fresh2 && k.equals(pressed), key, k.length() > 1 ? 18 : 30);
                hit(r, () -> key(k));
            }
            // ---- opslaan / annuleren ----
            float by = H - bottomH - 22;
            RectF cancel = new RectF(kx0, by, kx0 + (kx1 - kx0 - gap) / 2f, by + bottomH);
            RectF ok = new RectF(cancel.right + gap, by, kx1, by + bottomH);
            pt.uiButton(c, cancel, Lang.t("ANNULEREN", "CANCEL"), false, key, 15);
            pt.uiButton(c, ok, Lang.t("OPSLAAN", "SAVE"), true, key, 16);
            hit(cancel, close);
            hit(ok, () -> {
                String[] vals = new String[fields.length];
                for (int i = 0; i < fields.length; i++) vals[i] = fields[i].v.toString();
                String err = save.save(vals, toggle);
                if (err == null) close.run(); else error = err;
            });
            c.restore();
            c.save(); c.scale(s, s);
            pt.uiScanlines(c, 1240, H);
            c.restore();
        }
    }
}

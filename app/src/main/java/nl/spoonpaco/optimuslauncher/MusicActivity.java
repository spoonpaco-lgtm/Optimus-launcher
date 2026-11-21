package nl.spoonpaco.optimuslauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Muziekscherm in cockpitstijl (bibliotheek links, nu speelt rechts). */
public class MusicActivity extends Activity implements MusicPlayer.Listener {
    private MusicPlayer mp;
    private MusicView v;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mp = MusicPlayer.get(this);
        Theme t = Theme.byId(getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led"));
        v = new MusicView(this, t);
        setContentView(v);
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 7);
        else if (mp.all().isEmpty() && !mp.scanning) mp.scan();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) mp.scan();
        v.invalidate();
    }

    @Override protected void onResume() { super.onResume(); mp.addListener(this); v.tick(); }
    @Override protected void onPause() { super.onPause(); mp.removeListener(this); v.removeCallbacks(v.ticker); }
    @Override public void onChanged() { v.invalidate(); }

    private class MusicView extends View {
        private final CockpitView pt;
        private final Theme theme;
        private float s = 1f, H = 650f;
        private int mode = 1;               // 0 mappen, 1 artiesten, 2 albums, 3 genres
        private String openGroup = null;     // null = groepenlijst
        private float scroll = 0, downY, lastY; private boolean dragging;
        private final List<Object[]> hits = new ArrayList<>();
        private final RectF listArea = new RectF(18, 132, 482, 0);
        final Runnable ticker = this::tick;

        MusicView(Context c, Theme t) {
            super(c);
            theme = t;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            pt = RetroUi.painter(c, t);
        }

        void tick() { invalidate(); removeCallbacks(ticker); postDelayed(ticker, 1000); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { s = w / 1240f; H = h / s; listArea.bottom = H - 18; }

        private void hit(RectF r, Runnable a) { hits.add(new Object[]{r, a}); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX() / s, y = e.getY() / s;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: downY = lastY = y; dragging = false; return true;
                case MotionEvent.ACTION_MOVE:
                    if (listArea.contains(x, downY) && Math.abs(y - downY) > 10) dragging = true;
                    if (dragging) { scroll = Math.max(0, scroll - (y - lastY)); lastY = y; invalidate(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) return true;
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        RectF r = (RectF) hits.get(i)[0];
                        if (r.contains(x, y)) { ((Runnable) hits.get(i)[1]).run(); invalidate(); return true; }
                    }
                    return true;
            }
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            hits.clear();
            c.save(); c.scale(s, s);
            int key = theme.key, AMB = pt.uiAmb(), GRN = pt.uiGrn();
            pt.uiBackground(c, 1240, H);
            RectF left = new RectF(0, 0, 500, H - 2), right = new RectF(512, 0, 1240, H - 2);
            pt.uiBezel(c, left); pt.uiBezel(c, right);

            // ---- links: bron + tabs + lijst ----
            pt.uiText(c, Lang.t("MUZIEK", "MUSIC"), 26, 52, 24, key, Paint.Align.LEFT);
            boolean usb = mp.root != null;
            pt.uiLamp(c, 346, 44, 8, GRN, usb && (mp.isPlaying() || mp.current() != null));
            pt.uiText(c, "USB", 360, 49, 13, usb ? GRN : 0xFF6C6C78, Paint.Align.LEFT);
            boolean bt = BtMedia.playing && BtMedia.title != null && !mp.isPlaying();
            pt.uiLamp(c, 420, 44, 8, GRN, bt);
            pt.uiText(c, "BT", 434, 49, 13, bt ? GRN : 0xFF6C6C78, Paint.Align.LEFT);

            String[] tabs = {Lang.t("MAP", "FOLDER"), Lang.t("ARTIEST", "ARTIST"), "ALBUM", "GENRE"};
            float tw = (464 - 3 * 8) / 4f;
            for (int i = 0; i < 4; i++) {
                final int m = i;
                RectF r = new RectF(18 + i * (tw + 8), 70, 18 + i * (tw + 8) + tw, 118);
                pt.uiButton(c, r, tabs[i], mode == i, key, 12);
                hit(r, () -> { mode = m; openGroup = null; scroll = 0; });
            }
            drawList(c, key);

            // ---- rechts: hoes + nu speelt ----
            float X = 536;
            RectF ar = new RectF(X, 26, X + 236, 262);
            pt.uiWindow(c, ar, key);
            Bitmap art = mp.art();
            if (art != null) {
                c.drawBitmap(art, null, new RectF(ar.left + 10, ar.top + 10, ar.right - 10, ar.bottom - 10), new Paint(Paint.FILTER_BITMAP_FLAG));
            } else placeholder(c, new RectF(ar.left + 10, ar.top + 10, ar.right - 10, ar.bottom - 10), key);
            pt.uiGlass(c, ar);

            MusicPlayer.Track t = mp.current();
            float nx = X + 252, nr = 1216;
            pt.uiText(c, Lang.t("NU SPEELT", "NOW PLAYING"), nx, 46, 13, dim(AMB), Paint.Align.LEFT);
            RectF np = new RectF(nx, 58, nr, 236);
            pt.uiWindow(c, np, AMB);
            c.save(); c.clipRect(np.left + 8, np.top, np.right - 8, np.bottom);
            if (t != null) {
                pt.uiText(c, t.label().toUpperCase(Locale.ROOT), np.left + 18, np.top + 48, 26, AMB, Paint.Align.LEFT);
                pt.uiText(c, (t.artist.isEmpty() ? "-" : t.artist).toUpperCase(Locale.ROOT), np.left + 18, np.top + 84, 20, 0xFFF0CC88, Paint.Align.LEFT);
                pt.uiText(c, (t.album.isEmpty() ? "" : t.album.toUpperCase(Locale.ROOT) + "  ·  ")
                        + String.format(Locale.US, Lang.t("NUMMER %d VAN %d", "TRACK %d OF %d"), mp.queueIndex() + 1, mp.queueSize()),
                        np.left + 18, np.top + 112, 13, dim(AMB), Paint.Align.LEFT);
            } else if (bt) {
                pt.uiText(c, BtMedia.title.toUpperCase(Locale.ROOT), np.left + 18, np.top + 48, 26, AMB, Paint.Align.LEFT);
                pt.uiText(c, String.valueOf(BtMedia.artist).toUpperCase(Locale.ROOT), np.left + 18, np.top + 82, 16, 0xFFE6BE78, Paint.Align.LEFT);
                pt.uiText(c, Lang.t("VIA BLUETOOTH (TELEFOON)", "VIA BLUETOOTH (PHONE)"), np.left + 18, np.top + 110, 10, dim(AMB), Paint.Align.LEFT);
            } else {
                pt.uiText(c, mp.scanning ? Lang.t("STICK WORDT GELEZEN...", "READING STICK...") + " " + mp.indexed
                        : !hasPerm() ? Lang.t("TOESTEMMING NODIG", "PERMISSION NEEDED")
                        : mp.root == null ? Lang.t("GEEN USB-STICK GEVONDEN", "NO USB STICK FOUND")
                        : Lang.t("KIES MUZIEK LINKS", "PICK MUSIC ON THE LEFT"), np.left + 18, np.top + 60, 16, AMB, Paint.Align.LEFT);
            }
            c.restore();
            int dur = mp.durationMs(), posMs = mp.positionMs();
            int lit = dur > 0 ? Math.round(30f * posMs / dur) : 0;
            pt.uiBar(c, np.left + 18, np.bottom - 40, np.right - 150, 12, 24, lit, 2);
            if (t != null) {
                pt.uiSegRight(c, MusicPlayer.time(posMs), np.right - 18, np.bottom - 70, 30, AMB);
                pt.uiText(c, "/ " + MusicPlayer.time(dur), np.right - 18, np.bottom - 14, 13, dim(AMB), Paint.Align.RIGHT);
            }
            pt.uiGlass(c, np);

            // voortgangs-scanner: kop volgt de positie in het nummer
            RectF sc = new RectF(nx, 248, nr, 276);
            pt.uiWindow(c, sc, pt.uiRed());
            int n = 24; float gw = (sc.width() - 24) / n;
            int headI = dur > 0 ? Math.round((n - 1) * posMs / (float) dur) : -10;
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < n; i++) {
                float L = Math.max(0f, 1f - Math.abs(i - headI) / 3.5f);
                q.setColor(mix(0xFF2A0807, pt.uiRed(), L * 0.8f));
                c.drawRoundRect(sc.left + 12 + i * gw + 2, sc.top + 9, sc.left + 12 + (i + 1) * gw - 2, sc.bottom - 9, 2, 2, q);
            }
            pt.uiGlass(c, sc);

            // knoppen
            float bw = (nr - X - 48) / 4f, by = 300;
            RectF b1 = new RectF(X, by + 12, X + bw, by + 74), b2 = new RectF(X + bw + 16, by + 12, X + 2 * bw + 16, by + 74),
                    b3 = new RectF(X + 2 * bw + 32, by + 12, X + 3 * bw + 32, by + 74), b4 = new RectF(X + 3 * bw + 48, by + 12, nr, by + 74);
            boolean playing = mp.isPlaying();
            pt.uiButton(c, b1, Lang.t("VORIGE", "PREV"), false, key, 15); hit(b1, mp::previous);
            pt.uiButton(c, b2, playing ? Lang.t("PAUZE", "PAUSE") : Lang.t("SPEEL", "PLAY"), playing, key, 15); hit(b2, mp::toggle);
            pt.uiButton(c, b3, Lang.t("VOLGENDE", "NEXT"), false, key, 15); hit(b3, mp::next);
            pt.uiButton(c, b4, "SHUFFLE", mp.shuffle, GRN, 15); hit(b4, () -> mp.setShuffle(!mp.shuffle));

            // hierna
            pt.uiText(c, Lang.t("HIERNA", "UP NEXT"), X, by + 108, 13, dim(AMB), Paint.Align.LEFT);
            float y = by + 120;
            int k = mp.queueIndex() + 2;
            for (MusicPlayer.Track u : mp.upNext(3)) {
                if (y + 50 > H - 80) break;
                RectF r = new RectF(X, y, nr, y + 50);
                pt.uiWindow(c, r, key);
                c.save(); c.clipRect(r.left, r.top, r.right - 90, r.bottom);
                pt.uiText(c, String.valueOf(k++), r.left + 18, r.centerY() + 7, 17, dim(AMB), Paint.Align.LEFT);
                pt.uiText(c, u.label().toUpperCase(Locale.ROOT) + (u.artist.isEmpty() ? "" : "  ·  " + u.artist.toUpperCase(Locale.ROOT)), r.left + 60, r.centerY() + 7, 17, 0xFFE0E0E8, Paint.Align.LEFT);
                c.restore();
                if (u.dur > 0) pt.uiText(c, MusicPlayer.time(u.dur), r.right - 18, r.centerY() + 7, 15, 0xFFB4B4C0, Paint.Align.RIGHT);
                pt.uiGlass(c, r);
                y += 58;
            }
            RectF close = new RectF(X, H - 70, X + 220, H - 20);
            pt.uiButton(c, close, Lang.t("SLUITEN", "CLOSE"), true, key, 15);
            hit(close, MusicActivity.this::finish);
            if (!hasPerm()) {
                RectF pr = new RectF(X + 240, H - 70, X + 520, H - 20);
                pt.uiButton(c, pr, Lang.t("TOESTAAN", "ALLOW"), false, key, 15);
                hit(pr, () -> requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 7));
            } else {
                RectF rs = new RectF(X + 240, H - 70, X + 520, H - 20);
                pt.uiButton(c, rs, Lang.t("STICK OPNIEUW LEZEN", "RESCAN STICK"), false, key, 13);
                hit(rs, mp::scan);
            }
            c.restore();
            c.save(); c.scale(s, s); pt.uiScanlines(c, 1240, H); c.restore();
        }

        private void drawList(Canvas c, int key) {
            LinkedHashMap<String, List<MusicPlayer.Track>> g = mp.groups(mode);
            List<Object[]> rows = new ArrayList<>(); // {label, sub, runnable, isPlayingRow}
            MusicPlayer.Track cur = mp.current();
            if (openGroup == null) {
                List<MusicPlayer.Track> all = mp.all();
                if (!all.isEmpty()) rows.add(new Object[]{Lang.t("ALLES AFSPELEN", "PLAY ALL"), all.size() + (all.size() == 1 ? Lang.t(" NUMMER", " TRACK") : Lang.t(" NUMMERS", " TRACKS")),
                        (Runnable) () -> mp.play(all, 0), false});
                for (java.util.Map.Entry<String, List<MusicPlayer.Track>> e : g.entrySet()) {
                    final String name = e.getKey();
                    boolean has = cur != null && e.getValue().contains(cur);
                    rows.add(new Object[]{name, e.getValue().size() + (e.getValue().size() == 1 ? Lang.t(" NUMMER", " TRACK") : Lang.t(" NUMMERS", " TRACKS")),
                            (Runnable) () -> { openGroup = name; scroll = 0; }, has});
                }
            } else {
                final List<MusicPlayer.Track> tr = g.get(openGroup);
                rows.add(new Object[]{"< " + Lang.t("TERUG", "BACK"), openGroup, (Runnable) () -> { openGroup = null; scroll = 0; }, false});
                if (tr != null) for (int i = 0; i < tr.size(); i++) {
                    final int idx = i;
                    MusicPlayer.Track t = tr.get(i);
                    rows.add(new Object[]{t.label(), (t.artist.isEmpty() ? "" : t.artist + "  ") + (t.dur > 0 ? MusicPlayer.time(t.dur) : ""),
                            (Runnable) () -> mp.play(tr, idx), t == cur});
                }
            }
            float rowH = 80, top = listArea.top;
            float maxScroll = Math.max(0, rows.size() * rowH - listArea.height());
            if (scroll > maxScroll) scroll = maxScroll;
            c.save(); c.clipRect(listArea);
            for (int i = 0; i < rows.size(); i++) {
                float y = top + i * rowH - scroll;
                if (y + rowH < listArea.top || y > listArea.bottom) continue;
                RectF r = new RectF(listArea.left, y + 2, listArea.right, y + rowH - 4);
                boolean on = (Boolean) rows.get(i)[3];
                if (on) pt.uiWindow(c, r, key);
                else { Paint q = new Paint(); q.setColor(0xFF121216); c.drawRoundRect(r, 5, 5, q); }
                pt.uiLamp(c, r.left + 20, r.centerY(), 7, key, on);
                c.save(); c.clipRect(r.left, r.top, r.right - 24, r.bottom);
                pt.uiText(c, String.valueOf(rows.get(i)[0]).toUpperCase(Locale.ROOT), r.left + 42, r.top + 32, 19, on ? key : 0xFFE8E8F0, Paint.Align.LEFT);
                pt.uiText(c, String.valueOf(rows.get(i)[1]).toUpperCase(Locale.ROOT), r.left + 42, r.top + 58, 12, 0xFFA8A8B4, Paint.Align.LEFT);
                c.restore();
                pt.uiText(c, ">", r.right - 10, r.centerY() + 7, 18, 0xFF8E8E9A, Paint.Align.RIGHT);
                if (on) pt.uiGlass(c, r);
                // alleen tikbaar als zichtbaar
                RectF hr = new RectF(r.left, Math.max(r.top, listArea.top), r.right, Math.min(r.bottom, listArea.bottom));
                if (hr.height() > 10) hit(hr, (Runnable) rows.get(i)[2]);
            }
            c.restore();
            if (rows.isEmpty()) pt.uiText(c, mp.scanning ? Lang.t("BEZIG MET LEZEN...", "READING...") : Lang.t("GEEN MUZIEK GEVONDEN", "NO MUSIC FOUND"),
                    listArea.left + 10, listArea.top + 40, 12, 0xFF6C6C78, Paint.Align.LEFT);
        }

        /** Eigen placeholder-hoes: zon boven een raster (zelf getekend). */
        private void placeholder(Canvas c, RectF r, int key) {
            c.save();
            c.clipRect(r);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            q.setShader(new LinearGradient(0, r.top, 0, r.centerY(), 0xFF14061E, 0xFF5A1430, Shader.TileMode.CLAMP));
            c.drawRect(r.left, r.top, r.right, r.centerY(), q);
            q.setShader(null);
            q.setColor(0xFF0C0614); c.drawRect(r.left, r.centerY(), r.right, r.bottom, q);
            q.setColor(key); c.drawCircle(r.centerX(), r.centerY(), r.width() * 0.22f, q);
            q.setColor(0xFF14061E);
            for (int i = 0; i < 5; i++) c.drawRect(r.left, r.centerY() - r.height() * 0.12f + i * r.height() * 0.025f, r.right, r.centerY() - r.height() * 0.12f + i * r.height() * 0.025f + 3, q);
            q.setColor(0xFF0C0614); c.drawRect(r.left, r.centerY(), r.right, r.bottom, q);
            q.setColor(0xFFD23CC8); q.setStrokeWidth(2);
            for (int i = 0; i <= 8; i++) c.drawLine(r.centerX(), r.centerY(), r.left - r.width() * 0.3f + i * r.width() * 1.6f / 8f, r.bottom, q);
            for (int i = 1; i < 7; i++) { float yy = r.centerY() + r.height() / 2f * (float) Math.pow(i / 7f, 1.8); c.drawLine(r.left, yy, r.right, yy, q); }
            c.restore();
        }

        private boolean hasPerm() { return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED; }
        private int dim(int col) { return 0xFF000000 | ((int) (((col >> 16) & 0xFF) * 0.8f) << 16) | ((int) (((col >> 8) & 0xFF) * 0.8f) << 8) | (int) ((col & 0xFF) * 0.8f); }
        private int mix(int a, int b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            return 0xFF000000 | ((int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t) << 16)
                    | ((int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t) << 8) | (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        }
    }
}

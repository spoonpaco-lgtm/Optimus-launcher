package nl.spoonpaco.optimuslauncher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Volledige muziekspeler in cockpitstijl: bibliotheek links, nu speelt rechts. Gebruikt in het muziekscherm en zwevend. */
public class MusicPanel extends View {
    /** Wat het paneel van zijn omgeving nodig heeft (activity of zwevend venster). */
    public interface Host {
        void close();
        void requestPerm();
        default void drag(float dxPx, float dyPx) { }
        default void dragEnd() { }
    }
    private final MusicPlayer mp;
    private final Host host;
    private final boolean floating;
    private boolean movingWindow; private float mwX, mwY;
    private float upX;   // x van de laatste tik (ontwerp-eenheden), voor spoelen
    private final CockpitView pt;
    private final Theme theme;
    private float s = 1f, H = 650f;
    private int mode = 1;               // 0 mappen, 1 artiesten, 2 albums, 3 genres
    private final List<String> path = new ArrayList<>();   // gekozen groepen, bv. [artiest, album]
    private boolean searching = false, favView = false;
    private final StringBuilder query = new StringBuilder();
    private float scroll = 0, downY, lastY; private boolean dragging;
    private final List<Object[]> hits = new ArrayList<>();
    private final RectF listArea = new RectF(18, 132, 482, 0);
    public final Runnable ticker = this::tick;

    public MusicPanel(Context c, Theme t, Host host, boolean floating) {
        super(c);
        this.mp = MusicPlayer.get(c); this.host = host; this.floating = floating;
        theme = t;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        pt = RetroUi.painter(c, t);
    }

    /** Terugknop: eerst zoeken sluiten, dan een niveau omhoog. */
    public boolean back() {
        if (searching) { searching = false; invalidate(); return true; }
        if (!path.isEmpty()) { path.remove(path.size() - 1); scroll = 0; invalidate(); return true; }
        return false;
    }

    public void tick() { invalidate(); removeCallbacks(ticker); postDelayed(ticker, 1000); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { s = w / 1240f; H = h / s; listArea.bottom = H - 18; }

    private void hit(RectF r, Runnable a) { hits.add(new Object[]{r, a}); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / s, y = e.getY() / s;
        if (floating) {
            // titelbalk ("MUZIEK") = venster verslepen, zoals Winamp
            if (e.getAction() == MotionEvent.ACTION_DOWN && y < 62 && x < 330) { movingWindow = true; mwX = e.getRawX(); mwY = e.getRawY(); return true; }
            if (movingWindow) {
                if (e.getAction() == MotionEvent.ACTION_MOVE) { host.drag(e.getRawX() - mwX, e.getRawY() - mwY); mwX = e.getRawX(); mwY = e.getRawY(); }
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { movingWindow = false; host.dragEnd(); }
                return true;
            }
        }
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN: downY = lastY = y; dragging = false; return true;
            case MotionEvent.ACTION_MOVE:
                if (curList.contains(x, downY) && Math.abs(y - downY) > 10) dragging = true;
                if (dragging) { scroll = Math.max(0, scroll - (y - lastY)); lastY = y; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) return true;
                for (int i = hits.size() - 1; i >= 0; i--) {
                    RectF r = (RectF) hits.get(i)[0];
                    if (r.contains(x, y)) { upX = x; ((Runnable) hits.get(i)[1]).run(); invalidate(); return true; }
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
        float tw = (464 - 4 * 8 - 70) / 4f;
        RectF sr = new RectF(18 + 4 * (tw + 8), 70, 482, 118);
        pt.uiKey(c, sr, Lang.t("ZOEK", "FIND"), searching, key, 13);
        hit(sr, () -> { searching = !searching; scroll = 0; });
        for (int i = 0; i < 4; i++) {
            final int m = i;
            RectF r = new RectF(18 + i * (tw + 8), 70, 18 + i * (tw + 8) + tw, 118);
            pt.uiButton(c, r, tabs[i], mode == i && !searching, key, 11);
            hit(r, () -> { mode = m; path.clear(); searching = false; favView = false; scroll = 0; });
        }
        drawList(c, key);

        // ---- rechts: hoes + nu speelt (tijdens zoeken: de resultaten) ----
        float X = 536;
        if (searching) {
            drawRows(c, key, searchResults, new RectF(X, 26, 1216, H - 90), 72);
            drawBottomButtons(c, key, X);
            c.restore();
            c.save(); c.scale(s, s); pt.uiScanlines(c, 1240, H); c.restore();
            return;
        }
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
            c.save(); c.clipRect(np.left, np.top, np.right - 170, np.bottom);
            pt.uiText(c, (t.album.isEmpty() ? "" : t.album.toUpperCase(Locale.ROOT) + "  ·  ")
                    + String.format(Locale.US, Lang.t("NUMMER %d VAN %d", "TRACK %d OF %d"), mp.queueIndex() + 1, mp.queueSize()),
                    np.left + 18, np.top + 112, 13, dim(AMB), Paint.Align.LEFT);
            c.restore();
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
        int lit = dur > 0 ? Math.round(24f * posMs / dur) : 0;
        final RectF pb = new RectF(np.left + 18, np.bottom - 50, np.right - 150, np.bottom - 26);
        pt.uiBar(c, pb.left, pb.top, pb.right, pb.height(), 24, lit, 2);
        hit(new RectF(pb.left - 6, pb.top - 16, pb.right + 6, pb.bottom + 14), () -> mp.seekFraction((upX - pb.left) / pb.width()));
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
        final RectF scf = new RectF(sc);
        hit(scf, () -> mp.seekFraction((upX - scf.left - 12) / (scf.width() - 24)));

        // knoppen: vorige / speel-pauze / volgende als iconen, dan shuffle / herhaal / favoriet met lampje
        float by = 300, kw2 = 92, gap2 = 10;
        boolean playing = mp.isPlaying();
        RectF k1 = new RectF(X, by + 12, X + kw2, by + 74), k2 = new RectF(k1.right + gap2, by + 12, k1.right + gap2 + kw2, by + 74),
                k3 = new RectF(k2.right + gap2, by + 12, k2.right + gap2 + kw2, by + 74);
        pt.uiMusicKey(c, k1, 0, false, playing); hit(k1, mp::previous);
        pt.uiMusicKey(c, k2, 1, false, playing); hit(k2, mp::toggle);
        pt.uiMusicKey(c, k3, 2, false, playing); hit(k3, mp::next);
        float tw3 = (nr - k3.right - 3 * gap2) / 3f;
        RectF b4 = new RectF(k3.right + gap2, by + 12, k3.right + gap2 + tw3, by + 74);
        RectF b5 = new RectF(b4.right + gap2, by + 12, b4.right + gap2 + tw3, by + 74);
        RectF b6 = new RectF(b5.right + gap2, by + 12, nr, by + 74);
        pt.uiButton(c, b4, "SHUFFLE", mp.shuffle, GRN, 12); hit(b4, () -> mp.setShuffle(!mp.shuffle));
        String rep = mp.repeat == 2 ? Lang.t("HERHAAL 1", "REPEAT 1") : Lang.t("HERHAAL", "REPEAT");
        pt.uiButton(c, b5, rep, mp.repeat != 0, GRN, 12); hit(b5, mp::cycleRepeat);
        pt.uiButton(c, b6, Lang.t("FAVORIET", "FAVOURITE"), mp.isFav(t), key, 12); hit(b6, () -> mp.toggleFav(mp.current()));

        // hierna
        pt.uiText(c, Lang.t("HIERNA", "UP NEXT"), X, by + 108, 13, dim(AMB), Paint.Align.LEFT);
        float y = by + 120;
        int k = mp.queueIndex() + 2;
        int jump = mp.queueIndex();
        for (MusicPlayer.Track u : mp.upNext(3)) {
            if (y + 50 > H - 80) break;
            RectF r = new RectF(X, y, nr, y + 50);
            final int target = ++jump;
            hit(r, () -> mp.jumpTo(target));
            pt.uiWindow(c, r, key);
            c.save(); c.clipRect(r.left, r.top, r.right - 90, r.bottom);
            pt.uiText(c, String.valueOf(k++), r.left + 18, r.centerY() + 7, 17, dim(AMB), Paint.Align.LEFT);
            pt.uiText(c, u.label().toUpperCase(Locale.ROOT) + (u.artist.isEmpty() ? "" : "  ·  " + u.artist.toUpperCase(Locale.ROOT)), r.left + 60, r.centerY() + 7, 17, 0xFFE0E0E8, Paint.Align.LEFT);
            c.restore();
            if (u.dur > 0) pt.uiText(c, MusicPlayer.time(u.dur), r.right - 30, r.centerY() + 7, 15, 0xFFB4B4C0, Paint.Align.RIGHT);
            pt.uiText(c, ">", r.right - 8, r.centerY() + 7, 15, 0xFF8E8E9A, Paint.Align.RIGHT);
            pt.uiGlass(c, r);
            y += 58;
        }
        drawBottomButtons(c, key, X);
        c.restore();
        c.save(); c.scale(s, s); pt.uiScanlines(c, 1240, H); c.restore();
    }

    private void drawBottomButtons(Canvas c, int key, float X) {
        RectF close = new RectF(X, H - 70, X + 220, H - 20);
        pt.uiButton(c, close, floating ? Lang.t("INKLAPPEN", "COLLAPSE") : Lang.t("SLUITEN", "CLOSE"), true, key, 15);
        hit(close, host::close);
        if (!hasPerm()) {
            RectF pr = new RectF(X + 240, H - 70, X + 520, H - 20);
            pt.uiButton(c, pr, Lang.t("TOESTAAN", "ALLOW"), false, key, 15);
            hit(pr, host::requestPerm);
        } else {
            RectF rs = new RectF(X + 240, H - 70, X + 520, H - 20);
            pt.uiButton(c, rs, Lang.t("STICK OPNIEUW LEZEN", "RESCAN STICK"), false, key, 13);
            hit(rs, mp::scan);
        }
    }

    /** Indeling per tabblad: welke niveaus je doorklikt voordat je bij de nummers komt. */
    private int[] levels() {
        switch (mode) {
            case 0: return new int[]{0};          // map
            case 1: return new int[]{1, 2};       // artiest > album
            case 2: return new int[]{2};          // album
            default: return new int[]{3, 1, 2};   // genre > artiest > album
        }
    }

    private String keyOf(MusicPlayer.Track t, int lvl) {
        switch (lvl) {
            case 0: return t.folder;
            case 1: return t.groupArtist().isEmpty() ? Lang.t("ONBEKENDE ARTIEST", "UNKNOWN ARTIST") : t.groupArtist();
            case 2: return t.album.isEmpty() ? Lang.t("ONBEKEND ALBUM", "UNKNOWN ALBUM") : t.album;
            default: return t.genre.isEmpty() ? Lang.t("GEEN GENRE", "NO GENRE") : t.genre;
        }
    }

    private String count(int n) { return n + (n == 1 ? Lang.t(" NUMMER", " TRACK") : Lang.t(" NUMMERS", " TRACKS")); }

    private void sortTracks(List<MusicPlayer.Track> l) {
        java.util.Collections.sort(l, (x, y) -> {
            int c = MusicPlayer.SMART.compare(x.groupArtist(), y.groupArtist());
            return c != 0 && mode != 2 ? c : MusicPlayer.ALBUM_ORDER.compare(x, y);
        });
    }

    private void drawList(Canvas c, int key) {
        List<Object[]> rows = new ArrayList<>(); // {label, sub, runnable, isPlayingRow}
        MusicPlayer.Track cur = mp.current();
        if (searching) { searchRows(rows, cur); }
        else if (favView) {
            final List<MusicPlayer.Track> fl = mp.favorites();
            rows.add(new Object[]{"< " + Lang.t("TERUG", "BACK"), Lang.t("FAVORIETEN", "FAVOURITES"), (Runnable) () -> { favView = false; scroll = 0; }, false});
            if (!fl.isEmpty()) rows.add(new Object[]{Lang.t("ALLE FAVORIETEN AFSPELEN", "PLAY ALL FAVOURITES"), count(fl.size()), (Runnable) () -> mp.play(fl, 0), false});
            for (int i = 0; i < fl.size(); i++) {
                final int idx = i;
                MusicPlayer.Track t = fl.get(i);
                rows.add(new Object[]{t.label(), (t.groupArtist().isEmpty() ? "" : t.groupArtist() + "  ") + (t.dur > 0 ? MusicPlayer.time(t.dur) : ""),
                        (Runnable) () -> mp.play(fl, idx), t == cur});
            }
        }
        else {
            int[] lv = levels();
            List<MusicPlayer.Track> set = new ArrayList<>();
            for (MusicPlayer.Track t : mp.all()) {
                boolean ok = true;
                for (int d = 0; d < path.size() && d < lv.length; d++) if (!keyOf(t, lv[d]).equals(path.get(d))) { ok = false; break; }
                if (ok) set.add(t);
            }
            if (!path.isEmpty()) {
                String crumb = android.text.TextUtils.join("  >  ", path);
                rows.add(new Object[]{"< " + Lang.t("TERUG", "BACK"), crumb, (Runnable) () -> { path.remove(path.size() - 1); scroll = 0; }, false});
            }
            if (path.size() < lv.length) {
                final List<MusicPlayer.Track> playAll = new ArrayList<>(set);
                sortTracks(playAll);
                if (!playAll.isEmpty()) rows.add(new Object[]{path.isEmpty() ? Lang.t("ALLES AFSPELEN", "PLAY ALL") : Lang.t("ALLES VAN ", "ALL OF ") + path.get(path.size() - 1),
                        count(playAll.size()), (Runnable) () -> mp.play(playAll, 0), false});
                if (path.isEmpty()) {
                    int nf = mp.favorites().size();
                    if (nf > 0) rows.add(new Object[]{Lang.t("FAVORIETEN", "FAVOURITES"), count(nf), (Runnable) () -> { favView = true; scroll = 0; }, false});
                }
                int lvl = lv[path.size()];
                java.util.TreeMap<String, List<MusicPlayer.Track>> g = new java.util.TreeMap<>(MusicPlayer.SMART);
                for (MusicPlayer.Track t : set) {
                    String k = keyOf(t, lvl);
                    List<MusicPlayer.Track> l = g.get(k);
                    if (l == null) { l = new ArrayList<>(); g.put(k, l); }
                    l.add(t);
                }
                for (java.util.Map.Entry<String, List<MusicPlayer.Track>> e : g.entrySet()) {
                    final String name = e.getKey();
                    boolean has = cur != null && e.getValue().contains(cur);
                    String sub = count(e.getValue().size());
                    if (lvl == 2 && !e.getValue().get(0).artist.isEmpty() && path.isEmpty()) sub = e.getValue().get(0).artist + "  ·  " + sub;
                    rows.add(new Object[]{name, sub, (Runnable) () -> { path.add(name); scroll = 0; }, has});
                }
            } else {
                final List<MusicPlayer.Track> tr = set;
                sortTracks(tr);
                for (int i = 0; i < tr.size(); i++) {
                    final int idx = i;
                    MusicPlayer.Track t = tr.get(i);
                    rows.add(new Object[]{(t.no > 0 ? t.no + ". " : "") + t.label(), (mode == 0 && !t.artist.isEmpty() ? t.artist + "  " : "") + (t.dur > 0 ? MusicPlayer.time(t.dur) : ""),
                            (Runnable) () -> mp.play(tr, idx), t == cur});
                }
            }
        }
        if (searching) {
            drawSearchBox(c, key);
            searchResults = rows;
            return;
        }
        drawRows(c, key, rows, listArea, 80);
    }

    private List<Object[]> searchResults = new ArrayList<>();

    /** Lijst met regels {label, sub, actie, speelt} in een vak, scrollbaar. */
    private void drawRows(Canvas c, int key, List<Object[]> rows, RectF la, float rowH) {
        curList.set(la);
        float top = la.top;
        float maxScroll = Math.max(0, rows.size() * rowH - la.height());
        if (scroll > maxScroll) scroll = maxScroll;
        c.save(); c.clipRect(la);
        for (int i = 0; i < rows.size(); i++) {
            float y = top + i * rowH - scroll;
            if (y + rowH < la.top || y > la.bottom) continue;
            RectF r = new RectF(la.left, y + 2, la.right, y + rowH - 4);
            boolean on = (Boolean) rows.get(i)[3];
            if (on) pt.uiWindow(c, r, key);
            else { Paint q = new Paint(); q.setColor(0xFF121216); c.drawRoundRect(r, 5, 5, q); }
            pt.uiLamp(c, r.left + 20, r.centerY(), 7, key, on);
            c.save(); c.clipRect(r.left, r.top, r.right - 24, r.bottom);
            float ts = rowH >= 76 ? 19 : 18;
            pt.uiText(c, String.valueOf(rows.get(i)[0]).toUpperCase(Locale.ROOT), r.left + 42, r.top + rowH * 0.4f, ts, on ? key : 0xFFE8E8F0, Paint.Align.LEFT);
            pt.uiText(c, String.valueOf(rows.get(i)[1]).toUpperCase(Locale.ROOT), r.left + 42, r.top + rowH * 0.72f, 12, 0xFFA8A8B4, Paint.Align.LEFT);
            c.restore();
            pt.uiText(c, ">", r.right - 10, r.centerY() + 7, 18, 0xFF8E8E9A, Paint.Align.RIGHT);
            if (on) pt.uiGlass(c, r);
            RectF hr = new RectF(r.left, Math.max(r.top, la.top), r.right, Math.min(r.bottom, la.bottom));
            if (hr.height() > 10) hit(hr, (Runnable) rows.get(i)[2]);
        }
        c.restore();
        if (rows.isEmpty()) pt.uiText(c, searching ? (query.length() == 0 ? Lang.t("TYP LINKS EEN ARTIEST, ALBUM OF NUMMER", "TYPE AN ARTIST, ALBUM OR SONG ON THE LEFT") : Lang.t("NIETS GEVONDEN", "NOTHING FOUND"))
                : mp.scanning ? Lang.t("BEZIG MET LEZEN...", "READING...") : Lang.t("GEEN MUZIEK GEVONDEN", "NO MUSIC FOUND"),
                la.left + 10, la.top + 40, 15, 0xFF8E8E9A, Paint.Align.LEFT);
    }

    private final RectF curList = new RectF();

    /** Zoekveld + eigen toetsenbord onderin; geeft het vak voor de resultaten terug. */
    private RectF drawSearchBox(Canvas c, int key) {
        int AMB = pt.uiAmb();
        RectF q = new RectF(listArea.left, listArea.top, listArea.right, listArea.top + 52);
        pt.uiWindow(c, q, AMB);
        pt.uiLamp(c, q.left + 20, q.centerY(), 7, AMB, true);
        String txt = query.length() == 0 ? Lang.t("ZOEKEN...", "SEARCH...") : query.toString();
        pt.uiText(c, txt + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : ""), q.left + 40, q.centerY() + 8, 22,
                query.length() == 0 ? 0xFF6C6C78 : AMB, Paint.Align.LEFT);
        pt.uiGlass(c, q);
        postInvalidateDelayed(500);

        String[] rowsK = {"1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
        float gap = 7, kbTop = q.bottom + 16, kh = (listArea.bottom - kbTop - 4 * gap) / 5f;
        float kw = (listArea.width() - 9 * gap) / 10f;
        for (int rI = 0; rI < 4; rI++) {
            String r = rowsK[rI];
            float off = (10 - r.length()) * (kw + gap) / 2f;
            for (int i = 0; i < r.length(); i++) {
                final String ch = String.valueOf(r.charAt(i));
                RectF kr = new RectF(listArea.left + off + i * (kw + gap), kbTop + rI * (kh + gap),
                        listArea.left + off + i * (kw + gap) + kw, kbTop + rI * (kh + gap) + kh);
                pt.uiKey(c, kr, ch, false, key, 26);
                hit(kr, () -> { if (query.length() < 30) query.append(ch); scroll = 0; });
            }
        }
        float by = kbTop + 4 * (kh + gap);
        float w3 = (listArea.width() - 2 * gap);
        RectF sp = new RectF(listArea.left, by, listArea.left + w3 * 0.5f, by + kh);
        RectF bs = new RectF(sp.right + gap, by, sp.right + gap + w3 * 0.25f, by + kh);
        RectF cl = new RectF(bs.right + gap, by, listArea.right, by + kh);
        pt.uiKey(c, sp, Lang.t("SPATIE", "SPACE"), false, key, 18);
        pt.uiKey(c, bs, "<", false, key, 26);
        pt.uiKey(c, cl, Lang.t("WIS", "CLEAR"), false, key, 18);
        hit(sp, () -> { if (query.length() > 0 && query.charAt(query.length() - 1) != ' ') query.append(' '); });
        hit(bs, () -> { if (query.length() > 0) query.setLength(query.length() - 1); scroll = 0; });
        hit(cl, () -> { query.setLength(0); scroll = 0; });
        return q;
    }

    /** Zoekresultaten: artiesten, albums en nummers waar de zoektekst in voorkomt. */
    private void searchRows(List<Object[]> rows, MusicPlayer.Track cur) {
        String q = query.toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        java.util.TreeMap<String, Integer> artists = new java.util.TreeMap<>(MusicPlayer.SMART), albums = new java.util.TreeMap<>(MusicPlayer.SMART);
        java.util.Map<String, String> albumArtist = new java.util.HashMap<>();
        final List<MusicPlayer.Track> tracks = new ArrayList<>();
        for (MusicPlayer.Track t : mp.all()) {
            String ga = t.groupArtist();
            if (!ga.isEmpty() && (ga.toLowerCase(Locale.ROOT).contains(q) || t.artist.toLowerCase(Locale.ROOT).contains(q))) artists.merge(ga, 1, Integer::sum);
            if (!t.album.isEmpty() && t.album.toLowerCase(Locale.ROOT).contains(q)) { albums.merge(t.album, 1, Integer::sum); albumArtist.put(t.album, t.groupArtist()); }
            if (t.label().toLowerCase(Locale.ROOT).contains(q) && tracks.size() < 40) tracks.add(t);
        }
        int n = 0;
        for (java.util.Map.Entry<String, Integer> e : artists.entrySet()) {
            if (n++ >= 6) break;
            final String a = e.getKey();
            rows.add(new Object[]{a, Lang.t("ARTIEST  ·  ", "ARTIST  ·  ") + count(e.getValue()),
                    (Runnable) () -> { searching = false; mode = 1; path.clear(); path.add(a); scroll = 0; }, false});
        }
        n = 0;
        for (java.util.Map.Entry<String, Integer> e : albums.entrySet()) {
            if (n++ >= 6) break;
            final String al = e.getKey();
            String ar = albumArtist.get(al);
            rows.add(new Object[]{al, "ALBUM  ·  " + (ar == null || ar.isEmpty() ? "" : ar + "  ·  ") + count(e.getValue()),
                    (Runnable) () -> { searching = false; mode = 2; path.clear(); path.add(al); scroll = 0; }, false});
        }
        for (int i = 0; i < tracks.size(); i++) {
            final int idx = i;
            MusicPlayer.Track t = tracks.get(i);
            rows.add(new Object[]{t.label(), Lang.t("NUMMER", "SONG") + (t.artist.isEmpty() ? "" : "  ·  " + t.artist),
                    (Runnable) () -> mp.play(tracks, idx), t == cur});
        }
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

    private boolean hasPerm() { return getContext().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED; }
    private int dim(int col) { return 0xFF000000 | ((int) (((col >> 16) & 0xFF) * 0.8f) << 16) | ((int) (((col >> 8) & 0xFF) * 0.8f) << 8) | (int) ((col & 0xFF) * 0.8f); }
    private int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 0xFF000000 | ((int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t) << 16)
                | ((int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t) << 8) | (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
    }
}

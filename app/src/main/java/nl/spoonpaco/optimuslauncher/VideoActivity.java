package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Videospeler in cockpitstijl. Video's van de USB-stick per map, spoelen, verder kijken waar je was.
 * Veiligheid: zodra de auto rijdt (> 5 km/h volgens GPS) verdwijnt het beeld; het geluid loopt door.
 */
public class VideoActivity extends Activity {
    private static final String[] EXT = {".mp4", ".mkv", ".avi", ".mov", ".m4v", ".3gp", ".webm", ".ts", ".mpg", ".mpeg", ".wmv"};
    private static final float MOVING = 5f, STOPPED = 2f;

    private static List<File> videos = new ArrayList<>();
    private static volatile boolean scanning = false;
    private static String scannedRoot = null;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Panel panel;
    private TextureView tex;
    private SubView subView;
    private Subtitles subs;
    private long subOffset;   // ms; positief = ondertitels later
    private MediaPlayer mp;
    private Surface surface;
    private File current;
    private boolean prepared, full, blocked;
    private int vw, vh;
    private SharedPreferences prefs;
    private final AudioManager.OnAudioFocusChangeListener focus = f -> {
        if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("video", MODE_PRIVATE);
        Theme t = Theme.byId(getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led"));
        FrameLayout root = new FrameLayout(this);
        panel = new Panel(this, t);
        tex = new TextureView(this);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(tex, new FrameLayout.LayoutParams(1, 1));
        subView = new SubView(this);
        root.addView(subView, new FrameLayout.LayoutParams(1, 1));
        setContentView(root);
        tex.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                surface = new Surface(st);
                if (mp != null) try { mp.setSurface(surface); } catch (Exception ignored) { }
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (mp != null) try { mp.setSurface(null); } catch (Exception ignored) { }
                if (surface != null) surface.release();
                surface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        tex.setOnClickListener(v -> { if (full) { full = false; layoutVideo(); } else togglePlay(); });
        scan();
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        savePos();
        pause();
    }
    @Override protected void onDestroy() { super.onDestroy(); release(); }
    @Override public void onBackPressed() {
        if (full) { full = false; layoutVideo(); return; }
        if (panel.subMode) { panel.subMode = false; panel.invalidate(); return; }
        if (panel.folder != null) { panel.folder = null; panel.scroll = 0; panel.invalidate(); return; }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- bestanden

    private void scan() {
        File r = MusicPlayer.get(this).findRoot();
        String rp = r == null ? null : r.getAbsolutePath();
        if (scanning || (rp != null && rp.equals(scannedRoot) && !videos.isEmpty())) return;
        scanning = true; panel.invalidate();
        new Thread(() -> {
            List<File> out = new ArrayList<>();
            if (r != null) walk(r, out, 0);
            Collections.sort(out, (a, b) -> MusicPlayer.natural(a.getAbsolutePath().toLowerCase(Locale.ROOT), b.getAbsolutePath().toLowerCase(Locale.ROOT)));
            videos = out; scannedRoot = rp; scanning = false;
            CarLogger.note("video: " + out.size() + " video's op " + rp);
            h.post(panel::invalidate);
        }, "video-scan").start();
    }

    private static void walk(File dir, List<File> out, int depth) {
        if (depth > 6 || out.size() > 3000) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".") || n.equals("Android") || n.equals("LOST.DIR")) continue;
            if (f.isDirectory()) walk(f, out, depth + 1);
            else { String l = n.toLowerCase(Locale.ROOT); for (String e : EXT) if (l.endsWith(e)) { out.add(f); break; } }
        }
    }

    // ---------------------------------------------------------------- afspelen

    private void play(File f) {
        savePos();
        release();
        current = f;
        MusicPlayer.get(this).pause();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        mp = new MediaPlayer();
        prepared = false;
        loadSubs(f);
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(f.getAbsolutePath());
            if (surface != null) mp.setSurface(surface);
            mp.setOnPreparedListener(m -> {
                prepared = true;
                int resume = prefs.getInt(key(f), 0);
                if (resume > 5000 && resume < m.getDuration() - 10000) m.seekTo(resume);
                m.start();
                panel.invalidate();
            });
            mp.setOnVideoSizeChangedListener((m, w, hh) -> { vw = w; vh = hh; layoutVideo(); });
            mp.setOnCompletionListener(m -> { prefs.edit().remove(key(f)).apply(); panel.invalidate(); });
            mp.setOnErrorListener((m, w, x) -> { CarLogger.note("video: kan niet afspelen " + f.getName() + " (" + w + "/" + x + ")"); panel.error = Lang.t("DIT BESTAND KAN NIET WORDEN AFGESPEELD", "THIS FILE CANNOT BE PLAYED"); panel.invalidate(); return true; });
            mp.prepareAsync();
            panel.error = "";
            CarLogger.note("video: speelt " + f.getName());
        } catch (Exception e) {
            panel.error = Lang.t("OPENEN MISLUKT", "COULD NOT OPEN");
        }
        layoutVideo();
        panel.invalidate();
    }

    private void togglePlay() {
        if (mp == null || !prepared) return;
        try { if (mp.isPlaying()) { mp.pause(); savePos(); } else mp.start(); } catch (Exception ignored) { }
        panel.invalidate();
    }

    private void pause() { try { if (mp != null && prepared && mp.isPlaying()) mp.pause(); } catch (Exception ignored) { } }

    private void seekBy(int ms) { try { if (mp != null && prepared) mp.seekTo(Math.max(0, Math.min(mp.getDuration() - 1000, mp.getCurrentPosition() + ms))); } catch (Exception ignored) { } }

    private void seekFrac(float f) { try { if (mp != null && prepared) mp.seekTo(Math.round(Math.max(0f, Math.min(1f, f)) * mp.getDuration())); } catch (Exception ignored) { } }

    private boolean playing() { try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; } }
    private int pos() { try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    private int dur() { try { return mp != null && prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; } }

    private static String key(File f) { return "p:" + f.getAbsolutePath(); }

    private void savePos() {
        if (current != null && prepared) prefs.edit().putInt(key(current), pos()).putString("last", current.getAbsolutePath()).apply();
    }

    private void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } }
        mp = null; prepared = false;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.abandonAudioFocus(focus);
    }

    private long lastSave, stoppedSince;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // stilstand-blokkade
            float v = TrackerService.speedKmh;
            boolean fresh = System.currentTimeMillis() - TrackerService.lastFixTime < 5000;
            long now = System.currentTimeMillis();
            if (fresh && v > MOVING) { if (!blocked) { blocked = true; full = false; CarLogger.note("video: beeld uit (rijdt " + Math.round(v) + " km/h)"); layoutVideo(); } stoppedSince = 0; }
            else if (blocked && (!fresh || v < STOPPED)) {
                if (stoppedSince == 0) stoppedSince = now;
                if (now - stoppedSince > 2000) { blocked = false; layoutVideo(); }
            }
            if (now - lastSave > 5000) { lastSave = now; if (playing()) savePos(); }
            panel.invalidate();
            subView.invalidate();
            h.postDelayed(this, 250);
        }
    };

    /** TextureView op het videovenster leggen (of volledig scherm), met de juiste verhouding. */
    private void layoutVideo() {
        if (panel.getWidth() == 0) { panel.post(this::layoutVideo); return; }
        float s = panel.getWidth() / 1240f;
        RectF box = full ? new RectF(0, 0, panel.getWidth(), panel.getHeight())
                : new RectF(panel.video.left * s, panel.video.top * s, panel.video.right * s, panel.video.bottom * s);
        if (!full) box.inset(8 * s, 8 * s);
        float bw = box.width(), bh = box.height();
        float w = bw, hh = bh;
        if (vw > 0 && vh > 0) {
            float ar = vw / (float) vh;
            if (bw / bh > ar) { hh = bh; w = bh * ar; } else { w = bw; hh = bw / ar; }
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, Math.round(w)), Math.max(1, Math.round(hh)));
        lp.leftMargin = Math.round(box.left + (bw - w) / 2); lp.topMargin = Math.round(box.top + (bh - hh) / 2);
        tex.setLayoutParams(lp);
        tex.setVisibility(current != null && !blocked ? View.VISIBLE : View.INVISIBLE);
        subView.setLayoutParams(new FrameLayout.LayoutParams(lp));
        subView.setVisibility(tex.getVisibility());
        panel.invalidate();
    }

    // ---------------------------------------------------------------- ondertitels

    /** Gekozen ondertitel (of automatisch het bestand met dezelfde naam) + opgeslagen sync laden. */
    private void loadSubs(File f) {
        subs = null;
        String choice = prefs.getString("s:" + f.getAbsolutePath(), null);
        subOffset = prefs.getLong("o:" + f.getAbsolutePath(), 0);
        if ("-".equals(choice)) return;
        File sf = null;
        if (choice != null && new File(choice).exists()) sf = new File(choice);
        else {
            String base = f.getName().replaceAll("\\.[^.]+$", "").toLowerCase(Locale.ROOT);
            for (File c : Subtitles.candidates(f)) if (c.getName().toLowerCase(Locale.ROOT).startsWith(base)) { sf = c; break; }
        }
        if (sf != null) {
            subs = Subtitles.load(sf);
            CarLogger.note("video: ondertitel " + sf.getName() + " (" + subs.times.size() + " regels)");
        }
    }

    private void chooseSubs(File sf) {
        if (current == null) return;
        prefs.edit().putString("s:" + current.getAbsolutePath(), sf == null ? "-" : sf.getAbsolutePath()).apply();
        subs = sf == null ? null : Subtitles.load(sf);
        subView.invalidate();
    }

    private void shiftSubs(long delta) {
        if (current == null) return;
        subOffset = delta == 0 ? 0 : subOffset + delta;
        prefs.edit().putLong("o:" + current.getAbsolutePath(), subOffset).apply();
        subView.invalidate();
    }

    /** Tekst over het beeld: wit met zwarte rand, onderaan gecentreerd. */
    private class SubView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        SubView(Context c) {
            super(c);
            setClickable(false); setFocusable(false);
            fill.setColor(0xFFFFFFFF); fill.setTextAlign(Paint.Align.CENTER); fill.setFakeBoldText(true);
            edge.setColor(0xFF000000); edge.setTextAlign(Paint.Align.CENTER); edge.setFakeBoldText(true);
            edge.setStyle(Paint.Style.STROKE); edge.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public boolean onTouchEvent(MotionEvent e) { return false; }
        @Override protected void onDraw(Canvas c) {
            if (subs == null || blocked || !prepared) return;
            String t = subs.at(pos() - subOffset);
            if (t == null) return;
            float size = Math.max(14f, getHeight() * (full ? 0.055f : 0.065f));
            fill.setTextSize(size); edge.setTextSize(size); edge.setStrokeWidth(size * 0.16f);
            String[] lines = t.split("\n");
            float y = getHeight() - size * 0.6f - (lines.length - 1) * size * 1.2f;
            for (String l : lines) {
                c.drawText(l, getWidth() / 2f, y, edge);
                c.drawText(l, getWidth() / 2f, y, fill);
                y += size * 1.2f;
            }
        }
    }

    // ---------------------------------------------------------------- scherm

    private class Panel extends View {
        private final CockpitView pt; private final Theme theme;
        private float s = 1f, H = 650f, scroll, downY, lastY; private boolean dragging;
        String folder = null, error = "";
        boolean subMode = false;
        private File candFor; private List<File> cand = new ArrayList<>();
        final RectF video = new RectF(436, 22, 1218, 462);
        private final RectF list = new RectF(18, 84, 402, 0);
        private float upX;
        private final List<Object[]> hits = new ArrayList<>();

        Panel(Context c, Theme t) { super(c); theme = t; setLayerType(LAYER_TYPE_SOFTWARE, null); pt = RetroUi.painter(c, t); }

        @Override protected void onSizeChanged(int w, int hh, int ow, int oh) { s = w / 1240f; H = hh / s; list.bottom = H - 18; layoutVideo(); }

        private void hit(RectF r, Runnable a) { hits.add(new Object[]{r, a}); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX() / s, y = e.getY() / s;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: downY = lastY = y; dragging = false; return true;
                case MotionEvent.ACTION_MOVE:
                    if (list.contains(x, downY) && Math.abs(y - downY) > 10) dragging = true;
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

        /** Sync-blok: verschuif de ondertitels in stappen; wordt per video bewaard. */
        private void drawSync(Canvas c, int key, int AMB) {
            float top = list.bottom + 12;
            RectF w = new RectF(18, top, 402, top + 70);
            pt.uiWindow(c, w, AMB);
            pt.uiText(c, "SYNC", w.left + 16, w.top + 26, 13, 0xFFD8B070, Paint.Align.LEFT);
            String v = String.format(Locale.US, "%+.1f S", subOffset / 1000f);
            pt.uiText(c, v, w.right - 16, w.top + 44, 26, AMB, Paint.Align.RIGHT);
            pt.uiText(c, subOffset > 0 ? Lang.t("TEKST LATER", "TEXT LATER") : subOffset < 0 ? Lang.t("TEKST EERDER", "TEXT EARLIER") : Lang.t("ORIGINEEL", "ORIGINAL"),
                    w.left + 16, w.top + 54, 12, 0xFFD8B070, Paint.Align.LEFT);
            pt.uiGlass(c, w);
            String[] lab = {"-5", "-0.5", "+0.5", "+5", "0"};
            long[] d = {-5000, -500, 500, 5000, 0};
            float gap = 7, bw = (384 - 4 * gap) / 5f, by = w.bottom + 12;
            for (int i = 0; i < 5; i++) {
                final long dd = d[i];
                RectF r = new RectF(18 + i * (bw + gap), by, 18 + i * (bw + gap) + bw, by + 90);
                pt.uiKey(c, r, lab[i], false, key, 20);
                hit(r, () -> shiftSubs(dd));
            }
        }

        @Override protected void onDraw(Canvas c) {
            hits.clear();
            c.save(); c.scale(s, s);
            int key = theme.key, AMB = pt.uiAmb(), RED = pt.uiRed();
            pt.uiBackground(c, 1240, H);
            pt.uiBezel(c, new RectF(0, 0, 420, H - 2));
            pt.uiBezel(c, new RectF(424, 0, 1240, H - 2));

            // ---- links: lijst ----
            pt.uiText(c, "VIDEO", 26, 52, 24, key, Paint.Align.LEFT);
            pt.uiLamp(c, 330, 44, 8, pt.uiGrn(), scannedRoot != null);
            pt.uiText(c, "USB", 344, 49, 13, scannedRoot != null ? pt.uiGrn() : 0xFF6C6C78, Paint.Align.LEFT);
            List<Object[]> rows = new ArrayList<>();
            list.bottom = subMode ? H - 18 - 196 : H - 18;
            if (subMode) {
                rows.add(new Object[]{"< " + Lang.t("TERUG", "BACK"), Lang.t("ONDERTITEL KIEZEN", "CHOOSE SUBTITLES"), (Runnable) () -> { subMode = false; scroll = 0; }, false});
                if (current == null) rows.add(new Object[]{Lang.t("EERST EEN VIDEO KIEZEN", "PICK A VIDEO FIRST"), "", (Runnable) () -> { }, false});
                else {
                    rows.add(new Object[]{Lang.t("GEEN ONDERTITEL", "NO SUBTITLES"), "", (Runnable) () -> chooseSubs(null), subs == null});
                    if (candFor != current) { candFor = current; cand = Subtitles.candidates(current); }
                    for (final File sf : cand)
                        rows.add(new Object[]{sf.getName(), String.format(Locale.US, "%.0f KB", sf.length() / 1024f), (Runnable) () -> chooseSubs(sf),
                                subs != null && subs.file.equals(sf)});
                    if (cand.isEmpty())
                        rows.add(new Object[]{Lang.t("GEEN .SRT / .VTT IN DEZE MAP", "NO .SRT / .VTT IN THIS FOLDER"), Lang.t("ZET HET BESTAND NAAST DE VIDEO", "PUT THE FILE NEXT TO THE VIDEO"), (Runnable) () -> { }, false});
                }
            } else if (folder == null) {
                TreeMap<String, Integer> folders = new TreeMap<>(MusicPlayer.SMART);
                for (File f : videos) { File p = f.getParentFile(); String n = p == null ? "/" : p.getName(); folders.merge(n, 1, Integer::sum); }
                String last = prefs.getString("last", null);
                if (last != null && new File(last).exists()) {
                    final File lf = new File(last);
                    rows.add(new Object[]{Lang.t("VERDER KIJKEN", "CONTINUE WATCHING"), lf.getName() + "  ·  " + MusicPlayer.time(prefs.getInt(key(lf), 0)), (Runnable) () -> play(lf), lf.equals(current)});
                }
                for (java.util.Map.Entry<String, Integer> e : folders.entrySet()) {
                    final String n = e.getKey();
                    rows.add(new Object[]{n, e.getValue() + (e.getValue() == 1 ? " VIDEO" : Lang.t(" VIDEO'S", " VIDEOS")), (Runnable) () -> { folder = n; scroll = 0; }, current != null && current.getParentFile() != null && current.getParentFile().getName().equals(n)});
                }
            } else {
                rows.add(new Object[]{"< " + Lang.t("TERUG", "BACK"), folder, (Runnable) () -> { folder = null; scroll = 0; }, false});
                for (final File f : videos) {
                    File p = f.getParentFile();
                    if (p == null || !p.getName().equals(folder)) continue;
                    int at = prefs.getInt(key(f), 0);
                    String sub = String.format(Locale.US, "%.0f MB", f.length() / 1048576f) + (at > 5000 ? Lang.t("  ·  VERDER OP ", "  ·  RESUME AT ") + MusicPlayer.time(at) : "");
                    rows.add(new Object[]{f.getName().replaceAll("\\.[^.]+$", ""), sub, (Runnable) () -> play(f), f.equals(current)});
                }
            }
            float rowH = 76;
            float maxScroll = Math.max(0, rows.size() * rowH - list.height());
            if (scroll > maxScroll) scroll = maxScroll;
            c.save(); c.clipRect(list);
            for (int i = 0; i < rows.size(); i++) {
                float y = list.top + i * rowH - scroll;
                if (y + rowH < list.top || y > list.bottom) continue;
                RectF r = new RectF(list.left, y + 2, list.right, y + rowH - 4);
                boolean on = (Boolean) rows.get(i)[3];
                if (on) pt.uiWindow(c, r, key);
                else { Paint q = new Paint(); q.setColor(0xFF121216); c.drawRoundRect(r, 5, 5, q); }
                pt.uiLamp(c, r.left + 20, r.centerY(), 7, key, on);
                c.save(); c.clipRect(r.left, r.top, r.right - 24, r.bottom);
                pt.uiText(c, String.valueOf(rows.get(i)[0]).toUpperCase(Locale.ROOT), r.left + 42, r.top + 31, 18, on ? key : 0xFFE8E8F0, Paint.Align.LEFT);
                pt.uiText(c, String.valueOf(rows.get(i)[1]).toUpperCase(Locale.ROOT), r.left + 42, r.top + 55, 12, 0xFFA8A8B4, Paint.Align.LEFT);
                c.restore();
                pt.uiText(c, ">", r.right - 10, r.centerY() + 7, 18, 0xFF8E8E9A, Paint.Align.RIGHT);
                if (on) pt.uiGlass(c, r);
                RectF hr = new RectF(r.left, Math.max(r.top, list.top), r.right, Math.min(r.bottom, list.bottom));
                if (hr.height() > 10) hit(hr, (Runnable) rows.get(i)[2]);
            }
            c.restore();
            if (subMode) drawSync(c, key, AMB);
            if (rows.isEmpty()) pt.uiText(c, scanning ? Lang.t("STICK WORDT GELEZEN...", "READING STICK...") : Lang.t("GEEN VIDEO'S GEVONDEN", "NO VIDEOS FOUND"),
                    list.left + 10, list.top + 40, 15, 0xFF8E8E9A, Paint.Align.LEFT);

            // ---- rechts: beeld ----
            pt.uiWindow(c, video, 0xFF2A2A30);
            Paint black = new Paint(); black.setColor(0xFF000000);
            c.drawRect(video.left + 8, video.top + 8, video.right - 8, video.bottom - 8, black);
            if (blocked) {
                pt.uiLamp(c, video.centerX(), video.centerY() - 40, 14, RED, (System.currentTimeMillis() / 500) % 2 == 0);
                pt.uiText(c, Lang.t("BEELD UIT TIJDENS HET RIJDEN", "PICTURE OFF WHILE DRIVING"), video.centerX(), video.centerY() + 10, 20, RED, Paint.Align.CENTER);
                pt.uiText(c, Lang.t("HET GELUID LOOPT DOOR  ·  BEELD KOMT TERUG ALS JE STILSTAAT", "SOUND CONTINUES  ·  PICTURE RETURNS WHEN STOPPED"), video.centerX(), video.centerY() + 40, 12, 0xFFA8A8B4, Paint.Align.CENTER);
            } else if (current == null) {
                pt.uiText(c, Lang.t("KIES LINKS EEN VIDEO", "PICK A VIDEO ON THE LEFT"), video.centerX(), video.centerY() + 8, 18, 0xFF8E8E9A, Paint.Align.CENTER);
            } else if (!error.isEmpty()) {
                pt.uiText(c, error, video.centerX(), video.centerY() + 8, 16, RED, Paint.Align.CENTER);
            }

            // ---- titel + voortgang ----
            float y0 = video.bottom + 14;
            RectF info = new RectF(video.left, y0, video.right, y0 + 84);
            pt.uiWindow(c, info, AMB);
            c.save(); c.clipRect(info.left + 8, info.top, info.right - 190, info.bottom);
            pt.uiText(c, current == null ? "--" : current.getName().replaceAll("\\.[^.]+$", "").toUpperCase(Locale.ROOT), info.left + 18, info.top + 32, 19, AMB, Paint.Align.LEFT);
            c.restore();
            int d = dur(), p = pos();
            pt.uiSegRight(c, MusicPlayer.time(p), info.right - 18, info.top + 10, 26, AMB);
            final RectF pb = new RectF(info.left + 18, info.bottom - 32, info.right - 190, info.bottom - 14);
            pt.uiBar(c, pb.left, pb.top, pb.right, pb.height(), 30, d > 0 ? Math.round(30f * p / d) : 0, 2);
            pt.uiText(c, "/ " + MusicPlayer.time(d), info.right - 18, info.bottom - 14, 13, 0xFFD8B070, Paint.Align.RIGHT);
            pt.uiGlass(c, info);
            hit(new RectF(pb.left - 6, pb.top - 16, pb.right + 6, pb.bottom + 12), () -> seekFrac((upX - pb.left) / pb.width()));

            // ---- knoppen ----
            float by = info.bottom + 14, bh = Math.min(70, H - 18 - by);
            float gap = 10, bw = (video.width() - 5 * gap) / 6f;
            String[] lab = {"-10 S", playing() ? Lang.t("PAUZE", "PAUSE") : Lang.t("SPEEL", "PLAY"), "+30 S",
                    Lang.t("GROOT", "FULL"), Lang.t("ONDERTITEL", "SUBTITLES"), Lang.t("SLUITEN", "CLOSE")};
            Runnable[] act = {() -> seekBy(-10_000), VideoActivity.this::togglePlay, () -> seekBy(30_000),
                    () -> { if (current != null && !blocked) { full = true; layoutVideo(); } },
                    () -> { subMode = !subMode; scroll = 0; candFor = null; },
                    VideoActivity.this::finish};
            for (int i = 0; i < 6; i++) {
                RectF r = new RectF(video.left + i * (bw + gap), by, video.left + i * (bw + gap) + bw, by + bh);
                boolean on = (i == 1 && playing()) || i == 5 || (i == 4 && (subs != null || subMode));
                pt.uiButton(c, r, lab[i], on, key, i == 4 ? 11 : 13);
                hit(r, act[i]);
            }
            c.restore();
            c.save(); c.scale(s, s); pt.uiScanlines(c, 1240, H); c.restore();
        }
    }
}

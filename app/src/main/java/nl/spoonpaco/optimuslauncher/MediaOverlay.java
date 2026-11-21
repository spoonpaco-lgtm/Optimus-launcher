package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Zwevende mediaspeler over Android Auto (USB-muziek): hoes, titel, artiest, album, voortgang,
 * knoppen vorige / speel-pauze / volgende. Tik ergens anders = groot muziekscherm. Slepen = verplaatsen.
 */
final class MediaOverlay {
    static final float W = 362, H = 335;           // ontwerp-eenheden: ~374x346 px = de Spotify-kaart in AA
    private final Context ctx;
    private WindowManager wm;
    private Card card;
    private WindowManager.LayoutParams lp;
    private long lastPlaying;
    private MusicPanel full;                         // uitgeklapte speler (Winamp-stijl)
    private WindowManager.LayoutParams flp;
    private final MusicPlayer.Listener fullListener = () -> { if (full != null) full.invalidate(); };

    MediaOverlay(Context c) { ctx = c.getApplicationContext(); }

    /** Elke seconde aanroepen vanuit de service. */
    void update(boolean allowed) {
        MusicPlayer mp = MusicPlayer.get(ctx);
        long now = System.currentTimeMillis();
        if (mp.isPlaying()) lastPlaying = now;
        boolean want = allowed && android.provider.Settings.canDrawOverlays(ctx)
                && !MainActivity.visible && !Remote.ownScreenVisible()
                && mp.current() != null && now - lastPlaying < 5 * 60_000L;
        if (!want) { remove(); collapse(); return; }
        if (full != null) { remove(); return; }       // uitgeklapt: alleen de grote speler
        if (card == null) add();
        if (card != null) {
            String th = ctx.getSharedPreferences("optimus", Context.MODE_PRIVATE).getString("theme", "led");
            card.theme(Theme.byId(th));
            card.invalidate();
        }
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences("optimus", Context.MODE_PRIVATE); }

    private void add() {
        if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        card = new Card(ctx);
        float u = RetroUi.unit(ctx);
        int w = Math.round(W * u), h = Math.round(H * u);
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // standaard: precies over de mediakaart rechts in Android Auto
        lp.x = prefs().getInt("mvX2", Math.round(dm.widthPixels * 0.703f));
        lp.y = prefs().getInt("mvY2", Math.round(dm.heightPixels * 0.36f));
        try { wm.addView(card, lp); } catch (Exception e) { card = null; }
    }

    /** Tik op de mini-speler: de volledige speler zwevend tonen. */
    private void expand() {
        if (full != null || wm == null) return;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        String th = prefs().getString("theme", "led");
        full = new MusicPanel(ctx, Theme.byId(th), new MusicPanel.Host() {
            @Override public void close() { collapse(); }
            @Override public void requestPerm() {
                Intent in = new Intent(ctx, MusicActivity.class);
                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(in);
                collapse();
            }
            @Override public void drag(float dx, float dy) {
                flp.x += Math.round(dx); flp.y += Math.round(dy);
                try { wm.updateViewLayout(full, flp); } catch (Exception ignored) { }
            }
            @Override public void dragEnd() { prefs().edit().putInt("mfX", flp.x).putInt("mfY", flp.y).apply(); }
        }, true);
        int w = Math.round(dm.widthPixels * 0.82f), h = Math.round(w * 9f / 16f);
        flp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        flp.gravity = Gravity.TOP | Gravity.START;
        flp.x = prefs().getInt("mfX", (dm.widthPixels - w) / 2);
        flp.y = prefs().getInt("mfY", (dm.heightPixels - h) / 2);
        try { wm.addView(full, flp); } catch (Exception e) { full = null; return; }
        MusicPlayer.get(ctx).addListener(fullListener);
        full.tick();
        remove();
    }

    void collapse() {
        if (full == null) return;
        MusicPlayer.get(ctx).removeListener(fullListener);
        full.removeCallbacks(full.ticker);
        try { wm.removeView(full); } catch (Exception ignored) { }
        full = null;
    }

    void remove() {
        if (card != null && wm != null) { try { wm.removeView(card); } catch (Exception ignored) { } }
        card = null;
    }

    private final class Card extends View {
        private final CockpitView pt;
        private Theme theme;
        private final float u;
        private final RectF[] keys = {new RectF(), new RectF(), new RectF()};
        private int pressed = -1; private long pressedAt;
        private float dX, dY; private int sX, sY; private boolean moved;

        Card(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            pt = new CockpitView(c, null);
            u = RetroUi.unit(c);
        }

        void theme(Theme t) { if (t != theme) { theme = t; pt.setTheme(t); } }

        @Override protected void onDraw(Canvas c) {
            MusicPlayer mp = MusicPlayer.get(getContext());
            MusicPlayer.Track t = mp.current();
            int key = theme == null ? 0xFFFFB21A : theme.key, AMB = pt.uiAmb();
            c.save(); c.scale(u, u);
            pt.uiBezel(c, new RectF(2, 2, W - 2, H - 2));

            // hoes linksboven
            RectF ar = new RectF(14, 14, 128, 128);
            pt.uiWindow(c, ar, key);
            Bitmap art = mp.art();
            if (art != null) c.drawBitmap(art, null, new RectF(ar.left + 5, ar.top + 5, ar.right - 5, ar.bottom - 5), new Paint(Paint.FILTER_BITMAP_FLAG));
            else pt.uiText(c, "USB", ar.centerX(), ar.centerY() + 8, 22, key & 0x88FFFFFF, Paint.Align.CENTER);
            pt.uiGlass(c, ar);

            // rechtsboven: bron + tijd
            int dur = mp.durationMs(), pos = mp.positionMs();
            pt.uiLamp(c, 152, 34, 7, pt.uiGrn(), mp.isPlaying());
            pt.uiText(c, "USB", 166, 39, 13, pt.uiGrn(), Paint.Align.LEFT);
            pt.uiSegRight(c, MusicPlayer.time(pos), W - 16, 58, 34, AMB);
            pt.uiText(c, "/ " + MusicPlayer.time(dur), W - 16, 118, 13, 0xFFD8B070, Paint.Align.RIGHT);

            // tekst over de volle breedte
            RectF tw = new RectF(14, 138, W - 14, 236);
            pt.uiWindow(c, tw, AMB);
            c.save(); c.clipRect(tw.left + 6, tw.top, tw.right - 6, tw.bottom);
            if (t != null) {
                pt.uiText(c, t.label().toUpperCase(Locale.ROOT), tw.left + 12, tw.top + 32, 22, AMB, Paint.Align.LEFT);
                pt.uiText(c, (t.artist.isEmpty() ? "-" : t.artist).toUpperCase(Locale.ROOT), tw.left + 12, tw.top + 60, 17, 0xFFF0CC88, Paint.Align.LEFT);
                pt.uiText(c, t.album.toUpperCase(Locale.ROOT), tw.left + 12, tw.top + 84, 13, 0xFFD8B070, Paint.Align.LEFT);
            }
            c.restore();
            pt.uiGlass(c, tw);

            int lit = dur > 0 ? Math.round(24f * pos / dur) : 0;
            pt.uiBar(c, 16, 242, W - 16, 14, 24, lit, 2);

            float kw = (W - 28 - 16) / 3f;
            boolean fresh = System.currentTimeMillis() - pressedAt < 220;
            for (int i = 0; i < 3; i++) {
                keys[i].set(14 + i * (kw + 8), 266, 14 + i * (kw + 8) + kw, H - 14);
                pt.uiMusicKey(c, keys[i], i, fresh && pressed == i, mp.isPlaying());
            }
            if (fresh) postInvalidateDelayed(240);
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX = e.getRawX(); dY = e.getRawY(); sX = lp.x; sY = lp.y; moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - dX), dy = (int) (e.getRawY() - dY);
                    if (Math.abs(dx) > 14 || Math.abs(dy) > 14) moved = true;
                    if (moved) { lp.x = sX + dx; lp.y = sY + dy; try { wm.updateViewLayout(this, lp); } catch (Exception ignored) { } }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (moved) { prefs().edit().putInt("mvX2", lp.x).putInt("mvY2", lp.y).apply(); return true; }
                    float x = e.getX() / u, y = e.getY() / u;
                    MusicPlayer mp = MusicPlayer.get(getContext());
                    if (y > 232 && y < 262 && x > 10 && x < W - 10) {   // voortgangsbalk: spoelen
                        mp.seekFraction((x - 16) / (W - 32));
                        invalidate();
                        return true;
                    }
                    for (int i = 0; i < 3; i++) {
                        RectF k = keys[i];
                        if (x > k.left - 4 && x < k.right + 4 && y > k.top - 8 && y < k.bottom + 8) {
                            pressed = i; pressedAt = System.currentTimeMillis();
                            if (i == 0) mp.previous(); else if (i == 1) mp.toggle(); else mp.next();
                            invalidate();
                            return true;
                        }
                    }
                    expand();
                    return true;
                }
            }
            return true;
        }
    }
}

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
    static final float W = 400, H = 214;           // ontwerp-eenheden (1240 over de schermbreedte)
    private final Context ctx;
    private WindowManager wm;
    private Card card;
    private WindowManager.LayoutParams lp;
    private long lastPlaying;

    MediaOverlay(Context c) { ctx = c.getApplicationContext(); }

    /** Elke seconde aanroepen vanuit de service. */
    void update(boolean allowed) {
        MusicPlayer mp = MusicPlayer.get(ctx);
        long now = System.currentTimeMillis();
        if (mp.isPlaying()) lastPlaying = now;
        boolean want = allowed && android.provider.Settings.canDrawOverlays(ctx)
                && !MainActivity.visible && !Remote.ownScreenVisible()
                && mp.current() != null && now - lastPlaying < 5 * 60_000L;
        if (!want) { remove(); return; }
        if (card == null) add();
        if (card != null) {
            String th = ctx.getSharedPreferences("optimus", Context.MODE_PRIVATE).getString("theme", "led");
            card.theme(Theme.byId(th));
            card.invalidate();
        }
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences("optimus", Context.MODE_PRIVATE); }

    private void add() {
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        card = new Card(ctx);
        float u = RetroUi.unit(ctx);
        int w = Math.round(W * u), h = Math.round(H * u);
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs().getInt("mvX", dm.widthPixels - w - 16);
        lp.y = prefs().getInt("mvY", dm.heightPixels - h - 70);
        try { wm.addView(card, lp); } catch (Exception e) { card = null; }
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
            RectF all = new RectF(2, 2, W - 2, H - 2);
            pt.uiBezel(c, all);

            // hoes
            RectF ar = new RectF(14, 14, 140, 140);
            pt.uiWindow(c, ar, key);
            Bitmap art = mp.art();
            if (art != null) c.drawBitmap(art, null, new RectF(ar.left + 6, ar.top + 6, ar.right - 6, ar.bottom - 6), new Paint(Paint.FILTER_BITMAP_FLAG));
            else pt.uiText(c, "USB", ar.centerX(), ar.centerY() + 8, 22, key & 0x88FFFFFF, Paint.Align.CENTER);
            pt.uiGlass(c, ar);

            // tekst
            RectF tw = new RectF(150, 14, W - 14, 140);
            pt.uiWindow(c, tw, AMB);
            c.save(); c.clipRect(tw.left + 6, tw.top, tw.right - 6, tw.bottom);
            if (t != null) {
                pt.uiText(c, t.label().toUpperCase(Locale.ROOT), tw.left + 12, tw.top + 30, 21, AMB, Paint.Align.LEFT);
                pt.uiText(c, (t.artist.isEmpty() ? "-" : t.artist).toUpperCase(Locale.ROOT), tw.left + 12, tw.top + 58, 16, 0xFFF0CC88, Paint.Align.LEFT);
                pt.uiText(c, t.album.toUpperCase(Locale.ROOT), tw.left + 12, tw.top + 83, 13, 0xFFD8B070, Paint.Align.LEFT);
            }
            int dur = mp.durationMs(), pos = mp.positionMs();
            pt.uiText(c, MusicPlayer.time(pos) + " / " + MusicPlayer.time(dur), tw.left + 12, tw.bottom - 12, 12, 0xFFC8A060, Paint.Align.LEFT);
            c.restore();
            pt.uiLamp(c, tw.right - 18, tw.bottom - 17, 6, pt.uiGrn(), mp.isPlaying());
            pt.uiGlass(c, tw);

            // voortgang
            int lit = dur > 0 ? Math.round(26f * pos / dur) : 0;
            pt.uiBar(c, 16, 148, W - 16, 7, 26, lit, 2);

            // knoppen
            float kw = (W - 28 - 16) / 3f;
            boolean fresh = System.currentTimeMillis() - pressedAt < 220;
            for (int i = 0; i < 3; i++) {
                keys[i].set(14 + i * (kw + 8), 164, 14 + i * (kw + 8) + kw, H - 12);
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
                    if (moved) { prefs().edit().putInt("mvX", lp.x).putInt("mvY", lp.y).apply(); return true; }
                    float x = e.getX() / u, y = e.getY() / u;
                    MusicPlayer mp = MusicPlayer.get(getContext());
                    for (int i = 0; i < 3; i++) {
                        RectF k = keys[i];
                        if (x > k.left - 4 && x < k.right + 4 && y > k.top - 8 && y < k.bottom + 8) {
                            pressed = i; pressedAt = System.currentTimeMillis();
                            if (i == 0) mp.previous(); else if (i == 1) mp.toggle(); else mp.next();
                            invalidate();
                            return true;
                        }
                    }
                    Intent in = new Intent(getContext(), MusicActivity.class);
                    in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(in);
                    return true;
                }
            }
            return true;
        }
    }
}

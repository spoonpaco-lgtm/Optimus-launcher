package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * KITT-scanner: rij segmenten onder de bovenbalk (op de plek van de pinstripe).
 * - loopt heen en weer zolang de versterker aan staat (er speelt geluid),
 * - één sweep bij het openen van de launcher,
 * - pulseert zacht in de waarschuwingskleur bij een accu-/onderhoudswaarschuwing.
 * Tekent alleen zijn eigen strook, zodat de rest van de cockpit niet 25x per seconde opnieuw hoeft.
 */
public class ScannerView extends View {
    private static final int N = 26;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] lvl = new float[N];
    private int col = 0xFFFF2D23;
    private boolean leds;
    private float head = 0; private int dir = 1;
    private int sweepsLeft = 0;
    /** Van buiten gezet: geluid speelt / waarschuwing (0 geen, 1 geel, 2 rood). */
    public volatile boolean playing;
    public volatile int warn;
    private boolean running;
    private float s = 1f;
    private final RectF strip = new RectF();

    public ScannerView(Context c, AttributeSet a) { super(c, a); }

    public void setTheme(Theme t) { col = t.speed; leds = t.leds; invalidate(); }

    /** Eén keer heen en terug (opstarten). */
    public void sweepOnce() { sweepsLeft = 2; head = 0; dir = 1; kick(); }

    /** Aanroepen na een statuswijziging (1x per seconde vanuit de launcher). */
    public void update(boolean playing, int warn) {
        this.playing = playing; this.warn = warn;
        if (playing || warn > 0 || sweepsLeft > 0) kick(); else invalidate();
    }

    private void kick() { if (!running) { running = true; postOnAnimation(tick); } }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean animate = playing || sweepsLeft > 0;
            if (animate) {
                head += dir * 0.9f;
                if (head >= N - 1) { head = N - 1; dir = -1; if (sweepsLeft > 0) sweepsLeft--; }
                if (head <= 0) { head = 0; dir = 1; if (sweepsLeft > 0) sweepsLeft--; }
            }
            for (int i = 0; i < N; i++) lvl[i] *= 0.72f;      // uitdovende staart
            if (animate) {
                int h = Math.round(head);
                if (h >= 0 && h < N) lvl[h] = 1f;
            }
            float max = 0; for (float v : lvl) max = Math.max(max, v);
            invalidate((int) strip.left, (int) strip.top - 20, (int) strip.right, (int) strip.bottom + 20);
            if (animate || warn > 0 || max > 0.02f) postDelayed(this, 40);
            else running = false;
        }
    };

    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis != VISIBLE) { removeCallbacks(tick); running = false; }
        else if (playing || warn > 0 || sweepsLeft > 0) kick();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        s = w / 1240f;
        strip.set(0, 62 * s, w, 81 * s);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth();
        // gleuf (dekt de oude pinstripe af)
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF070709);
        c.drawRoundRect(strip.left, strip.top, strip.right, strip.bottom, 5 * s, 5 * s, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, s));
        p.setColor(0xFF2C2C35);
        c.drawRoundRect(strip.left, strip.top, strip.right, strip.bottom, 5 * s, 5 * s, p);
        p.setStyle(Paint.Style.FILL);

        // waarschuwing: zacht pulseren van de hele balk
        float pulse = 0f; int base = col;
        if (warn > 0 && !playing) {
            base = warn == 2 ? CockpitView.WR : CockpitView.WA;
            pulse = 0.18f + 0.18f * (float) Math.sin(System.currentTimeMillis() / 400.0);
        }
        float pad = 6 * s, gap = 5 * s;
        float segW = (w - 2 * pad - gap * (N - 1)) / N;
        float y0 = strip.top + 3 * s, y1 = strip.bottom - 3 * s;
        for (int i = 0; i < N; i++) {
            float L = Math.max(lvl[i], pulse);
            float x = pad + i * (segW + gap);
            int segCol = L > lvl[i] ? base : col;
            if (L > 0.05f) {
                // gloed: twee lagen halfdoorzichtig, groter dan het segment
                p.setColor(withAlpha(segCol, (int) (60 * L)));
                c.drawRoundRect(x - 6 * s, y0 - 6 * s, x + segW + 6 * s, y1 + 6 * s, 8 * s, 8 * s, p);
                p.setColor(withAlpha(segCol, (int) (90 * L)));
                c.drawRoundRect(x - 3 * s, y0 - 3 * s, x + segW + 3 * s, y1 + 3 * s, 6 * s, 6 * s, p);
            }
            p.setColor(mix(dim(segCol), segCol, L));
            if (leds) {
                float r = (y1 - y0) / 2f;
                for (float cx = x + r; cx <= x + segW - r + 0.1f; cx += 2.6f * r) c.drawCircle(cx, (y0 + y1) / 2f, r, p);
            } else {
                c.drawRoundRect(x, y0, x + segW, y1, 3 * s, 3 * s, p);
                p.setColor(withAlpha(0xFFFFFFFF, (int) (40 + 80 * L)));
                c.drawRect(x + 3 * s, y0 + 1.5f * s, x + segW - 3 * s, y0 + 2.5f * s, p);
            }
        }
    }

    private static int withAlpha(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0x00FFFFFF); }

    private static int dim(int c) {
        return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * 0.16f) << 16) | ((int) (((c >> 8) & 0xFF) * 0.16f) << 8) | (int) ((c & 0xFF) * 0.16f);
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}

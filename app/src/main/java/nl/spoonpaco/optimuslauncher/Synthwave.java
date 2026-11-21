package nl.spoonpaco.optimuslauncher;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/** Eigen "hoes" als er geen albumplaatje is: zon boven een raster dat naar je toe rijdt. */
final class Synthwave {
    private Synthwave() { }
    private static final Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static float phase;      // positie van het raster (0..1 per lijn)
    private static long lastT;

    /** Raster schuift mee met de echte snelheid (GPS): 50 km/h = één lijn per 0,9 s; stilstaan = stil. */
    private static float gridPhase(long t) {
        float dt = lastT == 0 ? 0 : Math.min(500, t - lastT);
        lastT = t;
        boolean fresh = System.currentTimeMillis() - TrackerService.lastFixTime < 5000;
        float v = fresh ? Math.max(0f, Math.min(160f, TrackerService.speedKmh)) : 0f;
        phase = (phase + dt * (v / 50f) / 900f) % 1f;
        return phase;
    }

    /** t = tijd in ms; rijden = raster beweegt (anders stil). */
    static void draw(Canvas c, RectF r, int key, long t, boolean moving) {
        c.save();
        c.clipRect(r);
        float hor = r.top + r.height() * 0.5f;
        q.setStyle(Paint.Style.FILL);
        q.setShader(new LinearGradient(0, r.top, 0, hor, 0xFF14061E, 0xFF5A1430, Shader.TileMode.CLAMP));
        c.drawRect(r.left, r.top, r.right, hor, q);
        q.setShader(null);
        // zon met strepen die langzaam omhoog schuiven
        float R = r.width() * 0.22f, cx = r.centerX();
        q.setShader(new LinearGradient(0, hor - R, 0, hor, 0xFFFFD060, key, Shader.TileMode.CLAMP));
        c.drawCircle(cx, hor, R, q);
        q.setShader(null);
        q.setColor(0xFF14061E);
        float band = R * 0.16f, ph = moving ? (t % 2400) / 2400f : 0f;
        for (int i = 0; i < 6; i++) {
            float y = hor - (i + ph) * band * 1.15f;
            float hgt = Math.max(1f, band * 0.45f * (1f - i / 6f));
            c.drawRect(r.left, y - hgt, r.right, y, q);
        }
        // grond
        q.setColor(0xFF0C0614);
        c.drawRect(r.left, hor, r.right, r.bottom, q);
        q.setColor(0xFFD23CC8);
        q.setStrokeWidth(Math.max(1.5f, r.width() / 140f));
        // lijnen naar het verdwijnpunt
        for (int i = 0; i <= 10; i++) c.drawLine(cx, hor, r.left - r.width() * 0.4f + i * r.width() * 1.8f / 10f, r.bottom, q);
        // dwarslijnen die naar je toe komen (perspectief)
        float f = gridPhase(t);
        for (int i = 0; i < 9; i++) {
            float k = (i + f) / 9f;                       // 0 = horizon, 1 = onderrand
            float y = hor + (r.bottom - hor) * k * k;
            q.setAlpha(Math.round(90 + 165 * k));
            c.drawLine(r.left, y, r.right, y, q);
        }
        q.setAlpha(255);
        // gloed op de horizon
        q.setShader(new LinearGradient(0, hor - 6, 0, hor + 10, 0x00D23CC8, 0x88D23CC8, Shader.TileMode.MIRROR));
        c.drawRect(r.left, hor - 6, r.right, hor + 10, q);
        q.setShader(null);
        c.restore();
    }
}

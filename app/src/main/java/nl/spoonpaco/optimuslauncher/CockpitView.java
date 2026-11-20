package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Retro-80s LED-cockpit (ontwerp "LED-cockpit schets"): 7-segmentcijfers, LED-balken,
 * lampjes, kompasband. Alles getekend; statische delen (ribbels, panelen, schroeven,
 * scanlines) worden één keer in een bitmap gezet, de rest 1x per seconde.
 * Ontwerpmaat: 1240 breed; alles schaalt mee.
 */
public class CockpitView extends View {

    public interface Listener {
        void onSpeedTap();
        void onClockLongPress();
        void onTankTap();
    }

    // ---- live waarden (gezet door MainActivity) ----
    public long now;
    public int speed = -1;            // -1 = geen GPS
    public int sats;
    public float heading = -1f;
    public float tripKm, tripAvg, tripMax, tripL, tripCost = -1f;
    public long tripMinutes;
    public float l100 = -1f;
    public int rangeKm = -1;
    public float tankL = -1f;
    public float volts = -1f;
    public boolean lContact, lMotor, lLicht, lTrips, lAuto, lLog;
    /** Accubewaker: 0 goed, 1 let op (geel), 2 slecht (rood). */
    public int accuWarn = 0;
    /** Kleur voor de hint onderaan een kaart (0 = standaard gedimd). */
    public int hintColor = 0;

    public Listener listener;

    // vaste waarschuwingskleuren (balken, lampjes)
    static final int WR = 0xFFFF2D23, WA = 0xFFFFB21A, WG = 0xFF3BE07A,
            DIM = 0xFF6C6C78, LBL = 0xFF8E8E9A, TXT = 0xFFC9C9D2;

    // themakleuren (zie Theme): RED = snelheid, AMB = klok/verbruik/naald, GRN = rit, BLU = accu, YEL = tank
    int RED = WR, AMB = WA, GRN = WG, BLU = 0xFF2F8CFF, YEL = 0xFFFFD23A;
    int tRed = 0xFF2B0505, eRed = 0xFF4A0D0D, tAmb = 0xFF241600, eAmb = 0xFF4A3000;
    int rib = 0x660E0E12, bgTop = 0xFF17171D, north = WR;
    /** Old-skool: alles van lampjes (dot-matrix cijfers/tekst, lampjesrijen). */
    boolean leds = false;

    public void setTheme(Theme t) {
        RED = t.speed; AMB = t.accent; GRN = t.trip; BLU = t.accu; YEL = t.tank;
        tRed = t.speedTint; eRed = t.speedEdge; tAmb = t.accentTint; eAmb = t.accentEdge;
        rib = t.rib; bgTop = t.bgTop; north = t.north; leds = t.leds;
        bg = null;
        invalidate();
    }

    private float s = 1f;              // schaal
    private Bitmap bg;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface label = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.US);
    

    // tikgebieden (design-coördinaten)
    private final RectF rSpeed = new RectF(), rClock = new RectF(), rTank = new RectF();
    private final Runnable longPress = () -> { if (listener != null) listener.onClockLongPress(); downInClock = false; };
    private boolean downInClock;

    // layout (design units)
    private float H;   // hoogte in design units
    private final RectF pan1 = new RectF(), pan2 = new RectF(), pan3 = new RectF();

    public CockpitView(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // nodig voor gloed (BlurMaskFilter)
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        s = w / 1240f;
        H = h / s;
        float top = 84f, gap = 16f, avail = 1240f - 2 * gap;
        float w1 = avail * 1.2f / 3f, w2 = avail * 0.8f / 3f, w3 = avail - w1 - w2;
        pan1.set(0, top, w1, H - 2);
        pan2.set(w1 + gap, top, w1 + gap + w2, H - 2);
        pan3.set(w1 + w2 + 2 * gap, top, 1240f, H - 2);
        rClock.set(0, 0, 300, 62);
        rSpeed.set(pan1);
        rTank.set(pan3.left, pan3.bottom - 90, pan3.right, pan3.bottom);
        bg = null;
    }

    // ---------------------------------------------------------------- tekenen

    @Override
    protected void onDraw(Canvas c) {
        if (bg == null) buildBackground();
        c.drawBitmap(bg, 0, 0, null);
        c.save();
        c.scale(s, s);
        drawTop(c);
        drawSpeedPanel(c);
        drawTripPanel(c);
        drawFuelPanel(c);
        c.restore();
        drawOverlay(c);
    }

    private void buildBackground() {
        bg = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bg);
        c.drawColor(0xFF040405);
        // radiale gloed bovenin + verticale ribbels
        p.setShader(new RadialGradient(getWidth() / 2f, 0, getWidth() * 0.7f, bgTop, 0xFF040405, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), p);
        p.setShader(null);
        p.setColor(rib);
        for (float x = 0; x < getWidth(); x += 26 * s) c.drawRect(x, 0, x + 2 * s, getHeight(), p);
        c.save();
        c.scale(s, s);
        // pinstripe
        p.setShader(new LinearGradient(0, 0, 1240, 0, new int[]{RED & 0x00FFFFFF, RED, AMB, RED, RED & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        glow.setShader(p.getShader());
        glow.setMaskFilter(new BlurMaskFilter(8, BlurMaskFilter.Blur.NORMAL));
        if (leds) {
            for (float px = 4; px < 1240; px += 9) { c.drawCircle(px, 71.5f, 2.2f, glow); c.drawCircle(px, 71.5f, 1.8f, p); }
        } else {
            c.drawRect(0, 70, 1240, 73, glow);
            c.drawRect(0, 70, 1240, 73, p);
        }
        p.setShader(null); glow.setShader(null); glow.setMaskFilter(null);
        for (RectF r : new RectF[]{pan1, pan2, pan3}) bezel(c, r);
        // vaste vensters/vakken
        window(c, new RectF(0, 2, 170, 60), tAmb, eAmb);
        compassFrame(c);
        window(c, new RectF(1080, 2, 1240, 60), tAmb, eAmb);
        sunken(c, new RectF(pan2.left + 18, pan2.top + 18, pan2.right - 18, pan2.top + 150));
        c.restore();
        // scanlines + vignet over alles
        p.setColor(0x06FFFFFF);
        for (float y = 0; y < getHeight(); y += 4 * s) c.drawRect(0, y, getWidth(), y + s, p);
        p.setShader(new RadialGradient(getWidth() / 2f, getHeight() * 0.45f, getWidth() * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x99000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), getHeight(), p);
        p.setShader(null);
    }

    private void bezel(Canvas c, RectF r) {
        // slagschaduw
        glow.setColor(0xD9000000);
        glow.setMaskFilter(new BlurMaskFilter(18, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(r.left + 2, r.top + 14, r.right - 2, r.bottom + 10, 6, 6, glow);
        glow.setMaskFilter(null);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF17171C, 0xFF0A0A0D, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 6, 6, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0xFF2C2C35);
        c.drawRoundRect(r, 6, 6, p);
        p.setColor(0x1AFFFFFF);
        c.drawLine(r.left + 6, r.top + 1, r.right - 6, r.top + 1, p);
        p.setStyle(Paint.Style.FILL);
        screw(c, r.left + 11, r.top + 11); screw(c, r.right - 11, r.top + 11);
        screw(c, r.left + 11, r.bottom - 11); screw(c, r.right - 11, r.bottom - 11);
    }

    private void screw(Canvas c, float x, float y) {
        p.setShader(new RadialGradient(x - 1.5f, y - 1.5f, 5.5f, 0xFF8A8A94, 0xFF2A2A30, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, 4.5f, p);
        p.setShader(null);
        p.setColor(0xFF111111);
        p.setStrokeWidth(1);
        c.drawLine(x - 3.2f, y - 2.2f, x + 3.2f, y + 2.2f, p);
    }

    private void sunken(Canvas c, RectF r) {
        p.setColor(0xFF070709);
        c.drawRoundRect(r, 4, 4, p);
        p.setShader(new LinearGradient(0, r.top, 0, r.top + 14, 0xCC000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 4, 4, p);
        p.setShader(null);
    }

    /** Verzonken displayvenster met getinte gloed, metalen rand, scanlines en glasreflectie. */
    private void window(Canvas c, RectF r, int tint, int edge) {
        if (leds) return; // Old-skool: lampjes zitten los op het paneel, geen display/scanlines
        glow.setColor(0xB3000000);
        glow.setMaskFilter(new BlurMaskFilter(8, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(r.left, r.top + 4, r.right, r.bottom + 6, 4, 4, glow);
        glow.setMaskFilter(null);
        p.setShader(new RadialGradient(r.centerX(), r.top + r.height() * 0.4f, Math.max(r.width(), r.height()) * 0.7f,
                tint, 0xFF070303, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 4, 4, p);
        p.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(28, r.height() / 2), 0xE6000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 4, 4, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0xFF44444C);
        c.drawRoundRect(r, 4, 4, p);
        p.setColor(edge);
        c.drawRoundRect(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2, 3, 3, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Scanlines + glasreflectie óver de cijfers in een venster. */
    private void glass(Canvas c, RectF r) {
        if (leds) return;
        p.setColor(0x4D000000);
        for (float y = r.top + 3; y < r.bottom - 2; y += 3) c.drawRect(r.left + 3, y, r.right - 3, y + 1, p);
        p.setShader(new LinearGradient(0, r.top + 4, 0, r.top + 4 + r.height() * 0.36f, 0x17FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r.left + 6, r.top + 4, r.right - 6, r.top + 4 + r.height() * 0.36f, 3, 3, p);
        p.setShader(null);
    }

    private void compassFrame(Canvas c) {
        RectF r = new RectF(310, 2, 1064, 60);
        glow.setColor(0xB3000000);
        glow.setMaskFilter(new BlurMaskFilter(8, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(r.left, r.top + 4, r.right, r.bottom + 6, 4, 4, glow);
        glow.setMaskFilter(null);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF0D0D11, 0xFF050507, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 4, 4, p);
        p.setShader(null);
    }

    private void drawTop(Canvas c) {
        RectF clockR = new RectF(0, 2, 170, 60);
        drawSegCentered(c, clockFmt.format(new Date(now)), clockR, 34, AMB);
        glass(c, clockR);
        text(c, new SimpleDateFormat("EEE d MMM", Lang.locale()).format(new Date(now)).replace(".", "").toUpperCase(Lang.locale()), 184, 26, 12, TXT);
        if (leds) { text(c, "OPTIMUS", 184, 50, 13, 0xFFECEFF4); } else {
        p.setShader(new LinearGradient(0, 36, 0, 50, 0xFFFFFFFF, 0xFF55555E, Shader.TileMode.CLAMP));
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextSize(14);
        p.setLetterSpacing(0.3f);
        c.drawText("OPTIMUS", 184, 50, p);
        p.setShader(null);
        p.setLetterSpacing(0f);
        }

        // kompas
        RectF r = new RectF(312, 4, 1062, 58);
        c.save();
        c.clipRect(r);
        float hd = heading < 0 ? 0 : heading;
        float pxPerDeg = r.width() / 150f;
        String[] names = Lang.en ? new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"} : new String[]{"N", "NO", "O", "ZO", "Z", "ZW", "W", "NW"};
        int start = (int) Math.floor((hd - 80) / 5) * 5;
        if (leds) {
            for (int d = start; d <= hd + 80; d += 5) {
                float x = r.centerX() + (d - hd) * pxPerDeg;
                int a = ((d % 360) + 360) % 360;
                if (a % 45 == 0) {
                    p.setTextAlign(Paint.Align.CENTER);
                    text(c, names[a / 45], x, r.top + 20, a % 90 == 0 ? 14 : 11, a == 0 ? north : 0xFFECEFF4);
                    p.setTextAlign(Paint.Align.LEFT);
                }
                if (a % 15 == 0) lampAt(c, x, r.bottom - 12, a % 45 == 0 ? 6 : 4.5f, a == 0 ? north : 0xFFB8B8C4, a % 45 == 0 && heading >= 0);
            }
        } else
        for (int d = start; d <= hd + 80; d += 5) {
            float x = r.centerX() + (d - hd) * pxPerDeg;
            int a = ((d % 360) + 360) % 360;
            boolean dim = heading < 0;
            if (a % 45 == 0) {
                boolean big = a % 90 == 0;
                int col = a == 0 ? north : (big ? 0xFFECEFF4 : 0xFF9A9AA6);
                if (dim) col = 0xFF3A3A42;
                p.setColor(col);
                c.drawRect(x - 1, r.bottom - 18, x + 1, r.bottom - 4, p);
                p.setTextAlign(Paint.Align.CENTER);
                text(c, names[a / 45], x, r.top + 19, big ? 14 : 11, col);
                p.setTextAlign(Paint.Align.LEFT);
            } else {
                p.setColor(dim ? 0xFF222228 : (a % 15 == 0 ? 0xFF5A5A64 : 0xFF3A3A42));
                float hh = a % 15 == 0 ? 9 : 5;
                c.drawRect(x - (a % 15 == 0 ? 1 : 0.5f), r.bottom - 4 - hh, x + (a % 15 == 0 ? 1 : 0.5f), r.bottom - 4, p);
            }
        }
        // randen laten vervagen, scanlines
        p.setShader(new LinearGradient(r.left, 0, r.right, 0, new int[]{0xFF050507, 0x00050507, 0x00050507, 0xFF050507},
                new float[]{0f, 0.18f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);
        p.setColor(0x59000000);
        for (float y = r.top + 2; y < r.bottom; y += 3) c.drawRect(r.left, y, r.right, y + 1, p);
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0xFF44444C);
        c.drawRoundRect(310, 2, 1064, 60, 4, 4, p);
        p.setStyle(Paint.Style.FILL);
        // naald
        if (leds) { lampAt(c, r.centerX(), r.bottom - 12, 8, AMB, true); } else {
        glowRect(c, r.centerX() - 1, r.top, r.centerX() + 1, r.bottom, AMB);
        Path tri = new Path();
        tri.moveTo(r.centerX() - 8, r.top); tri.lineTo(r.centerX() + 8, r.top); tri.lineTo(r.centerX(), r.top + 9); tri.close();
        p.setColor(AMB);
        c.drawPath(tri, p);
        }

        // koers-display
        RectF hr = new RectF(1080, 2, 1240, 60);
        if (heading >= 0) {
            int h = Math.round(heading) % 360;
            text(c, names[Math.round(h / 45f) % 8], 1094, 38, 14, AMB);
            drawSeg(c, String.format(Locale.US, "%03d", h), 1140, 16, 28, AMB);
        } else {
            drawSeg(c, "---", 1140, 16, 28, AMB);
        }
        glass(c, hr);
    }

    private void drawSpeedPanel(Canvas c) {
        RectF r = pan1;
        float x = r.left + 20, y = r.top + 18;
        text(c, Lang.t("SNELHEID", "SPEED"), x, y + 12, 14, RED);
        p.setTextAlign(Paint.Align.RIGHT);
        text(c, speed < 0 ? Lang.t("GPS ZOEKT", "GPS SEARCH") : "GPS · " + sats + " SAT", r.right - 20, y + 11, 10, DIM);
        p.setTextAlign(Paint.Align.LEFT);
        float ph = r.height();
        float digitH = Math.min(112, ph * 0.30f);
        RectF w = new RectF(x, y + 26, x + digitH * 2.05f + 52, y + 26 + digitH + 32);
        window(c, w, tRed, eRed);
        drawSegCentered(c, speed < 0 ? "--" : (speed < 10 ? " " + speed : String.valueOf(Math.min(speed, 199))), w, digitH, RED);
        glass(c, w);
        text(c, Lang.t("KM/U", "KM/H"), w.right + 12, w.bottom - 10, 16, RED);
        float by = w.bottom + 14;
        int lit = speed < 0 ? 0 : Math.min(22, Math.round(speed / 160f * 22f));
        bar(c, x, by, r.right - 20, 16, leds ? 16 : 22, leds ? Math.round(lit * 16f / 22f) : lit, 0, tRed, eRed);
        float sy = by + 44;
        float bw = r.right - 20 - x;
        for (int i = 0; i <= 4; i++) {
            p.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            text(c, String.valueOf(i * 40), x + bw * i / 4f, sy, 10, DIM);
        }
        p.setTextAlign(Paint.Align.LEFT);
        // max / gem
        float my = r.bottom - 64;
        float half = (r.width() - 52) / 2f;
        miniValue(c, "MAX", fmt0(tripMax), x, my, half, RED, tRed, eRed);
        miniValue(c, Lang.t("GEM", "AVG"), fmt0(tripAvg), x + half + 12, my, half, RED, tRed, eRed);
    }

    private void drawTripPanel(Canvas c) {
        RectF r = pan2;
        float x = r.left + 20;
        // lampjes 3 x 2
        float bx = r.left + 18, bw = r.width() - 36;
        String[] n = Lang.en ? new String[]{"IGNITION", "ENGINE", "LIGHTS", "TRIPS", "AUTO", "LOG"} : new String[]{"CONTACT", "MOTOR", "LICHT", "RITTEN", "AUTO", "LOG"};
        int[] col = {0xFFF4E04D, WG, WA, 0xFFB66BFF, 0xFF2F8CFF, WG};
        boolean[] on = {lContact, lMotor, lLicht, lTrips, lAuto, lLog};
        for (int i = 0; i < 6; i++) {
            float cx = bx + bw * (i % 3 * 2 + 1) / 6f;
            float cy = r.top + 18 + (i < 3 ? 34 : 98);
            lamp(c, cx, cy, col[i], on[i]);
            p.setTextAlign(Paint.Align.CENTER);
            text(c, n[i], cx, cy + 32, 9, on[i] ? TXT : 0xFF5F5F69);
            p.setTextAlign(Paint.Align.LEFT);
        }
        float y = r.top + 176;
        text(c, Lang.t("DEZE RIT", "THIS TRIP"), x, y, 12, GRN);
        // eigen display voor de rit
        RectF tw = new RectF(x - 6, y + 8, r.right - 14, r.bottom - 86);
        window(c, tw, dark(GRN, 0.12f), dark(GRN, 0.26f));
        float rowH = (tw.height() - 14) / 4f;
        String[][] rows = {
                {"KM", String.format(Locale.US, "%.1f", tripKm)},
                {Lang.t("TIJD", "TIME"), String.format(Locale.US, "%d:%02d", tripMinutes / 60, tripMinutes % 60)},
                {Lang.t("LITER", "LITRES"), String.format(Locale.US, "%.2f", tripL)},
                {"EURO", tripCost >= 0 ? String.format(Locale.US, "%.2f", tripCost) : "--"}};
        float segH = Math.min(22, rowH * 0.62f);
        for (int i = 0; i < 4; i++) {
            float ry = tw.top + 7 + i * rowH + (rowH - segH) / 2f;
            text(c, rows[i][0], tw.left + 10, ry + segH * 0.78f, 11, leds ? LBL : dark(GRN, 0.6f));
            float wSeg = segWidth(rows[i][1], segH);
            drawSeg(c, rows[i][1], tw.right - 12 - wSeg, ry, segH, GRN);
        }
        glass(c, tw);
        // accu: balk + spanning samen in één display
        float ay = r.bottom - 76;
        text(c, Lang.t("ACCU", "BATTERY"), x, ay + 10, 11, BLU);
        if (accuWarn > 0) warnTriangle(c, r.right - 32, ay + 2, 18, accuWarn == 2 ? WR : WA, (System.currentTimeMillis() / 600) % 2 == 0);
        RectF aw = new RectF(x - 6, ay + 18, r.right - 14, r.bottom - 16);
        window(c, aw, dark(BLU, 0.12f), dark(BLU, 0.26f));
        // 11,5 V (leeg, rood) .. 14,5 V (laden, groen)
        int litSeg = volts < 0 ? 0 : Math.max(1, Math.min(10, Math.round((volts - 11.5f) / 3.0f * 10f)));
        String v = volts < 0 ? "--.-" : String.format(Locale.US, "%.1f", volts);
        float vH = Math.min(22, aw.height() - 14);
        float vw = segWidth(v, vH);
        bar(c, aw.left + 6, aw.centerY() - 12, aw.right - vw - 46, 10, 10, litSeg, 3, 0, 0);
        drawSeg(c, v, aw.right - 30 - vw, aw.centerY() - vH / 2f, vH, BLU);
        text(c, "V", aw.right - 22, aw.centerY() + vH / 2f, 11, BLU);
        glass(c, aw);
    }

    /** Waarschuwingsdriehoek met uitroepteken (knippert als blink = false -> gedimd). */
    private void warnTriangle(Canvas c, float cx, float top, float h, int col, boolean bright) {
        android.graphics.Path t = new android.graphics.Path();
        float w = h * 1.15f;
        t.moveTo(cx, top); t.lineTo(cx + w / 2f, top + h); t.lineTo(cx - w / 2f, top + h); t.close();
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        int fill = bright ? col : dark(col, 0.35f);
        if (bright) { q.setColor(col); q.setMaskFilter(new android.graphics.BlurMaskFilter(h * 0.45f, android.graphics.BlurMaskFilter.Blur.NORMAL)); c.drawPath(t, q); q.setMaskFilter(null); }
        q.setColor(fill); c.drawPath(t, q);
        q.setColor(0xFF0A0A0D);
        q.setStrokeWidth(h * 0.12f); q.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(cx, top + h * 0.36f, cx, top + h * 0.66f, q);
        c.drawCircle(cx, top + h * 0.82f, h * 0.07f, q);
    }

    private static int dark(int col, float f) {
        int r = (int) (((col >> 16) & 0xFF) * f), g = (int) (((col >> 8) & 0xFF) * f), b = (int) ((col & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawFuelPanel(Canvas c) {
        RectF r = pan3;
        float x = r.left + 20, y = r.top + 18;
        text(c, Lang.t("VERBRUIK", "ECONOMY"), x, y + 12, 14, AMB);
        p.setTextAlign(Paint.Align.RIGHT);
        text(c, Lang.t("GESCHAT", "ESTIMATED"), r.right - 20, y + 11, 10, DIM);
        p.setTextAlign(Paint.Align.LEFT);
        float digitH = Math.min(82, r.height() * 0.22f);
        float kml = l100 > 0 ? 100f / l100 : -1f;
        RectF w = new RectF(x, y + 26, x + digitH * 2.8f + 44, y + 26 + digitH + 28);
        window(c, w, tAmb, eAmb);
        drawSegCentered(c, kml < 0 ? "--.-" : String.format(Locale.US, "%.1f", Math.min(kml, 99.9f)), w, digitH, AMB);
        glass(c, w);
        text(c, "KM/L", w.right + 12, w.bottom - 30, 15, AMB);
        text(c, l100 < 0 ? "-.- L/100" : String.format(Locale.US, "%.1f L/100", l100), w.right + 12, w.bottom - 8, 11, LBL);
        float by = w.bottom + 14;
        // balk: 6..22 km/l, zuinig = groen (rechts)
        int lit = kml < 0 ? 0 : Math.max(0, Math.min(16, Math.round((kml - 6f) / 16f * 16f)));
        bar(c, x, by, r.right - 20, 13, leds ? 12 : 16, leds ? Math.round(lit * 12f / 16f) : lit, 2, tAmb, eAmb);
        float sy = by + 40, bw = r.right - 20 - x;
        for (int i = 0; i <= 4; i++) {
            p.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            text(c, String.valueOf(6 + i * 4), x + bw * i / 4f, sy, 10, DIM);
        }
        p.setTextAlign(Paint.Align.LEFT);
        // tank: balk + bereik samen in één display
        float ty = r.bottom - 76;
        text(c, "TANK", x, ty + 10, 11, YEL);
        p.setTextAlign(Paint.Align.RIGHT);
        text(c, tankL < 0 ? Lang.t("TANKEN INVOEREN", "ENTER FUEL") : String.format(Locale.US, "~%.0f / 57 L", tankL), r.right - 20, ty + 10, 10, DIM);
        p.setTextAlign(Paint.Align.LEFT);
        RectF kw = new RectF(x - 6, ty + 18, r.right - 14, r.bottom - 16);
        window(c, kw, tAmb, eAmb);
        String rk = rangeKm < 0 ? "---" : String.valueOf(rangeKm);
        float rH = Math.min(24, kw.height() - 14);
        float rw = segWidth(rk, rH);
        int litDots = tankL < 0 ? 0 : Math.max(1, Math.min(12, Math.round(tankL / 57f * 12f)));
        bar(c, kw.left + 6, kw.centerY() - 13, kw.right - rw - 52, 12, 12, litDots, 3, 0, 0);
        drawSeg(c, rk, kw.right - 36 - rw, kw.centerY() - rH / 2f, rH, rangeKm >= 0 && rangeKm < 60 ? WR : GRN);
        text(c, "KM", kw.right - 28, kw.centerY() + rH / 2f, 10, GRN);
        glass(c, kw);
    }

    /**
     * Klein paneel in cockpitstijl (ritsamenvatting, zwevend venster). Coördinaten in dp; aanroeper schaalt.
     * big = groot 7-segment/lampjes-getal met eenheid; side = klein getal ernaast; rows = regels in een display.
     */
    public void paintCard(Canvas c, float w, float h, String title, int titleCol,
                          String big, String unit, int bigCol, String side, String sideLabel, int sideCol,
                          String[][] rows, int rowCol, String hint) {
        RectF panel = new RectF(6, 4, w - 6, h - 10);
        bezel(c, panel);
        float pad = 18, y = panel.top + 12;
        if (title != null) { text(c, title, pad, y + 12, 13, titleCol); y += 24; }
        if (big != null) {
            float bigH = rows == null ? Math.max(24, panel.bottom - y - 30) : 52;
            float bw = segWidth("188", bigH) + 28;
            RectF bwR = new RectF(pad, y, pad + bw, y + bigH + 18);
            window(c, bwR, tRed, eRed);
            drawSegCentered(c, big, bwR, bigH, bigCol);
            glass(c, bwR);
            text(c, unit, bwR.right + 10, bwR.top + 16, 12, bigCol);
            if (side != null) {
                float sh = Math.min(22, bigH * 0.5f);
                drawSeg(c, side, bwR.right + 10, bwR.bottom - sh - 4, sh, sideCol);
                text(c, sideLabel, bwR.right + 16 + segWidth(side, sh), bwR.bottom - 4, 10, sideCol);
            }
            y = bwR.bottom + 10;
        }
        if (rows != null) {
            RectF rw = new RectF(pad - 4, y, panel.right - 14, panel.bottom - (hint != null ? 30 : 14));
            window(c, rw, dark(rowCol, 0.12f), dark(rowCol, 0.26f));
            float rowH = (rw.height() - 10) / rows.length;
            float segH = Math.min(22, rowH * 0.62f);
            for (int i = 0; i < rows.length; i++) {
                float ry = rw.top + 5 + i * rowH + (rowH - segH) / 2f;
                text(c, rows[i][0], rw.left + 10, ry + segH * 0.8f, 11, leds ? LBL : dark(rowCol, 0.6f));
                float ws = segWidth(rows[i][1], segH);
                drawSeg(c, rows[i][1], rw.right - 12 - ws, ry, segH, rowCol);
            }
            glass(c, rw);
        }
        if (hint != null) {
            p.setTextAlign(Paint.Align.RIGHT);
            text(c, hint, panel.right - 18, panel.bottom - 12, 10, hintColor != 0 ? hintColor : DIM);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawOverlay(Canvas c) { /* scanlines/vignet zitten in de achtergrond */ }

    // ---------------------------------------------------------------- onderdelen

    private void miniValue(Canvas c, String lbl, String val, float x, float y, float w, int color, int tint, int edge) {
        text(c, lbl, x, y + 30, 11, LBL);
        RectF r = new RectF(x + w - 96, y, x + w, y + 46);
        window(c, r, tint, edge);
        drawSegCentered(c, val, r, 26, color);
        glass(c, r);
    }

    /** LED-balk in een verzonken glazen venster (thematint), met scanlines en glasreflectie. */
    private void bar(Canvas c, float x1, float y, float x2, float h, int n, int lit, int scheme, int tint, int edge) {
        RectF wr = new RectF(x1, y, x2, y + h + 14);
        boolean framed = tint != 0;
        if (framed) window(c, wr, tint, edge);
        y += 2;
        x1 += 2; x2 -= 2;
        float gap = 3, sw = (x2 - x1 - 10 - gap * (n - 1)) / n;
        if (leds) {
            float R = Math.min((h + 8) / 2f, (sw + gap) / 2f - 1f);
            float cy = y + 5 + h / 2f;
            for (int i = 0; i < n; i++) {
                int col = barColor(scheme, i, n);
                float cx = x1 + 5 + i * (sw + gap) + sw / 2f;
                lampAt(c, cx, cy, R, col, i < lit);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            int col;
            if (scheme == 0) col = i < n * 14 / 22 ? WG : (i < n * 18 / 22 ? WA : WR);
            else if (scheme == 1) col = i < n / 2 ? WG : (i < n * 13 / 16 ? WA : WR);
            else if (scheme == 2) col = i < n * 3 / 16 ? WR : (i < n * 7 / 16 ? WA : WG);
            else col = i < Math.max(1, n / 5) ? WR : (i < Math.max(2, n * 2 / 5) ? WA : WG);
            float sx = x1 + 5 + i * (sw + gap);
            if (i < lit) glowRect(c, sx, y + 5, sx + sw, y + 5 + h, col);
            else { p.setColor((col & 0x00FFFFFF) | 0x16000000); c.drawRect(sx, y + 5, sx + sw, y + 5 + h, p); }
        }
        if (framed) glass(c, wr);
    }

    private static int barColor(int scheme, int i, int n) {
        if (scheme == 0) return i < n * 14 / 22 ? WG : (i < n * 18 / 22 ? WA : WR);
        if (scheme == 1) return i < n / 2 ? WG : (i < n * 13 / 16 ? WA : WR);
        if (scheme == 2) return i < n * 3 / 16 ? WR : (i < n * 7 / 16 ? WA : WG);
        return i < Math.max(1, n / 5) ? WR : (i < Math.max(2, n * 2 / 5) ? WA : WG);
    }

    private void glowRect(Canvas c, float l, float t, float r, float b, int col) {
        glow.setColor((col & 0x00FFFFFF) | 0xAA000000);
        glow.setMaskFilter(new BlurMaskFilter(6, BlurMaskFilter.Blur.NORMAL));
        c.drawRect(l, t, r, b, glow);
        glow.setMaskFilter(null);
        p.setColor(col);
        c.drawRect(l, t, r, b, p);
        p.setColor(0x55FFFFFF);
        c.drawRect(l, t, r, t + 1, p);
    }

    private void dot(Canvas c, float cx, float cy, float rad, int col, boolean on) {
        if (on) {
            glow.setColor((col & 0x00FFFFFF) | 0xCC000000);
            glow.setMaskFilter(new BlurMaskFilter(rad * 1.4f, BlurMaskFilter.Blur.NORMAL));
            c.drawCircle(cx, cy, rad, glow);
            glow.setMaskFilter(null);
            p.setShader(new RadialGradient(cx - rad * 0.3f, cy - rad * 0.4f, rad * 1.3f,
                    new int[]{0xCCFFFFFF, col, (col & 0x00FFFFFF) | 0xAA000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        } else {
            p.setShader(new RadialGradient(cx - rad * 0.3f, cy - rad * 0.4f, rad * 1.3f, 0xFF2A2A30, 0xFF121216, Shader.TileMode.CLAMP));
        }
        c.drawCircle(cx, cy, rad, p);
        p.setShader(null);
    }

    /** Dashboardlampje: verchroomde ring met bolle lens. */
    private void lamp(Canvas c, float cx, float cy, int col, boolean on) { lampAt(c, cx, cy, 18, col, on); }

    /** Dashboardlampje met straal R (chromen ring), lens ~0.72 R. */
    private void lampAt(Canvas c, float cx, float cy, float R, int col, boolean on) {
        float k = R / 18f, lr = 13 * k;
        glow.setColor(0xFF000000);
        glow.setMaskFilter(new BlurMaskFilter(Math.max(1, 4 * k), BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy + 3 * k, R, glow);
        glow.setMaskFilter(null);
        p.setShader(new SweepGradient(cx, cy, new int[]{0xFFD9D9E0, 0xFF55555E, 0xFFC4C4CC, 0xFF3A3A42, 0xFFD9D9E0}, null));
        c.drawCircle(cx, cy, R, p);
        p.setShader(null);
        if (on) {
            glow.setColor(col);
            glow.setMaskFilter(new BlurMaskFilter(Math.max(2, 16 * k), BlurMaskFilter.Blur.NORMAL));
            c.drawCircle(cx, cy, 14 * k, glow);
            glow.setMaskFilter(null);
            p.setShader(new RadialGradient(cx - 4 * k, cy - 5 * k, 16 * k, new int[]{0xEEFFFFFF, col, (col & 0x00FFFFFF) | 0xCC000000, (col & 0x00FFFFFF) | 0x55000000},
                    new float[]{0f, 0.28f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        } else {
            p.setShader(new RadialGradient(cx - 4 * k, cy - 5 * k, 16 * k, new int[]{(col & 0x00FFFFFF) | 0x55000000, (col & 0x00FFFFFF) | 0x22000000, 0xFF120C0C},
                    new float[]{0f, 0.3f, 0.75f}, Shader.TileMode.CLAMP));
        }
        c.drawCircle(cx, cy, lr, p);
        p.setShader(null);
    }

    private void text(Canvas c, String t, float x, float y, float size, int col) {
        if (leds) {
            float pitch = size * 0.19f;
            float w = DotFont.width(t, pitch);
            Paint.Align al = p.getTextAlign();
            float x0 = al == Paint.Align.CENTER ? x - w / 2 : al == Paint.Align.RIGHT ? x - w : x;
            DotFont.draw(c, t, x0, y - 7 * pitch, pitch, col, false, false, p, glow);
            return;
        }
        p.setTypeface(label);
        p.setTextSize(size);
        p.setLetterSpacing(0.18f);
        p.setColor(col);
        c.drawText(t, x, y, p);
        p.setLetterSpacing(0f);
    }

    private static String fmt0(float v) { return v <= 0 ? "--" : String.valueOf(Math.round(v)); }

    // ---------------------------------------------------------------- 7-segment

    private static final String DIG = "0123456789- ";
    private static final String[] SEGS = {"abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg", "g", ""};

    float segWidth(String t, float H) {
        if (leds) return DotFont.width(t, H / 7f);
        float W = H * 0.56f, T = H * 0.13f, sp = H * 0.16f, x = 0;
        for (char ch : t.toCharArray()) {
            if (ch == '.') continue;
            if (ch == ':') { x += T * 1.4f + sp * 0.4f; continue; }
            x += W + sp;
        }
        return Math.max(0, x - sp);
    }

    private void drawSegCentered(Canvas c, String t, RectF r, float H, int col) {
        float w = segWidth(t, H);
        drawSeg(c, t, r.centerX() - w / 2f, r.centerY() - H / 2f, H, col);
    }

    /** 7-segmentcijfers met zwakke "uit"-segmenten, licht schuin, met gloed. */
    void drawSeg(Canvas c, String t, float x0, float y0, float H, int col) {
        if (leds) {
            DotFont.draw(c, t, x0, y0, H / 7f, col, H >= 24, true, p, glow);
            return;
        }
        float W = H * 0.56f, T = H * 0.13f, gp = T * 0.18f, sp = H * 0.16f;
        Path lit = new Path(), off = new Path();
        float x = 0;
        for (char ch : t.toCharArray()) {
            if (ch == '.') { addSeg(lit, x - sp * 0.75f, H - T, T, T, T); continue; }
            if (ch == ':') {
                addSeg(lit, x + T * 0.2f, H * 0.28f, T, T, T);
                addSeg(lit, x + T * 0.2f, H * 0.64f, T, T, T);
                x += T * 1.4f + sp * 0.4f; continue;
            }
            int idx = DIG.indexOf(ch);
            String on = idx >= 0 ? SEGS[idx] : "";
            float[][] g = {
                    {T / 2 + gp, 0, W - T - 2 * gp, T},                    // a
                    {W - T, T / 2 + gp, T, H / 2 - T / 2 - gp},             // b
                    {W - T, H / 2 + gp, T, H / 2 - T / 2 - 2 * gp},         // c
                    {T / 2 + gp, H - T, W - T - 2 * gp, T},                // d
                    {0, H / 2 + gp, T, H / 2 - T / 2 - 2 * gp},             // e
                    {0, T / 2 + gp, T, H / 2 - T / 2 - gp},                 // f
                    {T / 2 + gp, H / 2 - T / 2, W - T - 2 * gp, T}};        // g
            for (int k = 0; k < 7; k++) {
                boolean isOn = on.indexOf((char) ('a' + k)) >= 0;
                if (isOn) addSeg(lit, x + g[k][0], g[k][1], g[k][2], g[k][3], T);
                else if (ch != ' ') addSeg(off, x + g[k][0], g[k][1], g[k][2], g[k][3], T);
            }
            x += W + sp;
        }
        c.save();
        c.translate(x0, y0);
        c.skew(-0.12f, 0);
        c.translate(H * 0.12f, 0);
        p.setColor((col & 0x00FFFFFF) | 0x12000000);
        c.drawPath(off, p);
        glow.setColor((col & 0x00FFFFFF) | 0x99000000);
        glow.setMaskFilter(new BlurMaskFilter(Math.max(3, H * 0.16f), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(lit, glow);
        glow.setMaskFilter(null);
        p.setColor(col);
        c.drawPath(lit, p);
        c.restore();
    }

    private static void addSeg(Path path, float x, float y, float w, float h, float T) {
        path.addRoundRect(new RectF(x, y, x + w, y + h), T * 0.45f, T * 0.45f, Path.Direction.CW);
    }

    // ---------------------------------------------------------------- aanraken

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() / s, y = e.getY() / s;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downInClock = rClock.contains(x, y);
                if (downInClock) postDelayed(longPress, 700);
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (downInClock) { downInClock = false; return true; }
                if (listener == null) return true;
                if (rTank.contains(x, y)) listener.onTankTap();
                else if (rSpeed.contains(x, y)) listener.onSpeedTap();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                downInClock = false;
                return true;
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}

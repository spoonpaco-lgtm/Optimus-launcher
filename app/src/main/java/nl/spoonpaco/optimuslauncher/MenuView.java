package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Menu met tabbladen, volledig getekend in de cockpitstijl (bezels, displays met scanlines,
 * lampjes met chromen ring). Ontwerpmaat 1240 breed, net als de cockpit.
 */
public class MenuView extends View {

    public interface Host {
        void close();
        void fuel();
        void tripLog();
        void highscores();
        void export();
        void saveHere();
        void location(int index);
        void maintenance();
        void pickTheme(Theme t);
        void setLanguage(boolean english);
        void setStartAA(boolean aa);
        void setOverlay(boolean on);
        void update();
        void debug();
        boolean startAA();
        boolean overlay();
        String version();
        String status();
    }

    private final CockpitView pt;
    private final Store store;
    private Theme theme;
    private final Host host;
    private int tab = 0;
    private float s = 1f, H = 600f;
    private final List<Object[]> hits = new ArrayList<>(); // {RectF, Runnable}

    private static final String[][] TABS = {
            {"THEMA", "THEME", "kleuren & stijl", "colours & style"},
            {"AUTO", "CAR", "km-stand · onderhoud · accu", "odometer · service · battery"},
            {"RITTEN", "TRIPS", "rittenlog · locaties · scores", "trip log · places · scores"},
            {"INSTELLINGEN", "SETTINGS", "taal · opstarten · venster", "language · start-up · overlay"},
            {"SYSTEEM", "SYSTEM", "update · debug", "update · debug"}};

    public MenuView(Context c, Theme t, Store store, Host host) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        this.store = store; this.host = host;
        pt = new CockpitView(c, null);
        setTheme(t);
    }

    public void setTheme(Theme t) { theme = t; pt.setTheme(t); invalidate(); }

    public void setTab(int t) { tab = t; invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { s = w / 1240f; H = h / s; }

    private void hit(RectF r, Runnable a) { hits.add(new Object[]{r, a}); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX() / s, y = e.getY() / s;
            for (int i = hits.size() - 1; i >= 0; i--) {
                RectF r = (RectF) hits.get(i)[0];
                if (r.contains(x, y)) { ((Runnable) hits.get(i)[1]).run(); return true; }
            }
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        hits.clear();
        c.save();
        c.scale(s, s);
        int key = theme.key;
        pt.uiBackground(c, 1240, H);
        RectF left = new RectF(0, 0, 300, H - 2), right = new RectF(316, 0, 1240, H - 2);
        pt.uiBezel(c, left);
        pt.uiBezel(c, right);

        // ---- tabbladen ----
        float y = 22;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            RectF r = new RectF(18, y, 282, y + 82);
            pt.uiTab(c, r, Lang.en ? TABS[i][1] : TABS[i][0], Lang.en ? TABS[i][3] : TABS[i][2], i == tab, key);
            hit(r, () -> setTab(idx));
            y += 92;
        }
        pt.uiText(c, "OPTIMUS LAUNCHER " + host.version(), 24, y + 14, 10, 0xFF5F5F69, Paint.Align.LEFT);
        RectF close = new RectF(18, y + 30, 282, Math.min(y + 86, H - 22));
        pt.uiButton(c, close, Lang.t("SLUITEN", "CLOSE"), true, key, 16);
        hit(close, host::close);

        float x0 = 344, x1 = 1212;
        switch (tab) {
            case 0: drawThemes(c, x0, x1); break;
            case 1: drawCar(c, x0, x1); break;
            case 2: drawTrips(c, x0, x1); break;
            case 3: drawSettings(c, x0, x1); break;
            default: drawSystem(c, x0, x1); break;
        }
        c.restore();
        c.save(); c.scale(s, s);
        pt.uiScanlines(c, 1240, H);
        c.restore();
    }

    private void label(Canvas c, String t, float x, float y, int col) { pt.uiText(c, t, x, y, 13, col, Paint.Align.LEFT); }

    private RectF button(Canvas c, float l, float t, float r, float b, String text, boolean on, Runnable a) {
        RectF rr = new RectF(l, t, r, b);
        pt.uiButton(c, rr, text, on, theme.key, 15);
        hit(rr, a);
        return rr;
    }

    // ---------------------------------------------------------------- THEMA

    private void drawThemes(Canvas c, float x0, float x1) {
        label(c, Lang.t("THEMA", "THEME"), x0, 44, pt.uiAmb());
        float w = (x1 - x0 - 20) / 2f, h = 170;
        for (int i = 0; i < Theme.ALL.length; i++) {
            final Theme t = Theme.ALL[i];
            float l = x0 + (i % 2) * (w + 20), top = 62 + (i / 2) * (h + 20);
            RectF r = new RectF(l, top, l + w, top + h);
            boolean on = t == theme;
            if (on) pt.uiWindow(c, r, t.key); else pt.uiBezel(c, r);
            int[] cols = {t.speed, t.accent, t.trip, t.key};
            for (int k = 0; k < 4; k++) pt.uiLamp(c, r.left + 40 + k * 46, r.top + 44, 14, cols[k], true);
            pt.uiText(c, t.name.toUpperCase(Locale.ROOT), r.left + 24, r.top + 100, 20, t.key, Paint.Align.LEFT);
            pt.uiText(c, Lang.en ? descEn(t.id) : t.desc, r.left + 24, r.top + 128, 10, 0xFF8E8E9A, Paint.Align.LEFT);
            pt.uiLamp(c, r.right - 30, r.top + 30, 10, theme.key, on);
            if (on) pt.uiGlass(c, r);
            hit(r, () -> host.pickTheme(t));
        }
    }

    private static String descEn(String id) {
        switch (id) {
            case "vfd": return "Turquoise fluorescent, like 80s digital dashboards";
            case "neon": return "Magenta and cyan neon on deep purple";
            case "old": return "All lamps: dot-matrix digits and text";
            default: return "Red, amber and green LED, like the sketch";
        }
    }

    // ---------------------------------------------------------------- AUTO

    private void drawCar(Canvas c, float x0, float x1) {
        int AMB = pt.uiAmb(), BLU = pt.uiBlu(), YEL = pt.uiYel();
        long now = Clock.now();
        // kilometerstand
        label(c, Lang.t("KILOMETERSTAND", "ODOMETER"), x0, 44, AMB);
        RectF w = new RectF(x0, 56, x0 + 430, 128);
        pt.uiWindow(c, w, AMB);
        int odo = store.odometer();
        String ov = odo < 0 ? "------" : String.format(Locale.GERMANY, "%,d", odo);
        pt.uiSegRight(c, ov, w.right - 18, w.top + 14, 44, odo < 0 ? CockpitView.WR : AMB);
        pt.uiGlass(c, w);
        pt.uiText(c, "KM", w.right + 14, 88, 14, AMB, Paint.Align.LEFT);
        pt.uiText(c, odo < 0 ? Lang.t("NIET INGESTELD", "NOT SET") : Lang.t("GPS TELT BIJ", "GPS ADDS UP"), w.right + 14, 110, 9,
                odo < 0 ? CockpitView.WR : 0xFF6C6C78, Paint.Align.LEFT);
        button(c, x1 - 250, 64, x1, 120, Lang.t("AANPASSEN", "EDIT"), false, host::maintenance);

        // onderhoud
        label(c, Lang.t("ONDERHOUD", "SERVICE"), x0, 160, AMB);
        int k = store.oilKmLeft(), od = store.oilDaysLeft(now), a = store.apkDaysLeft(now);
        float oilFrac = 1f;
        if (k != Integer.MIN_VALUE) oilFrac = Math.min(oilFrac, k / (float) Store.OIL_KM);
        if (od != Integer.MIN_VALUE) oilFrac = Math.min(oilFrac, od / 365f);
        String oilTxt;
        if (k == Integer.MIN_VALUE && od == Integer.MIN_VALUE) { oilTxt = Lang.t("niet ingesteld", "not set"); oilFrac = -1; }
        else oilTxt = (k != Integer.MIN_VALUE ? (k >= 0 ? String.format(Locale.GERMANY, Lang.t("over %,d km", "in %,d km"), k) : Lang.t("TE LAAT", "OVERDUE")) : "")
                + (od != Integer.MIN_VALUE ? (k != Integer.MIN_VALUE ? "  /  " : "") + days(od) : "");
        serviceRow(c, x0, 172, x1 - 270, Lang.t("OLIE", "OIL"), oilTxt, oilFrac);
        float apkFrac = a == Integer.MIN_VALUE ? -1 : Math.min(1f, a / 365f);
        String apkTxt = a == Integer.MIN_VALUE ? Lang.t("niet ingesteld", "not set")
                : days(a) + "  ·  " + new java.text.SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new java.util.Date(store.apkDate()));
        serviceRow(c, x0, 232, x1 - 270, Lang.t("APK", "MOT"), apkTxt, apkFrac);
        button(c, x1 - 250, 178, x1, 278, Lang.t("INSTELLEN", "SET UP"), false, host::maintenance);

        // accu
        label(c, Lang.t("ACCU", "BATTERY"), x0, 316, BLU);
        int lv = BatteryHealth.level;
        int lc = lv == 2 ? CockpitView.WR : lv == 1 ? CockpitView.WA : CockpitView.WG;
        boolean known = BatteryHealth.last("rest") > 0;
        pt.uiLamp(c, x0 + 108, 311, 11, lc, known);
        pt.uiText(c, !known ? Lang.t("NOG GEEN METING", "NO DATA YET") : lv == 0 ? Lang.t("GOED", "GOOD") : lv == 1 ? Lang.t("LET OP", "WATCH") : Lang.t("SLECHT", "BAD"),
                x0 + 130, 316, 14, known ? lc : 0xFF6C6C78, Paint.Align.LEFT);
        if (lv > 0) pt.uiText(c, BatteryHealth.reason.toUpperCase(Locale.ROOT), x0 + 260, 316, 9, lc, Paint.Align.LEFT);
        String[][] v = {{Lang.t("RUST", "REST"), "rest"}, {"START", "crank"}, {Lang.t("LADEN", "CHARGE"), "charge"}};
        float bw = (x1 - x0 - 40) / 3f;
        for (int i = 0; i < 3; i++) {
            RectF r = new RectF(x0 + i * (bw + 20), 330, x0 + i * (bw + 20) + bw, 394);
            pt.uiWindow(c, r, BLU);
            pt.uiText(c, v[i][0], r.left + 14, r.centerY() + 5, 12, BLU, Paint.Align.LEFT);
            float val = BatteryHealth.last(v[i][1]);
            pt.uiSegRight(c, val > 0 ? String.format(Locale.US, "%.1f", val) : "--.-", r.right - 34, r.top + 14, 36, BLU);
            pt.uiText(c, "V", r.right - 14, r.bottom - 16, 12, BLU, Paint.Align.RIGHT);
            pt.uiGlass(c, r);
        }

        // tank
        label(c, "TANK", x0, 432, YEL);
        RectF t = new RectF(x0, 444, x1 - 270, 512);
        pt.uiWindow(c, t, YEL);
        float tl = store.tankLiters();
        int lit = tl < 0 ? 0 : Math.max(1, Math.round(tl / Store.TANK_LITERS * 16));
        pt.uiBar(c, t.left + 10, t.top + 18, t.left + 340, 18, 16, lit, 3);
        String range = tl < 0 ? "---" : String.valueOf(store.rangeKm());
        pt.uiSegRight(c, range, t.right - 44, t.top + 16, 36, CockpitView.WG);
        pt.uiText(c, "KM", t.right - 14, t.bottom - 18, 11, CockpitView.WG, Paint.Align.RIGHT);
        pt.uiText(c, tl < 0 ? Lang.t("TANKEN INVOEREN", "ENTER FUEL") : String.format(Locale.US, "~%.0f / 57 L", tl), t.left + 14, t.bottom - 8, 9, 0xFF6C6C78, Paint.Align.LEFT);
        pt.uiGlass(c, t);
        button(c, x1 - 250, 450, x1, 506, Lang.t("TANKEN", "REFUEL"), false, host::fuel);
    }

    private void serviceRow(Canvas c, float x0, float top, float right, String name, String txt, float frac) {
        int col = frac < 0 ? 0xFF6C6C78 : frac < 0.05f ? CockpitView.WR : frac < 0.15f ? CockpitView.WA : CockpitView.WG;
        RectF r = new RectF(x0, top, right, top + 50);
        pt.uiWindow(c, r, col);
        pt.uiText(c, name, r.left + 16, r.centerY() + 6, 15, col, Paint.Align.LEFT);
        pt.uiText(c, txt, r.left + 88, r.centerY() + 5, 12, CockpitView.TXT, Paint.Align.LEFT);
        int lit = frac < 0 ? 0 : Math.max(1, Math.round(Math.max(0f, Math.min(1f, frac)) * 12));
        pt.uiBar(c, r.right - 250, r.top + 11, r.right - 12, 14, 12, lit, 2);
        pt.uiGlass(c, r);
    }

    private static String days(int n) {
        return n >= 0 ? String.format(Locale.US, Lang.t("over %d dagen", "in %d days"), n) : Lang.t("TE LAAT", "OVERDUE");
    }

    // ---------------------------------------------------------------- RITTEN

    private void drawTrips(Canvas c, float x0, float x1) {
        int GRN = pt.uiGrn(), AMB = pt.uiAmb();
        int year = Calendar.getInstance().get(Calendar.YEAR);
        label(c, Lang.t("ZAKELIJK ", "BUSINESS ") + year + "  ·  SPOONPACO", x0, 44, AMB);
        RectF w = new RectF(x0, 56, x1, 136);
        pt.uiWindow(c, w, GRN);
        float km = WorkLog.kmThisYear(getContext());
        int n = 0;
        Calendar cal = Calendar.getInstance();
        for (TripLog.Trip t : WorkLog.trips(getContext())) { cal.setTimeInMillis(t.start); if (cal.get(Calendar.YEAR) == year) n++; }
        String kms = String.format(Locale.US, "%.0f", km);
        pt.uiSeg(c, kms, w.left + 20, w.top + 16, 46, GRN);
        float kx = w.left + 40 + pt.segWidth(kms, 46);
        pt.uiText(c, Lang.t("ZAKELIJKE KM", "BUSINESS KM"), kx, w.top + 38, 13, GRN, Paint.Align.LEFT);
        pt.uiText(c, n + Lang.t(" WERKRITTEN", " WORK TRIPS"), kx, w.top + 60, 10, 0xFF8E8E9A, Paint.Align.LEFT);
        String eur = String.format(Locale.US, "%.2f", km * WorkLog.ratePerKm(year));
        pt.uiSegRight(c, eur, w.right - 20, w.top + 14, 42, AMB);
        pt.uiText(c, "€", w.right - 30 - pt.segWidth(eur, 42), w.top + 50, 22, AMB, Paint.Align.RIGHT);
        pt.uiText(c, String.format(Locale.GERMANY, Lang.t("AFTREK X € %.2f / KM", "DEDUCTION X € %.2f / KM"), WorkLog.ratePerKm(year)), w.right - 20, w.bottom - 8, 8, 0xFF8E8E9A, Paint.Align.RIGHT);
        pt.uiGlass(c, w);

        float bw = (x1 - x0 - 40) / 3f;
        button(c, x0, 158, x0 + bw, 214, Lang.t("RITTENLOG", "TRIP LOG"), true, host::tripLog);
        button(c, x0 + bw + 20, 158, x0 + 2 * bw + 20, 214, "HIGH SCORES", false, host::highscores);
        button(c, x0 + 2 * bw + 40, 158, x1, 214, Lang.t("NAAR USB", "TO USB"), false, host::export);
        pt.uiText(c, Lang.t("ALLE RITTEN + ROUTES", "ALL TRIPS + ROUTES"), x0 + bw / 2, 234, 8, 0xFF6C6C78, Paint.Align.CENTER);
        pt.uiText(c, Lang.t("TOP 5 · ZUINIGST · LANGST", "TOP 5 · BEST · LONGEST"), x0 + bw * 1.5f + 20, 234, 8, 0xFF6C6C78, Paint.Align.CENTER);
        pt.uiText(c, Lang.t("RITTEN + SPOONPACO-RITTEN", "TRIPS + SPOONPACO-RITTEN"), x0 + bw * 2.5f + 40, 234, 8, 0xFF6C6C78, Paint.Align.CENTER);

        label(c, Lang.t("VASTE LOCATIES", "SAVED PLACES"), x0, 276, AMB);
        button(c, x1 - 250, 252, x1, 296, Lang.t("+ HIER OPSLAAN", "+ SAVE HERE"), false, host::saveHere);
        List<Locations.Loc> locs = Locations.all(getContext());
        float y = 310;
        if (locs.isEmpty()) {
            pt.uiText(c, Lang.t("NOG GEEN LOCATIES — DRUK TER PLEKKE OP + HIER OPSLAAN", "NO PLACES YET — PRESS + SAVE HERE ON SITE"), x0, y + 30, 11, 0xFF6C6C78, Paint.Align.LEFT);
        }
        int max = Math.max(0, (int) ((H - 30 - y) / 54));
        for (int i = 0; i < Math.min(locs.size(), max); i++) {
            final int idx = i;
            Locations.Loc l = locs.get(i);
            RectF r = new RectF(x0, y, x1, y + 46);
            pt.uiWindow(c, r, theme.key);
            boolean hasAddr = !l.addr.isEmpty();
            pt.uiLamp(c, r.left + 24, r.centerY(), 9, hasAddr ? CockpitView.WG : CockpitView.WA, true);
            pt.uiText(c, l.name.isEmpty() ? "?" : l.name.toUpperCase(Locale.ROOT), r.left + 48, r.centerY() + 6, 15, CockpitView.TXT, Paint.Align.LEFT);
            pt.uiText(c, hasAddr ? l.addr : Lang.t("(ADRES VOLGT)", "(ADDRESS PENDING)"), r.left + 260, r.centerY() + 5, 11, hasAddr ? 0xFFB8B8C2 : 0xFF6C6C78, Paint.Align.LEFT);
            pt.uiText(c, ">", r.right - 18, r.centerY() + 6, 14, 0xFF6C6C78, Paint.Align.RIGHT);
            pt.uiGlass(c, r);
            hit(r, () -> host.location(idx));
            y += 54;
        }
        if (locs.size() > max && max > 0) pt.uiText(c, "+" + (locs.size() - max) + Lang.t(" MEER IN HET RITTENSCHERM", " MORE IN THE TRIP SCREEN"), x0, y + 14, 9, 0xFF6C6C78, Paint.Align.LEFT);
    }

    // ---------------------------------------------------------------- INSTELLINGEN

    private void drawSettings(Canvas c, float x0, float x1) {
        int AMB = pt.uiAmb();
        float bw = (x1 - x0 - 20) / 2f;
        label(c, Lang.t("TAAL", "LANGUAGE"), x0, 44, AMB);
        button(c, x0, 58, x0 + bw, 118, "NEDERLANDS", !Lang.en, () -> host.setLanguage(false));
        button(c, x0 + bw + 20, 58, x1, 118, "ENGLISH", Lang.en, () -> host.setLanguage(true));

        boolean aa = host.startAA();
        label(c, Lang.t("BIJ CONTACT AAN", "ON IGNITION"), x0, 160, AMB);
        button(c, x0, 174, x0 + bw, 234, "LAUNCHER", !aa, () -> { host.setStartAA(false); invalidate(); });
        button(c, x0 + bw + 20, 174, x1, 234, "ANDROID AUTO", aa, () -> { host.setStartAA(true); invalidate(); });

        boolean ov = host.overlay();
        label(c, Lang.t("ZWEVEND SNELHEIDSVENSTER (BUITEN DE LAUNCHER)", "FLOATING SPEED WINDOW (OUTSIDE THE LAUNCHER)"), x0, 276, AMB);
        button(c, x0, 290, x0 + bw, 350, Lang.t("AAN", "ON"), ov, () -> { host.setOverlay(true); invalidate(); });
        button(c, x0 + bw + 20, 290, x1, 350, Lang.t("UIT", "OFF"), !ov, () -> { host.setOverlay(false); invalidate(); });
    }

    // ---------------------------------------------------------------- SYSTEEM

    private void drawSystem(Canvas c, float x0, float x1) {
        int AMB = pt.uiAmb();
        float bw = (x1 - x0 - 20) / 2f;
        label(c, Lang.t("SYSTEEM", "SYSTEM"), x0, 44, AMB);
        button(c, x0, 58, x0 + bw, 118, Lang.t("UPDATE ZOEKEN", "CHECK UPDATE"), false, host::update);
        button(c, x0 + bw + 20, 58, x1, 118, "DEBUG", false, host::debug);
        label(c, "STATUS", x0, 160, AMB);
        RectF w = new RectF(x0, 172, x1, 172 + 44 + 30 * 5);
        pt.uiWindow(c, w, pt.uiGrn());
        String[] lines = host.status().split("\n");
        float y = w.top + 34;
        for (String l : lines) { pt.uiText(c, l, w.left + 20, y, 12, pt.uiGrn(), Paint.Align.LEFT); y += 30; }
        pt.uiGlass(c, w);
    }
}

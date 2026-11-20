package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Rittenlog in cockpitstijl: lijst met ritten, route van de gekozen rit, werkrit-markering, scores, export. */
public class TripLogActivity extends Activity {
    private Store store;
    private Theme theme;
    private CockpitView pt;
    private float d;
    private List<TripLog.Trip> trips = new ArrayList<>();
    private int sel = 0;
    private RouteView route;
    private RetroUi.Text details, workTotal;
    private RetroUi.Btn workBtn;
    private BaseAdapter adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        theme = Theme.byId(getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led"));
        pt = RetroUi.painter(this, theme);
        d = RetroUi.unit(this);
        int pad = (int) (14 * d);

        FrameLayout frame = new FrameLayout(this);
        frame.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new RetroUi.Panel(pt, RetroUi.Panel.BG, 0, d));
        root.setPadding(pad, pad, pad, pad);

        // ---- bovenbalk ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        RetroUi.Text title = new RetroUi.Text(this, pt, 24, theme.key);
        title.set(Lang.t("RITTENLOG", "TRIP LOG"));
        titles.addView(title);
        workTotal = new RetroUi.Text(this, pt, 12, theme.trip);
        titles.addView(workTotal);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        workBtn = btn(Lang.t("MARKEER ALS WERKRIT", "MARK AS WORK TRIP"), false, v -> toggleWork());
        top.addView(workBtn, btnLp(250));
        top.addView(btn("HIGH SCORES", false, v -> Dialogs.highscores(this, theme)), btnLp(190));
        top.addView(btn(Lang.t("NAAR USB", "TO USB"), false, v -> Dialogs.export(this)), btnLp(140));
        top.addView(btn(Lang.t("SLUITEN", "CLOSE"), true, v -> finish()), btnLp(140));
        root.addView(top);

        // ---- lijst + route ----
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, (int) (10 * d), 0, 0);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setBackground(new RetroUi.Panel(pt, RetroUi.Panel.BEZEL, 0, d));
        list.setPadding((int) (10 * d), (int) (12 * d), (int) (10 * d), (int) (12 * d));
        list.setClipToPadding(false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return trips.size(); }
            @Override public Object getItem(int i) { return trips.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                Row r = cv instanceof Row ? (Row) cv : new Row(TripLogActivity.this);
                r.bind(trips.get(i), i == sel);
                return r;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> { sel = i; adapter.notifyDataSetChanged(); showTrip(); });
        body.addView(list, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.38f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding((int) (12 * d), 0, 0, 0);
        route = new RouteView(this);
        right.addView(route, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        details = new RetroUi.Text(this, pt, 13, CockpitView.TXT);
        LinearLayout detBox = new LinearLayout(this);
        detBox.setBackground(new RetroUi.Panel(pt, RetroUi.Panel.WINDOW, theme.key, d));
        detBox.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
        detBox.addView(details, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = (int) (10 * d);
        right.addView(detBox, dl);
        body.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(new RetroUi.Scanlines(this, pt), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
    }

    @Override protected void onResume() {
        super.onResume();
        trips = TripLog.all(this);
        if (sel >= trips.size()) sel = 0;
        adapter.notifyDataSetChanged();
        showTrip();
        Geo.fillMissing(this);
    }

    private RetroUi.Btn btn(String text, boolean on, View.OnClickListener l) {
        RetroUi.Btn b = new RetroUi.Btn(this, pt, text, on, theme.key);
        b.size = 15;
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams btnLp(int wDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (wDp * d), (int) (54 * d));
        lp.setMargins((int) (8 * d), 0, 0, 0);
        return lp;
    }

    private void toggleWork() {
        if (trips.isEmpty()) return;
        TripLog.Trip t = trips.get(sel);
        boolean work = !WorkLog.isWork(this, t.id);
        WorkLog.setWork(this, t.id, work);
        Toast.makeText(this, work ? Lang.t("Rit staat nu in spoonpaco-ritten", "Trip added to spoonpaco-ritten")
                : Lang.t("Rit uit spoonpaco-ritten gehaald", "Trip removed from spoonpaco-ritten"), Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
        showTrip();
    }

    private void showTrip() {
        float km = WorkLog.kmThisYear(this);
        int y = Calendar.getInstance().get(Calendar.YEAR);
        workTotal.set(String.format(Locale.GERMANY, Lang.t("ZAKELIJK %d: %.0f KM = € %.2f", "BUSINESS %d: %.0f KM = € %.2f"),
                y, km, km * WorkLog.ratePerKm(y)));
        workBtn.setVisibility(trips.isEmpty() ? View.GONE : View.VISIBLE);
        if (trips.isEmpty()) {
            route.set(null, Lang.t("NOG GEEN RITTEN", "NO TRIPS YET"));
            details.set(Lang.t("EEN RIT WORDT OPGESLAGEN ZODRA HIJ IS AFGESLOTEN\n(CONTACT UIT OF 10 MIN STILSTAND)",
                    "A TRIP IS SAVED WHEN IT ENDS\n(IGNITION OFF OR 10 MIN STOPPED)"));
            return;
        }
        TripLog.Trip t = trips.get(sel);
        boolean work = WorkLog.isWork(this, t.id);
        workBtn.set(work ? Lang.t("WERKRIT", "WORK TRIP") : Lang.t("MARKEER ALS WERKRIT", "MARK AS WORK TRIP"), work);
        route.set(TripLog.route(this, t.id), null);
        SimpleDateFormat day = new SimpleDateFormat("EEEE d MMMM", Lang.locale());
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        List<String> l = new ArrayList<>(); List<Integer> c = new ArrayList<>();
        l.add(day.format(new Date(t.start)).toUpperCase(Lang.locale()) + "   " + hm.format(new Date(t.start)) + " - " + hm.format(new Date(t.end))
                + String.format(Locale.US, "  (%d MIN)", t.minutes)); c.add(theme.key);
        String eco = t.km > 0.5f && t.liters > 0.01f ? String.format(Locale.US, "   %.1f KM/L", t.km / t.liters) : "";
        l.add(String.format(Locale.US, "%.1f KM   %s %.0f   MAX %.0f KM/%s   ~%.2f L%s%s%s", t.km, Lang.t("GEM", "AVG"), t.avg, t.max,
                Lang.t("U", "H"), t.liters, eco, t.cost >= 0 ? String.format(Locale.US, "   € %.2f", t.cost) : "",
                t.score >= 0 ? "   SCORE " + t.score : "")); c.add(CockpitView.TXT);
        l.add(Lang.t("VAN: ", "FROM: ") + WorkLog.address(this, t.sLat, t.sLon)); c.add(0xFF9A9AA6);
        l.add(Lang.t("NAAR: ", "TO: ") + WorkLog.address(this, t.eLat, t.eLon)); c.add(0xFF9A9AA6);
        if (t.odoStart > 0 && t.odoEnd > 0) { l.add(String.format(Locale.GERMANY, Lang.t("KM-STAND %,d - %,d", "ODOMETER %,d - %,d"), t.odoStart, t.odoEnd)); c.add(0xFF9A9AA6); }
        else if (store.odometer() < 0) { l.add(Lang.t("KM-STAND NIET INGESTELD: MENU > AUTO", "ODOMETER NOT SET: MENU > CAR")); c.add(CockpitView.WR); }
        int[] ci = new int[c.size()]; for (int i = 0; i < ci.length; i++) ci[i] = c.get(i);
        details.set(l.toArray(new String[0]), ci);
    }

    /** Eén rit in de lijst: display (gekozen) of donker, met lampje WERK. */
    private class Row extends View {
        TripLog.Trip t; boolean on;
        Row(Context c) { super(c); }
        void bind(TripLog.Trip t, boolean on) { this.t = t; this.on = on; invalidate(); }
        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), (int) (66 * d)); }
        @Override protected void onDraw(Canvas c) {
            if (t == null) return;
            c.save(); c.scale(d, d);
            float w = getWidth() / d;
            RectF r = new RectF(2, 3, w - 2, 61);
            if (on) pt.uiWindow(c, r, theme.key);
            boolean work = WorkLog.isWork(getContext(), t.id);
            pt.uiLamp(c, 22, 32, 8, CockpitView.WG, work);
            SimpleDateFormat df = new SimpleDateFormat("EEE d MMM  HH:mm", Lang.locale());
            pt.uiText(c, df.format(new Date(t.start)).replace(".", "").toUpperCase(Lang.locale()) + (work ? Lang.t("   WERK", "   WORK") : ""),
                    42, 27, 13, on ? theme.key : 0xFFB0B0BA, Paint.Align.LEFT);
            pt.uiText(c, String.format(Locale.US, "%.1f KM   %d MIN", t.km, t.minutes)
                    + (t.cost >= 0 ? String.format(Locale.US, "   € %.2f", t.cost) : "")
                    + (t.score >= 0 ? "   SCORE " + t.score : ""), 42, 48, 10, on ? CockpitView.TXT : 0xFF7A7A86, Paint.Align.LEFT);
            if (on) pt.uiGlass(c, r);
            c.restore();
        }
    }

    // ---------- routekaartje in retrostijl ----------

    private class RouteView extends View {
        private List<double[]> pts;
        private String msg;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        RouteView(Context c) { super(c); setBackground(new RetroUi.Panel(pt, RetroUi.Panel.WINDOW, theme.key, d)); }

        void set(List<double[]> points, String message) { pts = points; msg = message; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), m = 24 * d;
            // raster
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0xFF1B1B22);
            for (float x = m; x < w - m / 2; x += 40 * d) c.drawLine(x, m / 2, x, h - m / 2, p);
            for (float y = m; y < h - m / 2; y += 40 * d) c.drawLine(m / 2, y, w - m / 2, y, p);
            // scanlines
            p.setColor(0x14000000);
            for (float y = 0; y < h; y += 3 * d) c.drawLine(0, y, w, y, p);

            if (pts == null || pts.size() < 2) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF5F5F69);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(16 * d);
                p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                c.drawText(msg != null ? msg : Lang.t("GEEN ROUTE OPGESLAGEN", "NO ROUTE SAVED"), w / 2, h / 2, p);
                return;
            }
            double minLa = 90, maxLa = -90, minLo = 180, maxLo = -180;
            for (double[] q : pts) { minLa = Math.min(minLa, q[0]); maxLa = Math.max(maxLa, q[0]); minLo = Math.min(minLo, q[1]); maxLo = Math.max(maxLo, q[1]); }
            double k = Math.cos(Math.toRadians((minLa + maxLa) / 2));
            double spanX = Math.max((maxLo - minLo) * k, 1e-4), spanY = Math.max(maxLa - minLa, 1e-4);
            double sc = Math.min((w - 2 * m) / spanX, (h - 2 * m) / spanY);
            float ox = (float) ((w - spanX * sc) / 2), oy = (float) ((h - spanY * sc) / 2);
            Path path = new Path();
            for (int i = 0; i < pts.size(); i++) {
                float x = ox + (float) ((pts.get(i)[1] - minLo) * k * sc);
                float y = oy + (float) ((maxLa - pts.get(i)[0]) * sc);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(theme.speed);
            p.setStrokeWidth(8 * d);
            p.setMaskFilter(new BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL));
            c.drawPath(path, p);
            p.setMaskFilter(null);
            p.setStrokeWidth(2.5f * d);
            c.drawPath(path, p);
            double[] s = pts.get(0), e = pts.get(pts.size() - 1);
            dot(c, ox + (float) ((s[1] - minLo) * k * sc), oy + (float) ((maxLa - s[0]) * sc), CockpitView.WG, "S");
            dot(c, ox + (float) ((e[1] - minLo) * k * sc), oy + (float) ((maxLa - e[0]) * sc), CockpitView.WA, "P");
        }

        private void dot(Canvas c, float x, float y, int col, String t) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            p.setMaskFilter(new BlurMaskFilter(10 * d, BlurMaskFilter.Blur.NORMAL));
            c.drawCircle(x, y, 11 * d, p);
            p.setMaskFilter(null);
            c.drawCircle(x, y, 10 * d, p);
            p.setColor(0xFF0A0A0D);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(13 * d);
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            c.drawText(t, x, y + 4.5f * d, p);
        }
    }
}

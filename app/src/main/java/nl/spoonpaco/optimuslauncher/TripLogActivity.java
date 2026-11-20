package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Rittenlog: lijst met ritten, route van de gekozen rit, onderhoud, export naar USB. */
public class TripLogActivity extends Activity {
    private Store store;
    private Theme theme;
    private float d;
    private List<TripLog.Trip> trips = new ArrayList<>();
    private int sel = 0;
    private RouteView route;
    private TextView details, maint;
    private BaseAdapter adapter;
    private LinearLayout toggleRow;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        theme = Theme.byId(getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led"));
        d = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0A0D);
        root.setPadding(pad, pad, pad, pad);

        // ---- bovenbalk ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label(Lang.t("RITTENLOG", "TRIP LOG"), theme.key, 20));
        workTotal = label("", theme.trip, 12);
        titles.addView(workTotal);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toggleRow = new LinearLayout(this);
        top.addView(toggleRow);
        top.addView(choice("HIGH SCORES", false, v -> showHighscores()), btnLp(140));
        top.addView(choice(Lang.t("NAAR USB", "TO USB"), false, v -> export()), btnLp(130));
        top.addView(choice(Lang.t("SLUITEN", "CLOSE"), true, v -> finish()), btnLp(120));
        root.addView(top);

        // ---- lijst + route ----
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, (int) (10 * d), 0, 0);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setBackground(panel());
        list.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return trips.size(); }
            @Override public Object getItem(int i) { return trips.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                TextView t = cv instanceof TextView ? (TextView) cv : new TextView(TripLogActivity.this);
                TripLog.Trip tr = trips.get(i);
                SimpleDateFormat df = new SimpleDateFormat("EEE d MMM  HH:mm", Lang.locale());
                t.setText((WorkLog.isWork(TripLogActivity.this, tr.id) ? "● " + Lang.t("WERK", "WORK") + "  " : "")
                        + df.format(new Date(tr.start)).replace(".", "").toUpperCase(Lang.locale())
                        + String.format(Locale.US, "\n%.1f km · %d min", tr.km, tr.minutes)
                        + (tr.cost >= 0 ? String.format(Locale.US, " · € %.2f", tr.cost) : "")
                        + (tr.score >= 0 ? " · score " + tr.score : ""));
                t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                t.setTextSize(14);
                t.setLetterSpacing(0.06f);
                t.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
                boolean on = i == sel;
                t.setTextColor(on ? theme.key : 0xFF9A9AA6);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(6 * d);
                g.setColor(on ? ((theme.key & 0x00FFFFFF) | 0x22000000) : 0x00000000);
                if (on) g.setStroke((int) (2 * d), theme.key);
                t.setBackground(g);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> { sel = i; adapter.notifyDataSetChanged(); showTrip(); buildToggle(); });
        body.addView(list, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.38f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding((int) (12 * d), 0, 0, 0);
        route = new RouteView(this);
        right.addView(route, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        details = label("", 0xFFECEFF4, 15);
        details.setLetterSpacing(0.04f);
        details.setPadding((int) (4 * d), (int) (8 * d), 0, (int) (6 * d));
        right.addView(details);

        LinearLayout mrow = new LinearLayout(this);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        mrow.setGravity(Gravity.CENTER_VERTICAL);
        mrow.setBackground(panel());
        mrow.setPadding((int) (12 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
        maint = label("", theme.accent, 14);
        maint.setLetterSpacing(0.04f);
        mrow.addView(maint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mrow.addView(choice(Lang.t("HIER OPSLAAN", "SAVE HERE"), false, v -> saveHere()), btnLp(130));
        mrow.addView(choice(Lang.t("LOCATIES", "PLACES"), false, v -> showLocations()), btnLp(110));
        mrow.addView(choice(Lang.t("ONDERHOUD", "SERVICE"), false, v -> maintenanceDialog()), btnLp(120));
        right.addView(mrow);

        body.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        trips = TripLog.all(this);
        if (sel >= trips.size()) sel = 0;
        adapter.notifyDataSetChanged();
        showTrip();
        showMaintenance();
        buildToggle();
        updateWorkTotal();
        Geo.fillMissing(this);
    }

    /** Knop "WERKRIT" voor de gekozen rit: achteraf markeren of de markering weghalen. */
    private void buildToggle() {
        toggleRow.removeAllViews();
        if (trips.isEmpty()) return;
        final TripLog.Trip t = trips.get(sel);
        final boolean work = WorkLog.isWork(this, t.id);
        toggleRow.addView(choice(work ? "● " + Lang.t("WERKRIT", "WORK TRIP") : Lang.t("MARKEER ALS WERKRIT", "MARK AS WORK TRIP"), work, v -> {
            WorkLog.setWork(this, t.id, !work);
            Toast.makeText(this, !work ? Lang.t("Rit staat nu in spoonpaco-ritten", "Trip added to spoonpaco-ritten")
                    : Lang.t("Rit uit spoonpaco-ritten gehaald", "Trip removed from spoonpaco-ritten"), Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged(); buildToggle(); updateWorkTotal();
        }), btnLp(210));
    }

    private TextView workTotal;

    private void updateWorkTotal() {
        float km = WorkLog.kmThisYear(this);
        int y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        workTotal.setText(String.format(Locale.GERMANY, Lang.t("ZAKELIJK %d: %.0f KM = € %.2f", "BUSINESS %d: %.0f KM = € %.2f"),
                y, km, km * WorkLog.ratePerKm(y)));
    }

    private void showTrip() {
        if (trips.isEmpty()) {
            route.set(null, Lang.t("NOG GEEN RITTEN", "NO TRIPS YET"));
            details.setText(Lang.t("Een rit wordt opgeslagen zodra hij is afgesloten (contact uit of 5 min stilstand).",
                    "A trip is saved when it ends (ignition off or 5 min stopped)."));
            return;
        }
        TripLog.Trip t = trips.get(sel);
        route.set(TripLog.route(this, t.id), null);
        SimpleDateFormat day = new SimpleDateFormat("EEEE d MMMM", Lang.locale());
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        StringBuilder s = new StringBuilder();
        s.append(day.format(new Date(t.start))).append("   ").append(hm.format(new Date(t.start)))
                .append(" → ").append(hm.format(new Date(t.end))).append(String.format(Locale.US, "  (%d min)\n", t.minutes));
        s.append(String.format(Locale.US, "%.1f km   ·   %s %.0f   ·   max %.0f km/%s\n", t.km,
                Lang.t("gem", "avg"), t.avg, t.max, Lang.t("u", "h")));
        s.append(String.format(Locale.US, "~%.2f l", t.liters));
        if (t.km > 0.5f && t.liters > 0.01f) s.append(String.format(Locale.US, "   ·   %.1f km/l", t.km / t.liters));
        if (t.cost >= 0) s.append(String.format(Locale.US, "   ·   € %.2f", t.cost));
        if (t.score >= 0) s.append("   ·   SCORE ").append(t.score);
        s.append("\n").append(Lang.t("Van: ", "From: ")).append(WorkLog.address(this, t.sLat, t.sLon))
         .append("\n").append(Lang.t("Naar: ", "To: ")).append(WorkLog.address(this, t.eLat, t.eLon));
        if (t.odoStart > 0 && t.odoEnd > 0)
            s.append(String.format(Locale.US, "\n%s %,d → %,d", Lang.t("km-stand", "odometer"), t.odoStart, t.odoEnd));
        details.setText(s.toString());
    }

    private void showMaintenance() {
        long now = Clock.now();
        StringBuilder s = new StringBuilder();
        int odo = store.odometer();
        s.append(Lang.t("KM-STAND  ", "ODOMETER  ")).append(odo < 0 ? Lang.t("NIET INGESTELD — nodig voor werkritten", "NOT SET — needed for work trips") : String.format(Locale.US, "~%,d", odo));
        int k = store.oilKmLeft(), od = store.oilDaysLeft(now), a = store.apkDaysLeft(now);
        s.append(Lang.t("\nOLIE  ", "\nOIL  "));
        if (k == Integer.MIN_VALUE && od == Integer.MIN_VALUE) s.append(Lang.t("niet ingesteld", "not set"));
        else {
            if (k != Integer.MIN_VALUE) s.append(k >= 0 ? String.format(Locale.US, Lang.t("over %,d km", "in %,d km"), k)
                    : String.format(Locale.US, Lang.t("%,d km te laat!", "%,d km overdue!"), -k));
            if (od != Integer.MIN_VALUE) s.append(k != Integer.MIN_VALUE ? " / " : "").append(days(od));
        }
        s.append(Lang.t("\nAPK  ", "\nMOT  ")).append(a == Integer.MIN_VALUE ? Lang.t("niet ingesteld", "not set")
                : days(a) + "  (" + new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new Date(store.apkDate())) + ")");
        maint.setText(s.toString());
        maint.setTextColor(store.serviceDue(now) || odo < 0 ? CockpitView.WR : theme.accent);
    }

    private static String days(int n) {
        return n >= 0 ? String.format(Locale.US, Lang.t("over %d dagen", "in %d days"), n)
                : String.format(Locale.US, Lang.t("%d dagen te laat!", "%d days overdue!"), -n);
    }

    // ---------- vaste locaties ----------

    private void saveHere() {
        Locations.Loc l = Locations.addHere(this);
        if (l == null) { Toast.makeText(this, Lang.t("Nog geen GPS-positie", "No GPS position yet"), Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, Lang.t("Opgeslagen als ", "Saved as ") + l.label()
                + Lang.t(" — adres volgt zodra er internet is", " — address follows when online"), Toast.LENGTH_LONG).show();
        Geo.fillMissing(this);
        WorkLog.write(this);
        showTrip();
    }

    private void showLocations() {
        final List<Locations.Loc> locs = Locations.all(this);
        if (locs.isEmpty()) {
            Toast.makeText(this, Lang.t("Nog geen locaties. Druk ter plekke op HIER OPSLAAN.", "No places yet. Press SAVE HERE on site."), Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[locs.size()];
        for (int i = 0; i < locs.size(); i++) {
            Locations.Loc l = locs.get(i);
            items[i] = l.name + (l.addr.isEmpty() ? Lang.t("  (adres volgt)", "  (address pending)") : "  —  " + l.addr);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.t("Locaties (tik = naam wijzigen)", "Places (tap = rename)"))
                .setItems(items, (dd, i) -> renameLocation(locs, i))
                .setNegativeButton(Lang.t("Sluiten", "Close"), null)
                .show();
    }

    private void renameLocation(final List<Locations.Loc> locs, final int i) {
        final EditText e = new EditText(this);
        e.setText(locs.get(i).name);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.t("Naam (bv. Thuis, school, klant)", "Name (e.g. Home, school, client)"))
                .setView(e)
                .setPositiveButton(Lang.t("Opslaan", "Save"), (dd, w) -> {
                    locs.get(i).name = e.getText().toString().trim();
                    Locations.save(this, locs); WorkLog.write(this); showTrip();
                })
                .setNeutralButton(Lang.t("Verwijderen", "Delete"), (dd, w) -> {
                    locs.remove(i); Locations.save(this, locs); WorkLog.write(this); showTrip();
                })
                .setNegativeButton(Lang.t("Annuleren", "Cancel"), null)
                .show();
    }

    private void showHighscores() {
        RetroCard card = new RetroCard(this, theme);
        card.setSizeDp(460, 420);
        card.title = "HIGH SCORES";
        card.rows = TripLog.highscores(this);
        card.hint = Lang.t("TIK = OK", "TAP = OK");
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(card).create();
        card.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    private void export() {
        try {
            String p = TripLog.export(this);
            Toast.makeText(this, Lang.t("Rittenlog + spoonpaco-ritten opgeslagen in: ", "Trip log + spoonpaco-ritten saved in: ") + p, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, Lang.t("Exporteren mislukt: ", "Export failed: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- onderhoud instellen ----------

    private EditText focused;

    private void maintenanceDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        SimpleDateFormat df = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
        final EditText odo = field(Lang.t("Kilometerstand nu", "Odometer now"), store.odometer() > 0 ? String.valueOf(store.odometer()) : "");
        final EditText oilKm = field(Lang.t("Km-stand bij laatste olie", "Odometer at last oil change"), store.oilKm() > 0 ? String.valueOf(store.oilKm()) : "");
        final EditText oilDate = field(Lang.t("Datum laatste olie (dd-mm-jjjj)", "Date of last oil change (dd-mm-yyyy)"), store.oilDate() > 0 ? df.format(new Date(store.oilDate())) : "");
        final EditText apk = field(Lang.t("APK verloopt op (dd-mm-jjjj)", "MOT due (dd-mm-yyyy)"), store.apkDate() > 0 ? df.format(new Date(store.apkDate())) : "");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(odo); fields.addView(oilKm); fields.addView(oilDate); fields.addView(apk);
        row.addView(fields, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(keypad(), new LinearLayout.LayoutParams((int) (240 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row);
        focused = odo; odo.requestFocus();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.t("Onderhoud", "Service"))
                .setView(box)
                .setPositiveButton(Lang.t("Opslaan", "Save"), (dd, w) -> {
                    int o = num(odo), ok = num(oilKm);
                    long od = date(oilDate), ad = date(apk);
                    if (o > 0) store.setOdometer(o);
                    if (ok > 0 || od > 0) store.setOil(ok > 0 ? ok : store.oilKm(), od > 0 ? od : store.oilDate());
                    if (ad > 0) store.setApk(ad);
                    showMaintenance();
                })
                .setNegativeButton(Lang.t("Annuleren", "Cancel"), null)
                .show();
    }

    private EditText field(String hint, String val) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(val);
        e.setTextSize(16);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setKeyListener(DigitsKeyListener.getInstance("0123456789-"));
        e.setShowSoftInputOnFocus(false);
        e.setOnFocusChangeListener((v, has) -> { if (has) focused = (EditText) v; });
        return e;
    }

    private View keypad() {
        android.widget.GridLayout g = new android.widget.GridLayout(this);
        g.setColumnCount(3);
        g.setPadding((int) (10 * d), 0, 0, 0);
        for (String k : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "-", "0", "⌫"}) {
            android.widget.Button b = new android.widget.Button(this);
            b.setText(k);
            b.setTextSize(17);
            b.setTextColor(0xFFFFB21A);
            b.setBackgroundResource(R.drawable.key_bg);
            b.setPadding(0, 0, 0, 0);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
            lp.width = 0; lp.height = (int) (40 * d);
            lp.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
            b.setOnClickListener(v -> {
                EditText f = focused;
                if (f == null) return;
                android.text.Editable t = f.getText();
                if (k.equals("⌫")) { if (t.length() > 0) t.delete(t.length() - 1, t.length()); }
                else t.append(k);
                f.setSelection(f.getText().length());
            });
            g.addView(b, lp);
        }
        return g;
    }

    private static int num(EditText e) {
        try { return Integer.parseInt(e.getText().toString().replace("-", "").trim()); } catch (Exception x) { return -1; }
    }

    private static long date(EditText e) {
        try {
            String[] p = e.getText().toString().trim().split("-");
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(p[2]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[0]), 12, 0);
            return c.getTimeInMillis();
        } catch (Exception x) { return 0L; }
    }

    // ---------- stijl ----------

    private TextView label(String t, int col, float size) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        v.setLetterSpacing(0.15f);
        v.setTextSize(size);
        v.setTextColor(col);
        v.setShadowLayer(8f, 0, 0, (col & 0x00FFFFFF) | 0x66000000);
        return v;
    }

    private GradientDrawable panel() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF15151A, 0xFF0C0C0F});
        g.setCornerRadius(8 * d);
        g.setStroke((int) d, 0xFF2C2C35);
        return g;
    }

    private LinearLayout.LayoutParams btnLp(int wDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (wDp * d), (int) (44 * d));
        lp.setMargins((int) (5 * d), 0, 0, 0);
        return lp;
    }

    private View choice(String text, boolean on, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setLetterSpacing(0.12f);
        b.setTextSize(13);
        b.setTextColor(on ? theme.key : 0xFF8E8E9A);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(6 * d);
        g.setColor(on ? ((theme.key & 0x00FFFFFF) | 0x26000000) : 0xFF141418);
        g.setStroke((int) ((on ? 2 : 1) * d), on ? theme.key : 0xFF2C2C35);
        b.setBackground(g);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    // ---------- routekaartje in retrostijl ----------

    private class RouteView extends View {
        private List<double[]> pts;
        private String msg;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        RouteView(Context c) { super(c); setLayerType(LAYER_TYPE_SOFTWARE, null); setBackground(panel()); }

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

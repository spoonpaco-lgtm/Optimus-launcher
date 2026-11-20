package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Gedeelde vensters: onderhoud, vaste locaties, high scores. Gebruikt door het menu en het rittenscherm. */
public final class Dialogs {
    private Dialogs() { }

    // ---------- onderhoud ----------

    private static EditText focused;

    public static void maintenance(final Activity a, final Store store, final Runnable done) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        SimpleDateFormat df = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
        final EditText odo = field(a, Lang.t("Kilometerstand nu", "Odometer now"), store.odometer() > 0 ? String.valueOf(store.odometer()) : "");
        final EditText oilKm = field(a, Lang.t("Km-stand bij laatste olie", "Odometer at last oil change"), store.oilKm() > 0 ? String.valueOf(store.oilKm()) : "");
        final EditText oilDate = field(a, Lang.t("Datum laatste olie (dd-mm-jjjj)", "Date of last oil change (dd-mm-yyyy)"), store.oilDate() > 0 ? df.format(new Date(store.oilDate())) : "");
        final EditText apk = field(a, Lang.t("APK verloopt op (dd-mm-jjjj)", "MOT due (dd-mm-yyyy)"), store.apkDate() > 0 ? df.format(new Date(store.apkDate())) : "");
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout fields = new LinearLayout(a);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(odo); fields.addView(oilKm); fields.addView(oilDate); fields.addView(apk);
        row.addView(fields, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(keypad(a), new LinearLayout.LayoutParams((int) (240 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row);
        focused = odo; odo.requestFocus();
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.t("Onderhoud", "Service"))
                .setView(box)
                .setPositiveButton(Lang.t("Opslaan", "Save"), (dd, w) -> {
                    int o = num(odo), ok = num(oilKm);
                    long od = date(oilDate), ad = date(apk);
                    if (o > 0) store.setOdometer(o);
                    if (ok > 0 || od > 0) store.setOil(ok > 0 ? ok : store.oilKm(), od > 0 ? od : store.oilDate());
                    if (ad > 0) store.setApk(ad);
                    WorkLog.write(a);
                    if (done != null) done.run();
                })
                .setNegativeButton(Lang.t("Annuleren", "Cancel"), null)
                .show();
    }

    private static EditText field(Activity a, String hint, String val) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(val);
        e.setTextSize(16);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setKeyListener(DigitsKeyListener.getInstance("0123456789-"));
        e.setShowSoftInputOnFocus(false);
        e.setOnFocusChangeListener((v, has) -> { if (has) focused = (EditText) v; });
        return e;
    }

    private static View keypad(Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        android.widget.GridLayout g = new android.widget.GridLayout(a);
        g.setColumnCount(3);
        g.setPadding((int) (10 * d), 0, 0, 0);
        for (String k : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "-", "0", "⌫"}) {
            android.widget.Button b = new android.widget.Button(a);
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

    // ---------- vaste locaties ----------

    public static void saveHere(Activity a, Runnable done) {
        Locations.Loc l = Locations.addHere(a);
        if (l == null) { Toast.makeText(a, Lang.t("Nog geen GPS-positie", "No GPS position yet"), Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(a, Lang.t("Opgeslagen als ", "Saved as ") + l.label()
                + Lang.t(" — adres volgt zodra er internet is", " — address follows when online"), Toast.LENGTH_LONG).show();
        Geo.fillMissing(a);
        WorkLog.write(a);
        if (done != null) done.run();
    }

    public static void locationList(final Activity a, final Runnable done) {
        final List<Locations.Loc> locs = Locations.all(a);
        if (locs.isEmpty()) {
            Toast.makeText(a, Lang.t("Nog geen locaties. Druk ter plekke op HIER OPSLAAN.", "No places yet. Press SAVE HERE on site."), Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[locs.size()];
        for (int i = 0; i < locs.size(); i++) {
            Locations.Loc l = locs.get(i);
            items[i] = l.name + (l.addr.isEmpty() ? Lang.t("  (adres volgt)", "  (address pending)") : "  —  " + l.addr);
        }
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.t("Locaties (tik = naam wijzigen)", "Places (tap = rename)"))
                .setItems(items, (dd, i) -> renameLocation(a, i, done))
                .setNegativeButton(Lang.t("Sluiten", "Close"), null)
                .show();
    }

    public static void renameLocation(final Activity a, final int i, final Runnable done) {
        final List<Locations.Loc> locs = Locations.all(a);
        if (i < 0 || i >= locs.size()) return;
        final EditText e = new EditText(a);
        e.setText(locs.get(i).name);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.t("Naam (bv. Thuis, school, klant)", "Name (e.g. Home, school, client)"))
                .setMessage(locs.get(i).addr.isEmpty() ? Lang.t("Adres volgt zodra er internet is", "Address follows when online") : locs.get(i).addr)
                .setView(e)
                .setPositiveButton(Lang.t("Opslaan", "Save"), (dd, w) -> {
                    locs.get(i).name = e.getText().toString().trim();
                    Locations.save(a, locs); WorkLog.write(a);
                    if (done != null) done.run();
                })
                .setNeutralButton(Lang.t("Verwijderen", "Delete"), (dd, w) -> {
                    locs.remove(i); Locations.save(a, locs); WorkLog.write(a);
                    if (done != null) done.run();
                })
                .setNegativeButton(Lang.t("Annuleren", "Cancel"), null)
                .show();
    }

    // ---------- high scores ----------

    public static void highscores(Activity a, Theme theme) {
        RetroCard card = new RetroCard(a, theme);
        card.setSizeDp(460, 420);
        card.title = "HIGH SCORES";
        card.rows = TripLog.highscores(a);
        card.hint = Lang.t("TIK = OK", "TAP = OK");
        final AlertDialog dlg = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(card).create();
        card.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    public static void export(Activity a) {
        try {
            String p = TripLog.export(a);
            Toast.makeText(a, Lang.t("Rittenlog + spoonpaco-ritten opgeslagen in: ", "Trip log + spoonpaco-ritten saved in: ") + p, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, Lang.t("Exporteren mislukt: ", "Export failed: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

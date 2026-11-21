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
        SimpleDateFormat df = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
        InputForm.Field[] f = {
                new InputForm.Field(Lang.t("KILOMETERSTAND NU", "ODOMETER NOW"), store.odometer() > 0 ? String.valueOf(store.odometer()) : "", "km", false),
                new InputForm.Field(Lang.t("KM-STAND BIJ LAATSTE OLIE", "ODOMETER AT LAST OIL CHANGE"), store.oilKm() > 0 ? String.valueOf(store.oilKm()) : "", "km", false),
                new InputForm.Field(Lang.t("DATUM LAATSTE OLIE", "DATE OF LAST OIL CHANGE"), store.oilDate() > 0 ? df.format(new Date(store.oilDate())) : "", Lang.t("dd-mm-jjjj", "dd-mm-yyyy"), true),
                new InputForm.Field(Lang.t("APK VERLOOPT OP", "MOT DUE"), store.apkDate() > 0 ? df.format(new Date(store.apkDate())) : "", Lang.t("dd-mm-jjjj", "dd-mm-yyyy"), true)};
        InputForm.show(a, Lang.t("ONDERHOUD", "SERVICE"), Lang.t("TIK OP EEN VELD OM HET TE WIJZIGEN", "TAP A FIELD TO CHANGE IT"), f, null, false, Lang.t("WIS", "CLR"),
                (v, t) -> {
                    int o = num(v[0]), ok = num(v[1]);
                    long od = date(v[2]), ad = date(v[3]);
                    if (!v[2].isEmpty() && od <= 0) return Lang.t("DATUM OLIE KLOPT NIET (dd-mm-jjjj)", "OIL DATE IS INVALID (dd-mm-yyyy)");
                    if (!v[3].isEmpty() && ad <= 0) return Lang.t("APK-DATUM KLOPT NIET (dd-mm-jjjj)", "MOT DATE IS INVALID (dd-mm-yyyy)");
                    if (o > 0) store.setOdometer(o);
                    if (ok > 0 || od > 0) store.setOil(ok > 0 ? ok : store.oilKm(), od > 0 ? od : store.oilDate());
                    if (ad > 0) store.setApk(ad);
                    WorkLog.write(a);
                    if (done != null) done.run();
                    return null;
                });
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

    private static int num(String s) {
        try { return Integer.parseInt(s.replace("-", "").trim()); } catch (Exception x) { return -1; }
    }
    private static long date(EditText e) { return date(e.getText().toString()); }
    private static long date(String s) {
        try {
            String[] p = s.trim().split("-");
            int dd = Integer.parseInt(p[0]), mm = Integer.parseInt(p[1]), yy = Integer.parseInt(p[2]);
            if (dd < 1 || dd > 31 || mm < 1 || mm > 12 || yy < 2000 || yy > 2100) return 0L;
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(yy, mm - 1, dd, 12, 0);
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
        String[] names = new String[locs.size()], subs = new String[locs.size()];
        for (int i = 0; i < locs.size(); i++) {
            Locations.Loc l = locs.get(i);
            names[i] = l.name;
            subs[i] = l.addr.isEmpty() ? Lang.t("ADRES VOLGT ZODRA ER INTERNET IS", "ADDRESS FOLLOWS WHEN ONLINE") : l.addr;
        }
        InputForm.list(a, Lang.t("LOCATIES", "PLACES"), Lang.t("TIK OP EEN LOCATIE OM DE NAAM TE WIJZIGEN", "TAP A PLACE TO RENAME IT"), names, subs,
                i -> renameLocation(a, i, done));
    }

    public static void renameLocation(final Activity a, final int i, final Runnable done) {
        final List<Locations.Loc> locs = Locations.all(a);
        if (i < 0 || i >= locs.size()) return;
        final Locations.Loc l = locs.get(i);
        InputForm.Field[] f = {InputForm.Field.text(Lang.t("NAAM (BV. THUIS, SCHOOL, KLANT)", "NAME (E.G. HOME, SCHOOL, CLIENT)"), l.name, Lang.t("naam", "name"))};
        String sub = l.addr.isEmpty() ? Lang.t("ADRES VOLGT ZODRA ER INTERNET IS", "ADDRESS FOLLOWS WHEN ONLINE") : l.addr.toUpperCase(Locale.ROOT);
        InputForm.show(a, Lang.t("LOCATIE", "PLACE"), sub, f, null, false, "",
                (v, t) -> {
                    String n = v[0].trim();
                    if (n.isEmpty()) return Lang.t("VUL EEN NAAM IN", "ENTER A NAME");
                    l.name = n;
                    Locations.save(a, locs); WorkLog.write(a);
                    if (done != null) done.run();
                    return null;
                },
                Lang.t("VERWIJDEREN", "DELETE"),
                () -> { locs.remove(l); Locations.save(a, locs); WorkLog.write(a); if (done != null) done.run(); });
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

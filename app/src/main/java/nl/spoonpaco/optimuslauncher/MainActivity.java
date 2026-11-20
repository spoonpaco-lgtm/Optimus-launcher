package nl.spoonpaco.optimuslauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Optimus Launcher 0.0.2 — cockpit met snelheid, statusbalk, ritregel,
 * tanken en zelf-update.
 */
public class MainActivity extends Activity {

    private static final Locale NL = new Locale("nl");
    /** Android Auto-ontvanger van FlyAudio */
    private static final String PKG_AA = "com.carletter.car";
    private static final String PKG_RADIO = "com.flyaudio.media.radio";
    private static final int REQ_LOCATION = 1;

    private static boolean updateCheckedThisRun = false;

    private TextView clock, date, speed, trip;
    private TextView stNet, stGps, stPhone, stLog, stTank;
    private Store store;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", NL);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", Lang.locale());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        store = new Store(this);

        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        speed = findViewById(R.id.speed);
        trip = findViewById(R.id.trip);
        stNet = findViewById(R.id.st_net);
        stGps = findViewById(R.id.st_gps);
        stPhone = findViewById(R.id.st_phone);
        stLog = findViewById(R.id.st_log);
        stTank = findViewById(R.id.st_tank);
        cockpit = findViewById(R.id.cockpit);
        applyTheme(Theme.byId(getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led")));
        handler.postDelayed(() -> { if (scanner != null) scanner.sweepOnce(); }, 600);
        cockpit.listener = new CockpitView.Listener() {
            @Override public void onSpeedTap() { toggleOverlay(); }
            @Override public void onClockLongPress() { startActivity(new Intent(MainActivity.this, DebugActivity.class)); }
            @Override public void onTankTap() { showFuelDialog(); }
        };

        // AA-knop: normaal Android Auto; verbindt AA niet binnen 30 s, dan wordt hij "Offline navigeren"
        findViewById(R.id.btn_aa).setOnClickListener(v -> {
            if (aaKeyIsNav) launch("app.organicmaps", "Organic Maps");
            else launch(PKG_AA, "Android Auto");
        });

        // Contact aan (= tablet net opgestart): na 4 s vanzelf Android Auto openen, één keer per opstart
        if (!aaAutoStarted && android.os.SystemClock.elapsedRealtime() < 5L * 60L * 1000L && installed(PKG_AA)
                && getSharedPreferences("optimus", MODE_PRIVATE).getBoolean("startAA", false)) {
            aaAutoStarted = true;
            handler.postDelayed(() -> launch(PKG_AA, "Android Auto"), 4000L);
        }
        // Lang indrukken op Android Auto: afspelen/pauze zonder van scherm te wisselen
        findViewById(R.id.btn_aa).setOnLongClickListener(v -> {
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            toast(Lang.t("Afspelen / pauze", "Play / pause"));
            return true;
        });
        // EQ: geluidsinstellingen van FlyAudio
        findViewById(R.id.btn_eq).setOnClickListener(v -> {
            for (String pk : new String[]{"com.flyaudio.soundeffect", "com.flyaudio.service.dsp", "com.flyaudio.settings"}) {
                if (installed(pk)) { launch(pk, "EQ"); return; }
            }
            toast(Lang.t("Geen EQ-app gevonden", "No EQ app found"));
        });

        // Tik op de snelheid: zwevend snelheidsvenster aan/uit (boven Android Auto)
        speed.setOnClickListener(v -> toggleOverlay());
        wireApp(R.id.btn_radio, PKG_RADIO, getString(R.string.radio));

        // Verborgen debugscherm: klok lang ingedrukt houden
        clock.setOnLongClickListener(v -> {
            startActivity(new Intent(this, DebugActivity.class));
            return true;
        });

        findViewById(R.id.btn_apps).setOnClickListener(v -> showApps());
        findViewById(R.id.btn_fuel).setOnClickListener(v -> showFuelDialog());
        stTank.setOnClickListener(v -> showFuelDialog());
        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast(Lang.t("Instellingen niet beschikbaar", "Settings not available"));
            }
        });
        findViewById(R.id.btn_menu).setOnClickListener(v -> showMenu());
        // RIT: kort = registratie aan/uit, lang = rittenscherm
        findViewById(R.id.btn_trips).setOnClickListener(v -> {
            boolean on = !store.tripLogOn();
            store.setTripLogOn(on);
            toast(on ? Lang.t("Rittenregistratie AAN", "Trip logging ON") : Lang.t("Rittenregistratie UIT", "Trip logging OFF"));
            refresh();
        });
        findViewById(R.id.btn_trips).setOnLongClickListener(v -> {
            startActivity(new Intent(this, TripLogActivity.class));
            return true;
        });
        // Lang indrukken op een icoon = naam tonen (behalve AA: dat is afspelen/pauze)
        for (int id : new int[]{R.id.btn_radio, R.id.btn_eq, R.id.btn_fuel, R.id.btn_apps,
                R.id.btn_settings, R.id.btn_menu}) {
            findViewById(id).setOnLongClickListener(v -> { toast(String.valueOf(v.getContentDescription())); return true; });
        }

        ensureLocationAndStartTracker();

        if (!updateCheckedThisRun) {
            updateCheckedThisRun = true;
            handler.postDelayed(() -> Updater.check(this, false), 15000);
        }
        refresh();
    }

    // ---------- GPS-tracker ----------

    private void ensureLocationAndStartTracker() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            startTracker();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LOCATION);
        }
    }

    private void startTracker() {
        try {
            startForegroundService(new Intent(this, TrackerService.class));
        } catch (Exception e) {
            toast(Lang.t("Ritregistratie kon niet starten", "Trip logging could not start"));
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LOCATION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startTracker();
        } else if (req == REQ_LOCATION) {
            toast(Lang.t("Zonder locatie geen snelheid en ritgegevens", "No location means no speed or trip data"));
        }
    }

    // ---------- scherm verversen ----------

    private void toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast(Lang.t("Zet 'Weergeven over andere apps' aan voor Optimus Launcher", "Turn on 'Display over other apps' for Optimus Launcher"));
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast(Lang.t("Instelling niet gevonden", "Setting not found"));
            }
            return;
        }
        boolean on = !store.overlayEnabled();
        store.setOverlayEnabled(on);
        toast(on ? Lang.t("Zwevende snelheid aan (verschijnt buiten de launcher)", "Floating speed on (shows outside the launcher)") : Lang.t("Zwevende snelheid uit", "Floating speed off"));
    }

    private void refresh() {
        Date now = new Date(Clock.now());
        clock.setText(timeFmt.format(now));
        date.setText(capitalize(dateFmt.format(now)));

        boolean fresh = System.currentTimeMillis() - TrackerService.lastFixTime < 3000
                && TrackerService.speedKmh >= 0;
        speed.setText(fresh ? String.valueOf(Math.round(TrackerService.speedKmh)) : "--");

        refreshStatus(fresh);
        refreshTrip();
        refreshCockpit(fresh);
        maybeShowSummary();
        updateOfflineButton();
    }

    private void refreshStatus(boolean gpsFresh) {
        // Internet
        int net = netState();
        setStatus(stNet, net == 2 ? R.color.ok : net == 1 ? R.color.warn : R.color.bad,
                net == 1 ? "● Internet (geen data)" : "● Internet");

        // GPS
        boolean perm = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!TrackerService.gpsPresent) setStatus(stGps, R.color.bad, "● Geen GPS");
        else if (!perm) setStatus(stGps, R.color.bad, "● GPS (geen toestemming)");
        else if (gpsFresh) setStatus(stGps, R.color.ok, "● GPS");
        else setStatus(stGps, R.color.warn, "● GPS zoekt");

        // Telefoon via Bluetooth
        int bt = btState();
        setStatus(stPhone, bt == 2 ? R.color.ok : bt == 1 ? R.color.bad : R.color.off,
                bt == 0 ? "● Telefoon ?" : "● Telefoon");


        // Autolog
        int lg = CarLogger.state;
        setStatus(stLog, lg == 2 ? R.color.ok : lg == 1 ? R.color.warn : R.color.off,
                lg == 1 ? "● Autolog (alleen GPS)" : "● Autolog");

        // Tank
        int range = store.rangeKm();
        if (range < 0) {
            setStatus(stTank, R.color.warn, "⛽ tank ? (tik)");
        } else {
            int col = range < 60 ? R.color.bad : range < 120 ? R.color.warn : R.color.fg_dim;
            setStatus(stTank, col, String.format(NL, "⛽ ~%d km  (%.0f l)", range, store.tankLiters()));
        }
    }

    // ---------- menu + thema's ----------

    private Theme theme = Theme.LED;

    private static final int[] KEY_IDS = {R.id.btn_aa, R.id.btn_radio, R.id.btn_eq, R.id.btn_fuel,
            R.id.btn_trips, R.id.btn_apps, R.id.btn_settings, R.id.btn_menu};
    private static final String[] KEY_TEXT = {"AUTO", "FM", "EQ", "TANK", "RIT", "APPS", "SET", "MENU"};
    private static final int[] KEY_ICON = {R.drawable.ic_aa, R.drawable.ic_radio, R.drawable.ic_eq, R.drawable.ic_fuel,
            R.drawable.ic_trip, R.drawable.ic_apps, R.drawable.ic_settings, R.drawable.ic_menu};

    private ScannerView scanner;

    private void applyTheme(Theme t) {
        theme = t;
        cockpit.setTheme(t);
        if (scanner == null) scanner = findViewById(R.id.scanner);
        if (scanner != null) scanner.setTheme(t);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < KEY_IDS.length; i++) {
            android.widget.ImageButton b = findViewById(KEY_IDS[i]);
            b.setBackground(keyBackground(t.key, d));
            if (t.leds) {
                b.setImageTintList(null);
                String label = (KEY_IDS[i] == R.id.btn_aa && aaKeyIsNav) ? "NAV" : KEY_TEXT[i];
                b.setImageDrawable(new LedTextDrawable(label, t.key, 3.2f * d));
            } else {
                b.setImageResource(KEY_ICON[i] == R.drawable.ic_aa && aaKeyIsNav ? R.drawable.ic_nav : KEY_ICON[i]);
                b.setImageTintList(android.content.res.ColorStateList.valueOf(t.key));
            }
        }
    }

    /** Retro drukknop in de themakleur: glanzende bovenkant, schaduw, gloeiende streep onderaan. */
    private android.graphics.drawable.Drawable keyBackground(int col, float d) {
        android.graphics.drawable.GradientDrawable shadow = new android.graphics.drawable.GradientDrawable();
        shadow.setCornerRadius(6 * d); shadow.setColor(0xFF000000);
        android.graphics.drawable.GradientDrawable body = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2A2A31, 0xFF16161B, 0xFF0B0B0E});
        body.setCornerRadius(6 * d); body.setStroke((int) d, 0xFF000000);
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setCornerRadius(2 * d); line.setColor(col);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{shadow, body, line});
        ld.setLayerInset(0, 0, (int) (3 * d), 0, 0);
        ld.setLayerInset(1, 0, 0, 0, (int) (3 * d));
        ld.setLayerGravity(2, android.view.Gravity.BOTTOM | android.view.Gravity.FILL_HORIZONTAL);
        ld.setLayerHeight(2, (int) (2 * d));
        ld.setLayerInsetLeft(2, (int) (22 * d)); ld.setLayerInsetRight(2, (int) (22 * d));
        ld.setLayerInsetBottom(2, (int) (11 * d));
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1A1F, 0xFF08080A});
        pressed.setCornerRadius(6 * d); pressed.setStroke((int) (2 * d), col);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, ld);
        return sl;
    }

    /** Menu: thema's, update zoeken, debug. */
    private static String themeDescEn(String id) {
        switch (id) {
            case "vfd": return "Turquoise fluorescent, like digital 80s dashboards";
            case "neon": return "Magenta and cyan neon on deep purple";
            case "old": return "All made of lamps: dot-matrix digits and text";
            default: return "Red, amber and green LED, like the sketch";
        }
    }

    /** Taal gewisseld: datum, toetsen en cockpit opnieuw opbouwen. */
    private void languageChanged() {
        recreate();
    }

    private void showMenu() {
        final float d = getResources().getDisplayMetrics().density;
        final AlertDialog[] dlg = new AlertDialog[1];
        final Runnable close = () -> { if (dlg[0] != null) dlg[0].dismiss(); };
        final android.content.SharedPreferences op = getSharedPreferences("optimus", MODE_PRIVATE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (16 * d);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(0xFF0A0A0D);

        // ---- links: thema's in een 2x2-raster ----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(menuLabel(Lang.t("THEMA", "THEME"), theme.accent, d));
        LinearLayout gridRow = null;
        for (int i = 0; i < Theme.ALL.length; i++) {
            final Theme t = Theme.ALL[i];
            if (i % 2 == 0) {
                gridRow = new LinearLayout(this);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                left.addView(gridRow);
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1A1A20, 0xFF0B0B0E});
            bg.setCornerRadius(8 * d);
            bg.setStroke((int) ((t == theme ? 3 : 1) * d), t == theme ? t.key : 0xFF2C2C35);
            card.setBackground(bg);
            LinearLayout sw = new LinearLayout(this);
            sw.setOrientation(LinearLayout.HORIZONTAL);
            for (int c : new int[]{t.speed, t.accent, t.trip, t.key}) {
                View v = new View(this);
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(2 * d); g.setColor(c);
                v.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (10 * d), 1f);
                lp.setMargins((int) (2 * d), 0, (int) (2 * d), 0);
                sw.addView(v, lp);
            }
            card.addView(sw);
            TextView name = menuLabel(t.name.toUpperCase(), t.key, d);
            name.setTextSize(14);
            name.setPadding((int) (2 * d), (int) (8 * d), 0, (int) (2 * d));
            card.addView(name);
            TextView desc = new TextView(this);
            desc.setText(Lang.en ? themeDescEn(t.id) : t.desc);
            desc.setTextColor(0xFF8E8E9A);
            desc.setTextSize(11);
            desc.setMaxLines(2);
            card.addView(desc);
            card.setOnClickListener(v -> {
                op.edit().putString("theme", t.id).apply();
                applyTheme(t);
                close.run();
                toast(Lang.t("Thema: ", "Theme: ") + t.name);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (96 * d), 1f);
            lp.setMargins((int) (5 * d), (int) (5 * d), (int) (5 * d), (int) (5 * d));
            gridRow.addView(card, lp);
        }

        // ---- rechts: instellingen als keuzeknoppen ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding((int) (16 * d), 0, 0, 0);

        right.addView(menuLabel(Lang.t("TAAL", "LANGUAGE"), theme.accent, d));
        right.addView(choiceRow(d,
                choice("NEDERLANDS", !Lang.en, d, v -> { close.run(); if (Lang.en) { Lang.set(this, false); languageChanged(); } }),
                choice("ENGLISH", Lang.en, d, v -> { close.run(); if (!Lang.en) { Lang.set(this, true); languageChanged(); } })));

        boolean startAA = op.getBoolean("startAA", false);
        right.addView(menuLabel(Lang.t("BIJ CONTACT AAN", "ON IGNITION"), theme.accent, d));
        right.addView(choiceRow(d,
                choice("LAUNCHER", !startAA, d, v -> { op.edit().putBoolean("startAA", false).apply(); close.run();
                    toast(Lang.t("Bij contact aan: launcher", "On ignition: launcher")); }),
                choice("ANDROID AUTO", startAA, d, v -> { op.edit().putBoolean("startAA", true).apply(); close.run();
                    toast(Lang.t("Bij contact aan: Android Auto", "On ignition: Android Auto")); })));

        right.addView(menuLabel(Lang.t("SYSTEEM", "SYSTEM"), theme.accent, d));
        right.addView(choiceRow(d,
                choice(Lang.t("UPDATE ZOEKEN", "CHECK UPDATE"), false, d, v -> { close.run(); Updater.check(this, true); }),
                choice("DEBUG", false, d, v -> { close.run(); startActivity(new Intent(this, DebugActivity.class)); })));

        View spacer = new View(this);
        right.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView ver = new TextView(this);
        String vn = "";
        try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        ver.setText("OPTIMUS LAUNCHER " + vn);
        ver.setTextColor(0xFF5A5A66);
        ver.setTextSize(11);
        ver.setLetterSpacing(0.15f);
        ver.setPadding((int) (6 * d), (int) (8 * d), 0, (int) (4 * d));
        right.addView(ver);
        right.addView(choiceRow(d, choice(Lang.t("SLUITEN", "CLOSE"), true, d, v -> close.run())));

        box.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f));
        box.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // vangnet: past het scherm toch niet, dan kun je scrollen
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        dlg[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(scroll).create();
        dlg[0].show();
        if (dlg[0].getWindow() != null) dlg[0].getWindow().setLayout((int) (940 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** Rij van gelijke keuzeknoppen. */
    private LinearLayout choiceRow(float d, View... items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (48 * d), 1f);
            lp.setMargins((int) (5 * d), (int) (4 * d), (int) (5 * d), (int) (4 * d));
            row.addView(v, lp);
        }
        return row;
    }

    /** Keuzeknop: gekozen = gloeiende rand in de toetskleur, anders gedimd. */
    private View choice(String label, boolean on, float d, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(android.view.Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        b.setLetterSpacing(0.15f);
        b.setTextSize(13);
        b.setTextColor(on ? theme.key : 0xFF8E8E9A);
        if (on) b.setShadowLayer(8f, 0, 0, (theme.key & 0x00FFFFFF) | 0x99000000);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(6 * d);
        g.setColor(on ? ((theme.key & 0x00FFFFFF) | 0x26000000) : 0xFF141418);
        g.setStroke((int) ((on ? 2 : 1) * d), on ? theme.key : 0xFF2C2C35);
        b.setBackground(g);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private TextView menuLabel(String t, int col, float d) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
        v.setLetterSpacing(0.2f);
        v.setTextSize(13);
        v.setTextColor(col);
        v.setShadowLayer(8f, 0, 0, (col & 0x00FFFFFF) | 0x99000000);
        v.setPadding((int) (6 * d), (int) (12 * d), 0, (int) (6 * d));
        return v;
    }

    private View menuButton(int icon, String label, float d, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackground(keyBackground(theme.key, d));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(theme.key));
        b.addView(iv, new LinearLayout.LayoutParams((int) (32 * d), (int) (32 * d)));
        TextView tv = menuLabel(label, theme.key, d);
        tv.setPadding((int) (10 * d), 0, 0, 0);
        b.addView(tv);
        b.setClickable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (72 * d), 1f);
        b.setLayoutParams(lp);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
        wrap.addView(b, new android.widget.FrameLayout.LayoutParams(-1, -1));
        return wrap;
    }

    private void refreshCockpit(boolean fresh) {
        CockpitView c = cockpit;
        c.now = Clock.now();
        c.speed = fresh ? Math.round(TrackerService.speedKmh) : -1;
        c.sats = TrackerService.satsUsed;
        c.heading = TrackerService.bearing;
        c.tripKm = store.tripKm();
        c.tripAvg = store.tripAvgKmh();
        c.tripMax = store.tripMaxKmh();
        c.tripL = store.tripLiters();
        c.tripCost = store.tripCost();
        c.tripMinutes = Math.round(store.tripMovingH() * 60f);
        c.l100 = c.tripKm >= 0.5f ? c.tripL / c.tripKm * 100f : -1f;
        c.rangeKm = store.rangeKm();
        c.tankL = store.tankLiters();
        c.volts = CarSignals.volts > 0 && System.currentTimeMillis() - CarSignals.voltsTime < 60_000L ? CarSignals.volts : -1f;
        c.lContact = CarSignals.contact == 1 || (CarSignals.contact < 0 && c.volts > 0);
        c.lMotor = CarSignals.engineRunning();
        c.lLicht = CarSignals.lights == 1;
        c.lTrips = store.tripLogOn();
        c.accuWarn = BatteryHealth.level;
        c.lAuto = aaSeenRecently;
        if (scanner != null) scanner.update(CarSignals.mcu04 == 1, Math.max(BatteryHealth.level, store.serviceDue(Clock.now()) ? 1 : 0));
        c.lLog = CarLogger.state == 2;
        c.invalidate();
    }

    private void refreshTrip() {
        float km = store.tripKm();
        if (km < 0.05f) {
            trip.setText(Lang.t("Rit: nog niet gereden", "Trip: not driven yet"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(NL, "Rit %.1f km  ·  gem %.0f km/u  ·  max %.0f km/u  ·  ~%.1f l",
                km, store.tripAvgKmh(), store.tripMaxKmh(), store.tripLiters()));
        float cost = store.tripCost();
        if (cost >= 0) sb.append(String.format(NL, "  ·  € %.2f", cost));
        trip.setText(sb.toString());
    }

    private void setStatus(TextView v, int colorRes, String text) {
        v.setText(text);
        v.setTextColor(getColor(colorRes));
    }

    /** 0 = geen netwerk, 1 = verbonden zonder internet, 2 = internet ok. */
    private int netState() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return 0;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) return 0;
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return 2;
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 0 = onbekend (geen Android-Bluetooth), 1 = niet verbonden, 2 = verbonden. */
    private int btState() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) return 0;
            // De head unit is de "handsfree-kant": telefoon komt binnen via
            // HEADSET_CLIENT (16) / A2DP_SINK (11). Ook de gewone profielen checken.
            int[] profiles = {16, 11, 1, 2};
            for (int pr : profiles) {
                try {
                    if (ad.getProfileConnectionState(pr) == BluetoothAdapter.STATE_CONNECTED) return 2;
                } catch (Exception ignored) { }
            }
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded == null) return 0;
            Method isConnected = BluetoothDevice.class.getMethod("isConnected");
            for (BluetoothDevice d : bonded) {
                Object r = isConnected.invoke(d);
                if (r instanceof Boolean && (Boolean) r) return 2;
            }
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- knoppen ----------

    private void wireApp(int viewId, final String pkg, final String naam) {
        findViewById(viewId).setOnClickListener(v -> launch(pkg, naam));
    }

    // ---------- offline navigeren ----------

    private long lastArpCheck = 0L;
    private boolean aaSeen = false;
    private boolean aaSeenRecently = false;
    private boolean aaKeyIsNav = false;

    /** Knop "Offline navigeren" alleen tonen als Android Auto 30 s na opstart/wakker niet verbonden is. */
    private void updateOfflineButton() {
        android.widget.ImageButton b = findViewById(R.id.btn_aa);
        long now = android.os.SystemClock.elapsedRealtime();
        long since = now - TrackerService.lastWakeElapsed;
        if (since < 30_000L || TrackerService.lastWakeElapsed == 0L) aaSeen = false;
        if (now - lastArpCheck > 5_000L) {
            lastArpCheck = now;
            aaSeenRecently = aaClientPresent();
            if (aaSeenRecently) aaSeen = true;
        }
        boolean nav = since >= 30_000L && !aaSeen && installed("app.organicmaps");
        if (nav != aaKeyIsNav) {
            aaKeyIsNav = nav;
            if (theme.leds) b.setImageDrawable(new LedTextDrawable(nav ? "NAV" : "AUTO", theme.key, 3.2f * getResources().getDisplayMetrics().density));
            else b.setImageResource(nav ? R.drawable.ic_nav : R.drawable.ic_aa);
            b.setContentDescription(nav ? "Offline navigeren" : "Android Auto");
        }
    }

    /** Telefoon verbonden met de Android Auto-hotspot van de tablet (192.168.43.x in ARP-tabel)? */
    private static boolean aaClientPresent() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("192.168.43.") && !l.startsWith("192.168.43.1 ") && l.contains("0x2")) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private boolean installed(String pkg) {
        return getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    private void mediaKey(int code) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            long t = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0));
            CarLogger.note("mediatoets " + code + " verstuurd");
        } catch (Exception e) {
            toast(Lang.t("Mediatoets mislukt: ", "Media key failed: ") + e.getMessage());
        }
    }

    // ---------- ritsamenvatting ----------

    private AlertDialog summaryDialog;

    private void maybeShowSummary() {
        if (!store.summaryPending() || (summaryDialog != null && summaryDialog.isShowing())) return;
        RetroCard card = new RetroCard(this, theme);
        card.setSizeDp(460, 440);
        card.title = Lang.t("RIT KLAAR", "TRIP DONE");
        card.rows = store.summaryRows();
        card.hint = Lang.t("TIK = OK", "TAP = OK");
        final String rec = store.newRecord();
        if (!rec.isEmpty()) {
            // nieuw record: hint knippert "NEW HIGH SCORE"
            final String okText = card.hint;
            final String hs = "NEW HIGH SCORE  " + rec;
            Runnable blink = new Runnable() {
                boolean on;
                @Override public void run() {
                    if (summaryDialog == null || !summaryDialog.isShowing()) return;
                    on = !on;
                    card.hint = on ? hs : okText;
                    card.hintCol = on ? CockpitView.WA : 0;
                    card.invalidate();
                    handler.postDelayed(this, 600);
                }
            };
            handler.postDelayed(blink, 300);
        }
        summaryDialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(card)
                .setOnCancelListener(d -> store.clearSummary())
                .create();
        card.setOnClickListener(v -> { store.clearSummary(); summaryDialog.dismiss(); });
        summaryDialog.show();
        if (summaryDialog.getWindow() != null) {
            summaryDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
    }

    private void launch(String pkg, String naam) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else {
            toast(naam + Lang.t(" is niet geïnstalleerd", " is not installed"));
        }
    }

    private void showApps() {
        final PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = new ArrayList<>(pm.queryIntentActivities(main, 0));
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<android.graphics.drawable.Drawable> icons = new ArrayList<>();
        for (ResolveInfo ri : list) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || pkgs.contains(p)) continue;
            pkgs.add(p);
            labels.add(ri.loadLabel(pm).toString());
            icons.add(ri.loadIcon(pm));
        }
        final float d = getResources().getDisplayMetrics().density;
        android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing((int) (12 * d));
        grid.setHorizontalSpacing((int) (12 * d));
        grid.setPadding((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
        grid.setBackgroundColor(0xFF040405);
        final AlertDialog[] dlg = new AlertDialog[1];
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return pkgs.size(); }
            @Override public Object getItem(int i) { return pkgs.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View conv, android.view.ViewGroup parent) {
                LinearLayout cell;
                android.widget.ImageView iv;
                TextView tv;
                if (conv instanceof LinearLayout) {
                    cell = (LinearLayout) conv;
                    iv = (android.widget.ImageView) cell.getChildAt(0);
                    tv = (TextView) cell.getChildAt(1);
                } else {
                    cell = new LinearLayout(MainActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
                    iv = new android.widget.ImageView(MainActivity.this);
                    int sz = (int) (72 * d);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    tv = new TextView(MainActivity.this);
                    tv.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
                    tv.setTextSize(11);
                    tv.setLetterSpacing(0.15f);
                    tv.setTextColor(0xFFFFB21A);
                    tv.setShadowLayer(8f, 0f, 0f, 0x99FFB21A);
                    tv.setAllCaps(true);
                    tv.setSingleLine(true);
                    tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    tv.setGravity(android.view.Gravity.CENTER);
                    tv.setPadding((int) (2 * d), (int) (6 * d), (int) (2 * d), 0);
                    cell.addView(iv);
                    cell.addView(tv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                iv.setImageDrawable(icons.get(i));
                tv.setText(labels.get(i));
                cell.setContentDescription(labels.get(i));
                return cell;
            }
        });
        grid.setOnItemClickListener((p, v, i, id) -> {
            if (dlg[0] != null) dlg[0].dismiss();
            launch(pkgs.get(i), labels.get(i));
        });
        grid.setOnItemLongClickListener((p, v, i, id) -> { toast(labels.get(i)); return true; });
        dlg[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                .setView(grid)
                .create();
        dlg[0].show();
    }

    private void showFuelDialog() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText liters = numberField(Lang.t("Liters getankt", "Litres filled"));
        final EditText price = numberField(Lang.t("Prijs per liter (€)", "Price per litre (€)"));
        if (store.price() > 0) price.setText(String.format(java.util.Locale.US, "%.3f", store.price()));
        price.setOnClickListener(v -> { price.setText(""); });
        final CheckBox full = new CheckBox(this);
        full.setText(Lang.t("Tank is nu vol (57 l)", "Tank is now full (57 L)"));
        full.setTextSize(15);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(liters);
        fields.addView(price);
        fields.addView(full);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(fields, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(keypad(liters, price), new LinearLayout.LayoutParams((int) (230 * getResources().getDisplayMetrics().density), LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(row);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Lang.t("Tanken", "Refuel"))
                .setView(box)
                .setPositiveButton(Lang.t("Opslaan", "Save"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        float l = parse(liters.getText().toString());
                        float pr = parse(price.getText().toString());
                        if (!full.isChecked() && l <= 0) {
                            toast(Lang.t("Vul liters in, of vink 'vol' aan", "Enter litres, or tick 'full'"));
                            return;
                        }
                        store.fill(Math.max(0f, l), pr, full.isChecked());
                        toast(full.isChecked() ? Lang.t("Tank vol opgeslagen", "Full tank saved") : Lang.t("Tankbeurt opgeslagen", "Refuel saved"));
                        refresh();
                    }
                })
                .setNegativeButton(Lang.t("Annuleren", "Cancel"), null)
                .show();
    }

    private EditText numberField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(16);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setKeyListener(DigitsKeyListener.getInstance("0123456789.,"));
        e.setShowSoftInputOnFocus(false); // eigen toetsenblok met punt
        e.setOnFocusChangeListener((v, has) -> { if (has) focusedField = (EditText) v; });
        return e;
    }

    private EditText focusedField;

    /** Groot numeriek toetsenblok (1-9, punt, 0, wissen): werkt altijd, ook met punt, en is goed te raken in de auto. */
    private View keypad(EditText first, EditText second) {
        focusedField = first;
        first.requestFocus();
        float d = getResources().getDisplayMetrics().density;
        android.widget.GridLayout g = new android.widget.GridLayout(this);
        g.setColumnCount(3);
        g.setPadding((int) (10 * d), 0, 0, 0);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"};
        for (String k : keys) {
            android.widget.Button b = new android.widget.Button(this);
            b.setText(k);
            b.setTextSize(17);
            b.setPadding(0, 0, 0, 0);
            b.setTextColor(0xFFFFB21A);
            b.setBackgroundResource(R.drawable.key_bg);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = (int) (38 * d);
            lp.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
            b.setOnClickListener(v -> {
                EditText f = focusedField != null ? focusedField : first;
                android.text.Editable t = f.getText();
                if (k.equals("⌫")) {
                    if (t.length() > 0) t.delete(t.length() - 1, t.length());
                } else if (k.equals(".")) {
                    if (t.toString().indexOf('.') < 0 && t.toString().indexOf(',') < 0) t.append(t.length() == 0 ? "0." : ".");
                } else {
                    t.append(k);
                }
                f.setSelection(f.getText().length());
            });
            g.addView(b, lp);
        }
        return g;
    }

    private static float parse(String s) {
        try { return Float.parseFloat(s.trim().replace(',', '.')); }
        catch (Exception e) { return -1f; }
    }

    // ---------- overig ----------

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(NL) + s.substring(1);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static boolean aaAutoStarted = false;
    private CockpitView cockpit;

    /** Staat de launcher op het scherm? (dan geen zwevend venster nodig) */
    public static volatile boolean visible = false;

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // terug na het opstartfilmpje of de HOME-knop: één sweep
        if (scanner != null) scanner.sweepOnce();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        visible = false;
        handler.removeCallbacks(tick);
    }

    @Override public void onBackPressed() {
        // Home-scherm: terug-knop doet niets.
    }
}

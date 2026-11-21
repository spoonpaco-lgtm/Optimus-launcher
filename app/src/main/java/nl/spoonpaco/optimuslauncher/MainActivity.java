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
        instance = this;
        handleMenuExtra(getIntent());
        cockpit.listener = new CockpitView.Listener() {
            @Override public void onSpeedTap() { toggleOverlay(); }
            @Override public void onMusicTap() { startActivity(new Intent(MainActivity.this, MusicActivity.class)); }
            @Override public void onMusicKey(int k) {
                MusicPlayer mp = MusicPlayer.get(MainActivity.this);
                if (mp.current() != null && (mp.isPlaying() || !BtMedia.playing)) {
                    if (k == 0) mp.previous(); else if (k == 1) mp.toggle(); else mp.next();
                } else BtMedia.control(k);
                CarLogger.note("muziek: bandknop " + (k == 0 ? "vorige" : k == 1 ? "speel/pauze" : "volgende"));
                cockpit.postDelayed(() -> refreshCockpit(false), 300);
            }
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
        // RIT: kort = deze rit is een werkrit (gaat na de rit vanzelf uit), lang = rittenscherm
        findViewById(R.id.btn_trips).setOnClickListener(v -> {
            boolean on = !WorkLog.flag(this);
            WorkLog.setFlag(this, on);
            toast(on ? Lang.t("WERKRIT: deze rit gaat naar spoonpaco-ritten", "WORK TRIP: this trip goes to spoonpaco-ritten")
                     : Lang.t("Werkrit uit", "Work trip off"));
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

    private android.app.Dialog menuDlg;
    private MenuView menuView;

    /** Menu met tabbladen, getekend in cockpitstijl (MenuView). */
    private void showMenu() {
        final android.content.SharedPreferences op = getSharedPreferences("optimus", MODE_PRIVATE);
        final MainActivity act = this;
        menuView = new MenuView(this, theme, store, new MenuView.Host() {
            @Override public void close() { if (menuDlg != null) menuDlg.dismiss(); }
            @Override public void fuel() { close(); showFuelDialog(); }
            @Override public void tripLog() { close(); startActivity(new Intent(act, TripLogActivity.class)); }
            @Override public void highscores() { Dialogs.highscores(act, theme); }
            @Override public void export() { Dialogs.export(act); }
            @Override public void saveHere() { Dialogs.saveHere(act, () -> menuView.invalidate()); }
            @Override public void location(int i) { Dialogs.renameLocation(act, i, () -> menuView.invalidate()); }
            @Override public void maintenance() { Dialogs.maintenance(act, store, () -> menuView.invalidate()); }
            @Override public void pickTheme(Theme t) {
                op.edit().putString("theme", t.id).apply();
                applyTheme(t);
                menuView.setTheme(t);
            }
            @Override public void setLanguage(boolean en) {
                if (en == Lang.en) return;
                Lang.set(act, en);
                close();
                languageChanged();
            }
            @Override public void setStartAA(boolean aa) { op.edit().putBoolean("startAA", aa).apply(); }
            @Override public void setOverlay(boolean on) { store.setOverlayEnabled(on); }
            @Override public void speakerTest() { SpeakerTest.start(MainActivity.this, null); }
            @Override public boolean musicResume() { return MusicPlayer.get(MainActivity.this).resumeEnabled(); }
            @Override public void setMusicResume(boolean on) { MusicPlayer.get(MainActivity.this).setResumeEnabled(on); }
            @Override public void update() { close(); Updater.check(act, true); }
            @Override public void debug() { close(); startActivity(new Intent(act, DebugActivity.class)); }
            @Override public boolean startAA() { return op.getBoolean("startAA", false); }
            @Override public boolean overlay() { return store.overlayEnabled(); }
            @Override public String version() {
                try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
            }
            @Override public String status() {
                int net = netState();
                return Lang.t("VERSIE ", "VERSION ") + version() + "\n"
                        + "INTERNET: " + (net == 2 ? "OK" : net == 1 ? Lang.t("VERBONDEN, GEEN INTERNET", "CONNECTED, NO INTERNET") : Lang.t("GEEN", "NONE")) + "\n"
                        + "GPS: " + TrackerService.satsUsed + Lang.t(" SATELLIETEN", " SATELLITES") + "\n"
                        + "AUTOLOG: " + (CarLogger.state == 2 ? Lang.t("ACTIEF", "ACTIVE") : CarLogger.state == 1 ? Lang.t("ALLEEN GPS", "GPS ONLY") : Lang.t("UIT", "OFF")) + "\n"
                        + Lang.t("ACCU: ", "BATTERY: ") + (CarSignals.volts > 0 ? String.format(java.util.Locale.US, "%.2f V", CarSignals.volts) : "--");
            }
        });
        menuDlg = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        menuDlg.setContentView(menuView);
        menuDlg.show();
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
        c.lTrips = WorkLog.flag(this);
        c.accuWarn = BatteryHealth.level;
        c.lAuto = aaSeenRecently;
        // nu speelt: eigen speler (USB) of telefoon via Bluetooth
        MusicPlayer mpl = MusicPlayer.get(this);
        MusicPlayer.Track nt = mpl.current();
        if (nt != null && (mpl.isPlaying() || !BtMedia.playing)) {
            c.npShow = true; c.npPlaying = mpl.isPlaying(); c.npSrc = "USB"; c.npTitle = nt.label(); c.npArtist = nt.artist;
            c.npSub = (nt.album.isEmpty() ? "" : nt.album + "   ") + (mpl.queueIndex() + 1) + "/" + mpl.queueSize();
            int du = mpl.durationMs(), po = mpl.positionMs();
            c.npFrac = du > 0 ? po / (float) du : 0; c.npTime = MusicPlayer.time(po); c.npTotal = MusicPlayer.time(du);
        } else if (BtMedia.playing && BtMedia.title != null && !BtMedia.title.isEmpty()) {
            c.npShow = true; c.npPlaying = true; c.npSrc = "BT"; c.npTitle = BtMedia.title; c.npArtist = BtMedia.artist == null ? "" : BtMedia.artist;
            c.npSub = BtMedia.album == null ? "" : BtMedia.album; c.npFrac = 0; c.npTime = ""; c.npTotal = "";
        } else c.npShow = false;
        if (scanner != null) scanner.update(CarSignals.mcu04 == 1 || mpl.isPlaying(), Math.max(BatteryHealth.level, store.serviceDue(Clock.now()) ? 1 : 0));
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
        String pr = store.price() > 0 ? String.format(java.util.Locale.US, "%.3f", store.price()) : "";
        InputForm.Field[] f = {
                new InputForm.Field(Lang.t("LITERS GETANKT", "LITRES FILLED"), "", Lang.t("bv. 39.94", "e.g. 39.94"), false),
                new InputForm.Field(Lang.t("PRIJS PER LITER (€)", "PRICE PER LITRE (€)"), pr, Lang.t("bv. 2.089", "e.g. 2.089"), false)};
        float t = store.tankLiters();
        String sub = t >= 0 ? String.format(java.util.Locale.US, Lang.t("TANK NU ~%.0f VAN 57 L", "TANK NOW ~%.0f OF 57 L"), t) : "";
        InputForm.show(this, Lang.t("TANKEN", "REFUEL"), sub, f, Lang.t("TANK IS NU VOL (57 L)", "TANK IS NOW FULL (57 L)"), false, ".",
                (v, full) -> {
                    float l = parse(v[0]), p = parse(v[1]);
                    if (!full && l <= 0) return Lang.t("VUL LITERS IN, OF ZET 'VOL' AAN", "ENTER LITRES, OR SWITCH 'FULL' ON");
                    if (l > 80) return Lang.t("MEER DAN 80 LITER? CONTROLEER DE INVOER", "MORE THAN 80 LITRES? CHECK THE INPUT");
                    store.fill(Math.max(0f, l), p, full);
                    CarLogger.note(String.format(java.util.Locale.US, "tanken: %.2f l, %.3f EUR/l, vol=%b", l, p, full));
                    toast(full ? Lang.t("Tank vol opgeslagen", "Full tank saved") : Lang.t("Tankbeurt opgeslagen", "Refuel saved"));
                    refresh();
                    return null;
                });
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

    /** Laatst gemaakte MainActivity (voor de afstandsbediening). */
    static MainActivity instance;

    /** Huidig thema (voor vensters buiten de launcher-activity). */
    static Theme currentTheme() { return instance != null ? instance.theme : Theme.LED; }

    /** Intent van de afstandsbediening (of oude extra "menuTab") afhandelen. */
    private void handleMenuExtra(Intent i) {
        if (i == null) return;
        if (i.hasExtra("menuTab")) {
            final int t = i.getIntExtra("menuTab", 0);
            i.removeExtra("menuTab");
            handler.postDelayed(() -> openMenuTab(t), 400);
        }
        if (i.getBooleanExtra("remote", false) && i.hasExtra("cmd")) {
            final String cmd = i.getStringExtra("cmd");
            final Intent copy = new Intent(i);
            i.removeExtra("remote"); i.removeExtra("cmd");
            handler.postDelayed(() -> remote(cmd, copy), 400);
        }
    }

    private void openMenuTab(int t) {
        if (menuDlg != null && menuDlg.isShowing()) menuDlg.dismiss();
        showMenu();
        menuView.setTab(Math.max(0, Math.min(4, t)));
    }

    /** Commando's van Remote die de launcher zelf uitvoert. */
    void remote(String cmd, Intent in) {
        switch (cmd) {
            case "menu": openMenuTab(in.getIntExtra("tab", 0)); break;
            case "tone": SpeakerTest.start(this, in.getStringExtra("side")); break;
            case "close": if (menuDlg != null) menuDlg.dismiss(); break;
            case "open": {
                String w = String.valueOf(in.getStringExtra("what"));
                if (menuDlg != null && menuDlg.isShowing() && !w.equals("maintenance") && !w.equals("highscores") && !w.equals("locations")) menuDlg.dismiss();
                switch (w) {
                    case "triplog": startActivity(new Intent(this, TripLogActivity.class)); break;
                    case "debug": startActivity(new Intent(this, DebugActivity.class)); break;
                    case "music": startActivity(new Intent(this, MusicActivity.class)); break;
                    case "fuel": showFuelDialog(); break;
                    case "maintenance": Dialogs.maintenance(this, store, () -> { if (menuView != null) menuView.invalidate(); }); break;
                    case "highscores": Dialogs.highscores(this, theme); break;
                    case "locations": Dialogs.locationList(this, null); break;
                    default: break; // home: alleen launcher naar voren
                }
                break;
            }
            case "key": case "longkey": {
                String n = String.valueOf(in.getStringExtra("name"));
                int[] ids = {R.id.btn_aa, R.id.btn_radio, R.id.btn_eq, R.id.btn_fuel, R.id.btn_trips, R.id.btn_apps, R.id.btn_settings, R.id.btn_menu};
                String[] names = {"aa", "radio", "eq", "fuel", "trips", "apps", "settings", "menu"};
                for (int k = 0; k < names.length; k++) if (names[k].equals(n)) {
                    View v = findViewById(ids[k]);
                    if (cmd.equals("key")) v.performClick(); else v.performLongClick();
                }
                break;
            }
            case "theme": {
                Theme t = Theme.byId(String.valueOf(in.getStringExtra("id")));
                getSharedPreferences("optimus", MODE_PRIVATE).edit().putString("theme", t.id).apply();
                applyTheme(t);
                if (menuView != null) menuView.setTheme(t);
                break;
            }
            case "lang": Lang.set(this, "en".equals(in.getStringExtra("value"))); languageChanged(); break;
            default: CarLogger.note("remote: onbekend commando " + cmd);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleMenuExtra(intent);
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

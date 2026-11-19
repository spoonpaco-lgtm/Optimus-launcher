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
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", NL);

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

        wireApp(R.id.btn_aa, PKG_AA, "Android Auto (CarLetter)");
        // Lang indrukken op Android Auto: afspelen/pauze zonder van scherm te wisselen
        findViewById(R.id.btn_aa).setOnLongClickListener(v -> {
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            toast("Afspelen / pauze");
            return true;
        });
        // EQ: geluidsinstellingen van FlyAudio
        findViewById(R.id.btn_eq).setOnClickListener(v -> {
            for (String pk : new String[]{"com.flyaudio.soundeffect", "com.flyaudio.service.dsp", "com.flyaudio.settings"}) {
                if (installed(pk)) { launch(pk, "EQ"); return; }
            }
            toast("Geen EQ-app gevonden");
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
                toast("Instellingen niet beschikbaar");
            }
        });
        findViewById(R.id.btn_update).setOnClickListener(v -> Updater.check(this, true));

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
                == PackageManager.PERMISSION_GRANTED) {
            startTracker();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    private void startTracker() {
        try {
            startForegroundService(new Intent(this, TrackerService.class));
        } catch (Exception e) {
            toast("Ritregistratie kon niet starten");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LOCATION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startTracker();
        } else if (req == REQ_LOCATION) {
            toast("Zonder locatie geen snelheid en ritgegevens");
        }
    }

    // ---------- scherm verversen ----------

    private void toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Zet 'Weergeven over andere apps' aan voor Optimus Launcher");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("Instelling niet gevonden");
            }
            return;
        }
        boolean on = !store.overlayEnabled();
        store.setOverlayEnabled(on);
        toast(on ? "Zwevende snelheid aan (verschijnt buiten de launcher)" : "Zwevende snelheid uit");
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
        maybeShowSummary();
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

    private void refreshTrip() {
        float km = store.tripKm();
        if (km < 0.05f) {
            trip.setText("Rit: nog niet gereden");
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
            toast("Mediatoets mislukt: " + e.getMessage());
        }
    }

    // ---------- ritsamenvatting ----------

    private AlertDialog summaryDialog;

    private void maybeShowSummary() {
        if (!store.summaryPending() || (summaryDialog != null && summaryDialog.isShowing())) return;
        summaryDialog = new AlertDialog.Builder(this)
                .setTitle("Ritsamenvatting")
                .setMessage(store.summaryText())
                .setPositiveButton("OK", (d, w) -> store.clearSummary())
                .setOnCancelListener(d -> store.clearSummary())
                .show();
    }

    private void launch(String pkg, String naam) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } else {
            toast(naam + " is niet geïnstalleerd");
        }
    }

    private void showApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = new ArrayList<>(pm.queryIntentActivities(main, 0));
        final List<String> pkgs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        Collections.sort(list, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        for (ResolveInfo ri : list) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || pkgs.contains(p)) continue;
            pkgs.add(p);
            labels.add(ri.loadLabel(pm).toString());
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Apps")
                .setItems(labels.toArray(new String[0]), (d, which) ->
                        launch(pkgs.get(which), labels.get(which)))
                .setNegativeButton("Sluiten", null)
                .show();
    }

    private void showFuelDialog() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText liters = numberField("Liters getankt");
        final EditText price = numberField("Prijs per liter (€)");
        if (store.price() > 0) price.setText(String.format(NL, "%.3f", store.price()));
        final CheckBox full = new CheckBox(this);
        full.setText("Tank is nu vol (57 l)");
        full.setTextSize(18);

        box.addView(liters);
        box.addView(price);
        box.addView(full);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Tanken")
                .setView(box)
                .setPositiveButton("Opslaan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        float l = parse(liters.getText().toString());
                        float pr = parse(price.getText().toString());
                        if (!full.isChecked() && l <= 0) {
                            toast("Vul liters in, of vink 'vol' aan");
                            return;
                        }
                        store.fill(Math.max(0f, l), pr, full.isChecked());
                        toast(full.isChecked() ? "Tank vol opgeslagen" : "Tankbeurt opgeslagen");
                        refresh();
                    }
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private EditText numberField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(20);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setKeyListener(DigitsKeyListener.getInstance("0123456789.,"));
        return e;
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

    /** Staat de launcher op het scherm? (dan geen zwevend venster nodig) */
    public static volatile boolean visible = false;

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

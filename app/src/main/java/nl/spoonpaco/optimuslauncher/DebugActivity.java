package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/**
 * Verborgen debugscherm (klok lang ingedrukt houden).
 * Live: GPS-satellieten, netwerk/ADB, systeem, autolog + laatste MCU-bericht, rit/tank, crash.
 */
public class DebugActivity extends Activity {

    private static final Locale L = Locale.US;
    private TextView text;
    private Store store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new Store(this);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setPadding(pad, pad, pad, pad);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "Nieuw logdeel", () -> {
            CarLogger.newPart();
            toast("Nieuw logdeel gestart");
        });
        addButton(buttons, "Rit resetten", () -> {
            store.startNewTrip();
            toast("Rit gereset");
        });
        addButton(buttons, CarLogger.logAll ? "Log: alles" : "Log: alleen auto", () -> {
            CarLogger.logAll = !CarLogger.logAll;
            getSharedPreferences("optimus", MODE_PRIVATE).edit().putBoolean("logAll", CarLogger.logAll).apply();
            Toast.makeText(this, CarLogger.logAll ? "Autolog: alles (debug)" : "Autolog: alleen autodata", Toast.LENGTH_SHORT).show();
            recreate();
        });
        addButton(buttons, "Meldingtoegang", () -> {
            try { startActivity(new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
            catch (Exception e) { toast("Scherm niet beschikbaar; via adb geven"); }
        });
        addButton(buttons, "Update zoeken", () -> Updater.check(this, true));
        addButton(buttons, "Crash wissen", () -> {
            //noinspection ResultOfMethodCallIgnored
            new File(getFilesDir(), OptimusApp.CRASH_FILE).delete();
            toast("Crashlog gewist");
        });
        addButton(buttons, "Sluiten", this::finish);
        root.addView(buttons);

        sv = new ScrollView(this);
        sv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(13);
        text.setTextColor(getColor(R.color.fg));
        sv.addView(text);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void addButton(LinearLayout row, String label, Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(15);
        btn.setOnClickListener(v -> action.run());
        row.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        StringBuilder s = new StringBuilder();

        // ---- GPS ----
        s.append(MediaProbe.report(this));
        s.append("== GPS ==\n");
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean enabled = false;
        try { enabled = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }
        s.append(String.format(L, "provider aanwezig=%s  aan=%s\n", TrackerService.gpsPresent, enabled));
        s.append(String.format(L, "satellieten: zicht=%d  met signaal=%d  gebruikt=%d  beste=%.0f dB-Hz\n",
                TrackerService.satsVisible, TrackerService.satsWithSignal,
                TrackerService.satsUsed, TrackerService.bestSnr));
        s.append("  (sterkte: <15 slecht, 15-25 matig, >25 goed; * = gebruikt)\n");
        s.append("  ").append(TrackerService.satDetail.isEmpty() ? "-" : TrackerService.satDetail).append('\n');
        s.append("  sat-update: ").append(age(now, TrackerService.satUpdateTime)).append('\n');
        s.append("laatste positie: ").append(age(now, TrackerService.lastFixTime));
        if (TrackerService.lastAccuracy >= 0) s.append(String.format(L, "  nauwkeurig ±%.0f m", TrackerService.lastAccuracy));
        s.append('\n');
        s.append("eerste positie (TTFF): ")
                .append(TrackerService.firstFixMs >= 0 ? (TrackerService.firstFixMs / 1000) + " s" : "nog niet")
                .append("   GPS loopt al ").append(age(now, TrackerService.serviceStart)).append('\n');
        s.append(String.format(L, "snelheid: %.1f km/u\n", Math.max(0f, TrackerService.speedKmh)));
        s.append("klok: ").append(new java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", L).format(new java.util.Date(Clock.now())))
                .append("  bron: ").append(Clock.source);
        if (Clock.lastGpsSync > 0) s.append(" (").append(age(now, Clock.lastGpsSync)).append(")");
        s.append(String.format(L, "  correctie %+d s\n\n", Clock.offsetMs / 1000));

        // ---- Netwerk / ADB ----
        s.append("== Netwerk / ADB ==\n");
        s.append("IP: ").append(ips()).append('\n');
        s.append("internet: ").append(net()).append('\n');
        int adb = -1;
        try { adb = Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED); } catch (Exception ignored) { }
        s.append(String.format(L, "adb aan=%s  tcp-poort=%s  persist-poort=%s  adbd-root=%s\n\n",
                adb == 1 ? "ja" : "nee", prop("service.adb.tcp.port"), prop("persist.adb.tcp.port"),
                prop("service.adb.root")));

        // ---- Systeem ----
        s.append("== Systeem ==\n");
        String ver = "?";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) { }
        s.append("Optimus Launcher ").append(ver).append('\n');
        s.append("CPU-temp: ").append(cpuTemp()).append('\n');
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        s.append(String.format(L, "RAM: %d MB vrij van %d MB%s\n",
                mi.availMem / 1048576, mi.totalMem / 1048576, mi.lowMemory ? "  (LAAG!)" : ""));
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            s.append(String.format(L, "opslag: %d MB vrij\n\n", fs.getAvailableBytes() / 1048576));
        } catch (Exception e) { s.append("opslag: ?\n\n"); }

        // ---- Autolog ----
        s.append("== Aan / slaap ==\n");
        s.append(PowerLog.clocks()).append('\n');
        s.append(PowerLog.recentText()).append('\n');
        s.append("== Autolog ==\n");
        int st = CarLogger.state;
        s.append("status: ").append(st == 2 ? "volledig" : st == 1 ? "alleen GPS (geen READ_LOGS?)" : "uit")
                .append(CarLogger.full ? "  — DAG VOL" : "").append('\n');
        s.append(String.format(L, "bestand: %s  (%.1f MB)\n", CarLogger.currentFile, CarLogger.currentBytes / 1048576f));
        s.append("modus: ").append(CarLogger.logAll ? "alles (debug)" : "alleen autodata").append('\n');
        s.append(String.format(L, "regels: %d bewaard, %d weggefilterd\n",
                CarLogger.linesWritten, CarLogger.linesNoise));
        s.append("laatste MCU-bericht: ").append(age(now, CarLogger.lastMcuTime)).append('\n');
        String mcu = CarLogger.lastMcu;
        if (mcu != null) {
            int i = mcu.indexOf("getDataFrom");
            s.append("  ").append(i >= 0 ? mcu.substring(i) : mcu).append('\n');
        }
        s.append('\n');

        // ---- Rit / tank ----
        s.append("== Rit / tank ==\n");
        s.append(String.format(L, "rit: %.2f km  %.3f u  gem %.1f  max %.1f km/u  %.2f l\n",
                store.tripKm(), store.tripMovingH(), store.tripAvgKmh(), store.tripMaxKmh(), store.tripLiters()));
        s.append(String.format(L, "tank: %.1f l  bereik %d km  prijs %.3f\n",
                store.tankLiters(), store.rangeKm(), store.price()));
        s.append(String.format(L, "kalibratie: factor %.3f  sinds vol: %.1f km, geschat %.2f l, getankt %.2f l\n\n",
                store.calFactor(), store.kmSinceFull(), store.estSinceFull(), store.addedSinceFull()));

        // ---- Crash ----
        s.append("== Laatste crash ==\n").append(crash());

        // scrollpositie vasthouden: anders springt het scherm elke seconde terug naar boven
        final int y = sv.getScrollY();
        text.setText(s.toString());
        sv.post(() -> sv.scrollTo(0, y));
    }

    private ScrollView sv;

    // ---------- helpers ----------

    private static String age(long now, long t) {
        if (t <= 0) return "nooit";
        long sec = (now - t) / 1000;
        if (sec < 60) return sec + " s geleden";
        if (sec < 3600) return (sec / 60) + " min geleden";
        return (sec / 3600) + " u geleden";
    }

    private String ips() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        sb.append(ni.getName()).append('=').append(a.getHostAddress()).append("  ");
                    }
                }
            }
        } catch (Exception ignored) { }
        return sb.length() == 0 ? "geen" : sb.toString().trim();
    }

    private String net() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "geen netwerk";
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) return "onbekend";
            String type = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : "anders";
            return type + (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? ", internet ok" : ", GEEN internet");
        } catch (Exception e) {
            return "fout";
        }
    }

    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            String v = (String) get.invoke(null, key);
            return v == null || v.isEmpty() ? "-" : v;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String cpuTemp() {
        for (int z = 0; z < 4; z++) {
            File f = new File("/sys/class/thermal/thermal_zone" + z + "/temp");
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String v = br.readLine();
                if (v == null) continue;
                float t = Float.parseFloat(v.trim());
                if (t > 1000) t /= 1000f;
                return String.format(L, "%.1f °C (zone %d)", t, z);
            } catch (Exception ignored) { }
        }
        return "?";
    }

    private String crash() {
        File f = new File(getFilesDir(), OptimusApp.CRASH_FILE);
        if (!f.exists()) return "geen\n";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n++ < 25) sb.append(line).append('\n');
        } catch (Exception e) {
            return "kon crashlog niet lezen\n";
        }
        return sb.toString();
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }
}

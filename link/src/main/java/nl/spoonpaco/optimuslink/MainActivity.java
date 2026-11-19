package nl.spoonpaco.optimuslink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Eenvoudig scherm: starten/stoppen, auto kiezen, status. */
public class MainActivity extends Activity {

    private TextView status;
    private Button toggle;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Optimus Link");
        title.setTextSize(26);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Stuurt de GPS en de tijd van deze telefoon naar de Optimus Launcher in de auto.");
        info.setTextSize(15);
        info.setPadding(0, pad / 2, 0, pad);
        root.addView(info);

        toggle = button(root, "Start", v -> toggle());
        button(root, "Kies je auto (Bluetooth)", v -> pickCar());
        button(root, "Accubesparing uitzetten (aanbevolen)", v -> batteryExempt());

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad, 0, 0);
        root.addView(status);

        setContentView(root);
        askPermissions();
    }

    private Button button(LinearLayout root, String text, android.view.View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        root.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String s : p) if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) missing.add(s);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    private void toggle() {
        if (LinkService.running) {
            LinkService.prefs(this).edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, LinkService.class));
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                askPermissions();
                toast("Geef eerst toestemming voor locatie");
                return;
            }
            LinkService.prefs(this).edit().putBoolean("enabled", true).apply();
            LinkService.start(this);
        }
    }

    private void pickCar() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            askPermissions();
            toast("Geef eerst toestemming voor apparaten in de buurt");
            return;
        }
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { toast("Geen Bluetooth"); return; }
        Set<BluetoothDevice> bonded;
        try { bonded = ad.getBondedDevices(); } catch (SecurityException e) { toast("Geen toestemming"); return; }
        final List<BluetoothDevice> list = new ArrayList<>(bonded);
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            String n;
            try { n = list.get(i).getName(); } catch (SecurityException e) { n = null; }
            names[i] = (n == null ? "?" : n) + "  (" + list.get(i).getAddress() + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Welk apparaat is je auto?")
                .setItems(names, (d, w) -> {
                    LinkService.prefs(this).edit().putString(LinkService.KEY_CAR, list.get(w).getAddress()).apply();
                    toast("Auto opgeslagen: " + names[w]);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void batteryExempt() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("Accubesparing staat al uit voor Optimus Link");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refresh() {
        toggle.setText(LinkService.running ? "Stop" : "Start");
        String car = LinkService.prefs(this).getString(LinkService.KEY_CAR, null);
        long now = System.currentTimeMillis();
        StringBuilder s = new StringBuilder();
        s.append("Dienst: ").append(LinkService.running ? "actief" : "uit").append('\n');
        s.append("Auto (Bluetooth): ").append(car == null ? "nog niet gekozen" : car).append('\n');
        s.append("Bluetooth naar auto: ").append(LinkService.btConnected ? "verbonden" : "niet verbonden")
                .append("  (kanaal: ").append(LinkService.btMode).append(")\n");
        s.append("Wifi van de auto: ").append(LinkService.wifiCar ? "ja" : "nee").append('\n');
        s.append("GPS: ").append(LinkService.gpsOn ? "aan" : "uit");
        if (LinkService.lastFix > 0) s.append(", laatste positie ").append((now - LinkService.lastFix) / 1000).append(" s geleden");
        s.append('\n');
        s.append("Verzonden: ").append(LinkService.sent).append(" berichten\n");
        if (!LinkService.lastError.isEmpty()) s.append("Laatste melding: ").append(LinkService.lastError).append('\n');
        status.setText(s.toString());
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}

package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Optimus Launcher 0.0.1 — home-scherm skelet.
 * Toont klok + datum en zes knoppen die apps starten op pakketnaam.
 * Nog GEEN: dag/nacht, snelheid, GPS, spiegel, verbruik. Die komen later.
 */
public class MainActivity extends Activity {

    private static final Locale NL = new Locale("nl");

    private TextView clock;
    private TextView date;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", NL);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", NL);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);

        wireApp(R.id.btn_nav, "com.google.android.apps.maps", getString(R.string.nav));
        wireApp(R.id.btn_music, "com.spotify.music", getString(R.string.music));
        wireApp(R.id.btn_phone, "com.flyaudio.phone", getString(R.string.phone));
        wireApp(R.id.btn_radio, "com.flyaudio.media.radio", getString(R.string.radio));

        findViewById(R.id.btn_apps).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toast("Applijst komt in een volgende versie");
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    toast("Instellingen niet beschikbaar");
                }
            }
        });

        updateClock();
    }

    private void wireApp(int viewId, final String pkg, final String naam) {
        findViewById(viewId).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } else {
                    toast(naam + " is niet geïnstalleerd");
                }
            }
        });
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        date.setText(capitalize(dateFmt.format(now)));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(NL) + s.substring(1);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override public void onBackPressed() {
        // Home-scherm: terug-knop doet niets, zodat je niet "uit" de launcher valt.
    }
}

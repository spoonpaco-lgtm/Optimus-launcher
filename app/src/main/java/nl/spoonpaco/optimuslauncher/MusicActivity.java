package nl.spoonpaco.optimuslauncher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Muziekscherm (volledig scherm) rond MusicPanel. */
public class MusicActivity extends Activity implements MusicPlayer.Listener {
    private MusicPlayer mp;
    private MusicPanel v;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        mp = MusicPlayer.get(this);
        Theme t = Theme.byId(getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led"));
        v = new MusicPanel(this, t, new MusicPanel.Host() {
            @Override public void close() { finish(); }
            @Override public void requestPerm() { requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 7); }
        }, false);
        setContentView(MusicPanel.wrap(v));
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 7);
        else if (mp.all().isEmpty() && !mp.scanning) mp.scan();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) mp.scan();
        v.invalidate();
    }

    @Override protected void onResume() { super.onResume(); mp.addListener(this); v.tick(); }
    @Override protected void onPause() { super.onPause(); mp.removeListener(this); v.removeCallbacks(v.ticker); }
    @Override public void onChanged() { v.invalidate(); }

    @Override public void onBackPressed() { if (!v.back()) super.onBackPressed(); }
}

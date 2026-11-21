package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

/**
 * Opstartfilmpje bij contact aan als de tablet uit slaap komt
 * (de echte bootanimatie draait alleen bij een volledige herstart).
 * Daarna door naar Android Auto (of de launcher).
 */
public class WakeAnimActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean done = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        VideoView v = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        root.addView(v, lp);
        root.setOnClickListener(x -> next());
        setContentView(root);
        // korte geluidsfocus: muziek wordt even zachter en gaat daarna gewoon door (niet stoppen)
        try { v.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); } catch (Throwable ignored) { }
        v.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.optimus_boot));
        v.setOnCompletionListener(mp -> next());
        v.setOnErrorListener((mp, what, extra) -> { PowerLog.log("opstartfilmpje fout " + what); next(); return true; });
        v.start();
        h.postDelayed(this::next, 8000L); // vangnet
        PowerLog.log("opstartfilmpje gestart");
    }

    private void next() {
        if (done) return;
        done = true;
        boolean toAA = getSharedPreferences("optimus", MODE_PRIVATE).getBoolean("startAA", false);
        Intent aa = toAA ? getPackageManager().getLaunchIntentForPackage("com.carletter.car") : null;
        Intent go = aa != null ? aa : new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(go); } catch (Exception ignored) { }
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

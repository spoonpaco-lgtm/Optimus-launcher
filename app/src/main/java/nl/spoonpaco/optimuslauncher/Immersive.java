package nl.spoonpaco.optimuslauncher;

import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Android-balk bovenin verbergen in onze eigen schermen; vegen van boven laat hem even terugkomen. */
final class Immersive {
    private Immersive() { }

    static final int FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    static void apply(Window w) {
        if (w == null) return;
        try {
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            final View d = w.getDecorView();
            d.setSystemUiVisibility(FLAGS);
            // komt de balk terug (vegen), dan na een paar seconden weer weg
            d.setOnSystemUiVisibilityChangeListener(vis -> {
                if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) d.postDelayed(() -> d.setSystemUiVisibility(FLAGS), 3000);
            });
        } catch (Exception ignored) { }
    }
}

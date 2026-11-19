package nl.spoonpaco.optimuslink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Start Optimus Link na een herstart van de telefoon of na een update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!LinkService.prefs(c).getBoolean("enabled", false)) return;
        try {
            LinkService.start(c);
        } catch (Exception ignored) {
            // Sommige Android-versies weigeren dit vanaf opstarten; dan één keer de app openen.
        }
    }
}

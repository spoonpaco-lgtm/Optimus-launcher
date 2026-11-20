package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * Onzichtbare ingang voor Remote-commando's via adb. Tekent één leeg frame (zodat "am start -W"
 * meteen terugkomt) en sluit zich daarna.
 */
public class RemoteActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(new View(this));
        Remote.remoteDecor = new java.lang.ref.WeakReference<>(getWindow().getDecorView());
        Remote.handle(this, getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Remote.handle(this, i);
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().postDelayed(() -> { finish(); overridePendingTransition(0, 0); }, 120);
    }
}

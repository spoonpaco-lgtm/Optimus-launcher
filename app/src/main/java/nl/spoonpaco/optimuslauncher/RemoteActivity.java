package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Onzichtbare ingang voor Remote-commando's via adb. Sluit zich meteen. */
public class RemoteActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Remote.handle(this, getIntent());
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Remote.handle(this, i);
        finish();
        overridePendingTransition(0, 0);
    }
}

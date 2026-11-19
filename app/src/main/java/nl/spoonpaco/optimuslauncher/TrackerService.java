package nl.spoonpaco.optimuslauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Houdt op de achtergrond GPS bij, ook als Maps of Spotify voorop staat.
 * Berekent ritafstand, snelheid en geschat verbruik.
 */
public class TrackerService extends Service implements LocationListener {

    /** Laatste snelheid in km/u; -1 = nog geen fix. */
    public static volatile float speedKmh = -1f;
    /** Tijdstip van de laatste GPS-fix (ms). */
    public static volatile long lastFixTime = 0L;
    /** false als de tablet geen GPS-provider heeft. */
    public static volatile boolean gpsPresent = true;

    private static final String CHANNEL = "rit";
    private static final float MOVING_KMH = 3f;
    private static final float MAX_ACCURACY_M = 35f;

    private LocationManager lm;
    private Location last;
    private Store store;
    private long lastGpsNote = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Ritregistratie", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Optimus Launcher")
                .setContentText("Ritregistratie actief")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        CarLogger.start(this);

        lm =(LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || lm.getProvider(LocationManager.GPS_PROVIDER) == null) {
            gpsPresent = false;
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
        } catch (SecurityException e) {
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        CarLogger.stop();
        if (lm != null) {
            try { lm.removeUpdates(this); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onLocationChanged(Location loc) {
        long now = System.currentTimeMillis();
        lastFixTime = now;
        float kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
        speedKmh = kmh;

        if (now - lastGpsNote >= 5000) {
            lastGpsNote = now;
            CarLogger.note(String.format(java.util.Locale.US,
                    "gps kmh=%.1f acc=%.0f lat=%.5f lon=%.5f",
                    kmh, loc.hasAccuracy() ? loc.getAccuracy() : -1f,
                    loc.getLatitude(), loc.getLongitude()));
        }

        if (!loc.hasAccuracy() || loc.getAccuracy() > MAX_ACCURACY_M) return;

        if (kmh >= MOVING_KMH) {
            if (now - store.lastMoveTime() > Store.NEW_TRIP_AFTER_MS && store.tripKm() > 0f) {
                store.startNewTrip();
            }
            if (last != null) {
                float km = last.distanceTo(loc) / 1000f;
                float hours = (loc.getTime() - last.getTime()) / 3_600_000f;
                // sprongen en rare tijden negeren
                if (km > 0f && km < 0.5f && hours > 0f && hours < 0.01f) {
                    store.addSegment(km, hours, kmh, now);
                } else {
                    store.touchMove(now);
                }
            } else {
                store.touchMove(now);
            }
        }
        last = loc;
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
}

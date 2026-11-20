package nl.spoonpaco.optimuslauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.GnssStatus;
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
    /** Rijrichting in graden (GPS-koers), -1 = nog onbekend. */
    public static volatile float bearing = -1f;
    /** Tijdstip van de laatste GPS-fix (ms). */
    public static volatile long lastFixTime = 0L;
    /** false als de tablet geen GPS-provider heeft. */
    public static volatile boolean gpsPresent = true;

    // GPS-diagnose voor het debugscherm
    public static volatile int satsVisible = 0;
    public static volatile int satsUsed = 0;
    public static volatile int satsWithSignal = 0;
    public static volatile float bestSnr = 0f;
    public static volatile String satDetail = "";
    public static volatile long satUpdateTime = 0L;
    public static volatile long firstFixMs = -1L;
    public static volatile float lastAccuracy = -1f;
    public static volatile long serviceStart = 0L;

    private long lastSatNote = 0L;

    private final GnssStatus.Callback gnssCb = new GnssStatus.Callback() {
        @Override public void onStarted() { PowerLog.log("GPS-motor gestart"); }
        @Override public void onStopped() { PowerLog.log("GPS-motor gestopt"); }
        @Override public void onFirstFix(int ttffMillis) {
            firstFixMs = ttffMillis;
            PowerLog.log("GPS eerste positie na " + (ttffMillis / 1000) + " s (sats " + satsUsed + "/" + satsVisible + ")");
            CarLogger.note("gps eerste positie na " + (ttffMillis / 1000) + " s");
        }
        @Override public void onSatelliteStatusChanged(GnssStatus s) {
            int n = s.getSatelliteCount();
            int used = 0, withSig = 0;
            float best = 0f;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                float cn0 = s.getCn0DbHz(i);
                if (s.usedInFix(i)) used++;
                if (cn0 > 0f) withSig++;
                if (cn0 > best) best = cn0;
                if (i < 24) {
                    sb.append(constellation(s.getConstellationType(i)))
                      .append(s.getSvid(i)).append(':')
                      .append(Math.round(cn0))
                      .append(s.usedInFix(i) ? "* " : "  ");
                }
            }
            satsVisible = n;
            satsUsed = used;
            satsWithSignal = withSig;
            bestSnr = best;
            satDetail = sb.toString().trim();
            long now = System.currentTimeMillis();
            satUpdateTime = now;
            if (now - lastSatNote >= 30000) {
                lastSatNote = now;
                CarLogger.note(String.format(java.util.Locale.US,
                        "gps sats zicht=%d signaal=%d gebruikt=%d best=%.0f dBHz | %s",
                        n, withSig, used, best, satDetail));
            }
        }
    };

    private static String constellation(int t) {
        switch (t) {
            case GnssStatus.CONSTELLATION_GPS: return "G";
            case GnssStatus.CONSTELLATION_GLONASS: return "R";
            case GnssStatus.CONSTELLATION_BEIDOU: return "B";
            case GnssStatus.CONSTELLATION_GALILEO: return "E";
            case GnssStatus.CONSTELLATION_QZSS: return "J";
            case GnssStatus.CONSTELLATION_SBAS: return "S";
            default: return "?";
        }
    }

    private static final String CHANNEL = "rit";
    private static final float MOVING_KMH = 3f;
    /** Gewicht auto + bestuurder (kg) voor optrek-verbruik. */
    private static final float CAR_KG = 1600f;
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
        PowerLog.init(this);
        PowerLog.log("dienst gestart — " + PowerLog.clocks()
                + (android.os.SystemClock.elapsedRealtime() < 5L * 60L * 1000L ? "  => ECHTE OPSTART" : ""));
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_SHUTDOWN);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(powerRx, f);
        lastUpDelta = android.os.SystemClock.elapsedRealtime() - android.os.SystemClock.uptimeMillis();
        lastWakeElapsed = android.os.SystemClock.elapsedRealtime();
        CarSignals.listener = (name, v) -> ui.post(() -> onCarSignal(name, v));
        android.content.IntentFilter uf = new android.content.IntentFilter();
        uf.addAction(Intent.ACTION_MEDIA_MOUNTED);
        uf.addDataScheme("file");
        registerReceiver(usbRx, uf);

        // Zwevend snelheidsvenster over Android Auto e.d.
        ui.post(overlayTick);

        // Ritsamenvatting: rit van vóór de herstart afsluiten; daarna elke 10 s op stilstand controleren
        NotificationChannel sc = new NotificationChannel(CHANNEL_SUM, "Ritsamenvatting", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(sc);
        lastMoveElapsed = android.os.SystemClock.elapsedRealtime();
        if (!store.tripEnded() && store.tripKm() >= MIN_TRIP_KM) finishTrip("herstart");
        ui.postDelayed(tripTick, 10_000L);

        lm =(LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || lm.getProvider(LocationManager.GPS_PROVIDER) == null) {
            gpsPresent = false;
            return;
        }
        serviceStart = System.currentTimeMillis();
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            lm.registerGnssStatusCallback(gnssCb);
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
        ui.removeCallbacks(overlayTick);
        ui.removeCallbacks(tripTick);
        try { unregisterReceiver(powerRx); } catch (Exception ignored) { }
        try { unregisterReceiver(usbRx); } catch (Exception ignored) { }
        CarSignals.listener = null;
        PowerLog.log("dienst gestopt");
        removeBubble();
        CarLogger.stop();
        if (lm != null) {
            try { lm.removeUpdates(this); } catch (Exception ignored) { }
            try { lm.unregisterGnssStatusCallback(gnssCb); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onLocationChanged(Location loc) {
        if (!fixSinceWake) {
            fixSinceWake = true;
            PowerLog.log("GPS-positie binnen, " + PowerLog.dur(android.os.SystemClock.elapsedRealtime() - wakeAt) + " na wakker worden");
        }
        Clock.fromGps(loc.getTime());
        handleLocation(loc);
    }

    // ---------- ritsamenvatting ----------

    private static final String CHANNEL_SUM = "ritsamenvatting";
    private static final float MIN_TRIP_KM = 0.3f;
    private static final long STOP_MS = 5L * 60L * 1000L;
    private volatile long lastMoveElapsed = 0L;

    // ---------- aan/uit/slaap ----------

    private long lastUpDelta = 0L;
    private long screenOffAt = 0L;
    private long wakeAt = 0L;
    private boolean fixSinceWake = true;
    private long lastNoFixNote = 0L;

    private final android.content.BroadcastReceiver powerRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            switch (a) {
                case Intent.ACTION_SCREEN_OFF:
                    screenOffAt = android.os.SystemClock.elapsedRealtime();
                    PowerLog.log("scherm UIT — " + PowerLog.clocks());
                    if (!store.tripEnded() && store.tripKm() >= MIN_TRIP_KM) finishTrip("scherm uit");
                    break;
                case Intent.ACTION_SCREEN_ON:
                    long off = screenOffAt > 0 ? android.os.SystemClock.elapsedRealtime() - screenOffAt : -1;
                    PowerLog.log("scherm AAN" + (off >= 0 ? " na " + PowerLog.dur(off) + " uit" : "") + " — " + PowerLog.clocks());
                    onWake(off);
                    break;
                case Intent.ACTION_POWER_CONNECTED: PowerLog.log("voeding aangesloten"); break;
                case Intent.ACTION_POWER_DISCONNECTED: PowerLog.log("voeding weg"); break;
                case Intent.ACTION_SHUTDOWN: PowerLog.log("UITSCHAKELEN"); break;
                case Intent.ACTION_USER_PRESENT: PowerLog.log("ontgrendeld"); break;
            }
        }
    };

    /** Na wakker worden: GPS opnieuw aanvragen (sommige ROMs laten hem uit) en evt. Android Auto openen. */
    /** Tijdstip (elapsedRealtime) van laatste opstart of wakker worden; launcher gebruikt dit voor "Offline navigeren". */
    public static volatile long lastWakeElapsed = 0L;

    private void onCarSignal(String name, int v) {
        PowerLog.log(name + " -> " + v + (CarSignals.volts > 0 ? String.format(java.util.Locale.US, " (%.2f V)", CarSignals.volts) : ""));
        if (name.equals("handrem") && v == 1 && CarSignals.handbrake >= 0 && speedKmh < MOVING_KMH
                && !store.tripEnded() && store.tripKm() >= MIN_TRIP_KM) {
            finishTrip("handrem erop");
        }
        if (name.equals("contact") && v == 0
                && !store.tripEnded() && store.tripKm() >= MIN_TRIP_KM) {
            finishTrip("contact uit");
        }
    }

    // ---------- USB-stick ----------

    private final android.content.BroadcastReceiver usbRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getData() == null) return;
            final String path = i.getData().getPath();
            PowerLog.log("USB-stick: " + path);
            new Thread(() -> UsbStick.handle(TrackerService.this, path, store), "usb-stick").start();
        }
    };

    private void onWake(long offMs) {
        wakeAt = android.os.SystemClock.elapsedRealtime();
        lastWakeElapsed = wakeAt;
        CarLogger.restart(this);
        fixSinceWake = false;
        lastMoveElapsed = wakeAt;
        restartGps("wakker");
        if (offMs < 0 || offMs > 2L * 60L * 1000L) {
            // Opstartfilmpje, daarna gaat dat zelf door naar Android Auto
            ui.postDelayed(() -> {
                try {
                    Intent w = new Intent(this, WakeAnimActivity.class);
                    w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(w);
                } catch (Exception e) { PowerLog.log("opstartfilmpje mislukt: " + e.getMessage()); }
            }, 300L);
        }
    }

    private void restartGps(String why) {
        if (lm == null || !gpsPresent) return;
        try {
            lm.removeUpdates(this);
            lm.unregisterGnssStatusCallback(gnssCb);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            lm.registerGnssStatusCallback(gnssCb);
            PowerLog.log("GPS opnieuw aangevraagd (" + why + ")");
        } catch (Exception e) {
            PowerLog.log("GPS opnieuw aanvragen mislukt: " + e.getMessage());
        }
    }

    private final Runnable tripTick = new Runnable() {
        @Override public void run() {
            long el = android.os.SystemClock.elapsedRealtime();
            // Slaap ontdekken: uptime stond stil terwijl de echte tijd doorliep
            long delta = el - android.os.SystemClock.uptimeMillis();
            if (delta - lastUpDelta > 30_000L) {
                PowerLog.log("tablet heeft " + PowerLog.dur(delta - lastUpDelta) + " GESLAPEN — " + PowerLog.clocks());
                onWake(delta - lastUpDelta);
            }
            lastUpDelta = delta;
            // GPS na wakker worden in de gaten houden
            if (!fixSinceWake && el - wakeAt > 120_000L && el - lastNoFixNote > 120_000L) {
                lastNoFixNote = el;
                PowerLog.log("nog GEEN GPS-positie, " + PowerLog.dur(el - wakeAt) + " na wakker worden (sats "
                        + satsUsed + "/" + satsWithSignal + "/" + satsVisible + ")");
                if (el - wakeAt > 240_000L) restartGps("geen fix");
            }
            long idle = android.os.SystemClock.elapsedRealtime() - lastMoveElapsed;
            if (!store.tripEnded() && store.tripKm() >= MIN_TRIP_KM && idle >= STOP_MS) finishTrip("stilstand");
            ui.postDelayed(this, 10_000L);
        }
    };

    private void finishTrip(String why) {
        store.endTrip();
        CarLogger.note("rit afgesloten (" + why + "): " + store.summaryShort());
        try {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 2, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_SUM)
                    .setContentTitle("Rit klaar")
                    .setContentText(store.summaryShort())
                    .setStyle(new Notification.BigTextStyle().bigText(store.summaryText()))
                    .setSmallIcon(android.R.drawable.ic_menu_directions)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(2, n);
        } catch (Exception ignored) { }
    }

    private synchronized void handleLocation(Location loc) {
        long now = System.currentTimeMillis();
        lastFixTime = now;
        float kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
        speedKmh = kmh;
        if (loc.hasBearing() && kmh >= MOVING_KMH) bearing = loc.getBearing();
        lastAccuracy = loc.hasAccuracy() ? loc.getAccuracy() : -1f;

        if (now - lastGpsNote >= 5000) {
            lastGpsNote = now;
            CarLogger.note(String.format(java.util.Locale.US,
                    "gps kmh=%.1f acc=%.0f lat=%.5f lon=%.5f",
                    kmh, loc.hasAccuracy() ? loc.getAccuracy() : -1f,
                    loc.getLatitude(), loc.getLongitude()));
        }

        if (!loc.hasAccuracy() || loc.getAccuracy() > MAX_ACCURACY_M) return;

        // Extra verbruik: stationair draaien (stilstand tijdens een rit) en optrekken
        if (last != null) {
            float dt = (loc.getTime() - last.getTime()) / 1000f;
            if (dt > 0f && dt < 5f) {
                if (kmh < MOVING_KMH) {
                    if (!store.tripEnded()) store.addFuel(Store.IDLE_LPH * dt / 3600f);
                } else {
                    float v1 = last.hasSpeed() ? last.getSpeed() : 0f;
                    float v2 = loc.getSpeed();
                    if (v2 > v1) {
                        // kinetische energie / (energie benzine 34,2 MJ/l x motorrendement ~25%)
                        float joule = 0.5f * CAR_KG * (v2 * v2 - v1 * v1);
                        store.addFuel(joule / 8.55e6f);
                    }
                }
            }
        }

        if (kmh >= MOVING_KMH) {
            lastMoveElapsed = android.os.SystemClock.elapsedRealtime();
            // Vorige rit afgesloten (stilstand of herstart) -> nu begint een nieuwe
            if (store.tripEnded()) store.startNewTrip();
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

    // ---------- zwevend snelheidsvenster ----------

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.view.WindowManager wm;
    private android.widget.TextView bubble;
    private android.view.WindowManager.LayoutParams bp;

    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            try { updateOverlay(); } catch (Exception ignored) { }
            ui.postDelayed(this, 1000);
        }
    };

    private void updateOverlay() {
        boolean want = android.provider.Settings.canDrawOverlays(this)
                && !MainActivity.visible
                && store.overlayEnabled();
        if (!want) { removeBubble(); return; }
        if (bubble == null) addBubble();
        boolean fresh = System.currentTimeMillis() - lastFixTime < 3000 && speedKmh >= 0;
        String sp = fresh ? Math.round(speedKmh) + " km/u" : "-- km/u";
        bubble.setTextColor(getColor(fresh ? R.color.fg : R.color.fg_dim));
        java.util.Locale nl = new java.util.Locale("nl");
        if (store.overlayCollapsed()) {
            // snelheid + gemiddeld (geschat) verbruik van deze rit
            float tk = store.tripKm();
            bubble.setText(tk >= 0.5f
                    ? sp + String.format(nl, "  ·  %.1f km/l", tk / Math.max(0.01f, store.tripLiters()))
                    : sp);
            return;
        }
        String stats;
        float km = store.tripKm();
        if (km < 0.05f) {
            stats = "Rit: nog niet gereden";
        } else {
            stats = String.format(nl, "Rit %.1f km\nGem %.0f · Max %.0f km/u\n~%.1f l · %.1f km/l (%.1f l/100)", km,
                    store.tripAvgKmh(), store.tripMaxKmh(), store.tripLiters(), km / Math.max(0.01f, store.tripLiters()), store.tripLiters() / km * 100f);
            float cost = store.tripCost();
            if (cost >= 0) stats += String.format(nl, " · € %.2f", cost);
        }
        android.text.SpannableString s = new android.text.SpannableString(sp + "\n" + stats);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.6f), sp.length(), s.length(), 0);
        s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), sp.length(), s.length(), 0);
        bubble.setText(s);
    }

    private void addBubble() {
        wm = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        bubble = new android.widget.TextView(this);
        bubble.setTextSize(22);
        bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int p = (int) (10 * d);
        bubble.setPadding(p * 2, p, p * 2, p);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xCC0E0F12);
        bg.setCornerRadius(14 * d);
        bubble.setBackground(bg);

        bp = new android.view.WindowManager.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        bp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        bp.x = store.overlayX();
        bp.y = store.overlayY();

        bubble.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean moved; long downT;
            @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = bp.x; startY = bp.y; moved = false;
                        downT = android.os.SystemClock.uptimeMillis();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        bp.x = startX + dx; bp.y = startY + dy;
                        try { wm.updateViewLayout(bubble, bp); } catch (Exception ignored) { }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (moved) {
                            store.setOverlayPos(bp.x, bp.y);
                        } else if (android.os.SystemClock.uptimeMillis() - downT < 600) {
                            // korte tik: in-/uitklappen
                            store.setOverlayCollapsed(!store.overlayCollapsed());
                            updateOverlay();
                        } else {
                            // lang indrukken: naar de launcher
                            Intent i = new Intent(TrackerService.this, MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        }
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bubble, bp); } catch (Exception e) { bubble = null; }
    }

    private void removeBubble() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) { }
        }
        bubble = null;
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
}

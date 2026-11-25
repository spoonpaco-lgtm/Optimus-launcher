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
    public static volatile double lastLat = 0, lastLon = 0;
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
    private static final float MOVING_KMH = 6f;
    private int movingStreak = 0;
    private boolean moving = false;
    /** Gewicht auto + bestuurder (kg) voor optrek-verbruik. */
    private static final float CAR_KG = 1600f;
    private static final float MAX_ACCURACY_M = 35f;

    private LocationManager lm;
    private Location last;
    private long lastHarsh = 0L;
    private long lastGeo = 0L;
    private Store store;
    private long lastGpsNote = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, Lang.t("Ritregistratie", "Trip logging"), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Optimus Launcher")
                .setContentText(Lang.t("Ritregistratie actief", "Trip logging active"))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        BatteryHealth.init(this);
        CarLogger.start(this);
        BtMedia.start(this);
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
        detectWakeAfterKill();
        CarSignals.listener = (name, v) -> ui.post(() -> onCarSignal(name, v));
        android.content.IntentFilter uf = new android.content.IntentFilter();
        uf.addAction(Intent.ACTION_MEDIA_MOUNTED);
        uf.addAction(Intent.ACTION_MEDIA_EJECT);
        uf.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        uf.addAction(Intent.ACTION_MEDIA_REMOVED);
        uf.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
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
        if (media != null) { media.remove(); media.collapse(); }
        ui.removeCallbacks(tripTick);
        try { unregisterReceiver(powerRx); } catch (Exception ignored) { }
        try { unregisterReceiver(usbRx); } catch (Exception ignored) { }
        CarSignals.listener = null;
        store.flush();
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
    private static final long STOP_MS = 10L * 60L * 1000L; // rit afsluiten na 10 min stilstand
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

    private long lastAnim = 0L;

    // FlyAudio schiet de app vaak af bij contact uit; dit bestand onthoudt slaap/contact over dat heen.
    private android.content.SharedPreferences powerPrefs() { return getSharedPreferences("power", MODE_PRIVATE); }

    /** Bij (her)start van de dienst: kwamen we net uit slaap of na contact uit? Dan opstartfilmpje. */
    private void detectWakeAfterKill() {
        armGpsWatch();   // dienst (her)start: ook dan GPS bewaken tot de eerste positie
        long el = android.os.SystemClock.elapsedRealtime();
        long slept = el - android.os.SystemClock.uptimeMillis();
        android.content.SharedPreferences sp = powerPrefs();
        long prevEl = sp.getLong("el", -1), prevSlept = sp.getLong("slept", -1), offAt = sp.getLong("contactOffEl", 0);
        boolean sameBoot = prevEl >= 0 && prevEl <= el;
        if (el < 5L * 60L * 1000L) {
            PowerLog.log("echte opstart (bootanimatie)");
        } else if (sameBoot && prevSlept >= 0 && slept - prevSlept > 30_000L) {
            playWakeAnim("dienst herstart na " + PowerLog.dur(slept - prevSlept) + " slaap");
        } else if (sameBoot && offAt > 0 && el - offAt > 5_000L) {
            playWakeAnim("dienst herstart, contact was " + PowerLog.dur(el - offAt) + " uit");
        }
        saveClocks();
    }

    private void saveClocks() {
        long el = android.os.SystemClock.elapsedRealtime();
        powerPrefs().edit().putLong("el", el).putLong("slept", el - android.os.SystemClock.uptimeMillis()).apply();
    }

    /**
     * Opstartfilmpje ("Optimus Black, Ready"). Wordt NIET meteen gespeeld: de MCU houdt het scherm
     * bij lage accu soms een minuut donker. We zetten het filmpje "klaar" en spelen het pas zodra
     * de MCU meldt dat de verlichting echt aan is.
     */
    private void playWakeAnim(String why) {
        long now = android.os.SystemClock.elapsedRealtime();
        powerPrefs().edit().putLong("wantAnimEl", now).putString("wantWhy", why).apply();
        if (CarSignals.screen == 1 && screenOnSince > 0 && now - screenOnSince > 1_000L) {
            startWakeAnim(why + ", scherm was al aan");
            return;
        }
        PowerLog.log("opstartfilmpje klaar, wacht op scherm aan (" + why + ")");
        // Vangnet: geen schermmelding en de motor draait -> MCU heeft het scherm aan.
        ui.postDelayed(() -> {
            if (powerPrefs().getLong("wantAnimEl", 0) == now && CarSignals.screen == -1 && CarSignals.volts >= 13.3) {
                startWakeAnim(why + ", geen schermmelding maar motor draait");
            }
        }, 15_000L);
    }

    private long screenOnSince = 0L;

    private void startWakeAnim(String why) {
        long now = android.os.SystemClock.elapsedRealtime();
        android.content.SharedPreferences sp = powerPrefs();
        long last = sp.getLong("lastAnimEl", 0);
        sp.edit().putLong("wantAnimEl", 0).apply();
        if (last > 0 && last <= now && now - last < 20_000L) return;
        sp.edit().putLong("lastAnimEl", now).apply();
        PowerLog.log("opstartfilmpje: " + why);
        ui.postDelayed(() -> {
            try {
                Intent w = new Intent(this, WakeAnimActivity.class);
                w.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(w);
            } catch (Exception e) { PowerLog.log("opstartfilmpje mislukt: " + e.getMessage()); }
        }, 300L);
    }

    private long lastFlyRedirect = 0L;

    private void onCarSignal(String name, int v) {
        if (name.equals("flylauncher")) {
            // FlyAudio-launcher kwam naar voren: Optimus Launcher er direct weer overheen (max 1x per 3 s)
            long el = android.os.SystemClock.elapsedRealtime();
            if (el - lastFlyRedirect < 3000L) return;
            lastFlyRedirect = el;
            PowerLog.log("FlyAudio-launcher gestart -> Optimus Launcher naar voren");
            ui.postDelayed(() -> {
                try {
                    Intent i = new Intent(this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(i);
                } catch (Exception e) { PowerLog.log("terugschakelen mislukt: " + e.getMessage()); }
            }, 300L);
            return;
        }
        // Scherm gaat echt aan (MCU zet de verlichting aan) na minstens 5 s uit -> opstartfilmpje.
        // Dit is het betrouwbaarste moment: de MCU houdt het scherm soms donker tot de motor draait.
        if (name.equals("scherm")) {
            long now = android.os.SystemClock.elapsedRealtime();
            long offAt = powerPrefs().getLong("screenOffEl", 0);
            if (v == 0) { screenOnSince = 0; powerPrefs().edit().putLong("screenOffEl", now).apply(); }
            else {
                screenOnSince = now;
                long want = powerPrefs().getLong("wantAnimEl", 0);
                if (want > 0 && want <= now && now - want < 10L * 60_000L) {
                    startWakeAnim(powerPrefs().getString("wantWhy", "") + ", scherm aan na " + PowerLog.dur(now - want));
                    lastWakeElapsed = now;
                    restartGps("scherm aan");
                } else if (offAt > 0 && now - offAt > 5_000L) {
                    startWakeAnim("scherm aan na " + PowerLog.dur(now - offAt) + " donker");
                    lastWakeElapsed = now;
                    restartGps("scherm aan");
                }
                powerPrefs().edit().putLong("screenOffEl", 0).apply();
            }
        }
        // Contact aan na minstens 1 min uit -> opstartfilmpje (tablet slaapt niet altijd echt, dus niet alleen op scherm/slaap vertrouwen)
        if (name.equals("contact") && v == 0) store.flush();   // ritwaarden meteen opslaan: FlyAudio schiet de app soms af
        if (name.equals("contact") && (v == 0 || v == 1)) MusicPlayer.get(this).onContact(v == 1);
        if (name.equals("contact")) {
            long now = android.os.SystemClock.elapsedRealtime();
            long offAt = powerPrefs().getLong("contactOffEl", 0);
            if (v == 0) {
                // Contact uit = scherm gaat uit, ook als we die melding missen (FlyAudio schiet ons af).
                CarSignals.screen = 0; screenOnSince = 0;
                powerPrefs().edit().putLong("contactOffEl", now).putLong("screenOffEl", now).apply();
            }
            else if (v == 1) {
                if (offAt > 0 && now - offAt > 5_000L) {
                    playWakeAnim("contact aan na " + PowerLog.dur(now - offAt));
                    lastWakeElapsed = now;
                    restartGps("contact aan");
                }
                powerPrefs().edit().putLong("contactOffEl", 0).apply();
            }
        }
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
            if (!Intent.ACTION_MEDIA_MOUNTED.equals(i.getAction())) {
                // stick eruit: muziek stoppen (anders probeert de speler eindeloos nummers die er niet meer zijn)
                PowerLog.log("USB-stick weg: " + path + " (" + i.getAction() + ")");
                MusicPlayer.get(TrackerService.this).onStickRemoved(path);
                return;
            }
            PowerLog.log("USB-stick: " + path);
            MusicPlayer.get(TrackerService.this).onStickMounted(path);
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
        if (offMs < 0 || offMs > 2L * 60L * 1000L) playWakeAnim("wakker na " + PowerLog.dur(Math.max(0, offMs)));
    }

    /**
     * GPS-bewaking aanzetten: blijft er een positie uit, dan logt tripTick dat na 2 min en vraagt hij
     * de GPS na 4 min (en daarna elke 2 min) opnieuw aan. Moet ook na een herstart van de dienst
     * (FlyAudio schiet de app af tijdens lange slaap), anders staat de bewaking uit — zo ging het op 22 nov.
     */
    private void armGpsWatch() {
        if (System.currentTimeMillis() - lastFixTime < 10_000L) return;   // er zijn al posities
        wakeAt = android.os.SystemClock.elapsedRealtime();
        fixSinceWake = false;
        lastNoFixNote = 0L;
    }

    private void restartGps(String why) {
        if (!"geen fix".equals(why)) armGpsWatch();
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
            saveClocks();
            // GPS na wakker worden in de gaten houden
            if (!fixSinceWake && el - wakeAt > 120_000L && el - lastNoFixNote > 120_000L) {
                lastNoFixNote = el;
                PowerLog.log("nog GEEN GPS-positie, " + PowerLog.dur(el - wakeAt) + " na wakker worden (sats "
                        + satsUsed + "/" + satsWithSignal + "/" + satsVisible + ")");
                if (el - wakeAt > 240_000L) restartGps("geen fix");
            }
            long idle = android.os.SystemClock.elapsedRealtime() - lastMoveElapsed;
            if (!store.tripEnded() && store.tripKm() >= MIN_TRIP_KM && idle >= STOP_MS) finishTrip("stilstand");
            // adressen voor werkritten/locaties aanvullen zodra er internet is (elke 10 min proberen)
            if (el - lastGeo > 600_000L) { lastGeo = el; Geo.fillMissing(TrackerService.this); }
            ui.postDelayed(this, 10_000L);
        }
    };

    private void finishTrip(String why) {
        store.flush();
        store.endTrip();
        try { TripLog.end(this, store, last); } catch (Exception e) { CarLogger.note("rittenlog fout: " + e.getMessage()); }
        CarLogger.note("rit afgesloten (" + why + "): " + store.summaryShort());
        try {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 2, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL_SUM)
                    .setContentTitle(Lang.t("Rit klaar", "Trip done"))
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

        // ---- stilstand-filter tegen GPS-gedrift ----
        // 1) motor uit (accu < 13,0 V) of contact uit = de auto staat stil, wat de GPS ook zegt
        // alleen een betrouwbare spanning (geen oude waarde van vóór de slaap) mag "motor uit" zeggen
        // (en niet ouder dan 60 s, zodat één lage melding de snelheid niet lang op 0 zet)
        boolean voltsKnown = CarSignals.voltsTrusted() && System.currentTimeMillis() - CarSignals.voltsTime < 60_000L;
        boolean parked = CarSignals.contact == 0 || (voltsKnown && CarSignals.volts < 13.0f);
        // 2) GPS moet het eens zijn met zichzelf: snelheid én verplaatsing, een paar fixes achter elkaar
        float implied = -1f;
        if (last != null) {
            float dtS = (loc.getTime() - last.getTime()) / 1000f;
            if (dtS > 0f && dtS < 5f) implied = last.distanceTo(loc) / dtS * 3.6f;
        }
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : 99f;
        boolean looksMoving = kmh >= 15f
                || (kmh >= MOVING_KMH && acc <= 20f && implied >= 4f);
        movingStreak = (looksMoving && !parked) ? Math.min(movingStreak + 1, 10) : 0;
        if (moving) moving = looksMoving && !parked;          // stoppen gaat meteen
        else moving = movingStreak >= 3;                        // rijden pas na 3 fixes op rij
        if (!moving) kmh = 0f;

        speedKmh = kmh;
        if (loc.hasBearing() && moving) bearing = loc.getBearing();
        lastAccuracy = loc.hasAccuracy() ? loc.getAccuracy() : -1f;
        if (loc.hasAccuracy() && loc.getAccuracy() <= 30f) { lastLat = loc.getLatitude(); lastLon = loc.getLongitude(); }

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
                if (!moving) {
                    // stationair alleen als de motor (vermoedelijk) draait
                    if (!store.tripEnded() && !parked && CarSignals.engineRunning()) store.addFuel(Store.IDLE_LPH * dt / 3600f);
                } else {
                    float v1 = last.hasSpeed() ? last.getSpeed() : 0f;
                    float v2 = loc.getSpeed();
                    // rijstijl: hard optrekken (> 2,5 m/s²) of hard remmen (< -3 m/s²), max 1 keer per 3 s
                    if (dt >= 0.8f && !store.tripEnded()) {
                        float a = (v2 - v1) / dt;
                        long el = android.os.SystemClock.elapsedRealtime();
                        if ((a > 2.5f || a < -3.0f) && el - lastHarsh > 3000L) { lastHarsh = el; store.addHarsh(); }
                    }
                    if (v2 > v1) {
                        // kinetische energie / (energie benzine 34,2 MJ/l x motorrendement ~25%)
                        float joule = 0.5f * CAR_KG * (v2 * v2 - v1 * v1);
                        store.addFuel(joule / 8.55e6f);
                    }
                }
            }
        }

        if (moving) {
            lastMoveElapsed = android.os.SystemClock.elapsedRealtime();
            // Vorige rit afgesloten (stilstand of herstart) -> nu begint een nieuwe
            if (store.tripEnded()) { store.startNewTrip(); TripLog.begin(this, store, loc); }
            if (last != null) {
                float km = last.distanceTo(loc) / 1000f;
                float hours = (loc.getTime() - last.getTime()) / 3_600_000f;
                // sprongen en rare tijden negeren
                if (km > 0f && km < 0.5f && hours > 0f && hours < 0.01f) {
                    store.addSegment(km, hours, kmh, now);
                    TripLog.point(this, loc);
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
    private RetroCard bubble;
    private String bubbleTheme = "";
    private android.view.WindowManager.LayoutParams bp;
    private MediaOverlay media;

    private final Runnable overlayTick = new Runnable() {
        @Override public void run() {
            try { updateOverlay(); } catch (Exception ignored) { }
            try { if (media == null) media = new MediaOverlay(TrackerService.this); media.update(true); } catch (Exception ignored) { }
            ui.postDelayed(this, 1000);
        }
    };

    private void updateOverlay() {
        boolean want = android.provider.Settings.canDrawOverlays(this)
                && !MainActivity.visible && !Remote.ownScreenVisible()
                && store.overlayEnabled();
        if (!want) { removeBubble(); return; }
        if (bubble == null) addBubble();
        boolean fresh = System.currentTimeMillis() - lastFixTime < 3000 && speedKmh >= 0;
        String th = getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led");
        if (!th.equals(bubbleTheme)) { bubbleTheme = th; bubble.setTheme(Theme.byId(th)); }
        java.util.Locale us = java.util.Locale.US;
        bubble.big = fresh ? String.valueOf(Math.min(199, Math.round(speedKmh))) : "--";
        float tk = store.tripKm(), tl = store.tripLiters();
        bubble.side = tk >= 0.5f && tl > 0.01f ? String.format(us, "%.1f", tk / tl) : "--.-";
        if (store.overlayCollapsed()) {
            bubble.rows = null;
            bubble.setSizeDp(250, 104);
        } else {
            float cost = store.tripCost();
            bubble.rows = new String[][]{
                    {Lang.t("RIT KM", "TRIP KM"), String.format(us, "%.1f", tk)},
                    {Lang.t("GEM", "AVG"), String.format(us, "%.0f", store.tripAvgKmh())},
                    {"MAX", String.format(us, "%.0f", store.tripMaxKmh())},
                    {"EURO", cost >= 0 ? String.format(us, "%.2f", cost) : "--"}};
            bubble.setSizeDp(250, 270);
        }
        // alleen opnieuw tekenen/plaatsen als er echt iets veranderd is (scheelt werk naast Android Auto)
        StringBuilder sig = new StringBuilder(bubble.big).append('|').append(bubble.side).append('|').append(bubbleTheme);
        if (bubble.rows != null) for (String[] r : bubble.rows) sig.append('|').append(r[1]);
        String sg = sig.toString();
        boolean collapsed = store.overlayCollapsed();
        if (!sg.equals(bubbleSig)) { bubbleSig = sg; bubble.invalidate(); }
        if (collapsed != bubbleCollapsed) {
            bubbleCollapsed = collapsed;
            try { wm.updateViewLayout(bubble, bp); } catch (Exception ignored) { }
        }
    }
    private String bubbleSig = "";
    private Boolean bubbleCollapsed = null;

    private void addBubble() {
        wm = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        bubbleTheme = getSharedPreferences("optimus", MODE_PRIVATE).getString("theme", "led");
        bubble = new RetroCard(this, Theme.byId(bubbleTheme));
        bubble.scale = 0.65f;
        bubble.setSizeDp(250, 104);

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
        bubbleSig = ""; bubbleCollapsed = null;
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

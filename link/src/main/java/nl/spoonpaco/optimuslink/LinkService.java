package nl.spoonpaco.optimuslink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stuurt GPS-positie + tijd van de telefoon naar de Optimus Launcher op de tablet.
 * Route 1: Bluetooth (RFCOMM) naar de gekozen auto — werkt los van Android Auto.
 * Route 2: wifi (UDP) als de telefoon op het netwerk van de tablet (192.168.43.x) zit.
 * GPS staat alleen aan zolang er een verbinding met de auto is (spaart accu).
 */
public class LinkService extends Service implements LocationListener {

    public static final UUID LINK_UUID = UUID.fromString("6f7c1a3e-2b9d-4c55-9a1e-0b5e4c0f7a11");
    public static final int UDP_PORT = 47474;
    public static final String PREFS = "link";
    public static final String KEY_CAR = "carAddress";

    // status voor het scherm
    public static volatile boolean running = false;
    public static volatile boolean wifiCar = false;
    public static volatile boolean btConnected = false;
    public static volatile boolean gpsOn = false;
    public static volatile long lastFix = 0;
    public static volatile long sent = 0;
    public static volatile String lastError = "";

    private static final String CHANNEL = "link";

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private volatile Network carNet;
    private volatile DatagramSocket udp;
    private volatile OutputStream btOut;
    private volatile BluetoothSocket btSock;
    private LocationManager lm;
    private HandlerThread worker;
    private Thread btThread;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Optimus Link", NotificationManager.IMPORTANCE_LOW));
        Notification n = notification("Wacht op de auto…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }

        worker = new HandlerThread("link-gps");
        worker.start();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        watchWifi();
        btThread = new Thread(this::btLoop, "link-bt");
        btThread.setDaemon(true);
        btThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        closeBt();
        try { if (udp != null) udp.close(); } catch (Exception ignored) { }
        stopGps();
        if (btThread != null) btThread.interrupt();
        if (worker != null) worker.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Optimus Link")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        String t = btConnected && wifiCar ? "Verbonden met de auto (Bluetooth + wifi)"
                : btConnected ? "Verbonden met de auto (Bluetooth)"
                : wifiCar ? "Verbonden met de auto (wifi)"
                : "Wacht op de auto…";
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(1, notification(t));
    }

    // ---------- wifi-route ----------

    private void watchWifi() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(Network net, LinkProperties lp) {
                boolean isCar = false;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && a.getHostAddress().startsWith("192.168.43.")) isCar = true;
                }
                if (isCar && carNet == null) {
                    carNet = net;
                    try {
                        DatagramSocket s = new DatagramSocket();
                        net.bindSocket(s);
                        udp = s;
                        wifiCar = true;
                    } catch (Exception e) {
                        lastError = "wifi: " + e.getMessage();
                    }
                    onLinkChanged();
                }
            }
            @Override public void onLost(Network net) {
                if (net.equals(carNet)) {
                    carNet = null;
                    wifiCar = false;
                    try { if (udp != null) udp.close(); } catch (Exception ignored) { }
                    udp = null;
                    onLinkChanged();
                }
            }
        };
        try {
            cm.registerNetworkCallback(req, netCb);
        } catch (Exception e) {
            lastError = "netwerk: " + e.getMessage();
        }
    }

    // ---------- bluetooth-route ----------

    private void btLoop() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        while (running) {
            String addr = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CAR, null);
            if (ad == null || addr == null || !ad.isEnabled()) {
                sleep(5000);
                continue;
            }
            BluetoothSocket s = null;
            try {
                BluetoothDevice d = ad.getRemoteDevice(addr);
                s = d.createInsecureRfcommSocketToServiceRecord(LINK_UUID);
                try { ad.cancelDiscovery(); } catch (Exception ignored) { }
                s.connect();
                btSock = s;
                btOut = s.getOutputStream();
                btConnected = true;
                lastError = "";
                onLinkChanged();
                InputStream in = s.getInputStream();
                //noinspection StatementWithEmptyBody
                while (running && in.read() != -1) { }
            } catch (SecurityException e) {
                lastError = "bluetooth: geen toestemming";
            } catch (Exception e) {
                lastError = "bluetooth: " + e.getMessage();
            } finally {
                closeBt();
                onLinkChanged();
            }
            sleep(8000);
        }
    }

    private void closeBt() {
        btConnected = false;
        btOut = null;
        try { if (btSock != null) btSock.close(); } catch (Exception ignored) { }
        btSock = null;
    }

    // ---------- GPS ----------

    private synchronized void onLinkChanged() {
        updateNotification();
        if (btConnected || wifiCar) startGps(); else stopGps();
    }

    private void startGps() {
        if (gpsOn) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, worker.getLooper());
            gpsOn = true;
        } catch (SecurityException e) {
            lastError = "GPS: geen toestemming";
        } catch (Exception e) {
            lastError = "GPS: " + e.getMessage();
        }
    }

    private void stopGps() {
        if (!gpsOn) return;
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        gpsOn = false;
    }

    @Override
    public void onLocationChanged(Location loc) {
        lastFix = System.currentTimeMillis();
        String msg;
        try {
            JSONObject j = new JSONObject();
            j.put("v", 1);
            j.put("t", System.currentTimeMillis());
            j.put("lat", loc.getLatitude());
            j.put("lon", loc.getLongitude());
            if (loc.hasSpeed()) j.put("spd", loc.getSpeed());
            if (loc.hasBearing()) j.put("brg", loc.getBearing());
            if (loc.hasAccuracy()) j.put("acc", loc.getAccuracy());
            if (loc.hasAltitude()) j.put("alt", loc.getAltitude());
            msg = j.toString();
        } catch (Exception e) {
            return;
        }
        byte[] data = (msg + "\n").getBytes(StandardCharsets.UTF_8);

        OutputStream o = btOut;
        if (o != null) {
            try {
                o.write(data);
                o.flush();
                sent++;
            } catch (Exception e) {
                closeBt();
            }
        }
        DatagramSocket u = udp;
        if (u != null) {
            try {
                u.send(new DatagramPacket(data, data.length,
                        InetAddress.getByName("192.168.43.1"), UDP_PORT));
                sent++;
            } catch (Exception e) {
                lastError = "wifi-zenden: " + e.getMessage();
            }
        }
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    public static void start(Context c) {
        Intent i = new Intent(c, LinkService.class);
        c.startForegroundService(i);
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}

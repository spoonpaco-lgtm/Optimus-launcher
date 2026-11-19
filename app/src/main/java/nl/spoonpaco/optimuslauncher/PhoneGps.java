package nl.spoonpaco.optimuslauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.location.Location;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Ontvangt GPS-posities + tijd van Optimus Link op de telefoon.
 * Bluetooth (RFCOMM-server) én wifi (UDP-poort 47474).
 */
public class PhoneGps {

    public interface Listener { void onPhoneLocation(Location loc); }

    public static final UUID LINK_UUID = UUID.fromString("6f7c1a3e-2b9d-4c55-9a1e-0b5e4c0f7a11");
    public static final UUID LINK_UUID_SECURE = UUID.fromString("6f7c1a3e-2b9d-4c55-9a1e-0b5e4c0f7a12");
    public static final int UDP_PORT = 47474;

    public static volatile long lastMsg = 0L;
    public static volatile String via = "-";
    public static volatile long messages = 0L;
    public static volatile boolean btWaiting = false;
    public static volatile boolean btClient = false;
    public static volatile String lastError = "";

    private static Listener listener;
    private static volatile boolean started = false;

    public static synchronized void start(Listener l) {
        listener = l;
        if (started) return;
        started = true;
        Thread u = new Thread(PhoneGps::udpLoop, "phonegps-udp");
        u.setDaemon(true);
        u.start();
        Thread b = new Thread(() -> btLoop(false), "phonegps-bt");
        b.setDaemon(true);
        b.start();
        Thread bs = new Thread(() -> btLoop(true), "phonegps-bt-secure");
        bs.setDaemon(true);
        bs.start();
    }

    public static boolean fresh() {
        return System.currentTimeMillis() - lastMsg < 3000;
    }

    private static void udpLoop() {
        while (true) {
            try (DatagramSocket s = new DatagramSocket(UDP_PORT)) {
                byte[] buf = new byte[2048];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    handle(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim(), "wifi");
                }
            } catch (Exception e) {
                lastError = "udp: " + e.getMessage();
            }
            sleep(5000);
        }
    }

    private static void btLoop(boolean secure) {
        while (true) {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) { sleep(10000); continue; }
            BluetoothServerSocket ss = null;
            BluetoothSocket s = null;
            try {
                ss = secure
                        ? ad.listenUsingRfcommWithServiceRecord("OptimusLinkS", LINK_UUID_SECURE)
                        : ad.listenUsingInsecureRfcommWithServiceRecord("OptimusLink", LINK_UUID);
                btWaiting = true;
                s = ss.accept();
                btWaiting = false;
                try { ss.close(); } catch (Exception ignored) { }
                ss = null;
                btClient = true;
                BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                String route = secure ? "bluetooth (beveiligd)" : "bluetooth";
                while ((line = br.readLine()) != null) handle(line.trim(), route);
            } catch (Exception e) {
                lastError = (secure ? "bt-beveiligd: " : "bt: ") + e.getMessage();
            } finally {
                btWaiting = false;
                btClient = false;
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                try { if (ss != null) ss.close(); } catch (Exception ignored) { }
            }
            sleep(2000);
        }
    }

    private static void handle(String json, String route) {
        if (json.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(json);
            long t = j.optLong("t", 0L);
            if (t > 0) Clock.fromPhone(t);
            Location loc = new Location("phone");
            loc.setLatitude(j.getDouble("lat"));
            loc.setLongitude(j.getDouble("lon"));
            loc.setTime(t > 0 ? t : Clock.now());
            if (j.has("spd")) loc.setSpeed((float) j.getDouble("spd"));
            if (j.has("brg")) loc.setBearing((float) j.getDouble("brg"));
            if (j.has("acc")) loc.setAccuracy((float) j.getDouble("acc"));
            if (j.has("alt")) loc.setAltitude(j.getDouble("alt"));
            lastMsg = System.currentTimeMillis();
            via = route;
            messages++;
            Listener l = listener;
            if (l != null) l.onPhoneLocation(loc);
        } catch (Exception e) {
            lastError = "bericht: " + e.getMessage();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}

package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Klein logboek voor aan/uit/slaap en GPS: files/carlog/power.txt (max ~1 MB).
 * Doel: uitzoeken of de tablet bij contact uit echt uitgaat of slaapt,
 * en of de GPS na het wakker worden weer start.
 */
public final class PowerLog {

    private static final long MAX_BYTES = 1024L * 1024L;
    private static final SimpleDateFormat TS = new SimpleDateFormat("dd-MM HH:mm:ss", Locale.US);
    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private static File file;

    private PowerLog() { }

    public static synchronized void init(Context c) {
        if (file != null) return;
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File dir = new File(base, "carlog");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        file = new File(dir, "power.txt");
    }

    /** Uptime telt niet tijdens diepe slaap, elapsed wel; het verschil = totale slaaptijd sinds opstart. */
    public static String clocks() {
        long el = SystemClock.elapsedRealtime(), up = SystemClock.uptimeMillis();
        return String.format(Locale.US, "aan %s, waarvan geslapen %s", dur(el), dur(el - up));
    }

    public static String dur(long ms) {
        long s = ms / 1000;
        if (s < 120) return s + " s";
        if (s < 7200) return (s / 60) + " min";
        return String.format(Locale.US, "%d u %02d min", s / 3600, (s / 60) % 60);
    }

    public static synchronized void log(String msg) {
        String line = TS.format(new Date(Clock.now())) + "  " + msg;
        recent.addLast(line);
        while (recent.size() > 12) recent.removeFirst();
        CarLogger.note("power: " + msg);
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) trim();
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(line);
                w.write('\n');
            }
        } catch (Exception ignored) { }
    }

    /** Oudste helft weggooien. */
    private static void trim() throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            long half = r.length() / 2;
            r.seek(half);
            r.readLine();
            byte[] rest = new byte[(int) (r.length() - r.getFilePointer())];
            r.readFully(rest);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(file, false)) { o.write(rest); }
        }
    }

    public static synchronized String recentText() {
        StringBuilder sb = new StringBuilder();
        for (String s : recent) sb.append("  ").append(s).append('\n');
        return sb.toString();
    }
}

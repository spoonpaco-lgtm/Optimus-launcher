package nl.spoonpaco.optimuslauncher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Autolog: schrijft alles wat binnenkomt in het Android-systeemlogboek
 * (waar de MCU/CAN-signalen van FlyAudio terechtkomen) naar een bestand per dag,
 * aangevuld met de eigen GPS-snelheid om signalen te kunnen vergelijken.
 *
 * Bestanden: /sdcard/Android/data/nl.spoonpaco.optimuslauncher/files/carlog/
 * Vereist READ_LOGS, eenmalig te geven met:
 *   adb shell pm grant nl.spoonpaco.optimuslauncher android.permission.READ_LOGS
 */
public class CarLogger {

    /** 0 = uit, 1 = alleen GPS (geen READ_LOGS), 2 = volledig. */
    public static volatile int state = 0;

    private static final long MAX_BYTES_PER_DAY = 40L * 1024 * 1024;
    private static final int KEEP_DAYS = 7;
    private static final Object LOCK = new Object();

    private static File dir;
    private static BufferedWriter out;
    private static String outDay = "";
    private static long outBytes = 0;
    private static long lastFlush = 0;
    private static Process proc;
    private static Thread reader;

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    public static void start(Context c) {
        synchronized (LOCK) {
            if (reader != null) return;
            File base = c.getExternalFilesDir(null);
            if (base == null) base = c.getFilesDir();
            dir = new File(base, "carlog");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            cleanup();
        }
        boolean canRead = c.checkSelfPermission(Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        note("=== Optimus autolog start — " + Build.MANUFACTURER + " " + Build.MODEL
                + " sdk " + Build.VERSION.SDK_INT + " — READ_LOGS " + (canRead ? "ja" : "NEE") + " ===");
        if (!canRead) {
            state = 1;
            return;
        }
        state = 2;
        reader = new Thread(CarLogger::readLoop, "carlog");
        reader.setDaemon(true);
        reader.start();
    }

    public static void stop() {
        synchronized (LOCK) {
            if (proc != null) proc.destroy();
            proc = null;
            reader = null;
            closeOut();
        }
    }

    /** Eigen regel toevoegen (bijv. GPS-snelheid). */
    public static void note(String msg) {
        write(TS.format(new Date()) + "  [optimus] " + msg);
    }

    private static void readLoop() {
        String[][] commands = {
                {"logcat", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "events", "-b", "kernel"},
                {"logcat", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "events"},
        };
        int variant = 0;
        int quickFails = 0;
        while (true) {
            synchronized (LOCK) { if (reader == null) return; }
            long started = System.currentTimeMillis();
            try {
                Process p = Runtime.getRuntime().exec(commands[variant]);
                synchronized (LOCK) { proc = p; }
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()), 16384);
                String line;
                while ((line = br.readLine()) != null) {
                    write(line);
                }
            } catch (Exception e) {
                note("logcat fout: " + e.getMessage());
            }
            if (System.currentTimeMillis() - started > 60_000) {
                quickFails = 0;           // liep goed, gewoon herstarten
            } else if (variant < commands.length - 1) {
                variant++;                // snel gestopt: probeer zonder kernel-buffer
                note("logcat-variant " + variant + " wordt geprobeerd");
            } else if (++quickFails >= 3) {
                state = 1;
                note("logcat lukt niet, alleen GPS wordt gelogd");
                return;
            }
            try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
        }
    }

    /**
     * Ruis die geen autodata bevat en het logboek in minuten volspamt
     * (gemeten op de tablet: GPS-driver, nep-handtekening, geluidsmodule, DSP).
     * MCU-data (McuDataAnalysisManager) en GPIO (FlyaudioGpioJNI) blijven bewaard.
     */
    private static final String[] NOISE = {
            " gps_zkw ",
            "FAKE_PACKAGE_SIGNATURE",
            " SoundEffect:",
            "GooglePlayServicesUtil",
            "DspManagerPointcut",
            " DspManager:",
            " DspClient:",
            "aictlog-ShellUtils",
            "FansHelper.sendCpuTemp",
            "[Gralloc]",
    };

    static boolean isNoise(String line) {
        for (String n : NOISE) {
            if (line.contains(n)) return true;
        }
        return false;
    }

    private static void write(String line) {
        if (isNoise(line)) return;
        synchronized (LOCK) {
            try {
                String day = DAY.format(new Date());
                if (!day.equals(outDay) || out == null) {
                    closeOut();
                    File f = new File(dir, "carlog-" + day + ".txt");
                    outBytes = f.length();
                    out = new BufferedWriter(new FileWriter(f, true), 32768);
                    outDay = day;
                }
                if (outBytes > MAX_BYTES_PER_DAY) return;
                out.write(line);
                out.write('\n');
                outBytes += line.length() + 1;
                long now = System.currentTimeMillis();
                if (now - lastFlush > 2000) {
                    out.flush();
                    lastFlush = now;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeOut() {
        try { if (out != null) { out.flush(); out.close(); } } catch (Exception ignored) { }
        out = null;
    }

    private static void cleanup() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L;
        for (File f : files) {
            if (f.getName().startsWith("carlog-") && f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}

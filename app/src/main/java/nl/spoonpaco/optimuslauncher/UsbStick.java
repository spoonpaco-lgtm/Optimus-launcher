package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * USB-stick erin:
 *  1. logs (autolog van vandaag/gisteren, power.txt, rit) naar de stick kopiëren
 *     -> map Android/data/nl.spoonpaco.optimuslauncher/files/OptimusLog-JJJJMMDD-UUMM op de stick
 *     (Android laat apps alleen in die map op een stick schrijven)
 *  2. staat er optimus-launcher-X.Y.Z.apk in de hoofdmap en is die nieuwer -> installeren aanbieden
 */
final class UsbStick {

    private UsbStick() { }

    static void handle(Context c, String mount, Store store) {
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        exportLogs(c, mount, store);
        offerUpdate(c, mount);
    }

    private static void exportLogs(Context c, String mount, Store store) {
        try {
            File base = null;
            for (File f : c.getExternalFilesDirs(null)) {
                if (f != null && f.getAbsolutePath().startsWith(mount)) { base = f; break; }
            }
            if (base == null) base = new File(mount, "OptimusLog");
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date(Clock.now()));
            File dest = new File(base, "OptimusLog-" + stamp);
            if (!dest.mkdirs() && !dest.isDirectory()) throw new Exception("kan map niet maken op stick");

            File src = c.getExternalFilesDir(null);
            if (src == null) src = c.getFilesDir();
            File logDir = new File(src, "carlog");
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String today = day.format(new Date(Clock.now()));
            String yesterday = day.format(new Date(Clock.now() - 86_400_000L));
            int n = 0;
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String nm = f.getName();
                    if (nm.equals("power.txt") || nm.contains(today) || nm.contains(yesterday)) {
                        copy(f, new File(dest, nm));
                        n++;
                    }
                }
            }
            File crash = new File(c.getFilesDir(), "crash.txt");
            if (crash.exists()) { copy(crash, new File(dest, "crash.txt")); n++; }
            try (FileWriter w = new FileWriter(new File(dest, "rit.txt"))) {
                w.write("Optimus Launcher " + version(c) + "\n\n");
                w.write("Laatste ritsamenvatting:\n" + store.summaryText() + "\n\n");
                w.write("Huidige rit: " + store.summaryShort() + "\n");
                w.write(String.format(Locale.US, "Kalibratiefactor verbruik: %.3f\n", store.calFactor()));
            }
            PowerLog.log("USB-stick: " + n + " logbestanden gekopieerd naar " + dest.getAbsolutePath());
            toast(c, "Logs gekopieerd naar USB-stick (" + n + " bestanden)");
        } catch (Exception e) {
            PowerLog.log("USB-stick: kopiëren mislukt: " + e.getMessage());
            toast(c, "Logs kopiëren naar USB-stick mislukt: " + e.getMessage());
        }
    }

    private static void offerUpdate(Context c, String mount) {
        try {
            File[] files = new File(mount).listFiles();
            if (files == null) return;
            File best = null;
            String bestVer = version(c);
            for (File f : files) {
                String nm = f.getName();
                if (nm.startsWith("optimus-launcher") && nm.endsWith(".apk")) {
                    String v = nm.replace("optimus-launcher-", "").replace(".apk", "");
                    if (Updater.isNewer(v, bestVer)) { best = f; bestVer = v; }
                }
            }
            if (best == null) return;
            copy(best, new File(c.getFilesDir(), ApkProvider.FILE_NAME));
            PowerLog.log("USB-stick: update " + bestVer + " gevonden");
            final String v = bestVer;
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(c, "Update " + v + " van USB-stick", Toast.LENGTH_LONG).show();
                try { Updater.installFromContext(c); } catch (Exception e) {
                    Toast.makeText(c, "Installeren mislukt: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            PowerLog.log("USB-stick: update zoeken mislukt: " + e.getMessage());
        }
    }

    private static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "0"; }
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    private static void toast(Context c, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
    }
}

package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Kijkt op GitHub Releases of er een nieuwere versie is,
 * downloadt de APK en opent de Android-installer (één tik).
 */
public class Updater {

    private static final String LATEST =
            "https://api.github.com/repos/spoonpaco-lgtm/Optimus-launcher/releases/latest";

    private static volatile boolean busy = false;

    public static void check(final Activity a, final boolean manual) {
        if (busy) {
            if (manual) toast(a, "Update wordt al gecontroleerd");
            return;
        }
        busy = true;
        if (manual) toast(a, "Zoeken naar update…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String current = a.getPackageManager()
                            .getPackageInfo(a.getPackageName(), 0).versionName;
                    JSONObject rel = new JSONObject(get(LATEST));
                    String tag = rel.optString("tag_name", "").replaceFirst("^v", "");
                    if (!isNewer(tag, current)) {
                        if (manual) toast(a, "Je hebt de nieuwste versie (" + current + ")");
                        return;
                    }
                    String apkUrl = null;
                    JSONArray assets = rel.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject as = assets.getJSONObject(i);
                        String nm = as.optString("name");
                        if (nm.startsWith("optimus-launcher") && nm.endsWith(".apk")) {
                            apkUrl = as.optString("browser_download_url");
                            break;
                        }
                    }
                    if (apkUrl == null) {
                        if (manual) toast(a, "Geen APK gevonden in versie " + tag);
                        return;
                    }
                    toast(a, "Versie " + tag + " wordt gedownload…");
                    File out = new File(a.getFilesDir(), ApkProvider.FILE_NAME);
                    download(apkUrl, out);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() { install(a); }
                    });
                } catch (Exception e) {
                    if (manual) toast(a, "Update mislukt: geen internet of GitHub onbereikbaar");
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    private static void install(Activity a) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            toast(a, "Sta 'installeren' toe voor Optimus Launcher en druk dan opnieuw op Update");
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(s);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ApkProvider.URI, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    /** "0.0.3" nieuwer dan "0.0.2"? */
    static boolean isNewer(String remote, String local) {
        if (remote == null || remote.isEmpty() || local == null) return false;
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int rv = i < r.length ? parse(r[i]) : 0;
            int lv = i < l.length ? parse(l[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "OptimusLauncher");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File out) throws Exception {
        File tmp = new File(out.getPath() + ".part");
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/octet-stream");
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        if (out.exists() && !out.delete()) throw new Exception("kan oude update niet wissen");
        if (!tmp.renameTo(out)) throw new Exception("kan update niet opslaan");
    }

    private static void toast(final Activity a, final String msg) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(a, msg, Toast.LENGTH_LONG).show(); }
        });
    }
}

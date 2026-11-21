package nl.spoonpaco.optimuslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

/**
 * Muziekinfo via Bluetooth (AVRCP): de Bluetooth-dienst van Android zelf biedt een mediabrowser aan
 * met titel/artiest/album van de telefoon. Daar is geen meldingtoegang voor nodig.
 */
public final class BtMedia {
    private static final ComponentName SVC =
            new ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService");

    public static volatile String status = "niet gestart";
    public static volatile String title, artist, album;
    public static volatile boolean playing;
    public static volatile long updated;

    private static MediaBrowser browser;
    private static MediaController controller;
    private static String lastLogged = "";
    private static final Handler H = new Handler(Looper.getMainLooper());

    private BtMedia() { }

    public static void start(Context ctx) {
        final Context c = ctx.getApplicationContext();
        H.post(() -> connect(c));
    }

    private static void connect(Context c) {
        try {
            if (browser != null) try { browser.disconnect(); } catch (Exception ignored) { }
            browser = new MediaBrowser(c, SVC, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    try {
                        controller = new MediaController(c, browser.getSessionToken());
                        controller.registerCallback(cb, H);
                        if (!"verbonden".equals(status)) CarLogger.note("btmedia: verbonden met Bluetooth-muziek");
                        status = "verbonden";
                        read();
                    } catch (Exception e) { status = "fout: " + e.getMessage(); }
                }
                @Override public void onConnectionSuspended() { retry(c); }
                @Override public void onConnectionFailed() { status = "mislukt"; retry(c); }
            }, null);
            browser.connect();
            status = "verbinden…";
        } catch (Exception e) { status = "fout: " + e.getMessage(); retry(c); }
    }

    private static void retry(Context c) { H.postDelayed(() -> connect(c), 30_000L); }

    private static final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { read(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { read(); }
        @Override public void onSessionDestroyed() { status = "sessie weg"; }
    };

    private static void read() {
        MediaController mc = controller;
        if (mc == null) return;
        MediaMetadata m = mc.getMetadata();
        PlaybackState st = mc.getPlaybackState();
        title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        album = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        updated = System.currentTimeMillis();
        String line = (playing ? "speelt" : "stil") + " | " + title + " | " + artist + " | " + album;
        if (!line.equals(lastLogged)) { lastLogged = line; CarLogger.note("btmedia: " + line); }
    }

    public static String report() {
        return "bluetooth-muziek: " + status + "\n  " + (playing ? "speelt" : "stil")
                + " | titel=" + title + " | artiest=" + artist + " | album=" + album + "\n";
    }
}

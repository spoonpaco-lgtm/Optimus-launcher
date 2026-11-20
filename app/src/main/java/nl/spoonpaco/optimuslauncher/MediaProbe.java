package nl.spoonpaco.optimuslauncher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Meetfunctie: wat laat Android Auto (CarLetter/ZLink) zien aan de rest van Android?
 * Toont actieve mediaspelers (titel/artiest) en recente meldingen, en schrijft wijzigingen naar de autolog.
 * Vereist meldingtoegang voor Optimus Launcher.
 */
public class MediaProbe extends NotificationListenerService {

    public static volatile boolean connected = false;
    private static final List<String> recent = new ArrayList<>();
    private static String lastMeta = "";
    private MediaSessionManager msm;
    private final List<MediaController> watched = new ArrayList<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb = this::watch;

    @Override public void onListenerConnected() {
        connected = true;
        CarLogger.note("mediaprobe: meldingtoegang actief");
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, MediaProbe.class);
            msm.addOnActiveSessionsChangedListener(sessionsCb, cn);
            watch(msm.getActiveSessions(cn));
        } catch (Exception e) { CarLogger.note("mediaprobe: sessies niet leesbaar: " + e.getMessage()); }
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsCb); } catch (Exception ignored) { }
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : watched) try { c.unregisterCallback(cb); } catch (Exception ignored) { }
        watched.clear();
        if (list == null) return;
        StringBuilder names = new StringBuilder();
        for (MediaController c : list) {
            watched.add(c);
            names.append(c.getPackageName()).append(' ');
            try { c.registerCallback(cb); } catch (Exception ignored) { }
            logMeta(c);
        }
        CarLogger.note("mediaprobe: spelers = " + (names.length() == 0 ? "geen" : names.toString().trim()));
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { for (MediaController c : watched) logMeta(c); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { for (MediaController c : watched) logMeta(c); }
    };

    private static void logMeta(MediaController c) {
        String d = describe(c);
        if (!d.equals(lastMeta)) { lastMeta = d; CarLogger.note("mediaprobe: " + d); }
    }

    static String describe(MediaController c) {
        StringBuilder s = new StringBuilder(c.getPackageName());
        PlaybackState st = c.getPlaybackState();
        s.append(" [").append(st == null ? "?" : stateName(st.getState())).append("]");
        MediaMetadata m = c.getMetadata();
        if (m == null) return s.append(" geen metadata").toString();
        String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String al = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        s.append(" titel=").append(t).append(" | artiest=").append(a).append(" | album=").append(al);
        if (dur > 0) s.append(" | ").append(dur / 1000).append(" s");
        s.append(m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null || m.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ? " | hoes: ja" : " | hoes: nee");
        return s.toString();
    }

    private static String stateName(int s) {
        switch (s) {
            case PlaybackState.STATE_PLAYING: return "speelt";
            case PlaybackState.STATE_PAUSED: return "pauze";
            case PlaybackState.STATE_STOPPED: return "gestopt";
            case PlaybackState.STATE_BUFFERING: return "laden";
            default: return "status " + s;
        }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) return;
        Bundle x = sbn.getNotification().extras;
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(Clock.now())) + " "
                + sbn.getPackageName() + " | " + x.getCharSequence(Notification.EXTRA_TITLE)
                + " | " + x.getCharSequence(Notification.EXTRA_TEXT)
                + (x.containsKey(Notification.EXTRA_MEDIA_SESSION) ? " | MEDIA" : "");
        synchronized (recent) {
            recent.add(0, line);
            while (recent.size() > 8) recent.remove(recent.size() - 1);
        }
        CarLogger.note("melding: " + line);
    }

    /** Blok voor het debugscherm. */
    public static String report(Context ctx) {
        StringBuilder s = new StringBuilder("== Android Auto / media (meting) ==\n");
        s.append(BtMedia.report());
        boolean allowed = false;
        try {
            String en = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            allowed = en != null && en.contains(ctx.getPackageName());
        } catch (Exception ignored) { }
        s.append("meldingtoegang: ").append(allowed ? (connected ? "ja, actief" : "ja, nog niet verbonden") : "NEE (op dit toestel geblokkeerd; via adb)").append('\n');
        if (allowed) {
            try {
                MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                List<MediaController> list = m.getActiveSessions(new ComponentName(ctx, MediaProbe.class));
                if (list.isEmpty()) s.append("spelers: geen\n");
                for (MediaController c : list) s.append("speler: ").append(describe(c)).append('\n');
            } catch (Exception e) { s.append("spelers: fout ").append(e.getMessage()).append('\n'); }
        }
        synchronized (recent) {
            s.append("laatste meldingen:").append(recent.isEmpty() ? " geen\n" : "\n");
            for (String r : recent) s.append("  ").append(r).append('\n');
        }
        return s.append('\n').toString();
    }
}

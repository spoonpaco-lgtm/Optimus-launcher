package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Speakertest: per kant drie tonen (midden 1 kHz, laag 100 Hz, hoog 6 kHz), 2 s elk.
 * Laag = woofer in de deur, hoog = tweeter. Volgorde: LINKS, RECHTS, nog een keer.
 */
public final class SpeakerTest {
    private static volatile boolean running;

    public static void start(Context c, String only) {
        if (running) return;
        running = true;
        final Context ctx = c.getApplicationContext();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            AudioManager.OnAudioFocusChangeListener fl = f -> { };
            try {
                if (am != null) am.requestAudioFocus(fl, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                MusicPlayer.get(ctx).pause();
                CarLogger.note("speakertest gestart" + (only == null ? "" : " (" + only + ")"));
                String[] sides = only == null ? new String[]{"L", "R", "L", "R"} : new String[]{only, only};
                int[] freqs = {1000, 100, 6000};
                String[] what = {"MIDDEN", "LAAG (woofer)", "HOOG (tweeter)"};
                for (String side : sides) {
                    for (int i = 0; i < 3; i++) {
                        final String msg = (side.equals("L") ? "LINKS" : "RECHTS") + " · " + what[i];
                        ui.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
                        tone(freqs[i], 2000, side.equals("L"));
                        sleep(300);
                    }
                    sleep(900);
                }
            } catch (Exception e) {
                CarLogger.note("speakertest fout " + e.getMessage());
            } finally {
                if (am != null) am.abandonAudioFocus(fl);
                CarLogger.note("speakertest klaar");
                ui.post(() -> Toast.makeText(ctx, "Speakertest klaar", Toast.LENGTH_SHORT).show());
                running = false;
            }
        }, "speakertest").start();
    }

    private static void tone(int hz, int ms, boolean left) {
        int rate = 44100, n = rate * ms / 1000;
        short[] buf = new short[n * 2];
        for (int i = 0; i < n; i++) {
            double env = Math.min(1.0, Math.min(i, n - i) / (rate * 0.03));   // zacht in/uit, geen klik
            short v = (short) (Math.sin(2 * Math.PI * hz * i / rate) * 0.45 * env * Short.MAX_VALUE);
            buf[2 * i] = left ? v : 0;
            buf[2 * i + 1] = left ? 0 : v;
        }
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, buf.length * 2, AudioTrack.MODE_STATIC);
        t.write(buf, 0, buf.length);
        t.play();
        sleep(ms + 50);
        try { t.stop(); } catch (Exception ignored) { }
        t.release();
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}

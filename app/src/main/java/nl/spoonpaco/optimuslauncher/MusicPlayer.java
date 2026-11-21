package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Muziekspeler voor USB-stick/SD: bibliotheek (mappen, artiesten, albums), wachtrij, afspelen,
 * audiofocus (Android Auto pauzeert netjes) en een MediaSession voor de stuurwielknoppen.
 * Leeft in het app-proces (dat door de ritdienst blijft draaien), dus speelt door buiten de launcher.
 */
public final class MusicPlayer {
    public static final class Track {
        public String path, title, artist = "", album = "", genre = "", folder;
        public int dur, no;
        public String label() { return title != null && !title.isEmpty() ? title : new File(path).getName().replaceAll("\\.[^.]+$", ""); }
    }

    public interface Listener { void onChanged(); }

    private static MusicPlayer inst;
    public static synchronized MusicPlayer get(Context c) { if (inst == null) inst = new MusicPlayer(c.getApplicationContext()); return inst; }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    public final List<Track> library = new ArrayList<>();
    public volatile boolean scanning = false;
    public volatile String root = null;           // map van de stick, of null
    public volatile int indexed = 0;

    private List<Track> queue = new ArrayList<>();
    private List<Track> order = new ArrayList<>(); // afspeelvolgorde (geschud of niet)
    private int pos = -1;
    public boolean shuffle = false;
    private MediaPlayer mp;
    private boolean prepared = false, wantPlay = false, pausedByFocus = false;
    private MediaSession session;
    private Bitmap art; private String artFor = "";

    private MusicPlayer(Context c) {
        ctx = c;
        shuffle = c.getSharedPreferences("music", Context.MODE_PRIVATE).getBoolean("shuffle", false);
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    private void changed() { h.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onChanged(); }); }

    // ---------------------------------------------------------------- bibliotheek

    private static final String[] EXT = {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav", ".opus", ".wma"};

    /** Zoekt de stick (of SD) en leest alle muziek in. Tweede keer gaat snel dankzij de index. */
    public void scan() {
        if (scanning) return;
        scanning = true; changed();
        new Thread(() -> {
            try {
                File r = findRoot();
                root = r == null ? null : r.getAbsolutePath();
                List<File> files = new ArrayList<>();
                if (r != null) walk(r, files, 0);
                Map<String, JSONObject> idx = loadIndex();
                List<Track> out = new ArrayList<>();
                indexed = 0;
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                for (File f : files) {
                    Track t = new Track();
                    t.path = f.getAbsolutePath();
                    t.folder = f.getParentFile() == null ? "" : f.getParentFile().getName();
                    String key = t.path + "|" + f.length();
                    JSONObject o = idx.get(key);
                    if (o != null && !o.has("g")) {
                        // oud index-item zonder genre: alleen genre bijlezen
                        try { mmr.setDataSource(t.path); o.put("g", genre(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))); }
                        catch (Exception ignored) { try { o.put("g", ""); } catch (Exception ignored2) { } }
                    }
                    if (o == null) {
                        o = new JSONObject();
                        try {
                            mmr.setDataSource(t.path);
                            o.put("t", nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)));
                            o.put("a", nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)));
                            o.put("b", nz(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)));
                            o.put("d", parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
                            o.put("n", parse(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)));
                            o.put("g", genre(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)));
                        } catch (Exception ignored) { }
                        idx.put(key, o);
                    }
                    t.title = o.optString("t"); t.artist = o.optString("a"); t.album = o.optString("b"); t.genre = o.optString("g");
                    t.dur = o.optInt("d"); t.no = o.optInt("n");
                    out.add(t);
                    if (++indexed % 25 == 0) changed();
                }
                try { mmr.release(); } catch (Exception ignored) { }
                saveIndex(idx);
                Collections.sort(out, (a, b) -> a.path.compareToIgnoreCase(b.path));
                synchronized (library) { library.clear(); library.addAll(out); }
                CarLogger.note("muziek: " + out.size() + " nummers op " + root);
            } catch (Exception e) {
                CarLogger.note("muziek: inlezen mislukt " + e.getMessage());
            } finally {
                scanning = false; changed();
                if (pendingResume) h.post(this::doResume);
            }
        }, "music-scan").start();
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static int parse(String s) {
        if (s == null) return 0;
        try { int k = s.indexOf('/'); return Integer.parseInt((k > 0 ? s.substring(0, k) : s).trim()); } catch (Exception e) { return 0; }
    }

    private File findRoot() {
        // 1) app-map op een verwisselbare opslag -> wortel van die stick
        File[] ext = ctx.getExternalFilesDirs(null);
        if (ext != null) for (int i = 1; i < ext.length; i++) {
            if (ext[i] == null) continue;
            String p = ext[i].getAbsolutePath();
            int k = p.indexOf("/Android/data/");
            if (k > 0) { File r = new File(p.substring(0, k)); if (r.canRead()) return r; }
        }
        // 2) /storage/<UUID>
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File f : st) {
            String n = f.getName();
            if (n.equals("emulated") || n.equals("self") || !f.isDirectory()) continue;
            if (f.canRead()) return f;
        }
        // 3) interne Music-map als reserve
        File m = new File("/sdcard/Music");
        return m.isDirectory() ? m : null;
    }

    private void walk(File dir, List<File> out, int depth) {
        if (depth > 8 || out.size() > 8000) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(".") || f.getName().equals("Android") || f.getName().equals("LOST.DIR")) continue;
            if (f.isDirectory()) walk(f, out, depth + 1);
            else {
                String n = f.getName().toLowerCase(Locale.ROOT);
                for (String e : EXT) if (n.endsWith(e)) { out.add(f); break; }
            }
        }
    }

    private File indexFile() { return new File(ctx.getFilesDir(), "music-index.json"); }

    private Map<String, JSONObject> loadIndex() {
        Map<String, JSONObject> m = new HashMap<>();
        try (FileInputStream in = new FileInputStream(indexFile())) {
            byte[] b = new byte[(int) indexFile().length()];
            int n = in.read(b);
            JSONObject o = new JSONObject(new String(b, 0, Math.max(0, n), "UTF-8"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); m.put(k, o.getJSONObject(k)); }
        } catch (Exception ignored) { }
        return m;
    }

    private void saveIndex(Map<String, JSONObject> m) {
        try (FileOutputStream out = new FileOutputStream(indexFile())) {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, JSONObject> e : m.entrySet()) o.put(e.getKey(), e.getValue());
            out.write(o.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    /** ID3v1-genrenummers zoals "(17)" of "17" omzetten naar een naam; meerdere genres: eerste. */
    private static final String[] ID3 = {"Blues","Classic Rock","Country","Dance","Disco","Funk","Grunge","Hip-Hop","Jazz","Metal",
            "New Age","Oldies","Other","Pop","R&B","Rap","Reggae","Rock","Techno","Industrial","Alternative","Ska","Death Metal",
            "Pranks","Soundtrack","Euro-Techno","Ambient","Trip-Hop","Vocal","Jazz+Funk","Fusion","Trance","Classical","Instrumental",
            "Acid","House","Game","Sound Clip","Gospel","Noise","Alternative Rock","Bass","Soul","Punk","Space","Meditative",
            "Instrumental Pop","Instrumental Rock","Ethnic","Gothic","Darkwave","Techno-Industrial","Electronic","Pop-Folk",
            "Eurodance","Dream","Southern Rock","Comedy","Cult","Gangsta","Top 40","Christian Rap","Pop/Funk","Jungle",
            "Native American","Cabaret","New Wave","Psychedelic","Rave","Showtunes","Trailer","Lo-Fi","Tribal","Acid Punk",
            "Acid Jazz","Polka","Retro","Musical","Rock & Roll","Hard Rock"};
    static String genre(String g) {
        if (g == null) return "";
        g = g.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:\\((\\d+)\\)(.*)|(\\d+))$").matcher(g);
        if (m.matches()) {
            try {
                int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(3));
                String rest = m.group(2) == null ? "" : m.group(2).trim();
                if (!rest.isEmpty()) g = rest; else if (n < ID3.length) return ID3[n];
            } catch (Exception ignored) { }
        }
        for (String sep : new String[]{";", "/", ","}) { int i = g.indexOf(sep); if (i > 0) { g = g.substring(0, i).trim(); break; } }
        return g.isEmpty() ? g : g.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + g.substring(1);
    }

    /** Groepen voor de lijst: mode 0 = mappen, 1 = artiesten, 2 = albums, 3 = genres. Sleutel -> nummers. */
    public LinkedHashMap<String, List<Track>> groups(int mode) {
        Map<String, List<Track>> m = new HashMap<>();
        synchronized (library) {
            for (Track t : library) {
                String k = mode == 0 ? t.folder : mode == 1 ? (t.artist.isEmpty() ? Lang.t("ONBEKENDE ARTIEST", "UNKNOWN ARTIST") : t.artist)
                        : mode == 2 ? (t.album.isEmpty() ? Lang.t("ONBEKEND ALBUM", "UNKNOWN ALBUM") : t.album)
                        : (t.genre.isEmpty() ? Lang.t("GEEN GENRE", "NO GENRE") : t.genre);
                List<Track> l = m.get(k);
                if (l == null) { l = new ArrayList<>(); m.put(k, l); }
                l.add(t);
            }
        }
        List<String> keys = new ArrayList<>(m.keySet());
        Collections.sort(keys, String::compareToIgnoreCase);
        LinkedHashMap<String, List<Track>> out = new LinkedHashMap<>();
        for (String k : keys) {
            List<Track> l = m.get(k);
            Collections.sort(l, (a, b) -> {
                int c = a.album.compareToIgnoreCase(b.album);
                if (mode != 2 && mode != 3 && c != 0) return c;
                if (mode == 3) { int ca = a.artist.compareToIgnoreCase(b.artist); if (ca != 0) return ca; if (c != 0) return c; }
                int na = a.no > 0 ? a.no : Integer.MAX_VALUE, nb = b.no > 0 ? b.no : Integer.MAX_VALUE;
                if (na != nb) return Integer.compare(na, nb);
                return a.path.compareToIgnoreCase(b.path);
            });
            out.put(k, l);
        }
        return out;
    }

    public List<Track> all() { synchronized (library) { return new ArrayList<>(library); } }

    // ---------------------------------------------------------------- afspelen

    public Track current() { return pos >= 0 && pos < order.size() ? order.get(pos) : null; }
    public boolean isPlaying() { try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; } }
    public int positionMs() { try { return mp != null && prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    public int durationMs() {
        try { if (mp != null && prepared) return mp.getDuration(); } catch (Exception ignored) { }
        Track t = current(); return t == null ? 0 : t.dur;
    }
    public int queueSize() { return order.size(); }
    public int queueIndex() { return pos; }

    /** Volgende nummers na het huidige (max n). */
    public List<Track> upNext(int n) {
        List<Track> l = new ArrayList<>();
        for (int i = 1; i <= n && !order.isEmpty(); i++) {
            int k = pos + i;
            if (k >= order.size()) break;
            l.add(order.get(k));
        }
        return l;
    }

    /** Wachtrij instellen en bij nummer 'start' beginnen. */
    public void play(List<Track> tracks, int start) {
        if (tracks == null || tracks.isEmpty()) return;
        queue = new ArrayList<>(tracks);
        Track first = queue.get(Math.max(0, Math.min(start, queue.size() - 1)));
        rebuildOrder(first);
        startCurrent();
    }

    private void rebuildOrder(Track first) {
        order = new ArrayList<>(queue);
        if (shuffle) {
            Collections.shuffle(order, new Random());
            if (first != null) { order.remove(first); order.add(0, first); }
            pos = 0;
        } else pos = Math.max(0, order.indexOf(first));
    }

    public void setShuffle(boolean on) {
        shuffle = on;
        ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putBoolean("shuffle", on).apply();
        Track cur = current();
        if (!queue.isEmpty()) rebuildOrder(cur);
        changed();
    }

    public void toggle() { if (isPlaying()) pause(); else resume(); }

    public void pause() {
        wantPlay = false;
        try { if (mp != null && prepared) mp.pause(); } catch (Exception ignored) { }
        if (!contactPausing) saveState();
        updateSession(); changed();
    }

    public void resume() {
        if (current() == null) {
            List<Track> a = all();
            if (!a.isEmpty()) play(a, 0);
            return;
        }
        if (!requestFocus()) return;
        wantPlay = true;
        try { if (mp != null && prepared) mp.start(); else startCurrent(); } catch (Exception ignored) { }
        updateSession(); changed();
    }

    /** Direct naar een nummer in de wachtrij springen (index in de afspeelvolgorde). */
    public void jumpTo(int orderIndex) {
        if (orderIndex < 0 || orderIndex >= order.size()) return;
        pos = orderIndex;
        startCurrent();
    }

    /** Spoelen: 0..1 van de lengte van het huidige nummer. */
    public void seekFraction(float f) {
        int d = durationMs();
        if (mp == null || !prepared || d <= 0) return;
        try { mp.seekTo(Math.round(Math.max(0f, Math.min(1f, f)) * d)); } catch (Exception ignored) { }
        updateSession(); changed();
    }

    public void next() {
        if (order.isEmpty()) return;
        pos = (pos + 1) % order.size();
        startCurrent();
    }

    public void previous() {
        if (order.isEmpty()) return;
        if (positionMs() > 3000) { try { mp.seekTo(0); } catch (Exception ignored) { } changed(); return; }
        pos = (pos - 1 + order.size()) % order.size();
        startCurrent();
    }

    private void startCurrent() {
        Track t = current();
        if (t == null) return;
        if (!requestFocus()) return;
        release();
        mp = new MediaPlayer();
        prepared = false; wantPlay = true;
        try {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(t.path);
            mp.setOnPreparedListener(m -> {
                prepared = true;
                if (pendingSeek > 0) { try { m.seekTo(pendingSeek); } catch (Exception ignored) { } pendingSeek = 0; }
                if (wantPlay) m.start();
                updateSession(); changed(); saveState();
            });
            mp.setOnCompletionListener(m -> next());
            mp.setOnErrorListener((m, w, x) -> { CarLogger.note("muziek: kan niet afspelen " + t.path); h.postDelayed(this::next, 500); return true; });
            mp.prepareAsync();
            CarLogger.note("muziek: speelt " + t.label() + " - " + t.artist);
        } catch (Exception e) {
            CarLogger.note("muziek: fout " + e.getMessage());
        }
        art = null; artFor = "";
        updateSession(); changed();
    }

    private void release() {
        if (mp != null) { try { mp.release(); } catch (Exception ignored) { } mp = null; prepared = false; }
    }

    public void stop() { wantPlay = false; release(); abandonFocus(); updateSession(); changed(); }

    // ---------------------------------------------------------------- audiofocus

    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { pausedByFocus = false; if (isPlaying()) CarLogger.note("muziek: andere app neemt het geluid over, USB gepauzeerd"); pause(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (isPlaying()) { pausedByFocus = true; pause(); } }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { try { if (mp != null) mp.setVolume(0.3f, 0.3f); } catch (Exception ignored) { } }
        else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            try { if (mp != null) mp.setVolume(1f, 1f); } catch (Exception ignored) { }
            if (pausedByFocus) { pausedByFocus = false; resume(); }
        }
    };

    @SuppressWarnings("deprecation")
    private boolean requestFocus() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        return am == null || am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.abandonAudioFocus(focus);
    }

    // ---------------------------------------------------------------- stuurwielknoppen (MediaSession)

    private void updateSession() {
        try {
            if (session == null) {
                session = new MediaSession(ctx, "OptimusMusic");
                session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                session.setCallback(new MediaSession.Callback() {
                    @Override public void onPlay() { resume(); }
                    @Override public void onPause() { pause(); }
                    @Override public void onSkipToNext() { next(); }
                    @Override public void onSkipToPrevious() { previous(); }
                    @Override public void onStop() { pause(); }
                    @Override public boolean onMediaButtonEvent(Intent i) {
                        CarLogger.note("muziek: mediaknop " + i.getParcelableExtra(Intent.EXTRA_KEY_EVENT));
                        return super.onMediaButtonEvent(i);
                    }
                });
            }
            Track t = current();
            if (t != null) {
                session.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, t.label())
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs()).build());
            }
            boolean playing = isPlaying() || (wantPlay && !prepared);
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, positionMs(), 1f).build());
            session.setActive(t != null);
        } catch (Exception e) { CarLogger.note("muziek: sessie fout " + e.getMessage()); }
    }

    // ---------------------------------------------------------------- hoes

    /** Albumhoes van het huidige nummer (ingebed of cover.jpg/folder.jpg in de map), of null. */
    public Bitmap art() {
        Track t = current();
        if (t == null) return null;
        if (t.path.equals(artFor)) return art;
        artFor = t.path; art = null;
        try {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            m.setDataSource(t.path);
            byte[] b = m.getEmbeddedPicture();
            m.release();
            if (b != null) art = decode(b, null);
        } catch (Exception ignored) { }
        if (art == null) {
            File dir = new File(t.path).getParentFile();
            for (String n : new String[]{"cover.jpg", "folder.jpg", "Cover.jpg", "Folder.jpg", "front.jpg", "cover.png"}) {
                File f = new File(dir, n);
                if (f.exists()) { art = decode(null, f.getAbsolutePath()); if (art != null) break; }
            }
        }
        return art;
    }

    private static Bitmap decode(byte[] b, String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (b != null) BitmapFactory.decodeByteArray(b, 0, b.length, o); else BitmapFactory.decodeFile(path, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= 320) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return b != null ? BitmapFactory.decodeByteArray(b, 0, b.length, o) : BitmapFactory.decodeFile(path, o);
    }

    public static String time(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    // ---------------------------------------------------------------- doorspelen na contact uit/aan

    private boolean pendingResume = false, contactPausing = false;
    private int pendingSeek = 0, resumeTries = 0;
    private final Runnable saver = new Runnable() {
        @Override public void run() { if (isPlaying()) { saveState(); h.postDelayed(this, 5000); } }
    };

    public boolean resumeEnabled() { return ctx.getSharedPreferences("music", Context.MODE_PRIVATE).getBoolean("resume", true); }
    public void setResumeEnabled(boolean on) { ctx.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putBoolean("resume", on).apply(); }

    private File stateFile() { return new File(ctx.getFilesDir(), "music-state.json"); }

    /** Huidige wachtrij, nummer en positie bewaren (ook of hij speelde). */
    private void saveState() { saveState(isPlaying() || (wantPlay && !prepared)); }
    private void saveState(boolean playing) {
        if (order.isEmpty() || pos < 0) return;
        try {
            JSONObject o = new JSONObject();
            JSONArray a = new JSONArray();
            for (Track t : order) a.put(t.path);
            o.put("order", a); o.put("pos", pos); o.put("ms", positionMs()); o.put("playing", playing);
            try (FileOutputStream f = new FileOutputStream(stateFile())) { f.write(o.toString().getBytes("UTF-8")); }
        } catch (Exception ignored) { }
        h.removeCallbacks(saver);
        if (isPlaying()) h.postDelayed(saver, 5000);
    }

    /** Contact uit: pauzeren en onthouden dat hij speelde. Contact aan: doorspelen (als de optie aanstaat). */
    public void onContact(boolean on) {
        h.post(() -> {
            if (!on) {
                if (isPlaying()) {
                    saveState(true);
                    contactPausing = true; pause(); contactPausing = false;
                    CarLogger.note("muziek: contact uit, gepauzeerd en onthouden");
                }
                h.removeCallbacks(resumeLater);
            } else if (resumeEnabled() && !isPlaying()) {
                h.removeCallbacks(resumeLater);
                h.postDelayed(resumeLater, 6000);   // na het opstartfilmpje
            }
        });
    }

    private final Runnable resumeLater = () -> { resumeTries = 0; doResume(); };

    private void doResume() {
        pendingResume = false;
        JSONObject o;
        try (FileInputStream f = new FileInputStream(stateFile())) {
            byte[] b = new byte[(int) stateFile().length()];
            int n = 0; while (n < b.length) { int r = f.read(b, n, b.length - n); if (r < 0) break; n += r; }
            o = new JSONObject(new String(b, 0, n, "UTF-8"));
        } catch (Exception e) { return; }
        if (!o.optBoolean("playing", false) || isPlaying()) return;
        AudioManager am0 = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am0 != null && am0.isMusicActive()) { CarLogger.note("muziek: ander geluid speelt al (AA/BT), USB niet hervat"); return; }
        // zelfde wachtrij nog in het geheugen (tablet sliep alleen): gewoon verder
        if (current() != null && mp != null && prepared) { resume(); CarLogger.note("muziek: verder na contact aan"); return; }
        if (all().isEmpty()) {
            if (!scanning) {
                if (resumeTries++ >= 4) { CarLogger.note("muziek: doorspelen lukt niet, geen stick gevonden"); return; }
                pendingResume = true; scan();
                // stick kan nog niet gemount zijn: na een lege scan opnieuw proberen
                h.postDelayed(() -> { if (all().isEmpty() && !scanning) { pendingResume = false; doResume(); } }, 8000);
            } else pendingResume = true;
            return;
        }
        Map<String, Track> byPath = new HashMap<>();
        for (Track t : all()) byPath.put(t.path, t);
        JSONArray a = o.optJSONArray("order");
        if (a == null) return;
        List<Track> list = new ArrayList<>();
        int savedPos = o.optInt("pos", 0), newPos = 0;
        for (int i = 0; i < a.length(); i++) {
            Track t = byPath.get(a.optString(i));
            if (t == null) continue;
            if (i == savedPos) newPos = list.size();
            list.add(t);
        }
        if (list.isEmpty()) { CarLogger.note("muziek: opgeslagen nummers niet meer op de stick"); return; }
        queue = new ArrayList<>(list); order = new ArrayList<>(list); pos = Math.min(newPos, list.size() - 1);
        pendingSeek = a.optString(savedPos).equals(order.get(pos).path) ? o.optInt("ms", 0) : 0;
        CarLogger.note("muziek: verder na contact aan, " + order.get(pos).label() + " @ " + time(pendingSeek));
        startCurrent();
    }
}

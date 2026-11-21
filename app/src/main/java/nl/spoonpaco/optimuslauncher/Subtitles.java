package nl.spoonpaco.optimuslauncher;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ondertitels uit .srt of .vtt: tijdvakken met tekst. Zoekt de tekst bij een afspeelpositie. */
final class Subtitles {
    static final String[] EXT = {".srt", ".vtt"};
    private static final Pattern TIME = Pattern.compile(
            "((?:\\d+:)?\\d{1,2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[,.]\\d{1,3})");

    final List<long[]> times = new ArrayList<>();
    final List<String> texts = new ArrayList<>();
    final File file;

    private Subtitles(File f) { file = f; }

    static Subtitles load(File f) {
        Subtitles s = new Subtitles(f);
        try {
            byte[] b;
            try (FileInputStream in = new FileInputStream(f)) {
                b = new byte[(int) Math.min(f.length(), 4_000_000)];
                int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            }
            String txt = new String(b, Charset.forName("UTF-8"));
            if (txt.indexOf('�') >= 0) txt = new String(b, Charset.forName("windows-1252"));   // oude ondertitels
            if (txt.startsWith("﻿")) txt = txt.substring(1);
            String[] lines = txt.replace("\r", "").split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = TIME.matcher(lines[i]);
                if (!m.find()) continue;
                long a = ms(m.group(1)), e = ms(m.group(2));
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < lines.length && !lines[j].trim().isEmpty()) {
                    if (TIME.matcher(lines[j]).find()) break;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(lines[j].replaceAll("<[^>]*>", "").replaceAll("\\{[^}]*\\}", "").trim());
                    j++;
                }
                if (sb.length() > 0) { s.times.add(new long[]{a, e}); s.texts.add(sb.toString()); }
                i = j - 1;
            }
        } catch (Exception e) {
            CarLogger.note("video: ondertitel lezen mislukt " + f.getName());
        }
        return s;
    }

    private static long ms(String t) {
        String[] p = t.replace(',', '.').split(":");
        double sec = Double.parseDouble(p[p.length - 1]);
        long min = Long.parseLong(p[p.length - 2]);
        long hr = p.length > 2 ? Long.parseLong(p[p.length - 3]) : 0;
        return Math.round(((hr * 60 + min) * 60 + sec) * 1000);
    }

    private int last = 0;

    /** Tekst op positie ms, of null. */
    String at(long ms) {
        int n = times.size();
        if (n == 0) return null;
        if (last >= n) last = 0;
        if (times.get(last)[0] > ms) last = 0;
        for (int i = last; i < n; i++) {
            long[] t = times.get(i);
            if (ms < t[0]) return null;
            if (ms <= t[1]) { last = i; return texts.get(i); }
        }
        return null;
    }

    /** Ondertitelbestanden in dezelfde map als de video, best passende eerst. */
    static List<File> candidates(File video) {
        List<File> out = new ArrayList<>();
        File dir = video.getParentFile();
        if (dir == null) return out;
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        String base = video.getName().replaceAll("\\.[^.]+$", "").toLowerCase(Locale.ROOT);
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (String e : EXT) if (n.endsWith(e)) { out.add(f); break; }
        }
        java.util.Collections.sort(out, (a, b) -> {
            boolean ma = a.getName().toLowerCase(Locale.ROOT).startsWith(base), mb = b.getName().toLowerCase(Locale.ROOT).startsWith(base);
            if (ma != mb) return ma ? -1 : 1;
            return MusicPlayer.natural(a.getName().toLowerCase(Locale.ROOT), b.getName().toLowerCase(Locale.ROOT));
        });
        return out;
    }
}

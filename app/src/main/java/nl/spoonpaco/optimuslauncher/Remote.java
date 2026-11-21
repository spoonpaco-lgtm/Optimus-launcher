package nl.spoonpaco.optimuslauncher;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Afstandsbediening voor troubleshooting via adb (root). Commando's komen binnen via RemoteActivity:
 *   am start -n nl.spoonpaco.optimuslauncher/.RemoteActivity --es cmd <commando> [extra's]
 *
 * Commando's:
 *   tap        --ei x --ei y            tik op schermcoördinaat (pixels)
 *   longtap    --ei x --ei y            lang indrukken
 *   swipe      --ei x --ei y --ei x2 --ei y2
 *   back                                terugknop (sluit bovenste venster/dialoog)
 *   text       --es value               tekst in het actieve invulveld zetten
 *   dump                                lijst van knoppen/teksten met posities -> files/remote-dump.txt
 *   menu       --ei tab (0-4)           menu openen op tabblad
 *   open       --es what  music|video|triplog|debug|fuel|maintenance|highscores|locations|home
 *   tone       [--es side L|R]  speakertest
 *   key / longkey --es name  aa|music|radio|video|trips|apps|menu
 *   theme      --es id  led|vfd|neon|old
 *   lang       --es value  nl|en
 * Alleen bereikbaar via adb: de activity is niet geëxporteerd voor andere apps.
 */
public final class Remote {
    private static WeakReference<Activity> top = new WeakReference<>(null);
    private static volatile int resumed = 0;
    /** Venster van RemoteActivity zelf: nooit als doel gebruiken. */
    static WeakReference<View> remoteDecor;

    /** Staat er een scherm van de launcher-app vooraan (launcher, rittenlog, debug, ...)? */
    public static boolean ownScreenVisible() { return resumed > 0; }
    private static final Handler H = new Handler(Looper.getMainLooper());

    private Remote() { }

    /** In OptimusApp aanroepen: houdt bij welke activity bovenaan staat. */
    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                if (a instanceof RemoteActivity) return;
                top = new WeakReference<>(a); resumed++;
                Immersive.apply(a.getWindow());
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityPaused(Activity a) { if (!(a instanceof RemoteActivity)) resumed = Math.max(0, resumed - 1); }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** Commando's één voor één uitvoeren, met tussenruimte: snel na elkaar tikken gaat dan niet verloren. */
    private static long nextFree = 0;

    public static void handle(final Context ctx, final Intent in) {
        final String cmd = in.getStringExtra("cmd");
        if (cmd == null) return;
        long now = SystemClock.uptimeMillis();
        // minstens 600 ms na ontvangst (RemoteActivity is dan weg) en 450 ms na het vorige commando
        long at = Math.max(now + 600, nextFree);
        nextFree = at + 450;
        final Intent copy = new Intent(in);
        H.postAtTime(() -> {
            try {
                run(ctx.getApplicationContext(), cmd, copy);
                CarLogger.note("remote: " + cmd + " ok");
            } catch (Throwable t) {
                CarLogger.note("remote: " + cmd + " FOUT " + t);
            }
        }, at);
    }

    private static void run(Context c, String cmd, Intent in) throws Exception {
        switch (cmd) {
            case "tap": tap(in.getIntExtra("x", 0), in.getIntExtra("y", 0), 60); break;
            case "longtap": tap(in.getIntExtra("x", 0), in.getIntExtra("y", 0), 900); break;
            case "swipe": swipe(in.getIntExtra("x", 0), in.getIntExtra("y", 0), in.getIntExtra("x2", 0), in.getIntExtra("y2", 0)); break;
            case "back": back(); break;
            case "text": text(in.getStringExtra("value")); break;
            case "dump": dump(c); break;
            default: {
                // commando's voor de launcher zelf
                MainActivity m = MainActivity.instance;
                if (m == null || top.get() != m) {
                    Intent i = new Intent(c, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    i.putExtras(in);
                    i.putExtra("remote", true);
                    c.startActivity(i);
                } else m.remote(cmd, in);
            }
        }
    }

    // ---------------------------------------------------------------- vensters

    /** Alle vensters van onze app (activities + dialogen), bovenste laatst. Android 8.1: via WindowManagerGlobal. */
    @SuppressWarnings("unchecked")
    static List<View> roots() {
        try {
            Class<?> g = Class.forName("android.view.WindowManagerGlobal");
            Method gi = g.getMethod("getInstance");
            Object wmg = gi.invoke(null);
            Field f = g.getDeclaredField("mViews");
            f.setAccessible(true);
            List<View> v = new ArrayList<>((List<View>) f.get(wmg));
            List<View> shown = new ArrayList<>();
            View skip = remoteDecor == null ? null : remoteDecor.get();
            for (View x : v) if (x != skip && x.isShown() && x.getWindowToken() != null) shown.add(x);
            return shown;
        } catch (Exception e) {
            List<View> l = new ArrayList<>();
            Activity a = top.get();
            if (a != null) l.add(a.getWindow().getDecorView());
            return l;
        }
    }

    /** Bovenste venster dat het punt (schermcoördinaten) bevat. */
    private static View rootAt(int x, int y) {
        List<View> r = roots();
        for (int i = r.size() - 1; i >= 0; i--) {
            View v = r.get(i);
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (x >= loc[0] && y >= loc[1] && x < loc[0] + v.getWidth() && y < loc[1] + v.getHeight()) return v;
        }
        return r.isEmpty() ? null : r.get(r.size() - 1);
    }

    private static void tap(int x, int y, long hold) {
        final View v = rootAt(x, y);
        if (v == null) throw new IllegalStateException("geen venster");
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        final float lx = x - loc[0], ly = y - loc[1];
        final long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, lx, ly, 0);
        v.dispatchTouchEvent(d); d.recycle();
        H.postDelayed(() -> {
            MotionEvent u = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, lx, ly, 0);
            v.dispatchTouchEvent(u); u.recycle();
        }, hold);
    }

    private static void swipe(int x1, int y1, final int x2, final int y2) {
        final View v = rootAt(x1, y1);
        if (v == null) throw new IllegalStateException("geen venster");
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        final float ax = x1 - loc[0], ay = y1 - loc[1], bx = x2 - loc[0], by = y2 - loc[1];
        final long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, ax, ay, 0);
        v.dispatchTouchEvent(d); d.recycle();
        for (int i = 1; i <= 10; i++) {
            final float t = i / 10f;
            H.postDelayed(() -> {
                MotionEvent m = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                        t < 1f ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP, ax + (bx - ax) * t, ay + (by - ay) * t, 0);
                v.dispatchTouchEvent(m); m.recycle();
            }, i * 30L);
        }
    }

    private static void back() {
        List<View> r = roots();
        if (r.isEmpty()) return;
        View v = r.get(r.size() - 1);
        v.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        v.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
    }

    private static void text(String value) {
        List<View> r = roots();
        for (int i = r.size() - 1; i >= 0; i--) {
            View f = r.get(i).findFocus();
            if (f instanceof EditText) {
                EditText e = (EditText) f;
                e.setText(value == null ? "" : value);
                e.setSelection(e.getText().length());
                return;
            }
        }
        throw new IllegalStateException("geen invulveld actief");
    }

    // ---------------------------------------------------------------- dump

    private static void dump(Context c) throws Exception {
        StringBuilder s = new StringBuilder();
        s.append("remote-dump ").append(new java.util.Date(Clock.now())).append('\n');
        Activity a = top.get();
        s.append("bovenste activity: ").append(a == null ? "?" : a.getClass().getSimpleName()).append('\n');
        List<View> r = roots();
        for (int i = 0; i < r.size(); i++) {
            View v = r.get(i);
            int[] loc = new int[2]; v.getLocationOnScreen(loc);
            s.append(String.format(Locale.US, "\n=== venster %d%s  (%d,%d %dx%d)\n", i, i == r.size() - 1 ? " [BOVENSTE]" : "",
                    loc[0], loc[1], v.getWidth(), v.getHeight()));
            walk(v, s, 0);
        }
        File f = new File(c.getExternalFilesDir(null), "remote-dump.txt");
        try (FileWriter w = new FileWriter(f, false)) { w.write(s.toString()); }
    }

    private static void walk(View v, StringBuilder s, int depth) {
        if (v.getVisibility() != View.VISIBLE) return;
        Rect b = new Rect();
        v.getGlobalVisibleRect(b);
        String label = "";
        if (v instanceof TextView) label = String.valueOf(((TextView) v).getText());
        if (label.isEmpty() && v.getContentDescription() != null) label = v.getContentDescription().toString();
        boolean click = v.isClickable() || v.isLongClickable();
        String id = "";
        try { if (v.getId() > 0) id = v.getResources().getResourceEntryName(v.getId()); } catch (Exception ignored) { }
        if (click || !label.isEmpty() || v instanceof MenuView || v instanceof CockpitView || !id.isEmpty()) {
            s.append(String.format(Locale.US, "%s%s%s%s  [%d,%d - %d,%d]  midden %d,%d%s%s\n",
                    indent(depth), v.getClass().getSimpleName(), id.isEmpty() ? "" : " #" + id,
                    label.isEmpty() ? "" : " \"" + label.replace('\n', ' ') + "\"",
                    b.left, b.top, b.right, b.bottom, b.centerX(), b.centerY(),
                    click ? "  KLIKBAAR" : "", v.isFocused() ? "  FOCUS" : ""));
        }
        if (v instanceof MenuView) s.append(((MenuView) v).dumpHits(b.left, b.top, indent(depth + 1)));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), s, depth + 1);
        }
    }

    private static String indent(int d) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(d, 12); i++) s.append("  ");
        return s.toString();
    }
}

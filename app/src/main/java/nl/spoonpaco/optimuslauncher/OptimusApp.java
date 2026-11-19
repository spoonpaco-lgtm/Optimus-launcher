package nl.spoonpaco.optimuslauncher;

import android.app.Application;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Legt crashes vast in files/crash.txt, zodat het debugscherm ze kan tonen. */
public class OptimusApp extends Application {

    public static final String CRASH_FILE = "crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        Clock.init(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try (PrintWriter pw = new PrintWriter(new FileWriter(new File(getFilesDir(), CRASH_FILE), false))) {
                pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(Clock.now()))
                        + "  thread=" + thread.getName());
                ex.printStackTrace(pw);
            } catch (Exception ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, ex);
        });
    }
}

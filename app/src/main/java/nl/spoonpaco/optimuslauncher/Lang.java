package nl.spoonpaco.optimuslauncher;

import android.content.Context;

import java.util.Locale;

/** Taalkeuze Nederlands / English voor alles wat op het scherm staat. Eenheden blijven metrisch (km/h, km, L). */
public final class Lang {
    public static volatile boolean en = false;

    private Lang() { }

    public static void load(Context c) {
        en = c.getSharedPreferences("optimus", Context.MODE_PRIVATE).getString("lang", "nl").equals("en");
    }

    public static void set(Context c, boolean english) {
        en = english;
        c.getSharedPreferences("optimus", Context.MODE_PRIVATE).edit().putString("lang", english ? "en" : "nl").apply();
    }

    /** Kies de tekst in de huidige taal. */
    public static String t(String nl, String english) { return en ? english : nl; }

    public static Locale locale() { return en ? Locale.UK : new Locale("nl"); }
}

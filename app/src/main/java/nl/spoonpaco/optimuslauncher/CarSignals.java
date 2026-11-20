package nl.spoonpaco.optimuslauncher;

/**
 * Signalen van de auto, gelezen uit de FlyAudio-logregels (READ_LOGS).
 * Vondsten 20-11-2026 uit de autolog:
 *  - GPIO 60: VERLICHTING (bevestigd door Mick 20-11): 0 = lichten aan, 1 = uit (actief laag)
 *  - HANDREM: nog niet gevonden (MCU 04 bleek het niet te zijn, 20-11)
 *  - MCU ff5504 00 04 vv: onbekend, gelogd als "mcu04"
 *  - MCU ff5504 00 01 vv: contact (01 = aan, 00 = uit)
 *  - MCU ff5505 00 02 hhhh: accuspanning in mV (14,3 V = motor draait)
 */
public final class CarSignals {

    public interface Listener { void onSignal(String name, int value); }

    public static volatile int handbrake = -1;  // GPIO 60
    public static volatile int contact = -1;    // MCU key 01
    public static volatile int lights = -1;     // GPIO 60 (omgekeerd)
    public static volatile int mcu04 = -1;
    public static volatile int screen = -1;     // MCU: achtergrondverlichting aan/uit
    public static volatile int acc = -1;        // GPIO 62 via HardwareService      // MCU key 04, betekenis onbekend
    public static volatile float volts = -1f;   // MCU 05/02
    public static volatile long voltsTime = 0L;
    public static volatile Listener listener;

    private CarSignals() { }

    public static boolean engineRunning() {
        return volts >= 13.3f && System.currentTimeMillis() - voltsTime < 30_000L;
    }

    /** true = regel is "interessant" (status veranderd) en mag in de log. */
    static boolean parse(String line) {
        try {
            // Scherm echt aan/uit (MCU bevestigt achtergrondverlichting) -> moment voor het opstartfilmpje
            int bl = line.indexOf("onScreenBackLightEnable ~: ");
            if (bl >= 0) return set("scherm", line.startsWith("true", bl + 27) ? 1 : 0);
            // ACC (contactslot) rechtstreeks van FlyAudio HardwareService (GPIO 62)
            int ai = line.indexOf("onGpioInterrupt num:62 ~voltage:");
            if (ai >= 0) return set("acc", line.charAt(ai + 32) == '1' ? 1 : 0);
            int i = line.indexOf("readLoop state gpioNum:");
            if (i >= 0) {
                String rest = line.substring(i + 23);
                int num = Integer.parseInt(rest.substring(0, rest.indexOf(' ')).trim());
                int st = rest.charAt(rest.indexOf("state:") + 6) - '0';
                if (num == 60) return set("licht", st == 0 ? 1 : 0);
                return false; // GPIO 62 = hartslag van de MCU, niet loggen
            }
            if (line.contains("FlyaudioGpioJNI")) return false; // dubbele interrupt-regels
            final String TAG = "handleAnalyzedData ~~~: ";
            int m = line.indexOf(TAG);
            if (m >= 0) {
                String f = line.substring(m + TAG.length()).trim();
                if (f.length() == 14 && f.startsWith("ff550400")) {
                    String key = f.substring(8, 10);
                    int v = Integer.parseInt(f.substring(10, 12), 16);
                    if (key.equals("01")) return set("contact", v);
                    if (key.equals("04")) return set("mcu04", v);
                    return false;
                }
                if (f.length() == 16 && f.startsWith("ff55050002")) {
                    float v = Integer.parseInt(f.substring(10, 14), 16) / 1000f;
                    boolean big = volts < 0 || Math.abs(v - volts) >= 0.5f;
                    volts = v;
                    voltsTime = System.currentTimeMillis();
                    return big;
                }
                return false;
            }
        } catch (Exception ignored) { }
        return true;
    }

    private static boolean set(String name, int v) {
        int old;
        switch (name) {
            case "handrem": old = handbrake; handbrake = v; break;
            case "contact": old = contact; contact = v; break;
            case "mcu04": old = mcu04; mcu04 = v; break;
            case "scherm": old = screen; screen = v; break;
            case "acc": old = acc; acc = v; break;
            default: old = lights; lights = v; break;
        }
        if (old == v) return false;
        Listener l = listener;
        if (l != null) l.onSignal(name, v);
        return true;
    }
}

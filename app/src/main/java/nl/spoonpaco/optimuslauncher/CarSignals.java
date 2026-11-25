package nl.spoonpaco.optimuslauncher;

/**
 * Signalen van de auto, gelezen uit de FlyAudio-logregels (READ_LOGS).
 * Vondsten 20-11-2026 uit de autolog:
 *  - GPIO 60: VERLICHTING (bevestigd door Mick 20-11): 0 = lichten aan, 1 = uit (actief laag)
 *  - HANDREM: nog niet gevonden (MCU 04 bleek het niet te zijn, 20-11)
 *  - MCU ff5504 00 04 vv: versterker aan (geluid speelt) = 1, gedempt = 0 (getest 20-11: volgt onAmpMuteStatusCallback)
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

    // De FlyAudio-MCU stuurt de spanning alleen bij een flinke verandering (niet elke 10 s), en de
    // eerste melding na wakker worden kan een oude waarde van vóór de slaap zijn (21 nov: 14,091 V na
    // 43 min slaap, tot op de millivolt gelijk aan de laatste waarde). Daarom:
    //  - een start herkennen we aan het contact dat even wegvalt (< 10 s) of een dip onder 11 V;
    //  - zonder start telt de eerste melding na contact aan niet mee.
    private static volatile int voltsSinceOn = 0;
    private static volatile boolean crankSeen = false;
    private static volatile long contactOffAt = 0L;

    /** Is de huidige spanning te vertrouwen (geen mogelijk oude waarde van vóór de slaap)? */
    public static boolean voltsTrusted() {
        return volts > 0 && (crankSeen || voltsSinceOn >= 2);
    }

    public static boolean engineRunning() {
        if (contact == 0) return false;
        if (volts >= 13.3f && voltsTrusted()) return true;
        // rijden we (GPS)? dan draait de motor, ook als de MCU nog geen nieuwe spanning stuurde
        return TrackerService.speedKmh > 10f && System.currentTimeMillis() - TrackerService.lastFixTime < 5_000L;
    }

    /** Spanning voor op het scherm: een mogelijk oude hoge waarde (motor draait niet bewezen) niet tonen. */
    public static float displayVolts() {
        if (volts <= 0) return -1f;
        if (volts >= 13.0f && !voltsTrusted() && !engineRunning()) return -1f;
        return volts;
    }

    /** true = regel is "interessant" (status veranderd) en mag in de log. */
    static boolean parse(String line) {
        try {
            // FlyAudio start zijn eigen launcher (na wakker worden / HOME): melden zodat wij er overheen komen
            if (line.contains("START u0") && line.contains("cmp=com.flyaudio.launcher/.FlyLauncher")) {
                Listener l = listener;
                if (l != null) l.onSignal("flylauncher", 1);
                return true;
            }
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
                    if (key.equals("04")) return set("versterker", v);
                    return false;
                }
                if (f.length() == 16 && f.startsWith("ff55050002")) {
                    float v = Integer.parseInt(f.substring(10, 14), 16) / 1000f;
                    boolean big = volts < 0 || Math.abs(v - volts) >= 0.5f;
                    voltsSinceOn++;
                    if (v > 5f && v < 11.0f) crankSeen = true;   // startmotor trekt de spanning omlaag
                    volts = v;
                    voltsTime = System.currentTimeMillis();
                    BatteryHealth.onVolts(v);
                    AdcProbe.onVolts(v);
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
            case "versterker": old = mcu04; mcu04 = v; break;
            case "scherm": old = screen; screen = v; break;
            case "acc": old = acc; acc = v; break;
            default: old = lights; lights = v; break;
        }
        if (old == v) return false;
        if (name.equals("contact")) {
            long now = System.currentTimeMillis();
            if (v == 0) contactOffAt = now;
            else if (v == 1) {
                if (contactOffAt > 0 && now - contactOffAt < 10_000L) crankSeen = true;   // contact viel even weg = starten
                else { voltsSinceOn = 0; crankSeen = false; }                              // nieuw contact (na slaap)
            }
            BatteryHealth.onContact(v);
            if (v == 1) AdcProbe.onContactOn();
        }
        Listener l = listener;
        if (l != null) l.onSignal(name, v);
        return true;
    }
}

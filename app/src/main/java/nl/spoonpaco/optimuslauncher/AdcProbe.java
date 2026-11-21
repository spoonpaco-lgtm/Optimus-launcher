package nl.spoonpaco.optimuslauncher;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * Onderzoek: meet kanaal 1 van de SoC-ADC (saradc) mee met de accuspanning van de MCU.
 * Als die meebeweegt met de accu, kunnen we de startdip (minder dan 1 s) wél zien:
 * de MCU stuurt de spanning maar elke 10 s door. Alleen loggen, nog niets tonen.
 */
final class AdcProbe {
    private static final String CH = "/sys/bus/iio/devices/iio:device0/in_voltage1_raw";
    private static long lastLog;

    private AdcProbe() { }

    static int read() {
        try (BufferedReader r = new BufferedReader(new FileReader(CH))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) { return -1; }
    }

    /** Bij elke MCU-spanning, max. 1x per minuut: beide waarden naast elkaar loggen. */
    static void onVolts(float v) {
        long now = System.currentTimeMillis();
        if (now - lastLog < 60_000L) return;
        lastLog = now;
        int a = read();
        if (a >= 0) CarLogger.note(String.format(Locale.US, "accu-probe: mcu %.2f V, adc1 %d", v, a));
    }

    /** Contact aan: 60 s lang snel meten (50x per seconde) en het laagste punt loggen. */
    static void onContactOn() {
        new Thread(() -> {
            int min = Integer.MAX_VALUE, max = -1, n = 0, first = read();
            long end = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < end) {
                int a = read();
                if (a >= 0) { min = Math.min(min, a); max = Math.max(max, a); n++; }
                try { Thread.sleep(20); } catch (InterruptedException ignored) { }
            }
            CarLogger.note(String.format(Locale.US, "accu-probe: contact aan, adc1 begin %d, laagste %d, hoogste %d (%d metingen, mcu %.2f V)",
                    first, min, max, n, CarSignals.volts));
        }, "adc-probe").start();
    }
}

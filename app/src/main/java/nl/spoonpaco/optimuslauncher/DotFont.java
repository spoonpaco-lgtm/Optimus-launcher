package nl.spoonpaco.optimuslauncher;

import android.graphics.*;

/** 5x7 dot-matrix lettertype voor het Old-skool-thema: elke "pixel" is een lampje. */
public final class DotFont {
    private static final String CHARS = "\u0030\u0031\u0032\u0033\u0034\u0035\u0036\u0037\u0038\u0039\u0041\u0042\u0043\u0044\u0045\u0047\u0048\u0049\u004a\u004b\u004c\u004d\u004e\u004f\u0050\u0052\u0053\u0054\u0055\u0056\u0057\u005a\u0046\u0059\u0020\u002d\u002e\u003a\u002f\u00b0\u20ac\u0058\u0051\u00b7\u0025\u003f\u0028\u0029\u002b";
    private static final int[] BITS = {15623448110, 15170932932, 33357578798, 15619854623, 8891181448, 15620127807, 15621129292, 2216829471, 15621113390, 6728664622, 18842895918, 16694887983, 15603893806, 7836583207, 33321092159, 32801457710, 18842895921, 15170932878, 6753100060, 18560947505, 33320633377, 18842572657, 18850958897, 15621211694, 1108854319, 18561353263, 16660235326, 4433514655, 15621211697, 4648912433, 11464787505, 33321787935, 1108837439, 4433521201, 0, 1015808, 6643777536, 207624384, 1143087376, 206118, 30147689564, 18834663985, 23946905134, 6488064, 26610901603, 4299440686, 8726317192, 2290360450, 139432064};

    private DotFont() { }

    private static int bits(char ch) {
        int i = CHARS.indexOf(Character.toUpperCase(ch));
        return i < 0 ? 0 : BITS[i];
    }

    /** Breedte van tekst in pixels bij een gegeven pitch (afstand tussen lampjes). */
    public static float width(String t, float pitch) {
        return t.length() == 0 ? 0 : (t.length() * 6 - 1) * pitch;
    }

    /**
     * Teken tekst; (x,y) = linksboven. lamp = true: elk lampje met chromen randje en bolle lens (grote cijfers);
     * false: platte led-puntjes (kleine tekst). ghost: "uit"-lampjes zwak zichtbaar.
     */
    public static void draw(Canvas c, String t, float x, float y, float pitch, int col, boolean lamp, boolean ghost,
                            Paint p, Paint glow) {
        float r = pitch * (lamp ? 0.34f : 0.40f);
        Path lit = new Path();
        float cx0 = x;
        for (int k = 0; k < t.length(); k++) {
            int b = bits(t.charAt(k));
            for (int row = 0; row < 7; row++) {
                for (int c5 = 0; c5 < 5; c5++) {
                    float cx = cx0 + c5 * pitch + pitch / 2, cy = y + row * pitch + pitch / 2;
                    boolean on = (b & (1 << (row * 5 + c5))) != 0;
                    if (lamp) {
                        p.setShader(new LinearGradient(cx - pitch / 2, cy - pitch / 2, cx + pitch / 2, cy + pitch / 2,
                                new int[]{0xFFD9D9E0, 0xFF4A4A52, 0xFFC4C4CC}, null, Shader.TileMode.CLAMP));
                        c.drawCircle(cx, cy, pitch * 0.47f, p);
                        p.setShader(null);
                    }
                    if (on) {
                        lit.addCircle(cx, cy, r, Path.Direction.CW);
                    } else if (lamp) {
                        p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
                                new int[]{(col & 0x00FFFFFF) | 0x48000000, (col & 0x00FFFFFF) | 0x14000000, 0xFF0A0A0C},
                                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                        c.drawCircle(cx, cy, r, p);
                        p.setShader(null);
                    } else if (ghost) {
                        p.setColor((col & 0x00FFFFFF) | 0x14000000);
                        c.drawCircle(cx, cy, r, p);
                    }
                }
            }
            cx0 += pitch * 6;
        }
        glow.setColor((col & 0x00FFFFFF) | 0x99000000);
        glow.setMaskFilter(new BlurMaskFilter(Math.max(2f, pitch * (lamp ? 0.9f : 0.6f)), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(lit, glow);
        glow.setMaskFilter(null);
        if (lamp) {
            cx0 = x;
            for (int k = 0; k < t.length(); k++) {
                int b = bits(t.charAt(k));
                for (int row = 0; row < 7; row++) for (int c5 = 0; c5 < 5; c5++) {
                    if ((b & (1 << (row * 5 + c5))) == 0) continue;
                    float cx = cx0 + c5 * pitch + pitch / 2, cy = y + row * pitch + pitch / 2;
                    p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
                            new int[]{0xF2FFFFFF, col, (col & 0x00FFFFFF) | 0xCC000000, (col & 0x00FFFFFF) | 0x59000000},
                            new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                    c.drawCircle(cx, cy, r, p);
                }
                cx0 += pitch * 6;
            }
            p.setShader(null);
        } else {
            p.setColor(col);
            c.drawPath(lit, p);
        }
    }
}

package nl.spoonpaco.optimuslauncher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Knoptekst van led-puntjes (Old-skool-thema). */
public class LedTextDrawable extends Drawable {
    private final String text;
    private final int color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float pitch;

    public LedTextDrawable(String text, int color, float pitchPx) {
        this.text = text; this.color = color; this.pitch = pitchPx;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float w = DotFont.width(text, pitch), h = 7 * pitch;
        DotFont.draw(c, text, b.exactCenterX() - w / 2, b.exactCenterY() - h / 2, pitch, color, false, false, p, glow);
    }
    @Override public int getIntrinsicWidth() { return (int) (DotFont.width(text, pitch) + 2 * pitch); }
    @Override public int getIntrinsicHeight() { return (int) (9 * pitch); }
    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

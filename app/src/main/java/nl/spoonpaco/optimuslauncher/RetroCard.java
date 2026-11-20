package nl.spoonpaco.optimuslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/** Klein paneel in de stijl van het actieve thema (ritsamenvatting, zwevend venster). */
public class RetroCard extends View {
    private final CockpitView painter;
    private final float d;
    public float wDp = 300, hDp = 200;
    /** Schaal van het hele paneel (zwevend venster = 0,65 = 35% kleiner). */
    public float scale = 1f;
    public String title, big, unit = null, side, sideLabel = "KM/L", hint;
    public String[][] rows;
    public int titleCol, bigCol, sideCol, rowCol, hintCol;
    public Theme theme;

    public RetroCard(Context c, Theme t) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        d = getResources().getDisplayMetrics().density;
        painter = new CockpitView(c, null);
        setTheme(t);
    }

    public void setTheme(Theme t) {
        theme = t;
        painter.setTheme(t);
        titleCol = t.trip; bigCol = t.speed; sideCol = t.accent; rowCol = t.trip;
        invalidate();
    }

    public void setSizeDp(float w, float h) {
        if (w != wDp || h != hDp) { wDp = w; hDp = h; requestLayout(); }
    }

    @Override protected void onMeasure(int wm, int hm) {
        setMeasuredDimension((int) (wDp * d * scale), (int) (hDp * d * scale));
    }

    @Override protected void onDraw(Canvas c) {
        c.save();
        c.scale(d * scale, d * scale);
        painter.hintColor = hintCol;
        painter.paintCard(c, wDp, hDp, title, titleCol, big, unit != null ? unit : Lang.t("KM/U", "KM/H"), bigCol, side, sideLabel, sideCol, rows, rowCol, hint);
        c.restore();
    }
}

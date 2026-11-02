package mod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Outline "download" glyph drawn like Instagram's own action icons (rounded 2px strokes, no background). */
final class DlIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    DlIconView(Context c, boolean dark) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(2f * d);
        setDark(dark);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // lets the legibility shadow render on every device
        setContentDescription("Download");
    }

    void setDark(boolean dark) {
        paint.setColor(dark ? 0xFF000000 : 0xFFFFFFFF);
        if (dark) paint.clearShadowLayer();
        else paint.setShadowLayer(3f, 0f, 1f, 0x99000000); // like the shadowed white icons in Reels
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float icon = size * 0.60f; // 24dp glyph inside a 40dp touch target
        float u = icon / 24f;
        float ox = (getWidth() - icon) / 2f, oy = (getHeight() - icon) / 2f;
        path.reset();
        path.moveTo(ox + 12 * u, oy + 3 * u);       // arrow shaft
        path.lineTo(ox + 12 * u, oy + 15 * u);
        path.moveTo(ox + 7 * u, oy + 10.5f * u);     // arrow head
        path.lineTo(ox + 12 * u, oy + 15.5f * u);
        path.lineTo(ox + 17 * u, oy + 10.5f * u);
        path.moveTo(ox + 4 * u, oy + 15 * u);        // tray
        path.lineTo(ox + 4 * u, oy + 18.5f * u);
        path.quadTo(ox + 4 * u, oy + 21 * u, ox + 6.5f * u, oy + 21 * u);
        path.lineTo(ox + 17.5f * u, oy + 21 * u);
        path.quadTo(ox + 20 * u, oy + 21 * u, ox + 20 * u, oy + 18.5f * u);
        path.lineTo(ox + 20 * u, oy + 15 * u);
        canvas.drawPath(path, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) setAlpha(0.5f);
        else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) setAlpha(1f);
        return super.onTouchEvent(e);
    }
}

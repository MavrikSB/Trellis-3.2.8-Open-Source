package co.vine.android.views;

import android.animation.TimeInterpolator;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import com.edisonwang.android.slog.SLog;
import java.util.ArrayList;

public class MultiPhaseBitmapView extends View implements Runnable {
    private static final int DISAPPEAR_TIME = 6355;
    private static final int FRAME_DELAY = 16;
    private static final DrawingPhase[] PHASES = {new DrawingPhase(0, 80, 120, new DecelerateInterpolator(), null), new DrawingPhase(115, 35, 100, new DecelerateInterpolator(), null), new DrawingPhase(240, 0, 100, new DecelerateInterpolator(), null)};
    private static final int PHASE_ONE_DURATION_MS = 115;
    private static final int PHASE_TWO_DURATION_MS = 240;
    private final ArrayList<DrawingObject> mDrawingObjects;
    private final ArrayList<DrawingState> mDrawingStates;
    private final Matrix mMatrix;
    private final Paint mPaint;
    private final ArrayList<DrawingState> mToRemove;

    private Paint initPaint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        return paint;
    }

    public MultiPhaseBitmapView(Context context) {
        super(context);
        this.mDrawingObjects = new ArrayList<>();
        this.mDrawingStates = new ArrayList<>();
        this.mToRemove = new ArrayList<>();
        this.mMatrix = new Matrix();
        this.mPaint = initPaint();
    }

    public MultiPhaseBitmapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.mDrawingObjects = new ArrayList<>();
        this.mDrawingStates = new ArrayList<>();
        this.mToRemove = new ArrayList<>();
        this.mMatrix = new Matrix();
        this.mPaint = initPaint();
    }

    public MultiPhaseBitmapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.mDrawingObjects = new ArrayList<>();
        this.mDrawingStates = new ArrayList<>();
        this.mToRemove = new ArrayList<>();
        this.mMatrix = new Matrix();
        this.mPaint = initPaint();
    }

    @TargetApi(21)
    public MultiPhaseBitmapView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        this.mDrawingObjects = new ArrayList<>();
        this.mDrawingStates = new ArrayList<>();
        this.mToRemove = new ArrayList<>();
        this.mMatrix = new Matrix();
        this.mPaint = initPaint();
    }

    public void setBitmaps(Bitmap... bitmaps) {
        for (Bitmap bitmap : bitmaps) {
            this.mDrawingObjects.add(new DrawingObject(bitmap, null));
        }
    }

    public void add(boolean special, float x, float y, int angle) {
        synchronized (this) {
            SLog.m467i("Adding X: {}, Y: {}, A: {}", Float.valueOf(x), Float.valueOf(y), Integer.valueOf(angle));
            this.mDrawingStates.add(new DrawingState(special, System.currentTimeMillis(), x, y, angle, PHASES, 6355L, null));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float scale;
        synchronized (this) {
            for (DrawingState state : this.mDrawingStates) {
                DrawingObject drawingObject = null;
                if (state.mSpecial && state.mPhase + 1 < this.mDrawingObjects.size()) {
                    DrawingObject drawingObject2 = this.mDrawingObjects.get(state.mPhase + 1);
                    drawingObject = drawingObject2;
                } else if (this.mDrawingObjects.size() >= 1) {
                    DrawingObject drawingObject3 = this.mDrawingObjects.get(0);
                    drawingObject = drawingObject3;
                }
                if (drawingObject != null) {
                    int alpha = (state.mOpacity * 255) / 100;
                    if (state.mSpecial) {
                        scale = state.mScale / 30.0f;
                    } else {
                        scale = state.mScale / 100.0f;
                    }
                    float offsetX = drawingObject.centerX * scale;
                    float offsetY = drawingObject.centerY * scale;
                    this.mMatrix.reset();
                    this.mMatrix.postScale(scale, scale);
                    this.mMatrix.postRotate(state.f121mA, offsetX, offsetY);
                    this.mMatrix.postTranslate(state.f122mX - offsetX, state.f123mY - offsetY);
                    this.mPaint.setAlpha(alpha);
                    canvas.drawBitmap(drawingObject.bitmap, this.mMatrix, this.mPaint);
                }
            }
        }
        postDelayed(this, 16L);
    }

    @Override
    public void run() {
        long time = System.currentTimeMillis();
        synchronized (this) {
            this.mToRemove.clear();
            for (DrawingState state : this.mDrawingStates) {
                state.updatePhase(time);
                if (state.mGone) {
                    this.mToRemove.add(state);
                }
            }
            this.mDrawingStates.removeAll(this.mToRemove);
        }
        if (isShown()) {
            postInvalidate();
        }
    }

    public int getCount() {
        int size;
        synchronized (this) {
            size = this.mDrawingStates.size();
        }
        return size;
    }

    public void reset() {
        synchronized (this) {
            this.mToRemove.clear();
            this.mDrawingStates.clear();
        }
    }

    private static class DrawingObject {
        public final Bitmap bitmap;
        public final int centerX;
        public final int centerY;

        DrawingObject(Bitmap x0, Object x1) {
            this(x0);
        }

        private DrawingObject(Bitmap image) {
            this.bitmap = image;
            this.centerX = this.bitmap.getWidth() / 2;
            this.centerY = this.bitmap.getHeight() / 2;
        }
    }

    private static class DrawingPhase {
        public final TimeInterpolator interpolator;
        public final int opacity;
        public final int size;
        public final int start;

        DrawingPhase(int x0, int x1, int x2, TimeInterpolator x3, Object x4) {
            this(x0, x1, x2, x3);
        }

        private DrawingPhase(int start, int opacity, int size, TimeInterpolator interpolator) {
            this.start = start;
            this.opacity = opacity;
            this.size = size;
            this.interpolator = interpolator;
        }
    }

    private static class DrawingState {
        private final int f121mA;
        private final long mDisappearTime;
        private boolean mGone;
        private int mOpacity;
        private int mPhase;
        private final DrawingPhase[] mPhases;
        private int mScale;
        private final boolean mSpecial;
        private final long mStartTime;

        private final float f122mX;

        private final float f123mY;

        DrawingState(boolean x0, long x1, float x2, float x3, int x4, DrawingPhase[] x5, long x6, Object x7) {
            this(x0, x1, x2, x3, x4, x5, x6);
        }

        private DrawingState(boolean special, long startTime, float x, float y, int angle, DrawingPhase[] phases, long disappearTime) {
            this.mSpecial = special;
            this.mStartTime = startTime;
            this.f122mX = x;
            this.f123mY = y;
            this.f121mA = angle;
            this.mPhases = phases;
            this.mDisappearTime = disappearTime;
        }

        public void updatePhase(long time) {
            boolean lastPhase;
            long nextStart;
            long lapsedTime;
            do {
                lastPhase = this.mPhase >= this.mPhases.length + (-1);
                nextStart = lastPhase ? this.mDisappearTime : this.mPhases[this.mPhase + 1].start;
                lapsedTime = time - this.mStartTime;
                if (lapsedTime < nextStart) {
                    break;
                } else {
                    this.mPhase++;
                }
            } while (!lastPhase);
            if (this.mPhase >= this.mPhases.length) {
                this.mGone = true;
                return;
            }
            float timePercentage = (lapsedTime * 1.0f) / nextStart;
            float interp = this.mPhases[this.mPhase].interpolator.getInterpolation(timePercentage);
            if (this.mPhase == 0) {
                this.mOpacity = (int) (this.mPhases[this.mPhase].opacity * interp);
                this.mScale = ((int) ((this.mPhases[this.mPhase].size - 100) * interp)) + 100;
            } else {
                this.mOpacity = this.mPhases[this.mPhase - 1].opacity + ((int) ((this.mPhases[this.mPhase].opacity - this.mPhases[this.mPhase - 1].opacity) * interp));
                this.mScale = this.mPhases[this.mPhase - 1].size + ((int) ((this.mPhases[this.mPhase].size - this.mPhases[this.mPhase - 1].size) * interp));
            }
        }
    }
}

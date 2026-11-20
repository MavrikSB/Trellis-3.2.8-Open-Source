package co.vine.android.widget.trimControls;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.view.View;
import co.vine.android.R;
import java.util.ArrayList;

public class ThumbnailCarousel extends View {
    public static final int NUM_VISIBLE_THUMBNAILS = 8;
    private int mLeftPadding;
    private WidthMeasuredListener mListener;
    private int mThumbnailWidth;
    private ArrayList<Bitmap> mThumbnails;
    private Handler mUiHandler;
    private int mWidth;
    public int preferredHeight;

    public interface WidthMeasuredListener {
        void onWidthMeasured(int i);
    }

    public ThumbnailCarousel(Context context, int paddingLeft) {
        super(context, null);
        init(context, paddingLeft);
    }

    private void init(Context context, int paddingLeft) {
        this.mThumbnails = new ArrayList<>();
        this.preferredHeight = getResources().getDimensionPixelSize(R.dimen.carousel_height);
        this.mUiHandler = new Handler(context.getMainLooper());
        this.mLeftPadding = paddingLeft;
        setPadding(paddingLeft, 0, 0, 0);
    }

    public void updateLayout(int desiredThumbWidth, long durationMs, long maxDurationMs) {
        this.mThumbnailWidth = desiredThumbWidth;
        float thumbnailInterval = maxDurationMs / 8.0f;
        int numThumbnails = (int) (durationMs / thumbnailInterval);
        this.mWidth = (this.mThumbnailWidth * numThumbnails) + this.mLeftPadding;
        requestLayout();
    }

    public void setListener(WidthMeasuredListener listener) {
        this.mListener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSpec = this.mWidth > 0 ? View.MeasureSpec.makeMeasureSpec(this.mWidth + getPaddingRight(), 1073741824) : widthMeasureSpec;
        super.onMeasure(widthSpec, heightMeasureSpec);
        setMeasuredDimension(widthSpec, heightMeasureSpec);
        if (this.mWidth > 0 && this.mListener != null) {
            this.mListener.onWidthMeasured(this.mWidth);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!this.mThumbnails.isEmpty()) {
            int counter = 0;
            for (Bitmap thumbnail : this.mThumbnails) {
                if (thumbnail != null && !thumbnail.isRecycled()) {
                    canvas.drawBitmap(thumbnail, getPaddingLeft() + (this.mThumbnailWidth * counter), getTop(), (Paint) null);
                }
                counter++;
            }
        }
    }

    class RunnableC05811 implements Runnable {
        final  Bitmap val$bitmap;

        RunnableC05811(Bitmap bitmap) {
            this.val$bitmap = bitmap;
        }

        @Override
        public void run() {
            if (this.val$bitmap == null) {
                if (ThumbnailCarousel.this.mThumbnails.size() > 0) {
                    Bitmap altBitmap = (Bitmap) ThumbnailCarousel.this.mThumbnails.get(ThumbnailCarousel.this.mThumbnails.size() - 1);
                    ThumbnailCarousel.this.mThumbnails.add(altBitmap);
                }
            } else {
                ThumbnailCarousel.this.mThumbnails.add(this.val$bitmap);
            }
            ThumbnailCarousel.this.invalidate();
        }
    }

    public synchronized void addThumbnail(Bitmap bitmap) {
        this.mUiHandler.post(new RunnableC05811(bitmap));
    }

    public void addScrolledViewPadding(int paddingLeft, int paddingRight) {
        setPadding(paddingLeft, 0, paddingRight, 0);
    }

    public void releaseBitmaps() {
        for (Bitmap b : this.mThumbnails) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
    }

    public int getThumbnailStripWidth() {
        return this.mWidth;
    }
}

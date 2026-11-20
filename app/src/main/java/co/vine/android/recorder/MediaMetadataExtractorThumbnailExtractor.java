package co.vine.android.recorder;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import co.vine.android.util.CrashUtil;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingDeque;

@TargetApi(16)
public class MediaMetadataExtractorThumbnailExtractor implements ThumbnailExtractorInterface {
    private Context mContext;
    private Uri mInputUri;
    private ThumbnailExtractorInterface.OnThumbnailRetrievedListener mListener;
    private Thread mThumbnailExtractingThread;
    private LinkedBlockingDeque<Long> mTimestamps = new LinkedBlockingDeque<>();
    private boolean mExit = false;
    private long mLastAddedTimestamp = Long.MIN_VALUE;

    @Override
    public void start(Context context, Uri input, int targetLongestSize, ThumbnailExtractorInterface.OnThumbnailRetrievedListener listener) {
        this.mContext = context;
        this.mInputUri = input;
        this.mListener = listener;
        this.mThumbnailExtractingThread = new ThumbnailExtractingThread(this, null);
        this.mThumbnailExtractingThread.setPriority(10);
        this.mThumbnailExtractingThread.start();
    }

    private class ThumbnailExtractingThread extends Thread {
        private ThumbnailExtractingThread() {
        }

        ThumbnailExtractingThread(MediaMetadataExtractorThumbnailExtractor x0, Object x1) {
            this();
        }

        @Override
        public void run() {
            MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
            try {
                mediaMetadataRetriever.setDataSource(MediaMetadataExtractorThumbnailExtractor.this.mContext, MediaMetadataExtractorThumbnailExtractor.this.mInputUri);
            } catch (IllegalArgumentException e) {
                CrashUtil.log("Failed to set data source on " + MediaMetadataExtractorThumbnailExtractor.this.mInputUri);
                try {
                    mediaMetadataRetriever.release();
                } catch (IOException ignored) {
                }
                mediaMetadataRetriever = null;
                if (MediaMetadataExtractorThumbnailExtractor.this.mListener != null) {
                    MediaMetadataExtractorThumbnailExtractor.this.mListener.onError();
                    return;
                }
            }
            long timestamp = -1;
            while (!MediaMetadataExtractorThumbnailExtractor.this.mExit) {
                try {
                    timestamp = ((Long) MediaMetadataExtractorThumbnailExtractor.this.mTimestamps.take()).longValue();
                } catch (InterruptedException e2) {
                    MediaMetadataExtractorThumbnailExtractor.this.mExit = true;
                }
                Bitmap b = mediaMetadataRetriever.getFrameAtTime(timestamp);
                if (MediaMetadataExtractorThumbnailExtractor.this.mListener != null) {
                    MediaMetadataExtractorThumbnailExtractor.this.mListener.onThumbnailRetrieved(timestamp, b);
                }
            }
            try {
                mediaMetadataRetriever.release();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void requestThumbnail(long timestamp) {
        if (timestamp <= this.mLastAddedTimestamp) {
            throw new IllegalArgumentException("Cannot request a thumbnail that is before the last one");
        }
        this.mLastAddedTimestamp = timestamp;
        this.mTimestamps.add(Long.valueOf(timestamp));
    }

    @Override
    public void stop() {
        this.mExit = true;
        if (this.mThumbnailExtractingThread != null) {
            this.mThumbnailExtractingThread.interrupt();
        }
    }
}

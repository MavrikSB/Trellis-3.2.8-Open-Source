package co.vine.android.recorder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import co.vine.android.recorder.video.VideoData;
import com.edisonwang.android.slog.SLog;
import com.lifevibes.mediacoder.HwMediaCoder;
import java.util.ArrayDeque;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingDeque;

public class TranscodingCodecOutputSurface extends CodecOutputSurface implements SurfaceTexture.OnFrameAvailableListener {
    protected final Canvas mBitmapCanvas;
    private Point mCropOrigin;
    private LinkedBlockingDeque<VideoFrame> mEncodingIplQueue;
    private final EncodingThread mEncodingThread;
    private boolean mHasGeneratedThumbnail;
    private Matrix mMatrix;
    protected Bitmap mOutputBitmap;
    private ByteBuffer mPixelBuf;
    protected Bitmap mScaledCroppedBitmap;
    private LinkedBlockingDeque<ByteBuffer> mSourceIplQueue;
    private final String mThumbnailPath;

    public static class VideoFrame {
        final ByteBuffer image;
        final long timestamp;

        public VideoFrame(ByteBuffer image, long timestamp) {
            this.image = image;
            this.timestamp = timestamp;
        }
    }

    public TranscodingCodecOutputSurface(int inWidth, int inHeight, Point cropOrigin, int orientation, int outSize, int frames, ExtractorTranscoder extractorTranscoder, int videoBufferMaxByteCount) {
        super(inWidth, inHeight, outSize, cropOrigin);
        this.mSourceIplQueue = new LinkedBlockingDeque<>();
        this.mEncodingIplQueue = new LinkedBlockingDeque<>();
        this.mHasGeneratedThumbnail = false;
        HwVineFrameRecorder recorder = extractorTranscoder.getRecorder();
        this.mThumbnailPath = extractorTranscoder.getRecorderOutPath() + System.currentTimeMillis() + ".jpg";
        this.mMatrix = new Matrix();
        this.mMatrix.postRotate(orientation, outSize / 2.0f, outSize / 2.0f);
        if (orientation == 180) {
            this.mCropOrigin.x = -((this.mInWidth - this.mCropOrigin.x) - outSize);
        } else if (orientation == 270) {
            this.mCropOrigin.y = -((this.mInWidth - this.mCropOrigin.y) - outSize);
        }
        System.nanoTime();
        frames = frames < 10 ? 10 : frames;
        for (int i = 0; i < frames / 2; i++) {
            this.mSourceIplQueue.add(ByteBuffer.allocate(outSize * outSize * 4));
        }
        this.mEncodingThread = new EncodingThread(recorder, this.mEncodingIplQueue, this.mSourceIplQueue, new C04871(extractorTranscoder, recorder), videoBufferMaxByteCount);
        this.mOutputBitmap = Bitmap.createBitmap(this.mInWidth, this.mInHeight, Bitmap.Config.ARGB_8888);
        this.mScaledCroppedBitmap = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888);
        this.mPixelBuf = ByteBuffer.allocateDirect(this.mInWidth * this.mInHeight * 4);
        this.mPixelBuf.order(ByteOrder.LITTLE_ENDIAN);
        this.mPaint.setAntiAlias(false);
        this.mPaint.setDither(false);
        this.mPaint.setFilterBitmap(false);
        this.mBitmapCanvas = new Canvas(this.mScaledCroppedBitmap);
        this.mEncodingThread.setPriority(10);
        this.mEncodingThread.start();
    }

    class C04871 implements EncodingThread.EncodingDoneListener {
        final  ExtractorTranscoder val$extractorTranscoder;
        final  HwVineFrameRecorder val$recorder;

        C04871(ExtractorTranscoder extractorTranscoder, HwVineFrameRecorder swVineFrameRecorder) {
            this.val$extractorTranscoder = extractorTranscoder;
            this.val$recorder = swVineFrameRecorder;
        }

        @Override
        public void encodingProgressMade(long justProcessedPresentationTimestampUs) {
            if (justProcessedPresentationTimestampUs > 0) {
                this.val$extractorTranscoder.onEncodingProgressMade(justProcessedPresentationTimestampUs);
                return;
            }
            this.val$recorder.stopEncoding();
            this.val$extractorTranscoder.onFinishedVideoEncoding(TranscodingCodecOutputSurface.this.mEncodingThread.mVideoBufferMax, TranscodingCodecOutputSurface.this.mEncodingThread.mVideoData, TranscodingCodecOutputSurface.this.mThumbnailPath, TranscodingCodecOutputSurface.this.mScaledCroppedBitmap, this.val$extractorTranscoder.getFrameRate());
        }
    }

    @Override
    protected void establishSize(int inWidth, int inHeight, int outSize, Point cropOrigin) {
        int smallestDimension = Math.min(inWidth, inHeight);
        float scale = outSize / (1.0f * smallestDimension);
        this.mInWidth = Math.round(inWidth * scale);
        this.mInHeight = Math.round(inHeight * scale);
        int scaledOriginX = Math.round(cropOrigin.x * scale);
        int scaledOriginY = Math.round(cropOrigin.y * scale);
        this.mCropOrigin = new Point(scaledOriginX, scaledOriginY);
    }

    public void done() throws FileNotFoundException {
        this.mEncodingThread.done();
        SLog.m446d("Done decoding, waiting for encode to be done.");
        try {
            this.mEncodingThread.join();
        } catch (InterruptedException e) {
            if (SLog.sLogsOn) {
                e.printStackTrace();
            }
        }
    }

    public void saveFrame(long presentationTimestampUS) throws IOException {
        this.mPixelBuf.rewind();
        System.nanoTime();
        GLES20.glReadPixels(0, 0, this.mInWidth, this.mInHeight, 6408, 5121, this.mPixelBuf);
        this.mPixelBuf.rewind();
        System.nanoTime();
        this.mOutputBitmap.copyPixelsFromBuffer(this.mPixelBuf);
        System.nanoTime();
        this.mBitmapCanvas.save();
        this.mBitmapCanvas.translate(-this.mCropOrigin.x, -this.mCropOrigin.y);
        this.mBitmapCanvas.drawBitmap(this.mOutputBitmap, this.mMatrix, this.mPaint);
        this.mBitmapCanvas.restore();
        try {
            ByteBuffer image = this.mSourceIplQueue.take();
            image.clear();
            this.mScaledCroppedBitmap.copyPixelsToBuffer(image);
            image.rewind();
            if (!this.mHasGeneratedThumbnail) {
                this.mScaledCroppedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, new FileOutputStream(this.mThumbnailPath));
                this.mHasGeneratedThumbnail = true;
            }
            this.mEncodingIplQueue.add(new VideoFrame(image, presentationTimestampUS));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static class EncodingThread extends Thread {
        private LinkedBlockingDeque<VideoFrame> mInQueue;
        private EncodingDoneListener mListener;
        private LinkedBlockingDeque<ByteBuffer> mOutQueue;
        private HwVineFrameRecorder mRecorder;
        private final ArrayDeque<VideoData> mPending = new ArrayDeque<>();
        private byte[] mVideoBufferMax;
        private ArrayList<VideoData> mVideoData = new ArrayList<>();
        private boolean mDone = false;
        private int mVideoBufferPosition = 0;

        public interface EncodingDoneListener {
            void encodingProgressMade(long j);
        }

        public EncodingThread(HwVineFrameRecorder recorder, LinkedBlockingDeque<VideoFrame> inQueue, LinkedBlockingDeque<ByteBuffer> recycleQueue, EncodingDoneListener listener, int videoBufferMaxBytes) {
            this.mVideoBufferMax = new byte[videoBufferMaxBytes];
            this.mInQueue = inQueue;
            this.mOutQueue = recycleQueue;
            this.mRecorder = recorder;
            this.mListener = listener;
            recorder.setDataListener(new HwEncodingRunnable.FrameSink() {
                @Override
                public void onFrameAvailable(ByteBuffer encoded) {
                    VideoData data = EncodingThread.this.mPending.poll();
                    if (data != null) {
                        byte[] bytes = new byte[encoded.remaining()];
                        encoded.duplicate().get(bytes);
                        EncodingThread.this.store(data, EncodingThread.this.mRecorder.putEncodedData(encoded, EncodingThread.this.mVideoBufferMax, EncodingThread.this.mVideoBufferPosition), bytes);
                    }
                }
            });
        }

        private void store(VideoData data, int size, byte[] bytes) {
            data.encoded = true;
            data.start = this.mVideoBufferPosition;
            data.size = size;
            data.keyFrame = HwMediaCoder.containsIdr(bytes);
            this.mVideoData.add(data);
            this.mVideoBufferPosition += size;
        }

        @Override
        public void run() {
            while (true) {
                if (this.mDone && this.mInQueue.isEmpty()) {
                    break;
                }
                try {
                    VideoFrame frame = this.mInQueue.take();
                    ByteBuffer image = frame.image;
                    long presentationTimestampUS = frame.timestamp;
                    VideoData data = new VideoData();
                    data.timestamp = presentationTimestampUS;
                    this.mPending.add(data);
                    int position = this.mVideoBufferPosition;
                    image.rewind();
                    int size = this.mRecorder.encode(image, this.mVideoBufferMax, position, false);
                    if (size >= 0) {
                        byte[] bytes = new byte[size];
                        System.arraycopy(this.mVideoBufferMax, position, bytes, 0, size);
                        this.store(this.mPending.poll(), size, bytes);
                    }
                    this.mOutQueue.add(image);
                    if (presentationTimestampUS > 0) {
                        this.mListener.encodingProgressMade(presentationTimestampUS);
                    }
                } catch (InterruptedException e2) {
                }
            }
            if (this.mListener != null) {
                this.mListener.encodingProgressMade(-1L);
            }
        }

        public void done() {
            this.mDone = true;
            interrupt();
        }
    }
}

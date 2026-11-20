package co.vine.android.recorder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Process;
import co.vine.android.recorder.buffered.BufferedPreviewManagerCallback;
import co.vine.android.recorder.camera.CameraSetting;
import co.vine.android.recorder.video.VideoData;
import co.vine.android.util.CrashUtil;
import com.edisonwang.android.slog.SLog;
import com.lifevibes.mediacoder.LVMediaCoder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class HwEncodingRunnable extends AbstractEncodingRunnable {
    public abstract static class FrameSink implements LVMediaCoder.LVMediaCoderICSEncoderProgressListener {
        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Exception e, int code) {
        }
    }

    private BaseFinishProcessTask mAsyncTask;
    private final ByteBufferQueue mByteBufferQueue;
    private final Context mContext;
    private final ConcurrentLinkedQueue<VideoData> mDataQueue;
    private HwVineFrameRecorder mEncoder;
    private final EncodingProcessRunnable mEncodingRunnable;
    private final Thread mEncodingThread;
    private Bitmap mMatrixBitmap;
    private Canvas mMatrixCanvas;
    private int mMaxEncodedSize;
    private final String mOutput;
    private final PictureConverter mPictureConverter;
    private final BufferedPreviewManagerCallback mPreviewCallback;
    private final RecordState mProducer;
    private final int mRecorderFrameRate;
    private volatile boolean mTerminateImmediately = false;
    private final Bitmap mThumbnailBitmap;
    private final Canvas mThumbnailCanvas;
    private final Matrix mThumbnailMatrix;
    private int mTotalProcessed;
    private final byte[] mVideoDataBufferMax;
    private int mVideoDataBufferPosition;
    private long timeToMatrix;
    private long timeToRGB;
    private long timeToRecord;

    public HwEncodingRunnable(Context context, ConcurrentLinkedQueue<VideoData> queue, byte[] videoDataBufferMax, RecordState producer, String output, int frameRate, ByteBuffer buffer, Bitmap bitmap, Bitmap thumbnailBitmap, PictureConverter pc, int videoBufferPosition, BufferedPreviewManagerCallback cb, ByteBufferQueue byteBufferQueue, Matrix thumbnailMatrix, Canvas thumbnailCanvas, Canvas matrixCanvas) {
        this.mContext = context;
        this.mDataQueue = queue;
        this.mProducer = producer;
        this.mMatrixBitmap = bitmap;
        this.mThumbnailBitmap = thumbnailBitmap;
        this.mThumbnailCanvas = thumbnailCanvas;
        this.mMatrixCanvas = matrixCanvas;
        this.mVideoDataBufferMax = videoDataBufferMax;
        this.mVideoDataBufferPosition = videoBufferPosition;
        this.mPictureConverter = pc;
        this.mPreviewCallback = cb;
        this.mRecorderFrameRate = frameRate;
        this.mOutput = output;
        this.mThumbnailMatrix = thumbnailMatrix;
        this.mByteBufferQueue = byteBufferQueue;
        this.mEncodingRunnable = new EncodingProcessRunnable(buffer);
        this.mEncodingThread = new Thread(this.mEncodingRunnable);
        if (!this.mByteBufferQueue.isFresh()) {
            throw new RuntimeException("Invalid buffer queue.");
        }
    }

    class EncodingProcessRunnable implements Runnable, LVMediaCoder.LVMediaCoderICSEncoderProgressListener {
        private LinkedList<VideoData> mBufferedVideoData;
        private ByteBuffer mByteBuffer;
        private int mErrorCount;
        private VideoData mLast;
        private RecordSegment mLastSegment;
        private int mReceived;
        public boolean mRun;
        private boolean notFirst;
        private final int[] LOCK = new int[0];
        private int totalSizeToWait = -1;

        public EncodingProcessRunnable(ByteBuffer buffer) {
            this.mByteBuffer = buffer;
            makeNewEncoder();
            this.mBufferedVideoData = new LinkedList<>();
        }

        @Override
        public void run() {
            Process.setThreadPriority(19);
            this.mLastSegment = null;
            SLog.m446d("EncodingProcessRunnable started.");
            while (this.mRun) {
                ByteBufferQueue.QueueItem data = HwEncodingRunnable.this.mByteBufferQueue.get();
                if (!HwEncodingRunnable.this.mProducer.isStarted() && HwEncodingRunnable.this.mAsyncTask != null) {
                    int size = Math.max(HwEncodingRunnable.this.mByteBufferQueue.size(), 1);
                    if (this.totalSizeToWait == -1) {
                        this.totalSizeToWait = size;
                        Process.setThreadPriority(-8);
                        SLog.m465i("Size to wait: {}.", Integer.valueOf(this.totalSizeToWait));
                    }
                    HwEncodingRunnable.this.mAsyncTask.publish(((this.totalSizeToWait - size) * 90) / this.totalSizeToWait);
                }
                if (data != null) {
                    SLog.m447d("Got data. {}", this);
                    VideoData next = (VideoData) data.tag;
                    next.data = data.bytes;
                    if (this.mLast != null) {
                        try {
                            processVideoData(this.mLast, this.mLast.segment != next.segment);
                            this.mLast = null;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    this.mLast = next;
                } else if (HwEncodingRunnable.this.mByteBufferQueue.isEndOfPut()) {
                    this.mRun = false;
                } else {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e2) {
                        SLog.m455e("Interrupted.");
                    }
                }
            }
            if (this.mLast != null) {
                try {
                    processVideoData(this.mLast, true);
                    this.mLast = null;
                } catch (Exception e3) {
                    throw new RuntimeException(e3);
                }
            }
            stopEncoder(0);
        }

        private boolean doVideoFrameEncoded(ByteBuffer byteBuffer) throws HwVineFrameRecorder.Exception {
            if (this.mBufferedVideoData.isEmpty()) {
                return false;
            }
            VideoData data = this.mBufferedVideoData.getFirst();
            data.size = HwEncodingRunnable.this.mEncoder.putEncodedData(byteBuffer, HwEncodingRunnable.this.mVideoDataBufferMax, HwEncodingRunnable.this.mVideoDataBufferPosition);
            return putVideoFrame(data);
        }

        private boolean doVideoFrameRaw(byte[] image, boolean newSegment, boolean lastFrame) throws HwVineFrameRecorder.Exception {
            if (HwEncodingRunnable.this.mEncoder == null) {
                makeNewEncoder();
            } else {
                if (newSegment && this.notFirst) {
                    stopEncoder(1);
                    makeNewEncoder();
                }
                this.notFirst = true;
            }
            HwEncodingRunnable.this.mTotalProcessed++;
            if (Build.VERSION.SDK_INT < 16) {
                HwEncodingRunnable.this.mEncoder.encode(ByteBuffer.wrap(image), HwEncodingRunnable.this.mVideoDataBufferMax, HwEncodingRunnable.this.mVideoDataBufferPosition, lastFrame);
                return false;
            }
            VideoData data = this.mBufferedVideoData.getFirst();
            data.size = HwEncodingRunnable.this.mEncoder.encode(ByteBuffer.wrap(image), HwEncodingRunnable.this.mVideoDataBufferMax, HwEncodingRunnable.this.mVideoDataBufferPosition, lastFrame);
            SLog.m449d("onFrameAvailable Write video frame size {}. Total processed {}, last Frame? {}.", Integer.valueOf(data.size), Integer.valueOf(HwEncodingRunnable.this.mTotalProcessed), Boolean.valueOf(lastFrame));
            return putVideoFrame(data);
        }

        private boolean putVideoFrame(VideoData data) {
            if (data.size != -1) {
                data.start = HwEncodingRunnable.this.mVideoDataBufferPosition;
                HwEncodingRunnable.this.mMaxEncodedSize = Math.max(HwEncodingRunnable.this.mMaxEncodedSize, data.size);
                HwEncodingRunnable.this.mVideoDataBufferPosition += data.size;
                this.mBufferedVideoData.pop();
                return true;
            }
            SLog.m446d("******Image was buffered to the next frame.********");
            return false;
        }

        private void makeNewEncoder() {
            HwEncodingRunnable.this.mTotalProcessed = 0;
            this.mReceived = 0;
            HwEncodingRunnable.this.mEncoder = RecordConfigUtils.newVideoRecorder(HwEncodingRunnable.this.mContext, null, HwEncodingRunnable.this.mRecorderFrameRate);
            HwEncodingRunnable.this.mEncoder.setDataListener(this);
        }

        private void stopEncoder(int maxBufferCount) {
            try {
                SLog.m447d("Stopping encoder. Error count: {}.", Integer.valueOf(this.mErrorCount));
                HwEncodingRunnable.this.mEncoder.stopEncoding();
                SLog.m448d("Frames left? {}, allowed? {}.", Integer.valueOf(this.mBufferedVideoData.size()), Integer.valueOf(maxBufferCount));
                while (this.mBufferedVideoData.size() > maxBufferCount) {
                    this.mBufferedVideoData.pop().size = -1;
                }
                this.mErrorCount = 0;
            } catch (HwVineFrameRecorder.Exception e2) {
                SLog.m459e("Error flushing...", (Throwable) e2);
            }
        }

        public void add(VideoData next) {
            synchronized (this.LOCK) {
                SLog.m446d("Added to the queue. " + this);
                if (!HwEncodingRunnable.this.mByteBufferQueue.put(new ByteBufferQueue.QueueItem(next.data, next))) {
                    CrashUtil.log("Not enough memory left. (BufferOverflow)");
                }
            }
        }

        public byte[] processVideoData(VideoData next, boolean lastFrame) throws HwVineFrameRecorder.Exception, IOException {
            boolean newSegment = this.mLastSegment != next.segment;
            this.mLastSegment = next.segment;
            synchronized (HwEncodingRunnable.this.mPictureConverter.LOCK) {
                long startTime = System.currentTimeMillis();
                CameraSetting cameraSetting = next.segment.getCameraSetting();
                boolean hasChanged = HwEncodingRunnable.this.mPictureConverter.updateSettingsIfNeeded(cameraSetting);
                HwEncodingRunnable.this.mPictureConverter.giveMatrixNewValuesWithScaleIfDegreeHasChangedWithKnownConfigs(cameraSetting.degrees, false);
                HwEncodingRunnable.this.mPictureConverter.convert(cameraSetting, next, hasChanged);
                HwEncodingRunnable.this.timeToRGB += (System.currentTimeMillis() - startTime);
                long startTime2 = System.currentTimeMillis();
                HwEncodingRunnable.this.mPictureConverter.draw(HwEncodingRunnable.this.mMatrixCanvas);
                HwEncodingRunnable.this.timeToMatrix += (System.currentTimeMillis() - startTime2);
            }
            this.mByteBuffer.clear();
            HwEncodingRunnable.this.mMatrixBitmap.copyPixelsToBuffer(this.mByteBuffer);
            if (newSegment && !HwEncodingRunnable.this.mTerminateImmediately) {
                HwEncodingRunnable.this.mPictureConverter.drawGeneric(HwEncodingRunnable.this.mThumbnailCanvas, HwEncodingRunnable.this.mMatrixBitmap, HwEncodingRunnable.this.mThumbnailMatrix);
                String path = HwEncodingRunnable.this.mOutput + "." + System.currentTimeMillis() + ".jpg";
                next.segment.setThumbnailPath(path);
                File f = new File(path);
                File parentFile = f.getParentFile();
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    HwEncodingRunnable.this.mThumbnailBitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                    byte[] compressed = bos.toByteArray();
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(compressed);
                    fos.close();
                } catch (Throwable e) {
                    boolean wasSessionJustDeleted = RecordSessionManager.wasSessionJustDeleted(parentFile);
                    if (wasSessionJustDeleted) {
                        HwEncodingRunnable.this.mTerminateImmediately = true;
                        CrashUtil.logException(e);
                    } else {
                        throw e;
                    }
                }
            }
            long startTime3 = System.currentTimeMillis();
            this.mBufferedVideoData.add(next);
            doVideoFrameRaw(this.mByteBuffer.array(), newSegment, lastFrame);
            HwEncodingRunnable.this.timeToRecord += (System.currentTimeMillis() - startTime3);
            byte[] data = next.data;
            next.data = null;
            return data;
        }

        @Override
        public void onFrameAvailable(ByteBuffer byteBuffer) {
            this.mReceived++;
            SLog.m448d("Got Frame! Processed: {}, Received: {}.", Integer.valueOf(HwEncodingRunnable.this.mTotalProcessed), Integer.valueOf(this.mReceived));
            doVideoFrameEncoded(byteBuffer);
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Exception e, int code) {
            SLog.m451d("Encoding error: {}.", (Object) Integer.valueOf(code), (Throwable) e);
            this.mErrorCount++;
        }
    }

    @Override
    public void setAsyncTask(BaseFinishProcessTask task) {
        this.mAsyncTask = task;
    }

    @Override
    public void terminate() {
        SLog.m446d("Terminate without saving turned on.");
        this.mTerminateImmediately = true;
    }

    @Override
    public void run() {
        Process.setThreadPriority(19);
        CrashUtil.log("Processing started: {}.", this.mOutput);
        this.mEncodingRunnable.mRun = true;
        this.mEncodingThread.start();
        while (this.mProducer.isStarted() && !this.mTerminateImmediately) {
            process(true);
        }
        SLog.m446d("Flushing...");
        if (!this.mTerminateImmediately) {
            process(false);
        } else {
            SLog.m446d("Terminate without saving....");
        }
        this.mByteBufferQueue.endOfPut();
        try {
            this.mEncodingThread.join();
        } catch (InterruptedException e) {
            SLog.m455e("Interrupted.");
        }
        if (this.mAsyncTask != null) {
            this.mAsyncTask.publish(90);
        }
        SLog.m442b("Time used: rgb: {}ms", Long.valueOf(this.timeToRGB));
        SLog.m442b("Time used: matrix: {}ms.", Long.valueOf(this.timeToMatrix));
        SLog.m442b("Total Processed: {}.", Integer.valueOf(this.mTotalProcessed));
        SLog.m442b("Total Encoding time: {}ms.", Long.valueOf(this.timeToRecord));
        CrashUtil.log("Processing Ended: {}.", this.mOutput);
    }

    private void process(boolean waitForMore) {
        while (!this.mTerminateImmediately) {
            VideoData next = this.mDataQueue.poll();
            if (next != null || this.mProducer.isStarted()) {
                if (next == null) {
                    if (waitForMore) {
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException e) {
                            SLog.m446d("Break signal reached.");
                            return;
                        }
                    } else {
                        continue;
                    }
                } else {
                    if (!next.segment.removed) {
                        this.mEncodingRunnable.add(next);
                    } else {
                        SLog.m464i("Skipped processing data from deleted segments.");
                    }
                    this.mPreviewCallback.addBuffer(next.data, !waitForMore);
                }
            } else {
                return;
            }
        }
    }

    public String getOutput() {
        return this.mOutput;
    }
}

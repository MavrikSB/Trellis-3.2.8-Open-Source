package co.vine.android.recorder;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import co.vine.android.util.CrashUtil;
import com.edisonwang.android.slog.SLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

@TargetApi(16)
public class ThumbnailExtractor implements ThumbnailExtractorInterface {
    private static final String TAG = "ThumbnailExtractor";
    private static final int TIMEOUT_USEC = 10000;
    public static final boolean VERBOSE = true;
    private Context mContext;
    private MediaCodec mDecoder;
    private int mHeight;
    private Uri mInputUri;
    private ThumbnailExtractorInterface.OnThumbnailRetrievedListener mListener;
    private MediaExtractor mMediaExtractor;
    private FrameExtractingCodecOutputSurface mOutputSurface;
    private int mTargetSize;
    private Thread mThumbnailExtractingThread;
    private int mWidth;
    private LinkedBlockingDeque<Long> mTimestamps = new LinkedBlockingDeque<>();
    private boolean mExit = false;
    private long mLastAddedTimestamp = Long.MIN_VALUE;
    private MediaMetadataExtractorThumbnailExtractor mFailoverExtractor = null;

    @Override
    public void start(Context context, Uri input, int targetLongestSize, ThumbnailExtractorInterface.OnThumbnailRetrievedListener listener) {
        this.mContext = context;
        this.mInputUri = input;
        this.mTargetSize = targetLongestSize;
        this.mListener = listener;
        this.mThumbnailExtractingThread = new ThumbnailExtractingThread(this, null);
        this.mThumbnailExtractingThread.setPriority(10);
        this.mThumbnailExtractingThread.start();
    }

    private class ThumbnailExtractingThread extends Thread {
        private ThumbnailExtractingThread() {
        }

        ThumbnailExtractingThread(ThumbnailExtractor x0, Object x1) {
            this();
        }

        @Override
        public void run() {
            String mime = null;
            try {
                ThumbnailExtractor.this.mMediaExtractor = new MediaExtractor();
                try {
                    ThumbnailExtractor.this.mMediaExtractor.setDataSource(ThumbnailExtractor.this.mContext, ThumbnailExtractor.this.mInputUri, (Map<String, String>) null);
                    int trackIndex = ExtractorTranscoder.selectVideoTrack(ThumbnailExtractor.this.mMediaExtractor);
                    if (trackIndex < 0) {
                        ThumbnailExtractor.this.mListener.onError();
                        CrashUtil.logException(new Exception("No video track found in " + ThumbnailExtractor.this.mInputUri.toString()));
                        return;
                    }
                    ThumbnailExtractor.this.mMediaExtractor.selectTrack(trackIndex);
                    MediaFormat format = ThumbnailExtractor.this.mMediaExtractor.getTrackFormat(trackIndex);
                    Log.d(ThumbnailExtractor.TAG, "Video size is " + format.getInteger("width") + "x" + format.getInteger("height"));
                    int orientation = 0;
                    if (Build.VERSION.SDK_INT >= 17) {
                        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
                        mediaMetadataRetriever.setDataSource(ThumbnailExtractor.this.mContext, ThumbnailExtractor.this.mInputUri);
                        String orientationString = mediaMetadataRetriever.extractMetadata(24);
                        if (orientationString != null) {
                            orientation = Integer.valueOf(orientationString).intValue();
                        }
                        mediaMetadataRetriever.release();
                    }
                    ThumbnailExtractor.this.mWidth = format.getInteger("width");
                    ThumbnailExtractor.this.mHeight = format.getInteger("height");
                    ThumbnailExtractor.this.mOutputSurface = new FrameExtractingCodecOutputSurface(ThumbnailExtractor.this.mWidth, ThumbnailExtractor.this.mHeight, ThumbnailExtractor.this.mTargetSize, orientation);
                    if (format.containsKey("rotation-degrees")) {
                        format.setInteger("rotation-degrees", 0);
                    }
                    mime = format.getString("mime");
                    ThumbnailExtractor.this.mDecoder = MediaCodec.createDecoderByType(mime);
                    ThumbnailExtractor.this.mDecoder.configure(format, ThumbnailExtractor.this.mOutputSurface.getSurface(), (MediaCrypto) null, 0);
                    ThumbnailExtractor.this.mDecoder.start();
                    ByteBuffer[] decoderinput = ThumbnailExtractor.this.mDecoder.getInputBuffers();
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int inputChunk = 0;
                    boolean outputDone = false;
                    boolean inputDone = false;
                    long timestamp = -1;
                    while (!outputDone && !ThumbnailExtractor.this.mExit) {
                        if (timestamp == -1) {
                            try {
                                timestamp = ((Long) ThumbnailExtractor.this.mTimestamps.take()).longValue();
                            } catch (InterruptedException e) {
                                inputDone = true;
                                outputDone = true;
                                ThumbnailExtractor.this.mExit = true;
                            }
                        }
                        if (!inputDone) {
                            int inputBufIndex = ThumbnailExtractor.this.mDecoder.dequeueInputBuffer(10000L);
                            if (inputBufIndex >= 0) {
                                ByteBuffer inputBuf = decoderinput[inputBufIndex];
                                int chunkSize = ThumbnailExtractor.this.mMediaExtractor.readSampleData(inputBuf, 0);
                                if (chunkSize < 0) {
                                    ThumbnailExtractor.this.mDecoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, 4);
                                    inputDone = true;
                                    Log.d(ThumbnailExtractor.TAG, "sent input EOS");
                                } else {
                                    if (ThumbnailExtractor.this.mMediaExtractor.getSampleTrackIndex() != trackIndex) {
                                        Log.w(ThumbnailExtractor.TAG, "WEIRD: got sample from track " + ThumbnailExtractor.this.mMediaExtractor.getSampleTrackIndex() + ", expected " + trackIndex);
                                    }
                                    long presentationTimeUs = ThumbnailExtractor.this.mMediaExtractor.getSampleTime();
                                    ThumbnailExtractor.this.mDecoder.queueInputBuffer(inputBufIndex, 0, chunkSize, presentationTimeUs, 0);
                                    Log.d(ThumbnailExtractor.TAG, "submitted frame " + inputChunk + " to dec, size=" + chunkSize);
                                    inputChunk++;
                                    ThumbnailExtractor.this.mMediaExtractor.advance();
                                }
                            } else {
                                Log.d(ThumbnailExtractor.TAG, "input buffer not available");
                            }
                        }
                        int decoderStatus = ThumbnailExtractor.this.mDecoder.dequeueOutputBuffer(info, 10000L);
                        if (decoderStatus == -1) {
                            SLog.m446d("no output from decoder available");
                        } else if (decoderStatus == -3) {
                            SLog.m446d("decoder output buffers changed");
                        } else if (decoderStatus == -2) {
                            MediaFormat newFormat = ThumbnailExtractor.this.mDecoder.getOutputFormat();
                            SLog.m446d("decoder output format changed: " + newFormat);
                        } else if (decoderStatus >= 0) {
                            SLog.m446d("surface decoder given buffer " + decoderStatus + " (size=" + info.size + ")");
                            if ((info.flags & 4) != 0) {
                                outputDone = true;
                            }
                            boolean doRender = false;
                            if (info.presentationTimeUs >= timestamp) {
                                doRender = true;
                                timestamp = -1;
                            }
                            if (!ThumbnailExtractor.this.mExit) {
                                ThumbnailExtractor.this.mDecoder.releaseOutputBuffer(decoderStatus, doRender);
                                if (doRender) {
                                    boolean draw = true;
                                    try {
                                        ThumbnailExtractor.this.mOutputSurface.awaitNewImage();
                                    } catch (Exception e2) {
                                        draw = false;
                                    }
                                    if (draw) {
                                        ThumbnailExtractor.this.mOutputSurface.drawImage(true);
                                        Bitmap b = ThumbnailExtractor.this.mOutputSurface.saveFrame();
                                        if (ThumbnailExtractor.this.mListener != null) {
                                            ThumbnailExtractor.this.mListener.onThumbnailRetrieved(timestamp, b);
                                        }
                                    } else {
                                        ThumbnailExtractor.this.mExit = true;
                                    }
                                }
                            }
                        }
                    }
                    if (ThumbnailExtractor.this.mMediaExtractor != null) {
                        ThumbnailExtractor.this.mMediaExtractor.release();
                        ThumbnailExtractor.this.mMediaExtractor = null;
                    }
                    if (ThumbnailExtractor.this.mDecoder != null) {
                        ThumbnailExtractor.this.mDecoder.stop();
                        ThumbnailExtractor.this.mDecoder.release();
                        ThumbnailExtractor.this.mDecoder = null;
                    }
                    if (ThumbnailExtractor.this.mOutputSurface != null) {
                        ThumbnailExtractor.this.mOutputSurface.release();
                        ThumbnailExtractor.this.mOutputSurface = null;
                    }
                } catch (IOException e3) {
                    ThumbnailExtractor.this.mListener.onError();
                }
            } catch (Exception e4) {
                SLog.m446d("ryango failover");
                CrashUtil.logException(e4, "Failing over from MediaCodec thumbnail extractor to MediaMetadataRetriever version - phone model {} mime {}", Build.MODEL, mime);
                ThumbnailExtractor.this.mFailoverExtractor = new MediaMetadataExtractorThumbnailExtractor();
                while (!ThumbnailExtractor.this.mTimestamps.isEmpty()) {
                    ThumbnailExtractor.this.mFailoverExtractor.requestThumbnail(((Long) ThumbnailExtractor.this.mTimestamps.removeFirst()).longValue());
                }
                ThumbnailExtractor.this.mFailoverExtractor.start(ThumbnailExtractor.this.mContext, ThumbnailExtractor.this.mInputUri, ThumbnailExtractor.this.mTargetSize, ThumbnailExtractor.this.mListener);
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
        if (this.mFailoverExtractor != null) {
            this.mFailoverExtractor.stop();
        }
    }

    public static ThumbnailExtractorInterface getExtractor(boolean forceMetadataRetriever) {
        return (forceMetadataRetriever || RecordConfigUtils.RecordConfig.SHOULD_USE_MEDIAMETADATAEXTRACTOR) ? new MediaMetadataExtractorThumbnailExtractor() : new ThumbnailExtractor();
    }
}

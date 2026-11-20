package com.lifevibes.mediacoder;

import android.annotation.TargetApi;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;

@TargetApi(21)
public class HwMediaCoder implements LVMediaCoder {
    private static final String TAG = "HwMediaCoder";
    private static final String AVC = "video/avc";
    private static final String AAC = "audio/mp4a-latm";
    private static final long POLL_US = 5000;
    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    private static volatile byte[] sVideoCsd;

    private final LVMediaCoderConfiguration mConfig;
    private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();
    private final ArrayDeque<byte[]> mPendingVideo = new ArrayDeque<>();
    private final ArrayDeque<byte[]> mPendingAudio = new ArrayDeque<>();
    private MediaCodec mVideo;
    private MediaCodec mAudio;
    private MediaMuxer mMuxer;
    private int mVideoTrack = -1;
    private int mAudioTrack = -1;
    private boolean mMuxerStarted;
    private boolean mRecording;
    private String mOutputPath;
    private long mVideoFramesQueued;
    private long mAudioSamplesQueued;
    private long mAudioFramesWritten;
    private long mLastVideoPtsUs = -1;
    private byte[] mRow;
    private LVMediaCoderICSEncoderProgressListener mListener;

    public HwMediaCoder(LVMediaCoderConfiguration configuration) {
        this.mConfig = configuration;
    }

    @Override
    public void startEncoding() {
        try {
            this.mVideo = createVideoEncoder();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void startRecording(String outputFilePath) {
        if (outputFilePath == null || outputFilePath.isEmpty()) {
            throw new IllegalArgumentException("Invalid output filepath parameter.");
        }
        this.mOutputPath = outputFilePath;
        File parent = new File(outputFilePath).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            byte[] csd = sVideoCsd != null ? sVideoCsd : probeVideoCsd();
            this.mAudio = createAudioEncoder();
            this.mMuxer = new MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            this.mVideoTrack = this.mMuxer.addTrack(videoTrackFormat(csd));
            this.mAudioTrack = this.mMuxer.addTrack(audioTrackFormat());
            this.mMuxer.start();
            this.mMuxerStarted = true;
            this.mRecording = true;
        } catch (IOException e) {
            release();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ByteBuffer encodeVideo(ByteBuffer inputFrame, int inputType) {
        if (this.mVideo == null) {
            throw new IllegalStateException("Video encoder is not running.");
        }
        if (inputFrame.remaining() < this.mConfig.videoWidth * this.mConfig.videoHeight * (inputType == 0 ? 3 : 4)) {
            throw new IllegalArgumentException("Improper size of input frame: " + inputFrame.remaining() + " bytes.");
        }
        int index = -1;
        for (int tries = 0; tries < 40 && index < 0; tries++) {
            index = this.mVideo.dequeueInputBuffer(POLL_US);
            if (index < 0) {
                drainVideo(0);
            }
        }
        if (index >= 0) {
            int bpp = inputType == 0 ? 3 : 4;
            Image image = this.mVideo.getInputImage(index);
            int size = this.mConfig.videoWidth * this.mConfig.videoHeight * 3 / 2;
            if (image != null) {
                fillImage(image, inputFrame, bpp);
            } else {
                ByteBuffer buffer = this.mVideo.getInputBuffer(index);
                fillNv12(buffer, inputFrame, bpp);
            }
            long ptsUs = (this.mVideoFramesQueued * 1000000L) / Math.max(1, this.mConfig.videoFrameRate);
            this.mVideoFramesQueued++;
            this.mVideo.queueInputBuffer(index, 0, size, ptsUs, 0);
        } else {
            Log.w(TAG, "No encoder input buffer available, frame dropped.");
        }
        for (int tries = 0; tries < 6 && this.mPendingVideo.isEmpty(); tries++) {
            drainVideo(POLL_US);
        }
        byte[] out = this.mPendingVideo.poll();
        return out == null ? null : ByteBuffer.wrap(out);
    }

    @Override
    public void encodeVideoAsync(ByteBuffer inputFrame, int inputType, boolean isEoS) {
        throw new IllegalStateException("encodeVideoAsync is only used on Android 4.0.");
    }

    @Override
    public ShortBuffer encodeAudio(byte[] pcmFrame, int start, int byteCount) {
        if (this.mAudio == null) {
            throw new IllegalStateException("Audio encoder is not running.");
        }
        int index = -1;
        for (int tries = 0; tries < 40 && index < 0; tries++) {
            index = this.mAudio.dequeueInputBuffer(POLL_US);
            if (index < 0) {
                drainAudio(0);
            }
        }
        if (index >= 0) {
            ByteBuffer buffer = this.mAudio.getInputBuffer(index);
            buffer.clear();
            int count = Math.min(byteCount, buffer.remaining());
            buffer.put(pcmFrame, start, count);
            long ptsUs = (this.mAudioSamplesQueued * 1000000L) / this.mConfig.audioSampleRate;
            this.mAudioSamplesQueued += count / (2 * this.mConfig.audioChannelCount);
            this.mAudio.queueInputBuffer(index, 0, count, ptsUs, 0);
        }
        drainAudio(POLL_US);
        byte[] out = this.mPendingAudio.poll();
        if (out == null) {
            return ShortBuffer.allocate(0);
        }
        ShortBuffer shorts = ShortBuffer.allocate(out.length);
        for (byte b : out) {
            shorts.put(b);
        }
        shorts.flip();
        return shorts;
    }

    @Override
    public void recordAudio(ShortBuffer aacFrame) {
        if (!this.mRecording) {
            throw new IllegalStateException("Current session is not configured for audio-recording.");
        }
        int length = aacFrame.remaining();
        if (length == 0) {
            return;
        }
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) aacFrame.get();
        }
        writeAudio(data);
    }

    @Override
    public void recordVideo(ByteBuffer h264Frame, int timeStampMs) {
        if (!this.mRecording) {
            throw new IllegalStateException("Current session is not configured for video-recording.");
        }
        int length = h264Frame.remaining();
        if (length == 0) {
            return;
        }
        byte[] data = new byte[length];
        h264Frame.get(data);
        long ptsUs = timeStampMs * 1000L;
        if (ptsUs <= this.mLastVideoPtsUs) {
            ptsUs = this.mLastVideoPtsUs + 1;
        }
        this.mLastVideoPtsUs = ptsUs;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, length, ptsUs, containsIdr(data) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        this.mMuxer.writeSampleData(this.mVideoTrack, ByteBuffer.wrap(data), info);
    }

    @Override
    public void stop() {
        try {
            if (this.mVideo != null) {
                signalEndOfStream(this.mVideo, (this.mVideoFramesQueued * 1000000L) / Math.max(1, this.mConfig.videoFrameRate));
                drainVideoUntilEnd();
                if (this.mListener != null) {
                    byte[] frame;
                    while ((frame = this.mPendingVideo.poll()) != null) {
                        this.mListener.onFrameAvailable(ByteBuffer.wrap(frame));
                    }
                }
            }
            if (this.mAudio != null && this.mRecording) {
                signalEndOfStream(this.mAudio, (this.mAudioSamplesQueued * 1000000L) / this.mConfig.audioSampleRate);
                drainAudioUntilEnd();
                byte[] frame;
                while ((frame = this.mPendingAudio.poll()) != null) {
                    writeAudio(frame);
                }
            }
        } finally {
            release();
        }
    }

    @Override
    public void cancel() {
        stop();
        if (this.mOutputPath != null) {
            new File(this.mOutputPath).delete();
        }
    }

    @Override
    public void setEncoderVideoAsyncListener(LVMediaCoderICSEncoderProgressListener listener) {
        this.mListener = listener;
    }

    @Override
    public LVMediaCoderConfiguration getConfiguration() {
        return this.mConfig;
    }

    @Override
    public String getOutputFilePath() {
        return this.mOutputPath;
    }

    private MediaCodec createVideoEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(AVC, this.mConfig.videoWidth, this.mConfig.videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, this.mConfig.videoBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, this.mConfig.videoFrameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, this.mConfig.videoIFrameInterval);
        MediaCodec codec = MediaCodec.createEncoderByType(AVC);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch (RuntimeException e) {
            codec.release();
            throw e;
        }
        return codec;
    }

    private MediaCodec createAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(AAC, this.mConfig.audioSampleRate, this.mConfig.audioChannelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, this.mConfig.audioBitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
        MediaCodec codec = MediaCodec.createEncoderByType(AAC);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
        } catch (RuntimeException e) {
            codec.release();
            throw e;
        }
        return codec;
    }

    private byte[] probeVideoCsd() throws IOException {
        this.mVideo = createVideoEncoder();
        try {
            int size = this.mConfig.videoWidth * this.mConfig.videoHeight * 4;
            ByteBuffer black = ByteBuffer.allocate(size);
            for (int i = 0; i < 100 && sVideoCsd == null; i++) {
                int index = this.mVideo.dequeueInputBuffer(POLL_US);
                if (index >= 0) {
                    black.position(0);
                    Image image = this.mVideo.getInputImage(index);
                    if (image != null) {
                        fillImage(image, black, 4);
                    }
                    this.mVideo.queueInputBuffer(index, 0, this.mConfig.videoWidth * this.mConfig.videoHeight * 3 / 2, i * 33333L, 0);
                }
                drainVideo(POLL_US);
            }
        } finally {
            this.mVideo.stop();
            this.mVideo.release();
            this.mVideo = null;
            this.mPendingVideo.clear();
            this.mVideoFramesQueued = 0;
        }
        if (sVideoCsd == null) {
            throw new IOException("Encoder did not produce SPS/PPS.");
        }
        return sVideoCsd;
    }

    private void drainVideo(long timeoutUs) {
        while (true) {
            int index = this.mVideo.dequeueOutputBuffer(this.mInfo, timeoutUs);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rememberCsd(this.mVideo.getOutputFormat());
                continue;
            }
            if (index < 0) {
                return;
            }
            boolean end = (this.mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (this.mInfo.size > 0) {
                byte[] data = copyOut(this.mVideo.getOutputBuffer(index), this.mInfo);
                if ((this.mInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (sVideoCsd == null) {
                        sVideoCsd = data;
                    }
                } else {
                    this.mPendingVideo.add(data);
                }
            }
            this.mVideo.releaseOutputBuffer(index, false);
            if (end) {
                return;
            }
            timeoutUs = 0;
        }
    }

    private void drainVideoUntilEnd() {
        for (int i = 0; i < 200; i++) {
            int index = this.mVideo.dequeueOutputBuffer(this.mInfo, POLL_US);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rememberCsd(this.mVideo.getOutputFormat());
                continue;
            }
            if (index < 0) {
                continue;
            }
            boolean end = (this.mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (this.mInfo.size > 0 && (this.mInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                this.mPendingVideo.add(copyOut(this.mVideo.getOutputBuffer(index), this.mInfo));
            }
            this.mVideo.releaseOutputBuffer(index, false);
            if (end) {
                return;
            }
        }
    }

    private void drainAudio(long timeoutUs) {
        while (true) {
            int index = this.mAudio.dequeueOutputBuffer(this.mInfo, timeoutUs);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            if (index < 0) {
                return;
            }
            boolean end = (this.mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (this.mInfo.size > 0 && (this.mInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                this.mPendingAudio.add(copyOut(this.mAudio.getOutputBuffer(index), this.mInfo));
            }
            this.mAudio.releaseOutputBuffer(index, false);
            if (end) {
                return;
            }
            timeoutUs = 0;
        }
    }

    private void drainAudioUntilEnd() {
        for (int i = 0; i < 200; i++) {
            int index = this.mAudio.dequeueOutputBuffer(this.mInfo, POLL_US);
            if (index < 0) {
                continue;
            }
            boolean end = (this.mInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (this.mInfo.size > 0 && (this.mInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                this.mPendingAudio.add(copyOut(this.mAudio.getOutputBuffer(index), this.mInfo));
            }
            this.mAudio.releaseOutputBuffer(index, false);
            if (end) {
                return;
            }
        }
    }

    private static void signalEndOfStream(MediaCodec codec, long ptsUs) {
        for (int i = 0; i < 40; i++) {
            int index = codec.dequeueInputBuffer(POLL_US);
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                return;
            }
        }
    }

    private void writeAudio(byte[] data) {
        long ptsUs = (this.mAudioFramesWritten * 1024L * 1000000L) / this.mConfig.audioSampleRate;
        this.mAudioFramesWritten++;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, data.length, ptsUs, 0);
        this.mMuxer.writeSampleData(this.mAudioTrack, ByteBuffer.wrap(data), info);
    }

    private void release() {
        if (this.mVideo != null) {
            try {
                this.mVideo.stop();
            } catch (RuntimeException ignored) {
            }
            this.mVideo.release();
            this.mVideo = null;
        }
        if (this.mAudio != null) {
            try {
                this.mAudio.stop();
            } catch (RuntimeException ignored) {
            }
            this.mAudio.release();
            this.mAudio = null;
        }
        if (this.mMuxer != null) {
            try {
                if (this.mMuxerStarted) {
                    this.mMuxer.stop();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Muxer stop failed", e);
            }
            this.mMuxer.release();
            this.mMuxer = null;
        }
        this.mMuxerStarted = false;
        this.mRecording = false;
    }

    private static void rememberCsd(MediaFormat format) {
        ByteBuffer sps = format.getByteBuffer("csd-0");
        ByteBuffer pps = format.getByteBuffer("csd-1");
        if (sps == null || pps == null || sVideoCsd != null) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] a = new byte[sps.remaining()];
        sps.duplicate().get(a);
        byte[] b = new byte[pps.remaining()];
        pps.duplicate().get(b);
        out.write(a, 0, a.length);
        out.write(b, 0, b.length);
        sVideoCsd = out.toByteArray();
    }

    private static byte[] copyOut(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        byte[] data = new byte[info.size];
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        buffer.get(data);
        return data;
    }

    private MediaFormat videoTrackFormat(byte[] csd) {
        MediaFormat format = MediaFormat.createVideoFormat(AVC, this.mConfig.videoWidth, this.mConfig.videoHeight);
        byte[][] nals = splitSpsPps(csd);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(nals[0]));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(nals[1]));
        return format;
    }

    private MediaFormat audioTrackFormat() {
        int rateIndex = 4;
        for (int i = 0; i < AAC_RATES.length; i++) {
            if (AAC_RATES[i] == this.mConfig.audioSampleRate) {
                rateIndex = i;
                break;
            }
        }
        int asc = (2 << 11) | (rateIndex << 7) | (this.mConfig.audioChannelCount << 3);
        MediaFormat format = MediaFormat.createAudioFormat(AAC, this.mConfig.audioSampleRate, this.mConfig.audioChannelCount);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{(byte) (asc >> 8), (byte) asc}));
        format.setInteger(MediaFormat.KEY_BIT_RATE, this.mConfig.audioBitRate);
        return format;
    }

    static byte[][] splitSpsPps(byte[] csd) {
        byte[] sps = null;
        byte[] pps = null;
        int start = nextStartCode(csd, 0);
        while (start >= 0) {
            int payload = start + (csd[start + 2] == 1 ? 3 : 4);
            int next = nextStartCode(csd, payload);
            int end = next < 0 ? csd.length : next;
            byte[] nal = new byte[4 + end - payload];
            nal[3] = 1;
            System.arraycopy(csd, payload, nal, 4, end - payload);
            int type = csd[payload] & 0x1F;
            if (type == 7) {
                sps = nal;
            } else if (type == 8) {
                pps = nal;
            }
            start = next;
        }
        if (sps == null || pps == null) {
            throw new IllegalStateException("SPS/PPS missing from encoder output.");
        }
        return new byte[][]{sps, pps};
    }

    public static boolean containsIdr(byte[] data) {
        int start = nextStartCode(data, 0);
        while (start >= 0) {
            int payload = start + (data[start + 2] == 1 ? 3 : 4);
            if (payload < data.length && (data[payload] & 0x1F) == 5) {
                return true;
            }
            start = nextStartCode(data, payload);
        }
        return false;
    }

    private static int nextStartCode(byte[] data, int from) {
        for (int i = from; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void fillImage(Image image, ByteBuffer rgb, int bpp) {
        int width = this.mConfig.videoWidth;
        int height = this.mConfig.videoHeight;
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yPlane = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        ByteBuffer uPlane = planes[1].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        ByteBuffer vPlane = planes[2].getBuffer();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int base = rgb.position();
        if (this.mRow == null || this.mRow.length < width) {
            this.mRow = new byte[width];
        }
        for (int y = 0; y < height; y++) {
            int src = base + y * width * bpp;
            for (int x = 0; x < width; x++) {
                int r = rgb.get(src) & 0xFF;
                int g = rgb.get(src + 1) & 0xFF;
                int b = rgb.get(src + 2) & 0xFF;
                this.mRow[x] = (byte) ((((66 * r) + (129 * g) + (25 * b) + 128) >> 8) + 16);
                src += bpp;
            }
            if (yPixelStride == 1) {
                yPlane.position(y * yRowStride);
                yPlane.put(this.mRow, 0, width);
            } else {
                for (int x = 0; x < width; x++) {
                    yPlane.put(y * yRowStride + x * yPixelStride, this.mRow[x]);
                }
            }
        }
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                int src = base + ((y * 2) * width + (x * 2)) * bpp;
                int r = rgb.get(src) & 0xFF;
                int g = rgb.get(src + 1) & 0xFF;
                int b = rgb.get(src + 2) & 0xFF;
                uPlane.put(y * uRowStride + x * uPixelStride, (byte) ((((-38 * r) - (74 * g) + (112 * b) + 128) >> 8) + 128));
                vPlane.put(y * vRowStride + x * vPixelStride, (byte) ((((112 * r) - (94 * g) - (18 * b) + 128) >> 8) + 128));
            }
        }
    }

    private void fillNv12(ByteBuffer out, ByteBuffer rgb, int bpp) {
        int width = this.mConfig.videoWidth;
        int height = this.mConfig.videoHeight;
        int base = rgb.position();
        out.clear();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int src = base + (y * width + x) * bpp;
                int r = rgb.get(src) & 0xFF;
                int g = rgb.get(src + 1) & 0xFF;
                int b = rgb.get(src + 2) & 0xFF;
                out.put((byte) ((((66 * r) + (129 * g) + (25 * b) + 128) >> 8) + 16));
            }
        }
        for (int y = 0; y < height / 2; y++) {
            for (int x = 0; x < width / 2; x++) {
                int src = base + ((y * 2) * width + (x * 2)) * bpp;
                int r = rgb.get(src) & 0xFF;
                int g = rgb.get(src + 1) & 0xFF;
                int b = rgb.get(src + 2) & 0xFF;
                out.put((byte) ((((-38 * r) - (74 * g) + (112 * b) + 128) >> 8) + 128));
                out.put((byte) ((((112 * r) - (94 * g) - (18 * b) + 128) >> 8) + 128));
            }
        }
    }
}

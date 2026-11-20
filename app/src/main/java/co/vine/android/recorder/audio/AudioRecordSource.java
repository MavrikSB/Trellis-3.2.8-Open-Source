package co.vine.android.recorder.audio;

import android.media.AudioRecord;
import android.os.Process;
import android.support.annotation.NonNull;
import co.vine.android.recorder.RecordConfigUtils;
import co.vine.android.service.ResourceService;
import co.vine.android.util.CrashUtil;
import com.edisonwang.android.slog.SLog;

public class AudioRecordSource implements AudioSource {
    private final AudioArray mAudioDataBuffer;
    private AudioRecord mAudioRecord;
    private Thread mAudioThread;
    private final String mCurrentTarget;
    private volatile boolean mIsInitialized;
    private boolean mIsStopped;

    public AudioRecordSource(String targetTag, @NonNull AudioArray buffer) {
        this.mCurrentTarget = targetTag;
        this.mAudioDataBuffer = buffer;
    }

    public class AudioGatherer implements Runnable {
        private final AudioReceiver mReceiver;

        public AudioGatherer(AudioReceiver receiver) {
            this.mReceiver = receiver;
        }

        @Override
        public void run() {
            Process.setThreadPriority(-19);
            try {
                AudioRecordSource.this.mAudioRecord = new AudioRecord(1, 44100, 16, 2, RecordConfigUtils.AUDIO_RUNNABLE_SAMPLE_SIZE);
            } catch (Throwable e) {
                SLog.m459e("Unable to initialize AudioRecord; audio capture is disabled for this session.", e);
                ResourceService.releaseAudioBuffer(AudioArrays.typeOf(AudioRecordSource.this.mAudioDataBuffer));
                this.mReceiver.onAudioSourceStopped();
                return;
            }
            SLog.m446d("mAudioRecord.startRecording()");
            SLog.m447d("Buffer size {}.", Integer.valueOf(AudioRecordSource.this.mAudioDataBuffer.getLength()));
            AudioRecordSource.this.mIsInitialized = false;
            long initializationDeadline = System.currentTimeMillis() + 2000L;
            while (AudioRecordSource.this.mAudioRecord.getState() == AudioRecord.STATE_UNINITIALIZED
                && !AudioRecordSource.this.mIsStopped
                && System.currentTimeMillis() < initializationDeadline) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (AudioRecordSource.this.mIsStopped
                || AudioRecordSource.this.mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                SLog.m455e("AudioRecord could not be initialized; ending audio capture safely.");
                ResourceService.releaseAudioBuffer(AudioArrays.typeOf(AudioRecordSource.this.mAudioDataBuffer));
                AudioRecordSource.this.mAudioRecord.release();
                AudioRecordSource.this.mIsInitialized = false;
                this.mReceiver.onAudioSourceStopped();
                return;
            }
            AudioRecordSource.this.mIsInitialized = true;
            CrashUtil.log("mAudioRecord.isInitialized: {}.", AudioRecordSource.this.mCurrentTarget);
            AudioRecordSource.this.mAudioRecord.startRecording();
            while (this.mReceiver.needsMoreData() && !AudioRecordSource.this.mIsStopped) {
                this.mReceiver.beforeDataCollection();
                int bufferReadResult = AudioRecordSource.this.mAudioDataBuffer.readFrom(AudioRecordSource.this.mAudioRecord, AudioRecordSource.this.mAudioDataBuffer.getLength());
                if (bufferReadResult == -3) {
                    SLog.m446d("Audio Record in invalid state: (valid = 3) " + AudioRecordSource.this.mAudioRecord.getState());
                    break;
                } else if (bufferReadResult > 0 && !this.mReceiver.onAudioDataReceived(bufferReadResult, AudioRecordSource.this.mAudioDataBuffer)) {
                    break;
                }
            }
            ResourceService.releaseAudioBuffer(AudioArrays.typeOf(AudioRecordSource.this.mAudioDataBuffer));
            SLog.m446d("AudioThread Finished, release mAudioRecord");
            AudioRecordSource.this.mAudioRecord.stop();
            AudioRecordSource.this.mAudioRecord.release();
            AudioRecordSource.this.mIsInitialized = false;
            CrashUtil.log("mAudioRecord released: {}.", AudioRecordSource.this.mCurrentTarget);
            this.mReceiver.onAudioSourceStopped();
        }
    }

    @Override
    public void start(AudioReceiver receiver) {
        this.mIsStopped = false;
        this.mAudioThread = new Thread(new AudioGatherer(receiver), "AudioRunnable");
        this.mAudioThread.start();
    }

    @Override
    public void stop() {
        CrashUtil.log("Stop audio record source: {}", Boolean.valueOf(this.mIsInitialized));
        this.mIsStopped = true;
        try {
            if (this.mAudioRecord != null && this.mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                this.mAudioRecord.stop();
            }
        } catch (IllegalStateException ignored) {
        }
        if (this.mAudioThread != null) {
            try {
                this.mAudioThread.join();
            } catch (InterruptedException e) {
                SLog.m455e("Error join audio thread.");
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return this.mIsInitialized;
    }
}

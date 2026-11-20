package co.vine.android.embed.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import co.vine.android.util.PriorityHandlerThread;
import java.io.IOException;

class VinePlayerInternal {
    private static final int IDLE_INTERVAL_MS = 300;
    private static final int MSG_DO_SOME_WORK = 1;
    private static final int MSG_PREPARE = 2;
    private static final int MSG_RESET = 5;
    private static final int RELEASE_WAIT = 10;
    private static final int RELEASE_WARN = 1000;
    private static final int RENDERING_INTERVAL_MS = 10;
    private static final String TAG = "VinePlayerInternal";
    private AudioRenderer mAudioRenderer;
    private final MediaClock mClock;
    private final Context mContext;
    private boolean mFirstFrameMessageSent;
    private Handler mHandler;
    private boolean mHasAudioTrack;
    private Boolean mLastPlayWhenReady;
    private final EventListener mListener;
    private final Handler mListenerHandler;
    private boolean mPlayWhenReady;
    private VineSampleSource mSampleSource;
    private PlayerState mState;
    private Surface mSurface;
    private Uri mUri;
    private VideoRenderer mVideoRenderer;
    private float mLastVolume = 1.0f;
    private float mVolume = 1.0f;
    private long mDurationMs = -1;
    private PriorityHandlerThread mPlaybackThread = new PriorityHandlerThread(getClass().getSimpleName() + ":Handler", -16);

    protected interface EventListener {
        void onError(PlayerState playerState, int i, Throwable th);

        void onFirstFrameRendered();

        void onLoopCompleted();

        void onPastError(Exception exc);

        void onPrepared();

        void onVideoSizeChanged(int i, int i2);
    }

    public VinePlayerInternal(Context context, EventListener listener) {
        this.mPlaybackThread.start();
        this.mHandler = new Handler(this.mPlaybackThread.getLooper(), new PlayerMessageHandler(this, null));
        this.mListenerHandler = new Handler(Looper.getMainLooper());
        this.mState = PlayerState.IDLE;
        this.mContext = context;
        this.mClock = new MediaClock();
        this.mListener = listener;
    }

    public synchronized boolean playWhenReady() {
        return this.mPlayWhenReady;
    }

    public synchronized void setPlayWhenReady(boolean playWhenReady) {
        this.mPlayWhenReady = playWhenReady;
    }

    private void scheduleNextOperation(int operationType, long thisOperationStartTimeMs, long intervalMs) {
        long nextOperationStartTimeMs = thisOperationStartTimeMs + intervalMs;
        long nextOperationDelayMs = nextOperationStartTimeMs - SystemClock.elapsedRealtime();
        if (nextOperationDelayMs <= 0) {
            this.mHandler.sendEmptyMessage(operationType);
        } else {
            this.mHandler.sendEmptyMessageDelayed(operationType, nextOperationDelayMs);
        }
    }

    private void resetInternal() {
        this.mPlayWhenReady = false;
        this.mUri = null;
        this.mSurface = null;
        this.mVolume = 1.0f;
        this.mLastPlayWhenReady = null;
        this.mLastVolume = 1.0f;
        this.mFirstFrameMessageSent = false;
        if (this.mSampleSource != null) {
            this.mSampleSource.release();
            Exception error = this.mSampleSource.getError();
            if (error != null) {
                this.mListenerHandler.post(new RunnableC03771(error));
            }
            this.mSampleSource = null;
        }
        if (this.mVideoRenderer != null) {
            this.mVideoRenderer.release();
            this.mVideoRenderer = null;
        }
        if (this.mAudioRenderer != null) {
            this.mAudioRenderer.release();
            this.mAudioRenderer = null;
        }
        this.mClock.reset();
        setState(PlayerState.IDLE);
        this.mHandler.removeMessages(2);
        this.mHandler.removeMessages(1);
        Log.d(TAG, "Player reseted.");
    }

    class RunnableC03771 implements Runnable {
        final  Exception val$error;

        RunnableC03771(Exception exc) {
            this.val$error = exc;
        }

        @Override
        public void run() {
            VinePlayerInternal.this.mListener.onPastError(this.val$error);
        }
    }

    public void reset() {
        if (getState() != PlayerState.IDLE) {
            this.mHandler.sendEmptyMessage(5);
            int s = 0;
            while (getState() != PlayerState.IDLE) {
                s += 10;
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (s >= 1000) {
                    Log.e(TAG, "Taking too long to release, something is wrong.");
                }
            }
        }
    }

    public void release() {
        reset();
        if (this.mPlaybackThread.isAlive()) {
            this.mPlaybackThread.quit();
        }
    }

    public synchronized void prepareAsync() {
        if (this.mState != PlayerState.IDLE) {
            throw new IllegalStateException("Player is not idle.");
        }
        if (this.mUri == null) {
            throw new IllegalStateException("Uri is not set.");
        }
        if (!this.mPlaybackThread.isAlive()) {
            throw new IllegalStateException("The player is already released.");
        }
        this.mHandler.sendEmptyMessage(2);
    }

    public synchronized void setUri(Uri uri) {
        this.mUri = uri;
    }

    public synchronized Uri getUri() {
        return this.mUri;
    }

    private void prepareInternal() throws AudioTrack.InitializationException, IOException, AudioTrack.WriteException {
        Log.i(TAG, "Preparing...");
        if (this.mState != PlayerState.IDLE) {
            throw new IllegalStateException("You cannot prepare when state is " + this.mState);
        }
        setState(PlayerState.PREPARING);
        Uri uri = getUri();
        if (uri == null) {
            throw new IllegalArgumentException("You cannot prepare before setting a Uri");
        }
        Surface surface = this.mSurface;
        if (surface == null) {
            throw new IllegalStateException("Surface is not set.");
        }
        this.mSampleSource = new VineSampleSource(uri);
        this.mVideoRenderer = new VideoRenderer();
        this.mAudioRenderer = new AudioRenderer();
        this.mSampleSource.prepare(this.mContext);
        this.mAudioRenderer.prepare(this.mSampleSource);
        this.mVideoRenderer.prepare(this.mSampleSource, surface);
        if (this.mSampleSource.hasAudioTrack()) {
            this.mDurationMs = this.mAudioRenderer.getDurationMs();
            this.mHasAudioTrack = true;
        } else {
            this.mDurationMs = this.mVideoRenderer.getDurationMs();
            this.mHasAudioTrack = false;
        }
        setState(PlayerState.PLAYABLE);
        this.mListenerHandler.post(new RunnableC03782());
        doSomeWorkInternal();
    }

    class RunnableC03782 implements Runnable {
        RunnableC03782() {
        }

        @Override
        public void run() {
            VinePlayerInternal.this.mListener.onPrepared();
        }
    }

    public void setSurface(Surface surface) {
        this.mSurface = surface;
        if (this.mSurface == null) {
            release();
        }
    }

    public int getCurrentPositionMs() {
        return (int) (this.mClock.positionUs() / 1000);
    }

    private synchronized void setState(PlayerState state) {
        this.mState = state;
    }

    private class PlayerMessageHandler implements Handler.Callback {
        private PlayerMessageHandler() {
        }

        PlayerMessageHandler(VinePlayerInternal x0, RunnableC03771 x1) {
            this();
        }

        @Override
        public boolean handleMessage(Message msg) {
            boolean z = true;
            try {
                switch (msg.what) {
                    case 1:
                        VinePlayerInternal.this.doSomeWorkInternal();
                        break;
                    case 2:
                        VinePlayerInternal.this.prepareInternal();
                        break;
                    case 3:
                    case 4:
                    default:
                        z = false;
                        break;
                    case 5:
                        VinePlayerInternal.this.resetInternal();
                        break;
                }
            } catch (Throwable e) {
                VinePlayerInternal.this.mListenerHandler.post(new RunnableC03821(msg, e));
            }
            return z;
        }

        class RunnableC03821 implements Runnable {
            final  Throwable val$e;
            final  Message val$msg;

            RunnableC03821(Message message, Throwable th) {
                this.val$msg = message;
                this.val$e = th;
            }

            @Override
            public void run() {
                VinePlayerInternal.this.mListener.onError(VinePlayerInternal.this.mState, this.val$msg.what, this.val$e);
            }
        }
    }

    public synchronized PlayerState getState() {
        return this.mState;
    }

    public void setVolume(float gain) {
        this.mVolume = gain;
    }

    public boolean isPlaying() {
        return getState() == PlayerState.PLAYABLE && playWhenReady();
    }

    private void doSomeWorkInternal() throws AudioTrack.InitializationException, IOException, AudioTrack.WriteException {
        if (this.mState != PlayerState.IDLE) {
            boolean playWhenReady = playWhenReady();
            boolean justResumed = (this.mLastPlayWhenReady == null || !playWhenReady || this.mLastPlayWhenReady.booleanValue()) ? false : true;
            this.mLastPlayWhenReady = Boolean.valueOf(playWhenReady);
            this.mClock.operationStart();
            if (this.mState == PlayerState.PLAYABLE && playWhenReady) {
                doAudioWork(justResumed);
                doVideoWork(justResumed);
            }
            this.mHandler.removeMessages(1);
            if (this.mState == PlayerState.PLAYABLE && playWhenReady) {
                scheduleNextOperation(1, this.mClock.getOperationStartMs(), 10L);
            } else {
                scheduleNextOperation(1, this.mClock.getOperationStartMs(), 300L);
            }
            this.mClock.operationEnd();
            return;
        }
        Log.i(TAG, "Do nothing at state " + this.mState);
    }

    public boolean isReleased() {
        return !this.mPlaybackThread.isAlive();
    }

    public long getDurationMs() {
        return this.mDurationMs;
    }

    private void doVideoWork(boolean justResumed) throws IOException {
        int width = this.mVideoRenderer.getWidth();
        int height = this.mVideoRenderer.getHeight();
        this.mVideoRenderer.doSomeWork(this.mClock, this.mHasAudioTrack, justResumed);
        int newWidth = this.mVideoRenderer.getWidth();
        int newHeight = this.mVideoRenderer.getHeight();
        if (width != newWidth || height != newHeight) {
            this.mListenerHandler.post(new RunnableC03793(newWidth, newHeight));
        }
        if (!this.mFirstFrameMessageSent && this.mVideoRenderer.getFramesRendered() >= 1) {
            this.mFirstFrameMessageSent = true;
            this.mListenerHandler.post(new RunnableC03804());
        }
    }

    class RunnableC03793 implements Runnable {
        final  int val$newHeight;
        final  int val$newWidth;

        RunnableC03793(int i, int i2) {
            this.val$newWidth = i;
            this.val$newHeight = i2;
        }

        @Override
        public void run() {
            VinePlayerInternal.this.mListener.onVideoSizeChanged(this.val$newWidth, this.val$newHeight);
        }
    }

    class RunnableC03804 implements Runnable {
        RunnableC03804() {
        }

        @Override
        public void run() {
            VinePlayerInternal.this.mListener.onFirstFrameRendered();
        }
    }

    private void doAudioWork(boolean justResumed) throws AudioTrack.InitializationException, AudioTrack.WriteException {
        if (this.mVolume != this.mLastVolume) {
            this.mLastVolume = this.mVolume;
            this.mAudioRenderer.setVolume(this.mLastVolume);
        }
        if (this.mAudioRenderer.doSomeWork(this.mClock, justResumed)) {
            this.mListenerHandler.post(new RunnableC03815());
        }
    }

    class RunnableC03815 implements Runnable {
        RunnableC03815() {
        }

        @Override
        public void run() {
            VinePlayerInternal.this.mListener.onLoopCompleted();
        }
    }
}

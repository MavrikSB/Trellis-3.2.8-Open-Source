package co.vine.android.player;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.MediaController;
import co.vine.android.cache.CacheKey;
import co.vine.android.provider.VineVideoProvider;
import co.vine.android.util.CrashUtil;
import com.edisonwang.android.slog.SLog;
import java.io.IOException;

public class OldSdkVideoView extends SurfaceView implements MediaController.MediaPlayerControl, VideoViewInterface {
    private static final int STATE_ERROR = -1;
    private static final int STATE_IDLE = 0;
    private static final int STATE_PAUSED = 4;
    private static final int STATE_PLAYBACK_COMPLETED = 5;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_PREPARED = 2;
    private static final int STATE_PREPARING = 1;
    private int mAudioSession;
    private boolean mAutoPlay;
    private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener;
    private CacheKey mCacheKey;
    private boolean mCanPause;
    private boolean mCanSeekBack;
    private boolean mCanSeekForward;
    private MediaPlayer.OnCompletionListener mCompletionListener;
    private int mCurrentBufferPercentage;
    private int mCurrentState;
    private VideoViewInterface.OnPreparedListener mCustomOnPreParedListener;
    private MediaPlayer.OnErrorListener mErrorListener;
    private MediaPlayer.OnInfoListener mInfoListener;
    private boolean mLooping;
    private MediaController mMediaController;
    private MediaPlayer mMediaPlayer;
    private boolean mMuted;
    private VideoViewInterface.OnCompletionListener mOnCompletionListener;
    private VideoViewInterface.OnErrorListener mOnErrorListener;
    private MediaPlayer.OnInfoListener mOnInfoListener;
    private final MediaPlayer.OnPreparedListener mOnPreparedListener;
    MediaPlayer.OnPreparedListener mPreparedListener;
    private boolean mReportVideoError;
    SurfaceHolder.Callback mSHCallback;
    private int mSeekWhenPrepared;
    MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener;
    private int mSurfaceHeight;
    private SurfaceHolder mSurfaceHolder;
    private int mSurfaceWidth;
    private int mTargetState;
    private Uri mUri;
    private int mVideoHeight;
    private int mVideoWidth;
    private boolean mWasMuted;

    class C03911 implements MediaPlayer.OnPreparedListener {
        C03911() {
        }

        @Override
        public void onPrepared(MediaPlayer mp) {
            SLog.m446d("Videoview prepared.");
            if (OldSdkVideoView.this.mAutoPlay) {
                mp.start();
            }
            mp.setLooping(OldSdkVideoView.this.mLooping);
            if (OldSdkVideoView.this.mCustomOnPreParedListener != null) {
                OldSdkVideoView.this.mCustomOnPreParedListener.onPrepared(OldSdkVideoView.this);
            }
        }
    }

    public OldSdkVideoView(Context context) {
        super(context);
        this.mOnPreparedListener = new C03911();
        this.mCurrentState = 0;
        this.mTargetState = 0;
        this.mSurfaceHolder = null;
        this.mMediaPlayer = null;
        this.mMuted = false;
        this.mWasMuted = false;
        this.mSizeChangedListener = new C03922();
        this.mPreparedListener = new C03933();
        this.mCompletionListener = new C03944();
        this.mInfoListener = new C03955();
        this.mErrorListener = new C03966();
        this.mBufferingUpdateListener = new C03977();
        this.mSHCallback = new SurfaceHolderCallbackC03988();
        initVideoView();
    }

    public OldSdkVideoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
        initVideoView();
    }

    public OldSdkVideoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.mOnPreparedListener = new C03911();
        this.mCurrentState = 0;
        this.mTargetState = 0;
        this.mSurfaceHolder = null;
        this.mMediaPlayer = null;
        this.mMuted = false;
        this.mWasMuted = false;
        this.mSizeChangedListener = new C03922();
        this.mPreparedListener = new C03933();
        this.mCompletionListener = new C03944();
        this.mInfoListener = new C03955();
        this.mErrorListener = new C03966();
        this.mBufferingUpdateListener = new C03977();
        this.mSHCallback = new SurfaceHolderCallbackC03988();
        initVideoView();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(this.mVideoWidth, widthMeasureSpec);
        int height = getDefaultSize(this.mVideoHeight, heightMeasureSpec);
        if (this.mVideoWidth > 0 && this.mVideoHeight > 0) {
            int widthSpecMode = View.MeasureSpec.getMode(widthMeasureSpec);
            int widthSpecSize = View.MeasureSpec.getSize(widthMeasureSpec);
            int heightSpecMode = View.MeasureSpec.getMode(heightMeasureSpec);
            int heightSpecSize = View.MeasureSpec.getSize(heightMeasureSpec);
            if (widthSpecMode == 1073741824 && heightSpecMode == 1073741824) {
                width = widthSpecSize;
                height = heightSpecSize;
                if (this.mVideoWidth * height < this.mVideoHeight * width) {
                    width = (this.mVideoWidth * height) / this.mVideoHeight;
                } else if (this.mVideoWidth * height > this.mVideoHeight * width) {
                    height = (this.mVideoHeight * width) / this.mVideoWidth;
                }
            } else if (widthSpecMode == 1073741824) {
                width = widthSpecSize;
                height = (this.mVideoHeight * width) / this.mVideoWidth;
                if (heightSpecMode == Integer.MIN_VALUE && height > heightSpecSize) {
                    height = heightSpecSize;
                }
            } else if (heightSpecMode == 1073741824) {
                height = heightSpecSize;
                width = (this.mVideoWidth * height) / this.mVideoHeight;
                if (widthSpecMode == Integer.MIN_VALUE && width > widthSpecSize) {
                    width = widthSpecSize;
                }
            } else {
                width = this.mVideoWidth;
                height = this.mVideoHeight;
                if (heightSpecMode == Integer.MIN_VALUE && height > heightSpecSize) {
                    height = heightSpecSize;
                    width = (this.mVideoWidth * height) / this.mVideoHeight;
                }
                if (widthSpecMode == Integer.MIN_VALUE && width > widthSpecSize) {
                    width = widthSpecSize;
                    height = (this.mVideoHeight * width) / this.mVideoWidth;
                }
            }
        }
        setMeasuredDimension(width, height);
    }

    public int resolveAdjustedSize(int desiredSize, int measureSpec) {
        return getDefaultSize(desiredSize, measureSpec);
    }

    private void initVideoView() {
        this.mVideoWidth = 0;
        this.mVideoHeight = 0;
        getHolder().addCallback(this.mSHCallback);
        getHolder().setType(3);
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        this.mCurrentState = 0;
        this.mTargetState = 0;
    }

    @Override
    public void setVideoPath(String path) {
        setVideoURI(Uri.parse(VineVideoProvider.CONTENT_AUTHORITY + path));
    }

    @Override
    public void setVideoPathDirect(String path) {
        setVideoURI(Uri.parse(path));
    }

    public void setVideoURI(Uri uri) {
        this.mUri = uri;
        this.mSeekWhenPrepared = 0;
        openVideo();
        requestLayout();
        invalidate();
    }

    public void removeVideoUri() {
        this.mUri = null;
        this.mSeekWhenPrepared = 0;
    }

    @Override
    public void stopPlayback() {
        if (this.mMediaPlayer != null) {
            SLog.m446d("Playback stopped.");
            try {
                this.mMediaPlayer.stop();
            } catch (IllegalStateException e) {
            }
            try {
                this.mMediaPlayer.reset();
            } catch (IllegalStateException e2) {
            }
            try {
                this.mMediaPlayer.release();
            } catch (IllegalStateException e3) {
            }
            this.mMediaPlayer = null;
            this.mCurrentState = 0;
            this.mTargetState = 0;
        }
    }

    private void openVideo() {
        if (this.mUri != null && this.mSurfaceHolder != null) {
            Intent i = new Intent("com.android.music.musicservicecommand");
            i.putExtra("command", "pause");
            getContext().sendBroadcast(i);
            release(false);
            try {
                this.mMediaPlayer = new MediaPlayer();
                Context context = getContext();
                if (this.mAudioSession != 0) {
                    this.mMediaPlayer.setAudioSessionId(this.mAudioSession);
                } else {
                    this.mAudioSession = this.mMediaPlayer.getAudioSessionId();
                }
                this.mMediaPlayer.setOnPreparedListener(this.mPreparedListener);
                this.mMediaPlayer.setOnVideoSizeChangedListener(this.mSizeChangedListener);
                this.mMediaPlayer.setOnCompletionListener(this.mCompletionListener);
                this.mMediaPlayer.setOnErrorListener(this.mErrorListener);
                this.mMediaPlayer.setOnInfoListener(this.mInfoListener);
                this.mMediaPlayer.setOnBufferingUpdateListener(this.mBufferingUpdateListener);
                this.mCurrentBufferPercentage = 0;
                this.mMediaPlayer.setDataSource(context, this.mUri);
                this.mMediaPlayer.setDisplay(this.mSurfaceHolder);
                this.mMediaPlayer.setAudioStreamType(3);
                this.mMediaPlayer.setScreenOnWhilePlaying(true);
                this.mMediaPlayer.prepareAsync();
                if (this.mMuted) {
                    this.mMediaPlayer.setVolume(0.0f, 0.0f);
                }
                this.mCurrentState = 1;
                attachMediaController();
            } catch (IOException e) {
                if (this.mReportVideoError) {
                    CrashUtil.logException(e, "Unable to open content {}, {}.", this.mUri);
                }
                this.mCurrentState = -1;
                this.mTargetState = -1;
                this.mErrorListener.onError(this.mMediaPlayer, 1, 0);
            } catch (IllegalArgumentException e2) {
                if (this.mReportVideoError) {
                    CrashUtil.logException(e2, "Unable to open content {}, {}.", this.mUri);
                }
                this.mCurrentState = -1;
                this.mTargetState = -1;
                this.mErrorListener.onError(this.mMediaPlayer, 1, 0);
            } catch (IllegalStateException ex) {
                this.mCurrentState = -1;
                this.mTargetState = -1;
                CrashUtil.logException(ex, "IllegalState error {}, {}.", this.mUri);
            }
        }
    }

    public void setMediaController(MediaController controller) {
        if (this.mMediaController != null) {
            this.mMediaController.hide();
        }
        this.mMediaController = controller;
        attachMediaController();
    }

    private void attachMediaController() {
        if (this.mMediaPlayer != null && this.mMediaController != null) {
            this.mMediaController.setMediaPlayer(this);
            View anchorView = getParent() instanceof View ? (View) getParent() : this;
            this.mMediaController.setAnchorView(anchorView);
            this.mMediaController.setEnabled(isInPlaybackState());
        }
    }

    class C03922 implements MediaPlayer.OnVideoSizeChangedListener {
        C03922() {
        }

        @Override
        public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
            OldSdkVideoView.this.mVideoWidth = mp.getVideoWidth();
            OldSdkVideoView.this.mVideoHeight = mp.getVideoHeight();
            if (OldSdkVideoView.this.mVideoWidth != 0 && OldSdkVideoView.this.mVideoHeight != 0) {
                OldSdkVideoView.this.getHolder().setFixedSize(OldSdkVideoView.this.mVideoWidth, OldSdkVideoView.this.mVideoHeight);
                OldSdkVideoView.this.requestLayout();
            }
        }
    }

    class C03933 implements MediaPlayer.OnPreparedListener {
        C03933() {
        }

        @Override
        public void onPrepared(MediaPlayer mp) {
            OldSdkVideoView.this.mCurrentState = 2;
            OldSdkVideoView.this.mCanPause = (OldSdkVideoView.this.mCanSeekBack = (OldSdkVideoView.this.mCanSeekForward = true));
            if (OldSdkVideoView.this.mOnPreparedListener != null) {
                OldSdkVideoView.this.mOnPreparedListener.onPrepared(OldSdkVideoView.this.mMediaPlayer);
            }
            if (OldSdkVideoView.this.mMediaController != null) {
                OldSdkVideoView.this.mMediaController.setEnabled(true);
            }
            OldSdkVideoView.this.mVideoWidth = mp.getVideoWidth();
            OldSdkVideoView.this.mVideoHeight = mp.getVideoHeight();
            int seekToPosition = OldSdkVideoView.this.mSeekWhenPrepared;
            if (seekToPosition != 0) {
                OldSdkVideoView.this.seekTo(seekToPosition);
            }
            if (OldSdkVideoView.this.mVideoWidth != 0 && OldSdkVideoView.this.mVideoHeight != 0) {
                OldSdkVideoView.this.getHolder().setFixedSize(OldSdkVideoView.this.mVideoWidth, OldSdkVideoView.this.mVideoHeight);
                if (OldSdkVideoView.this.mSurfaceWidth == OldSdkVideoView.this.mVideoWidth && OldSdkVideoView.this.mSurfaceHeight == OldSdkVideoView.this.mVideoHeight) {
                    if (OldSdkVideoView.this.mTargetState == 3) {
                        OldSdkVideoView.this.start();
                        if (OldSdkVideoView.this.mMediaController != null) {
                            OldSdkVideoView.this.mMediaController.show();
                            return;
                        }
                        return;
                    }
                    if (!OldSdkVideoView.this.isPlaying()) {
                        if ((seekToPosition != 0 || OldSdkVideoView.this.getCurrentPosition() > 0) && OldSdkVideoView.this.mMediaController != null) {
                            OldSdkVideoView.this.mMediaController.show(0);
                            return;
                        }
                        return;
                    }
                    return;
                }
                return;
            }
            if (OldSdkVideoView.this.mTargetState == 3) {
                OldSdkVideoView.this.start();
            }
        }
    }

    class C03944 implements MediaPlayer.OnCompletionListener {
        C03944() {
        }

        @Override
        public void onCompletion(MediaPlayer mp) {
            OldSdkVideoView.this.mCurrentState = 5;
            OldSdkVideoView.this.mTargetState = 5;
            if (OldSdkVideoView.this.mMediaController != null) {
                OldSdkVideoView.this.mMediaController.hide();
            }
            if (OldSdkVideoView.this.mOnCompletionListener != null) {
                OldSdkVideoView.this.mOnCompletionListener.onCompletion(OldSdkVideoView.this);
            }
        }
    }

    class C03955 implements MediaPlayer.OnInfoListener {
        C03955() {
        }

        @Override
        public boolean onInfo(MediaPlayer mp, int arg1, int arg2) {
            if (OldSdkVideoView.this.mOnInfoListener != null) {
                OldSdkVideoView.this.mOnInfoListener.onInfo(mp, arg1, arg2);
                return true;
            }
            return true;
        }
    }

    class C03966 implements MediaPlayer.OnErrorListener {
        C03966() {
        }

        @Override
        public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
            SLog.m446d("Error: " + framework_err + "," + impl_err);
            OldSdkVideoView.this.mCurrentState = -1;
            OldSdkVideoView.this.mTargetState = -1;
            if (OldSdkVideoView.this.mMediaController != null) {
                OldSdkVideoView.this.mMediaController.hide();
            }
            if (OldSdkVideoView.this.mOnErrorListener == null || !OldSdkVideoView.this.mOnErrorListener.onError(OldSdkVideoView.this, framework_err, impl_err)) {
                SLog.m455e("Cannot play this video.");
            }
            return true;
        }
    }

    class C03977 implements MediaPlayer.OnBufferingUpdateListener {
        C03977() {
        }

        @Override
        public void onBufferingUpdate(MediaPlayer mp, int percent) {
            OldSdkVideoView.this.mCurrentBufferPercentage = percent;
        }
    }

    @Override
    public void setOnCompletionListener(VideoViewInterface.OnCompletionListener l) {
        this.mOnCompletionListener = l;
    }

    @Override
    public void setOnErrorListener(VideoViewInterface.OnErrorListener l) {
        this.mOnErrorListener = l;
    }

    public void setOnInfoListener(MediaPlayer.OnInfoListener l) {
        this.mOnInfoListener = l;
    }

    class SurfaceHolderCallbackC03988 implements SurfaceHolder.Callback {
        SurfaceHolderCallbackC03988() {
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            OldSdkVideoView.this.mSurfaceWidth = w;
            OldSdkVideoView.this.mSurfaceHeight = h;
            boolean isValidState = OldSdkVideoView.this.mTargetState == 3;
            boolean hasValidSize = OldSdkVideoView.this.mVideoWidth == w && OldSdkVideoView.this.mVideoHeight == h;
            if (OldSdkVideoView.this.mMediaPlayer != null && isValidState && hasValidSize) {
                if (OldSdkVideoView.this.mSeekWhenPrepared != 0) {
                    OldSdkVideoView.this.seekTo(OldSdkVideoView.this.mSeekWhenPrepared);
                }
                OldSdkVideoView.this.start();
            }
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            OldSdkVideoView.this.mSurfaceHolder = holder;
            OldSdkVideoView.this.openVideo();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            OldSdkVideoView.this.mSurfaceHolder = null;
            if (OldSdkVideoView.this.mMediaController != null) {
                OldSdkVideoView.this.mMediaController.hide();
            }
            OldSdkVideoView.this.release(true);
        }
    }

    private void release(boolean cleartargetstate) {
        if (this.mMediaPlayer != null) {
            this.mMediaPlayer.reset();
            this.mMediaPlayer.release();
            this.mMediaPlayer = null;
            this.mCurrentState = 0;
            if (cleartargetstate) {
                this.mTargetState = 0;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isInPlaybackState() && this.mMediaController != null) {
            toggleMediaControlsVisiblity();
            return false;
        }
        return false;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        if (isInPlaybackState() && this.mMediaController != null) {
            toggleMediaControlsVisiblity();
            return false;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean isKeyCodeSupported = (keyCode == 4 || keyCode == 24 || keyCode == 25 || keyCode == 164 || keyCode == 82 || keyCode == 5 || keyCode == 6) ? false : true;
        if (isInPlaybackState() && isKeyCodeSupported && this.mMediaController != null) {
            if (keyCode == 79 || keyCode == 85) {
                if (this.mMediaPlayer.isPlaying()) {
                    pause();
                    this.mMediaController.show();
                    return true;
                }
                start();
                this.mMediaController.hide();
                return true;
            }
            if (keyCode == 126) {
                if (this.mMediaPlayer.isPlaying()) {
                    return true;
                }
                start();
                this.mMediaController.hide();
                return true;
            }
            if (keyCode == 86 || keyCode == 127) {
                if (!this.mMediaPlayer.isPlaying()) {
                    return true;
                }
                pause();
                this.mMediaController.show();
                return true;
            }
            toggleMediaControlsVisiblity();
        }
        return super.onKeyDown(keyCode, event);
    }

    private void toggleMediaControlsVisiblity() {
        if (this.mMediaController.isShowing()) {
            this.mMediaController.hide();
        } else {
            this.mMediaController.show();
        }
    }

    @Override
    public void start() {
        if (isInPlaybackState()) {
            this.mMediaPlayer.start();
            this.mCurrentState = 3;
        }
        this.mTargetState = 3;
    }

    @Override
    public void pause() {
        if (isInPlaybackState() && this.mMediaPlayer.isPlaying()) {
            this.mMediaPlayer.pause();
            this.mCurrentState = 4;
        }
        this.mTargetState = 4;
    }

    @Override
    public void suspend() {
        release(false);
    }

    @Override
    public void resume() {
        this.mWasMuted = !this.mMuted;
        setMute(this.mMuted);
        if (isInPlaybackState()) {
            this.mMediaPlayer.start();
        } else {
            openVideo();
        }
    }

    @Override
    public int getDuration() {
        if (isInPlaybackState()) {
            return this.mMediaPlayer.getDuration();
        }
        return -1;
    }

    @Override
    public int getCurrentPosition() {
        if (isInPlaybackState()) {
            return this.mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(int msec) {
        if (isInPlaybackState()) {
            this.mMediaPlayer.seekTo(msec);
            this.mSeekWhenPrepared = 0;
        } else {
            this.mSeekWhenPrepared = msec;
        }
    }

    @Override
    public boolean isPlaying() {
        return isInPlaybackState() && this.mMediaPlayer.isPlaying();
    }

    @Override
    public boolean isPaused() {
        return isInPlaybackState() && !this.mMediaPlayer.isPlaying();
    }

    @Override
    public boolean hasStarted() {
        return isInPlaybackState();
    }

    @Override
    public int getPlayingPosition() {
        if (!isInPlaybackState() || this.mMediaPlayer == null) {
            return -1;
        }
        return this.mMediaPlayer.getCurrentPosition();
    }

    public void onPrepared() {
        if (isInPlaybackState() && this.mCurrentState == 2 && this.mMediaPlayer != null) {
            this.mOnPreparedListener.onPrepared(this.mMediaPlayer);
        }
    }

    @Override
    public int getBufferPercentage() {
        if (this.mMediaPlayer != null) {
            return this.mCurrentBufferPercentage;
        }
        return 0;
    }

    @Override
    public boolean isInPlaybackState() {
        return (this.mMediaPlayer == null || this.mCurrentState == -1 || this.mCurrentState == 0 || this.mCurrentState == 1) ? false : true;
    }

    @Override
    public boolean canPause() {
        return this.mCanPause;
    }

    @Override
    public boolean canSeekBackward() {
        return this.mCanSeekBack;
    }

    @Override
    public boolean canSeekForward() {
        return this.mCanSeekForward;
    }

    @Override
    public int getAudioSessionId() {
        if (this.mAudioSession == 0) {
            MediaPlayer foo = new MediaPlayer();
            this.mAudioSession = foo.getAudioSessionId();
            foo.release();
        }
        return this.mAudioSession;
    }

    @Override
    public void setOnPreparedListener(VideoViewInterface.OnPreparedListener listener) {
        this.mCustomOnPreParedListener = listener;
    }

    @Override
    public void setAutoPlayOnPrepared(boolean autoPlay) {
        this.mAutoPlay = autoPlay;
    }

    @Override
    public void setLooping(boolean b) {
        this.mLooping = true;
    }

    public void setMute(boolean mute) {
        this.mMuted = mute;
        if (isInPlaybackState() && this.mWasMuted != this.mMuted) {
            if (this.mMuted) {
                this.mMediaPlayer.setVolume(0.0f, 0.0f);
            } else {
                this.mMediaPlayer.setVolume(1.0f, 1.0f);
            }
        }
        this.mWasMuted = this.mMuted;
    }

    public void setReportOpenVideoError(boolean reportVideoError) {
        this.mReportVideoError = reportVideoError;
    }

    public boolean isReportErrorOn() {
        return this.mReportVideoError;
    }

    @Override
    public void setCacheKey(CacheKey cacheKey) {
        this.mCacheKey = cacheKey;
    }

    @Override
    public CacheKey getCacheKey() {
        return this.mCacheKey;
    }
}

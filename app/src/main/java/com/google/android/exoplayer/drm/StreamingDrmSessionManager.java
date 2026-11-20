package com.google.android.exoplayer.drm;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.DeniedByServerException;
import android.media.MediaCrypto;
import android.media.MediaDrm;
import android.media.NotProvisionedException;
import android.media.UnsupportedSchemeException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import java.util.Map;
import java.util.UUID;

@TargetApi(18)
public class StreamingDrmSessionManager implements DrmSessionManager {
    private static final int MSG_KEYS = 1;
    private static final int MSG_PROVISION = 0;
    final MediaDrmCallback callback;
    private final Handler eventHandler;
    private final EventListener eventListener;
    private Exception lastException;
    private MediaCrypto mediaCrypto;
    private final MediaDrm mediaDrm;
    final MediaDrmHandler mediaDrmHandler;
    private String mimeType;
    private int openCount;
    private Handler postRequestHandler;
    final PostResponseHandler postResponseHandler;
    private HandlerThread requestHandlerThread;
    private byte[] schemePsshData;
    private byte[] sessionId;
    private int state;
    final UUID uuid;

    public interface EventListener {
        void onDrmSessionManagerError(Exception exc);
    }

    public StreamingDrmSessionManager(UUID uuid, Looper playbackLooper, MediaDrmCallback callback, Handler eventHandler, EventListener eventListener) throws UnsupportedSchemeException {
        this.uuid = uuid;
        this.callback = callback;
        this.eventHandler = eventHandler;
        this.eventListener = eventListener;
        this.mediaDrm = new MediaDrm(uuid);
        this.mediaDrm.setOnEventListener(new MediaDrmEventListener(this, null));
        this.mediaDrmHandler = new MediaDrmHandler(playbackLooper);
        this.postResponseHandler = new PostResponseHandler(playbackLooper);
        this.state = 1;
    }

    @Override
    public int getState() {
        return this.state;
    }

    @Override
    public MediaCrypto getMediaCrypto() {
        if (this.state != 3 && this.state != 4) {
            throw new IllegalStateException();
        }
        return this.mediaCrypto;
    }

    @Override
    public boolean requiresSecureDecoderComponent(String mimeType) {
        if (this.state != 3 && this.state != 4) {
            throw new IllegalStateException();
        }
        return this.mediaCrypto.requiresSecureDecoderComponent(mimeType);
    }

    @Override
    public Exception getError() {
        if (this.state == 0) {
            return this.lastException;
        }
        return null;
    }

    public final String getPropertyString(String key) {
        return this.mediaDrm.getPropertyString(key);
    }

    public final byte[] getPropertyByteArray(String key) {
        return this.mediaDrm.getPropertyByteArray(key);
    }

    @Override
    public void open(Map<UUID, byte[]> psshData, String mimeType) {
        int i = this.openCount + 1;
        this.openCount = i;
        if (i == 1) {
            if (this.postRequestHandler == null) {
                this.requestHandlerThread = new HandlerThread("DrmRequestHandler");
                this.requestHandlerThread.start();
                this.postRequestHandler = new PostRequestHandler(this.requestHandlerThread.getLooper());
            }
            if (this.schemePsshData == null) {
                this.mimeType = mimeType;
                this.schemePsshData = psshData.get(this.uuid);
                if (this.schemePsshData == null) {
                    onError(new IllegalStateException("Media does not support uuid: " + this.uuid));
                    return;
                }
            }
            this.state = 2;
            openInternal(true);
        }
    }

    @Override
    public void close() {
        int i = this.openCount - 1;
        this.openCount = i;
        if (i == 0) {
            this.state = 1;
            this.mediaDrmHandler.removeCallbacksAndMessages(null);
            this.postResponseHandler.removeCallbacksAndMessages(null);
            this.postRequestHandler.removeCallbacksAndMessages(null);
            this.postRequestHandler = null;
            this.requestHandlerThread.quit();
            this.requestHandlerThread = null;
            this.schemePsshData = null;
            this.mediaCrypto = null;
            this.lastException = null;
            if (this.sessionId != null) {
                this.mediaDrm.closeSession(this.sessionId);
                this.sessionId = null;
            }
        }
    }

    private void openInternal(boolean allowProvisioning) {
        try {
            this.sessionId = this.mediaDrm.openSession();
            this.mediaCrypto = new MediaCrypto(this.uuid, this.sessionId);
            this.state = 3;
            postKeyRequest();
        } catch (NotProvisionedException e) {
            if (allowProvisioning) {
                postProvisionRequest();
            } else {
                onError(e);
            }
        } catch (Exception e2) {
            onError(e2);
        }
    }

    private void postProvisionRequest() {
        MediaDrm.ProvisionRequest request = this.mediaDrm.getProvisionRequest();
        this.postRequestHandler.obtainMessage(0, request).sendToTarget();
    }

    private void onProvisionResponse(Object response) {
        if (this.state == 2 || this.state == 3 || this.state == 4) {
            if (response instanceof Exception) {
                onError((Exception) response);
                return;
            }
            try {
                this.mediaDrm.provideProvisionResponse((byte[]) response);
                if (this.state == 2) {
                    openInternal(false);
                } else {
                    postKeyRequest();
                }
            } catch (DeniedByServerException e) {
                onError(e);
            }
        }
    }

    private void postKeyRequest() {
        try {
            MediaDrm.KeyRequest keyRequest = this.mediaDrm.getKeyRequest(this.sessionId, this.schemePsshData, this.mimeType, 1, null);
            this.postRequestHandler.obtainMessage(1, keyRequest).sendToTarget();
        } catch (NotProvisionedException e) {
            onKeysError(e);
        }
    }

    private void onKeyResponse(Object response) {
        if (this.state == 3 || this.state == 4) {
            if (response instanceof Exception) {
                onKeysError((Exception) response);
                return;
            }
            try {
                this.mediaDrm.provideKeyResponse(this.sessionId, (byte[]) response);
                this.state = 4;
            } catch (Exception e) {
                onKeysError(e);
            }
        }
    }

    private void onKeysError(Exception e) {
        if (e instanceof NotProvisionedException) {
            postProvisionRequest();
        } else {
            onError(e);
        }
    }

    private void onError(Exception e) {
        this.lastException = e;
        if (this.eventHandler != null && this.eventListener != null) {
            this.eventHandler.post(new RunnableC08701());
        }
        if (this.state != 4) {
            this.state = 0;
        }
    }

    class RunnableC08701 implements Runnable {
        RunnableC08701() {
        }

        @Override
        public void run() {
            StreamingDrmSessionManager.this.eventListener.onDrmSessionManagerError(StreamingDrmSessionManager.this.lastException);
        }
    }

    @SuppressLint({"HandlerLeak"})
    private class MediaDrmHandler extends Handler {
        public MediaDrmHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (StreamingDrmSessionManager.this.openCount != 0) {
                if (StreamingDrmSessionManager.this.state == 3 || StreamingDrmSessionManager.this.state == 4) {
                    switch (msg.what) {
                        case 1:
                            StreamingDrmSessionManager.this.state = 3;
                            StreamingDrmSessionManager.this.postProvisionRequest();
                            break;
                        case 2:
                            StreamingDrmSessionManager.this.postKeyRequest();
                            break;
                        case 3:
                            StreamingDrmSessionManager.this.state = 3;
                            StreamingDrmSessionManager.this.postKeyRequest();
                            break;
                    }
                }
            }
        }
    }

    private class MediaDrmEventListener implements MediaDrm.OnEventListener {
        private MediaDrmEventListener() {
        }

        MediaDrmEventListener(StreamingDrmSessionManager x0, RunnableC08701 x1) {
            this();
        }

        @Override
        public void onEvent(MediaDrm md, byte[] sessionId, int event, int extra, byte[] data) {
            StreamingDrmSessionManager.this.mediaDrmHandler.sendEmptyMessage(event);
        }
    }

    @SuppressLint({"HandlerLeak"})
    private class PostResponseHandler extends Handler {
        public PostResponseHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    StreamingDrmSessionManager.this.onProvisionResponse(msg.obj);
                    break;
                case 1:
                    StreamingDrmSessionManager.this.onKeyResponse(msg.obj);
                    break;
            }
        }
    }

    @SuppressLint({"HandlerLeak"})
    private class PostRequestHandler extends Handler {
        public PostRequestHandler(Looper backgroundLooper) {
            super(backgroundLooper);
        }

        @Override
        public void handleMessage(Message msg) {
            Object objExecuteKeyRequest;
            try {
                switch (msg.what) {
                    case 0:
                        objExecuteKeyRequest = StreamingDrmSessionManager.this.callback.executeProvisionRequest(StreamingDrmSessionManager.this.uuid, (MediaDrm.ProvisionRequest) msg.obj);
                        break;
                    case 1:
                        objExecuteKeyRequest = StreamingDrmSessionManager.this.callback.executeKeyRequest(StreamingDrmSessionManager.this.uuid, (MediaDrm.KeyRequest) msg.obj);
                        break;
                    default:
                        throw new RuntimeException();
                }
            } catch (Exception e) {
                objExecuteKeyRequest = e;
            }
            StreamingDrmSessionManager.this.postResponseHandler.obtainMessage(msg.what, objExecuteKeyRequest).sendToTarget();
        }
    }
}

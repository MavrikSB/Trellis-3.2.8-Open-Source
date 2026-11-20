package com.google.android.exoplayer.upstream;

import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.Util;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class DataSourceStream implements Loader.Loadable, NonBlockingInputStream {
    private static final int CHUNKED_ALLOCATION_INCREMENT = 262144;
    private Allocation allocation;
    private final Allocator allocator;
    private final DataSource dataSource;
    private final DataSpec dataSpec;
    private volatile boolean loadCanceled;
    private volatile long loadPosition;
    private final ReadHead readHead;
    private volatile long resolvedLength;
    private int writeFragmentIndex;
    private int writeFragmentOffset;
    private int writeFragmentRemainingLength;

    public static class DataSourceStreamLoadException extends IOException {
        public DataSourceStreamLoadException(IOException cause) {
            super(cause);
        }
    }

    public DataSourceStream(DataSource dataSource, DataSpec dataSpec, Allocator allocator) {
        Assertions.checkState(dataSpec.length <= 2147483647L);
        this.dataSource = dataSource;
        this.dataSpec = dataSpec;
        this.allocator = allocator;
        this.resolvedLength = -1L;
        this.readHead = new ReadHead(null);
    }

    public void resetReadPosition() {
        this.readHead.reset();
    }

    public long getReadPosition() {
        return this.readHead.position;
    }

    public long getLoadPosition() {
        return this.loadPosition;
    }

    public long getLength() {
        return this.resolvedLength != -1 ? this.resolvedLength : this.dataSpec.length;
    }

    public boolean isLoadFinished() {
        return this.resolvedLength != -1 && this.loadPosition == this.resolvedLength;
    }

    public final byte[] getLoadedData() {
        if (this.loadPosition == 0) {
            return null;
        }
        byte[] rawData = new byte[(int) this.loadPosition];
        read(null, rawData, 0, new ReadHead(null), rawData.length);
        return rawData;
    }

    @Override
    public long getAvailableByteCount() {
        return this.loadPosition - ((long) this.readHead.position);
    }

    @Override
    public boolean isEndOfStream() {
        return this.resolvedLength != -1 && ((long) this.readHead.position) == this.resolvedLength;
    }

    @Override
    public void close() {
        if (this.allocation != null) {
            this.allocation.release();
            this.allocation = null;
        }
    }

    @Override
    public int skip(int skipLength) {
        return read(null, null, 0, this.readHead, skipLength);
    }

    @Override
    public int read(ByteBuffer target1, int readLength) {
        return read(target1, null, 0, this.readHead, readLength);
    }

    @Override
    public int read(byte[] target, int offset, int readLength) {
        return read(null, target, offset, this.readHead, readLength);
    }

    private int read(ByteBuffer target, byte[] targetArray, int targetArrayOffset, ReadHead readHead, int readLength) {
        if (isEndOfStream()) {
            return -1;
        }
        int bytesToRead = (int) Math.min(this.loadPosition - ((long) readHead.position), readLength);
        if (bytesToRead == 0) {
            return 0;
        }
        if (readHead.position == 0) {
            readHead.fragmentIndex = 0;
            readHead.fragmentOffset = this.allocation.getFragmentOffset(0);
            readHead.fragmentRemaining = this.allocation.getFragmentLength(0);
        }
        int bytesRead = 0;
        byte[][] buffers = this.allocation.getBuffers();
        while (bytesRead < bytesToRead) {
            if (readHead.fragmentRemaining == 0) {
                readHead.fragmentIndex++;
                readHead.fragmentOffset = this.allocation.getFragmentOffset(readHead.fragmentIndex);
                readHead.fragmentRemaining = this.allocation.getFragmentLength(readHead.fragmentIndex);
            }
            int bufferReadLength = Math.min(readHead.fragmentRemaining, bytesToRead - bytesRead);
            if (target != null) {
                target.put(buffers[readHead.fragmentIndex], readHead.fragmentOffset, bufferReadLength);
            } else if (targetArray != null) {
                System.arraycopy(buffers[readHead.fragmentIndex], readHead.fragmentOffset, targetArray, targetArrayOffset, bufferReadLength);
                targetArrayOffset += bufferReadLength;
            }
            readHead.position += bufferReadLength;
            bytesRead += bufferReadLength;
            readHead.fragmentOffset += bufferReadLength;
            readHead.fragmentRemaining -= bufferReadLength;
        }
        return bytesRead;
    }

    @Override
    public void cancelLoad() {
        this.loadCanceled = true;
    }

    @Override
    public boolean isLoadCanceled() {
        return this.loadCanceled;
    }

    @Override
    public void load() throws InterruptedException, IOException {
        if (!this.loadCanceled && !isLoadFinished()) {
            try {
                if (this.loadPosition == 0 && this.resolvedLength == -1) {
                    DataSpec loadDataSpec = this.dataSpec;
                    long resolvedLength = this.dataSource.open(loadDataSpec);
                    if (resolvedLength > 2147483647L) {
                        throw new DataSourceStreamLoadException(new UnexpectedLengthException(this.dataSpec.length, resolvedLength));
                    }
                    this.resolvedLength = resolvedLength;
                } else {
                    long remainingLength = this.resolvedLength != -1 ? this.resolvedLength - this.loadPosition : -1L;
                    DataSpec loadDataSpec2 = new DataSpec(this.dataSpec.uri, this.dataSpec.position + this.loadPosition, remainingLength, this.dataSpec.key);
                    this.dataSource.open(loadDataSpec2);
                }
                if (this.allocation == null) {
                    int initialAllocationSize = this.resolvedLength != -1 ? (int) this.resolvedLength : 262144;
                    this.allocation = this.allocator.allocate(initialAllocationSize);
                }
                int allocationCapacity = this.allocation.capacity();
                if (this.loadPosition == 0) {
                    this.writeFragmentIndex = 0;
                    this.writeFragmentOffset = this.allocation.getFragmentOffset(0);
                    this.writeFragmentRemainingLength = this.allocation.getFragmentLength(0);
                }
                int read = Integer.MAX_VALUE;
                byte[][] buffers = this.allocation.getBuffers();
                while (!this.loadCanceled && read > 0 && maybeMoreToLoad()) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                    read = this.dataSource.read(buffers[this.writeFragmentIndex], this.writeFragmentOffset, this.writeFragmentRemainingLength);
                    if (read > 0) {
                        this.loadPosition += (long) read;
                        this.writeFragmentOffset += read;
                        this.writeFragmentRemainingLength -= read;
                        if (this.writeFragmentRemainingLength == 0 && maybeMoreToLoad()) {
                            this.writeFragmentIndex++;
                            if (this.loadPosition == allocationCapacity) {
                                this.allocation.ensureCapacity(262144 + allocationCapacity);
                                allocationCapacity = this.allocation.capacity();
                                buffers = this.allocation.getBuffers();
                            }
                            this.writeFragmentOffset = this.allocation.getFragmentOffset(this.writeFragmentIndex);
                            this.writeFragmentRemainingLength = this.allocation.getFragmentLength(this.writeFragmentIndex);
                        }
                    } else if (this.resolvedLength == -1) {
                        this.resolvedLength = this.loadPosition;
                    } else if (this.resolvedLength != this.loadPosition) {
                        throw new DataSourceStreamLoadException(new UnexpectedLengthException(this.resolvedLength, this.loadPosition));
                    }
                }
                Util.closeQuietly(this.dataSource);
            } catch (Throwable th) {
                Util.closeQuietly(this.dataSource);
                throw th;
            }
        }
    }

    private boolean maybeMoreToLoad() {
        return this.resolvedLength == -1 || this.loadPosition < this.resolvedLength;
    }

    private static class ReadHead {
        private int fragmentIndex;
        private int fragmentOffset;
        private int fragmentRemaining;
        private int position;

        private ReadHead() {
        }

        ReadHead(Object x0) {
            this();
        }

        public void reset() {
            this.position = 0;
            this.fragmentIndex = 0;
            this.fragmentOffset = 0;
            this.fragmentRemaining = 0;
        }
    }
}

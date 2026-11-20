package com.google.android.exoplayer.upstream;

import java.io.IOException;
import java.util.PriorityQueue;

public final class NetworkLock {
    public static final int DOWNLOAD_PRIORITY = 10;
    public static final int STREAMING_PRIORITY = 0;
    public static final NetworkLock instance = new NetworkLock();
    private final PriorityQueue<Integer> queue = new PriorityQueue<>();

    public static class PriorityTooLowException extends IOException {
        public PriorityTooLowException(int priority, int highestPriority) {
            super("Priority too low [priority=" + priority + ", highest=" + highestPriority + "]");
        }
    }

    private NetworkLock() {
    }

    public synchronized void proceed(int priority) throws InterruptedException {
        while (this.queue.peek().intValue() < priority) {
            wait();
        }
    }

    public synchronized boolean proceedNonBlocking(int priority) {
        return this.queue.peek().intValue() >= priority;
    }

    public synchronized void proceedOrThrow(int priority) throws PriorityTooLowException {
        int highestPriority = this.queue.peek().intValue();
        if (highestPriority < priority) {
            throw new PriorityTooLowException(priority, highestPriority);
        }
    }

    public synchronized void add(int priority) {
        this.queue.add(Integer.valueOf(priority));
    }

    public synchronized void remove(int priority) {
        this.queue.remove(Integer.valueOf(priority));
        notifyAll();
    }
}

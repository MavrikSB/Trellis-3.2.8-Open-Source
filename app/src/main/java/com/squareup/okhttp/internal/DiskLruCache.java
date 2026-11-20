package com.squareup.okhttp.internal;

import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;
import org.apache.commons.io.FilenameUtils;

public final class DiskLruCache implements Closeable {
    static final long ANY_SEQUENCE_NUMBER = -1;
    private static final String CLEAN = "CLEAN";
    private static final String DIRTY = "DIRTY";
    static final String JOURNAL_FILE = "journal";
    static final String JOURNAL_FILE_BACKUP = "journal.bkp";
    static final String JOURNAL_FILE_TEMP = "journal.tmp";
    static final String MAGIC = "libcore.io.DiskLruCache";
    private static final String READ = "READ";
    private static final String REMOVE = "REMOVE";
    static final String VERSION_1 = "1";
    private final int appVersion;
    private final File directory;
    private final File journalFile;
    private final File journalFileBackup;
    private final File journalFileTmp;
    private BufferedSink journalWriter;
    private long maxSize;
    private int redundantOpCount;
    private final int valueCount;
    static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,120}");
    private static final Sink NULL_SINK = new C12702();
    private long size = 0;
    private final LinkedHashMap<String, Entry> lruEntries = new LinkedHashMap<>(0, 0.75f, true);
    private long nextSequenceNumber = 0;
    final ThreadPoolExecutor executorService = new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue(), Util.threadFactory("OkHttp DiskLruCache", true));
    private final Runnable cleanupRunnable = new RunnableC12691();

    static BufferedSink access$000(DiskLruCache x0) {
        return x0.journalWriter;
    }

    static void access$100(DiskLruCache x0) throws IOException {
        x0.trimToSize();
    }

    static boolean access$200(DiskLruCache x0) {
        return x0.journalRebuildRequired();
    }

    static void access$300(DiskLruCache x0) throws IOException {
        x0.rebuildJournal();
    }

    class RunnableC12691 implements Runnable {
        RunnableC12691() {
        }

        @Override
        public void run() {
            synchronized (DiskLruCache.this) {
                if (DiskLruCache.access$000(DiskLruCache.this) != null) {
                    try {
                        DiskLruCache.access$100(DiskLruCache.this);
                        if (DiskLruCache.access$200(DiskLruCache.this)) {
                            DiskLruCache.access$300(DiskLruCache.this);
                            DiskLruCache.this.redundantOpCount = 0;
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private DiskLruCache(File directory, int appVersion, int valueCount, long maxSize) {
        this.directory = directory;
        this.appVersion = appVersion;
        this.journalFile = new File(directory, JOURNAL_FILE);
        this.journalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
        this.journalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
        this.valueCount = valueCount;
        this.maxSize = maxSize;
    }

    public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize) throws IOException {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (valueCount <= 0) {
            throw new IllegalArgumentException("valueCount <= 0");
        }
        File backupFile = new File(directory, JOURNAL_FILE_BACKUP);
        if (backupFile.exists()) {
            File journalFile = new File(directory, JOURNAL_FILE);
            if (journalFile.exists()) {
                backupFile.delete();
            } else {
                renameTo(backupFile, journalFile, false);
            }
        }
        DiskLruCache cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
        if (cache.journalFile.exists()) {
            try {
                cache.readJournal();
                cache.processJournal();
                return cache;
            } catch (IOException journalIsCorrupt) {
                Platform.get().logW("DiskLruCache " + directory + " is corrupt: " + journalIsCorrupt.getMessage() + ", removing");
                cache.delete();
            }
        }
        directory.mkdirs();
        DiskLruCache cache2 = new DiskLruCache(directory, appVersion, valueCount, maxSize);
        cache2.rebuildJournal();
        return cache2;
    }

    private void readJournal() throws IOException {
        BufferedSource source = Okio.buffer(Okio.source(this.journalFile));
        try {
            String magic = source.readUtf8LineStrict();
            String version = source.readUtf8LineStrict();
            String appVersionString = source.readUtf8LineStrict();
            String valueCountString = source.readUtf8LineStrict();
            String blank = source.readUtf8LineStrict();
            if (!MAGIC.equals(magic) || !VERSION_1.equals(version) || !Integer.toString(this.appVersion).equals(appVersionString) || !Integer.toString(this.valueCount).equals(valueCountString) || !"".equals(blank)) {
                throw new IOException("unexpected journal header: [" + magic + ", " + version + ", " + valueCountString + ", " + blank + "]");
            }
            int lineCount = 0;
            while (true) {
                try {
                    readJournalLine(source.readUtf8LineStrict());
                    lineCount++;
                } catch (EOFException e) {
                    this.redundantOpCount = lineCount - this.lruEntries.size();
                    if (!source.exhausted()) {
                        rebuildJournal();
                    } else {
                        this.journalWriter = Okio.buffer(Okio.appendingSink(this.journalFile));
                    }
                    Util.closeQuietly(source);
                    return;
                }
            }
        } catch (Throwable th) {
            Util.closeQuietly(source);
            throw th;
        }
    }

    private void readJournalLine(String line) throws IOException {
        String key;
        int firstSpace = line.indexOf(32);
        if (firstSpace == -1) {
            throw new IOException("unexpected journal line: " + line);
        }
        int keyBegin = firstSpace + 1;
        int secondSpace = line.indexOf(32, keyBegin);
        if (secondSpace == -1) {
            key = line.substring(keyBegin);
            if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
                this.lruEntries.remove(key);
                return;
            }
        } else {
            key = line.substring(keyBegin, secondSpace);
        }
        Entry entry = this.lruEntries.get(key);
        if (entry == null) {
            entry = new Entry(this, key, null);
            this.lruEntries.put(key, entry);
        }
        if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
            String[] parts = line.substring(secondSpace + 1).split(MinimalPrettyPrinter.DEFAULT_ROOT_VALUE_SEPARATOR);
            entry.readable = true;
            entry.currentEditor = null;
            entry.setLengths(parts);
            return;
        }
        if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
            entry.currentEditor = new Editor(this, entry, null);
        } else if (secondSpace != -1 || firstSpace != READ.length() || !line.startsWith(READ)) {
            throw new IOException("unexpected journal line: " + line);
        }
    }

    private void processJournal() throws IOException {
        deleteIfExists(this.journalFileTmp);
        Iterator<Entry> i = this.lruEntries.values().iterator();
        while (i.hasNext()) {
            Entry entry = i.next();
            if (entry.currentEditor == null) {
                for (int t = 0; t < this.valueCount; t++) {
                    this.size += entry.lengths[t];
                }
            } else {
                entry.currentEditor = null;
                for (int t2 = 0; t2 < this.valueCount; t2++) {
                    deleteIfExists(entry.cleanFiles[t2]);
                    deleteIfExists(entry.dirtyFiles[t2]);
                }
                i.remove();
            }
        }
    }

    private synchronized void rebuildJournal() throws IOException {
        if (this.journalWriter != null) {
            this.journalWriter.close();
        }
        BufferedSink writer = Okio.buffer(Okio.sink(this.journalFileTmp));
        try {
            writer.writeUtf8(MAGIC).writeByte(10);
            writer.writeUtf8(VERSION_1).writeByte(10);
            writer.writeUtf8(Integer.toString(this.appVersion)).writeByte(10);
            writer.writeUtf8(Integer.toString(this.valueCount)).writeByte(10);
            writer.writeByte(10);
            for (Entry entry : this.lruEntries.values()) {
                if (entry.currentEditor != null) {
                    writer.writeUtf8(DIRTY).writeByte(32);
                    writer.writeUtf8(entry.key);
                    writer.writeByte(10);
                } else {
                    writer.writeUtf8(CLEAN).writeByte(32);
                    writer.writeUtf8(entry.key);
                    writer.writeUtf8(entry.getLengths());
                    writer.writeByte(10);
                }
            }
            writer.close();
            if (this.journalFile.exists()) {
                renameTo(this.journalFile, this.journalFileBackup, true);
            }
            renameTo(this.journalFileTmp, this.journalFile, false);
            this.journalFileBackup.delete();
            this.journalWriter = Okio.buffer(Okio.appendingSink(this.journalFile));
        } catch (Throwable th) {
            writer.close();
            throw th;
        }
    }

    private static void deleteIfExists(File file) throws IOException {
        if (!file.delete() && file.exists()) {
            throw new IOException("failed to delete " + file);
        }
    }

    private static void renameTo(File from, File to, boolean deleteDestination) throws IOException {
        if (deleteDestination) {
            deleteIfExists(to);
        }
        if (!from.renameTo(to)) {
            throw new IOException();
        }
    }

    public synchronized Snapshot get(String key) throws IOException {
        Snapshot snapshot = null;
        synchronized (this) {
            checkNotClosed();
            validateKey(key);
            Entry entry = this.lruEntries.get(key);
            if (entry != null && entry.readable) {
                Source[] sources = new Source[this.valueCount];
                for (int i = 0; i < this.valueCount; i++) {
                    try {
                        sources[i] = Okio.source(entry.cleanFiles[i]);
                    } catch (FileNotFoundException e) {
                        for (int i2 = 0; i2 < this.valueCount && sources[i2] != null; i2++) {
                            Util.closeQuietly(sources[i2]);
                        }
                    }
                }
                this.redundantOpCount++;
                this.journalWriter.writeUtf8(READ).writeByte(32).writeUtf8(key).writeByte(10);
                if (journalRebuildRequired()) {
                    this.executorService.execute(this.cleanupRunnable);
                }
                snapshot = new Snapshot(this, key, entry.sequenceNumber, sources, entry.lengths, null);
            }
        }
        return snapshot;
    }

    public Editor edit(String key) throws IOException {
        return edit(key, -1L);
    }

    private synchronized Editor edit(String key, long expectedSequenceNumber) throws IOException {
        Editor editor = null;
        synchronized (this) {
            checkNotClosed();
            validateKey(key);
            Entry entry = this.lruEntries.get(key);
            if (expectedSequenceNumber == -1 || (entry != null && entry.sequenceNumber == expectedSequenceNumber)) {
                if (entry == null) {
                    entry = new Entry(this, key, null);
                    this.lruEntries.put(key, entry);
                } else if (entry.currentEditor == null) {
                }
                editor = new Editor(this, entry, null);
                entry.currentEditor = editor;
                this.journalWriter.writeUtf8(DIRTY).writeByte(32).writeUtf8(key).writeByte(10);
                this.journalWriter.flush();
            }
        }
        return editor;
    }

    public File getDirectory() {
        return this.directory;
    }

    public synchronized long getMaxSize() {
        return this.maxSize;
    }

    public synchronized void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        this.executorService.execute(this.cleanupRunnable);
    }

    public synchronized long size() {
        return this.size;
    }

    private synchronized void completeEdit(Editor editor, boolean success) throws IOException {
        int i = 0;
        File dirty;

        Entry entry = editor.entry;
        if (entry.currentEditor != editor) {
            throw new IllegalStateException();
        }
        if (success && !entry.readable) {
            for (int i2 = 0; i2 < this.valueCount; i2++) {
                if (!editor.written[i2]) {
                    editor.abort();
                    throw new IllegalStateException("Newly created entry didn't create value for index " + i2);
                }
                if (!entry.dirtyFiles[i2].exists()) {
                    editor.abort();
                }
            }
            for (i = 0; i < this.valueCount; i++) {
                dirty = entry.dirtyFiles[i];
                if (success) {
                    if (dirty.exists()) {
                        File clean = entry.cleanFiles[i];
                        dirty.renameTo(clean);
                        long oldLength = entry.lengths[i];
                        long newLength = clean.length();
                        entry.lengths[i] = newLength;
                        this.size = (this.size - oldLength) + newLength;
                    }
                } else {
                    deleteIfExists(dirty);
                }
            }
            this.redundantOpCount++;
            entry.currentEditor = null;
            if (entry.readable | success) {
                entry.readable = true;
                this.journalWriter.writeUtf8(CLEAN).writeByte(32);
                this.journalWriter.writeUtf8(entry.key);
                this.journalWriter.writeUtf8(entry.getLengths());
                this.journalWriter.writeByte(10);
                if (success) {
                    long j = this.nextSequenceNumber;
                    this.nextSequenceNumber = 1 + j;
                    entry.sequenceNumber = j;
                }
            } else {
                this.lruEntries.remove(entry.key);
                this.journalWriter.writeUtf8(REMOVE).writeByte(32);
                this.journalWriter.writeUtf8(entry.key);
                this.journalWriter.writeByte(10);
            }
            this.journalWriter.flush();
            if (this.size <= this.maxSize) {
                this.executorService.execute(this.cleanupRunnable);
            } else {
                this.executorService.execute(this.cleanupRunnable);
            }
        } else {
            while (i < this.valueCount) {
                dirty = entry.dirtyFiles[i];
                if (success) {
                    if (dirty.exists()) {
                        File clean2 = entry.cleanFiles[i];
                        dirty.renameTo(clean2);
                        long oldLength2 = entry.lengths[i];
                        long newLength2 = clean2.length();
                        entry.lengths[i] = newLength2;
                        this.size = (this.size - oldLength2) + newLength2;
                    }
                } else {
                    deleteIfExists(dirty);
                }
            }
            this.redundantOpCount++;
            entry.currentEditor = null;
            if (entry.readable | success) {
                entry.readable = true;
                this.journalWriter.writeUtf8(CLEAN).writeByte(32);
                this.journalWriter.writeUtf8(entry.key);
                this.journalWriter.writeUtf8(entry.getLengths());
                this.journalWriter.writeByte(10);
                if (success) {
                    long j2 = this.nextSequenceNumber;
                    this.nextSequenceNumber = 1 + j2;
                    entry.sequenceNumber = j2;
                }
            } else {
                this.lruEntries.remove(entry.key);
                this.journalWriter.writeUtf8(REMOVE).writeByte(32);
                this.journalWriter.writeUtf8(entry.key);
                this.journalWriter.writeByte(10);
            }
            this.journalWriter.flush();
            if (this.size <= this.maxSize || journalRebuildRequired()) {
                this.executorService.execute(this.cleanupRunnable);
            }
        }
    }

    private boolean journalRebuildRequired() {
        return this.redundantOpCount >= 2000 && this.redundantOpCount >= this.lruEntries.size();
    }

    public synchronized boolean remove(String key) throws IOException {
        Entry entry;

        checkNotClosed();
        validateKey(key);
        entry = this.lruEntries.get(key);

        return entry == null ? false : removeEntry(entry);
    }

    private boolean removeEntry(Entry entry) throws IOException {
        if (entry.currentEditor != null) {
            entry.currentEditor.hasErrors = true;
        }
        for (int i = 0; i < this.valueCount; i++) {
            File file = entry.cleanFiles[i];
            deleteIfExists(file);
            this.size -= entry.lengths[i];
            entry.lengths[i] = 0;
        }
        this.redundantOpCount++;
        this.journalWriter.writeUtf8(REMOVE).writeByte(32).writeUtf8(entry.key).writeByte(10);
        this.lruEntries.remove(entry.key);
        if (journalRebuildRequired()) {
            this.executorService.execute(this.cleanupRunnable);
        }
        return true;
    }

    public boolean isClosed() {
        return this.journalWriter == null;
    }

    private void checkNotClosed() {
        if (this.journalWriter == null) {
            throw new IllegalStateException("cache is closed");
        }
    }

    public synchronized void flush() throws IOException {
        checkNotClosed();
        trimToSize();
        this.journalWriter.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (this.journalWriter != null) {
            Entry[] arr$ = (Entry[]) this.lruEntries.values().toArray(new Entry[this.lruEntries.size()]);
            for (Entry entry : arr$) {
                if (entry.currentEditor != null) {
                    entry.currentEditor.abort();
                }
            }
            trimToSize();
            this.journalWriter.close();
            this.journalWriter = null;
        }
    }

    private void trimToSize() throws IOException {
        while (this.size > this.maxSize) {
            Entry toEvict = this.lruEntries.values().iterator().next();
            removeEntry(toEvict);
        }
    }

    public void delete() throws IOException {
        close();
        Util.deleteContents(this.directory);
    }

    public synchronized void evictAll() throws IOException {
        Entry[] arr$ = (Entry[]) this.lruEntries.values().toArray(new Entry[this.lruEntries.size()]);
        for (Entry entry : arr$) {
            removeEntry(entry);
        }
    }

    private void validateKey(String key) {
        Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
        }
    }

    private static String sourceToString(Source in) throws IOException {
        try {
            String utf8 = Okio.buffer(in).readUtf8();
            Util.closeQuietly(in);
            return utf8;
        } catch (Throwable th) {
            Util.closeQuietly(in);
            throw th;
        }
    }

    public final class Snapshot implements Closeable {
        private final String key;
        private final long[] lengths;
        private final long sequenceNumber;
        private final Source[] sources;

        Snapshot(DiskLruCache x0, String x1, long x2, Source[] x3, long[] x4, RunnableC12691 x5) {
            this(x1, x2, x3, x4);
        }

        private Snapshot(String key, long sequenceNumber, Source[] sources, long[] lengths) {
            this.key = key;
            this.sequenceNumber = sequenceNumber;
            this.sources = sources;
            this.lengths = lengths;
        }

        public Editor edit() throws IOException {
            return DiskLruCache.this.edit(this.key, this.sequenceNumber);
        }

        public Source getSource(int index) {
            return this.sources[index];
        }

        public String getString(int index) throws IOException {
            return DiskLruCache.sourceToString((getSource(index)));
        }

        public long getLength(int index) {
            return this.lengths[index];
        }

        @Override
        public void close() {
            Source[] arr$ = this.sources;
            for (Source in : arr$) {
                Util.closeQuietly(in);
            }
        }
    }

    static class C12702 implements Sink {
        C12702() {
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        @Override
        public void close() throws IOException {
        }
    }

    public final class Editor {
        private boolean committed;
        private final Entry entry;
        private boolean hasErrors;
        private final boolean[] written;

        Editor(DiskLruCache x0, Entry x1, RunnableC12691 x2) {
            this(x1);
        }

        private Editor(Entry entry) {
            this.entry = entry;
            this.written = entry.readable ? null : new boolean[DiskLruCache.this.valueCount];
        }

        public Source newSource(int index) throws IOException {
            Source source = null;
            synchronized (DiskLruCache.this) {
                if (this.entry.currentEditor != this) {
                    throw new IllegalStateException();
                }
                if (this.entry.readable) {
                    try {
                        source = Okio.source(this.entry.cleanFiles[index]);
                    } catch (FileNotFoundException e) {
                    }
                }
                return source;
            }
        }

        public String getString(int index) throws IOException {
            Source source = newSource(index);
            if (source != null) {
                return DiskLruCache.sourceToString(source);
            }
            return null;
        }

        public Sink newSink(int index) throws IOException {
            Sink sinkAccess$2300;
            Sink sink = DiskLruCache.NULL_SINK;
            synchronized (DiskLruCache.this) {
                if (this.entry.currentEditor != this) {
                    throw new IllegalStateException();
                }
                if (!this.entry.readable) {
                    this.written[index] = true;
                }
                File dirtyFile = this.entry.dirtyFiles[index];
                try {
                    sink = Okio.sink(dirtyFile);
                } catch (FileNotFoundException e) {
                    DiskLruCache.this.directory.mkdirs();
                    try {
                        sink = Okio.sink(dirtyFile);
                    } catch (FileNotFoundException e2) {
                        sink = DiskLruCache.NULL_SINK;
                    }
                }
                sinkAccess$2300 = new FaultHidingSink(sink);
            }
            return sinkAccess$2300;
        }

        public void set(int index, String value) throws IOException {
            BufferedSink writer = Okio.buffer(newSink(index));
            writer.writeUtf8(value);
            writer.close();
        }

        public void commit() throws IOException {
            synchronized (DiskLruCache.this) {
                if (this.hasErrors) {
                    DiskLruCache.this.completeEdit(this, false);
                    DiskLruCache.this.removeEntry(this.entry);
                } else {
                    DiskLruCache.this.completeEdit(this, true);
                }
                this.committed = true;
            }
        }

        public void abort() throws IOException {
            synchronized (DiskLruCache.this) {
                DiskLruCache.this.completeEdit(this, false);
            }
        }

        public void abortUnlessCommitted() {
            synchronized (DiskLruCache.this) {
                if (!this.committed) {
                    try {
                        DiskLruCache.this.completeEdit(this, false);
                    } catch (IOException e) {
                    }
                }
            }
        }

        private class FaultHidingSink extends ForwardingSink {
            public FaultHidingSink(Sink delegate) {
                super(delegate);
            }

            @Override
            public void write(Buffer source, long byteCount) throws IOException {
                try {
                    super.write(source, byteCount);
                } catch (IOException e) {
                    synchronized (DiskLruCache.this) {
                        Editor.this.hasErrors = true;
                    }
                }
            }

            @Override
            public void flush() throws IOException {
                try {
                    super.flush();
                } catch (IOException e) {
                    synchronized (DiskLruCache.this) {
                        Editor.this.hasErrors = true;
                    }
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } catch (IOException e) {
                    synchronized (DiskLruCache.this) {
                        Editor.this.hasErrors = true;
                    }
                }
            }
        }
    }

    private final class Entry {
        private final File[] cleanFiles;
        private Editor currentEditor;
        private final File[] dirtyFiles;
        private final String key;
        private final long[] lengths;
        private boolean readable;
        private long sequenceNumber;

        Entry(DiskLruCache x0, String x1, RunnableC12691 x2) {
            this(x1);
        }

        private Entry(String key) {
            this.key = key;
            this.lengths = new long[DiskLruCache.this.valueCount];
            this.cleanFiles = new File[DiskLruCache.this.valueCount];
            this.dirtyFiles = new File[DiskLruCache.this.valueCount];
            StringBuilder fileBuilder = new StringBuilder(key).append(FilenameUtils.EXTENSION_SEPARATOR);
            int truncateTo = fileBuilder.length();
            for (int i = 0; i < DiskLruCache.this.valueCount; i++) {
                fileBuilder.append(i);
                this.cleanFiles[i] = new File(DiskLruCache.this.directory, fileBuilder.toString());
                fileBuilder.append(".tmp");
                this.dirtyFiles[i] = new File(DiskLruCache.this.directory, fileBuilder.toString());
                fileBuilder.setLength(truncateTo);
            }
        }

        public String getLengths() throws IOException {
            StringBuilder result = new StringBuilder();
            long[] arr$ = this.lengths;
            for (long size : arr$) {
                result.append(' ').append(size);
            }
            return result.toString();
        }

        private void setLengths(String[] strings) throws IOException {
            if (strings.length != DiskLruCache.this.valueCount) {
                throw invalidLengths(strings);
            }
            for (int i = 0; i < strings.length; i++) {
                try {
                    this.lengths[i] = Long.parseLong(strings[i]);
                } catch (NumberFormatException e) {
                    throw invalidLengths(strings);
                }
            }
        }

        private IOException invalidLengths(String[] strings) throws IOException {
            throw new IOException("unexpected journal line: " + Arrays.toString(strings));
        }
    }
}

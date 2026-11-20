package co.vine.android.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;
import co.vine.android.Settings;
import co.vine.android.client.AppController;
import com.edisonwang.android.slog.SLog;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

public class LoopManager {
    private static final String TAG = "VineLoopManager";
    private static final long VIEWING_ID_EXPIRATION = 120000;
    private static LoopManager sInstance;
    private final Context mContext;
    private final String mLoopPreferenceName;
    private final SharedPreferences mPrefs;
    private LinkedList<Record> mRecords = new LinkedList<>();
    private long mViewingId = 0;
    private long mViewingIdSetTime;
    private static final int[] LOCK = new int[0];
    private static final boolean DEBUGABLE = BuildUtil.isLogsOn();
    private static final LongSparseArray<Integer> sCounts = new LongSparseArray<>();

    public static int getLocalLoopCount(long postId) {
        return sCounts.get(postId, 0).intValue();
    }

    public static class Record implements Parcelable {
        public static final Parcelable.Creator<Record> CREATOR = new C05291();
        public int loopCount;
        public long postId;
        public long timeStamp;
        public long userId;

        Record(Parcel x0, Object x1) {
            this(x0);
        }

        public Record(long userId, long postId) {
            this.userId = userId;
            this.postId = postId;
            this.loopCount = 0;
        }

        private Record() {
        }

        public void increment() {
            this.loopCount++;
            this.timeStamp = System.currentTimeMillis();
            int currentCount = ((Integer) LoopManager.sCounts.get(this.postId, -1)).intValue();
            if (currentCount == -1) {
                LoopManager.sCounts.put(this.postId, 1);
            } else {
                LoopManager.sCounts.put(this.postId, Integer.valueOf(currentCount + 1));
            }
        }

        public String toSerializedString() {
            return String.valueOf(this.userId) + CommonUtil.MARKER_COLON + this.postId + CommonUtil.MARKER_COLON + this.loopCount + CommonUtil.MARKER_COLON + this.timeStamp;
        }

        public static Record fromSerializedString(String s) {
            String[] spl = s.split(":");
            if (spl.length != 4) {
                return null;
            }
            Record r = new Record();
            r.userId = Long.parseLong(spl[0]);
            r.postId = Long.parseLong(spl[1]);
            r.loopCount = Integer.parseInt(spl[2]);
            r.timeStamp = Long.parseLong(spl[3]);
            return r;
        }

        public String toString() {
            return toSerializedString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int i) {
            parcel.writeLong(this.userId);
            parcel.writeLong(this.postId);
            parcel.writeInt(this.loopCount);
            parcel.writeLong(this.timeStamp);
        }

        private Record(Parcel in) {
            this.userId = in.readLong();
            this.postId = in.readLong();
            this.loopCount = in.readInt();
            this.timeStamp = in.readLong();
        }

        static class C05291 implements Parcelable.Creator<Record> {
            C05291() {
            }

            @Override
            public Record createFromParcel(Parcel in) {
                return new Record(in, (Object) null);
            }

            @Override
            public Record[] newArray(int size) {
                return new Record[size];
            }
        }
    }

    private LoopManager(Context context) {
        this.mPrefs = Util.getDefaultSharedPrefs(context);
        this.mContext = context;
        this.mLoopPreferenceName = SystemUtil.isRunningOnServiceProcess(context) ? Settings.PREF_LOOP_COUNT_RECORDS : Settings.PREF_LOOP_COUNT_RECORDS + SystemUtil.getSubProcessName(context);
        Set<String> savedLoops = this.mPrefs.getStringSet(this.mLoopPreferenceName, Collections.emptySet());
        if (savedLoops != null) {
            if (SLog.sLogsOn) {
                SLog.m466i("LoopManager loaded {} loops from {}", Integer.valueOf(savedLoops.size()), SystemUtil.getSubProcessName(context));
            }
            for (String s : savedLoops) {
                addRecord(Record.fromSerializedString(s));
            }
        }
    }

    public static LoopManager get(Context context) {
        synchronized (LOCK) {
            if (sInstance == null) {
                sInstance = new LoopManager(context);
            }
        }
        return sInstance;
    }

    public void increment(long postId) {
        Record record;
        synchronized (LOCK) {
            resetViewingIdIfNeeded();
            if (this.mViewingId != postId) {
                record = new Record(AppController.getInstance(this.mContext).getActiveId(), postId);
                addRecord(record);
            } else {
                record = this.mRecords.getLast();
            }
            this.mViewingId = postId;
            this.mViewingIdSetTime = SystemClock.elapsedRealtime();
            record.increment();
            Log.d(TAG, "Incremented " + record.postId + " with count " + record.loopCount);
        }
    }

    private void resetViewingIdIfNeeded() {
        if (SystemClock.elapsedRealtime() - this.mViewingIdSetTime > VIEWING_ID_EXPIRATION) {
            this.mViewingId = 0L;
            this.mViewingIdSetTime = 0L;
        }
    }

    public void addRecord(Record record) {
        synchronized (LOCK) {
            this.mRecords.add(record);
            if (record.loopCount > 0) {
                int currentCount = sCounts.get(record.postId, -1).intValue();
                if (currentCount == -1) {
                    sCounts.put(record.postId, Integer.valueOf(record.loopCount));
                } else {
                    sCounts.put(record.postId, Integer.valueOf(record.loopCount + currentCount));
                }
            }
        }
    }

    public void save() {
        Set<String> recordsToSave = new HashSet<>();
        synchronized (LOCK) {
            for (Record r : this.mRecords) {
                recordsToSave.add(r.toSerializedString());
            }
            if (DEBUGABLE) {
                Log.v(TAG, "Committing these loop records:\n" + toString());
            }
        }
        this.mPrefs.edit().putStringSet(this.mLoopPreferenceName, recordsToSave).apply();
    }

    public List<Record> popPendingLoops() {
        LinkedList<Record> pendingLoops;
        LinkedList<Record> substitute = new LinkedList<>();
        synchronized (LOCK) {
            pendingLoops = this.mRecords;
            this.mRecords = substitute;
            resetViewingIdIfNeeded();
            if (!pendingLoops.isEmpty() && this.mViewingId == pendingLoops.getLast().postId) {
                this.mRecords.add(pendingLoops.removeLast());
            }
        }
        long userId = AppController.getInstance(this.mContext).getActiveId();
        ListIterator<Record> it = pendingLoops.listIterator();
        while (it.hasNext()) {
            Record r = it.next();
            if (r.userId != userId) {
                synchronized (LOCK) {
                    this.mRecords.addFirst(r);
                }
                it.remove();
            }
        }
        return pendingLoops;
    }

    public void onServerSucess() {
        synchronized (LOCK) {
            save();
        }
    }

    public void onServerFailure(List<Record> poppedRecords) {
        synchronized (LOCK) {
            this.mRecords.addAll(0, poppedRecords);
            save();
        }
    }

    public String toString() {
        String string;
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            for (Record r : this.mRecords) {
                sb.append(r.toString()).append('\n');
            }
            string = sb.toString();
        }
        return string;
    }
}

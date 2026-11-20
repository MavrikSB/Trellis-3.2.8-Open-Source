package co.vine.android.service;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import co.vine.android.R;
import co.vine.android.Settings;
import co.vine.android.StartActivity;
import co.vine.android.api.VineEverydayNotification;
import co.vine.android.api.VinePagedData;
import co.vine.android.api.VineSingleNotification;
import co.vine.android.client.AppController;
import co.vine.android.client.AppSessionListener;
import co.vine.android.network.UrlCachePolicy;
import co.vine.android.util.CommonUtil;
import co.vine.android.util.CrashUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ActivityPollJobService extends JobService {
    private static final int JOB_ID = 1001;
    private static final long INTERVAL_MS = 15 * 60 * 1000;
    private static final String PREF_SEEN = "activity_poll_seen";
    private static final int MAX_SEEN = 200;
    private static final String NOTIFY_TAG = "activity_poll";
    private static final int NOTIFY_ID = 1;
    private static final int MAX_LINES = 5;

    public static void schedule(Context context) {
        if (Build.VERSION.SDK_INT < 21) {
            return;
        }
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null || isScheduled(scheduler)) {
                return;
            }
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ActivityPollJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(job);
        } catch (Exception e) {
            CrashUtil.logException(e);
        }
    }

    private static boolean isScheduled(JobScheduler scheduler) {
        if (Build.VERSION.SDK_INT >= 24) {
            JobInfo pending = scheduler.getPendingJob(JOB_ID);
            return pending != null && pending.getIntervalMillis() == INTERVAL_MS;
        }
        for (JobInfo job : scheduler.getAllPendingJobs()) {
            if (job.getId() == JOB_ID && job.getIntervalMillis() == INTERVAL_MS) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
                @Override
                public void run() {
                    doPoll(params);
                }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    private void doPoll(final JobParameters params) {
        final CountDownLatch latch = new CountDownLatch(1);
        AppController controller = null;
        AppSessionListener listener = null;
        try {
            controller = AppController.getInstance(this);
            if (controller == null || !controller.isLoggedInReadOnly() || !Settings.isNotificationEnabled(this)) {
                latch.countDown();
                return;
            }
            final AppController app = controller;
            listener = new AppSessionListener() {
                @Override
                public void onGetActivityComplete(String reqId, int statusCode, String reasonPhrase, VinePagedData<VineEverydayNotification> notifications, VinePagedData<VineSingleNotification> followRequests, UrlCachePolicy policyUsed) {
                    try {
                        handleResult(notifications);
                    } catch (Exception e) {
                        CrashUtil.logException(e);
                    } finally {
                        latch.countDown();
                    }
                }
            };
            app.addListener(listener);
            app.fetchActivity(app.getActiveSession(), 1, 0L, false, false, UrlCachePolicy.NETWORK_ONLY);
            latch.await(90, TimeUnit.SECONDS);
        } catch (Exception e) {
            CrashUtil.logException(e);
        } finally {
            try {
                if (controller != null && listener != null) {
                    controller.removeListener(listener);
                }
            } catch (Exception ignored) {
            }
            jobFinished(params, false);
        }
    }

    private void handleResult(VinePagedData<VineEverydayNotification> notifications) {
        if (notifications == null || notifications.items == null) {
            return;
        }
        SharedPreferences prefs = CommonUtil.getDefaultSharedPrefs(this);
        boolean firstRun = !prefs.contains(PREF_SEEN);
        Set<String> seen = new HashSet<>(prefs.getStringSet(PREF_SEEN, new HashSet<String>()));
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> lines = new ArrayList<>();
        for (VineEverydayNotification item : notifications.items) {
            if (item == null) {
                continue;
            }
            String key = keyOf(item);
            keys.add(key);
            if (!firstRun && !seen.contains(key)) {
                String line = formatLine(item);
                if (line != null && lines.size() < MAX_LINES) {
                    lines.add(line);
                }
            }
        }
        for (String key : seen) {
            if (keys.size() >= MAX_SEEN) {
                break;
            }
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        prefs.edit().putStringSet(PREF_SEEN, new HashSet<>(keys)).apply();
        if (!lines.isEmpty()) {
            postNotification(lines);
        }
    }

    static String keyOf(VineEverydayNotification item) {
        if (item.notificationId != 0) {
            return String.valueOf(item.notificationId);
        }
        long userId = item.user != null ? item.user.userId : 0L;
        long postId = item.post != null ? item.post.postId : 0L;
        return item.type + "|" + userId + "|" + postId + "|" + item.createdAt + "|" + item.comment;
    }

    private String formatLine(VineEverydayNotification item) {
        if (!TextUtils.isEmpty(item.comment)) {
            return item.comment.trim();
        }
        if (item.user == null || TextUtils.isEmpty(item.user.username)) {
            return null;
        }
        String name = item.user.username;
        if (VineEverydayNotification.TYPE_LIKED.equals(item.type)) {
            return String.format(Locale.US, getString(R.string.notif_liked), name);
        }
        if (VineEverydayNotification.TYPE_COMMENTED.equals(item.type) || VineEverydayNotification.TYPE_GROUP_COMMENTED.equals(item.type)) {
            return String.format(Locale.US, getString(R.string.notif_commented), name);
        }
        if (VineEverydayNotification.TYPE_REVINED.equals(item.type)) {
            return String.format(Locale.US, getString(R.string.notif_reposted), name);
        }
        if (VineEverydayNotification.TYPE_FOLLOWED.equals(item.type) || VineEverydayNotification.TYPE_FOLLOW_APPROVED.equals(item.type)) {
            return String.format(Locale.US, getString(R.string.notif_followed), name);
        }
        if (VineEverydayNotification.TYPE_MENTION.equals(item.type) || VineEverydayNotification.TYPE_POST_MENTION.equals(item.type) || VineEverydayNotification.TYPE_COMMENT_MENTION.equals(item.type)) {
            return String.format(Locale.US, getString(R.string.notif_mentioned), name);
        }
        return null;
    }

    private void postNotification(ArrayList<String> lines) {
        NotificationChannels.ensure(this);
        String title = getString(R.string.app_name);
        String text = lines.size() == 1 ? lines.get(0) : String.format(Locale.US, getString(R.string.notif_summary), lines.size());
        Intent intent = new Intent(this, StartActivity.class);
        intent.setAction(StartActivity.ACTION_ACTIVITY_NOTIFICATION_PRESSED);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        SharedPreferences prefs = CommonUtil.getDefaultSharedPrefs(this);
        int defaults = 0;
        Uri sound = null;
        if (prefs.getBoolean(Settings.PREF_NOTIFICATIONS_SOUND, true)) {
            String ringtone = prefs.getString(Settings.PREF_NOTIFICATIONS_RINGTONE, "");
            if (!TextUtils.isEmpty(ringtone)) {
                sound = Uri.parse(ringtone);
            } else {
                defaults |= Notification.DEFAULT_SOUND;
            }
        }
        if (prefs.getBoolean(Settings.PREF_NOTIFICATIONS_VIBRATE, false)) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }
        if (prefs.getBoolean(Settings.PREF_NOTIFICATIONS_LIGHT, true)) {
            defaults |= Notification.DEFAULT_LIGHTS;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder builder = new Notification.Builder(this, NotificationChannels.ACTIVITY)
                    .setSmallIcon(R.drawable.ic_vine_status_bar)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            if (sound != null) {
                builder.setSound(sound);
            }
            builder.setDefaults(defaults);
            if (lines.size() > 1) {
                Notification.InboxStyle style = new Notification.InboxStyle();
                for (String line : lines) {
                    style.addLine(line);
                }
                style.setSummaryText(text);
                builder.setStyle(style);
            }
            manager.notify(NOTIFY_TAG, NOTIFY_ID, builder.build());
        } else {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.ic_vine_status_bar)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            if (sound != null) {
                builder.setSound(sound);
            }
            builder.setDefaults(defaults);
            if (lines.size() > 1) {
                NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
                for (String line : lines) {
                    style.addLine(line);
                }
                style.setSummaryText(text);
                builder.setStyle(style);
            }
            manager.notify(NOTIFY_TAG, NOTIFY_ID, builder.build());
        }
    }
}

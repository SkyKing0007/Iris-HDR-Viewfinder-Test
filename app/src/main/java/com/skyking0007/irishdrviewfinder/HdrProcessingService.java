package com.skyking0007.irishdrviewfinder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * V2.24 background lifetime lease for already-captured HDR post-processing.
 *
 * This service intentionally owns no image math. CaptureSetSaver starts it only after
 * V2.23 GPU fusion has produced independent fused JPEG bytes and both acquired RAW
 * Image objects have been released. It keeps the process foreground-eligible while
 * optional NAFNet and final file writes finish, then stops immediately.
 */
public final class HdrProcessingService extends Service {
    private static final String CHANNEL_ID = "iris_hdr_processing";
    private static final int NOTIFICATION_ID = 224;
    private static final String ACTION_START = "iris.action.HDR_PROCESSING_START";
    private static final String EXTRA_CAPTURE_ID = "captureId";

    static void start(Context context, String captureId) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, HdrProcessingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CAPTURE_ID, captureId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    static void stop(Context context) {
        context.getApplicationContext().stopService(
                new Intent(context.getApplicationContext(), HdrProcessingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        RuntimeLogger.event("HDR_PROCESSING_SERVICE", "created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String captureId = intent == null ? null : intent.getStringExtra(EXTRA_CAPTURE_ID);
        if (captureId == null || captureId.isEmpty()) captureId = "HDR capture";
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Iris HDR processing")
                .setContentText("Finishing " + captureId)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        RuntimeLogger.event("HDR_PROCESSING_SERVICE", "foreground capture=" + captureId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        RuntimeLogger.event("HDR_PROCESSING_SERVICE", "destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "HDR processing",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps an already-captured HDR image processing after leaving Iris.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}

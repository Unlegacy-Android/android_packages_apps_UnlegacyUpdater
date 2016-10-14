package org.unlegacy_android.updater;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;

import java.io.File;

public class SystemUpdateNotify {
    public static void notifyDownloadComplete(Context context,
                                              Intent updateIntent, File updateFile) {

        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle()
                .setBigContentTitle(context.getString(R.string.system_update_update_downloaded_notification_title))
                .bigText(context.getString(R.string.system_update_requires_restart_status_text));

        NotificationCompat.Builder builder = createBaseContentBuilder(context, updateIntent)
                .setSmallIcon(R.mipmap.notification_system_update_available)
                .setContentTitle(context.getString(R.string.system_update_update_downloaded_notification_title))
                .setContentText(context.getString(R.string.system_update_activity_title))
                .setTicker(context.getString(R.string.system_update_update_downloaded_notification_title))
                .setStyle(style)
                .addAction(R.mipmap.ic_dialog_system_update,
                        context.getString(R.string.system_update_update_downloaded_install_message),
                        createInstallPendingIntent(context, updateFile));

        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(R.string.system_update_update_downloaded_notification_title, builder.build());
    }

    public static void notifyDownloadError(Context context,
                                           Intent updateIntent, int failureMessageResId) {
        NotificationCompat.Builder builder = createBaseContentBuilder(context, updateIntent)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.system_update_nonmandatory_update_download_failure_notification_title))
                .setContentText(context.getString(failureMessageResId))
                .setTicker(context.getString(R.string.system_update_nonmandatory_update_download_failure_notification_title))
                .addAction(R.mipmap.ic_dialog_system_update,
                        context.getString(R.string.system_update_download_retry_button_text),
                        openSystemUpdateActivity(context));

        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(R.string.system_update_update_downloaded_notification_title, builder.build());
    }

    private static NotificationCompat.Builder createBaseContentBuilder(Context context,
                                                                       Intent updateIntent) {
        PendingIntent contentIntent = PendingIntent.getActivity(context, 1,
                updateIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(context)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
                .setLocalOnly(true)
                .setAutoCancel(true);
    }

    private static PendingIntent openSystemUpdateActivity(Context context) {
        Intent openSystemUpdateActivity = new Intent(context, SystemUpdateActivity.class);
        return PendingIntent.getActivity(context, 0,
                openSystemUpdateActivity, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent createInstallPendingIntent(Context context, File updateFile) {
        Intent installIntent = new Intent(context, SystemUpdateReceiver.class);
        installIntent.setAction(SystemUpdateReceiver.ACTION_INSTALL_UPDATE);
        installIntent.putExtra(SystemUpdateReceiver.EXTRA_FILEPATH, updateFile.getAbsolutePath());

        return PendingIntent.getBroadcast(context, 0,
                installIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

package org.unlegacy_android.updater;

import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Parcelable;
import android.os.RecoverySystem;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;

import static android.content.Intent.ACTION_BOOT_COMPLETED;

public class SystemUpdateReceiver extends BroadcastReceiver {
    // Action filters
    public static final String ACTION_START_DOWNLOAD = "org.unlegacy_android.updater.action.START_DOWNLOAD";
    public static final String ACTION_DOWNLOAD_STARTED = "org.unlegacy_android.updater.action.DOWNLOAD_STARTED";
    public static final String ACTION_DOWNLOAD_COMPLETE = "org.unlegacy_android.updater.action.ACTION_DOWNLOAD_COMPLETE";
    public static final String ACTION_INSTALL_PREPARED = "org.unlegacy_android.updater.action.ACTION_INSTALL_PREPARED";
    public static final String ACTION_INSTALL_UPDATE = "org.unlegacy_android.updater.action.INSTALL_UPDATE";

    // Extras
    public static final String EXTRA_UPDATE_INFO = "update_info";
    public static final String EXTRA_FILEPATH = "filename";
    private static final String TAG = SystemUpdateReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.v(TAG, "onReceive(): action=" + action);

        if ( ACTION_START_DOWNLOAD.equals(action) ) {
            UpdateInfo ui = intent.getParcelableExtra(EXTRA_UPDATE_INFO);
            handleDownloadStart(context, ui);
        } else if ( DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action) ) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            handleDownloadCompleted(context, id);
        } else if ( ACTION_INSTALL_UPDATE.equals(action) ) {
            String updatePackagePath = intent.getStringExtra(EXTRA_FILEPATH);
            handleInstallUpdate(context, updatePackagePath);
        } else if ( ACTION_BOOT_COMPLETED.equals(action) ) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent alarmIntent = new Intent(context, SystemUpdateService.class);
            alarmIntent.setAction(SystemUpdateService.ACTION_UPDATE_CHECK);
            PendingIntent pendingIntent = PendingIntent.getService(context, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, 0, 48 * 60 * 60 * 1000, pendingIntent); // Check for updates every 48 hours
            context.startService(alarmIntent);
        }
    }

    private void handleDownloadStart(Context context, UpdateInfo ui) {
        Log.v(TAG, "handleDownloadStart(): Intent SystemUpdateService to start the download");
        Intent intent = new Intent(context, SystemUpdateService.class);
        intent.setAction(ACTION_START_DOWNLOAD);
        intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) ui);
        context.startService(intent);
    }

    private void handleDownloadCompleted(Context context, long downloadId) {
        Log.v(TAG, "handleDownloadCompleted(): Intent SystemUpdateService to verify if installation can continue");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        long enqueued = prefs.getLong(Utils.DOWNLOAD_ID, -1);
        if ( enqueued < 0 || downloadId < 0 || downloadId != enqueued ) {
            return;
        }

        String downloadedMD5 = prefs.getString(Utils.DOWNLOAD_MD5, "");

        Intent intent = new Intent(context, SystemUpdateService.class);
        intent.setAction(ACTION_DOWNLOAD_COMPLETE);
        intent.putExtra(Utils.DOWNLOAD_ID, downloadId);
        intent.putExtra(Utils.DOWNLOAD_MD5, downloadedMD5);
        context.startService(intent);

        prefs.edit()
                .remove(Utils.DOWNLOAD_MD5)
                .remove(Utils.DOWNLOAD_ID)
                .apply();
    }

    private void handleInstallUpdate(Context context, String updatePackagePath) {
        try {
            Log.v(TAG, "handleInstallUpdate(): Trying to launch update...");
            File otaPackage = new File(updatePackagePath);
            RecoverySystem.installPackage(context, otaPackage);
            Log.v(TAG, "handleInstallUpdate(): Update launched! Rebooting to recovery...");
        } catch (IOException e) {
            Log.e(TAG, "handleInstallUpdate(): Unable to launch update.", e);
        }
    }
}
package unlegacy.android.update.service;

import android.app.DownloadManager;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.File;

import unlegacy.android.update.R;
import unlegacy.android.update.Utils;
import unlegacy.android.update.model.UpdateInfo;
import unlegacy.android.update.notify.DownloadNotifier;
import unlegacy.android.update.receiver.DownloadReceiver;

import static unlegacy.android.update.SystemUpdates.*;
import static unlegacy.android.update.receiver.DownloadReceiver.*;

public class DownloadService extends IntentService {

    private static final String TAG = DownloadService.class.getName();

    // Internal extras
    private static final String EXTRA_UPDATE_INFO = "update_Info";

    private DownloadManager mDownloadManager;
    private SharedPreferences mPrefs;

    public DownloadService() {
        super(DownloadService.class.getSimpleName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    public static void startService(Context context, UpdateInfo ui) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_START_DOWNLOAD);
        intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) ui);
        context.startService(intent);
    }

    public static void startService(Context context, long downloadedId, String downloadedMD5) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(ACTION_DOWNLOAD_COMPLETE);
        intent.putExtra(Utils.DOWNLOAD_ID, downloadedId);
        intent.putExtra(Utils.DOWNLOAD_MD5, downloadedMD5);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();
        Log.v(TAG, "onHandleIntent(): action=" + action);

        if (ACTION_START_DOWNLOAD.equals(action))
            downloadStart(intent);
        else if (ACTION_DOWNLOAD_COMPLETE.equals(action))
            downloadCompleted(intent);
    }

    private long enqueueDownload(String downloadUrl, String localFilePath) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        String userAgent = Utils.getUserAgentString(this);
        if (userAgent != null) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        request.setTitle(getString(R.string.app_name));
        request.setDestinationUri(Uri.parse(localFilePath));
        request.setAllowedOverRoaming(false);
        request.setVisibleInDownloadsUi(false);
        request.setAllowedOverMetered(true);

        final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        return dm.enqueue(request);
    }

    private void downloadStart(Intent intent) {
        UpdateInfo mUpdateInfo;
        String uriSchema = "file://";
        String fileName = "/update.zip.partial";
        String cachePath = uriSchema + Environment.getDownloadCacheDirectory().getAbsolutePath() + fileName;
        String appPath = uriSchema + Environment.getExternalStorageDirectory().getAbsolutePath() + fileName;
        String finalPath;
        long downloadId;
        Intent downloadStartedIntent;

        Log.v(TAG, "downloadStart(): Initialized");
        if (!Utils.isOnline(this)) {
            // Only check for updates if the device is actually connected to a network
            Log.i(TAG, "downloadStart(): Could not check for updates. Not connected to the network");
            return;
        }

        mUpdateInfo = intent.getParcelableExtra(EXTRA_UPDATE_INFO);
        // Check if we have the updateInfo filled
        if (mUpdateInfo == null) {
            Log.e(TAG, "downloadStart(): Intent UpdateInfo extras were null");
            return;
        }

        try {
            // Try to enqueue to cache
            finalPath = cachePath;
            downloadId = enqueueDownload(mUpdateInfo.getUrl(), finalPath);
            Log.v(TAG, "downloadStart(): Enqueue download to cache");
        } catch (Exception ignore) {
            // Else we download to storage
            finalPath = appPath;
            downloadId = enqueueDownload(mUpdateInfo.getUrl(), finalPath);
            Log.v(TAG, "downloadStart(): Enqueue download to storage");
        }

        Log.v(TAG, "downloadStart(): Saving download details to PreferenceManager");
        mPrefs.edit()
                .putLong(Utils.DOWNLOAD_ID, downloadId)
                .putString(Utils.DOWNLOAD_MD5, mUpdateInfo.getMd5())
                .putString(Utils.DOWNLOAD_FILEPATH, finalPath)
                .apply();

        Log.v(TAG, "downloadStart(): Intent DownloadReceiver that download just started");
        downloadStartedIntent = new Intent(ACTION_DOWNLOAD_STARTED);
        downloadStartedIntent.putExtra(Utils.DOWNLOAD_ID, downloadId);
        sendBroadcast(downloadStartedIntent);
    }

    @Nullable
    private String fetchDownloadPartialPath(long id) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        try (Cursor c = mDownloadManager.query(query)) {
            if (c.moveToFirst())
                return Uri.parse(c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))).getPath();
        }
        return null;
    }

    private int fetchDownloadStatus(long id) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        try (Cursor c = mDownloadManager.query(query)) {
            if (c.moveToFirst()) {
                return c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }
        }
        return DownloadManager.STATUS_FAILED;
    }

    private void displayErrorResult(Intent updateIntent, int failureMessageResId) {
        DownloadNotifier.notifyDownloadError(this, updateIntent, failureMessageResId);
    }

    private void displaySuccessResult(Intent updateIntent, File updateFile) {
        if ( isActivityVisible()) sendBroadcast(updateIntent);
        else {
            DownloadNotifier.notifyDownloadComplete(this, updateIntent, updateFile);
        }
    }

    protected void downloadCompleted(Intent intent) {
        if (!intent.hasExtra(Utils.DOWNLOAD_ID) ||
                !intent.hasExtra(Utils.DOWNLOAD_MD5)) {
            Log.e(TAG, "downloadCompleted(): Intent extras are missing, returning");
            return;
        }

        String completedFileFullPath;
        String downloadedMD5;
        int status;
        long id;
        File partialFile;
        File updateFile;
        Intent downloadCompletedIntent;

        downloadedMD5 = intent.getStringExtra(Utils.DOWNLOAD_MD5);
        downloadCompletedIntent = new Intent();
        downloadCompletedIntent.setAction(DownloadReceiver.ACTION_INSTALL_PREPARED);

        id = intent.getLongExtra(Utils.DOWNLOAD_ID, -1);
        status = fetchDownloadStatus(id);
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            Log.v(TAG, "downloadCompleted(): Download was a success");
            // Strip off the .partial at the end to get the completed file
            String partialFileFullPath = fetchDownloadPartialPath(id);

            if (partialFileFullPath == null) {
                Log.v(TAG, "downloadCompleted(): Failed to fetch partial download file path");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_title);
            }

            try {
                completedFileFullPath = partialFileFullPath.replace(".partial", "");
            } catch (Exception ignored) {
                Log.v(TAG, "downloadCompleted(): Internal error occurred trying to replace .partial in partialFileFullPath");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_title);
                return;
            }

            partialFile = new File(partialFileFullPath);
            updateFile = new File(completedFileFullPath);
            if (!partialFile.renameTo(updateFile)) {
                Log.v(TAG, "downloadCompleted(): Failed to rename " + partialFile.getName() + " to " + updateFile.getName() + ", aborting");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_title);
                return;
            }

            Log.v(TAG, "downloadCompleted(): Checking MD5...");
            // Start the MD5 check of the downloaded file
            if (Utils.checkMD5(downloadedMD5, updateFile)) {
                Log.v(TAG, "downloadCompleted(): MD5 check match, move on");
                // We passed. Bring the main app to the foreground and trigger download completed
                downloadCompletedIntent.putExtra(Utils.DOWNLOAD_ID, id);
                downloadCompletedIntent.putExtra(Utils.DOWNLOAD_FILEPATH,
                        completedFileFullPath);
                Log.v(TAG, "downloadCompleted(): Notify that MD5 verification was successful");
                displaySuccessResult(downloadCompletedIntent, updateFile);
            } else {
                Log.v(TAG, "downloadCompleted(): MD5 check failed, cleaning...");
                // We failed. Clear the file and reset everything
                mDownloadManager.remove(id);

                if (updateFile.exists()) {
                    if (updateFile.delete())
                        Log.v(TAG, "downloadCompleted(): Since download failed due MD5 check fail, we deleted the corrupted file");
                }
                Log.v(TAG, "downloadCompleted(): Notifying that MD5 check has failed");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_verification_failed_text);
            }
        } else if (status == DownloadManager.STATUS_FAILED) {
            Log.v(TAG, "downloadCompleted(): Download has failed");
            // The download failed, reset
            mDownloadManager.remove(id);
            displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_title);
        }
    }
}

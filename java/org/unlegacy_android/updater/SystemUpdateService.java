package org.unlegacy_android.updater;

import android.accounts.NetworkErrorException;
import android.app.DownloadManager;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

import static org.unlegacy_android.updater.SystemUpdate.isActivityVisible;
import static org.unlegacy_android.updater.SystemUpdateReceiver.ACTION_DOWNLOAD_COMPLETE;
import static org.unlegacy_android.updater.SystemUpdateReceiver.ACTION_DOWNLOAD_STARTED;
import static org.unlegacy_android.updater.SystemUpdateReceiver.ACTION_START_DOWNLOAD;
import static org.unlegacy_android.updater.SystemUpdateReceiver.EXTRA_UPDATE_INFO;

public class SystemUpdateService extends IntentService {

    // request actions
    public static final String ACTION_UPDATE_CHECK = "org.unlegacy_android.updater.action.UPDATE_CHECK";
    public static final String ACTION_UPDATE_CHECK_CANCEL = "org.unlegacy_android.updater.action.UPDATE_CHECK_CANCEL";
    // broadcast actions
    public static final String ACTION_UPDATE_CHECK_FINISHED = "org.unlegacy_android.updater.action.UPDATE_CHECK_FINISHED";
    public static final String ACTION_UPDATE_CHECK_FAILED = "org.unlegacy_android.updater.action.UPDATE_CHECK_FAILED";
    private static final String TAG = SystemUpdateService.class.getName();
    private DownloadManager mDownloadManager;
    private SharedPreferences mPrefs;

    public SystemUpdateService() {
        super(SystemUpdateService.class.getSimpleName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ( ACTION_UPDATE_CHECK_CANCEL.equals(intent.getAction()) ) {
            this.stopSelf();
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        String action = intent.getAction();
        Log.v(TAG, "onHandleIntent(): action=" + action);

        if ( ACTION_UPDATE_CHECK.equals(action) )
            updateCheck(intent);
        else if ( ACTION_START_DOWNLOAD.equals(action) )
            downloadStart(intent);
        else if ( ACTION_DOWNLOAD_COMPLETE.equals(action) )
            downloadCompleted(intent);
    }

    private void updateCheck(Intent intent) {

        Log.d(TAG, "updateCheck(): Start getting UpdateInfo...");
        URL url;
        String response = "";
        UpdateInfo updateInfo;

        try {
            if ( !Utils.isOnline(this) ) {
                // Only check for updates if the device is actually connected to a network
                Log.i(TAG, "onHandleIntent: Could not check for updates. Not connected to the network.");
                throw new NetworkErrorException("Could not check for updates. Not connected to the network.");
            }

            // Parameters
            Uri.Builder builder = new Uri.Builder()
                    .appendQueryParameter(UpdateInfo.DEVICE, Build.DEVICE.toLowerCase())
                    .appendQueryParameter(UpdateInfo.VERSION, Build.VERSION.RELEASE)
                    .appendQueryParameter(UpdateInfo.INCREMENTAL, Build.VERSION.INCREMENTAL)
                    .appendQueryParameter(UpdateInfo.DATE, Long.toString(Build.TIME));
            String query = builder.build().getEncodedQuery();

            // Setup HttpURLConnection
            url = new URL(getString(R.string.server_update_check_url) + "?" + query);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setReadTimeout(10000);
            urlConnection.setConnectTimeout(15000);

            int responseCode = urlConnection.getResponseCode();
            Log.d(TAG, "updateCheck(): OTA Server http response code: " + responseCode);
            if ( responseCode == HttpsURLConnection.HTTP_OK ) {
                String line;
                BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                while ((line = br.readLine()) != null)
                    response += line;

                JSONObject json = new JSONObject(response);
                if ( json.has("error") ) {
                    updateInfo = null;
                    Log.e(TAG, "updateCheck(): JSONObject(response) failed.");
                } else {
                    updateInfo = new UpdateInfo(
                            json.getString(UpdateInfo.DEVICE),
                            json.getString(UpdateInfo.VERSION),
                            json.getLong(UpdateInfo.DATE),
                            json.getString(UpdateInfo.DESCRIPTION),
                            json.getString(UpdateInfo.URL),
                            json.getString(UpdateInfo.MD5),
                            json.getBoolean(UpdateInfo.IS_INCREMENTAL));
                    Log.v(TAG, "updateCheck(): UpdateInfo.toString()\n" + updateInfo.toString());
                }
            } else {
                updateInfo = null;
                Log.e(TAG, "updateCheck(): responseCode <> HttpsURLConnection.HTTP_OK");
            }

        } catch (Exception e) {
            updateInfo = null;
            Log.e(TAG, "updateCheck(): Get UpdateInfo has failed.", e);
        }
        intent = new Intent();
        if ( updateInfo != null ) {
            intent.setAction(ACTION_UPDATE_CHECK_FINISHED);
            intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) updateInfo);
        } else
            intent.setAction(ACTION_UPDATE_CHECK_FAILED);
        sendBroadcast(intent);
    }

    private long enqueueDownload(String downloadUrl, String localFilePath) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        String userAgent = Utils.getUserAgentString(this);
        if ( userAgent != null ) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        request.setTitle(getString(R.string.system_update_activity_name));
        request.setDestinationUri(Uri.parse(localFilePath));
        request.setAllowedOverRoaming(false);
        request.setVisibleInDownloadsUi(false);
        request.setAllowedOverMetered(true);
        //request.setDestinationToSystemCache();

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
        if ( !Utils.isOnline(this) ) {
            // Only check for updates if the device is actually connected to a network
            Log.i(TAG, "downloadStart(): Could not check for updates. Not connected to the network");
            return;
        }

        mUpdateInfo = intent.getParcelableExtra(EXTRA_UPDATE_INFO);
        // Check if we have the updateInfo filled
        if ( mUpdateInfo == null ) {
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

        Log.v(TAG, "downloadStart(): Intent SystemUpdateReceiver that download just started");
        downloadStartedIntent = new Intent(ACTION_DOWNLOAD_STARTED);
        downloadStartedIntent.putExtra(Utils.DOWNLOAD_ID, downloadId);
        sendBroadcast(downloadStartedIntent);
    }

    @Nullable
    private String fetchDownloadPartialPath(long id) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        try (Cursor c = mDownloadManager.query(query)) {
            if ( c.moveToFirst() )
                return Uri.parse(c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))).getPath();
        }
        return null;
    }

    private int fetchDownloadStatus(long id) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        try (Cursor c = mDownloadManager.query(query)) {
            if ( c.moveToFirst() ) {
                return c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }
        }
        return DownloadManager.STATUS_FAILED;
    }

    private void displayErrorResult(Intent updateIntent, int failureMessageResId) {
        if ( !isActivityVisible() )
            SystemUpdateNotify.notifyDownloadError(this, updateIntent, failureMessageResId);
        sendBroadcast(updateIntent);
    }

    private void displaySuccessResult(Intent updateIntent, File updateFile) {
        if ( !isActivityVisible() )
            SystemUpdateNotify.notifyDownloadComplete(this, updateIntent, updateFile);
        sendBroadcast(updateIntent);
    }

    protected void downloadCompleted(Intent intent) {
        if ( !intent.hasExtra(Utils.DOWNLOAD_ID) ||
                !intent.hasExtra(Utils.DOWNLOAD_MD5) ) {
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
        downloadCompletedIntent.setAction(SystemUpdateReceiver.ACTION_INSTALL_PREPARED);

        id = intent.getLongExtra(Utils.DOWNLOAD_ID, -1);
        status = fetchDownloadStatus(id);
        if ( status == DownloadManager.STATUS_SUCCESSFUL ) {
            Log.v(TAG, "downloadCompleted(): Download was a success");
            // Strip off the .partial at the end to get the completed file
            String partialFileFullPath = fetchDownloadPartialPath(id);

            if ( partialFileFullPath == null ) {
                Log.v(TAG, "downloadCompleted(): Failed to fetch partial download file path");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_message);
            }

            try {
                completedFileFullPath = partialFileFullPath.replace(".partial", "");
            } catch (Exception ignored) {
                Log.v(TAG, "downloadCompleted(): Internal error occurred trying to replace .partial in partialFileFullPath");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_message);
                return;
            }

            partialFile = new File(partialFileFullPath);
            updateFile = new File(completedFileFullPath);
            if ( !partialFile.renameTo(updateFile) ) {
                Log.v(TAG, "downloadCompleted(): Failed to rename " + partialFile.getName() + " to " + updateFile.getName() + ", aborting");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_message);
                return;
            }

            Log.v(TAG, "downloadCompleted(): Checking MD5...");
            // Start the MD5 check of the downloaded file
            if ( Utils.checkMD5(downloadedMD5, updateFile) ) {
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

                if ( updateFile.exists() ) {
                    if ( updateFile.delete() )
                        Log.v(TAG, "downloadCompleted(): Since download failed due MD5 check fail, we deleted the corrupted file");
                }
                Log.v(TAG, "downloadCompleted(): Notifying that MD5 check has failed");
                displayErrorResult(downloadCompletedIntent, R.string.system_update_verification_failed_text);
            }
        } else if ( status == DownloadManager.STATUS_FAILED ) {
            Log.v(TAG, "downloadCompleted(): Download has failed");
            // The download failed, reset
            mDownloadManager.remove(id);
            displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_message);
        }
    }
}

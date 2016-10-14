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
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.TimeZone;

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
        LinkedList<UpdateInfo> updates = new LinkedList<UpdateInfo>();
        intent = new Intent();

        try {
            if ( !Utils.isOnline(this) ) {
                // Only check for updates if the device is actually connected to a network
                Log.i(TAG, "onHandleIntent: Could not check for updates. Not connected to the network.");
                throw new NetworkErrorException("Could not check for updates. Not connected to the network.");
            }

            // Url
            StringBuilder strUrl = new StringBuilder()
                    .append(getString(R.string.server_update_check_url))
                    .append("/v1/")
                    .append(Build.DEVICE.toLowerCase())
                    .append("/release/")
                    .append(Build.VERSION.INCREMENTAL);

            // Parameters
            Uri.Builder builder = new Uri.Builder()
                    .appendQueryParameter(UpdateInfo.AFTER, Utils.getDateFromEpoch(Build.TIME, TimeZone.getTimeZone("GMT")));

            // Setup HttpURLConnection
            url = new URL(strUrl.toString() + "?" + builder.build().getEncodedQuery());
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
                JSONArray updateList = json.getJSONArray("response");
                int length = updateList.length();

                Log.d(TAG, "Got update JSON data with " + length + " entries");

                for (int i = 0; i < length; i++) {
                    if (updateList.isNull(i)) {
                        continue;
                    }
                    JSONObject item = updateList.getJSONObject(i);
                    UpdateInfo info = parseUpdateJSONObject(item);
                    if (info != null) {
                        updates.add(info);
                    }
                }
            } else {
                Log.e(TAG, "updateCheck(): responseCode <> HttpsURLConnection.HTTP_OK");
            }
            intent.setAction(ACTION_UPDATE_CHECK_FINISHED);
            if (!updates.isEmpty())
                intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) updates.getFirst());
            else
                intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) null);
        } catch (Exception e) {
            intent.setAction(ACTION_UPDATE_CHECK_FAILED);
            Log.e(TAG, "updateCheck(): Get UpdateInfo has failed.", e);
        }
        if (!updates.isEmpty())
            displayUpdateAvailable(intent);
        sendBroadcast(intent);
    }

    private UpdateInfo parseUpdateJSONObject(JSONObject obj) throws JSONException {
        UpdateInfo ui = new UpdateInfo(
                Build.DEVICE.toLowerCase(),
                obj.getString(UpdateInfo.VERSION),
                obj.getLong(UpdateInfo.DATE),
                obj.getString(UpdateInfo.URL),
                obj.getString(UpdateInfo.MD5));
        if (!ui.isUpdatable()) {
            Log.d(TAG, "updateCheck(): Build is older than the installed build:\n"+ui.toString());
            return null;
        }
        return ui;
    }

    private long enqueueDownload(String downloadUrl) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        String userAgent = Utils.getUserAgentString(this);
        if ( userAgent != null ) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        request.setTitle(getString(R.string.system_update_activity_name));
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

        downloadId = enqueueDownload(mUpdateInfo.getUrl());
        Log.v(TAG, "downloadStart(): Enqueue download with id: "+downloadId);

        Log.v(TAG, "downloadStart(): Saving download details to PreferenceManager");
        mPrefs.edit()
                .putLong(Utils.DOWNLOAD_ID, downloadId)
                .putString(Utils.DOWNLOAD_MD5, mUpdateInfo.getMd5())
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

    private void displayUpdateAvailable(Intent updateIntent) {
        if ( !isActivityVisible() )
            SystemUpdateNotify.notifyUpdateAvailable(this, updateIntent);
        sendBroadcast(updateIntent);
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

        String downloadedMD5;
        int status;
        long id;
        File updateFile;
        Intent downloadCompletedIntent;

        downloadedMD5 = intent.getStringExtra(Utils.DOWNLOAD_MD5);
        downloadCompletedIntent = new Intent();
        downloadCompletedIntent.setAction(SystemUpdateReceiver.ACTION_INSTALL_PREPARED);

        id = intent.getLongExtra(Utils.DOWNLOAD_ID, -1);
        status = fetchDownloadStatus(id);
        if ( status == DownloadManager.STATUS_SUCCESSFUL ) {
            Log.v(TAG, "downloadCompleted(): Download was a success");
            updateFile = new File(getApplicationContext().getDir("ota", Context.MODE_PRIVATE).getPath() + "/update.zip");
            try (
            FileOutputStream outStream = new FileOutputStream(updateFile);

            ParcelFileDescriptor file = mDownloadManager.openDownloadedFile(id);
            FileInputStream inStream = new FileInputStream(file.getFileDescriptor());

            FileChannel inChannel = inStream.getChannel();
            FileChannel outChannel = outStream.getChannel();
            ) {
                inChannel.transferTo(0, file.getStatSize(), outChannel);
            } catch (Exception e) {
                Log.e(TAG, "downloadCompleted(): Copy of download failed", e);
                displayErrorResult(downloadCompletedIntent, R.string.system_update_nonmandatory_update_download_failure_notification_message);
                if (updateFile.exists()) {
                    updateFile.delete();
                }
                return;
            }

            Log.v(TAG, "downloadCompleted(): Checking MD5...");
            // Start the MD5 check of the downloaded file
            if ( Utils.checkMD5(downloadedMD5, updateFile) ) {
                Log.v(TAG, "downloadCompleted(): MD5 check match, move on");
                // We passed. Bring the main app to the foreground and trigger download completed
                downloadCompletedIntent.putExtra(Utils.DOWNLOAD_ID, id);
                downloadCompletedIntent.putExtra(Utils.DOWNLOAD_FILEPATH,
                        updateFile.getAbsolutePath());
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

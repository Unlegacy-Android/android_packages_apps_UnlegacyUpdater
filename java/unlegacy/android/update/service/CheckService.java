package unlegacy.android.update.service;

import android.app.IntentService;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

import unlegacy.android.update.R;
import unlegacy.android.update.Utils;
import unlegacy.android.update.model.UpdateInfo;

public class CheckService extends IntentService {

    // request actions
    public static final String ACTION_UPDATE_CHECK = "unlegacy.android.update.action.UPDATE_CHECK";
    public static final String ACTION_UPDATE_CHECK_CANCEL = "unlegacy.android.update.action.UPDATE_CHECK_CANCEL";

    // broadcast actions
    public static final String ACTION_UPDATE_CHECK_FINISHED = "unlegacy.android.update.action.UPDATE_CHECK_FINISHED";
    public static final String ACTION_UPDATE_CHECK_FAILED = "unlegacy.android.update.action.UPDATE_CHECK_FAILED";

    // extras
    public static final String EXTRA_UPDATE_INFO = "update_Info";

    private static final String TAG = CheckService.class.getName();

    public CheckService() {
        super("CheckService");
    }

    private static long getFileSize(String fileUrl) {
        URL url;
        URLConnection conn;
        long result;
        try {
            url = new URL(fileUrl);
            conn = url.openConnection();
            conn.setConnectTimeout(10000);
            conn.connect();
            result = Long.parseLong(conn.getHeaderField("content-length"));
        } catch (Exception ignored) {
            result = 0;
        }
        return result;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_UPDATE_CHECK_CANCEL.equals(intent.getAction())) {
            this.stopSelf();
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (!Utils.isOnline(this)) {
            // Only check for updates if the device is actually connected to a network
            Log.i(TAG, "onHandleIntent: Could not check for updates. Not connected to the network.");
            return;
        }
        updateCheck();
    }

    private void updateCheck() {

        Log.d(TAG, "UpdateCheckTask.doInBackground: Start getting UpdateInfo...");
        URL url;
        String response = "";
        UpdateInfo updateInfo;
        try {

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
            Log.d(TAG, "UpdateCheckTask.doInBackground: OTA Server http response code: " + responseCode);
            if (responseCode == HttpsURLConnection.HTTP_OK) {
                String line;
                BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                while ((line = br.readLine()) != null)
                    response += line;

                JSONObject json = new JSONObject(response);
                if (json.has("error")) {
                    updateInfo = null;
                    Log.e(TAG, "UpdateCheckTask.doInBackground: JSONObject(response) failed.");
                } else {
                    updateInfo = new UpdateInfo(
                            json.getString(UpdateInfo.VERSION),
                            json.getString(UpdateInfo.DESCRIPTION),
                            json.getString(UpdateInfo.URL),
                            json.getString(UpdateInfo.MD5),
                            Utils.getDateFromString(json.getString(UpdateInfo.DATE)),
                            getFileSize(json.getString(UpdateInfo.URL)));
                    Log.v(TAG, "UpdateCheckTask.doInBackground: UpdateInfo.toString()\n" + updateInfo.toString());
                }
            } else {
                updateInfo = null;
                Log.e(TAG, "UpdateCheckTask.doInBackground: responseCode <> HttpsURLConnection.HTTP_OK");
            }

        } catch (Exception e) {
            updateInfo = null;
            Log.e(TAG, "UpdateCheckTask.doInBackground: Get UpdateInfo has failed.", e);
        }
        Intent intent = new Intent();
        if (updateInfo != null) {
            intent.setAction(ACTION_UPDATE_CHECK_FINISHED);
            intent.putExtra(EXTRA_UPDATE_INFO, (Parcelable) updateInfo);
        } else
            intent.setAction(ACTION_UPDATE_CHECK_FAILED);
        sendBroadcast(intent);
    }
}

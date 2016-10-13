package unlegacy.android.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityCompat.OnRequestPermissionsResultCallback;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Date;

import unlegacy.android.update.model.UpdateInfo;
import unlegacy.android.update.receiver.DownloadReceiver;
import unlegacy.android.update.service.CheckService;

import static unlegacy.android.update.Utils.LAST_STATE;
import static unlegacy.android.update.Utils.LAST_UPDATE_CHECK_PREF;

public class SystemUpdatesActivity extends Activity implements OnRequestPermissionsResultCallback {

    private static final String TAG = SystemUpdatesActivity.class.getName();

    // Permissions
    private static final int PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 1;
    private static final int PERMISSIONS_REQUEST_FOR_DOWNLOAD = 0;
    private TextView title;
    private ProgressBar progress;
    private TextView size;
    private TextView status;
    private TextView description;
    private Button actionButton;
    private BroadcastReceiver broadcastReceiver;
    private IntentFilter intentFilter;
    private State state;
    private SharedPreferences mPrefs;
    private UpdateInfo updateInfo = null;
    private Handler handler = new Handler();
    private long downloadId;
    private String downloadPath;
    private DownloadManager mDownloadManager;
    private int mPermissionsReqType;
    private Runnable downloadProgress = new Runnable() {
        public void run() {
            if ( downloadId < 0 ) {
                return;
            }

            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(downloadId);

            Cursor cursor = mDownloadManager.query(q);
            int dStatus;

            if ( cursor == null || !cursor.moveToFirst() ) {
                // DownloadReceiver has likely already removed the download
                // from the DB due to failure or MD5 mismatch
                dStatus = DownloadManager.STATUS_FAILED;
            } else {
                dStatus = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }

            switch (dStatus) {
                case DownloadManager.STATUS_PENDING:
                    progress.setIndeterminate(true);
                    break;
                case DownloadManager.STATUS_PAUSED:
                case DownloadManager.STATUS_RUNNING:
                    long downloadedBytes = cursor.getLong(
                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long totalBytes = cursor.getInt(
                            cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                    if ( totalBytes < 0 ) {
                        progress.setIndeterminate(true);
                    } else {
                        progress.setIndeterminate(false);
                        progress.setMax((int) totalBytes);
                        progress.setProgress((int) downloadedBytes);
                        int percentage = (int) ((downloadedBytes * 100) / totalBytes);
                        size.setText(downloadedBytes / 1024 / 1024 + "/" + totalBytes / 1024 / 1024 + "MB");
                        String strPercent = percentage + "%";
                        status.setText(strPercent);
                    }
                    break;
                case DownloadManager.STATUS_FAILED:
                    setState(State.UPDATED_DOWNLOAD_FAILED);
                    downloadId = -1;
                    break;
                case DownloadManager.STATUS_SUCCESSFUL:
                    progress.setProgress(progress.getMax());
                    break;
            }

            if ( cursor != null ) {
                cursor.close();
            }
            if ( dStatus != DownloadManager.STATUS_FAILED &&
                    dStatus != DownloadManager.STATUS_SUCCESSFUL ) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.v(TAG, "onCreate(): Provisioning views");
        setContentView(R.layout.system_update_activity);
        title = (TextView) findViewById(R.id.title);
        progress = (ProgressBar) findViewById(R.id.progress);
        size = (TextView) findViewById(R.id.size);
        status = (TextView) findViewById(R.id.status);
        description = (TextView) findViewById(R.id.description);
        actionButton = (Button) findViewById(R.id.action_button);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart(): Getting DownloadManager service instance");
        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        Log.v(TAG, "onStart(): Getting app SharedPreferences instance");
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        Log.v(TAG, "onStart(): Creating the broadcastReciever instance");
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                Log.v(TAG, "onReceive(): action=" + action);

                if ( CheckService.ACTION_UPDATE_CHECK_FINISHED.equals(action) ) {
                    checkUpdateFinished(intent);
                } else if ( CheckService.ACTION_UPDATE_CHECK_FAILED.equals(action) ) {
                    checkUpdateFailed(intent);
                } else if ( DownloadReceiver.ACTION_DOWNLOAD_STARTED.equals(action) ) {
                    updateDownloadStarted(intent);
                } else if ( DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action) ) {
                    updateDownloadCompleted(intent);
                } else if ( DownloadReceiver.ACTION_INSTALL_PREPARED.equals(action) ) {
                    updateDownloadPrepared(intent);
                }
            }
        };

        Log.v(TAG, "onStart(): Adding action filters to intentFilter");
        intentFilter = new IntentFilter();
        intentFilter.addAction(CheckService.ACTION_UPDATE_CHECK_FINISHED);
        intentFilter.addAction(CheckService.ACTION_UPDATE_CHECK_FAILED);
        intentFilter.addAction(DownloadReceiver.ACTION_DOWNLOAD_STARTED);
        intentFilter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        intentFilter.addAction(DownloadReceiver.ACTION_INSTALL_PREPARED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.v(TAG, "onResume(): Loading state preferences");
        loadPrefs();
        if ( downloadId > -1 ) {
            Log.v(TAG, "onResume(): Resume downloadProgress handler");
            handler.post(downloadProgress);
        }
        Log.v(TAG, "onResume(): Registering broadcastReceiver and its intentFilter");
        registerReceiver(broadcastReceiver, intentFilter);
        Log.v(TAG, "onResume(): Informing app that this activity has been resumed");
        SystemUpdates.activityResumed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.v(TAG, "onPause(): Saving state preferences");
        savePrefs();
        if ( downloadId > -1 ) {
            Log.v(TAG, "onPause(): Removing handler callbacks");
            handler.removeCallbacks(downloadProgress);
        }
        Log.v(TAG, "onPause(): Un-registering broadcastReceiver");
        if ( broadcastReceiver != null ) {
            unregisterReceiver(broadcastReceiver);
            broadcastReceiver = null;
        }
        Log.v(TAG, "onPause(): Informing app that this activity is now suspended");
        SystemUpdates.activityPaused();
    }

    /*
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadId>-1) {
            Log.v(TAG, "onDestroy(): Removing enqueued downloads");
            mDownloadManager.remove(downloadId);
            setState(State.UPDATE_AVAILABLE);
        }
    }
    */

    private void checkUpdate() {
        setState(State.CHECKING);
        Intent checkIntent = new Intent(SystemUpdatesActivity.this, CheckService.class);
        checkIntent.setAction(CheckService.ACTION_UPDATE_CHECK);
        startService(checkIntent);
    }

    private void checkUpdateCancel() {
        setState(State.UNCHECKED);
        Intent cancelIntent = new Intent(SystemUpdatesActivity.this, CheckService.class);
        cancelIntent.setAction(CheckService.ACTION_UPDATE_CHECK_CANCEL);
        stopService(cancelIntent);
    }

    private void checkUpdateFinished(Intent intent) {
        UpdateInfo updateInfo = intent.getParcelableExtra(CheckService.EXTRA_UPDATE_INFO);
        if ( updateInfo.isUpdatable() ) {
            this.updateInfo = updateInfo;
            setState(State.UPDATE_AVAILABLE);
        } else {
            setState(State.UPDATE);
        }
        mPrefs.edit()
                .putLong(LAST_UPDATE_CHECK_PREF, new Date().getTime()).apply();
    }

    private void checkUpdateFailed(Intent intent) {
        setState(State.CHECK_FAILED);
    }

    private void updateDownload() {
        setState(State.UPDATE_DOWNLOADING);
        // Start the download
        Intent intent = new Intent(SystemUpdatesActivity.this, DownloadReceiver.class);
        intent.setAction(DownloadReceiver.ACTION_START_DOWNLOAD);
        intent.putExtra(DownloadReceiver.EXTRA_UPDATE_INFO, (Parcelable) updateInfo);
        sendBroadcast(intent);
    }

    private void updateDownloadStarted(Intent intent) {
        downloadId = intent.getLongExtra(Utils.DOWNLOAD_ID, -1);
        handler.post(downloadProgress);

    }

    private void updateDownloadCompleted(Intent intent) {
        if ( downloadId > -1 )
            setState(State.UPDATE_DOWNLOADED);

    }

    private void updateDownloadPrepared(Intent intent) {
        downloadPath = intent.getStringExtra(Utils.DOWNLOAD_FILEPATH);
        setState(State.UPDATE_PREPARED);
    }

    private void updateDownloadFailed() {
        setState(State.UPDATED_DOWNLOAD_FAILED);
    }

    private void updateInstall() {
        Intent installIntent = new Intent();
        installIntent.setAction(DownloadReceiver.ACTION_INSTALL_UPDATE);
        installIntent.putExtra(DownloadReceiver.EXTRA_FILEPATH, downloadPath);
        sendBroadcast(installIntent);
    }

    // Request permissions
    private void requestStoragePermission() {
        int permissionCheck = ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if ( permissionCheck == PackageManager.PERMISSION_GRANTED ) {
            // permission already granted, go ahead
            switch (mPermissionsReqType) {
                case PERMISSIONS_REQUEST_FOR_DOWNLOAD:
                    updateDownload();
                    break;
                default:
                    break;
            }
        } else {
            // permission not granted, request it from the user
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
        }
    }

    // Handle requested permissions
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[],
                                           int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE: {
                // if request is cancelled, the result arrays are empty
                if ( grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED ) {
                    // permission was granted
                    switch (mPermissionsReqType) {
                        case PERMISSIONS_REQUEST_FOR_DOWNLOAD:
                            updateDownload();
                            break;
                        default:
                            break;
                    }
                } else {
                    // permission was not granted
                    switch (mPermissionsReqType) {
                        case PERMISSIONS_REQUEST_FOR_DOWNLOAD:
                            updateDownloadFailed();
                            break;
                        default:
                            break;
                    }
                    /*new AlertDialog.Builder(this)
                            .setTitle(R.string.permission_not_granted_dialog_title)
                            .setMessage(R.string.permission_not_granted_dialog_message)
                            .setPositiveButton(R.string.dialog_ok, null)
                            .show();*/
                    return;
                }
                break;
            }
        }
    }

    private void loadPrefs() {
        updateInfo = UpdateInfo.loadPrefs(mPrefs);
        downloadId = mPrefs.getLong(Utils.DOWNLOAD_ID, -1);
        downloadPath = mPrefs.getString(Utils.DOWNLOAD_FILEPATH, "");
        setState(State.values()[mPrefs.getInt(LAST_STATE, State.UNKNOWN.ordinal())]);
    }

    private void savePrefs() {
        if ( updateInfo != null ) {
            mPrefs.edit()
                    .putLong(Utils.DOWNLOAD_ID, downloadId)
                    .putString(Utils.DOWNLOAD_FILEPATH, downloadPath)
                    .apply();
            updateInfo.savePrefs(mPrefs);
        }

    }

    private void setUiViews(
            String strTitle,
            String strStatus,
            String strDescription,
            String strActionButton,
            String strSize,
            boolean setProgressEnabled
    ) {
        title.setText(strTitle);
        status.setText(strStatus);
        description.setText(strDescription);
        actionButton.setText(strActionButton);
        actionButton.setVisibility(strActionButton.length() > 0 ? View.VISIBLE : View.INVISIBLE);
        size.setText(strSize);
        progress.setIndeterminate(setProgressEnabled);
        progress.setVisibility(setProgressEnabled ? View.VISIBLE : View.INVISIBLE);
    }

    private void updateUi() {
        switch (this.state) {
            case UNKNOWN:
            case UPDATE:
            case UPDATED:
            case UNCHECKED:
                setUiViews(
                        getString(R.string.system_update_no_update_content_text), // Title
                        "", // Status
                        "", // Description
                        getString(R.string.system_update_check_now_button_text), // Action Button
                        "", // Size
                        false // ProgressBar enabled/visible
                );
                break;
            case CHECKING:
                setUiViews(
                        getString(R.string.system_update_verifying_status_text),
                        "",
                        "",
                        getString(R.string.conn_auth_error_button_cancel),
                        "",
                        true
                );
                break;
            case CHECK_FAILED:
                setUiViews(
                        getString(R.string.system_update_verification_failed_text),
                        "",
                        "",
                        getString(R.string.system_update_check_now_button_text),
                        "",
                        false
                );
                break;
            case UPDATE_AVAILABLE:
                setUiViews(
                        getString(R.string.system_update_update_available_notification_title),
                        "",
                        updateInfo.getDescription(),
                        getString(R.string.system_update_download_button_text),
                        updateInfo.getSize(),
                        false
                );
                break;
            case UPDATE_DOWNLOADING:
                setUiViews(
                        getString(R.string.system_update_downloading_status_text),
                        "0%",
                        updateInfo.getDescription(),
                        getString(R.string.conn_auth_error_button_cancel),
                        updateInfo.getSize(),
                        true
                );
                break;
            case UPDATE_DOWNLOADED:
                setUiViews(
                        getString(R.string.system_update_update_downloaded_notification_title),
                        getString(R.string.system_update_verifying_status_text),
                        updateInfo.getDescription(),
                        "",
                        "",
                        false
                );
                break;
            case UPDATED_DOWNLOAD_FAILED:
                setUiViews(
                        getString(R.string.system_update_download_failed_status_text),
                        "",
                        updateInfo.getDescription(),
                        getString(R.string.system_update_download_retry_button_text),
                        updateInfo.getSize(),
                        false
                );
                break;
            case UPDATE_PREPARED:
                setUiViews(
                        getString(R.string.system_update_verified_status_text),
                        getString(R.string.system_update_requires_restart_status_text),
                        updateInfo.getDescription(),
                        getString(R.string.system_update_install_button_text),
                        "",
                        false
                );
                break;
        }
    }

    private void setState(final State state) {
        Log.v(TAG, "setState(): state=" + state.name());
        this.state = state;
        mPrefs.edit().putInt(LAST_STATE, this.state.ordinal()).apply();
        try {
            this.actionButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switch (state) {
                        case UNKNOWN:
                        case UNCHECKED:
                        case CHECK_FAILED:
                        case UPDATE:
                        case UPDATED:
                            checkUpdate();
                            break;
                        case CHECKING:
                            checkUpdateCancel();
                            break;
                        case UPDATE_AVAILABLE:
                        case UPDATED_DOWNLOAD_FAILED:
                            mPermissionsReqType = PERMISSIONS_REQUEST_FOR_DOWNLOAD;
                            requestStoragePermission();
                            break;
                        case UPDATE_DOWNLOADING:
                            handler.removeCallbacks(downloadProgress);
                            if ( downloadId > -1 )
                                mDownloadManager.remove(downloadId);
                            downloadId = -1;
                            updateInfo = null;
                            setState(State.UNCHECKED);
                            break;
                        case UPDATE_PREPARED:
                            updateInstall();
                            break;
                        case UPDATE_DOWNLOADED:
                            break;
                    }

                }
            });
            updateUi();
        } catch (Exception ignored) {
            setState(State.UNKNOWN);
        }
    }

    public enum State {
        UNKNOWN,
        UNCHECKED,
        CHECKING,
        CHECK_FAILED,
        UPDATE,
        UPDATE_AVAILABLE,
        UPDATE_DOWNLOADING,
        UPDATE_DOWNLOADED,
        UPDATED_DOWNLOAD_FAILED,
        UPDATE_PREPARED,
        UPDATED
    }
}

package unlegacy.android.update.notify;

import android.content.Intent;

import java.io.File;

import unlegacy.android.update.service.DownloadService;

public class DownloadNotifier {
    public static void notifyDownloadComplete(DownloadService downloadService, Intent updateIntent, File updateFile) {
    }

    public static void notifyDownloadError(DownloadService downloadService, Intent updateIntent, int failureMessageResId) {
    }
}

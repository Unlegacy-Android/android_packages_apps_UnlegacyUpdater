package unlegacy.android.update.model;

import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;
import java.net.URL;
import java.net.URLConnection;

public class UpdateInfo implements Parcelable, Serializable {

    public static final String DEVICE = "device";
    public static final String VERSION = "version";
    public static final String DATE = "date";
    public static final String DESCRIPTION = "description";
    public static final String URL = "url";
    public static final String MD5 = "md5";
    public static final String IS_INCREMENTAL = "isIncremental";

    public static final String INCREMENTAL = "incremental";
    private static final String SIZE = "size";

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public String getMd5() {
        return md5;
    }

    public String getSize() {
        if (size>10000000)
            return (size/1048576)+"MB";
        else if (size>10000)
            return (size/1024)+"KB";
        else
            return size+"B";
    }

    private String device;
    private String version;
    private long date;
    private String description;
    private String url;
    private String md5;
    private boolean isIncremental;
    private long size;


    public UpdateInfo(String device,
                      String version,
                      long date,
                      String description,
                      String url,
                      String md5,
                      boolean isIncremental) {
        this.device = device;
        this.version = version;
        this.date = date;
        this.description = description;
        this.url = url;
        this.md5 = md5;
        this.isIncremental = isIncremental;
        this.size = getFileSize(this.url);
    }

    private UpdateInfo(String device,
                       String version,
                       long date,
                       String description,
                       String url,
                       String md5,
                       boolean isIncremental,
                       long size) {
        this.device = device;
        this.version = version;
        this.date = date;
        this.description = description;
        this.url = url;
        this.md5 = md5;
        this.isIncremental = isIncremental;
        this.size = size;
    }

    private UpdateInfo(Parcel in) {
        device = in.readString();
        version = in.readString();
        date = in.readLong();
        description = in.readString();
        url = in.readString();
        md5 = in.readString();
        isIncremental = in.readByte() != 0;
        size = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(device);
        dest.writeString(version);
        dest.writeLong(date);
        dest.writeString(description);
        dest.writeString(url);
        dest.writeString(md5);
        dest.writeByte((byte) (isIncremental ? 1 : 0));
        dest.writeLong(size);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<UpdateInfo> CREATOR = new Creator<UpdateInfo>() {
        @Override
        public UpdateInfo createFromParcel(Parcel in) {
            return new UpdateInfo(in);
        }

        @Override
        public UpdateInfo[] newArray(int size) {
            return new UpdateInfo[size];
        }
    };

    @Override
    public String toString() {
        return ("Device: " + device + "\n") +
                "Version: " + version + "\n" +
                "Date: " + date + "\n" +
                "Description: " + description + "\n" +
                "URL: " + url + "\n" +
                "MD5: " + md5 + "\n" +
                "isIncremental: " + isIncremental + "\n" +
                "Size: " + size + " bytes";
    }

    public boolean isUpdatable() {
        // TODO: Build the isUpdatable algorithm
        return true;
    }

    public void savePrefs(SharedPreferences mPrefs) {
        mPrefs.edit()
                .putString(DEVICE,this.device)
                .putString(VERSION,this.version)
                .putLong(DATE,this.date)
                .putString(DESCRIPTION,this.description)
                .putString(URL,this.url)
                .putString(MD5,this.md5)
                .putBoolean(IS_INCREMENTAL,this.isIncremental)
                .putLong(SIZE,this.size)
                .apply();
    }

    public static UpdateInfo loadPrefs(SharedPreferences mPrefs) {
        if (!mPrefs.getString(UpdateInfo.DEVICE,"").isEmpty())
            return new UpdateInfo(
                    mPrefs.getString(UpdateInfo.DEVICE, ""),
                    mPrefs.getString(UpdateInfo.VERSION, ""),
                    mPrefs.getLong(UpdateInfo.DATE, 0),
                    mPrefs.getString(UpdateInfo.DESCRIPTION, ""),
                    mPrefs.getString(UpdateInfo.URL, ""),
                    mPrefs.getString(UpdateInfo.MD5, ""),
                    mPrefs.getBoolean(UpdateInfo.IS_INCREMENTAL, false),
                    mPrefs.getLong(UpdateInfo.SIZE, -1));
        else
            return null;
    }

    private static long getFileSize(String fileUrl) {
        java.net.URL url;
        URLConnection conn;
        long result;
        try {
            url = new URL(fileUrl);
            conn = url.openConnection();
            conn.setConnectTimeout(10000);
            conn.connect();
            result = Long.parseLong(conn.getHeaderField("content-length"));
        } catch (Exception ignored) {
            result = -1;
        }
        return result;
    }
}
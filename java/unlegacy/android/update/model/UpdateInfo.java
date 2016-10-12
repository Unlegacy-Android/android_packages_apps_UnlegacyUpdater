package unlegacy.android.update.model;

import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Date;

public class UpdateInfo implements Parcelable, Serializable {

    public static final String DEVICE = "device";
    public static final String VERSION = "version";
    public static final String INCREMENTAL = "incremental";

    public static final String DESCRIPTION = "description";
    public static final String URL = "url";
    public static final String MD5 = "md5";
    public static final String DATE = "date";
    public static final String SIZE = "size";

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
    private String device;
    private String version;
    private String description;
    private String url;
    private String md5;
    private Date date;
    private long fileSize;


    public UpdateInfo(String version, String description, String url, String md5, Date date, long fileSize) {
        this.device = Build.DEVICE;
        this.version = version;
        this.description = description;
        this.url = url;
        this.md5 = md5;
        this.date = date;
        this.fileSize = fileSize;
    }

    protected UpdateInfo(Parcel in) {
        device = in.readString();
        version = in.readString();
        description = in.readString();
        url = in.readString();
        md5 = in.readString();
        date = (java.util.Date) in.readSerializable();
        fileSize = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {

        dest.writeString(device);
        dest.writeString(version);
        dest.writeString(description);
        dest.writeString(url);
        dest.writeString(md5);
        dest.writeSerializable(date);
        dest.writeLong(fileSize);
    }

    public String getDevice() {
        return device;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public String getMd5() {
        return md5;
    }

    public Date getDate() {
        return date;
    }

    public long getFileSize() {
        return fileSize;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: "+device+"\n");
        sb.append("Version: "+version+"\n");
        sb.append("Description: "+description+"\n");
        sb.append("URL: "+url+"\n");
        sb.append("MD5: "+md5+"\n");
        sb.append("Date: "+date.toString()+"\n");
        sb.append("FileSize: "+fileSize+" bytes");
        return sb.toString();
    }

    public boolean isUpdatable() {
        if (Build.DEVICE.toLowerCase().equals(device.toLowerCase()) &&
                tryGetLong(Build.VERSION.INCREMENTAL) < tryGetLong(version))
            return true;
        return true;
    }

    private static long tryGetLong(String incrementalString) {
        try {
            return Long.parseLong(incrementalString);
        } catch (Exception e) {
            return 0;
        }
    }
}
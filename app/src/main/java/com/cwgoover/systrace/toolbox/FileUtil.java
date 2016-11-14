package com.cwgoover.systrace.toolbox;


import android.content.Context;
import android.preference.PreferenceManager;
import android.util.Log;

import com.cwgoover.systrace.StartAtraceActivity;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileUtil {
    public static final String TAG = StartAtraceActivity.TAG + ".c";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String EXIT = "exit\n";
    private static final String OUTPUT_FILE_PREFIX = "systrace_";
    private static final String OUTPUT_FILE_SUFFIX = ".trace";

    //FIXME: Do not place Android context classes in static fields (static reference to
    //FIXME: FileUtil which has field mContext pointing to Context); this is a memory leak
    //FIXME: (and also breaks Instant Run)
    //FIXME: A static field will leak contexts!!!
//    private final Context mContext;
//    private final SharedPreferences mPerferences;

    private static FileUtil sInstance;
//    private FileUtil(Context context) {
//        mContext = context;
////      SharedPreferences mPerferences = getPreferences(Context.MODE_PRIVATE);
//        mPerferences = PreferenceManager.getDefaultSharedPreferences(mContext);
//    }

    private FileUtil() {}
    public static synchronized FileUtil getInstance () {
        if (sInstance == null) {
            sInstance = new FileUtil();
        }
        return sInstance;
    }

    public static void myLogger(String tag, String msg) {
        if (DEBUG) {
            Log.d(tag, msg);
        }
    }

    public void setTimeInterval(Context ctx, String key, String val) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(key, val).apply();
    }

    public String getTimeInterval(Context ctx, String key) {
        return PreferenceManager.getDefaultSharedPreferences(ctx).getString(key, "0");
    }

    public void setBooleanState(Context ctx, String key, boolean val) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putBoolean(key, val).apply();
    }

    public boolean getBooleanState(Context ctx, String key, boolean  defaultValue) {
        // the default true value is special for the "ICON_SHOW"
        return PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(key, defaultValue);
    }

    public File createTraceFile(String path) {
        File filePath = new File(path);
        if (!filePath.exists() || !filePath.isDirectory()) {
            myLogger(TAG, "create file path : " + path);
            filePath.mkdirs();
        }

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss.SSS", Locale.US);
        String time= df.format(new Date());
        String fileName = OUTPUT_FILE_PREFIX + time + OUTPUT_FILE_SUFFIX;
        myLogger(TAG, "createTraceFile: name=" + fileName);

        return new File(filePath, fileName);
    }

    public void deleteFile(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                myLogger(TAG, "delete file " + path);
                file.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "delete file fail");
        }
    }

    /**
     * What is the most efficient/elegant way to dump a StringBuilder to a text file?
     *   http://stackoverflow.com/questions/1677194/dumping-a-java-stringbuilder-to-file
     * answered: NawaMan, rob
     *
     * @param sb    the context of the dump information
     * @param meminfoFile   the output file path
     */
    public void dumpToFile(StringBuilder sb, String meminfoFile) {
        File file = new File(meminfoFile);
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(file, true));
            writer.write(sb.toString());
        } catch (IOException e) {
            Log.e(TAG, "dumpToFile fail!");
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (IOException e) {Log.e(TAG, "dumpToFile fail when closing writer!");}
        }
    }

    /**
     * Copy file from assets directory into /data/data/[my_package_name]/files,
     * cause it write by <code>openFileOutput()</code> method
     * <p></p>
     * <p>You can use <code>getFileStreamPath</code> method to open file directly.</p>
     * <p><code>File file = getFileStreamPath(file);</code></p>
     * <p></p>
     * <p>and use <code>setExecutable</code> method to set it executable(744).
     * <p><code>boolean res = file.setExecutable(true);</code></p>
     * <p></p>
     * @param filename the file in the asset directory.
     */
    public void copyFromAssets(Context ctx, String filename) {
        myLogger(TAG, "Attempting to copy this file: " + filename);
        try {
            InputStream ins = ctx.getAssets().open(filename);
            byte[] buffer = new byte[ins.available()];  //check
            ins.read(buffer);
            ins.close();
            FileOutputStream fos = ctx.openFileOutput(filename, Context.MODE_PRIVATE);
            fos.write(buffer);
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

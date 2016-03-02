package com.jrdcom.systrace.toolbox;


import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.jrdcom.systrace.StartAtraceActivity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

public class CommandUtil {
    public static final String TAG = StartAtraceActivity.TAG + ".c";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String EXIT = "exit\n";
    private static final String OUTPUT_FILE_PREFIX = "systrace_";
    private static final String OUTPUT_FILE_SUFFIX = ".trace";

    enum OUTPUT {
        STDOUT,
        STDERR,
        BOTH
    }

    private final Context mContext;
    private final SharedPreferences mPerferences;

    private static CommandUtil sInstance;
    private CommandUtil(Context context) {
        mContext = context;
//      SharedPreferences mPerferences = getPreferences(Context.MODE_PRIVATE);
        mPerferences = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public static synchronized CommandUtil getInstance (Context context) {
        if (sInstance == null) {
            sInstance = new CommandUtil(context);
        }
        return sInstance;
    }

    public static void myLogger(String tag, String msg) {
        if (DEBUG) {
            Log.d(tag, msg);
        }
    }

    public void setTimeInterval(String key, String val) {
        mPerferences.edit().putString(key, val).commit();
    }

    public String getTimeInterval(String key) {
        return mPerferences.getString(key, "0");
    }

    public void setBooleanState(String key, boolean val) {
        mPerferences.edit().putBoolean(key, val).commit();
    }

    public boolean getBooleanState(String key, boolean  defaultValue) {
        // the default true value is special for the "ICON_SHOW"
        return mPerferences.getBoolean(key, defaultValue);
    }

    public File createFile(String path) {
        File filePath = new File(path);
        if (!filePath.exists() || !filePath.isDirectory()) {
            myLogger(TAG, "create file path : " + path);
            filePath.mkdirs();
        }

        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss.SSS");
        String time= df.format(new Date());
        String fileName = OUTPUT_FILE_PREFIX + time + OUTPUT_FILE_SUFFIX;
        myLogger(TAG, "createFile: name=" + fileName);

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
     * Use ProcessBuilder to execute shell command
     *
     * @param command   shell command to execute
     * @return  true means successful, else means fail
     */
    public boolean runCommand(String[] command, File file) {
        myLogger(TAG, "setAdbCommand: command=" + Arrays.toString(command));
        try {
            return _runCommand(command, file);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * First, if your file contains binary data, then using BufferedReader would
     * be a big mistake (because you would be converting the data to String, which
     * is unnecessary and could easily corrupt the data); you should use a BufferedInputStream
     * instead. If it's text data and you need to split it along linebreaks, then using
     * BufferedReader is OK (assuming the file contains lines of a sensible lenght)
     *
     */
    private boolean _runCommand(String[] command, File file)  throws IOException {
        InputStream in = null;
        OutputStream out = null;
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                                    .redirectErrorStream(false)
                                    .directory(new File(file.getParent()))
                                    .start();

            // get the output from the process
            in = new BufferedInputStream(process.getInputStream());
            out = new BufferedOutputStream(new FileOutputStream(file.getAbsolutePath()));

            int cnt;
            byte[] buffer = new byte[1024];
            // read from this until EOF, and write the output to a file
            while ((cnt = in.read(buffer)) != -1) {
                out.write(buffer, 0, cnt);
            }

            //There should really be a timeout here.
            if (0 != process.waitFor()) {
                Log.e(TAG, "cmd time out!");
                return false;
            }
            myLogger(TAG, "_runCommand: finished");
            return true;
        } catch (Exception e) {
            final String msg = e.getMessage();
            Log.e(TAG, "\n\n\t\tCOMMAND FAILED: " + msg);
            throw new IOException(msg);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
                if (process != null) {
                    process.destroy();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Use Runtime.exec() to execute shell command, it can run executable file.
     *
     * @param command   shell command to execute
     * @return  the result of the command
     */
    public String exec(String[] command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            InputStreamReader reader = new InputStreamReader(process.getInputStream());
            BufferedReader bufferedReader = new BufferedReader(reader);
            int numRead;
            char[] buffer = new char[5000];
            StringBuffer commandOutput = new StringBuffer();
            while ((numRead = bufferedReader.read(buffer)) > 0) {
                commandOutput.append(buffer, 0, numRead);
            }
            bufferedReader.close();
            process.waitFor();

            return commandOutput.toString();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "\n\n\t\tCOMMAND FAILED: " + Arrays.toString(command));
            return null;
        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
            Log.e(TAG, "\n\n\t\tCOMMAND InterruptedException: " + Arrays.toString(command));
            return null;
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
    public void copyFromAssets(String filename) {
        myLogger(TAG, "Attempting to copy this file: " + filename);
        try {
            InputStream ins = mContext.getAssets().open(filename);
            byte[] buffer = new byte[ins.available()];  //check
            ins.read(buffer);
            ins.close();
            FileOutputStream fos = mContext.openFileOutput(filename, Context.MODE_PRIVATE);
            fos.write(buffer);
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

package com.cwgoover.systrace.toolbox;

import android.util.Log;

import com.cwgoover.systrace.StartAtraceActivity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;

public class ShellChannel {
    public static final String TAG = StartAtraceActivity.TAG + ".sh";

    private Process mProcess = null;

    enum OUTPUT {
        STDOUT,
        STDERR,
        BOTH
    }

    /**
     * Use ProcessBuilder to execute shell command
     *
     * @param command   shell command to execute
     * @return  true means successful, else means fail
     */
    public boolean runCommand(String[] command, File file) {
        Log.d(TAG, "setAdbCommand: command=" + Arrays.toString(command));
        try {
            return _runCommand(command, file);
        } catch (IOException e) {
            return false;
        }
    }

    public void terminal() {
        mProcess.destroy();
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

        try {
            mProcess = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .directory(new File(file.getParent()))
                    .start();

            // get the output from the mProcess
            in = new BufferedInputStream(mProcess.getInputStream());
            /*
             *   FileOutputStream(File file, boolean append)
             * Set the boolean to true. That way, the data you write will be appended to the
             * end of the file, rather than overwriting what was already there.
             */
            out = new BufferedOutputStream(new FileOutputStream(file.getAbsolutePath(), true));

            int cnt;
            byte[] buffer = new byte[1024];
            // read from this until EOF, and write the output to a file
            while ((cnt = in.read(buffer)) != -1) {
                out.write(buffer, 0, cnt);
            }

            //There should really be a timeout here.
            if (0 != mProcess.waitFor()) {
                Log.e(TAG, "cmd times out!");
                return false;
            }
            Log.d(TAG, "_runCommand: finished");
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
                if (mProcess != null) {
                    mProcess.destroy();
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
            StringBuilder commandOutput = new StringBuilder();
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
}

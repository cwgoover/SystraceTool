package com.cwgoover.systrace.commands;

import android.os.Process;

import com.cwgoover.systrace.StartAtraceActivity;
import com.cwgoover.systrace.TaskManager;
import com.cwgoover.systrace.TaskRunnableMethods;
import com.cwgoover.systrace.toolbox.FileUtil;
import com.cwgoover.systrace.toolbox.ShellChannel;

import java.io.File;

/**
 * Command: ps/top
 */
public class GlobalProcessRunnable implements Runnable {

    public static final String TAG = StartAtraceActivity.TAG + ".global";
    // SystemProperty: PS cmd flag
    private static final String[] SP_CMD_PS_FLAG = {"getprop", "persist.atrace.ps"};
    // capture ps info
    private static final String[] PS_CMD = {"/system/bin/ps"};
    // capture top info
    private static final String[] TOP_CMD = {"/system/bin/top", "-t", "-d", "1", "-m", "25", "-n", "10"};

    private static boolean ENABLE_PS_CMD_RESQ = true;

    final TaskRunnableMethods mTaskMethods;
    final ShellChannel mShellChannel;

    private File mTargetFile;

    public GlobalProcessRunnable(TaskRunnableMethods task, File dest) {
        mShellChannel = new ShellChannel();
        mTaskMethods = task;
        mTargetFile = dest;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("get_system_property");

        // Moves the current Thread into the background
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        try {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            // get_property
            boolean isShowPsInfo;
            try {
                String info = mShellChannel.exec(SP_CMD_PS_FLAG).trim();
                // if info has error number format, return false
                if (!info.isEmpty()) {
                    isShowPsInfo = Integer.parseInt(info) != 0;
                    FileUtil.myLogger(TAG, "prepareProperty: isShowPsInfo= " + isShowPsInfo);
                } else {
                    isShowPsInfo = false;
                }
            } catch (NumberFormatException e) {
                isShowPsInfo = false;
                FileUtil.myLogger(TAG, "Error for prepareProperty: EXCEPTION!");
                e.printStackTrace();
            }

            // Before continuing, checks to see that the Thread hasn't been
            // interrupted
            if (Thread.interrupted()) {

                throw new InterruptedException();
            }

            FileUtil.myLogger(TAG, "runAtrace: success, now catch addition info!");
            // capture ps info from system device
            if (ENABLE_PS_CMD_RESQ || isShowPsInfo) {
                String ps_file = mTargetFile.getAbsolutePath() + ".ps";
                mShellChannel.runCommand(PS_CMD, new File(ps_file));
            }

            // capture top info from system device
            String top_file = mTargetFile.getAbsolutePath() + ".top";
            mShellChannel.runCommand(TOP_CMD, new File(top_file));

        } catch (InterruptedException e) {
            // Does nothing
        } finally {
            Thread.interrupted();
            mTaskMethods.handleCommandState(TaskManager.TASK_COMPLETE);
        }
    }
}

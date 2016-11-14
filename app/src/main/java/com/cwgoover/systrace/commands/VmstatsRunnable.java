package com.cwgoover.systrace.commands;

import android.os.Process;

import com.cwgoover.systrace.StartAtraceActivity;
import com.cwgoover.systrace.TaskRunnableMethods;
import com.cwgoover.systrace.toolbox.FileUtil;
import com.cwgoover.systrace.toolbox.ShellChannel;

import java.io.File;

/**
 * vmstats command
 */
public class VmstatsRunnable implements Runnable {
    public static final String TAG = StartAtraceActivity.TAG + ".vm";

    // capture vmstats info
    private static final String[] VMSTATS_CMD = {"/system/bin/vmstat", "-n", "1"};

    private final TaskRunnableMethods mTaskMethods;
    private final ShellChannel mShellChannel;

    private File mTargetFile;
    private FileUtil mUntil;

    public VmstatsRunnable(TaskRunnableMethods task, File dest) {
        mShellChannel = new ShellChannel();
        mUntil = FileUtil.getInstance();
        mTaskMethods = task;
        mTargetFile = dest;
    }

    /**
     *  This thread will be blocked at runCommand method which is running vmstats
     * command all the ways, so I can't stop the thread with Thread.interrupt method.
     *  The only way I can do is to stop Process created by this thread with Process.destroy
     *  method.
     */
    public void stopVmstatsThread() {
        mShellChannel.terminal();
    }

    @Override
    public void run() {
        Thread.currentThread().setName("get_vmstats");

        // Moves the current Thread into the background
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

//            if (Thread.interrupted()) {
//                throw new InterruptedException();
//            }

        FileUtil.myLogger(TAG, "Thread: catch vmstats in the background");
        String vmstats_file = mTargetFile.getAbsolutePath() + ".vmstats";

        // write title to the file first
        StringBuilder sb = new StringBuilder();
        sb.append("procs -----------memory---------- ---swap-- -----io---- -system-- ----cpu----\n");
        sb.append(" r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa\n");
        mUntil.dumpToFile(sb, vmstats_file);

        // write data to the file now
        mShellChannel.runCommand(VMSTATS_CMD, new File(vmstats_file));
        FileUtil.myLogger(TAG, Thread.currentThread().getName() + " STOP!!");
    }
}

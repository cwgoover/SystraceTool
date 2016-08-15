package com.cwgoover.systrace.commands;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Process;

import com.cwgoover.systrace.StartAtraceActivity;
import com.cwgoover.systrace.TaskRunnableMethods;
import com.cwgoover.systrace.toolbox.FileUtil;
import com.jaredrummler.android.processes.ProcessManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Command: meminfo
 */
public class MeminfoRunnable implements Runnable {
    public static final String TAG = StartAtraceActivity.TAG + ".mem";

    final TaskRunnableMethods mTaskMethods;
    private ActivityManager mActivityManager;
    private Context mContext;
    private FileUtil mUntil;
    private File mTargetFile;

    public MeminfoRunnable(Context context, TaskRunnableMethods task, File dest) {
        mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        mUntil = FileUtil.getInstance(context);
        mContext = context;
        mTaskMethods = task;
        mTargetFile = dest;
    }

    @Override
    public void run() {
        mTaskMethods.setMeminfoThread(Thread.currentThread());
        Thread.currentThread().setName("get_meminfo");

        // Moves the current Thread into the background
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        String meminfoFile = mTargetFile.getAbsolutePath() + ".mem";

        try {
            // Before continuing, checks to see that the Thread hasn't been
            // interrupted
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            // capture meminfo from system device
            FileUtil.myLogger(TAG, "capture meminfo from system device!");
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            mActivityManager.getMemoryInfo(memInfo);

            // StringBuilder is faster than StringBuffer because StringBuffer is synchronized, StringBuilder is not.
            //  The default size(384 param) used if you don't specify one is 16 characters, which is usually too small and results
            // in the StringBuilder having to do reallocation to make itself bigger
            //StringBuilder sb = new StringBuilder(384);
            // This sb is too big, so use default size to reallocate.
            StringBuilder sb = new StringBuilder();
            sb.append("Applications Memory Usage (kB):\n");
            sb.append(" TotalMem:   ").append(roundToKB(memInfo.totalMem)).append(" kB\n");
            sb.append(" AvailMem:   ").append(roundToKB(memInfo.availMem)).append(" kB\n");
            sb.append(" Threshold:  ").append(roundToKB(memInfo.threshold)).append(" kB\n");
            sb.append(" LowMemory:  ").append(memInfo.lowMemory).append("\n\n");
            mUntil.dumpToFile(sb, meminfoFile);

            SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            while (true) {
                // Before continuing, checks to see that the Thread hasn't been
                // interrupted
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }

                // reset the StringBuilder
                sb.setLength(0);
                sb.append("\nTime:").append(df.format(new Date())).append("\n");

                List<ActivityManager.RunningAppProcessInfo> runningAppProcesses;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // getRunningAppProcesses() returns my application package only in Android 5.1.1 and above
                    runningAppProcesses = mActivityManager.getRunningAppProcesses();
                } else {
                    // package: https://github.com/jaredrummler/AndroidProcesses
                    // System apps aren't visible because they have a higher SELinux context than third party apps.
                    runningAppProcesses = ProcessManager.getRunningAppProcessInfo(mContext);
                }

                // Warning: Explicit type argument Integer, String can be replaced with <>
//                Map<Integer, String> pidMap = new TreeMap<>();
                for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcesses) {
                    int pid[] = new int[1];
                    pid[0] = runningAppProcessInfo.pid;
                    Debug.MemoryInfo[] memoryInfoArray = mActivityManager.getProcessMemoryInfo(pid);
                    for (Debug.MemoryInfo pidMemoryInfo : memoryInfoArray) {
                        sb.append(String.format(Locale.US, "\n** MEMINFO in pid %d [%s] **\n", pid[0], runningAppProcessInfo.processName));
                        sb.append(" TotalPss: ").append(pidMemoryInfo.getTotalPss()).append("\n");
                        sb.append(" TotalPrivateDirty: ").append(pidMemoryInfo.getTotalPrivateDirty()).append("\n");
                        sb.append(" TotalSharedDirty: ").append(pidMemoryInfo.getTotalSharedDirty()).append("\n\n");
                    }
                }
                mUntil.dumpToFile(sb, meminfoFile);
            }
        } catch (InterruptedException e) {
            FileUtil.myLogger(TAG, Thread.currentThread().getName() + " STOP!!");
        } finally {
            mTaskMethods.setMeminfoThread(null);
            Thread.interrupted();
        }
    }

    private long roundToKB(long raw) {
        return Math.round(raw / 1024);
    }
}

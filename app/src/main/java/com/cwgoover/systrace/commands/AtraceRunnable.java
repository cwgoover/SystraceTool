package com.cwgoover.systrace.commands;

import android.content.Context;

import com.cwgoover.systrace.StartAtraceActivity;
import com.cwgoover.systrace.TaskManager;
import com.cwgoover.systrace.TaskRunnableMethods;
import com.cwgoover.systrace.toolbox.FileUtil;
import com.cwgoover.systrace.toolbox.ShellChannel;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AtraceRunnable implements Runnable {

    public static final String TAG = StartAtraceActivity.TAG + ".trace";

    /**
     * we need to know whether running on a rooted device
     */
    private static final String SYSTEM_PROPERTY_DEBUGGABLE = "ro.debuggable";
    private static final String SYSTEM_PROPERTY_SECURE = "ro.secure";

    // SystemProperty: heap size
    private static final String[] SP_HEAP_SIZE = {"getprop", "persist.atrace.heapsize"};

    private static final String[] RUN_ATRACE_0 = {"/system/bin/atrace", "-z", "-t"};
    private static final String[] RUN_ATRACE_USER_1 = {"gfx", "input", "view", "webview", "am", "wm", "sm",
            "audio", "video", "camera", "hal", "app", "res", "dalvik", "rs", "hwui", "perf",
            "bionic", "power", "sched", "freq", "idle", "load"};

    // /system/bin/atrace -z -t 15 -b 2048 gfx input view webview am wm sm audio video camera hal app res dalvik
    // rs hwui perf bionic power sched irq freq idle disk mmc load sync workq memreclaim regulators

    private static final String[] RUN_ATRACE_ENG_1 = {"gfx", "input", "view", "webview", "am", "wm", "sm",
            "audio", "video", "camera", "hal", "app", "res", "dalvik", "rs", "hwui", "perf", "bionic", "power",
            "sched", "irq", "freq", "idle", "disk", "mmc", "load", "sync", "workq", "memreclaim", "regulators"};

    private static final String HEAP_SIZE_LOW = "2048";     // 2MB
    private static final String HEAP_SIZE_MEDIUM = "5120";    // 5MB
    private static final String HEAP_SIZE_HIGH = "10240";       //10MB

    final List<String> mAtraceCmd = new ArrayList<>();
    final TaskRunnableMethods mTaskMethods;
    final ShellChannel mShellChannel;

    private Context mContext;
    private File mTargetFile;
    private String mTimeInterval;

    List<String> mPreAtrace;
    List<String> mPostAtrace;

    public AtraceRunnable(Context context, TaskRunnableMethods task, File file, String timeInterval) {
        mContext = context;
        mTaskMethods = task;
        mTargetFile = file;
        mTimeInterval = timeInterval;
        mShellChannel = new ShellChannel();

        /**
         *  NOTE: The list returned by <br>Arrays.asList<br> is a fixed size list.
         *  If you want to add something to the list, you would need to create another
         *  list, and use addAll to add elements to it.
         */
        mPreAtrace = Arrays.asList(RUN_ATRACE_0);

        String isDebuggable = getSystemProperty(SYSTEM_PROPERTY_DEBUGGABLE);
        String isSecure = getSystemProperty(SYSTEM_PROPERTY_SECURE);
        FileUtil.myLogger(TAG, "prepareProperty: ro.debuggable= " + isDebuggable
                + ", ro.secure=" + isSecure);
        boolean hasRootPermission = "1".equals(isDebuggable) && "0".equals(isSecure);
        if (hasRootPermission) {
            // Otherwise, "command fail!"
//            mPostAtrace = Arrays.asList(RUN_ATRACE_ENG_1);
            mPostAtrace = Arrays.asList(RUN_ATRACE_USER_1);
        } else {
            mPostAtrace = Arrays.asList(RUN_ATRACE_USER_1);
        }
    }

    @Override
    public void run() {
        Thread.currentThread().setName("running_atrace");

        mTaskMethods.handleCommandState(TaskManager.SYSTRACE_STARTED);

        // prepare command
        synchronized (mAtraceCmd) {
            mAtraceCmd.clear();
            mAtraceCmd.addAll(mPreAtrace);
            mAtraceCmd.add(mTimeInterval);

            // set heap size of the atrace
            mAtraceCmd.add("-b");

            String spHeapSize = mShellChannel.exec(SP_HEAP_SIZE);
            if (spHeapSize != null) spHeapSize = spHeapSize.trim();
            if (spHeapSize != null && !spHeapSize.isEmpty() && !spHeapSize.equals("0")) {
                FileUtil.myLogger(TAG, "set heap size 0f system property:" + spHeapSize);
                mAtraceCmd.add(spHeapSize);
            } else {
                int _timeInterval = Integer.parseInt(mTimeInterval);
                // Time interval is divided into 3 types:0~15,15~10,20~30
                if (_timeInterval <= 15) {
                    mAtraceCmd.add(HEAP_SIZE_LOW);
                } else if (_timeInterval <= 20) {
                    mAtraceCmd.add(HEAP_SIZE_MEDIUM);
                } else {
                    mAtraceCmd.add(HEAP_SIZE_HIGH);
                }
            }
            mAtraceCmd.addAll(mPostAtrace);
        }

        // change List<String> to String[]
        String[] command = new String[mAtraceCmd.size()];
        final boolean isSucess = mShellChannel.runCommand(mAtraceCmd.toArray(command), mTargetFile);

        if (isSucess) {
            // finish caught, so feedback this state.
            mTaskMethods.handleCommandState(TaskManager.SYSTRACE_COMPLETE);
        } else {
            mTaskMethods.handleCommandState(TaskManager.SYSTRACE_FAILED);
            FileUtil.getInstance(mContext).deleteFile(mTargetFile.toString());
        }
    }

    /*
     * How to use android.os.SystemProperites
     * http://songxiaoming.com/android/2013/02/27/How-to-use-android.os.SystemProperties/
     */
    private String getSystemProperty(String cmd) {
        String property = "";
        ClassLoader cl = mContext.getClassLoader();
        try {
            Class<?> SystemProperties = cl.loadClass("android.os.SystemProperties");
            // Parameters Types
            Class[] paramTypes = {String.class};
            Method get = SystemProperties.getMethod("get", paramTypes);

            // Parameters
            Object[] params = {cmd};
            property = (String) get.invoke(SystemProperties, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return property;
    }
}

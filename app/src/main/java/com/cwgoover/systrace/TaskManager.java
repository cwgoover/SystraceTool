package com.cwgoover.systrace;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.cwgoover.systrace.commands.AtraceRunnable;
import com.cwgoover.systrace.commands.GlobalProcessRunnable;
import com.cwgoover.systrace.commands.MeminfoRunnable;
import com.cwgoover.systrace.commands.VmstatsRunnable;
import com.cwgoover.systrace.toolbox.FileUtil;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class creates pool of background threads for executing command.
 * The class is implemented as a singleton; the only way to get an TaskManager
 * instance is to call {@link #getInstance}.
 * <p>
 * Finally, this class defines a handler that communicates back to the UI thread
 * to change the state.
 */
public class TaskManager implements TaskRunnableMethods {

    public static final String TAG = StartAtraceActivity.TAG + ".manager";

    /*
     * status indicators
     */
    public static final int SYSTRACE_FAILED = -1;
    public static final int SYSTRACE_STARTED = 1;
    public static final int SYSTRACE_COMPLETE = 2;
    public static final int TASK_COMPLETE = 3;

    // Sets the amount of time an idle thread will wait for a task before termnating.
    private static final int KEEP_ALIVE_TIME = 1;

    // Sets the Time Unit to seconds
    // The time unit for "keep alive" is in seconds
    private static final TimeUnit KEEP_ALIVE_TIME_UNIT = TimeUnit.SECONDS;

    private static final String DEFAULT_SYSTRACE_SUBDIR = "/jrdSystrace";

    /**
     * NOTE: This is the number of total available cores. The number of threads
     * you can have in a thread pool depends primarily on the number of cores
     * available for your device.
     * <p>
     * This number may not reflect the number of physical cores in the devices;
     * some devices have CPUs that deactivate one or more cores depending on the
     * system load. For these devices, availableProcessors() return the number of
     * active cores, which may be less than the total number of cores.
     */
    private static int NUMBER_OF_CORES = Runtime.getRuntime().availableProcessors();

    // A queue of Runnables for the command work pool.
    private final BlockingQueue<Runnable> mCommandWorkQueue;

    // A managed pool of background command threads.
    private final ThreadPoolExecutor mWorkThreadPool;

    // An object that manages Messages in a Thread
    private H mHandler;

    // A single instance of TaskManager
    private static TaskManager sInstance;
    private static File mTargetFile;

    private AtraceFloatView mFloatView;
    private String mTargetPath;

    private VmstatsRunnable mVmstatsRunnable;
    private Thread mMeminfoThread;
    private boolean mTaskFinished;

    static {
        sInstance = null;
        mTargetFile = null;
    }

    private TaskManager() {
        /*
         * Creates a work queue for the pool of Thread objects used for executing
         * command, using a linked list queue that blocks when the queue is empty.
         */
        mCommandWorkQueue = new LinkedBlockingQueue<>();

        // Creates a new pool of Thread objects for the command work queue.
        mWorkThreadPool = new ThreadPoolExecutor(NUMBER_OF_CORES, NUMBER_OF_CORES,
                KEEP_ALIVE_TIME, KEEP_ALIVE_TIME_UNIT, mCommandWorkQueue);

        mHandler = new H(Looper.getMainLooper());
        mTargetPath = Environment.getExternalStorageDirectory().getPath() + DEFAULT_SYSTRACE_SUBDIR;
    }

    // Creates a single static instance of TaskManager
    static TaskManager getInstance() {
        if (sInstance == null) sInstance = new TaskManager();
        return sInstance;
    }

    @Override
    public void handleCommandState(int state) {
        String name = null;
        Message msg;
        try {
            switch (state) {
                case SYSTRACE_STARTED:
                    name = "systraceStarted";
                    mTaskFinished = false;
                    msg = mHandler.obtainMessage(H.FREEZE_ICON);
                    msg.sendToTarget();
                    break;
                case SYSTRACE_FAILED:
                    name = "systraceFailed";
                    stopTraceThreads(false);
                    msg = mHandler.obtainMessage(H.ENABLE_ICON);
                    msg.sendToTarget();
                    break;
                case SYSTRACE_COMPLETE:
                    name = "systraceComplete";
                    stopTraceThreads(true);
                    msg = mHandler.obtainMessage(H.DISABLE_ICON);
                    msg.sendToTarget();
                    // capture global system info
                    mWorkThreadPool.execute(new GlobalProcessRunnable(this, mTargetFile));
                    break;
                case TASK_COMPLETE:
                    name = "TaskComplete";
                    msg = mHandler.obtainMessage(H.ENABLE_ICON);
                    msg.sendToTarget();
                    // clear the queue.
                    mWorkThreadPool.getQueue().clear();
                    break;
            }
        } catch (Throwable throwable) {
            final String error = "Error in " + name;
            Log.e(TAG, error, throwable);
        }
    }

    private void stopTraceThreads(boolean isFinished) {
        mTaskFinished = isFinished;
        stopVmstats();
        stopMeminfo();
    }

    @Override
    public void setMeminfoThread(Thread thread) {
        synchronized (mCommandWorkQueue) {
            mMeminfoThread = thread;
        }
    }

    void startAtrace(AtraceFloatView floatView, String timeInterval) {
        mFloatView = floatView;
        FileUtil util = FileUtil.getInstance();
        mTargetFile = util.createTraceFile(mTargetPath);
        if (mFloatView != null) {
            FileUtil.myLogger(TAG, "catch systrace now!");
            // capture atrace now
            mWorkThreadPool.execute(new AtraceRunnable(mFloatView, this, mTargetFile, timeInterval));
            // capture vmstats info in parallel
            mVmstatsRunnable = new VmstatsRunnable(this, mTargetFile);
            mWorkThreadPool.execute(mVmstatsRunnable);
            // capture meminfo
            mWorkThreadPool.execute(new MeminfoRunnable(mFloatView, this, mTargetFile));
        } else {
            Log.e(TAG, "Error: the instance of AtraceFloatView is null!!");
        }

    }

    private void stopMeminfo() {
        synchronized (mCommandWorkQueue) {
            if (mMeminfoThread != null) {
                mMeminfoThread.interrupt();
            }
        }
    }

    private void stopVmstats() {
        synchronized (mCommandWorkQueue) {
            mVmstatsRunnable.stopVmstatsThread();
        }
    }

    protected final class H extends Handler {
        static final int ENABLE_ICON = 0;
        static final int FREEZE_ICON = 1;
        static final int DISABLE_ICON = 2;

        H (Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ENABLE_ICON:
                    mFloatView.updateIconState(false, true);
                    mFloatView.showUserInfo(mTaskFinished, mTargetFile);
                    break;
                case FREEZE_ICON:
                    mFloatView.updateIconState(true, false);
                    break;
                case DISABLE_ICON:
                    mFloatView.updateIconState(true, true);
                    break;
            }
        }
    }
}

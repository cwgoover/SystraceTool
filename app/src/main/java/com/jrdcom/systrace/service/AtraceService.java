
package com.jrdcom.systrace.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.FloatMath;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import com.jrdcom.systrace.R;
import com.jrdcom.systrace.StartAtraceActivity;
import com.jrdcom.systrace.toolbox.CommandUtil;

public class AtraceService extends Service implements OnSharedPreferenceChangeListener {

    public static final String TAG = StartAtraceActivity.TAG + ".s";

    public static final int ONGOING_NOTIFICATION_ID = 2018;
    public static final int ANIMATION_DURATION = 350;
    public static final int LONG_CLICK_DURATION = 1000;
    public static final String DEFAULT_SYSTRACE_SUBDIR = "/jrdSystrace";

    /**
     * Max allowed duration for a "click", in milliseconds
     */
    private static final int MAX_CLICK_DURATION = 600;

    /**
     * Max allowed distance to move during a "click", in DP.
     */
    private static final int MAX_CLICK_DISTANCE = 15;

    private static boolean ENABLE_PS_CMD_RESQ = true;

    private static String sDesiredStoragePath = null;
    private static boolean sAtraceRunning = false;
    private static boolean sIconShowing = false;

    // SystemProperty: PS cmd flag
    private static final String[] SP_CMD_PS_FLAG = {"getprop", "persist.atrace.ps"};
    // SystemProperty: heap size
    private static final String[] SP_HEAP_SIZE = {"getprop", "persist.atrace.heapsize"};
    // catch ps info
    private static final String[] PS_CMD = {"/system/bin/ps"};
    // catch top info
    private static final String[] TOP_CMD = {"/system/bin/top", "-t", "-d", "1", "-m", "15", "-n", "10"};

    private static final String[] RUN_ATRACE_0 = {"/system/bin/atrace", "-z", "-t"};
    private static final String[] RUN_ATRACE_1 = {"gfx", "input", "view", "am", "wm", "sm", "res",
                                                    "dalvik", "sched", "freq", "app", "power",
                                                    "hal", "rs", "webview", "audio", "video", "camera",
                                                    "bionic", "idle", "load"};

    private static final String HEAP_SIZE_LOW = "2048";     // 2MB
    private static final String HEAP_SIZE_MEDIUM = "5120";    // 5MB
    private static final String HEAP_SIZE_HIGH = "10240";       //10MB

    private final List<String> mAtraceCmd = new ArrayList<String>();

    private NotificationManager mNotificationManager;
    private WindowManager mWindowManager;
    private PowerManager.WakeLock mWakeLock;
    private Vibrator mVibrator;
    private ImageView mFloatView;

    List<String> mPreAtrace;
    List<String> mPostAtrace;
    String unformatTimeInterval;
    String runningToast;
    String failToast;
    String pathToast;
    boolean mHasLongClicked = false;
    boolean stayedWithinClickDistance = false;
    boolean mActivityUIState = true;
    long touchStartTime;
    int mWindowWidth;
    String mTimeInterval;
    static boolean sShowPsInfo;

    private final UIBinder mBinder = new UIBinder();
    private Callback mCallback;
    private CommandUtil mCommandUtil;
    private final Handler mHandler = new Handler();

    public interface Callback {
        public void notifyChange(boolean changed);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        unformatTimeInterval = getApplication().getResources().getString(R.string.unformat_time_interval);
        runningToast = getApplication().getResources().getString(R.string.running_toast);
        failToast = getApplication().getResources().getString(R.string.command_fail_toast);
        pathToast = getApplication().getResources().getString(R.string.path_toast);

        SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(this);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mCommandUtil = CommandUtil.getInstance(this);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createFloatView();

        prepareProperty();

        sDesiredStoragePath = Environment.getExternalStorageDirectory().getPath() + DEFAULT_SYSTRACE_SUBDIR;
        sIconShowing = mCommandUtil.getBooleanState(StartAtraceActivity.ICON_SHOW);

        /** get Activity's widget in the service, used LayoutInflater instance.
         *  but it's the bad design, you should interact with UI in Activity not Service */
//        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
//        View layout = inflater.inflate(R.layout.activity_systrace, null);
//        EditText et = (EditText)layout.findViewById(R.id.interval);

        /**
         *  NOTE: The list returned by <br>Arrays.asList<br> is a fixed size list.
         *  If you want to add something to the list, you would need to create another
         *  list, and use addAll to add elements to it.
         */
        mPreAtrace = Arrays.asList(RUN_ATRACE_0);
        mPostAtrace = Arrays.asList(RUN_ATRACE_1);

        // set this as foreground service
        setPriorityToForeground(true);

        // keep screen on when icon is showing.
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "MyWakelockTag");
        mWakeLock.acquire();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mFloatView != null) {
            mWindowManager.removeView(mFloatView);
        }
        mWakeLock.release();
        setPriorityToForeground(false);
//        mNotificationManager.cancel(ONGOING_NOTIFICATION_ID);
    }

    /** If EditText changes its value, it can fire at the same time. But you must keep
     *  the service running, that is, the float icon displays.
     *  If user changed the time EditText first, and then open float icon, it will fail
     *  to get mTimeInterval.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
            String key) {
//        if (key.equals(StartAtraceActivity.TIME_INTERVAL)) {
//            mTimeInterval = mCommandUtil.getTimeInterval(key);
//            CommandUtil.myLogger(TAG, "onSharedPreferenceChanged: mTimeInterval=" + mTimeInterval);
//        }
        if (key.equals(StartAtraceActivity.ICON_SHOW)) {
            sIconShowing = mCommandUtil.getBooleanState(key);
            CommandUtil.myLogger(TAG, "onSharedPreferenceChanged: sIconShowing=" + sIconShowing);
        }
    }

    private void createFloatView() {
        mWindowManager = (WindowManager) getApplication().getSystemService(Context.WINDOW_SERVICE);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getMetrics(metrics);
        if (metrics.densityDpi <= DisplayMetrics.DENSITY_HIGH) {
            CommandUtil.myLogger(TAG, "In the low density device");
            params.width = 70;
            params.height = 70;
        }
        // Gravity.BOTTOM can avoid icon bouncing whether status bar is hidden.
        // But it makes the Y coordinate REVERSE!
        params.gravity = Gravity.LEFT | Gravity.BOTTOM;

        Display display = mWindowManager.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        params.x = 0;
        params.y = size.y / 2;
        mWindowWidth = size.x;
        // deprecated after API level 13
//        params.y = mWindowManager.getDefaultDisplay().getHeight() / 2;
//        mWindowWidth = mWindowManager.getDefaultDisplay().getWidth();


        mFloatView = new ImageView(this);
        mFloatView.setImageResource(R.drawable.btn_assistivetouch_enable);
        mFloatView.setOnTouchListener(new View.OnTouchListener() {
            private final WindowManager.LayoutParams paramsF = params;
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartTime = System.currentTimeMillis();
                        initialX = paramsF.x;
                        initialY = paramsF.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        CommandUtil.myLogger(TAG, "ACTION_DOWN:"
                                + "  initialX=" + initialX
                                + ", initialTouchX=" + initialTouchX
                                + ", initialY=" + initialY
                                + ", initialTouchY=" + initialTouchY);
                        stayedWithinClickDistance = true;
                        setClickEffect(true);
                        mHandler.postDelayed(longClickRun, LONG_CLICK_DURATION);
                        break;

                    case MotionEvent.ACTION_MOVE:
                        // determine whether is "MOVE" action
                        if (stayedWithinClickDistance && distance(initialTouchX, initialTouchY,
                                event.getRawX(), event.getRawY()) > MAX_CLICK_DISTANCE) {
                            stayedWithinClickDistance = false;
                            mHandler.removeCallbacks(longClickRun);
                        }
                        paramsF.x = initialX + (int) (event.getRawX() - initialTouchX);
                        // Gravity.BOTTOM makes the Y coordinate REVERSE! So we should *minus*
                        // the relative value to keep the same direction. (Gravity.TOP use *plus*)
                        paramsF.y = initialY - (int) (event.getRawY() - initialTouchY);

                        CommandUtil.myLogger(TAG, "ACTION_MOVE:"
                                + " initialX=" + initialX
                                + ", initialTouchX=" + initialTouchX
                                + ", paramsF.x=" + paramsF.x
                                + ", initialY=" + initialY
                                + ", initialTouchY=" + initialTouchY
                                + ", paramsF.y=" + paramsF.y);

                        mWindowManager.updateViewLayout(mFloatView, paramsF);
//                        updateViewLayout(mFloatView, x, y, null, null);
                        break;

                    case MotionEvent.ACTION_UP:
                        setClickEffect(false);
                        mHandler.removeCallbacks(longClickRun);
                        long touchDuration = System.currentTimeMillis() - touchStartTime;
                        if (touchDuration < MAX_CLICK_DURATION && stayedWithinClickDistance) {

                            // Click event has occurred, so play sound effect
                            v.playSoundEffect(SoundEffectConstants.CLICK);

                            if (!sAtraceRunning) {
                                // get the time directly from SharePreference.
                                mTimeInterval = mCommandUtil.getTimeInterval(StartAtraceActivity.TIME_INTERVAL);
                                if (mTimeInterval == null || mTimeInterval.length() == 0) {
                                    showToast(unformatTimeInterval);
                                    break;
                                }
                                CommandUtil.myLogger(TAG, "catch systrace now!");
                                sAtraceRunning = true;
                                freezeIcon(true);
                                runAtrace();
                            } else {
                                showToast(runningToast);
                            }
                        } else {
                            int endX = ((int) event.getRawX()) > mWindowWidth / 2 ? mWindowWidth : 0;
                            CommandUtil.myLogger(TAG, "ACTION_UP: moving endX=" + endX);
                            overlayAnimation(mFloatView, paramsF.x, endX);
                        }
                        break;

                    case MotionEvent.ACTION_CANCEL:
                        mHandler.removeCallbacks(longClickRun);
                        break;
                }
                return false;
            }
        });

        try {
            mWindowManager.addView(mFloatView, params);
        } catch (Exception e) {
            Log.e(TAG, "Error: add view error!");
            e.printStackTrace();
        }
    }

    final Runnable longClickRun = new Runnable() {
        @Override
        public void run() {
            CommandUtil.myLogger(TAG, "longClickRun: launch activity");
            // Vibrate for 50 milliseconds
            mVibrator.vibrate(50);
//            createNotification();
//            AtraceService.this.stopSelf();
            launchActivity();
        }
    };

    private void updateViewLayout(View view, Integer x, Integer y, Integer w, Integer h) {
        if (view != null) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
            if (x != null) lp.x = x;
            if (y != null) lp.y = y;
            if (w != null && w > 0) lp.width = w;
            if (h != null && h > 0) lp.height = h;
            mWindowManager.updateViewLayout(view, lp);
        }
    }

    private void overlayAnimation(final View view2Animate, int startX, int endX) {
        ValueAnimator translateLeft = ValueAnimator.ofInt(startX, endX);
        translateLeft.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                int val = (Integer) animation.getAnimatedValue();
                updateViewLayout(view2Animate, val, null, null, null);
            }
        });
        translateLeft.setDuration(ANIMATION_DURATION);
        translateLeft.start();
    }

    private void setClickEffect(boolean clicked) {
        if (clicked) {
            mFloatView.setImageResource(R.drawable.btn_assistivetouch_enable);
            mFloatView.setAlpha(0.6f);
        } else {
            mFloatView.setImageResource(R.drawable.btn_assistivetouch_enable);
            mFloatView.setAlpha(1.0f);
        }
    }

    private void freezeIcon(boolean freeze) {
        if (freeze) {
            if (sIconShowing) {
                mFloatView.setImageResource(R.drawable.btn_assistivetouch_disable);
                mFloatView.setAlpha(0.5f);
                mFloatView.setEnabled(false);
            } else {
                mFloatView.setVisibility(View.GONE);
            }
            updateActivityUI(false);
        } else {
            if (sIconShowing) {
                mFloatView.setImageResource(R.drawable.btn_assistivetouch_enable);
                mFloatView.setAlpha(1f);
                mFloatView.setEnabled(true);
            } else {
                mFloatView.setVisibility(View.VISIBLE);
            }
            updateActivityUI(true);
        }
    }

    private void updateActivityUI(boolean enable) {
        CommandUtil.myLogger(TAG, "updateActivityUI: enable=" + enable);
        mActivityUIState = enable;

        if (mCallback != null) {
            mCallback.notifyChange(enable);
        }
    }

    /**
     * Foreground services must have a notification that cannot be dismissed.
     * To create a foreground service you have to call startForeground(...) method
     * from the Service itself passing the id of the notification and the Notification
     * object itself. You can use the id if you want to update the notification later.
     *
     * developer.android.com/guide/components/services.html#Foreground
     */
    private void setPriorityToForeground(boolean foreground) {
        if (foreground) {
            Intent notificationIntent = new Intent(this, StartAtraceActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

            Notification.Builder builder = new Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getResources().getString(R.string.notif_show_title))
            .setContentText(getResources().getString(R.string.notif_show_text));
            builder.setContentIntent(pendingIntent);
            builder.setOngoing(true);
            startForeground(ONGOING_NOTIFICATION_ID, builder.build());
        } else {
            stopForeground(true);
        }
    }

    /**
     * catch Systrace output into SYSTRACE_PATH
     */
    private void runAtrace() {
        final File file = mCommandUtil.createFile(sDesiredStoragePath);
        Thread atraceThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // prepare command
                mAtraceCmd.clear();
                mAtraceCmd.addAll(mPreAtrace);
                mAtraceCmd.add(mTimeInterval);

                // set heap size of the atrace
                mAtraceCmd.add("-b");
                String spHeapSize = mCommandUtil.exec(SP_HEAP_SIZE).trim();
                if (spHeapSize != null && !spHeapSize.isEmpty() && !spHeapSize.equals("0")) {
                    CommandUtil.myLogger(TAG, "set heap size 0f system property:" + spHeapSize);
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

                // change List<String> to String[]
                String[] command = new String[mAtraceCmd.size()];
                final boolean isOK = mCommandUtil.runCommand(mAtraceCmd.toArray(command), file);

                if (isOK) {
                    CommandUtil.myLogger(TAG, "runAtrace: success, now catch addition info!");
                    if (ENABLE_PS_CMD_RESQ || sShowPsInfo) {
                        String ps_file = file.getAbsolutePath() + ".ps";
                        mCommandUtil.runCommand(PS_CMD, new File(ps_file));
                    }
                    String top_file = file.getAbsolutePath() + ".top";
                    mCommandUtil.runCommand(TOP_CMD, new File(top_file));
                } else {
                    mCommandUtil.deleteFile(file.toString());
                }

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        sAtraceRunning = false;
                        freezeIcon(false);
                        if (!isOK) {
                            showToast(failToast);
                        } else {
                            showLongToast(pathToast + sDesiredStoragePath + "/");
                        }
                    }
                });
            }
        });
        atraceThread.setName("atraceThread");
        atraceThread.start();
    }

    // uses dp instead of px
    private float pxToDp(float px) {
        return px / getResources().getDisplayMetrics().density;
    }

    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 -y2;
        // Euclidean distance: a^2+b^2=c^2
        float distanceInPx = FloatMath.sqrt(dx * dx + dy * dy);
        return pxToDp(distanceInPx);
    }

    private void launchActivity() {
        Intent i = new Intent();
        i.setAction(Intent.ACTION_MAIN);
//        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        i.setComponent(new ComponentName(getApplicationContext().getPackageName(),
                StartAtraceActivity.class.getName()));
        getApplicationContext().startActivity(i);
    }

    private void createNotification() {
        Context cx = getApplicationContext();
        Resources res = getApplicationContext().getResources();
        Intent notificationIntent = new Intent(cx, AtraceService.class);
        PendingIntent pendingIntent = PendingIntent.getService(cx, 0, notificationIntent, 0);

        Notification.Builder builder = new Notification.Builder(cx)
            .setSmallIcon(R.drawable.btn_assistivetouch_enable)
            .setContentTitle(res.getString(R.string.notif_show_title))
            .setContentText(res.getString(R.string.notif_show_text));
        builder.setContentIntent(pendingIntent);
        builder.setAutoCancel(true);
        mNotificationManager.notify(ONGOING_NOTIFICATION_ID, builder.build());
    }

    private void prepareProperty() {
        Thread newThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String info = mCommandUtil.exec(SP_CMD_PS_FLAG).trim();
                    sShowPsInfo = Integer.parseInt(info) != 0 ? true : false;
                    CommandUtil.myLogger(TAG, "prepareProperty: sShowPsInfo= " + sShowPsInfo);
                } catch (NumberFormatException e) {
                    sShowPsInfo = false;
                    CommandUtil.myLogger(TAG, "Error for prepareProperty: EXCEPTION!");
                    e.printStackTrace();
                }
            }
        });
        newThread.setName("get_property");
        newThread.start();
    }

    private void showToast(String string) {
        Toast.makeText(getApplication(), string, Toast.LENGTH_SHORT).show();
    }

    private void showLongToast(String string) {
        Toast.makeText(getApplication(), string, Toast.LENGTH_LONG).show();
    }

    public class UIBinder extends Binder {

        public void setCallback(Callback activity) {
            mCallback = activity;
        }

        public void removeCallback() {
            mCallback = null;
        }

        public boolean getUIState () {
            CommandUtil.myLogger(TAG, "getUIState: " + mActivityUIState);
            return mActivityUIState;
        }
    }
}

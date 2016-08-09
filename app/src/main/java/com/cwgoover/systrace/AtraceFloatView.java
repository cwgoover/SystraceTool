
package com.cwgoover.systrace;

import android.animation.ValueAnimator;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.Settings;
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

import com.cwgoover.systrace.toolbox.FileUtil;

import java.io.File;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class AtraceFloatView extends Service implements OnSharedPreferenceChangeListener {

    public static final String TAG = StartAtraceActivity.TAG + ".view";

    public static final int ONGOING_NOTIFICATION_ID = 2018;
    public static final int DIALOG_NOTIFICATION_ID = 2015;
    public static final int ANIMATION_DURATION = 350;
    public static final int LONG_CLICK_DURATION = 1000;

    /**
     * Max allowed duration for a "click", in milliseconds
     */
    private static final int MAX_CLICK_DURATION = 600;

    /**
     * Max allowed distance to move during a "click", in DP.
     */
    private static final int MAX_CLICK_DISTANCE = 15;

    private static boolean sIconShowing = false;

    private NotificationManager mNotificationManager;
    private WindowManager mWindowManager;
    private PowerManager.WakeLock mWakeLock;
    private Vibrator mVibrator;
    private ImageView mFloatView;

    String unformatTimeInterval;
    String failToast;
    String pathToast;
    boolean stayedWithinClickDistance = false;
    boolean mActivityUIState = true;
    long touchStartTime;
    int mWindowWidth;
    String mTimeInterval;

    private final UIBinder mBinder = new UIBinder();
    private Callback mCallback;
    private TaskManager mTaskManager;
    private FileUtil mUtil;
    private final Handler mHandler = new Handler();

    public interface Callback {
        void notifyChange(boolean changed);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        unformatTimeInterval = getApplication().getResources().getString(R.string.unformat_time_interval);
//        runningToast = getApplication().getResources().getString(R.string.running_toast);
        failToast = getApplication().getResources().getString(R.string.command_fail_toast);
        pathToast = getApplication().getResources().getString(R.string.path_toast);

        SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(this);

        mUtil = FileUtil.getInstance(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mTaskManager = TaskManager.getInstance();
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createFloatView();

        sIconShowing = mUtil.getBooleanState(StartAtraceActivity.ICON_SHOW, true);

        /** get Activity's widget in the service, used LayoutInflater instance.
         *  but it's the bad design, you should interact with UI in Activity not Service */
//        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
//        View layout = inflater.inflate(R.layout.activity_systrace, null);
//        EditText et = (EditText)layout.findViewById(R.id.interval);

        // set this as foreground service
        setPriorityToForeground(true);

        // prepare wake lock to keep screen on while catching systrace
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "MyWakelockTag");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mFloatView != null) {
            mWindowManager.removeView(mFloatView);
        }
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        setPriorityToForeground(false);
//        mNotificationManager.cancel(ONGOING_NOTIFICATION_ID);
    }

    /**
     * If EditText changes its value, it can fire at the same time. But you must keep
     * the service running, that is, the float icon displays.
     * If user changed the time EditText first, and then open float icon, it will fail
     * to get mTimeInterval.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
//        if (key.equals(StartAtraceActivity.TIME_INTERVAL)) {
//            mTimeInterval = mUtil.getTimeInterval(key);
//            FileUtil.myLogger(TAG, "onSharedPreferenceChanged: mTimeInterval=" + mTimeInterval);
//        }
        if (key.equals(StartAtraceActivity.ICON_SHOW)) {
            sIconShowing = mUtil.getBooleanState(key, true);
            FileUtil.myLogger(TAG, "onSharedPreferenceChanged: sIconShowing=" + sIconShowing);
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
            FileUtil.myLogger(TAG, "In the low density device");
            params.width = 70;
            params.height = 70;
        }
        // Gravity.BOTTOM can avoid icon bouncing whether status bar is hidden.
        // But it makes the Y coordinate REVERSE!
        params.gravity = Gravity.START | Gravity.BOTTOM;

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
                        FileUtil.myLogger(TAG, "ACTION_DOWN:"
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
                        FileUtil.myLogger(TAG, "ACTION_MOVE:"
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

                            // get the time directly from SharePreference.
                            mTimeInterval = mUtil.getTimeInterval(StartAtraceActivity.TIME_INTERVAL);
                            if (mTimeInterval == null || mTimeInterval.length() == 0) {
                                showToast(unformatTimeInterval);
                                break;
                            }
                            // Running atrace now!
                            // NOTE: the parent object from inner class. "Class.this"
                            mTaskManager.startAtrace(AtraceFloatView.this, mTimeInterval);
                        } else {
                            int endX = ((int) event.getRawX()) > mWindowWidth / 2 ? mWindowWidth : 0;
                            FileUtil.myLogger(TAG, "ACTION_UP: moving endX=" + endX);
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
            FileUtil.myLogger(TAG, "longClickRun: launch activity");
            // Vibrate for 50 milliseconds
            mVibrator.vibrate(50);
//            createNotification();
//            AtraceFloatView.this.stopSelf();
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

    public void showUserInfo(boolean successful, File targetFile) {
        if (!successful) {
            showToast(failToast);
        } else {
            showDescriptionDialogOrToast(targetFile.getAbsolutePath() + ".txt");
        }
    }

    public void updateIconState(boolean freeze, boolean finished) {
        Log.d(TAG, "updateIconState: freeze="+freeze+", finished="+finished);
        if (freeze) {
            // TODO: system can't sleep
            // keep the screen on while catching systrace
            mWakeLock.acquire();

            if (sIconShowing) {
                if (finished) {
                    // Vibrate for 100 milliseconds
                    mVibrator.vibrate(100);
                    mFloatView.setImageResource(R.drawable.btn_assistivetouch_enable);
                    mFloatView.setAlpha(0.5f);
                    mFloatView.setEnabled(false);
                } else {
                    mFloatView.setImageResource(R.drawable.btn_assistivetouch_disable);
                    mFloatView.setAlpha(0.5f);
                    mFloatView.setEnabled(false);
                }
            } else {
                mFloatView.setVisibility(View.GONE);
            }
            updateActivityUI(false);
        } else {
            // release wake lock
            if (mWakeLock.isHeld()) {
                FileUtil.myLogger(TAG, "release wake lock-");
                mWakeLock.release();
            }
            // Vibrate for 100 milliseconds
            mVibrator.vibrate(100);
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
        FileUtil.myLogger(TAG, "updateActivityUI: enable=" + enable);
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
     * <p/>
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

    private void showDescriptionDialogOrToast(String file) {
        boolean isShow = mUtil.getBooleanState(StartAtraceActivity.MENU_SHOW_DIALOG, false);
        String filePath = file.substring(0, file.lastIndexOf(File.separator));
        String toast = pathToast + filePath + File.separator;
        if (isShow) {
            Intent intent = new Intent(getApplicationContext(), DescriptionDialogActivity.class);
            intent.putExtra(DescriptionDialogActivity.FILE_KEY, file);
            intent.putExtra(DescriptionDialogActivity.TOAST_KEY, toast);
            // IMPORTANT: otherwise, your activity will be shown behind dialog whether what session you were in.
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);

            startActivity(intent);
//            createNotification();
        } else {
            showLongToast(toast);
        }
    }

    // uses dp instead of px
    private float pxToDp(float px) {
        return px / getResources().getDisplayMetrics().density;
    }

    private float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
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

    @Deprecated
    private void createNotification() {
        Context cx = getApplicationContext();
//        Resources res = getApplicationContext().getResources();
//        Intent notificationIntent = new Intent(cx, AtraceFloatView.class);
//        PendingIntent pendingIntent = PendingIntent.getService(cx, 0, notificationIntent, 0);
        Intent notificationIntent = new Intent(cx, DescriptionDialogActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(cx, 0, notificationIntent,
                PendingIntent.FLAG_CANCEL_CURRENT);

        Notification.Builder builder = new Notification.Builder(cx)
                .setSmallIcon(R.drawable.btn_assistivetouch_enable)
                .setContentTitle(getString(R.string.notify_show_dialog_title))
                .setContentText(getString(R.string.notify_show_dialog_text));
        builder.setContentIntent(pendingIntent);
        builder.setAutoCancel(true);
        mNotificationManager.notify(DIALOG_NOTIFICATION_ID, builder.build());
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

        @SuppressWarnings("deprecation")
        public void removeCallback() {
            mCallback = null;
        }

        public boolean getUIState() {
            FileUtil.myLogger(TAG, "getUIState: " + mActivityUIState);
            return mActivityUIState;
        }
    }

    /**
     * Here's an exact solution to get current top activity on your Android L/Lollipop & Android M/Marshmallow devices.
     * http://stackoverflow.com/a/33968978
     * <p/>
     * Use Comparator<UsageStats> to sort Collections.
     * http://stackoverflow.com/a/27304318
     */
    @SuppressWarnings("unused")
    private void getTopActivityFromLolipopOnwards() {
        if (needPermissionForBlocking()) {
            // open "Apps with usage access" to check whether allow usage access
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    // E/AndroidRuntime: FATAL EXCEPTION: main
                    // android.util.AndroidRuntimeException: Calling startActivity() from outside of an Activity
                    // context requires the FLAG_ACTIVITY_NEW_TASK flag. Is this really what you want?
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            });
        } else {
            // get current top activity on Android L+
            String topPackageName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                /**
                 * 1. UsageStatsManager was added in API Level 21; you need to be compiling against
                 *    API Level 21 to reference that class. So Set your compileSdkVersion to 21 or higher
                 *    in your build.gradle file
                 * 2. Can't use "getSystemService(Context.USAGE_STATS_SERVICE)"
                 *    Must be some kind of bug in Android Studio. You can disable inspection by adding:
                 *    context.getSystemService("usagestats")
                 */
                UsageStatsManager mUsageStatsManager = (UsageStatsManager) getSystemService("usagestats");
                long time = System.currentTimeMillis();
                // We get usage stats for the last 10 seconds
                List<UsageStats> stats = mUsageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);
                // Sort the stats by the last time used
                if (stats != null) {
                    SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
                    for (UsageStats usageStats : stats) {
                        mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
                    }
                    if (!mySortedMap.isEmpty()) {
                        topPackageName = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
                        Log.i(TAG, "********** : TopPackage name: " + topPackageName);
                    }
                }
            }
        }
    }

    private boolean needPermissionForBlocking() {
        try {
            PackageManager packageManager = getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(getPackageName(), 0);
            AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, applicationInfo.packageName);
            return (mode != AppOpsManager.MODE_ALLOWED);
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }
}

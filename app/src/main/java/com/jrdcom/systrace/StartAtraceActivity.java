
package com.jrdcom.systrace;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import com.jrdcom.systrace.service.AtraceService;
import com.jrdcom.systrace.toolbox.CommandUtil;
import com.jrdcom.systrace.R;

public class StartAtraceActivity extends Activity
            implements OnClickListener, AtraceService.Callback {

    public static final String TAG = "jrdSystrace";
    public static final String SYSTRACE_SERVICE = "com.jrdcom.systrace.service.SystraceService";

    public static final String TIME_INTERVAL = "time";
    public static final String ICON_SHOW = "iconShow";

    Button mStartBtn;
    Button mStopBtn;
    EditText mTimeInterval;

    CommandUtil mUtil;
    AtraceService.UIBinder mBinder;
    AtraceService.Callback mCallback;

    boolean mIsBindService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_systrace);

        mCallback = this;
        mUtil = CommandUtil.getInstance(this);

        mStartBtn = (Button) findViewById(R.id.start);
        mStopBtn = (Button) findViewById(R.id.stop);
        mStartBtn.setOnClickListener(this);
        mStopBtn.setOnClickListener(this);
        mTimeInterval = (EditText) findViewById(R.id.interval);

        String savedTime = mUtil.getTimeInterval(TIME_INTERVAL);
        if (savedTime.isEmpty() || savedTime.equals("0")) {
            mUtil.setTimeInterval(TIME_INTERVAL, mTimeInterval.getText().toString());
            CommandUtil.myLogger(TAG, "default setTimeInterval " + mTimeInterval.getText().toString());
        }
        else {
            mTimeInterval.setText(savedTime);
        }
        // place cursor at the end of text in EditText
        mTimeInterval.setSelection(mTimeInterval.getText().length());

        mTimeInterval.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String time = mTimeInterval.getText().toString();
                CommandUtil.myLogger(TAG, "EditText change, the time=" + time);
                mUtil.setTimeInterval(TIME_INTERVAL, time);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent intent = new Intent(this, AtraceService.class);
        // flag : BIND_AUTO_CREATE (it will bind the service and start the service)
        // flag : 0 (method will return true and will not start service until a call
        //        like startService(Intent) is made to start the service)
        if (!mIsBindService) {
            mIsBindService = true;
            bindService(intent, connection, 0);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsBindService) {
            mIsBindService = false;
            unbindService(connection);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);

        final ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                        this, R.array.icon_actions, android.R.layout.simple_list_item_1);

        MenuItem spinnerItem = menu.findItem(R.id.menu_spinner);
        View view = spinnerItem.getActionView();
        if (view instanceof Spinner) {
            final Spinner spinner = (Spinner) view;
            spinner.setAdapter(adapter);
            // change spinner's default value
            if (mUtil.getBooleanState(ICON_SHOW) == false) {
                spinner.setSelection(1);
            }
            spinner.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                        int position, long id) {
                    switch (parent.getId()) {
                        case R.id.menu_spinner:
                            switch (position) {
                                case 0:
                                    mUtil.setBooleanState(ICON_SHOW, true);
                                    break;
                                case 1:
                                    mUtil.setBooleanState(ICON_SHOW, false);
                                    break;
                            }
                    }
                    return;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onClick(View v) {
        if (v == mStartBtn) {
            Intent intent = new Intent(this, AtraceService.class);
            startService(intent);
        }
        else if (v == mStopBtn) {
            Intent intent = new Intent(this, AtraceService.class);
            if (mIsBindService) {
                CommandUtil.myLogger(TAG, "click stop button, and unbindService");
                mIsBindService = false;
                unbindService(connection);
            }
            stopService(intent);
        }
    }

    @Override
    public void notifyChange(boolean changed) {
        CommandUtil.myLogger(TAG, "notifyChange: changed=" + changed);
        updateUI(changed);
    }


    private void updateUI (boolean enable) {
        mStartBtn.setEnabled(enable);
        mStopBtn.setEnabled(enable);
        mTimeInterval.setEnabled(enable);
    }

    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = (AtraceService.UIBinder) service;
            CommandUtil.myLogger(TAG, "onServiceConnected: create mBinder");
            mBinder.setCallback(mCallback);
            updateUI(mBinder.getUIState());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {}
    };
}

package com.jrdcom.systrace.service;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.jrdcom.systrace.R;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * Created by tcao on 2/26/16.
 * Dialog Activity used to show dialog from service
 */
public class DescriptionDialogActivity extends Activity {
    public static final String TAG = DescriptionDialogActivity.class.getSimpleName();
    public static final String FILE_KEY = "DESCRIP_FILE_NAME";
    public static final String TOAST_KEY = "TOAST_CONTENT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String file = getIntent().getStringExtra(FILE_KEY);

        View descriptionView = LayoutInflater.from(this).inflate(R.layout.show_description, null);
        final EditText edittext = (EditText) descriptionView.findViewById(R.id.user_input);
        // add the toast in the dialog
        TextView toastView = (TextView) descriptionView.findViewById(R.id.toast_id);
        String toast = getIntent().getStringExtra(TOAST_KEY);
        toastView.setText(String.format("Tips: %s", toast));


        // change Dialog style to Holo theme
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppTheme_DialogTheme);
        builder.setTitle(R.string.descrip_alert_title);
        builder.setView(descriptionView);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.alert_ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Note: convert CharSequence to String with toString() method
                final String userInput = edittext.getText().toString();
                if (file != null && !userInput.isEmpty()) {
                    SaveUserFeedbackTask saveTask = new SaveUserFeedbackTask();
                    saveTask.execute(file, userInput);
                } else {
                    finish();
                }
            }
        });
        builder.setNegativeButton(R.string.alert_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
                finish();
            }
        });
        AlertDialog dialog = builder.create();
//        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
//        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        dialog.show();
    }

    private class SaveUserFeedbackTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... params) {
            String path = params[0];
            String data = params[1];

            Writer writer = null;
            try {
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(path)), "utf-8"));
                writer.write(getString(R.string.dlg_write_title));
                writer.write(data);
                writer.write("\n");
            } catch (IOException e) {
                Log.e(TAG, "exception writing data to file", e);
                e.printStackTrace();
            } finally {
                try { writer.close();} catch (Exception e) {}
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            finish();
        }
    }
}


























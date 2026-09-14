package com.ayuu.instapong;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 701;
    private TextView status;
    private TextView debug;
    private Button update;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        debug = findViewById(R.id.debug);
        update = findViewById(R.id.update);
        update.setOnClickListener(v -> UpdateManager.installLatest(this));
        ((Button)findViewById(R.id.accessibility)).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        ((Button)findViewById(R.id.start)).setOnClickListener(v -> requestCapture());
        ((Button)findViewById(R.id.stop)).setOnClickListener(v -> { stopService(new Intent(this,ScreenCaptureService.class)); status.setText("Stopped."); });
        BotController.setUiCallback(text -> runOnUiThread(() -> debug.setText(text)));
        UpdateManager.register(this);
    }

    @Override protected void onResume() {
        super.onResume();
        UpdateManager.check(this, () -> {
            update.setText("UPDATE AVAILABLE — TAP TO INSTALL");
            update.setEnabled(true);
            status.setText("A newer bot build is available. Your data/settings stay with this app.");
        });
    }

    private void requestCapture() {
        if(PongAccessibilityService.instance==null){ status.setText("Enable Instagram Pong Bot in Accessibility Settings first."); return; }
        MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(),REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_CAPTURE)return;
        if(resultCode!=RESULT_OK||data==null){status.setText("Screen capture permission was cancelled.");return;}
        Intent service=new Intent(this,ScreenCaptureService.class)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE,resultCode)
                .putExtra(ScreenCaptureService.EXTRA_DATA,data);
        startForegroundService(service);
        status.setText("Auto Play is running. Switch to Instagram and open Pong.");
    }

    @Override protected void onDestroy() {
        UpdateManager.unregister(this);
        super.onDestroy();
    }
}

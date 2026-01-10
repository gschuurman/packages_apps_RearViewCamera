package com.schuurman.rvc;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

public final class RearViewCameraActivity extends Activity {

    private static final String TAG = "RVC.Activity";
    static final String ACTION_FINISH_RVC = "com.schuurman.rvc.action.FINISH";

    private Camera2Controller mCamera2;
    private SurfaceView mPreview;

    private boolean mReceiverRegistered = false;

    private final BroadcastReceiver mFinishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_FINISH_RVC.equals(intent.getAction())) {
                Log.i(TAG, "Finish requested");
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Appear immediately and stay on top (rear-view camera semantics)
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        setContentView(R.layout.activity_rvc);

        mPreview = findViewById(R.id.preview);
        mCamera2 = new Camera2Controller(this);

        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ✅ Android 13+ requires explicit exported/not-exported flag
        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_FINISH_RVC);

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                        mFinishReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                );
            } else {
                registerReceiver(mFinishReceiver, filter);
            }

            mReceiverRegistered = true;
        }

        hideSystemUi();

        // Start camera AFTER receiver registration
        mCamera2.start(mPreview);
    }

    @Override
    protected void onPause() {
        // Stop camera first to avoid surface teardown races
        mCamera2.stop();

        if (mReceiverRegistered) {
            try {
                unregisterReceiver(mFinishReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver already unregistered — safe to ignore
            }
            mReceiverRegistered = false;
        }

        super.onPause();
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        WindowInsetsController controller = decorView.getWindowInsetsController();

        if (controller != null) {
            controller.hide(
                    WindowInsets.Type.statusBars()
                            | WindowInsets.Type.navigationBars()
            );
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        } else {
            // Legacy fallback
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        }
    }
}

package com.schuurman.rvc;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.WindowManager;

/**
 * Full screen rear view camera. Started when reverse gear is selected (and finished again by
 * {@link GearMonitorService}), or from the settings screen to preview the configuration.
 */
public final class RearViewCameraActivity extends Activity {

    private static final String TAG = "RVC.Activity";
    static final String ACTION_FINISH_RVC = "com.schuurman.rvc.action.FINISH";

    private Camera2Controller mCamera2;
    private TextureView mPreview;

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
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!mReceiverRegistered) {
            registerReceiver(mFinishReceiver, new IntentFilter(ACTION_FINISH_RVC),
                    Context.RECEIVER_NOT_EXPORTED);
            mReceiverRegistered = true;
        }

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
}

package com.schuurman.rvc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.FragmentActivity;

/**
 * Full screen rear view camera. Started when reverse gear is selected (and finished again by
 * {@link GearMonitorService}), or from the settings screen to preview the configuration.
 *
 * A settings button opens the rear view camera settings in a panel next to the live image, so they
 * can be adjusted while looking at the result; changes apply immediately.
 */
public final class RearViewCameraActivity extends FragmentActivity implements RvcConfig.Listener {

    private static final String TAG = "RVC.Activity";
    static final String ACTION_FINISH_RVC = "com.schuurman.rvc.action.FINISH";
    private static final String SETTINGS_TAG = "rvc_settings";
    /** Share of the screen width used by the settings panel. */
    private static final float PANEL_WIDTH_FRACTION = 0.45f;

    private Camera2Controller mCamera2;
    private TextureView mPreview;
    private View mSettingsPanel;

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

    private final OnBackPressedCallback mCloseSettings = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            showSettings(false);
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
        mSettingsPanel = findViewById(R.id.settings_panel);
        mCamera2 = new Camera2Controller(this);

        findViewById(R.id.settings_button).setOnClickListener(
                v -> showSettings(mSettingsPanel.getVisibility() != View.VISIBLE));
        getOnBackPressedDispatcher().addCallback(this, mCloseSettings);
        // The panel survives a configuration change; keep the preview next to it.
        showSettings(getSupportFragmentManager().findFragmentByTag(SETTINGS_TAG) != null
                && savedInstanceState != null
                && savedInstanceState.getBoolean(SETTINGS_TAG));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SETTINGS_TAG, mSettingsPanel.getVisibility() == View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!mReceiverRegistered) {
            registerReceiver(mFinishReceiver, new IntentFilter(ACTION_FINISH_RVC),
                    Context.RECEIVER_NOT_EXPORTED);
            mReceiverRegistered = true;
        }
        RvcConfig.addListener(this);

        // Start camera AFTER receiver registration
        mCamera2.start(mPreview);
    }

    @Override
    protected void onPause() {
        // Stop camera first to avoid surface teardown races
        mCamera2.stop();
        RvcConfig.removeListener(this);

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

    @Override
    public void onRvcConfigChanged(String key) {
        mCamera2.applyConfig(key);
    }

    /** Opens or closes the settings panel; while open, the image only uses the space next to it. */
    private void showSettings(boolean show) {
        if (show && getSupportFragmentManager().findFragmentByTag(SETTINGS_TAG) == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_panel, RvcSettingsFragment.newInCameraInstance(),
                            SETTINGS_TAG)
                    .commitNow();
        }
        // A share of the screen rather than a fixed size: head units run anything from low to high
        // densities, and the car UI preferences need room for their keylines.
        final int panelWidth = Math.round(getResources().getDisplayMetrics().widthPixels
                * PANEL_WIDTH_FRACTION);
        final ViewGroup.LayoutParams panelLp = mSettingsPanel.getLayoutParams();
        panelLp.width = panelWidth;
        mSettingsPanel.setLayoutParams(panelLp);
        mSettingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        final ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) mPreview.getLayoutParams();
        lp.setMarginEnd(show ? panelWidth : 0);
        mPreview.setLayoutParams(lp);
        mCloseSettings.setEnabled(show);
    }
}

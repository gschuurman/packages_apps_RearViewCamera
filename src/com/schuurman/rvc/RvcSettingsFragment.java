package com.schuurman.rvc;

import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.android.car.ui.preference.PreferenceFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Rear view camera settings: which camera and stream to use and how to show it. The camera and
 * stream lists come from the camera itself; the values are kept in {@link RvcConfig}.
 */
public final class RvcSettingsFragment extends PreferenceFragment {
    private static final String TAG = "RVC.Settings";
    private static final String KEY_PREVIEW = "preview";
    private static final String ARG_IN_CAMERA = "in_camera";

    /** The settings as a panel on the camera screen: no "show camera" entry there. */
    static RvcSettingsFragment newInCameraInstance() {
        final RvcSettingsFragment fragment = new RvcSettingsFragment();
        final Bundle args = new Bundle();
        args.putBoolean(ARG_IN_CAMERA, true);
        fragment.setArguments(args);
        return fragment;
    }

    private CameraManager mCameraManager;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setPreferenceDataStore(new RvcConfig.DataStore());
        setPreferencesFromResource(R.xml.rvc_settings, rootKey);
        mCameraManager = requireContext().getSystemService(CameraManager.class);

        findPreference(RvcConfig.KEY_CAMERA_ID).setOnPreferenceChangeListener((p, value) -> {
            // Another camera has other streams: go back to automatic.
            new RvcConfig.DataStore().putString(RvcConfig.KEY_STREAM_SIZE, "");
            updateStreamSizes((String) value);
            return true;
        });
        final Preference preview = findPreference(KEY_PREVIEW);
        if (getArguments() != null && getArguments().getBoolean(ARG_IN_CAMERA)) {
            getPreferenceScreen().removePreference(preview);
        } else {
            preview.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(requireContext(), RearViewCameraActivity.class));
                return true;
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateCameras();
    }

    private void updateCameras() {
        final ListPreference cameraPref = findPreference(RvcConfig.KEY_CAMERA_ID);
        final List<CharSequence> entries = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        entries.add(getString(R.string.rvc_camera_auto));
        values.add("");
        String cameraId = null;
        try {
            for (String id : Camera2Controller.getExternalCameraIds(mCameraManager)) {
                entries.add(getString(R.string.rvc_camera_entry, id));
                values.add(id);
            }
            cameraId = Camera2Controller.findCameraId(mCameraManager, RvcConfig.getCameraId());
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to list the cameras", e);
        }
        cameraPref.setEntries(entries.toArray(new CharSequence[0]));
        cameraPref.setEntryValues(values.toArray(new CharSequence[0]));
        cameraPref.setValue(RvcConfig.getCameraId());
        cameraPref.setSummary(cameraId == null ? getString(R.string.rvc_no_camera)
                : getString(R.string.rvc_camera_entry, cameraId));
        updateStreamSizes(RvcConfig.getCameraId());
    }

    private void updateStreamSizes(String configuredCameraId) {
        final ListPreference sizePref = findPreference(RvcConfig.KEY_STREAM_SIZE);
        final List<CharSequence> entries = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        entries.add(getString(R.string.rvc_stream_auto));
        values.add("");
        Size current = null;
        try {
            final String cameraId = Camera2Controller.findCameraId(mCameraManager,
                    configuredCameraId);
            if (cameraId != null) {
                for (Size size : Camera2Controller.getStreamSizes(mCameraManager, cameraId)) {
                    entries.add(describe(size));
                    values.add(RvcConfig.toString(size));
                }
                current = Camera2Controller.chooseStreamSize(mCameraManager, cameraId,
                        RvcConfig.getStreamSize());
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(TAG, "Failed to list the streams", e);
        }
        sizePref.setEntries(entries.toArray(new CharSequence[0]));
        sizePref.setEntryValues(values.toArray(new CharSequence[0]));
        sizePref.setValue(RvcConfig.getStreamSize() == null ? ""
                : RvcConfig.toString(RvcConfig.getStreamSize()));
        sizePref.setEnabled(current != null);
        sizePref.setSummary(current == null ? null : describe(current));
    }

    /** "720 × 576 (PAL)": analog capture sizes tell which video standard they decode. */
    private String describe(Size size) {
        final String text = getString(R.string.rvc_stream_entry, size.getWidth(), size.getHeight());
        final int h = size.getHeight();
        if (Camera2Controller.isAnalogSize(size)) {
            return getString(h == 576 || h == 288 ? R.string.rvc_stream_pal
                    : R.string.rvc_stream_ntsc, text);
        }
        return text;
    }
}

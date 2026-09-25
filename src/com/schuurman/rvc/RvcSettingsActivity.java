package com.schuurman.rvc;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

import com.android.car.ui.core.CarUi;
import com.android.car.ui.toolbar.NavButtonMode;
import com.android.car.ui.toolbar.ToolbarController;

/** Rear view camera settings, shown as an entry on the CarSettings home screen. */
public final class RvcSettingsActivity extends FragmentActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rvc_settings);

        final ToolbarController toolbar = CarUi.requireToolbar(this);
        toolbar.setTitle(R.string.rvc_settings_title);
        toolbar.setNavButtonMode(NavButtonMode.BACK);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new RvcSettingsFragment())
                    .commit();
        }
    }
}

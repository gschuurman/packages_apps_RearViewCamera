package com.schuurman.rvc;

import android.app.Service;
import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.property.CarPropertyManager;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

/**
 * Drop-in gear monitor that does NOT depend on CarPropertyValue APIs.
 *
 * Rationale: CarPropertyValue / callback method signatures vary across branches.
 * Polling is stable, simple, and sufficient for RVC (10 Hz by default).
 */
public final class GearMonitorService extends Service {
    private static final String TAG = "RVC.GearMonitorService";

    // 10Hz polling: good compromise for responsiveness and load
    private static final int POLL_INTERVAL_MS = 100;

    private HandlerThread mThread;
    private Handler mHandler;

    private Car mCar;
    private CarPropertyManager mCarPropertyManager;

    private boolean mInReverse = false;

    private final Runnable mPollTask = new Runnable() {
        @Override
        public void run() {
            try {
                if (mCar == null || !mCar.isConnected()) {
                    // Retry connecting periodically
                    ensureCarConnected();
                } else if (mCarPropertyManager == null) {
                    tryGetManagers();
                } else {
                    pollGearOnce();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Poll loop exception", t);
                // continue
            } finally {
                if (mHandler != null) {
                    mHandler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mThread = new HandlerThread("rvc-gear-monitor");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        // Start the loop immediately
        mHandler.post(mPollTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        disconnectCar();
        if (mThread != null) {
            mThread.quitSafely();
            mThread = null;
        }
        mHandler = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureCarConnected() {
        if (mCar == null) {
            try {
                // Older-stable API: createCar(Context)
                mCar = Car.createCar(getApplicationContext());
            } catch (Throwable t) {
                Log.e(TAG, "Car.createCar failed", t);
                mCar = null;
                return;
            }
        }

        if (!mCar.isConnected()) {
            try {
                mCar.connect();
                Log.i(TAG, "Car.connect() called");
            } catch (Throwable t) {
                Log.w(TAG, "Car.connect() failed", t);
            }
        }
    }

    private void tryGetManagers() {
        try {
            Object mgr = mCar.getCarManager(Car.PROPERTY_SERVICE);
            if (mgr instanceof CarPropertyManager) {
                mCarPropertyManager = (CarPropertyManager) mgr;
                Log.i(TAG, "CarPropertyManager acquired");
            } else {
                Log.w(TAG, "PROPERTY_SERVICE not a CarPropertyManager: " + mgr);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get CarPropertyManager", t);
            mCarPropertyManager = null;
        }
    }

    private void pollGearOnce() {
        final int gear;
        try {
            // areaId 0 for global
            gear = mCarPropertyManager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0);
        } catch (Throwable t) {
            // This can happen briefly during boot / car service init
            Log.w(TAG, "getIntProperty(GEAR_SELECTION) failed", t);
            return;
        }

        boolean nowReverse = (gear == VehicleGear.GEAR_REVERSE);
        if (nowReverse != mInReverse) {
            mInReverse = nowReverse;
            Log.i(TAG, "Gear change detected: reverse=" + mInReverse + " raw=" + gear);
            if (mInReverse) {
                launchRearView();
            } else {
                closeRearView();
            }
        }
    }

    private void disconnectCar() {
        try {
            mCarPropertyManager = null;
            if (mCar != null) {
                if (mCar.isConnected()) {
                    mCar.disconnect();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            mCar = null;
        }
    }

    private void launchRearView() {
        Intent i = new Intent(this, RearViewCameraActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start RearViewCameraActivity", t);
        }
    }

    private void closeRearView() {
        Intent i = new Intent(RearViewCameraActivity.ACTION_FINISH_RVC);
        i.setPackage(getPackageName());
        try {
            sendBroadcast(i);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to request RVC finish", t);
        }
    }
}

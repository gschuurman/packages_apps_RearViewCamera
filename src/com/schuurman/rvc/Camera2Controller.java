package com.schuurman.rvc;

import android.content.Context;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Arrays;

final class Camera2Controller {
    private static final String TAG = "RVC.Camera2";

    private final Context mContext;
    private final CameraManager mCameraManager;

    private HandlerThread mThread;
    private Handler mHandler;

    private CameraDevice mCamera;
    private CameraCaptureSession mSession;

    private String mCameraId;
    private Size mPreviewSize;

    Camera2Controller(Context ctx) {
        mContext = ctx;
        mCameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    void start(SurfaceView surfaceView) {
        startThread();

        SurfaceHolder holder = surfaceView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                openExternalCameraAndStartPreview(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // No-op; could reconfigure if desired.
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stop();
            }
        });

        // If surface already created, holder callback might not fire immediately on some devices.
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            openExternalCameraAndStartPreview(holder.getSurface());
        }
    }

    void stop() {
        closeSession();
        closeCamera();
        stopThread();
    }

    private void startThread() {
        if (mThread != null) return;
        mThread = new HandlerThread("rvc-camera2");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
    }

    private void stopThread() {
        if (mThread == null) return;
        mThread.quitSafely();
        mThread = null;
        mHandler = null;
    }

    private void openExternalCameraAndStartPreview(Surface surface) {
        if (mCamera != null || mHandler == null) return;

        mHandler.post(() -> {
            try {
                mCameraId = findExternalCameraId();
                if (mCameraId == null) {
                    Log.e(TAG, "No EXTERNAL camera found");
                    return;
                }
                mPreviewSize = choosePreviewSize(mCameraId);
                Log.i(TAG, "Opening cameraId=" + mCameraId + " preview=" + mPreviewSize);

                mCameraManager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        mCamera = camera;
                        createPreviewSession(surface);
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        Log.w(TAG, "Camera disconnected");
                        closeCamera();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        Log.e(TAG, "Camera error=" + error);
                        closeCamera();
                    }
                }, mHandler);

            } catch (SecurityException se) {
                Log.e(TAG, "Missing CAMERA permission or blocked by policy", se);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open camera", e);
            }
        });
    }

    private String findExternalCameraId() throws CameraAccessException {
        for (String id : mCameraManager.getCameraIdList()) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                return id;
            }
        }
        return null;
    }

    private Size choosePreviewSize(String cameraId) throws CameraAccessException {
        CameraCharacteristics c = mCameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(640, 480);
        }

        Size[] sizes = map.getOutputSizes(SurfaceHolder.class);
        if (sizes == null || sizes.length == 0) {
            // Some devices report sizes for SurfaceTexture instead; fall back
            sizes = map.getOutputSizes(Surface.class);
        }
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }

        // Prefer 1280x720 if available; else pick the largest <= 1920x1080; else largest.
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() == 1280 && s.getHeight() == 720) {
                return s;
            }
        }
        for (Size s : sizes) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                if (best == null || (s.getWidth() * s.getHeight()) > (best.getWidth() * best.getHeight())) {
                    best = s;
                }
            }
        }
        if (best != null) return best;

        // Fall back to absolute largest.
        best = sizes[0];
        for (Size s : sizes) {
            if ((s.getWidth() * s.getHeight()) > (best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best;
    }

    private void createPreviewSession(Surface surface) {
        if (mCamera == null) return;

        try {
            final CaptureRequest.Builder req =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(surface);
            req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            mCamera.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mSession = session;
                            try {
                                session.setRepeatingRequest(req.build(), null, mHandler);
                                Log.i(TAG, "Preview started");
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to start repeating request", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Preview session configure failed");
                            closeSession();
                        }
                    }, mHandler);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create preview session", e);
            closeSession();
        }
    }

    private void closeSession() {
        if (mSession != null) {
            try {
                mSession.stopRepeating();
            } catch (Exception ignored) {}
            try {
                mSession.close();
            } catch (Exception ignored) {}
            mSession = null;
        }
    }

    private void closeCamera() {
        if (mCamera != null) {
            try {
                mCamera.close();
            } catch (Exception ignored) {}
            mCamera = null;
        }
    }
}

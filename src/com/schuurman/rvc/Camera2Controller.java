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

    // Delay to allow SurfaceFlinger to commit fixed buffer geometry before HAL binds.
    private static final long OPEN_CAMERA_DELAY_MS = 50L;

    // One-time restart to force clean stream/buffer negotiation on some AAOS stacks.
    private static final long RESTART_PREVIEW_DELAY_MS = 100L;

    private final Context mContext;
    private final CameraManager mCameraManager;

    private HandlerThread mThread;
    private Handler mHandler;

    private CameraDevice mCamera;
    private CameraCaptureSession mSession;

    private String mCameraId;
    private Size mPreviewSize;

    // Track whether start() has been called; prevents reentry from callback + "surface already valid" path.
    private boolean mStarted;

    // Keep reference to the current holder to control buffer sizing.
    private SurfaceHolder mHolder;

    Camera2Controller(Context ctx) {
        mContext = ctx;
        mCameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    void start(SurfaceView surfaceView) {
        startThread();

        mStarted = true;
        mHolder = surfaceView.getHolder();

        mHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // IMPORTANT: open using holder, not surface, so we can setFixedSize first.
                openExternalCameraAndStartPreview(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // If surface changes while running, rebuild cleanly.
                // This avoids stale buffers with different geometry.
                if (!mStarted) return;
                restartPreviewIfRunning(holder);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stop();
            }
        });

        // If surface already exists, start immediately (but still go through holder path).
        Surface s = mHolder.getSurface();
        if (s != null && s.isValid()) {
            openExternalCameraAndStartPreview(mHolder);
        }
    }

    void stop() {
        mStarted = false;
        closeSession();
        closeCamera();
        stopThread();
        mHolder = null;
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

    /**
     * AAOS fix: lock Surface buffer geometry BEFORE opening camera.
     * Many external camera/HWC stacks glitch if HAL binds to a transient Surface buffer size.
     */
    private void openExternalCameraAndStartPreview(SurfaceHolder holder) {
        if (!mStarted || holder == null) return;
        if (mHandler == null) return;

        // Prevent double opens from (a) surfaceCreated and (b) "surface already valid" path.
        if (mCamera != null) return;

        mHandler.post(() -> {
            try {
                mCameraId = findExternalCameraId();
                if (mCameraId == null) {
                    Log.e(TAG, "No EXTERNAL camera found");
                    return;
                }

                mPreviewSize = choosePreviewSize(mCameraId);
                Log.i(TAG, "Opening cameraId=" + mCameraId + " preview=" + mPreviewSize);

                // CRITICAL: force buffer queue to match camera stream size.
                // This avoids first-open stride/segment distortion.
                holder.setFixedSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

                final Surface surface = holder.getSurface();
                if (surface == null || !surface.isValid()) {
                    Log.e(TAG, "Surface not valid after setFixedSize()");
                    return;
                }

                // Let SurfaceFlinger commit the new geometry before HAL binds.
                mHandler.postDelayed(() -> {
                    if (!mStarted || mCamera != null) return;

                    try {
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
                }, OPEN_CAMERA_DELAY_MS);

            } catch (Exception e) {
                Log.e(TAG, "Failed to prepare camera", e);
            }
        });
    }

    private void restartPreviewIfRunning(SurfaceHolder holder) {
        if (mHandler == null) return;
        mHandler.post(() -> {
            // Only restart if we already have a camera; otherwise surfaceCreated will handle it.
            if (mCamera == null) return;

            Log.i(TAG, "Surface changed; restarting preview session");
            closeSession();

            // Re-assert fixed size in case SurfaceView changed its buffers.
            if (mPreviewSize != null && holder != null) {
                try {
                    holder.setFixedSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } catch (Exception ignored) {}
            }

            Surface surface = holder != null ? holder.getSurface() : null;
            if (surface != null && surface.isValid()) {
                createPreviewSession(surface);
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

        // Prefer SurfaceHolder sizes (SurfaceView), otherwise fall back.
        Size[] sizes = map.getOutputSizes(SurfaceHolder.class);
        if (sizes == null || sizes.length == 0) {
            sizes = map.getOutputSizes(Surface.class);
        }
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }

        // Prefer 1280x720 if available; else pick the largest <= 1920x1080; else largest.
        for (Size s : sizes) {
            if (s.getWidth() == 1280 && s.getHeight() == 720) {
                return s;
            }
        }

        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                if (best == null ||
                        (s.getWidth() * s.getHeight()) > (best.getWidth() * best.getHeight())) {
                    best = s;
                }
            }
        }
        if (best != null) return best;

        best = sizes[0];
        for (Size s : sizes) {
            if ((s.getWidth() * s.getHeight()) > (best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best;
    }

    private void createPreviewSession(Surface surface) {
        if (mCamera == null || surface == null || !surface.isValid()) return;

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
                                final CaptureRequest previewRequest = req.build();
                                session.setRepeatingRequest(previewRequest, null, mHandler);
                                Log.i(TAG, "Preview started");

                                // One-time restart: fixes certain external camera/HWC first-bind glitches.
                                mHandler.postDelayed(() -> {
                                    try {
                                        if (mSession != null) {
                                            mSession.stopRepeating();
                                            mSession.setRepeatingRequest(previewRequest, null, mHandler);
                                            Log.i(TAG, "Preview restarted (stability pass)");
                                        }
                                    } catch (Exception ignored) {}
                                }, RESTART_PREVIEW_DELAY_MS);

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

package com.schuurman.rvc;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shows the rear view camera in a TextureView, using the camera, stream size, mirroring and scaling
 * configured in the settings (see {@link RvcConfig}).
 */
final class Camera2Controller {
    private static final String TAG = "RVC.Camera2";

    private final CameraManager mCameraManager;

    private HandlerThread mThread;
    private Handler mHandler;

    private TextureView mView;
    private CameraDevice mCamera;
    private CameraCaptureSession mSession;
    private Surface mSurface;
    private Size mPreviewSize;
    private boolean mStarted;

    private final TextureView.SurfaceTextureListener mTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    openCamera(texture);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width,
                        int height) {
                    updateTransform();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    stop();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
            };

    Camera2Controller(Context context) {
        mCameraManager = context.getSystemService(CameraManager.class);
    }

    void start(TextureView view) {
        if (mStarted) return;
        mStarted = true;
        mView = view;
        mThread = new HandlerThread("rvc-camera2");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        view.setSurfaceTextureListener(mTextureListener);
        if (view.isAvailable()) {
            openCamera(view.getSurfaceTexture());
        }
    }

    void stop() {
        if (!mStarted) return;
        mStarted = false;
        final HandlerThread thread = mThread;
        mHandler.post(() -> {
            closeCamera();
            thread.quitSafely();
        });
        mThread = null;
        mHandler = null;
        mView = null;
    }

    private void openCamera(SurfaceTexture texture) {
        if (!mStarted) return;
        final Handler handler = mHandler;
        handler.post(() -> {
            if (mCamera != null) return;
            try {
                final String cameraId = findCameraId(mCameraManager, RvcConfig.getCameraId());
                if (cameraId == null) {
                    Log.e(TAG, "No external camera found");
                    return;
                }
                mPreviewSize = chooseStreamSize(mCameraManager, cameraId, RvcConfig.getStreamSize());
                Log.i(TAG, "Opening camera " + cameraId + " stream " + mPreviewSize);

                // The buffers must have the stream's size, or the image is scaled/distorted twice.
                texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                mSurface = new Surface(texture);
                updateTransform();

                mCameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        if (!mStarted) {
                            camera.close();
                            return;
                        }
                        mCamera = camera;
                        createSession();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        Log.w(TAG, "Camera disconnected");
                        closeCamera();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        Log.e(TAG, "Camera error " + error);
                        closeCamera();
                    }
                }, handler);
            } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                Log.e(TAG, "Failed to open camera", e);
            }
        });
    }

    private void createSession() {
        try {
            final CaptureRequest.Builder request =
                    mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(mSurface);
            request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mCamera.createCaptureSession(Arrays.asList(mSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            mSession = session;
                            try {
                                session.setRepeatingRequest(request.build(), null, mHandler);
                                Log.i(TAG, "Preview started");
                            } catch (CameraAccessException | IllegalStateException e) {
                                Log.e(TAG, "Failed to start the preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Preview session configuration failed");
                        }
                    }, mHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to create the preview session", e);
        }
    }

    private void closeCamera() {
        if (mSession != null) {
            mSession.close();
            mSession = null;
        }
        if (mCamera != null) {
            mCamera.close();
            mCamera = null;
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
    }

    /** Scales the stream into the view as configured (fit/fill/stretch), mirrored if configured. */
    private void updateTransform() {
        final TextureView view = mView;
        final Size size = mPreviewSize;
        if (view == null || size == null) return;
        view.post(() -> {
            final float viewWidth = view.getWidth();
            final float viewHeight = view.getHeight();
            if (viewWidth == 0 || viewHeight == 0) return;

            // A TextureView stretches the buffer to the whole view; undo that for fit/fill.
            float scaleX = 1f;
            float scaleY = 1f;
            final String scale = RvcConfig.getScale();
            if (!RvcConfig.SCALE_STRETCH.equals(scale)) {
                final float aspect = getDisplayAspect(size);
                final float viewAspect = viewWidth / viewHeight;
                final boolean fit = !RvcConfig.SCALE_FILL.equals(scale);
                if ((viewAspect > aspect) == fit) {
                    scaleX = aspect / viewAspect;   // full height
                } else {
                    scaleY = viewAspect / aspect;   // full width
                }
            }
            if (RvcConfig.isMirrored()) {
                scaleX = -scaleX;
            }
            final Matrix matrix = new Matrix();
            matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
            view.setTransform(matrix);
        });
    }

    /**
     * Analog (PAL/NTSC) capture sizes have non-square pixels: their picture is 4:3 whatever the
     * number of samples per line.
     */
    static float getDisplayAspect(Size size) {
        if (isAnalogSize(size)) {
            return 4f / 3f;
        }
        return (float) size.getWidth() / size.getHeight();
    }

    static boolean isAnalogSize(Size size) {
        final int w = size.getWidth();
        final int h = size.getHeight();
        return (w == 720 || w == 704 || w == 640) && (h == 576 || h == 480 || h == 288 || h == 240)
                && !(w == 640 && h == 480);
    }

    /** @return the external cameras, in camera id order */
    static List<String> getExternalCameraIds(CameraManager manager) throws CameraAccessException {
        final List<String> ids = new ArrayList<>();
        for (String id : manager.getCameraIdList()) {
            final Integer facing =
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** @return the configured camera if it is connected, else the first external camera */
    static String findCameraId(CameraManager manager, String configured)
            throws CameraAccessException {
        final List<String> ids = getExternalCameraIds(manager);
        if (ids.contains(configured)) {
            return configured;
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** @return the stream sizes the camera offers for a preview, largest first */
    static Size[] getStreamSizes(CameraManager manager, String cameraId)
            throws CameraAccessException {
        final StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        final Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null) {
            return new Size[0];
        }
        Arrays.sort(sizes, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(),
                (long) a.getWidth() * a.getHeight()));
        return sizes;
    }

    /**
     * @return the configured size if the camera offers it; else, automatically, the camera's
     *         native analog size if it has one (upscaled modes only blur the picture), else the
     *         largest size up to 1920x1080
     */
    static Size chooseStreamSize(CameraManager manager, String cameraId, Size configured)
            throws CameraAccessException {
        final Size[] sizes = getStreamSizes(manager, cameraId);
        if (sizes.length == 0) {
            return new Size(640, 480);
        }
        for (Size size : sizes) {
            if (size.equals(configured)) return size;
        }
        for (Size size : sizes) {
            if (isAnalogSize(size)) return size;
        }
        for (Size size : sizes) {
            if (size.getWidth() <= 1920 && size.getHeight() <= 1080) return size;
        }
        return sizes[sizes.length - 1];
    }
}

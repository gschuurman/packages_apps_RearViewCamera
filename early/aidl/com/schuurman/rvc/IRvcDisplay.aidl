package com.schuurman.rvc;

import android.view.Surface;

/**
 * The rear view camera's layer on the main display, above everything (the boot animation
 * included), served by rvc_display. Used by rvc_early before Android has booted: rvc_early runs as
 * AID_AUTOMOTIVE_EVS (the uid cameraserver serves that early), and only graphics/system may create
 * a top-level SurfaceFlinger layer.
 */
interface IRvcDisplay {
    /** A surface for a camera stream of the given size (the layer is created or resized). */
    Surface getSurface(int width, int height);

    /**
     * Shows the layer on a black background.
     *
     * @param mirror flip the image horizontally
     * @param scale  "fit" (whole image), "fill" (crop to the screen) or "stretch"
     */
    void show(boolean mirror, @utf8InCpp String scale);

    /** Hides the layer. */
    void hide();
}

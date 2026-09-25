/*
 * rvc_display: the rear view camera's full screen layer while Android boots.
 *
 * rvc_early runs as AID_AUTOMOTIVE_EVS, the only client cameraserver serves before system_server,
 * but SurfaceFlinger only lets graphics/system create a top-level layer that early (no permission
 * service yet). So this helper, running as graphics like the boot animation, owns the layer and hands
 * its Surface to rvc_early (IRvcDisplay). Being the owner it also mirrors and scales the image as
 * configured, which a plain camera stream into a window can't.
 */

#include <aidl/com/schuurman/rvc/BnRvcDisplay.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/view/Surface.h>
#include <ui/DisplayMode.h>
#include <ui/DisplayState.h>
#include <ui/Rect.h>
#include <ui/Transform.h>

#include <mutex>

namespace {

using ::android::sp;
using ::android::SurfaceComposerClient;
using ::android::SurfaceControl;
using ::ndk::ScopedAStatus;

constexpr const char* kServiceName = "rvc_display";
// Above everything, like the car display proxy used to be; the black background right below.
constexpr int32_t kLayerZ = 0x7FFFFFFF;

// Same rule as the app (Camera2Controller.getDisplayAspect): analog (PAL/NTSC) capture sizes have
// non-square pixels and a 4:3 picture.
float displayAspect(int32_t width, int32_t height) {
    const bool analogWidth = width == 720 || width == 704 || width == 640;
    const bool analogHeight = height == 576 || height == 480 || height == 288 || height == 240;
    if (analogWidth && analogHeight && !(width == 640 && height == 480)) return 4.f / 3.f;
    return static_cast<float>(width) / height;
}

class RvcDisplay : public aidl::com::schuurman::rvc::BnRvcDisplay {
  public:
    ScopedAStatus getSurface(int32_t width, int32_t height,
                             aidl::android::view::Surface* _aidl_return) override {
        std::lock_guard lock(mLock);
        if (width <= 0 || height <= 0) return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        if (!initDisplay()) return ScopedAStatus::fromServiceSpecificError(1);

        if (mLayer == nullptr || width != mWidth || height != mHeight) {
            if (mLayer != nullptr) SurfaceComposerClient::Transaction{}.reparent(mLayer, nullptr).apply();
            mLayer = mClient->createSurface(android::String8("RearViewCamera"), width, height,
                                            android::PIXEL_FORMAT_RGBX_8888,
                                            android::ISurfaceComposerClient::eOpaque);
            if (mLayer == nullptr || !mLayer->isValid()) {
                LOG(ERROR) << "Failed to create the camera layer";
                mLayer = nullptr;
                return ScopedAStatus::fromServiceSpecificError(2);
            }
            mWidth = width;
            mHeight = height;
        }
        _aidl_return->reset(mLayer->getSurface().get());
        return ScopedAStatus::ok();
    }

    ScopedAStatus show(bool mirror, const std::string& scale) override {
        std::lock_guard lock(mLock);
        if (mLayer == nullptr) return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
        android::ui::DisplayState state;
        SurfaceComposerClient::getDisplayState(mDisplayToken, &state);

        // Scale the stream into the screen: fit (whole image, black bars), fill (crop) or stretch.
        const float screenW = mScreen.getWidth();
        const float screenH = mScreen.getHeight();
        float w = screenW;
        float h = screenH;
        if (scale != "stretch") {
            const float aspect = displayAspect(mWidth, mHeight);
            const bool fit = scale != "fill";
            if ((screenW / screenH > aspect) == fit) {
                w = screenH * aspect;  // full height
            } else {
                h = screenW / aspect;  // full width
            }
        }
        const android::Rect destination(static_cast<int32_t>((screenW - w) / 2),
                                        static_cast<int32_t>((screenH - h) / 2),
                                        static_cast<int32_t>((screenW + w) / 2),
                                        static_cast<int32_t>((screenH + h) / 2));

        SurfaceComposerClient::Transaction t;
        t.setDisplayLayerStack(mDisplayToken, state.layerStack);
        t.setLayerStack(mBackground, state.layerStack)
                .setLayer(mBackground, kLayerZ - 1)
                .setCrop(mBackground, mScreen)
                .show(mBackground);
        t.setLayerStack(mLayer, state.layerStack)
                .setLayer(mLayer, kLayerZ)
                .setGeometry(mLayer, android::Rect(mWidth, mHeight), destination,
                             mirror ? android::ui::Transform::FLIP_H : 0)
                .show(mLayer);
        if (auto status = t.apply(); status != android::NO_ERROR) {
            LOG(ERROR) << "Failed to show the camera layer: " << status;
            return ScopedAStatus::fromServiceSpecificError(3);
        }
        return ScopedAStatus::ok();
    }

    ScopedAStatus hide() override {
        std::lock_guard lock(mLock);
        if (mLayer != nullptr) {
            SurfaceComposerClient::Transaction{}.hide(mLayer).hide(mBackground).apply();
        }
        return ScopedAStatus::ok();
    }

  private:
    bool initDisplay() {
        if (mClient != nullptr) return true;
        const auto ids = SurfaceComposerClient::getPhysicalDisplayIds();
        if (ids.empty()) {
            LOG(ERROR) << "No display";
            return false;
        }
        mDisplayToken = SurfaceComposerClient::getPhysicalDisplayToken(ids.front());
        android::ui::DisplayMode mode;
        android::ui::DisplayState state;
        if (mDisplayToken == nullptr ||
            SurfaceComposerClient::getActiveDisplayMode(mDisplayToken, &mode) != android::NO_ERROR ||
            SurfaceComposerClient::getDisplayState(mDisplayToken, &state) != android::NO_ERROR) {
            LOG(ERROR) << "Failed to read the display";
            return false;
        }
        int32_t width = mode.resolution.getWidth();
        int32_t height = mode.resolution.getHeight();
        if (state.orientation == android::ui::ROTATION_90 ||
            state.orientation == android::ui::ROTATION_270) {
            std::swap(width, height);
        }
        mScreen = android::Rect(width, height);

        sp<SurfaceComposerClient> client = sp<SurfaceComposerClient>::make();
        if (client->initCheck() != android::NO_ERROR) return false;
        mBackground = client->createSurface(android::String8("RearViewCameraBackground"), 0, 0,
                                            android::PIXEL_FORMAT_RGBA_8888,
                                            android::ISurfaceComposerClient::eFXSurfaceEffect);
        if (mBackground == nullptr) return false;
        SurfaceComposerClient::Transaction{}.setColor(mBackground, android::half3(0, 0, 0)).apply();
        mClient = client;
        LOG(INFO) << "Display " << width << "x" << height;
        return true;
    }

    std::mutex mLock;
    sp<SurfaceComposerClient> mClient;
    sp<android::IBinder> mDisplayToken;
    android::Rect mScreen;
    sp<SurfaceControl> mBackground;
    sp<SurfaceControl> mLayer;
    int32_t mWidth = 0;
    int32_t mHeight = 0;
};

}  // namespace

int main() {
    android::base::InitLogging(nullptr, android::base::LogdLogger(android::base::SYSTEM));
    auto service = ndk::SharedRefBase::make<RvcDisplay>();
    if (AServiceManager_addService(service->asBinder().get(), kServiceName) != STATUS_OK) {
        LOG(FATAL) << "Failed to register " << kServiceName;
    }
    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();
    ABinderProcess_joinThreadPool();
    return 1;
}

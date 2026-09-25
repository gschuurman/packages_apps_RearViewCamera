/*
 * rvc_early: the rear view camera while Android is still booting.
 *
 * Before system_server runs there is no CarService, no activity and no window manager, so this
 * native service does it all itself:
 * - reads the gear from the VHAL (GEAR_SELECTION)
 * - opens the rear camera with the NDK Camera2 API. cameraserver only serves a client this early if
 *   it runs as AID_AUTOMOTIVE_EVS and the camera is an exterior system camera (see the external
 *   camera HAL's ro.vendor.camera.external.automotive_location)
 * - streams it into the layer of rvc_display, above everything, the boot animation included (only
 *   graphics/system may create such a layer this early, so rvc_display owns it; it also mirrors and
 *   scales the image as configured)
 *
 * Once Android has booted (sys.boot_completed) the RearViewCamera app takes over and this service
 * exits, after the car has left reverse if it was in reverse at that moment.
 *
 * The camera, stream, mirroring and scaling are the ones configured in the RearViewCamera settings
 * (persist.rvc.*).
 */

#include <IVhalClient.h>
#include <aidl/com/schuurman/rvc/IRvcDisplay.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/native_window.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <media/NdkImage.h>

#include <algorithm>
#include <chrono>
#include <functional>
#include <cstdio>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <vector>

namespace {

using ::aidl::com::schuurman::rvc::IRvcDisplay;
using ::android::frameworks::automotive::vhal::IVhalClient;
using namespace std::chrono_literals;

// VehicleProperty::GEAR_SELECTION (SYSTEM | GLOBAL | INT32 | 0x0400) and VehicleGear::GEAR_REVERSE.
constexpr int32_t kGearSelection = 0x11400400;
constexpr int32_t kGearReverse = 0x0002;

constexpr auto kPollInterval = 100ms;
constexpr auto kRetryInterval = 500ms;
constexpr const char* kDisplayService = "rvc_display";

struct Size {
    int32_t width;
    int32_t height;
    bool operator==(const Size& o) const { return width == o.width && height == o.height; }
};

// Same rule as the app (Camera2Controller.isAnalogSize): PAL/NTSC capture sizes.
bool isAnalogSize(const Size& s) {
    const bool w = s.width == 720 || s.width == 704 || s.width == 640;
    const bool h = s.height == 576 || s.height == 480 || s.height == 288 || s.height == 240;
    return w && h && !(s.width == 640 && s.height == 480);
}

std::optional<Size> parseSize(const std::string& value) {
    Size size;
    if (std::sscanf(value.c_str(), "%dx%d", &size.width, &size.height) != 2) return std::nullopt;
    return size;
}

class RearCamera {
  public:
    ~RearCamera() {
        stop();
        if (mManager != nullptr) ACameraManager_delete(mManager);
    }

    // Starts streaming the configured camera into the window `getWindow` returns for the chosen
    // stream size. Returns false if it can't (yet).
    bool start(const std::function<ANativeWindow*(const Size&)>& getWindow) {
        if (mSession != nullptr) return true;
        if (mManager == nullptr) mManager = ACameraManager_create();

        const std::optional<std::string> cameraId = findCamera();
        if (!cameraId) {
            LOG(WARNING) << "No external camera";
            return false;
        }
        const Size size = chooseStreamSize(*cameraId);
        LOG(INFO) << "Opening camera " << *cameraId << " stream " << size.width << "x" << size.height;

        ANativeWindow* window = getWindow(size);
        if (window == nullptr) return false;
        ACameraDevice_StateCallbacks deviceCallbacks = {
                .context = this, .onDisconnected = onDisconnected, .onError = onError};
        if (auto s = ACameraManager_openCamera(mManager, cameraId->c_str(), &deviceCallbacks,
                                               &mDevice);
            s != ACAMERA_OK) {
            LOG(ERROR) << "openCamera failed: " << s;
            mDevice = nullptr;
            return false;
        }

        ACaptureSessionOutputContainer_create(&mOutputs);
        ACaptureSessionOutput_create(window, &mOutput);
        ACaptureSessionOutputContainer_add(mOutputs, mOutput);
        ACameraCaptureSession_stateCallbacks sessionCallbacks = {
                .context = this, .onClosed = onSessionEvent, .onReady = onSessionEvent,
                .onActive = onSessionEvent};
        if (auto s = ACameraDevice_createCaptureSession(mDevice, mOutputs, &sessionCallbacks,
                                                        &mSession);
            s != ACAMERA_OK) {
            LOG(ERROR) << "createCaptureSession failed: " << s;
            mSession = nullptr;
            stop();
            return false;
        }

        ACameraOutputTarget_create(window, &mTarget);
        ACameraDevice_createCaptureRequest(mDevice, TEMPLATE_PREVIEW, &mRequest);
        ACaptureRequest_addTarget(mRequest, mTarget);
        if (auto s = ACameraCaptureSession_setRepeatingRequest(mSession, nullptr, 1, &mRequest,
                                                               nullptr);
            s != ACAMERA_OK) {
            LOG(ERROR) << "setRepeatingRequest failed: " << s;
            stop();
            return false;
        }
        LOG(INFO) << "Preview started";
        return true;
    }

    void stop() {
        if (mSession != nullptr) {
            ACameraCaptureSession_stopRepeating(mSession);
            ACameraCaptureSession_close(mSession);
            mSession = nullptr;
        }
        if (mRequest != nullptr) {
            ACaptureRequest_free(mRequest);
            mRequest = nullptr;
        }
        if (mTarget != nullptr) {
            ACameraOutputTarget_free(mTarget);
            mTarget = nullptr;
        }
        if (mOutputs != nullptr) {
            ACaptureSessionOutputContainer_free(mOutputs);
            mOutputs = nullptr;
        }
        if (mOutput != nullptr) {
            ACaptureSessionOutput_free(mOutput);
            mOutput = nullptr;
        }
        if (mDevice != nullptr) {
            ACameraDevice_close(mDevice);
            mDevice = nullptr;
        }
    }

    bool isStreaming() const { return mSession != nullptr; }

  private:
    static void onDisconnected(void*, ACameraDevice*) { LOG(WARNING) << "Camera disconnected"; }
    static void onError(void*, ACameraDevice*, int error) { LOG(ERROR) << "Camera error " << error; }
    static void onSessionEvent(void*, ACameraCaptureSession*) {}

    // The configured camera (persist.rvc.camera_id) if it is connected, else the first external one.
    std::optional<std::string> findCamera() {
        ACameraIdList* ids = nullptr;
        if (ACameraManager_getCameraIdList(mManager, &ids) != ACAMERA_OK || ids == nullptr) {
            return std::nullopt;
        }
        const std::string configured = android::base::GetProperty("persist.rvc.camera_id", "");
        std::optional<std::string> first;
        for (int i = 0; i < ids->numCameras; i++) {
            ACameraMetadata* chars = nullptr;
            if (ACameraManager_getCameraCharacteristics(mManager, ids->cameraIds[i], &chars) !=
                ACAMERA_OK) {
                continue;
            }
            ACameraMetadata_const_entry facing;
            const bool external =
                    ACameraMetadata_getConstEntry(chars, ACAMERA_LENS_FACING, &facing) ==
                            ACAMERA_OK &&
                    facing.count == 1 && facing.data.u8[0] == ACAMERA_LENS_FACING_EXTERNAL;
            ACameraMetadata_free(chars);
            if (!external) continue;
            if (configured == ids->cameraIds[i]) {
                first = configured;
                break;
            }
            if (!first) first = ids->cameraIds[i];
        }
        ACameraManager_deleteCameraIdList(ids);
        return first;
    }

    // Like the app (Camera2Controller.chooseStreamSize): the configured size if offered, else the
    // native analog size, else the largest up to 1920x1080.
    Size chooseStreamSize(const std::string& cameraId) {
        std::vector<Size> sizes;
        ACameraMetadata* chars = nullptr;
        if (ACameraManager_getCameraCharacteristics(mManager, cameraId.c_str(), &chars) ==
            ACAMERA_OK) {
            ACameraMetadata_const_entry entry;
            if (ACameraMetadata_getConstEntry(chars, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
                                              &entry) == ACAMERA_OK) {
                for (uint32_t i = 0; i + 3 < entry.count; i += 4) {
                    if (entry.data.i32[i] == AIMAGE_FORMAT_PRIVATE &&
                        entry.data.i32[i + 3] ==
                                ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT) {
                        sizes.push_back({entry.data.i32[i + 1], entry.data.i32[i + 2]});
                    }
                }
            }
            ACameraMetadata_free(chars);
        }
        if (sizes.empty()) return {640, 480};
        std::sort(sizes.begin(), sizes.end(), [](const Size& a, const Size& b) {
            return (int64_t)a.width * a.height > (int64_t)b.width * b.height;
        });

        if (auto configured = parseSize(android::base::GetProperty("persist.rvc.stream_size", ""))) {
            for (const auto& s : sizes) {
                if (s == *configured) return s;
            }
        }
        for (const auto& s : sizes) {
            if (isAnalogSize(s)) return s;
        }
        for (const auto& s : sizes) {
            if (s.width <= 1920 && s.height <= 1080) return s;
        }
        return sizes.back();
    }

    ACameraManager* mManager = nullptr;
    ACameraDevice* mDevice = nullptr;
    ACaptureSessionOutputContainer* mOutputs = nullptr;
    ACaptureSessionOutput* mOutput = nullptr;
    ACameraCaptureSession* mSession = nullptr;
    ACameraOutputTarget* mTarget = nullptr;
    ACaptureRequest* mRequest = nullptr;
};

// The camera layer, owned by rvc_display.
class Display {
  public:
    bool init() {
        mService = IRvcDisplay::fromBinder(ndk::SpAIBinder(AServiceManager_waitForService(kDisplayService)));
        return mService != nullptr;
    }

    ANativeWindow* surfaceFor(const Size& size) {
        if (!mService->getSurface(size.width, size.height, &mSurface).isOk()) {
            LOG(ERROR) << "No surface from " << kDisplayService;
            return nullptr;
        }
        return mSurface.get();
    }

    void show() {
        mService->show(android::base::GetBoolProperty("persist.rvc.mirror", false),
                       android::base::GetProperty("persist.rvc.scale", "fit"));
    }

    void hide() { mService->hide(); }

  private:
    std::shared_ptr<IRvcDisplay> mService;
    aidl::android::view::Surface mSurface;
};

bool isInReverse(IVhalClient& vhal) {
    auto request = vhal.createHalPropValue(kGearSelection);
    auto result = vhal.getValueSync(*request);
    if (!result.ok() || *result == nullptr) return false;
    const auto values = (*result)->getInt32Values();
    return !values.empty() && values[0] == kGearReverse;
}

}  // namespace

int main() {
    // Also to the kernel log: early in boot logd may not be running yet, and dmesg/the serial
    // console is where early boot is debugged.
    static android::base::LogdLogger logdLogger(android::base::SYSTEM);
    android::base::InitLogging(nullptr, [](android::base::LogId id, android::base::LogSeverity sev,
                                           const char* tag, const char* file, unsigned int line,
                                           const char* message) {
        android::base::KernelLogger(id, sev, tag, file, line, message);
        logdLogger(id, sev, tag, file, line, message);
    });
    ABinderProcess_setThreadPoolMaxThreadCount(2);
    ABinderProcess_startThreadPool();

    std::shared_ptr<IVhalClient> vhal;
    while ((vhal = IVhalClient::tryCreate(/*startThreadPool=*/false)) == nullptr) {
        if (android::base::GetBoolProperty("sys.boot_completed", false)) return 0;
        std::this_thread::sleep_for(kRetryInterval);
    }

    Display display;
    if (!display.init()) return 1;
    RearCamera camera;
    LOG(INFO) << "Watching the gear";

    bool shown = false;
    while (true) {
        const bool reverse = isInReverse(*vhal);
        if (reverse && !camera.isStreaming()) {
            // cameraserver starts once /data is mounted, and a USB camera may still be probing.
            if (camera.start([&](const Size& size) { return display.surfaceFor(size); })) {
                display.show();
                shown = true;
            } else {
                std::this_thread::sleep_for(kRetryInterval);
                continue;
            }
        } else if (!reverse && shown) {
            display.hide();
            camera.stop();
            shown = false;
            LOG(INFO) << "Preview stopped";
        }
        if (!reverse && android::base::GetBoolProperty("sys.boot_completed", false)) {
            LOG(INFO) << "Android has booted; the RearViewCamera app takes over";
            return 0;
        }
        std::this_thread::sleep_for(kPollInterval);
    }
}

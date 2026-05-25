#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <wmcodecdsp.h>
#include <codecapi.h>
#include <wrl/client.h>
#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

using Microsoft::WRL::ComPtr;
namespace winrt_capture = winrt::Windows::Graphics::Capture;
namespace winrt_dx = winrt::Windows::Graphics::DirectX;
namespace winrt_d3d11 = winrt::Windows::Graphics::DirectX::Direct3D11;

static int RunMain(int argc, char** argv);

static void Check(HRESULT hr, const char* what) {
    if (FAILED(hr)) {
        std::cerr << "[native] " << what << " failed hr=0x" << std::hex << hr << std::dec << "\n";
        std::exit(2);
    }
}

static int ClampInt(int v, int lo, int hi) {
    return std::max(lo, std::min(v, hi));
}

struct Options {
    uint64_t hwnd = 0;
    int left = 0;
    int top = 0;
    int width = 1280;
    int height = 720;
    int out_width = 1280;
    int out_height = 720;
    int fps = 60;
    int quality = 80;
};

static int ReadIntArg(int argc, char** argv, const char* name, int fallback) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (std::strcmp(argv[i], name) == 0) return std::atoi(argv[i + 1]);
    }
    return fallback;
}

static Options ParseOptions(int argc, char** argv) {
    Options o;
    for (int i = 1; i + 1 < argc; ++i) {
        if (std::strcmp(argv[i], "--hwnd") == 0) {
            o.hwnd = std::strtoull(argv[i + 1], nullptr, 0);
        }
    }
    o.left = ReadIntArg(argc, argv, "--left", o.left);
    o.top = ReadIntArg(argc, argv, "--top", o.top);
    o.width = ReadIntArg(argc, argv, "--width", o.width);
    o.height = ReadIntArg(argc, argv, "--height", o.height);
    o.out_width = ReadIntArg(argc, argv, "--out-width", o.out_width);
    o.out_height = ReadIntArg(argc, argv, "--out-height", o.out_height);
    o.fps = ClampInt(ReadIntArg(argc, argv, "--fps", o.fps), 5, 160);
    o.quality = ClampInt(ReadIntArg(argc, argv, "--quality", o.quality), 35, 100);
    o.width -= o.width % 2;
    o.height -= o.height % 2;
    o.out_width -= o.out_width % 2;
    o.out_height -= o.out_height % 2;
    return o;
}

static UINT32 EstimateBitrate(const Options& o) {
    double scale = (double)o.out_width * (double)o.out_height * (double)o.fps / (1920.0 * 1080.0 * 60.0);
    double q = (double)o.quality / 100.0;
    double mbps = scale * (4.0 + q * q * 28.0);
    return (UINT32)ClampInt((int)(mbps * 1000000.0), 1800000, 85000000);
}

struct CapturedFrame {
    const uint8_t* data = nullptr;
    UINT pitch = 0;
    int width = 0;
    int height = 0;
};

struct GpuFrame {
    ComPtr<ID3D11Texture2D> texture;
    int width = 0;
    int height = 0;
};

class ScreenCapture {
public:
    explicit ScreenCapture(const Options& options) : opt(options) {
        InitD3D();
        InitDuplication();
    }

    ~ScreenCapture() {
        Unmap();
    }

    ID3D11Device* Device() const { return device.Get(); }
    ID3D11DeviceContext* Context() const { return context.Get(); }

    bool CaptureGpu(GpuFrame& frame) {
        Unmap();
        auto capture_frame = frame_pool.TryGetNextFrame();
        if (!capture_frame) return false;
        auto content_size = capture_frame.ContentSize();
        if (content_size.Width > 0 && content_size.Height > 0 &&
            (content_size.Width != pool_width || content_size.Height != pool_height)) {
            frame_pool.Recreate(
                winrt_device,
                winrt_dx::DirectXPixelFormat::B8G8R8A8UIntNormalized,
                2,
                content_size
            );
            pool_width = content_size.Width;
            pool_height = content_size.Height;
            std::cerr << "[native] WGC frame pool resized " << pool_width << "x" << pool_height << "\n";
        }
        auto surface = capture_frame.Surface();
        auto access = surface.as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
        ComPtr<ID3D11Texture2D> texture;
        Check(access->GetInterface(__uuidof(ID3D11Texture2D), reinterpret_cast<void**>(texture.GetAddressOf())), "Get frame texture");
        D3D11_TEXTURE2D_DESC texture_desc{};
        texture->GetDesc(&texture_desc);
        source_width = (int)texture_desc.Width;
        source_height = (int)texture_desc.Height;
        frame.texture = texture;
        frame.width = source_width;
        frame.height = source_height;
        return true;
    }

    bool CaptureCpu(CapturedFrame& frame) {
        GpuFrame gpu;
        if (!CaptureGpu(gpu)) return false;
        return CopyGpuToCpu(gpu, frame);
    }

    bool CopyGpuToCpu(const GpuFrame& gpu, CapturedFrame& frame) {
        if (!gpu.texture) return false;
        Unmap();
        D3D11_TEXTURE2D_DESC texture_desc{};
        gpu.texture->GetDesc(&texture_desc);
        EnsureStaging(texture_desc.Width, texture_desc.Height);
        context->CopyResource(staging.Get(), gpu.texture.Get());

        Check(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped), "Map staging");
        mapped_active = true;
        frame.data = static_cast<const uint8_t*>(mapped.pData);
        frame.pitch = mapped.RowPitch;
        frame.width = source_width;
        frame.height = source_height;
        return true;
    }

private:
    static BOOL CALLBACK MonitorEnumProc(HMONITOR monitor, HDC, LPRECT, LPARAM param) {
        auto* self = reinterpret_cast<ScreenCapture*>(param);
        MONITORINFOEXW info{};
        info.cbSize = sizeof(info);
        if (!GetMonitorInfoW(monitor, &info)) return TRUE;
        RECT r = info.rcMonitor;
        int w = r.right - r.left;
        int h = r.bottom - r.top;
        LONG score = std::labs(r.left - self->opt.left) + std::labs(r.top - self->opt.top) +
            std::labs(w - self->opt.width) + std::labs(h - self->opt.height);
        std::wcerr << L"[native] monitor candidate " << info.szDevice
                   << L" rect=(" << r.left << L"," << r.top << L"," << r.right << L"," << r.bottom
                   << L") score=" << score << L"\n";
        if (!self->selected_monitor || score < self->selected_monitor_score) {
            self->selected_monitor = monitor;
            self->selected_monitor_score = score;
            self->source_width = w;
            self->source_height = h;
        }
        return score == 0 ? FALSE : TRUE;
    }

    void Unmap() {
        if (mapped_active) {
            context->Unmap(staging.Get(), 0);
            mapped_active = false;
            mapped = {};
        }
    }

    void EnsureStaging(UINT width, UINT height) {
        if (width == 0 || height == 0) return;
        if (staging && source_width == (int)width && source_height == (int)height) return;

        staging.Reset();
        source_width = (int)width;
        source_height = (int)height;

        D3D11_TEXTURE2D_DESC desc{};
        desc.Width = width;
        desc.Height = height;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_STAGING;
        desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        Check(device->CreateTexture2D(&desc, nullptr, &staging), "Create staging texture");
        std::cerr << "[native] staging resized " << source_width << "x" << source_height << "\n";
    }

    winrt_d3d11::IDirect3DDevice CreateWinRtDevice() {
        ComPtr<IDXGIDevice> dxgi_device;
        Check(device.As(&dxgi_device), "IDXGIDevice");
        winrt::com_ptr<IInspectable> inspectable;
        Check(CreateDirect3D11DeviceFromDXGIDevice(dxgi_device.Get(), inspectable.put()), "CreateDirect3D11DeviceFromDXGIDevice");
        return inspectable.as<winrt_d3d11::IDirect3DDevice>();
    }

    void InitD3D() {
        UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
        D3D_FEATURE_LEVEL levels[] = {
            D3D_FEATURE_LEVEL_11_1,
            D3D_FEATURE_LEVEL_11_0,
        };
        D3D_FEATURE_LEVEL level{};
        Check(D3D11CreateDevice(
            nullptr,
            D3D_DRIVER_TYPE_HARDWARE,
            nullptr,
            flags,
            levels,
            ARRAYSIZE(levels),
            D3D11_SDK_VERSION,
            &device,
            &level,
            &context
        ), "D3D11CreateDevice");
    }

    void InitDuplication() {
        Unmap();
        staging.Reset();
        frame_pool = nullptr;
        session = nullptr;
        item = nullptr;
        selected_monitor = nullptr;
        selected_monitor_score = LONG_MAX;

        auto interop = winrt::get_activation_factory<winrt_capture::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        winrt::com_ptr<ABI::Windows::Graphics::Capture::IGraphicsCaptureItem> abi_item;
        if (opt.hwnd != 0) {
            HWND hwnd = reinterpret_cast<HWND>((uintptr_t)opt.hwnd);
            if (!IsWindow(hwnd)) {
                std::cerr << "[native] invalid hwnd=" << opt.hwnd << "\n";
                std::exit(2);
            }
            Check(interop->CreateForWindow(
                hwnd,
                winrt::guid_of<winrt_capture::GraphicsCaptureItem>(),
                abi_item.put_void()
            ), "CreateForWindow");
            std::cerr << "[native] selected WGC window hwnd=" << opt.hwnd << "\n";
        } else {
            EnumDisplayMonitors(nullptr, nullptr, MonitorEnumProc, reinterpret_cast<LPARAM>(this));
            if (!selected_monitor) {
                std::cerr << "[native] no monitor found\n";
                std::exit(2);
            }
            std::cerr << "[native] selected WGC monitor src=" << source_width << "x" << source_height << "\n";
            Check(interop->CreateForMonitor(
                selected_monitor,
                winrt::guid_of<winrt_capture::GraphicsCaptureItem>(),
                abi_item.put_void()
            ), "CreateForMonitor");
        }
        item = abi_item.as<winrt_capture::GraphicsCaptureItem>();
        winrt_device = CreateWinRtDevice();
        auto size = item.Size();
        source_width = size.Width;
        source_height = size.Height;
        pool_width = size.Width;
        pool_height = size.Height;
        std::cerr << "[native] WGC item size=" << source_width << "x" << source_height << "\n";

        EnsureStaging(size.Width, size.Height);

        frame_pool = winrt_capture::Direct3D11CaptureFramePool::CreateFreeThreaded(
            winrt_device,
            winrt_dx::DirectXPixelFormat::B8G8R8A8UIntNormalized,
            2,
            size
        );
        session = frame_pool.CreateCaptureSession(item);
        try {
            session.IsCursorCaptureEnabled(true);
        } catch (...) {
        }
        try {
            session.IsBorderRequired(false);
        } catch (...) {
        }
        session.StartCapture();
    }

    Options opt;
    HMONITOR selected_monitor = nullptr;
    LONG selected_monitor_score = LONG_MAX;
    int source_width = 0;
    int source_height = 0;
    int pool_width = 0;
    int pool_height = 0;
    bool mapped_active = false;
    D3D11_MAPPED_SUBRESOURCE mapped{};
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11Texture2D> staging;
    winrt_d3d11::IDirect3DDevice winrt_device{nullptr};
    winrt_capture::GraphicsCaptureItem item{nullptr};
    winrt_capture::Direct3D11CaptureFramePool frame_pool{nullptr};
    winrt_capture::GraphicsCaptureSession session{nullptr};
};

static inline uint8_t ClipByte(int v) {
    return (uint8_t)std::max(0, std::min(255, v));
}

static void BGRAtoNV12(const CapturedFrame& src, int out_width, int out_height, std::vector<uint8_t>& nv12) {
    const int y_size = out_width * out_height;
    nv12.resize(y_size + y_size / 2);
    uint8_t* y_plane = nv12.data();
    uint8_t* uv_plane = nv12.data() + y_size;

    for (int j = 0; j < out_height; ++j) {
        int sy = (int)((int64_t)j * src.height / out_height);
        for (int i = 0; i < out_width; ++i) {
            int sx = (int)((int64_t)i * src.width / out_width);
            const uint8_t* p = src.data + sy * src.pitch + sx * 4;
            int b = p[0], g = p[1], r = p[2];
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            y_plane[j * out_width + i] = ClipByte(y);
        }
    }

    for (int j = 0; j < out_height; j += 2) {
        for (int i = 0; i < out_width; i += 2) {
            int sum_u = 0;
            int sum_v = 0;
            for (int y = 0; y < 2; ++y) {
                for (int x = 0; x < 2; ++x) {
                    int sy = (int)((int64_t)(j + y) * src.height / out_height);
                    int sx = (int)((int64_t)(i + x) * src.width / out_width);
                    const uint8_t* p = src.data + sy * src.pitch + sx * 4;
                    int b = p[0], g = p[1], r = p[2];
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    sum_u += u;
                    sum_v += v;
                }
            }
            int uv_index = (j / 2) * out_width + i;
            uv_plane[uv_index] = ClipByte(sum_u / 4);
            uv_plane[uv_index + 1] = ClipByte(sum_v / 4);
        }
    }
}

class H264Encoder {
public:
    explicit H264Encoder(const Options& options, ID3D11Device* d3d_device = nullptr, ID3D11DeviceContext* d3d_context = nullptr)
        : opt(options), device(d3d_device), context(d3d_context) {
        Check(CoCreateInstance(CLSID_CMSH264EncoderMFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&mft)), "CoCreateInstance(CMSH264EncoderMFT)");

        InitD3DInput();

        if (SUCCEEDED(mft.As(&codec_api))) {
            VARIANT value{};
            value.vt = VT_BOOL;
            value.boolVal = VARIANT_TRUE;
            codec_api->SetValue(&CODECAPI_AVLowLatencyMode, &value);
            codec_api->SetValue(&CODECAPI_AVEncCommonLowLatency, &value);
            codec_api->SetValue(&CODECAPI_AVEncCommonRealTime, &value);

            VariantClear(&value);
            value.vt = VT_UI4;
            value.ulVal = EstimateBitrate(opt);
            codec_api->SetValue(&CODECAPI_AVEncCommonMeanBitRate, &value);

            VariantClear(&value);
            value.vt = VT_UI4;
            value.ulVal = (ULONG)std::max(1, opt.fps);
            codec_api->SetValue(&CODECAPI_AVEncMPVGOPSize, &value);
            codec_api->SetValue(&CODECAPI_AVEncMPVGOPSizeMin, &value);
            codec_api->SetValue(&CODECAPI_AVEncMPVGOPSizeMax, &value);
            codec_api->SetValue(&CODECAPI_AVEncMPVGOPSInSeq, &value);

            VariantClear(&value);
            value.vt = VT_UI4;
            value.ulVal = 0;
            codec_api->SetValue(&CODECAPI_AVEncMPVDefaultBPictureCount, &value);

            VariantClear(&value);
            value.vt = VT_BOOL;
            value.boolVal = VARIANT_FALSE;
            codec_api->SetValue(&CODECAPI_AVEncMPVGOPOpen, &value);
            codec_api->SetValue(&CODECAPI_AVEncCommonAllowFrameDrops, &value);

            VariantClear(&value);
            value.vt = VT_BOOL;
            value.boolVal = VARIANT_TRUE;
            codec_api->SetValue(&CODECAPI_AVEncH264CABACEnable, &value);
        }

        ConfigureTypes();
        Check(mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0), "BEGIN_STREAMING");
        Check(mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0), "START_OF_STREAM");
        WriteCurrentSequenceHeader();
    }

    bool GpuEnabled() const {
        return gpu_input_ready;
    }

    bool EncodeTexture(const GpuFrame& frame, int64_t frame_index) {
        if (!gpu_input_ready || !frame.texture) return false;
        if (!EnsureVideoProcessor(frame.width, frame.height)) {
            std::cerr << "[native] disabling D3D11 surface input after video processor setup failure\n";
            gpu_input_ready = false;
            return false;
        }

        ComPtr<ID3D11VideoProcessorInputView> input_view;
        D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC input_desc{};
        input_desc.FourCC = 0;
        input_desc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
        input_desc.Texture2D.MipSlice = 0;
        input_desc.Texture2D.ArraySlice = 0;
        HRESULT hr = video_device->CreateVideoProcessorInputView(
            frame.texture.Get(),
            video_enum.Get(),
            &input_desc,
            &input_view
        );
        if (FAILED(hr)) {
            std::cerr << "[native] CreateVideoProcessorInputView failed hr=0x" << std::hex << hr << std::dec << "\n";
            gpu_input_ready = false;
            return false;
        }

        RECT src_rect{0, 0, frame.width, frame.height};
        RECT dst_rect{0, 0, opt.out_width, opt.out_height};
        video_context->VideoProcessorSetStreamSourceRect(video_processor.Get(), 0, TRUE, &src_rect);
        video_context->VideoProcessorSetStreamDestRect(video_processor.Get(), 0, TRUE, &dst_rect);
        video_context->VideoProcessorSetOutputTargetRect(video_processor.Get(), TRUE, &dst_rect);

        D3D11_VIDEO_PROCESSOR_STREAM stream{};
        stream.Enable = TRUE;
        stream.pInputSurface = input_view.Get();
        hr = video_context->VideoProcessorBlt(video_processor.Get(), nv12_output_view.Get(), (UINT)frame_index, 1, &stream);
        if (FAILED(hr)) {
            std::cerr << "[native] VideoProcessorBlt failed hr=0x" << std::hex << hr << std::dec << "\n";
            gpu_input_ready = false;
            return false;
        }

        if (codec_api && frame_index % std::max(1, opt.fps) == 0) {
            VARIANT force_key{};
            force_key.vt = VT_UI4;
            force_key.ulVal = 1;
            codec_api->SetValue(&CODECAPI_AVEncVideoForceKeyFrame, &force_key);
        }

        ComPtr<IMFMediaBuffer> buffer;
        hr = MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), nv12_texture.Get(), 0, FALSE, &buffer);
        if (FAILED(hr)) {
            std::cerr << "[native] MFCreateDXGISurfaceBuffer failed hr=0x" << std::hex << hr << std::dec << "\n";
            gpu_input_ready = false;
            return false;
        }
        buffer->SetCurrentLength((DWORD)(opt.out_width * opt.out_height * 3 / 2));

        ComPtr<IMFSample> sample;
        Check(MFCreateSample(&sample), "MFCreateSample(gpu)");
        Check(sample->AddBuffer(buffer.Get()), "sample AddBuffer(gpu)");
        const LONGLONG duration = 10000000LL / opt.fps;
        Check(sample->SetSampleTime(frame_index * duration), "SetSampleTime(gpu)");
        Check(sample->SetSampleDuration(duration), "SetSampleDuration(gpu)");
        if (!SubmitSample(sample.Get())) {
            std::cerr << "[native] disabling D3D11 surface input, falling back to CPU path\n";
            gpu_input_ready = false;
            return false;
        }
        return true;
    }

    void EncodeFrame(const std::vector<uint8_t>& nv12, int64_t frame_index) {
        if (codec_api && frame_index % std::max(1, opt.fps) == 0) {
            VARIANT force_key{};
            force_key.vt = VT_UI4;
            force_key.ulVal = 1;
            codec_api->SetValue(&CODECAPI_AVEncVideoForceKeyFrame, &force_key);
        }

        ComPtr<IMFMediaBuffer> buffer;
        Check(MFCreateMemoryBuffer((DWORD)nv12.size(), &buffer), "MFCreateMemoryBuffer");
        BYTE* dst = nullptr;
        DWORD max_len = 0;
        DWORD cur_len = 0;
        Check(buffer->Lock(&dst, &max_len, &cur_len), "buffer Lock");
        std::memcpy(dst, nv12.data(), nv12.size());
        Check(buffer->Unlock(), "buffer Unlock");
        Check(buffer->SetCurrentLength((DWORD)nv12.size()), "buffer SetCurrentLength");

        ComPtr<IMFSample> sample;
        Check(MFCreateSample(&sample), "MFCreateSample");
        Check(sample->AddBuffer(buffer.Get()), "sample AddBuffer");
        const LONGLONG duration = 10000000LL / opt.fps;
        Check(sample->SetSampleTime(frame_index * duration), "SetSampleTime");
        Check(sample->SetSampleDuration(duration), "SetSampleDuration");

        Check(SubmitSample(sample.Get()) ? S_OK : E_FAIL, "ProcessInput");
    }

    void Drain() {
        MFT_OUTPUT_STREAM_INFO stream_info{};
        Check(mft->GetOutputStreamInfo(0, &stream_info), "GetOutputStreamInfo");
        const bool provides_samples = (stream_info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;

        while (true) {
            ComPtr<IMFSample> out_sample;
            ComPtr<IMFMediaBuffer> out_buffer;
            if (!provides_samples) {
                Check(MFCreateSample(&out_sample), "MFCreateSample(out)");
                Check(MFCreateMemoryBuffer(std::max<DWORD>(stream_info.cbSize, 1024 * 1024), &out_buffer), "MFCreateMemoryBuffer(out)");
                Check(out_sample->AddBuffer(out_buffer.Get()), "out AddBuffer");
            }

            MFT_OUTPUT_DATA_BUFFER output{};
            output.dwStreamID = 0;
            output.pSample = out_sample.Get();
            DWORD status = 0;
            HRESULT hr = mft->ProcessOutput(0, 1, &output, &status);
            if (output.pEvents) output.pEvents->Release();
            if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) break;
            if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
                ConfigureOutputType();
                continue;
            }
            Check(hr, "ProcessOutput");
            if (output.pSample) {
                WriteSample(output.pSample);
                if (provides_samples) output.pSample->Release();
            } else if (out_sample) {
                WriteSample(out_sample.Get());
            }
        }
    }

private:
    void InitD3DInput() {
        if (!device || !context) return;
        if (FAILED(device.As(&video_device)) || FAILED(context.As(&video_context))) {
            std::cerr << "[native] D3D11 video processor unavailable, using CPU path\n";
            return;
        }
        UINT reset_token = 0;
        HRESULT hr = MFCreateDXGIDeviceManager(&reset_token, &dxgi_manager);
        if (FAILED(hr)) {
            std::cerr << "[native] MFCreateDXGIDeviceManager failed hr=0x" << std::hex << hr << std::dec << "\n";
            return;
        }
        hr = dxgi_manager->ResetDevice(device.Get(), reset_token);
        if (FAILED(hr)) {
            std::cerr << "[native] ResetDevice failed hr=0x" << std::hex << hr << std::dec << "\n";
            dxgi_manager.Reset();
            return;
        }
        hr = mft->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, reinterpret_cast<ULONG_PTR>(dxgi_manager.Get()));
        if (FAILED(hr)) {
            std::cerr << "[native] encoder rejected D3D manager hr=0x" << std::hex << hr << std::dec << "\n";
            dxgi_manager.Reset();
            return;
        }
        gpu_input_ready = true;
        std::cerr << "[native] D3D11 surface input enabled\n";
    }

    bool EnsureVideoProcessor(int src_width, int src_height) {
        if (!gpu_input_ready || src_width <= 0 || src_height <= 0) return false;
        if (video_processor && nv12_texture && vp_src_width == src_width && vp_src_height == src_height) return true;

        video_processor.Reset();
        video_enum.Reset();
        nv12_output_view.Reset();
        nv12_texture.Reset();

        D3D11_VIDEO_PROCESSOR_CONTENT_DESC content_desc{};
        content_desc.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
        content_desc.InputWidth = src_width;
        content_desc.InputHeight = src_height;
        content_desc.OutputWidth = opt.out_width;
        content_desc.OutputHeight = opt.out_height;
        content_desc.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
        HRESULT hr = video_device->CreateVideoProcessorEnumerator(&content_desc, &video_enum);
        if (FAILED(hr)) {
            std::cerr << "[native] CreateVideoProcessorEnumerator failed hr=0x" << std::hex << hr << std::dec << "\n";
            return false;
        }
        hr = video_device->CreateVideoProcessor(video_enum.Get(), 0, &video_processor);
        if (FAILED(hr)) {
            std::cerr << "[native] CreateVideoProcessor failed hr=0x" << std::hex << hr << std::dec << "\n";
            return false;
        }

        D3D11_TEXTURE2D_DESC tex_desc{};
        tex_desc.Width = opt.out_width;
        tex_desc.Height = opt.out_height;
        tex_desc.MipLevels = 1;
        tex_desc.ArraySize = 1;
        tex_desc.Format = DXGI_FORMAT_NV12;
        tex_desc.SampleDesc.Count = 1;
        tex_desc.Usage = D3D11_USAGE_DEFAULT;
        tex_desc.BindFlags = D3D11_BIND_RENDER_TARGET;
        hr = device->CreateTexture2D(&tex_desc, nullptr, &nv12_texture);
        if (FAILED(hr)) {
            std::cerr << "[native] Create NV12 texture failed hr=0x" << std::hex << hr << std::dec << "\n";
            return false;
        }

        D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC output_desc{};
        output_desc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
        output_desc.Texture2D.MipSlice = 0;
        hr = video_device->CreateVideoProcessorOutputView(nv12_texture.Get(), video_enum.Get(), &output_desc, &nv12_output_view);
        if (FAILED(hr)) {
            std::cerr << "[native] CreateVideoProcessorOutputView failed hr=0x" << std::hex << hr << std::dec << "\n";
            return false;
        }

        vp_src_width = src_width;
        vp_src_height = src_height;
        std::cerr << "[native] GPU processor " << vp_src_width << "x" << vp_src_height
                  << " -> " << opt.out_width << "x" << opt.out_height << "\n";
        return true;
    }

    bool SubmitSample(IMFSample* sample) {
        HRESULT hr = mft->ProcessInput(0, sample, 0);
        if (hr == MF_E_NOTACCEPTING) {
            Drain();
            hr = mft->ProcessInput(0, sample, 0);
        }
        if (FAILED(hr)) {
            std::cerr << "[native] ProcessInput failed hr=0x" << std::hex << hr << std::dec << "\n";
            return false;
        }
        Drain();
        return true;
    }

    void ConfigureTypes() {
        ConfigureOutputType();

        ComPtr<IMFMediaType> input;
        Check(MFCreateMediaType(&input), "MFCreateMediaType(input)");
        Check(input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video), "input major");
        Check(input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12), "input subtype");
        Check(MFSetAttributeSize(input.Get(), MF_MT_FRAME_SIZE, opt.out_width, opt.out_height), "input frame size");
        Check(MFSetAttributeRatio(input.Get(), MF_MT_FRAME_RATE, opt.fps, 1), "input frame rate");
        Check(MFSetAttributeRatio(input.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1), "input aspect");
        Check(input->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive), "input progressive");
        Check(mft->SetInputType(0, input.Get(), 0), "SetInputType");
    }

    void ConfigureOutputType() {
        ComPtr<IMFMediaType> output;
        Check(MFCreateMediaType(&output), "MFCreateMediaType(output)");
        Check(output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video), "output major");
        Check(output->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264_ES), "output subtype");
        Check(MFSetAttributeSize(output.Get(), MF_MT_FRAME_SIZE, opt.out_width, opt.out_height), "output frame size");
        Check(MFSetAttributeRatio(output.Get(), MF_MT_FRAME_RATE, opt.fps, 1), "output frame rate");
        Check(MFSetAttributeRatio(output.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1), "output aspect");
        Check(output->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive), "output progressive");
        Check(output->SetUINT32(MF_MT_AVG_BITRATE, EstimateBitrate(opt)), "output bitrate");
        HRESULT hr = mft->SetOutputType(0, output.Get(), 0);
        if (SUCCEEDED(hr)) {
            std::cerr << "[native] output subtype=H264_ES\n";
            return;
        }
        Check(output->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264), "fallback output subtype");
        Check(mft->SetOutputType(0, output.Get(), 0), "SetOutputType");
        std::cerr << "[native] output subtype=H264\n";
    }

    void WriteCurrentSequenceHeader() {
        ComPtr<IMFMediaType> current;
        if (FAILED(mft->GetOutputCurrentType(0, &current)) || !current) return;

        UINT32 blob_size = 0;
        if (FAILED(current->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &blob_size)) || blob_size == 0) return;

        std::vector<uint8_t> header(blob_size);
        UINT32 actual_size = 0;
        if (SUCCEEDED(current->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, header.data(), blob_size, &actual_size)) && actual_size > 0) {
            WriteH264Payload(header.data(), actual_size);
            std::cerr << "[native] wrote sequence header bytes=" << actual_size << "\n";
        }
    }

    static bool HasAnnexBStart(const uint8_t* data, DWORD len) {
        if (len >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) return true;
        if (len >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1) return true;
        return false;
    }

    static bool LooksLikeLengthPrefixedNal(const uint8_t* data, DWORD len) {
        DWORD pos = 0;
        int nal_count = 0;
        while (pos + 4 <= len) {
            DWORD nal_len = ((DWORD)data[pos] << 24) | ((DWORD)data[pos + 1] << 16) |
                ((DWORD)data[pos + 2] << 8) | (DWORD)data[pos + 3];
            pos += 4;
            if (nal_len == 0 || nal_len > len - pos) return false;
            pos += nal_len;
            ++nal_count;
        }
        return pos == len && nal_count > 0;
    }

    static bool LooksLikeAvccHeader(const uint8_t* data, DWORD len) {
        return len >= 7 && data[0] == 1 && (data[4] & 0xfc) == 0xfc && (data[5] & 0xe0) == 0xe0;
    }

    static void WriteAll(const uint8_t* data, DWORD len) {
        DWORD written = 0;
        HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
        while (written < len) {
            DWORD chunk = 0;
            DWORD to_write = std::min<DWORD>(len - written, 65536);
            if (!WriteFile(out, data + written, to_write, &chunk, nullptr) || chunk == 0) {
                std::exit(0);
            }
            written += chunk;
        }
    }

    void LogNal(const uint8_t* data, DWORD len) {
        if (logged_nals >= 40 || len == 0) return;
        int nal_type = data[0] & 0x1f;
        std::cerr << "[native] nal type=" << nal_type << " bytes=" << len << "\n";
        ++logged_nals;
    }

    void WriteH264Payload(const uint8_t* data, DWORD len) {
        if (len == 0) return;
        static const uint8_t start_code[] = {0, 0, 0, 1};
        if (HasAnnexBStart(data, len)) {
            DWORD pos = 0;
            while (pos + 4 < len) {
                DWORD start = pos;
                if (data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 0 && data[pos + 3] == 1) {
                    pos += 4;
                } else if (data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 1) {
                    pos += 3;
                } else {
                    ++pos;
                    continue;
                }
                DWORD next = pos;
                while (next + 4 < len &&
                       !(data[next] == 0 && data[next + 1] == 0 && data[next + 2] == 1) &&
                       !(data[next] == 0 && data[next + 1] == 0 && data[next + 2] == 0 && data[next + 3] == 1)) {
                    ++next;
                }
                LogNal(data + pos, next - pos);
                pos = next > start ? next : start + 1;
            }
            WriteAll(data, len);
            return;
        }

        if (LooksLikeAvccHeader(data, len)) {
            DWORD pos = 6;
            const int sps_count = data[5] & 0x1f;
            for (int i = 0; i < sps_count; ++i) {
                if (pos + 2 > len) return;
                DWORD nal_len = ((DWORD)data[pos] << 8) | (DWORD)data[pos + 1];
                pos += 2;
                if (nal_len == 0 || nal_len > len - pos) return;
                LogNal(data + pos, nal_len);
                WriteAll(start_code, 4);
                WriteAll(data + pos, nal_len);
                pos += nal_len;
            }
            if (pos >= len) return;
            const int pps_count = data[pos++];
            for (int i = 0; i < pps_count; ++i) {
                if (pos + 2 > len) return;
                DWORD nal_len = ((DWORD)data[pos] << 8) | (DWORD)data[pos + 1];
                pos += 2;
                if (nal_len == 0 || nal_len > len - pos) return;
                LogNal(data + pos, nal_len);
                WriteAll(start_code, 4);
                WriteAll(data + pos, nal_len);
                pos += nal_len;
            }
            return;
        }

        if (LooksLikeLengthPrefixedNal(data, len)) {
            DWORD pos = 0;
            while (pos + 4 <= len) {
                DWORD nal_len = ((DWORD)data[pos] << 24) | ((DWORD)data[pos + 1] << 16) |
                    ((DWORD)data[pos + 2] << 8) | (DWORD)data[pos + 3];
                pos += 4;
                if (nal_len == 0 || nal_len > len - pos) break;
                LogNal(data + pos, nal_len);
                WriteAll(start_code, 4);
                WriteAll(data + pos, nal_len);
                pos += nal_len;
            }
            return;
        }

        LogNal(data, len);
        WriteAll(start_code, 4);
        WriteAll(data, len);
    }

    void WriteSample(IMFSample* sample) {
        ComPtr<IMFMediaBuffer> buffer;
        if (FAILED(sample->ConvertToContiguousBuffer(&buffer))) return;
        BYTE* data = nullptr;
        DWORD max_len = 0;
        DWORD len = 0;
        if (FAILED(buffer->Lock(&data, &max_len, &len))) return;
        if (len > 0) {
            static const uint8_t aud[] = {0, 0, 0, 1, 0x09, 0xf0};
            WriteAll(aud, 6);
            WriteH264Payload(data, len);
        }
        buffer->Unlock();
    }

    Options opt;
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11VideoDevice> video_device;
    ComPtr<ID3D11VideoContext> video_context;
    ComPtr<IMFDXGIDeviceManager> dxgi_manager;
    ComPtr<ID3D11VideoProcessorEnumerator> video_enum;
    ComPtr<ID3D11VideoProcessor> video_processor;
    ComPtr<ID3D11Texture2D> nv12_texture;
    ComPtr<ID3D11VideoProcessorOutputView> nv12_output_view;
    ComPtr<IMFTransform> mft;
    ComPtr<ICodecAPI> codec_api;
    bool gpu_input_ready = false;
    int vp_src_width = 0;
    int vp_src_height = 0;
    int logged_nals = 0;
};

int main(int argc, char** argv) {
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    __try {
        return RunMain(argc, argv);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        std::cerr << "[native] unhandled SEH exception code=0x"
                  << std::hex << GetExceptionCode() << std::dec << "\n";
        return 3;
    }
}

static int RunMain(int argc, char** argv) {
    winrt::init_apartment(winrt::apartment_type::multi_threaded);
    Options opt = ParseOptions(argc, argv);
    std::cerr << "[native] start left=" << opt.left << " top=" << opt.top
              << " src=" << opt.width << "x" << opt.height
              << " out=" << opt.out_width << "x" << opt.out_height
              << " fps=" << opt.fps << " quality=" << opt.quality << "\n";

    Check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "CoInitializeEx");
    Check(MFStartup(MF_VERSION), "MFStartup");

    ScreenCapture capture(opt);
    H264Encoder encoder(opt, capture.Device(), capture.Context());
    std::vector<uint8_t> nv12;

    const auto frame_duration = std::chrono::microseconds(1000000 / opt.fps);
    auto next_frame = std::chrono::steady_clock::now();
    int64_t frame_index = 0;
    int64_t slow_frames = 0;
    auto last_log = std::chrono::steady_clock::now();

    while (true) {
        GpuFrame gpu_frame;
        if (!capture.CaptureGpu(gpu_frame)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }
        if (!encoder.EncodeTexture(gpu_frame, frame_index)) {
            CapturedFrame cpu_frame;
            if (!capture.CopyGpuToCpu(gpu_frame, cpu_frame)) {
                continue;
            }
            BGRAtoNV12(cpu_frame, opt.out_width, opt.out_height, nv12);
            encoder.EncodeFrame(nv12, frame_index);
        }
        ++frame_index;

        auto now = std::chrono::steady_clock::now();
        if (now > next_frame + frame_duration) {
            ++slow_frames;
            next_frame = now;
        } else {
            next_frame += frame_duration;
            std::this_thread::sleep_until(next_frame);
        }

        if (now - last_log > std::chrono::seconds(3)) {
            std::cerr << "[native] frames=" << frame_index << " slow=" << slow_frames << "\n";
            last_log = now;
        }
    }

    MFShutdown();
    CoUninitialize();
    return 0;
}

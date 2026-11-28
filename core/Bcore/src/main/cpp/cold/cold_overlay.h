#pragma once

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include "cold_common.h"
#include "imgui/imgui.h"
#include "imgui/backends/imgui_impl_opengl3.h"

namespace cold {

class OverlayEngine {
public:
    static OverlayEngine& get();

    bool init(int screenWidth, int screenHeight);
    void updateDimensions(int width, int height);
    void render();
    void shutdown();
    bool isInitialized() const { return m_initialized; }

private:
    OverlayEngine() = default;
    ~OverlayEngine() = default;

    bool m_initialized = false;
    int m_width = 1920;
    int m_height = 1080;
    float m_fps = 0.0f;
    uint32_t m_frameCount = 0;

    void setupTheme();
    void renderDiagnosticWindow();
    size_t getMemoryRss();
};

} // namespace cold

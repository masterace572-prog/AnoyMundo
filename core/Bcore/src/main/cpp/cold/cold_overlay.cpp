#include "cold_overlay.h"
#include <fstream>
#include <sstream>

namespace cold {

OverlayEngine& OverlayEngine::get() {
    static OverlayEngine instance;
    return instance;
}

bool OverlayEngine::init(int screenWidth, int screenHeight) {
    if (m_initialized) return true;

    LOGI("Initializing Dear ImGui Overlay Engine (%dx%d)...", screenWidth, screenHeight);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(screenWidth), static_cast<float>(screenHeight));
    io.IniFilename = nullptr; // Stateless in-memory configuration

    setupTheme();

    if (!ImGui_ImplOpenGL3_Init("#version 300 es")) {
        LOGE("Failed to initialize ImGui OpenGL3 backend");
        return false;
    }

    m_width = screenWidth;
    m_height = screenHeight;
    m_initialized = true;
    LOGI("Dear ImGui Overlay Engine initialized successfully");
    return true;
}

void OverlayEngine::updateDimensions(int width, int height) {
    if (width > 0 && height > 0) {
        m_width = width;
        m_height = height;
        if (m_initialized) {
            ImGui::GetIO().DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
        }
    }
}

void OverlayEngine::setupTheme() {
    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowRounding = 8.0f;
    style.FrameRounding = 5.0f;
    style.PopupRounding = 6.0f;
    style.ScrollbarRounding = 6.0f;
    style.GrabRounding = 4.0f;
    style.WindowBorderSize = 1.0f;
    style.FrameBorderSize = 0.0f;

    ImVec4* colors = style.Colors;
    colors[ImGuiCol_WindowBg] = ImVec4(0.08f, 0.08f, 0.10f, 0.88f);
    colors[ImGuiCol_Header] = ImVec4(0.20f, 0.25f, 0.35f, 0.80f);
    colors[ImGuiCol_HeaderHovered] = ImVec4(0.26f, 0.35f, 0.50f, 0.80f);
    colors[ImGuiCol_HeaderActive] = ImVec4(0.26f, 0.35f, 0.50f, 1.00f);
    colors[ImGuiCol_Button] = ImVec4(0.85f, 0.10f, 0.15f, 0.80f);
    colors[ImGuiCol_ButtonHovered] = ImVec4(0.95f, 0.15f, 0.20f, 0.90f);
    colors[ImGuiCol_ButtonActive] = ImVec4(0.70f, 0.08f, 0.12f, 1.00f);
    colors[ImGuiCol_FrameBg] = ImVec4(0.15f, 0.15f, 0.18f, 0.80f);
    colors[ImGuiCol_TitleBg] = ImVec4(0.08f, 0.08f, 0.10f, 1.00f);
    colors[ImGuiCol_TitleBgActive] = ImVec4(0.12f, 0.12f, 0.15f, 1.00f);
    colors[ImGuiCol_Border] = ImVec4(0.25f, 0.25f, 0.30f, 0.60f);
}

size_t OverlayEngine::getMemoryRss() {
    std::ifstream statm("/proc/self/statm");
    if (!statm.is_open()) return 0;
    size_t size = 0, resident = 0;
    statm >> size >> resident;
    return (resident * sysconf(_SC_PAGESIZE)) / (1024 * 1024); // Return MB
}

void OverlayEngine::renderDiagnosticWindow() {
    ImGui::SetNextWindowSize(ImVec2(340, 240), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos(ImVec2(40, 40), ImGuiCond_FirstUseEver);

    if (ImGui::Begin("CCore Runtime Overlay", nullptr, ImGuiWindowFlags_NoCollapse)) {
        ImGui::TextColored(ImVec4(0.0f, 0.9f, 0.4f, 1.0f), "CONTAINER ENGINE ACTIVE");
        ImGui::Separator();

        pid_t pid = getpid();
        pid_t ppid = getppid();
        uid_t uid = getuid();

        ImGui::Text("Process ID : %d", pid);
        ImGui::Text("Parent PID : %d", ppid);
        ImGui::Text("Sandbox UID: %d", uid);
        ImGui::Text("Slot Target: :p0 (Virtual Client)");

        ImGui::Separator();
        ImGui::Text("Display Res: %dx%d", m_width, m_height);
        ImGui::Text("Frame Rate : %.1f FPS (%.2f ms)", ImGui::GetIO().Framerate, 1000.0f / (ImGui::GetIO().Framerate > 0 ? ImGui::GetIO().Framerate : 1.0f));
        ImGui::Text("Memory RSS : %zu MB", getMemoryRss());

        ImGui::Separator();
        static bool enableAntiRecord = true;
        ImGui::Checkbox("OEM Anti-Capture Filter", &enableAntiRecord);
        if (ImGui::Button("Minimize HUD", ImVec2(-1, 0))) {
            // Interactive control
        }
    }
    ImGui::End();
}

void OverlayEngine::render() {
    if (!m_initialized) return;

    // Backup OpenGL ES state
    GLint last_program; glGetIntegerv(GL_CURRENT_PROGRAM, &last_program);
    GLint last_texture; glGetIntegerv(GL_TEXTURE_BINDING_2D, &last_texture);
    GLint last_array_buffer; glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &last_array_buffer);
    GLint last_vertex_array; glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &last_vertex_array);
    GLint last_viewport[4]; glGetIntegerv(GL_VIEWPORT, last_viewport);
    GLboolean last_enable_blend = glIsEnabled(GL_BLEND);
    GLboolean last_enable_cull_face = glIsEnabled(GL_CULL_FACE);
    GLboolean last_enable_depth_test = glIsEnabled(GL_DEPTH_TEST);
    GLboolean last_enable_scissor_test = glIsEnabled(GL_SCISSOR_TEST);

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    renderDiagnosticWindow();

    ImGui::Render();
    glViewport(0, 0, m_width, m_height);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());

    // Restore OpenGL ES state
    glUseProgram(last_program);
    glBindTexture(GL_TEXTURE_2D, last_texture);
    glBindBuffer(GL_ARRAY_BUFFER, last_array_buffer);
    glBindVertexArray(last_vertex_array);
    glViewport(last_viewport[0], last_viewport[1], (GLsizei)last_viewport[2], (GLsizei)last_viewport[3]);
    if (last_enable_blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
    if (last_enable_cull_face) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
    if (last_enable_depth_test) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
    if (last_enable_scissor_test) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);

    m_frameCount++;
}

void OverlayEngine::shutdown() {
    if (!m_initialized) return;
    LOGI("Shutting down ImGui Overlay Engine...");
    ImGui_ImplOpenGL3_Shutdown();
    ImGui::DestroyContext();
    m_initialized = false;
}

} // namespace cold

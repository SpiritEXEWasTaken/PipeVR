// PipeVR: immersive 360°/180° video player for OpenXR headsets.
//
// The app's ExoPlayer renders into a SurfaceTexture bound to an external GL texture. Every
// frame this code updates that texture and draws it around the viewer: for each eye it
// computes the viewing direction of every pixel and maps it to the equirectangular video
// (360° or 180°, mono / top-bottom / side-by-side stereo). Only the head's orientation is
// used, so the video stays centred on the viewer.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#define LOG_TAG "PipeVRXr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Video layouts; keep in sync with ImmersiveVideoActivity.
enum Layout : int {
    MONO_360 = 0, TOP_BOTTOM_360 = 1, SIDE_BY_SIDE_360 = 2,
    SIDE_BY_SIDE_180 = 3, TOP_BOTTOM_180 = 4, MONO_180 = 5,
    // virtual theater: a big screen in front of the viewer (2D or 3D movies)
    SCREEN_2D = 6, SCREEN_SIDE_BY_SIDE = 7, SCREEN_TOP_BOTTOM = 8,
    LAYOUT_COUNT = 9
};

struct Bridge {
    JavaVM *vm = nullptr;
    jobject activity = nullptr;   // global ref
    jmethodID createSurface = nullptr;  // void onCreateVideoTexture(int)
    jmethodID updateTexture = nullptr;  // boolean onUpdateVideoTexture(float[])
    jmethodID onAction = nullptr;       // void onXrAction(int, int)
    jmethodID onFinished = nullptr;     // void onXrFinished(String)
    jmethodID onSurface = nullptr;      // void onCreateVideoSurface(Surface)
    jmethodID videoSize = nullptr;      // int[] getVideoSize()
    jmethodID label = nullptr;          // Bitmap renderOverlay(int)
    jmethodID chat = nullptr;           // Bitmap renderChat(int scroll) (null: no chat)
    jmethodID chatLayout = nullptr;     // float[] getChatLayout() {x, y, z, scale, visible}
    jmethodID saveChatLayout = nullptr; // void onChatLayout(float, float, float, float, boolean)
    jmethodID getEnvironment = nullptr; // int getEnvironment()
    jmethodID captionVersion = nullptr; // int getCaptionVersion()
    jmethodID panelsVersion = nullptr;  // int getPanelsVersion()
    jmethodID shortsPanel = nullptr;    // Bitmap renderShortsPanel() (null: not a short)
    jmethodID commentsPanel = nullptr;  // Bitmap renderCommentsPanel() (null: closed)
    jmethodID panelClick = nullptr;     // void onPanelClick(int, float, float)
    jmethodID captions = nullptr;       // Bitmap renderCaptions() (null: none showing)
    jmethodID saveEnvironment = nullptr; // void onEnvironment(int)
};

// actions sent to Java
constexpr int ACTION_PLAY_PAUSE = 1;
constexpr int ACTION_SEEK = 2;     // arg: seconds (+/-)
constexpr int ACTION_EXIT = 3;
constexpr int ACTION_LAYOUT = 4;   // arg: new layout
constexpr int ACTION_SKIP = 5;     // arg: +1 next video, -1 previous video
constexpr int ACTION_NOTE = 9;     // arg: environment shown (ENV_*), -1 no passthrough here
constexpr int ENV_DARK = 0, ENV_THEATER = 1, ENV_SPACE = 2, ENV_BEACH = 4, ENV_AURORA = 5,
        ENV_CITY = 6, ENV_PASSTHROUGH = 7, ENV_UNDERWATER = 8;
// the order Y goes through them
constexpr int kEnvCycle[] = {ENV_DARK, ENV_THEATER, ENV_SPACE, ENV_BEACH, ENV_AURORA, ENV_CITY,
                             ENV_UNDERWATER, ENV_PASSTHROUGH};
constexpr int kEnvCount = sizeof(kEnvCycle) / sizeof(kEnvCycle[0]);
int nextEnvironment(int current) {
    for (int i = 0; i < kEnvCount; i++) {
        if (kEnvCycle[i] == current) return kEnvCycle[(i + 1) % kEnvCount];
    }
    return ENV_DARK;
}

Bridge gBridge;
std::thread gThread;
std::atomic<bool> gRunning{false};
std::atomic<int> gLayout{MONO_360};

#define XR_CHECK(call)                                                        \
    do {                                                                      \
        XrResult _r = (call);                                                 \
        if (XR_FAILED(_r)) {                                                  \
            LOGE("%s failed: %d", #call, (int) _r);                           \
            error = std::string(#call) + " failed (" + std::to_string(_r) + ")"; \
            goto cleanup;                                                     \
        }                                                                     \
    } while (0)

const char *kVertexShader = R"(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
out vec3 vDir;
void main() {
    vDir = aPos;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
)";

const char *kFragmentShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform int uLayout;
uniform int uEye;
uniform vec2 uVideoSize;
uniform float uScreenAspect;
uniform int uEnv;          // 0 dark room, 1 theater, 2 space, 3 unused, 4 beach, 5 aurora,
                           // 6 city rooftop, 7 passthrough, 8 underwater
uniform float uTime;       // seconds, for slow motion in the environments
uniform int uBake;         // 1: drawing the environment into its cube map (no screen)
uniform int uCubeReady;    // 1: the environment is in uEnvCube, look it up instead
uniform samplerCube uEnvCube;
in vec3 vDir;
out vec4 fragColor;
const float PI = 3.14159265359;
vec3 tap(vec2 uv) {
    return texture(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}
// Catmull-Rom bicubic filtering in 9 bilinear taps: keeps the video sharp when it is
// enlarged (a 4K 360° video has far fewer pixels per degree than the headset's display)
vec3 bicubic(vec2 uv) {
    vec2 samplePos = uv * uVideoSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 p0 = (texPos1 - 1.0) / uVideoSize;
    vec2 p3 = (texPos1 + 2.0) / uVideoSize;
    vec2 p12 = (texPos1 + w2 / w12) / uVideoSize;
    vec3 c = tap(vec2(p0.x, p0.y)) * w0.x * w0.y
           + tap(vec2(p12.x, p0.y)) * w12.x * w0.y
           + tap(vec2(p3.x, p0.y)) * w3.x * w0.y
           + tap(vec2(p0.x, p12.y)) * w0.x * w12.y
           + tap(vec2(p12.x, p12.y)) * w12.x * w12.y
           + tap(vec2(p3.x, p12.y)) * w3.x * w12.y
           + tap(vec2(p0.x, p3.y)) * w0.x * w3.y
           + tap(vec2(p12.x, p3.y)) * w12.x * w3.y
           + tap(vec2(p3.x, p3.y)) * w3.x * w3.y;
    return clamp(c, 0.0, 1.0);
}

// ---------- environments (theater views) ----------
float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}
float noise3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash13(i), hash13(i + vec3(1, 0, 0)), f.x),
                   mix(hash13(i + vec3(0, 1, 0)), hash13(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash13(i + vec3(0, 0, 1)), hash13(i + vec3(1, 0, 1)), f.x),
                   mix(hash13(i + vec3(0, 1, 1)), hash13(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}
float fbm(vec3 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise3(p); p = p * 2.03 + 1.7; a *= 0.5; }
    return v;
}
// average colour of the picture, to light the theater with what is playing
vec3 screenLight() {
    vec3 c = tap(vec2(0.5, 0.5)) + tap(vec2(0.25, 0.3)) + tap(vec2(0.75, 0.3))
           + tap(vec2(0.25, 0.7)) + tap(vec2(0.75, 0.7));
    return pow(c * 0.2, vec3(2.2));
}
vec3 stars(vec3 d) {
    vec3 c = vec3(0.0);
    // three layers of star cells of different sizes
    for (int l = 0; l < 3; l++) {
        float scale = 90.0 + float(l) * 110.0;
        vec3 g = d * scale;
        vec3 cell = floor(g);
        float h = hash13(cell + float(l) * 17.0);
        if (h > 0.985) {
            vec3 sp = cell + 0.5 + 0.35 * (vec3(hash13(cell + 3.1), hash13(cell + 5.7),
                                                  hash13(cell + 9.3)) - 0.5);
            float dist = length(g - sp);
            float b = smoothstep(0.5, 0.0, dist) * (h - 0.985) * 66.0;
            vec3 tint = mix(vec3(1.0, 0.85, 0.7), vec3(0.75, 0.85, 1.0), hash13(cell + 1.3));
            c += tint * b * (0.6 + 0.4 * sin(uTime * (1.0 + h * 3.0) + h * 40.0));
        }
    }
    // faint milky way band
    vec3 band = normalize(vec3(0.3, 0.8, 0.5));
    float m = exp(-pow(dot(d, band) * 3.2, 2.0));
    c += vec3(0.05, 0.045, 0.06) * m * (0.4 + 0.9 * fbm(d * 6.0));
    return c;
}
vec3 space(vec3 d) {
    vec3 sunDir = normalize(vec3(-0.8, 0.45, -0.4));
    vec3 col = stars(d);
    // the sun
    float sd = max(dot(d, sunDir), 0.0);
    col += vec3(1.0, 0.95, 0.85) * (smoothstep(0.9995, 0.9998, sd) * 8.0
                                    + pow(sd, 400.0) * 1.2 + pow(sd, 30.0) * 0.03);
    // the Earth below: radius R, viewer at height H above the surface
    const float R = 6.0, H = 1.1;
    vec3 o = vec3(0.0, R + H, 0.0);
    float b = dot(o, d);
    float c = dot(o, o) - R * R;
    float disc = b * b - c;
    // closest approach of the ray to the centre: drives the atmosphere glow
    float closest = sqrt(max(dot(o, o) - b * b, 0.0));
    if (disc > 0.0 && -b > 0.0) {
        float t = -b - sqrt(disc);
        vec3 n = normalize(o + d * t);
        // the planet turns slowly
        float a = uTime * 0.01;
        vec3 q = vec3(n.x * cos(a) - n.y * sin(a), n.x * sin(a) + n.y * cos(a), n.z);
        float land = fbm(q * 3.0);
        vec3 surf = land > 0.53
                ? mix(vec3(0.16, 0.30, 0.10), vec3(0.45, 0.38, 0.22), smoothstep(0.55, 0.7, land))
                : mix(vec3(0.01, 0.05, 0.16), vec3(0.03, 0.12, 0.30), land * 1.6);
        float ice = smoothstep(0.86, 0.95, abs(q.z));
        surf = mix(surf, vec3(0.85), ice);
        // clouds drift a little faster
        vec3 cq = vec3(n.x * cos(a * 1.3) - n.y * sin(a * 1.3),
                       n.x * sin(a * 1.3) + n.y * cos(a * 1.3), n.z);
        float cloud = smoothstep(0.55, 0.8, fbm(cq * 5.0 + 4.0));
        surf = mix(surf, vec3(0.9), cloud * 0.8);
        float light = max(dot(n, sunDir), 0.0);
        vec3 day = surf * (0.04 + 1.1 * light);
        // city lights on the night side
        float city = land > 0.55 ? smoothstep(0.6, 0.85, noise3(q * 40.0)) : 0.0;
        vec3 night = vec3(1.0, 0.7, 0.35) * city * 0.25 * (1.0 - cloud)
                * smoothstep(0.1, -0.1, dot(n, sunDir));
        // atmosphere towards the limb
        float limb = pow(1.0 - max(dot(n, -d), 0.0), 3.0);
        col = day + night + vec3(0.25, 0.5, 1.0) * limb * (0.15 + 0.8 * light);
    } else {
        // glow of the atmosphere around the planet's edge
        float h = closest - R;
        if (h > 0.0 && -b > 0.0) {
            float lit = max(dot(normalize(o + d * (-b)), sunDir) + 0.3, 0.0);
            col += vec3(0.3, 0.55, 1.0) * exp(-h * 9.0) * (0.25 + 0.9 * lit);
        }
    }
    return col;
}

vec3 sunsetSky(vec3 d, vec3 sunDir) {
    float h = max(d.y, 0.0);
    vec3 col = mix(vec3(1.0, 0.45, 0.18), vec3(0.55, 0.22, 0.38), smoothstep(0.0, 0.18, h));
    col = mix(col, vec3(0.10, 0.10, 0.28), smoothstep(0.15, 0.7, h));
    float sd = max(dot(d, sunDir), 0.0);
    col += vec3(1.0, 0.55, 0.2) * pow(sd, 8.0) * 0.5 + vec3(1.0, 0.8, 0.5) * pow(sd, 60.0);
    col += vec3(1.0, 0.9, 0.7) * smoothstep(0.9990, 0.9994, sd) * 3.0;
    // long streaky clouds lit from below
    if (d.y > 0.01) {
        vec2 cp = d.xz / d.y;
        float c = fbm(vec3(cp.x * 0.25, cp.y * 1.2, uTime * 0.01));
        c = smoothstep(0.5, 0.75, c) * smoothstep(0.0, 0.08, d.y);
        vec3 cc = mix(vec3(1.0, 0.5, 0.35), vec3(0.45, 0.2, 0.35), smoothstep(0.05, 0.4, d.y));
        col = mix(col, cc + vec3(0.6, 0.3, 0.1) * pow(sd, 6.0), c * 0.8);
    }
    return col;
}
vec3 beach(vec3 d) {
    vec3 sunDir = normalize(vec3(-0.55, 0.035, -0.83));
    // distant islands on the horizon
    float az = atan(d.x, -d.z);
    float isl = 0.012 * smoothstep(0.45, 0.8, fbm(vec3(az * 3.0, 0.0, 2.0))) - 0.002;
    if (d.y > 0.0) {
        if (d.y < isl) return vec3(0.12, 0.05, 0.08);
        return sunsetSky(d, sunDir);
    }
    const float EYE = 1.6, SHORE = 4.5;
    float t = -EYE / d.y;
    vec3 p = d * t;
    float shore = -SHORE + 0.6 * sin(p.x * 0.35 + uTime * 0.4) * 0.5
                  + 0.4 * sin(p.x * 0.9 - uTime * 0.7) * 0.4;
    if (p.z < shore) {
        // the sea: wavy normal, sky reflection, the sun's glittering path
        vec3 q = vec3(p.x * 0.6, p.z * 1.5, uTime * 0.5);
        vec3 n = normalize(vec3((noise3(q) - 0.5) * 0.25, 1.0,
                                (noise3(q + 7.3) - 0.5) * 0.25));
        vec3 r = reflect(d, n);
        r.y = abs(r.y);
        float fres = 0.04 + 0.96 * pow(1.0 - max(dot(-d, n), 0.0), 5.0);
        vec3 col = mix(vec3(0.03, 0.06, 0.10), sunsetSky(r, sunDir), fres);
        col += vec3(1.0, 0.65, 0.3) * pow(max(dot(r, sunDir), 0.0), 300.0) * 6.0;
        // foam where the waves meet the sand
        float foam = smoothstep(1.2, 0.0, shore - p.z) * (0.5 + 0.5 * noise3(vec3(p.xz * 3.0, uTime)));
        return mix(col, vec3(0.9, 0.75, 0.7), foam * 0.6) * exp(-t * 0.004);
    }
    // sand: wet and darker near the water
    float wet = smoothstep(2.5, 0.0, p.z - shore);
    vec3 sand = mix(vec3(0.5, 0.33, 0.24), vec3(0.25, 0.15, 0.12), wet);
    sand *= 0.85 + 0.15 * noise3(vec3(p.xz * 8.0, 0.0));
    vec3 col = sand * (vec3(0.55, 0.32, 0.25) + vec3(0.6, 0.3, 0.1) * max(dot(vec3(0, 1, 0), sunDir), 0.0));
    col += vec3(1.0, 0.6, 0.3) * wet * pow(max(dot(reflect(d, vec3(0, 1, 0)), sunDir), 0.0), 40.0) * 0.5;
    return col;
}
vec3 auroraSky(vec3 d) {
    vec3 col = mix(vec3(0.01, 0.02, 0.05), vec3(0.002, 0.004, 0.015), smoothstep(0.0, 0.6, d.y));
    col += stars(d) * 0.8;
    if (d.y > 0.02) {
        // curtains of light at several heights
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float h = 6.0 + float(i) * 0.5;
            vec2 p = d.xz * (h / d.y);
            float band = sin(p.x * 0.08 + 2.5 * fbm(vec3(p * 0.03, uTime * 0.03)) + uTime * 0.05);
            float a = smoothstep(0.85, 1.0, 1.0 - abs(band)) * smoothstep(60.0, 15.0, length(p));
            vec3 c = mix(vec3(0.1, 1.0, 0.45), vec3(0.6, 0.2, 0.9), float(i) / 8.0);
            acc += c * a * (1.0 - float(i) / 9.0);
        }
        col += acc * 0.12;
    }
    return col;
}
vec3 aurora(vec3 d) {
    // snowy mountains around a still lake
    float az = atan(d.x, -d.z);
    float ridge = 0.02 + 0.13 * fbm(vec3(az * 2.2, 1.0, 0.0)) * smoothstep(0.0, 0.4, abs(sin(az)) + 0.3);
    if (d.y > 0.0) {
        if (d.y < ridge) {
            float snow = smoothstep(ridge - 0.04, ridge - 0.005, d.y);
            return mix(vec3(0.01, 0.015, 0.03), vec3(0.18, 0.22, 0.3), snow * 0.6)
                   + auroraSky(vec3(d.x, 0.3, d.z)) * 0.08;
        }
        return auroraSky(d);
    }
    const float EYE = 1.6;
    float t = -EYE / d.y;
    vec3 p = d * t;
    float lake = length(vec2(p.x * 0.6, p.z + 30.0)) - 26.0
                 + 2.0 * (fbm(vec3(p.xz * 0.1, 3.0)) - 0.5) * 4.0;
    if (lake < 0.0) {
        // the lake mirrors the sky and the mountains
        vec3 r = vec3(d.x, -d.y, d.z);
        float rr = 0.02 + 0.13 * fbm(vec3(az * 2.2, 1.0, 0.0))
                   * smoothstep(0.0, 0.4, abs(sin(az)) + 0.3);
        vec3 refl = r.y < rr ? vec3(0.01, 0.015, 0.03) : auroraSky(r);
        float ripple = 0.9 + 0.1 * noise3(vec3(p.xz * 2.0, uTime * 0.3));
        // a little snow-covered shore blending in
        return mix(refl * 0.6 * ripple, vec3(0.03, 0.045, 0.06), smoothstep(-1.5, 0.0, lake));
    }
    // snow, faintly lit green by the aurora
    float sn = 0.9 + 0.1 * noise3(vec3(p.xz * 5.0, 0.0));
    return vec3(0.03, 0.045, 0.06) * sn + vec3(0.0, 0.012, 0.006);
}
vec3 city(vec3 d) {
    float az = atan(d.x, -d.z);
    vec3 col = mix(vec3(0.16, 0.07, 0.12), vec3(0.01, 0.015, 0.05), smoothstep(-0.05, 0.5, d.y));
    col += stars(d) * 0.25 * smoothstep(0.2, 0.5, d.y);
    // the moon
    vec3 moon = normalize(vec3(0.5, 0.45, -0.75));
    float md = dot(d, moon);
    col += vec3(0.9, 0.9, 0.8) * smoothstep(0.9993, 0.9996, md) + vec3(0.15, 0.15, 0.2) * pow(max(md, 0.0), 200.0);
    // two layers of buildings, far then near
    for (int layer = 0; layer < 2; layer++) {
        float n = layer == 0 ? 90.0 : 40.0;
        float x = az * n / 3.14159;
        float id = floor(x);
        float hb = hash13(vec3(id, float(layer), 3.0));
        float top = (layer == 0 ? 0.04 + 0.12 * hb : 0.02 + 0.22 * hb * hb);
        if (d.y < top && fract(x) > 0.08) {
            float dist = layer == 0 ? 1.0 : 0.6;
            vec3 b = vec3(0.012, 0.012, 0.025) * dist;
            // lit windows
            vec2 w = vec2(fract(x) * 6.0, (d.y + 0.2) * (layer == 0 ? 160.0 : 90.0));
            vec2 wc = floor(w);
            float on = step(0.62, hash13(vec3(wc + id * 13.0, float(layer))));
            float inside = step(0.25, fract(w.x)) * step(0.3, fract(w.y));
            vec3 wcol = mix(vec3(1.0, 0.75, 0.4), vec3(0.6, 0.8, 1.0), hash13(vec3(wc, id)));
            b += wcol * on * inside * (layer == 0 ? 0.35 : 0.5);
            // red light on the tallest roofs
            if (hb > 0.85 && top - d.y < 0.004 && abs(fract(x) - 0.5) < 0.06)
                b += vec3(1.0, 0.1, 0.05) * step(0.5, fract(uTime * 0.5 + hb * 7.0));
            col = b;
        }
    }
    if (d.y < 0.0) {
        // the rooftop you stand on, with a low wall at the edge
        float t = -1.6 / d.y;
        vec3 p = d * t;
        float edge = max(abs(p.x), abs(p.z));
        if (edge < 6.0) {
            float tiles = 0.9 + 0.1 * step(0.05, fract(p.x * 0.8)) * step(0.05, fract(p.z * 0.8));
            col = vec3(0.018, 0.018, 0.024) * tiles + vec3(0.02, 0.012, 0.01) * exp(-edge * 0.4);
        } else {
            // the low wall at the roof's edge, with the glow of the streets above it
            col = vec3(0.012, 0.012, 0.016) + vec3(0.05, 0.025, 0.015) * smoothstep(-0.25, 0.0, d.y);
        }
    }
    return col;
}
vec3 underwater(vec3 d) {
    vec3 col = mix(vec3(0.0, 0.05, 0.09), vec3(0.03, 0.35, 0.5), smoothstep(-0.6, 0.9, d.y));
    // shafts of light from the surface
    float az = atan(d.x, -d.z);
    float rays = pow(noise3(vec3(az * 6.0, uTime * 0.15, 0.0)), 3.0) * smoothstep(-0.2, 0.9, d.y);
    col += vec3(0.2, 0.45, 0.45) * rays * 0.6;
    // the bright, rippling surface overhead
    if (d.y > 0.0) {
        vec2 sp = d.xz * (8.0 / d.y);
        float rip = noise3(vec3(sp * 0.3, uTime * 0.4));
        col += vec3(0.3, 0.6, 0.6) * smoothstep(0.55, 0.9, rip) * smoothstep(0.2, 0.8, d.y);
    } else {
        // sandy floor with moving caustics, fading into the blue
        float t = -4.0 / d.y;
        vec3 p = d * t;
        float c1 = noise3(vec3(p.xz * 1.5, uTime * 0.5));
        float c2 = noise3(vec3(p.xz * 1.5 + 5.0, uTime * 0.5 + 3.0));
        float caust = pow(1.0 - abs(c1 - c2) * 2.0, 8.0);
        vec3 floorCol = vec3(0.25, 0.3, 0.25) * (0.8 + 0.2 * noise3(vec3(p.xz * 4.0, 1.0)))
                        + vec3(0.4, 0.6, 0.5) * caust * 0.5;
        // sea grass patches
        float grass = smoothstep(0.62, 0.7, fbm(vec3(p.xz * 0.3, 0.0)));
        floorCol = mix(floorCol, vec3(0.05, 0.2, 0.08), grass);
        col = mix(col, floorCol, exp(-t * 0.08));
    }
    // bubbles rising
    vec3 g = d * 40.0 + vec3(0.0, -uTime * 2.0, 0.0);
    vec3 cell = floor(g);
    if (hash13(cell) > 0.993) {
        float b = smoothstep(0.25, 0.1, length(fract(g) - 0.5));
        col += vec3(0.5, 0.8, 0.9) * b * 0.5;
    }
    return col;
}
// background for the environments that have no room geometry around the screen
vec3 skyEnv(vec3 d) {
    if (uEnv == 2) return space(d);
    if (uEnv == 4) return beach(d);
    if (uEnv == 5) return aurora(d);
    if (uEnv == 6) return city(d);
    return underwater(d);
}
// the baked cube map when there is one (cheap), else drawn live
vec3 envBg(vec3 d) {
    if (uCubeReady == 1) return texture(uEnvCube, d).rgb;
    return skyEnv(d);
}
vec3 theater(vec3 d, vec2 screenHalf, vec3 light) {
    // a cinema hall: floor 1.2 m below the eyes, ceiling 5 m up, screen wall 3 m ahead
    const float FLOOR = -1.2, CEIL = 5.0, FRONT = -3.02, BACK = 9.0, SIDE = 6.0;
    float t = 1e9;
    int hit = 0;
    if (d.y < 0.0) { float tt = FLOOR / d.y; if (tt < t) { t = tt; hit = 1; } }
    if (d.y > 0.0) { float tt = CEIL / d.y; if (tt < t) { t = tt; hit = 2; } }
    if (d.z < 0.0) { float tt = FRONT / d.z; if (tt < t) { t = tt; hit = 3; } }
    if (d.z > 0.0) { float tt = BACK / d.z; if (tt < t) { t = tt; hit = 4; } }
    if (abs(d.x) > 0.0) { float tt = SIDE / abs(d.x); if (tt < t) { t = tt; hit = 5; } }
    // rows of seats in front of you (their backs), with the tops just below eye level
    for (int r = 0; r < 2; r++) {
        float zr = -1.1 - float(r) * 1.0;
        if (d.z < 0.0) {
            float tt = zr / d.z;
            vec3 p = d * tt;
            float top = -0.62 + float(r) * 0.12;   // rows further ahead sit a step lower
            top -= float(r) * 0.12 + 0.0;
            float seatX = fract(p.x / 0.6 + 0.5) - 0.5;
            float bump = top - 0.9 * seatX * seatX - (abs(seatX) > 0.44 ? 0.25 : 0.0);
            if (tt < t && p.y < bump && p.y > FLOOR && abs(p.x) < SIDE - 0.6) {
                t = tt;
                hit = 10 + r;
            }
        }
    }
    vec3 p = d * t;
    // light from the screen: stronger near it and on surfaces facing it
    float toScreen = length(p - vec3(0.0, 0.0, FRONT));
    vec3 lit = light * (0.55 / (1.0 + toScreen * toScreen * 0.12)) + vec3(0.002, 0.0015, 0.0015);
    vec3 col;
    if (hit == 3) {
        vec2 e = abs(p.xy) - screenHalf;
        float border = max(e.x, e.y);
        if (border < 0.12) {
            col = vec3(0.0);                                // black masking around the screen
        } else if (abs(p.x) > screenHalf.x + 0.12) {
            // red velvet curtains with folds
            float folds = 0.55 + 0.45 * sin(p.x * 18.0 + sin(p.y * 1.3) * 0.6);
            col = vec3(0.45, 0.03, 0.04) * folds * (lit * 1.4 + 0.012);
        } else {
            col = vec3(0.05, 0.02, 0.02) * (lit * 1.4 + 0.01);
        }
    } else if (hit == 5) {
        // side walls: dark panels with warm sconces
        float panel = 0.75 + 0.25 * step(0.06, fract(p.z * 0.5));
        col = vec3(0.07, 0.02, 0.025) * panel * (lit * 2.0 + 0.03);
        vec2 sc = vec2(fract(p.z / 3.0) - 0.5, p.y - 2.4);
        float sconce = exp(-dot(sc, sc) * 60.0);
        col += vec3(1.0, 0.55, 0.25) * sconce * 0.5;
        col += vec3(0.5, 0.25, 0.1) * exp(-dot(sc, sc) * 3.0) * 0.04;
    } else if (hit == 2) {
        // ceiling with small lights
        vec2 g = p.xz * 1.2;
        vec2 cell = floor(g);
        float h = hash13(vec3(cell, 7.0));
        float dot2 = h > 0.7 ? exp(-dot(fract(g) - 0.5, fract(g) - 0.5) * 300.0) : 0.0;
        col = vec3(0.01) * (lit + 0.02) + vec3(0.9, 0.7, 0.45) * dot2 * 0.25;
    } else if (hit == 1) {
        // carpet with a subtle pattern, and aisle lights along the rows
        float pat = 0.8 + 0.2 * noise3(vec3(p.xz * 6.0, 0.0));
        col = vec3(0.06, 0.015, 0.02) * pat * (lit * 1.2 + 0.01);
        float strip = exp(-pow((fract(p.z / 0.95 + 0.5) - 0.5) * 30.0, 2.0))
                * step(SIDE - 0.9, abs(p.x));
        col += vec3(1.0, 0.6, 0.25) * strip * 0.12;
    } else if (hit >= 10) {
        // seat backs: red upholstery lit by the screen from behind = mostly silhouettes
        float seatX = fract(p.x / 0.6 + 0.5) - 0.5;
        float edge = smoothstep(0.5, 0.42, abs(seatX));
        // lit from the screen on their tops, dark lower down
        float topLight = smoothstep(-1.2, -0.6, p.y);
        col = vec3(0.3, 0.035, 0.05) * edge * (lit * (0.4 + 1.2 * topLight) + 0.006);
    } else {
        col = vec3(0.02, 0.008, 0.01) * (lit + 0.02);       // back wall
    }
    return col;
}
void main() {
    vec3 d = normalize(vDir);
    if (uBake == 1) {
        fragColor = vec4(skyEnv(d), 1.0);
        return;
    }
    if (uLayout >= 6) {
        // theater: a screen 3 m in front, about 60 degrees wide, in a dark room
        vec3 room = vec3(0.012, 0.012, 0.018) + vec3(0.02) * max(0.0, -d.y);
        // wide videos: ~60 degrees wide; tall ones (Shorts): limited in height instead
        float halfW = 1.75;
        float halfH = halfW / uScreenAspect;
        if (uScreenAspect < 1.0) {
            halfH = 1.3;
            halfW = halfH * uScreenAspect;
        }
        if (d.z > -0.05) {
            if (uEnv == 7) fragColor = vec4(0.0);
            else if (uEnv == 1) fragColor = vec4(theater(d, vec2(halfW, halfH), screenLight()), 1.0);
            else if (uEnv >= 2) fragColor = vec4(envBg(d), 1.0);
            else fragColor = vec4(room, 1.0);
            return;
        }
        vec2 p = d.xy * (-3.0 / d.z);
        vec2 q = vec2(p.x / (2.0 * halfW) + 0.5, p.y / (2.0 * halfH) + 0.5);
        if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) {
            // soft glow of the screen on the room
            float dist = length(max(abs(p) - vec2(halfW, halfH), 0.0));
            if (uEnv == 1) {
                fragColor = vec4(theater(d, vec2(halfW, halfH), screenLight()), 1.0);
                return;
            }
            if (uEnv >= 2 && uEnv != 7) {
                // a thin frame so the screen doesn't look cut out of the sky
                float frame = smoothstep(0.03, 0.0, dist);
                fragColor = vec4(mix(envBg(d), vec3(0.02), frame), 1.0);
                return;
            }
            if (uEnv == 7) {
                // the real room shows through, with a faint glow around the screen
                float g = 0.25 * exp(-dist * 6.0);
                fragColor = vec4(vec3(0.6) * g, g);
                return;
            }
            fragColor = vec4(room + vec3(0.05) * exp(-dist * 3.0), 1.0);
            return;
        }
        float su = q.x, sv = q.y;
        if (uLayout == 7) su = su * 0.5 + (uEye == 0 ? 0.0 : 0.5);   // left eye on the left
        if (uLayout == 8) sv = sv * 0.5 + (uEye == 0 ? 0.5 : 0.0);   // left eye on top
        fragColor = vec4(pow(bicubic(vec2(su, sv)), vec3(2.2)), 1.0);
        return;
    }
    float lon = atan(d.x, -d.z);          // 0 = straight ahead
    float lat = asin(clamp(d.y, -1.0, 1.0));
    bool is180 = uLayout >= 3;
    float u;
    if (is180) {
        if (abs(lon) > PI * 0.5) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        u = lon / PI + 0.5;
    } else {
        u = lon / (2.0 * PI) + 0.5;
    }
    float v = 0.5 + lat / PI;             // texture origin is bottom-left
    if (uLayout == 1 || uLayout == 4) {   // top-bottom: left eye on top
        v = v * 0.5 + (uEye == 0 ? 0.5 : 0.0);
    } else if (uLayout == 2 || uLayout == 3) { // side-by-side: left eye on the left
        u = u * 0.5 + (uEye == 0 ? 0.0 : 0.5);
    }
    // the swapchain is sRGB: convert the (gamma encoded) video colours to linear first
    fragColor = vec4(pow(bicubic(vec2(u, v)), vec3(2.2)), 1.0);
}
)";

// drawing one face of the environment cube map: a full-screen quad, direction per pixel
const char *kBakeVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec3 uFwd;
uniform vec3 uRight;
uniform vec3 uUp;
out vec3 vDir;
void main() {
    vDir = uFwd + aPos.x * uRight + aPos.y * uUp;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

// faces in GL order (+X, -X, +Y, -Y, +Z, -Z): forward, right (s), up (t)
const float kCubeFaces[6][9] = {
        {1, 0, 0, 0, 0, -1, 0, -1, 0},
        {-1, 0, 0, 0, 0, 1, 0, -1, 0},
        {0, 1, 0, 1, 0, 0, 0, 0, 1},
        {0, -1, 0, 1, 0, 0, 0, 0, -1},
        {0, 0, 1, 1, 0, 0, 0, -1, 0},
        {0, 0, -1, -1, 0, 0, 0, -1, 0}};
constexpr int kCubeSize = 1024;

const char *kLabelVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uProj;
uniform vec2 uSize;
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    // head-locked, 1.5 m ahead and a bit below the centre of view
    gl_Position = uProj * vec4(aPos.x * uSize.x, aPos.y * uSize.y - 0.40, -1.5, 1.0);
}
)";

// controller pointer rays and the cursor dot: plain coloured shapes
const char *kSolidVertex = R"(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
void main() {
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
)";
const char *kSolidFragment = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
)";

/** A flat panel in the room: centre and half-size vectors (as the panel shader uses them). */
struct Panel {
    float c[3] = {0, 0, 0}, r[3] = {0, 0, 0}, u[3] = {0, 0, 0};
};

/** Panel at (cx, cy, cz) facing the viewer, halfH tall, width from the picture's aspect. */
Panel facingPanel(float cx, float cy, float cz, float halfH, float aspect) {
    Panel p;
    const float len = std::max(0.1f, sqrtf(cx * cx + cz * cz));
    const float nx = -cx / len, nz = -cz / len;
    const float halfW = halfH * aspect;
    p.c[0] = cx; p.c[1] = cy; p.c[2] = cz;
    p.r[0] = nz * halfW; p.r[1] = 0; p.r[2] = -nx * halfW;
    p.u[0] = 0; p.u[1] = halfH; p.u[2] = 0;
    return p;
}

/** Ray o + t*d against a panel: true with the hit point and texture coordinates (0..1). */
bool hitPanel(const Panel &p, const float *o, const float *d, float *hit, float &tu, float &tv,
              float &dist) {
    const float n[3] = {p.r[1] * p.u[2] - p.r[2] * p.u[1], p.r[2] * p.u[0] - p.r[0] * p.u[2],
                        p.r[0] * p.u[1] - p.r[1] * p.u[0]};
    const float denom = n[0] * d[0] + n[1] * d[1] + n[2] * d[2];
    if (fabsf(denom) < 1e-6f) return false;
    const float t = (n[0] * (p.c[0] - o[0]) + n[1] * (p.c[1] - o[1]) + n[2] * (p.c[2] - o[2]))
                    / denom;
    if (t <= 0) return false;
    float l[3];
    for (int i = 0; i < 3; i++) {
        hit[i] = o[i] + t * d[i];
        l[i] = hit[i] - p.c[i];
    }
    const float rr = p.r[0] * p.r[0] + p.r[1] * p.r[1] + p.r[2] * p.r[2];
    const float uu = p.u[0] * p.u[0] + p.u[1] * p.u[1] + p.u[2] * p.u[2];
    const float a = (l[0] * p.r[0] + l[1] * p.r[1] + l[2] * p.r[2]) / rr;
    const float b = (l[0] * p.u[0] + l[1] * p.u[1] + l[2] * p.u[2]) / uu;
    if (fabsf(a) > 1 || fabsf(b) > 1) return false;
    tu = (a + 1) * 0.5f;
    tv = (1 - b) * 0.5f;
    dist = t;
    return true;
}

// a panel placed in the room (live chat next to the screen)
const char *kPanelVertex = R"(#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uViewProj;
uniform vec3 uCenter;
uniform vec3 uRight;
uniform vec3 uUp;
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = uViewProj * vec4(uCenter + aPos.x * uRight + aPos.y * uUp, 1.0);
}
)";

const char *kLabelFragment = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uAlpha;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv);
    fragColor = vec4(pow(c.rgb, vec3(2.2)), c.a * uAlpha);
}
)";

GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("shader: %s", log);
    }
    return s;
}

// column-major 4x4 helpers
void matMul(float *out, const float *a, const float *b) {
    float r[16];
    for (int c = 0; c < 4; c++)
        for (int rr = 0; rr < 4; rr++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += a[k * 4 + rr] * b[c * 4 + k];
            r[c * 4 + rr] = s;
        }
    memcpy(out, r, sizeof(r));
}

void projection(float *m, const XrFovf &fov, float nearZ, float farZ) {
    const float l = tanf(fov.angleLeft), r = tanf(fov.angleRight);
    const float d = tanf(fov.angleDown), u = tanf(fov.angleUp);
    const float w = r - l, h = u - d;
    memset(m, 0, 16 * sizeof(float));
    m[0] = 2 / w;
    m[5] = 2 / h;
    m[8] = (r + l) / w;
    m[9] = (u + d) / h;
    m[10] = -(farZ + nearZ) / (farZ - nearZ);
    m[11] = -1;
    m[14] = -(2 * farZ * nearZ) / (farZ - nearZ);
}

// inverse rotation (view matrix without translation) of a quaternion, then yaw offset
void viewRotation(float *m, const XrQuaternionf &q, float yawOffset) {
    // rotation matrix of q
    const float x = q.x, y = q.y, z = q.z, w = q.w;
    float r[16] = {
            1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
            2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
            2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
            0, 0, 0, 1};
    // inverse = transpose
    float inv[16];
    for (int c = 0; c < 4; c++)
        for (int rr = 0; rr < 4; rr++) inv[c * 4 + rr] = r[rr * 4 + c];
    // yaw offset rotates the world (recentering)
    const float cy = cosf(yawOffset), sy = sinf(yawOffset);
    float yaw[16] = {cy, 0, -sy, 0, 0, 1, 0, 0, sy, 0, cy, 0, 0, 0, 0, 1};
    matMul(m, inv, yaw);
}

float yawOf(const XrQuaternionf &q) {
    // heading of the -Z forward vector
    const float fx = -2 * (q.x * q.z + q.w * q.y);
    const float fz = -(1 - 2 * (q.x * q.x + q.y * q.y));
    return atan2f(-fx, -fz);
}

JNIEnv *attach() {
    JNIEnv *env = nullptr;
    if (gBridge.vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        gBridge.vm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

void sendAction(JNIEnv *env, int action, int arg) {
    env->CallVoidMethod(gBridge.activity, gBridge.onAction, action, arg);
}

XrPath path(XrInstance instance, const char *s) {
    XrPath p = XR_NULL_PATH;
    xrStringToPath(instance, s, &p);
    return p;
}

void suggest(XrInstance instance, const char *profile,
             const std::vector<std::pair<XrAction, const char *>> &bindings) {
    std::vector<XrActionSuggestedBinding> b;
    for (auto &pair : bindings) b.push_back({pair.first, path(instance, pair.second)});
    XrInteractionProfileSuggestedBinding s{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    s.interactionProfile = path(instance, profile);
    s.suggestedBindings = b.data();
    s.countSuggestedBindings = (uint32_t) b.size();
    XrResult r = xrSuggestInteractionProfileBindings(instance, &s);
    LOGI("bindings for %s: %d", profile, (int) r);
}

bool hasExtension(const std::vector<XrExtensionProperties> &all, const char *name) {
    for (auto &e : all) if (strcmp(e.extensionName, name) == 0) return true;
    return false;
}

void queryVideoSize(JNIEnv *env, int &w, int &h) {
    w = 3840;
    h = 2160;
    auto arr = (jintArray) env->CallObjectMethod(gBridge.activity, gBridge.videoSize);
    if (arr == nullptr) return;
    jint v[2];
    env->GetIntArrayRegion(arr, 0, 2, v);
    env->DeleteLocalRef(arr);
    if (v[0] > 0 && v[1] > 0) {
        w = v[0];
        h = v[1];
    }
}

/** Creates the swapchain the video decoder writes into; tries the setups runtimes accept. */
bool createVideoSwapchain(PFN_xrCreateSwapchainAndroidSurfaceKHR create, XrSession session,
                          bool fbCreate, int w, int h, XrSwapchain *out, jobject *surface) {
    struct Attempt { bool fb; XrSwapchainUsageFlags usage; int64_t format; };
    const Attempt attempts[] = {
            {true, 0, 0},
            {false, 0, 0},
            {true, XR_SWAPCHAIN_USAGE_SAMPLED_BIT, GL_RGBA8},
            {false, XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT,
             GL_RGBA8},
    };
    for (const Attempt &a : attempts) {
        if (a.fb && !fbCreate) continue;
        XrAndroidSurfaceSwapchainCreateInfoFB fbInfo{
                XR_TYPE_ANDROID_SURFACE_SWAPCHAIN_CREATE_INFO_FB};
        fbInfo.createFlags = XR_ANDROID_SURFACE_SWAPCHAIN_SYNCHRONOUS_BIT_FB |
                             XR_ANDROID_SURFACE_SWAPCHAIN_USE_TIMESTAMPS_BIT_FB;
        XrSwapchainCreateInfo vi{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        vi.next = a.fb ? &fbInfo : nullptr;
        vi.usageFlags = a.usage;
        vi.format = a.format;
        vi.width = (uint32_t) w;
        vi.height = (uint32_t) h;
        vi.faceCount = 1;
        vi.arraySize = 1;
        vi.mipCount = 1;
        vi.sampleCount = 1;
        *surface = nullptr;
        XrResult r = create(session, &vi, out, surface);
        LOGI("surface swapchain %dx%d fb=%d usage=%d format=0x%llx -> %d", w, h, (int) a.fb,
             (int) a.usage, (long long) a.format, (int) r);
        if (XR_SUCCEEDED(r) && *surface != nullptr) return true;
    }
    return false;
}

void renderLoop() {
    JNIEnv *env = attach();
    std::string error;

    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction selectAction = XR_NULL_HANDLE, backAction = XR_NULL_HANDLE,
            stickAction = XR_NULL_HANDLE, gripAction = XR_NULL_HANDLE,
            stickClickAction = XR_NULL_HANDLE;
    std::vector<XrViewConfigurationView> configViews;
    std::vector<XrSwapchain> swapchains;
    std::vector<std::vector<XrSwapchainImageOpenGLESKHR>> images;
    std::vector<std::vector<GLuint>> framebuffers;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface pbuffer = EGL_NO_SURFACE;
    EGLConfig config = nullptr;
    GLuint program = 0, videoTex = 0, vbo = 0, ibo = 0, vao = 0;
    bool sessionRunning = false;
    GLuint labelProgram = 0, labelTex = 0, labelVao = 0, labelVbo = 0;
    float labelAspect = 4.f;
    // controls overlay (title, progress, view name, button hints): shown on start and after
    // any button, A/X shows it on demand; refreshed twice a second while visible
    int64_t overlayUntilNs = 0;
    int64_t overlayUploadedNs = 0;
    bool labelPending = true;
    GLuint panelProgram = 0, chatTex = 0;
    // captions: a video-shaped transparent picture, uploaded only when it changes
    GLuint captionTex = 0;
    int captionVersion = -1;
    bool hasCaption = false;
    float captionAspect = 16.f / 9.f;
    // heavy environments are drawn into a cube map, one face per frame, and looked up
    GLuint bakeProgram = 0, envCube = 0, envFbo = 0;
    int bakedEnv = -1, bakeFace = 0;
    float chatAspect = 0.7f;
    bool hasChat = false;
    // Shorts panels (pressed with the pointers) and the pointers themselves
    GLuint shortsTex = 0, commentsTex = 0, solidProgram = 0, solidVao = 0, solidVbo = 0;
    bool hasShortsPanel = false, hasCommentsPanel = false;
    float shortsAspect = 0.57f, commentsAspect = 0.76f;
    int panelsVersion = -1;
    Panel shortsPanelGeo, commentsPanelGeo;
    XrAction selectLeftAction = XR_NULL_HANDLE, rightAimAction = XR_NULL_HANDLE;
    XrSpace rightAimSpace = XR_NULL_HANDLE;
    bool prevSelectLeft = false;
    float headPos[3] = {0, 0, 0};
    struct Pointer {
        bool valid = false;
        float o[3] = {0, 0, 0}, d[3] = {0, 0, -1};
        bool hits = false;
        int panel = 0;            // 1 shorts, 2 comments
        float hit[3] = {0, 0, 0};
        float u = 0, v = 0, dist = 3;
    } pointers[2];                // 0 left, 1 right
    int64_t chatCheckNs = 0;
    // chat panel: where (direction from the viewer), how big, shown or not, scroll position
    float chatX = 2.75f, chatY = 0.05f, chatZ = -2.4f, chatScale = 1.0f;
    bool chatVisible = true;
    int chatScroll = 0;            // messages scrolled up from the newest
    bool chatDirty = true;         // re-render now (scrolled)
    bool chatGrabbing = false;
    int64_t chatScrollRepeatNs = 0;
    XrAction stickLeftAction = XR_NULL_HANDLE, chatBottomAction = XR_NULL_HANDLE,
            chatToggleAction = XR_NULL_HANDLE, chatGrabAction = XR_NULL_HANDLE,
            leftAimAction = XR_NULL_HANDLE;
    XrSpace leftAimSpace = XR_NULL_HANDLE;
    bool prevChatBottom = false, prevChatToggle = false;
    // passthrough theater (XR_FB_passthrough: Quest, newer Pico): the screen floats in the room
    bool hasPassthroughExt = false, passthroughOn = false, prevPassthroughBtn = false;
    int environment = 0;  // ENV_*
    XrPassthroughFB passthrough = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer = XR_NULL_HANDLE;
    PFN_xrCreatePassthroughFB pCreatePassthrough = nullptr;
    PFN_xrDestroyPassthroughFB pDestroyPassthrough = nullptr;
    PFN_xrPassthroughStartFB pPassthroughStart = nullptr;
    PFN_xrPassthroughPauseFB pPassthroughPause = nullptr;
    PFN_xrCreatePassthroughLayerFB pCreatePassthroughLayer = nullptr;
    PFN_xrDestroyPassthroughLayerFB pDestroyPassthroughLayer = nullptr;
    PFN_xrPassthroughLayerResumeFB pPassthroughLayerResume = nullptr;
    PFN_xrPassthroughLayerPauseFB pPassthroughLayerPause = nullptr;
    XrCompositionLayerPassthroughFB passthroughComp{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
    XrAction passthroughAction = XR_NULL_HANDLE;
    XrAction infoAction = XR_NULL_HANDLE;
    bool prevInfo = false;
    // best path (Quest): the decoder writes into a swapchain that the compositor itself maps
    // around the viewer (equirect layer), so the video is resampled only once
    bool useEquirectLayer = false;
    XrSwapchain videoSwapchain = XR_NULL_HANDLE;
    int videoW = 0, videoH = 0;
    int64_t lastSizeCheckNs = 0;
    PFN_xrCreateSwapchainAndroidSurfaceKHR createSurfaceSwapchain = nullptr;
    bool hasFbSurfaceCreate = false;
    bool hasLayerSettings = false;
    XrCompositionLayerSettingsFB layerSettings{XR_TYPE_COMPOSITION_LAYER_SETTINGS_FB};
    int glVideoW = 3840, glVideoH = 2160;
    // until the user picks a layout (thumbstick click), it follows the video's shape:
    // a square-ish frame is a top-bottom 3D video, a 2:1 frame is mono
    bool layoutAuto = true;
    int64_t glSizeCheckNs = 0;
    std::vector<XrExtensionProperties> availableExtensions;
    float yawOffset = 0;
    bool recenterRequested = false;
    bool prevSelect = false, prevBack = false, prevGrip = false, prevStickClick = false;
    int stickState = 0;
    int stickStateY = 0;
    float texMatrix[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    jfloatArray jTexMatrix = env->NewFloatArray(16);
    XrSwapchainCreateInfo swapInfo{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    int64_t chosenFormat = 0;
    uint32_t formatCount = 0, viewCount = 0;
    std::vector<int64_t> formats;
    XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
    XrSessionActionSetsAttachInfo attachInfo{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};

    // ---- loader & instance ----
    {
        PFN_xrInitializeLoaderKHR initLoader = nullptr;
        xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                              (PFN_xrVoidFunction *) &initLoader);
        if (initLoader) {
            XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
            loaderInfo.applicationVM = gBridge.vm;
            loaderInfo.applicationContext = gBridge.activity;
            initLoader((XrLoaderInitInfoBaseHeaderKHR *) &loaderInfo);
        }
        uint32_t extCount = 0;
        xrEnumerateInstanceExtensionProperties(nullptr, 0, &extCount, nullptr);
        availableExtensions.resize(extCount, {XR_TYPE_EXTENSION_PROPERTIES});
        xrEnumerateInstanceExtensionProperties(nullptr, extCount, &extCount,
                                               availableExtensions.data());
        std::vector<const char *> extensions = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
                                                XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
        // disabled: Quest 3 (v2026) rejects every android-surface swapchain setup with
        // XR_ERROR_VALIDATION_FAILURE, so the GL path is used everywhere
        useEquirectLayer = false &&
                hasExtension(availableExtensions, XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME) &&
                hasExtension(availableExtensions, XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME);
        if (useEquirectLayer) {
            extensions.push_back(XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME);
            extensions.push_back(XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME);
            if (hasExtension(availableExtensions,
                             XR_FB_ANDROID_SURFACE_SWAPCHAIN_CREATE_EXTENSION_NAME)) {
                extensions.push_back(XR_FB_ANDROID_SURFACE_SWAPCHAIN_CREATE_EXTENSION_NAME);
                hasFbSurfaceCreate = true;
            }
        }
        LOGI("equirect layer path: %s", useEquirectLayer ? "yes" : "no (GL fallback)");
        if (hasExtension(availableExtensions, XR_FB_COMPOSITION_LAYER_SETTINGS_EXTENSION_NAME)) {
            extensions.push_back(XR_FB_COMPOSITION_LAYER_SETTINGS_EXTENSION_NAME);
            hasLayerSettings = true;
        }
        LOGI("compositor sharpening: %s", hasLayerSettings ? "yes" : "no");
        if (hasExtension(availableExtensions, XR_FB_PASSTHROUGH_EXTENSION_NAME)) {
            extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
            hasPassthroughExt = true;
        }
        LOGI("passthrough: %s", hasPassthroughExt ? "yes" : "no");
        XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        androidInfo.applicationVM = gBridge.vm;
        androidInfo.applicationActivity = gBridge.activity;
        XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};
        info.next = &androidInfo;
        strcpy(info.applicationInfo.applicationName, "PipeVR");
        strcpy(info.applicationInfo.engineName, "PipeVR");
        info.applicationInfo.applicationVersion = 1;
        info.applicationInfo.apiVersion = XR_API_VERSION_1_0;
        info.enabledExtensionCount = (uint32_t) extensions.size();
        info.enabledExtensionNames = extensions.data();
        XR_CHECK(xrCreateInstance(&info, &instance));
    }
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XR_CHECK(xrGetSystem(instance, &systemInfo, &systemId));
    xrGetInstanceProcAddr(instance, "xrGetOpenGLESGraphicsRequirementsKHR",
                          (PFN_xrVoidFunction *) &getRequirements);
    if (getRequirements) getRequirements(instance, systemId, &requirements);

    // ---- EGL ----
    {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglInitialize(display, nullptr, nullptr);
        const EGLint attribs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                                  EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                                  EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
        EGLint numConfigs = 0;
        eglChooseConfig(display, attribs, &config, 1, &numConfigs);
        const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
        const EGLint pbAttribs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
        pbuffer = eglCreatePbufferSurface(display, config, pbAttribs);
        if (context == EGL_NO_CONTEXT || !eglMakeCurrent(display, pbuffer, pbuffer, context)) {
            error = "Could not create the OpenGL context";
            goto cleanup;
        }
    }

    // ---- session & space ----
    binding.display = display;
    binding.config = config;
    binding.context = context;
    sessionInfo.next = &binding;
    sessionInfo.systemId = systemId;
    XR_CHECK(xrCreateSession(instance, &sessionInfo, &session));
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1;
    XR_CHECK(xrCreateReferenceSpace(session, &spaceInfo, &space));
    if (hasPassthroughExt) {
        auto get = [&](const char *name, PFN_xrVoidFunction *fn) {
            xrGetInstanceProcAddr(instance, name, fn);
        };
        get("xrCreatePassthroughFB", (PFN_xrVoidFunction *) &pCreatePassthrough);
        get("xrDestroyPassthroughFB", (PFN_xrVoidFunction *) &pDestroyPassthrough);
        get("xrPassthroughStartFB", (PFN_xrVoidFunction *) &pPassthroughStart);
        get("xrPassthroughPauseFB", (PFN_xrVoidFunction *) &pPassthroughPause);
        get("xrCreatePassthroughLayerFB", (PFN_xrVoidFunction *) &pCreatePassthroughLayer);
        get("xrDestroyPassthroughLayerFB", (PFN_xrVoidFunction *) &pDestroyPassthroughLayer);
        get("xrPassthroughLayerResumeFB", (PFN_xrVoidFunction *) &pPassthroughLayerResume);
        get("xrPassthroughLayerPauseFB", (PFN_xrVoidFunction *) &pPassthroughLayerPause);
        XrPassthroughCreateInfoFB pci{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
        XrPassthroughLayerCreateInfoFB lci{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
        if (pCreatePassthrough && pCreatePassthroughLayer &&
            XR_SUCCEEDED(pCreatePassthrough(session, &pci, &passthrough))) {
            lci.passthrough = passthrough;
            lci.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
            if (XR_FAILED(pCreatePassthroughLayer(session, &lci, &passthroughLayer))) {
                passthroughLayer = XR_NULL_HANDLE;
            }
        } else {
            passthrough = XR_NULL_HANDLE;
        }
        if (passthroughLayer == XR_NULL_HANDLE) hasPassthroughExt = false;
        LOGI("passthrough layer: %s", hasPassthroughExt ? "ready" : "unavailable");
        passthroughComp.layerHandle = passthroughLayer;
        passthroughComp.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
    }
    {
        environment = env->CallIntMethod(gBridge.activity, gBridge.getEnvironment);
        if (environment < 0 || environment > ENV_UNDERWATER || environment == 3) {
            environment = ENV_DARK;
        }
        if (environment == ENV_PASSTHROUGH) {
            if (hasPassthroughExt) {
                passthroughOn = XR_SUCCEEDED(pPassthroughStart(passthrough)) &&
                        XR_SUCCEEDED(pPassthroughLayerResume(passthroughLayer));
            }
            if (!passthroughOn) environment = ENV_DARK;
        }
    }

    // ---- swapchains (one per eye) ----
    XR_CHECK(xrEnumerateViewConfigurationViews(instance, systemId,
            XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &viewCount, nullptr));
    configViews.resize(viewCount, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
    XR_CHECK(xrEnumerateViewConfigurationViews(instance, systemId,
            XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, viewCount, &viewCount, configViews.data()));
    XR_CHECK(xrEnumerateSwapchainFormats(session, 0, &formatCount, nullptr));
    formats.resize(formatCount);
    XR_CHECK(xrEnumerateSwapchainFormats(session, formatCount, &formatCount, formats.data()));
    chosenFormat = formats.empty() ? GL_RGBA8 : formats[0];
    for (int64_t f : formats) {
        if (f == GL_SRGB8_ALPHA8) { chosenFormat = f; break; }
    }
    for (uint32_t i = 0; i < viewCount; i++) {
        swapInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        swapInfo.format = chosenFormat;
        swapInfo.sampleCount = 1;
        // render 1.4x the recommended size (sharper video), within the runtime's limits
        configViews[i].recommendedImageRectWidth = std::min(
                (uint32_t) (configViews[i].recommendedImageRectWidth * 1.4f),
                configViews[i].maxImageRectWidth);
        configViews[i].recommendedImageRectHeight = std::min(
                (uint32_t) (configViews[i].recommendedImageRectHeight * 1.4f),
                configViews[i].maxImageRectHeight);
        swapInfo.width = configViews[i].recommendedImageRectWidth;
        swapInfo.height = configViews[i].recommendedImageRectHeight;
        swapInfo.faceCount = 1;
        swapInfo.arraySize = 1;
        swapInfo.mipCount = 1;
        XrSwapchain sc;
        XR_CHECK(xrCreateSwapchain(session, &swapInfo, &sc));
        swapchains.push_back(sc);
        uint32_t imageCount = 0;
        xrEnumerateSwapchainImages(sc, 0, &imageCount, nullptr);
        std::vector<XrSwapchainImageOpenGLESKHR> imgs(imageCount,
                {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(sc, imageCount, &imageCount,
                                   (XrSwapchainImageBaseHeader *) imgs.data());
        std::vector<GLuint> fbs(imageCount);
        glGenFramebuffers((GLsizei) imageCount, fbs.data());
        for (uint32_t k = 0; k < imageCount; k++) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbs[k]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                   imgs[k].image, 0);
        }
        images.push_back(imgs);
        framebuffers.push_back(fbs);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    LOGI("swapchains: %u views, %ux%u, format 0x%llx", viewCount,
         configViews[0].recommendedImageRectWidth, configViews[0].recommendedImageRectHeight,
         (long long) chosenFormat);

    // ---- actions ----
    strcpy(setInfo.actionSetName, "pipevr");
    strcpy(setInfo.localizedActionSetName, "PipeVR");
    XR_CHECK(xrCreateActionSet(instance, &setInfo, &actionSet));
    {
        auto make = [&](XrAction *a, const char *name, const char *label, XrActionType type) {
            XrActionCreateInfo ai{XR_TYPE_ACTION_CREATE_INFO};
            strcpy(ai.actionName, name);
            strcpy(ai.localizedActionName, label);
            ai.actionType = type;
            return xrCreateAction(actionSet, &ai, a);
        };
        XR_CHECK(make(&selectAction, "play_pause", "Play / pause", XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&backAction, "exit", "Exit", XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&stickAction, "seek", "Seek", XR_ACTION_TYPE_VECTOR2F_INPUT));
        XR_CHECK(make(&gripAction, "recenter", "Recenter", XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&stickClickAction, "layout", "Video layout", XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&infoAction, "info", "Show controls", XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&stickLeftAction, "chat_scroll", "Scroll chat",
                      XR_ACTION_TYPE_VECTOR2F_INPUT));
        XR_CHECK(make(&chatBottomAction, "chat_bottom", "Newest chat",
                      XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&chatToggleAction, "chat_toggle", "Show or hide chat",
                      XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&chatGrabAction, "chat_move", "Move chat", XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&leftAimAction, "left_aim", "Left hand aim", XR_ACTION_TYPE_POSE_INPUT));
        XR_CHECK(make(&rightAimAction, "right_aim", "Right hand aim", XR_ACTION_TYPE_POSE_INPUT));
        XR_CHECK(make(&selectLeftAction, "select_left", "Left trigger",
                      XR_ACTION_TYPE_BOOLEAN_INPUT));
        XR_CHECK(make(&passthroughAction, "passthrough", "Show the room",
                      XR_ACTION_TYPE_BOOLEAN_INPUT));
    }
    for (const char *profile : {"/interaction_profiles/oculus/touch_controller",
                                "/interaction_profiles/bytedance/pico4_controller"}) {
        suggest(instance, profile, {
                {selectAction, "/user/hand/right/input/trigger/value"},
                {selectLeftAction, "/user/hand/left/input/trigger/value"},
                {rightAimAction, "/user/hand/right/input/aim/pose"},
                {backAction, "/user/hand/right/input/b/click"},
                {passthroughAction, "/user/hand/left/input/y/click"},
                {stickAction, "/user/hand/right/input/thumbstick"},
                {stickLeftAction, "/user/hand/left/input/thumbstick"},
                {gripAction, "/user/hand/right/input/squeeze/value"},
                {chatGrabAction, "/user/hand/left/input/squeeze/value"},
                {stickClickAction, "/user/hand/right/input/thumbstick/click"},
                {chatBottomAction, "/user/hand/left/input/thumbstick/click"},
                {infoAction, "/user/hand/right/input/a/click"},
                {chatToggleAction, "/user/hand/left/input/x/click"},
                {leftAimAction, "/user/hand/left/input/aim/pose"}});
    }
    suggest(instance, "/interaction_profiles/khr/simple_controller", {
            {selectAction, "/user/hand/right/input/select/click"},
            {selectAction, "/user/hand/left/input/select/click"},
            {backAction, "/user/hand/right/input/menu/click"},
            {backAction, "/user/hand/left/input/menu/click"}});
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &actionSet;
    XR_CHECK(xrAttachSessionActionSets(session, &attachInfo));
    {
        XrActionSpaceCreateInfo asi{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        asi.action = leftAimAction;
        asi.poseInActionSpace.orientation.w = 1;
        xrCreateActionSpace(session, &asi, &leftAimSpace);
        asi.action = rightAimAction;
        xrCreateActionSpace(session, &asi, &rightAimSpace);
    }
    {
        // where the chat panel was left last time
        auto arr = (jfloatArray) env->CallObjectMethod(gBridge.activity, gBridge.chatLayout);
        if (arr != nullptr) {
            jfloat v[5];
            env->GetFloatArrayRegion(arr, 0, 5, v);
            env->DeleteLocalRef(arr);
            if (v[3] > 0) {
                chatX = v[0];
                chatY = v[1];
                chatZ = v[2];
                chatScale = v[3];
                chatVisible = v[4] > 0.5f;
            }
        }
    }

    // ---- GL resources ----
    {
        program = glCreateProgram();
        glAttachShader(program, compile(GL_VERTEX_SHADER, kVertexShader));
        glAttachShader(program, compile(GL_FRAGMENT_SHADER, kFragmentShader));
        glLinkProgram(program);
        // a cube around the viewer: the shader maps each pixel's direction to the video
        const float s = 10.f;
        const float verts[] = {-s, -s, -s, s, -s, -s, s, s, -s, -s, s, -s,
                               -s, -s, s, s, -s, s, s, s, s, -s, s, s};
        const unsigned short idx[] = {0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1,
                                      3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2};
        glGenVertexArrays(1, &vao);
        glBindVertexArray(vao);
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
        glGenBuffers(1, &ibo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(idx), idx, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        glBindVertexArray(0);

        if (useEquirectLayer) {
            xrGetInstanceProcAddr(instance, "xrCreateSwapchainAndroidSurfaceKHR",
                                  (PFN_xrVoidFunction *) &createSurfaceSwapchain);
            queryVideoSize(env, videoW, videoH);
            jobject surface = nullptr;
            if (createSurfaceSwapchain == nullptr ||
                !createVideoSwapchain(createSurfaceSwapchain, session, hasFbSurfaceCreate,
                                      videoW, videoH, &videoSwapchain, &surface)) {
                LOGE("android surface swapchain failed, using the GL path");
                useEquirectLayer = false;
                videoSwapchain = XR_NULL_HANDLE;
            } else {
                LOGI("video surface swapchain %dx%d", videoW, videoH);
                env->CallVoidMethod(gBridge.activity, gBridge.onSurface, surface);
                env->DeleteLocalRef(surface);
            }
        }
        // environment cube map
        bakeProgram = glCreateProgram();
        glAttachShader(bakeProgram, compile(GL_VERTEX_SHADER, kBakeVertex));
        glAttachShader(bakeProgram, compile(GL_FRAGMENT_SHADER, kFragmentShader));
        glLinkProgram(bakeProgram);
        glGenTextures(1, &envCube);
        glBindTexture(GL_TEXTURE_CUBE_MAP, envCube);
        glTexStorage2D(GL_TEXTURE_CUBE_MAP, 1, GL_SRGB8_ALPHA8, kCubeSize, kCubeSize);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
        glGenFramebuffers(1, &envFbo);
        // layout name popup
        labelProgram = glCreateProgram();
        glAttachShader(labelProgram, compile(GL_VERTEX_SHADER, kLabelVertex));
        glAttachShader(labelProgram, compile(GL_FRAGMENT_SHADER, kLabelFragment));
        glLinkProgram(labelProgram);
        const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
        glGenVertexArrays(1, &labelVao);
        glBindVertexArray(labelVao);
        glGenBuffers(1, &labelVbo);
        glBindBuffer(GL_ARRAY_BUFFER, labelVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glBindVertexArray(0);
        panelProgram = glCreateProgram();
        glAttachShader(panelProgram, compile(GL_VERTEX_SHADER, kPanelVertex));
        glAttachShader(panelProgram, compile(GL_FRAGMENT_SHADER, kLabelFragment));
        glLinkProgram(panelProgram);
        glGenTextures(1, &chatTex);
        glBindTexture(GL_TEXTURE_2D, chatTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // panels pressed with the pointers, and the pointer shapes
        for (GLuint *t : {&shortsTex, &commentsTex}) {
            glGenTextures(1, t);
            glBindTexture(GL_TEXTURE_2D, *t);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        solidProgram = glCreateProgram();
        glAttachShader(solidProgram, compile(GL_VERTEX_SHADER, kSolidVertex));
        glAttachShader(solidProgram, compile(GL_FRAGMENT_SHADER, kSolidFragment));
        glLinkProgram(solidProgram);
        glGenVertexArrays(1, &solidVao);
        glBindVertexArray(solidVao);
        glGenBuffers(1, &solidVbo);
        glBindBuffer(GL_ARRAY_BUFFER, solidVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(float) * 12, nullptr, GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        glBindVertexArray(0);
        glGenTextures(1, &captionTex);
        glBindTexture(GL_TEXTURE_2D, captionTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenTextures(1, &labelTex);
        glBindTexture(GL_TEXTURE_2D, labelTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glGenTextures(1, &videoTex);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTex);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (!useEquirectLayer) {
            env->CallVoidMethod(gBridge.activity, gBridge.createSurface, (jint) videoTex);
        }
    }

    // ---- frame loop ----
    while (gRunning.load()) {
        XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
        while (xrPollEvent(instance, &event) == XR_SUCCESS) {
            if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                auto *e = (XrEventDataSessionStateChanged *) &event;
                LOGI("session state %d", (int) e->state);
                if (e->state == XR_SESSION_STATE_READY) {
                    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    if (XR_SUCCEEDED(xrBeginSession(session, &bi))) sessionRunning = true;
                } else if (e->state == XR_SESSION_STATE_STOPPING) {
                    xrEndSession(session);
                    sessionRunning = false;
                } else if (e->state == XR_SESSION_STATE_EXITING ||
                           e->state == XR_SESSION_STATE_LOSS_PENDING) {
                    gRunning = false;
                }
            } else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
                gRunning = false;
            }
            event = {XR_TYPE_EVENT_DATA_BUFFER};
        }
        if (!sessionRunning) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        XrFrameState frameState{XR_TYPE_FRAME_STATE};
        if (XR_FAILED(xrWaitFrame(session, nullptr, &frameState))) continue;
        xrBeginFrame(session, nullptr);

        // input
        {
            XrActiveActionSet active{actionSet, XR_NULL_PATH};
            XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
            sync.countActiveActionSets = 1;
            sync.activeActionSets = &active;
            xrSyncActions(session, &sync);
            auto boolState = [&](XrAction a) {
                XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
                gi.action = a;
                XrActionStateBoolean st{XR_TYPE_ACTION_STATE_BOOLEAN};
                xrGetActionStateBoolean(session, &gi, &st);
                return st.isActive && st.currentState;
            };
            const bool select = boolState(selectAction);
            const bool back = boolState(backAction);
            const bool grip = boolState(gripAction);
            const bool stickClick = boolState(stickClickAction);
            const bool info = boolState(infoAction);
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.action = stickAction;
            XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
            xrGetActionStateVector2f(session, &gi, &stick);

            bool anyPress = (info && !prevInfo) || (select && !prevSelect) ||
                            (stickClick && !prevStickClick) || (grip && !prevGrip);
            prevInfo = info;

            // ---- environment (Y): dark room -> theater -> space -> passthrough ----
            const bool ptBtn = boolState(passthroughAction);
            if (ptBtn && !prevPassthroughBtn) {
                int next = nextEnvironment(environment);
                if (next == ENV_PASSTHROUGH) {
                    passthroughOn = hasPassthroughExt &&
                            XR_SUCCEEDED(pPassthroughStart(passthrough)) &&
                            XR_SUCCEEDED(pPassthroughLayerResume(passthroughLayer));
                    if (!passthroughOn) {
                        sendAction(env, ACTION_NOTE, -1);   // no passthrough on this headset
                        next = ENV_DARK;
                    }
                } else if (passthroughOn) {
                    pPassthroughLayerPause(passthroughLayer);
                    pPassthroughPause(passthrough);
                    passthroughOn = false;
                }
                environment = next;
                sendAction(env, ACTION_NOTE, environment);
                overlayUntilNs = frameState.predictedDisplayTime + 4000000000LL;
                labelPending = true;
                env->CallVoidMethod(gBridge.activity, gBridge.saveEnvironment, (jint) environment);
            }
            prevPassthroughBtn = ptBtn;

            // ---- chat (left hand) ----
            const bool chatBottom = boolState(chatBottomAction);
            const bool chatToggle = boolState(chatToggleAction);
            const bool grab = boolState(chatGrabAction);
            XrActionStateGetInfo lgi{XR_TYPE_ACTION_STATE_GET_INFO};
            lgi.action = stickLeftAction;
            XrActionStateVector2f lstick{XR_TYPE_ACTION_STATE_VECTOR2F};
            xrGetActionStateVector2f(session, &lgi, &lstick);
            const float ly = lstick.isActive ? lstick.currentState.y : 0.f;
            if (chatToggle && !prevChatToggle) {
                chatVisible = !chatVisible;
                env->CallVoidMethod(gBridge.activity, gBridge.saveChatLayout, chatX, chatY,
                                    chatZ, chatScale, (jboolean) chatVisible);
            }
            if (chatBottom && !prevChatBottom && chatScroll != 0) {
                chatScroll = 0;
                chatDirty = true;
            }
            prevChatToggle = chatToggle;
            prevChatBottom = chatBottom;
            if (grab) {
                // move the panel to where the left controller points; stick resizes it
                XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
                if (leftAimSpace != XR_NULL_HANDLE && XR_SUCCEEDED(xrLocateSpace(
                        leftAimSpace, space, frameState.predictedDisplayTime, &loc)) &&
                    (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
                    const XrQuaternionf &q = loc.pose.orientation;
                    // controller forward (-Z) in the world, then undo the recenter yaw
                    float fx = -2 * (q.x * q.z + q.w * q.y);
                    float fy = -2 * (q.y * q.z - q.w * q.x);
                    float fz = -(1 - 2 * (q.x * q.x + q.y * q.y));
                    const float c = cosf(yawOffset), sn = sinf(yawOffset);
                    const float wx = c * fx - sn * fz, wz = sn * fx + c * fz;
                    const float dist = 3.2f;
                    chatX = wx * dist;
                    chatY = fy * dist;
                    chatZ = wz * dist;
                }
                if (fabsf(ly) > 0.2f) {
                    chatScale = std::max(0.5f, std::min(2.5f, chatScale * (1 + ly * 0.02f)));
                }
                chatGrabbing = true;
            } else {
                if (chatGrabbing) {
                    chatGrabbing = false;
                    env->CallVoidMethod(gBridge.activity, gBridge.saveChatLayout, chatX, chatY,
                                        chatZ, chatScale, (jboolean) chatVisible);
                }
                // scroll: push up for older messages, repeating while held
                if (fabsf(ly) > 0.6f && frameState.predictedDisplayTime > chatScrollRepeatNs) {
                    chatScroll = std::max(0, chatScroll + (ly > 0 ? 1 : -1));
                    chatDirty = true;
                    chatScrollRepeatNs = frameState.predictedDisplayTime +
                            (fabsf(ly) > 0.95f ? 80000000LL : 180000000LL);
                }
            }
            // ---- pointers: rays from both controllers onto the Shorts panels ----
            const bool selectLeft = boolState(selectLeftAction);
            {
                const float cy = cosf(yawOffset), sy = sinf(yawOffset);
                XrSpace aims[2] = {leftAimSpace, rightAimSpace};
                for (int h = 0; h < 2; h++) {
                    Pointer &pt = pointers[h];
                    pt.valid = false;
                    pt.hits = false;
                    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
                    if (aims[h] == XR_NULL_HANDLE || XR_FAILED(xrLocateSpace(aims[h], space,
                            frameState.predictedDisplayTime, &loc))) continue;
                    if (!(loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) ||
                        !(loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT)) continue;
                    const XrQuaternionf &q = loc.pose.orientation;
                    const float fx = -2 * (q.x * q.z + q.w * q.y);
                    const float fy = -2 * (q.y * q.z - q.w * q.x);
                    const float fz = -(1 - 2 * (q.x * q.x + q.y * q.y));
                    const float px = loc.pose.position.x - headPos[0];
                    const float py = loc.pose.position.y - headPos[1];
                    const float pz = loc.pose.position.z - headPos[2];
                    // into the (recentred) room the panels live in
                    pt.o[0] = cy * px - sy * pz; pt.o[1] = py; pt.o[2] = sy * px + cy * pz;
                    pt.d[0] = cy * fx - sy * fz; pt.d[1] = fy; pt.d[2] = sy * fx + cy * fz;
                    pt.valid = true;
                    pt.dist = 3.0f;
                    float hit[3], u, v, dist;
                    if (hasShortsPanel && hitPanel(shortsPanelGeo, pt.o, pt.d, hit, u, v, dist)
                        && dist < pt.dist + 10) {
                        pt.hits = true; pt.panel = 1; pt.u = u; pt.v = v; pt.dist = dist;
                        memcpy(pt.hit, hit, sizeof(hit));
                    }
                    if (hasCommentsPanel && hitPanel(commentsPanelGeo, pt.o, pt.d, hit, u, v,
                            dist) && (!pt.hits || dist < pt.dist)) {
                        pt.hits = true; pt.panel = 2; pt.u = u; pt.v = v; pt.dist = dist;
                        memcpy(pt.hit, hit, sizeof(hit));
                    }
                }
            }
            // trigger: press what the pointer is on, otherwise play/pause
            {
                const bool pressed[2] = {selectLeft && !prevSelectLeft, select && !prevSelect};
                bool clicked = false;
                bool toggle = false;
                for (int h = 0; h < 2; h++) {
                    if (!pressed[h]) continue;
                    if (pointers[h].hits) {
                        env->CallVoidMethod(gBridge.activity, gBridge.panelClick,
                                            (jint) pointers[h].panel, (jfloat) pointers[h].u,
                                            (jfloat) pointers[h].v);
                        clicked = true;
                    } else {
                        toggle = true;
                    }
                }
                if (toggle && !clicked) sendAction(env, ACTION_PLAY_PAUSE, 0);
                prevSelectLeft = selectLeft;
            }
            if (back && !prevBack) sendAction(env, ACTION_EXIT, 0);
            if (stickClick && !prevStickClick) {
                layoutAuto = false;
                int next = (gLayout.load() + 1) % LAYOUT_COUNT;
                gLayout = next;
                sendAction(env, ACTION_LAYOUT, next);
                labelPending = true;
            }
            const float sx = stick.isActive ? stick.currentState.x : 0.f;
            const int newStick = sx > 0.7f ? 1 : (sx < -0.7f ? -1 : 0);
            if (newStick != 0 && stickState == 0) {
                sendAction(env, ACTION_SEEK, newStick * 10);
                anyPress = true;
            }
            if (anyPress) {
                overlayUntilNs = frameState.predictedDisplayTime + 4000000000LL;
                labelPending = true;
            }
            stickState = (fabsf(sx) < 0.3f) ? 0 : (newStick != 0 ? newStick : stickState);
            // up/down: previous/next video of the queue (e.g. the next short)
            const float sy = stick.isActive ? stick.currentState.y : 0.f;
            const int newStickY = sy > 0.7f ? 1 : (sy < -0.7f ? -1 : 0);
            if (newStickY != 0 && stickStateY == 0 && fabsf(sx) < 0.5f) {
                sendAction(env, ACTION_SKIP, newStickY > 0 ? -1 : 1);
                anyPress = true;
            }
            stickStateY = (fabsf(sy) < 0.3f) ? 0 : (newStickY != 0 ? newStickY : stickStateY);
            prevSelect = select;
            prevBack = back;
            prevStickClick = stickClick;
            if (grip && !prevGrip) recenterRequested = true;  // recenter on the head's yaw
            prevGrip = grip;
        }

        if (useEquirectLayer) {
            // follow quality changes: the swapchain must match the video size
            if (frameState.predictedDisplayTime - lastSizeCheckNs > 1000000000LL) {
                lastSizeCheckNs = frameState.predictedDisplayTime;
                int w, h;
                queryVideoSize(env, w, h);
                if (w != videoW || h != videoH) {
                    XrSwapchain newChain = XR_NULL_HANDLE;
                    jobject surface = nullptr;
                    if (createVideoSwapchain(createSurfaceSwapchain, session, hasFbSurfaceCreate,
                                             w, h, &newChain, &surface)) {
                        env->CallVoidMethod(gBridge.activity, gBridge.onSurface, surface);
                        env->DeleteLocalRef(surface);
                        xrDestroySwapchain(videoSwapchain);
                        videoSwapchain = newChain;
                        videoW = w;
                        videoH = h;
                        LOGI("video surface swapchain resized to %dx%d", w, h);
                    }
                }
            }

            XrCompositionLayerEquirect2KHR eq[2];
            const int lay = gLayout.load();
            const bool is180 = lay >= SIDE_BY_SIDE_180;
            const bool tb = lay == TOP_BOTTOM_360 || lay == TOP_BOTTOM_180;
            const bool sbs = lay == SIDE_BY_SIDE_360 || lay == SIDE_BY_SIDE_180;
            const bool stereo = tb || sbs;
            if (recenterRequested) {
                XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
                // head orientation: locate the view space would need another space; use views
                std::vector<XrView> views(viewCount, {XR_TYPE_VIEW});
                XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
                li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                li.displayTime = frameState.predictedDisplayTime;
                li.space = space;
                XrViewState vs{XR_TYPE_VIEW_STATE};
                uint32_t located = 0;
                xrLocateViews(session, &li, &vs, viewCount, &located, views.data());
                if (located > 0) yawOffset = yawOf(views[0].pose.orientation);
                recenterRequested = false;
                (void) loc;
            }
            for (int eye = 0; eye < (stereo ? 2 : 1); eye++) {
                XrCompositionLayerEquirect2KHR &l = eq[eye];
                l = {XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR};
                l.space = space;
                l.eyeVisibility = !stereo ? XR_EYE_VISIBILITY_BOTH
                        : (eye == 0 ? XR_EYE_VISIBILITY_LEFT : XR_EYE_VISIBILITY_RIGHT);
                l.subImage.swapchain = videoSwapchain;
                int x = 0, y = 0, w = videoW, h = videoH;
                if (tb) { h = videoH / 2; y = eye == 0 ? 0 : videoH / 2; }
                if (sbs) { w = videoW / 2; x = eye == 0 ? 0 : videoW / 2; }
                l.subImage.imageRect.offset = {x, y};
                l.subImage.imageRect.extent = {w, h};
                l.subImage.imageArrayIndex = 0;
                l.pose.orientation = {0, sinf(yawOffset / 2), 0, cosf(yawOffset / 2)};
                l.pose.position = {0, 0, 0};
                l.radius = 0;  // infinitely far away
                l.centralHorizontalAngle = is180 ? (float) M_PI : (float) (2 * M_PI);
                l.upperVerticalAngle = (float) (M_PI / 2);
                l.lowerVerticalAngle = (float) (-M_PI / 2);
            }
            const XrCompositionLayerBaseHeader *eqLayers[2] = {
                    (XrCompositionLayerBaseHeader *) &eq[0],
                    (XrCompositionLayerBaseHeader *) &eq[1]};
            XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};
            end.displayTime = frameState.predictedDisplayTime;
            end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            end.layerCount = frameState.shouldRender ? (stereo ? 2u : 1u) : 0u;
            end.layers = eqLayers;
            xrEndFrame(session, &end);
            continue;
        }

        if (frameState.predictedDisplayTime - glSizeCheckNs > 1000000000LL) {
            glSizeCheckNs = frameState.predictedDisplayTime;
            queryVideoSize(env, glVideoW, glVideoH);
            if (layoutAuto && glVideoW > 0 && gLayout.load() < SCREEN_2D) {
                const float aspect = (float) glVideoW / (float) glVideoH;
                const int current = gLayout.load();
                const bool is180 = current >= SIDE_BY_SIDE_180;
                int wanted = current;
                if (aspect < 1.3f) {
                    wanted = is180 ? TOP_BOTTOM_180 : TOP_BOTTOM_360;  // stacked 3D
                } else if (current == TOP_BOTTOM_360 || current == TOP_BOTTOM_180) {
                    wanted = is180 ? SIDE_BY_SIDE_180 : MONO_360;      // not stacked after all
                }
                if (wanted != current) {
                    LOGI("video %dx%d: layout %d -> %d", glVideoW, glVideoH, current, wanted);
                    gLayout = wanted;
                }
            }
        }
        // newest video frame
        if (env->CallBooleanMethod(gBridge.activity, gBridge.updateTexture, jTexMatrix)) {
            env->GetFloatArrayRegion(jTexMatrix, 0, 16, texMatrix);
        }

        if (overlayUntilNs == 0) {  // first frame: show the controls for a moment
            overlayUntilNs = frameState.predictedDisplayTime + 5000000000LL;
        }
        if (frameState.predictedDisplayTime < overlayUntilNs &&
            frameState.predictedDisplayTime - overlayUploadedNs > 500000000LL) {
            labelPending = true;  // keep the time and progress up to date
        }
        if (labelPending) {
            labelPending = false;
            overlayUploadedNs = frameState.predictedDisplayTime;
            jobject bitmap = env->CallObjectMethod(gBridge.activity, gBridge.label,
                                                   (jint) gLayout.load());
            AndroidBitmapInfo info;
            void *pixels = nullptr;
            if (bitmap != nullptr && AndroidBitmap_getInfo(env, bitmap, &info) == 0 &&
                AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
                glBindTexture(GL_TEXTURE_2D, labelTex);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei) info.width,
                             (GLsizei) info.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                AndroidBitmap_unlockPixels(env, bitmap);
                labelAspect = (float) info.width / (float) info.height;
            }
            if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
        }
        // Shorts panels: re-drawn by Java only when something changed
        {
            const int v = env->CallIntMethod(gBridge.activity, gBridge.panelsVersion);
            if (v != panelsVersion) {
                panelsVersion = v;
                auto upload = [&](jmethodID m, GLuint tex, bool &has, float &aspect) {
                    jobject bitmap = env->CallObjectMethod(gBridge.activity, m);
                    AndroidBitmapInfo info;
                    void *pixels = nullptr;
                    has = false;
                    if (bitmap != nullptr && AndroidBitmap_getInfo(env, bitmap, &info) == 0 &&
                        AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
                        glBindTexture(GL_TEXTURE_2D, tex);
                        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei) info.width,
                                     (GLsizei) info.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                        AndroidBitmap_unlockPixels(env, bitmap);
                        aspect = (float) info.width / (float) info.height;
                        has = true;
                    }
                    if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
                };
                upload(gBridge.shortsPanel, shortsTex, hasShortsPanel, shortsAspect);
                upload(gBridge.commentsPanel, commentsTex, hasCommentsPanel, commentsAspect);
            }
            // beside the tall screen: the Shorts panel right, comments left
            shortsPanelGeo = facingPanel(1.35f, 0.0f, -2.85f, 0.9f, shortsAspect);
            commentsPanelGeo = facingPanel(-1.75f, 0.0f, -2.7f, 1.0f, commentsAspect);
        }
        // captions: check every frame (a cheap call), upload only when they change
        {
            const int v = env->CallIntMethod(gBridge.activity, gBridge.captionVersion);
            if (v != captionVersion) {
                captionVersion = v;
                jobject bitmap = env->CallObjectMethod(gBridge.activity, gBridge.captions);
                AndroidBitmapInfo info;
                void *pixels = nullptr;
                hasCaption = false;
                if (bitmap != nullptr && AndroidBitmap_getInfo(env, bitmap, &info) == 0 &&
                    AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
                    glBindTexture(GL_TEXTURE_2D, captionTex);
                    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei) info.width,
                                 (GLsizei) info.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                    AndroidBitmap_unlockPixels(env, bitmap);
                    captionAspect = (float) info.width / (float) info.height;
                    hasCaption = true;
                }
                if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
            }
        }
        // live chat panel: refreshed once a second
        if (chatVisible && (chatDirty ||
                frameState.predictedDisplayTime - chatCheckNs > 1000000000LL)) {
            chatCheckNs = frameState.predictedDisplayTime;
            chatDirty = false;
            jobject bitmap = env->CallObjectMethod(gBridge.activity, gBridge.chat,
                                                   (jint) chatScroll);
            AndroidBitmapInfo info;
            void *pixels = nullptr;
            hasChat = false;
            if (bitmap != nullptr && AndroidBitmap_getInfo(env, bitmap, &info) == 0 &&
                AndroidBitmap_lockPixels(env, bitmap, &pixels) == 0) {
                glBindTexture(GL_TEXTURE_2D, chatTex);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei) info.width,
                             (GLsizei) info.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                AndroidBitmap_unlockPixels(env, bitmap);
                chatAspect = (float) info.width / (float) info.height;
                hasChat = true;
            }
            if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
        }
        float labelAlpha = 0;
        {
            const float left = (float) (overlayUntilNs - frameState.predictedDisplayTime) / 1e9f;
            labelAlpha = left > 0.6f ? 1.0f : (left > 0 ? left / 0.6f : 0.0f);
        }

        std::vector<XrCompositionLayerProjectionView> projViews(viewCount,
                {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW});
        XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        const XrCompositionLayerBaseHeader *layers[2];
        uint32_t layerCount = 0;

        if (frameState.shouldRender) {
            std::vector<XrView> views(viewCount, {XR_TYPE_VIEW});
            XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
            li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            li.displayTime = frameState.predictedDisplayTime;
            li.space = space;
            XrViewState vs{XR_TYPE_VIEW_STATE};
            uint32_t located = 0;
            xrLocateViews(session, &li, &vs, viewCount, &located, views.data());
            if (located > 0) {
                for (int k = 0; k < 3; k++) headPos[k] = 0;
                for (uint32_t k = 0; k < located; k++) {
                    headPos[0] += views[k].pose.position.x / (float) located;
                    headPos[1] += views[k].pose.position.y / (float) located;
                    headPos[2] += views[k].pose.position.z / (float) located;
                }
            }

            if (recenterRequested && located > 0) {
                yawOffset = yawOf(views[0].pose.orientation);
                recenterRequested = false;
            }
            const float yaw = yawOffset;
            const float envTime = (float) ((frameState.predictedDisplayTime / 1000000LL)
                                           % 3600000LL) / 1000.0f;

            // heavy environments: refresh the cube map (all faces when it changes, else one)
            const bool baked = gLayout.load() >= SCREEN_2D && !passthroughOn &&
                    (environment == ENV_SPACE || environment == ENV_BEACH ||
                     environment == ENV_AURORA || environment == ENV_CITY ||
                     environment == ENV_UNDERWATER);
            if (baked) {
                const int faces = bakedEnv == environment ? 1 : 6;
                bakedEnv = environment;
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_CUBE_MAP, 0);   // not sampled while drawn into
                glActiveTexture(GL_TEXTURE0);
                glBindFramebuffer(GL_FRAMEBUFFER, envFbo);
                glViewport(0, 0, kCubeSize, kCubeSize);
                glDisable(GL_BLEND);
                glUseProgram(bakeProgram);
                glUniform1i(glGetUniformLocation(bakeProgram, "uBake"), 1);
                glUniform1i(glGetUniformLocation(bakeProgram, "uCubeReady"), 0);
                // two different sampler types may not share a unit
                glUniform1i(glGetUniformLocation(bakeProgram, "uTex"), 0);
                glUniform1i(glGetUniformLocation(bakeProgram, "uEnvCube"), 1);
                glUniform1i(glGetUniformLocation(bakeProgram, "uEnv"), environment);
                glUniform1f(glGetUniformLocation(bakeProgram, "uTime"), envTime);
                glUniform1i(glGetUniformLocation(bakeProgram, "uLayout"), gLayout.load());
                glBindVertexArray(labelVao);
                for (int f = 0; f < faces; f++) {
                    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                           GL_TEXTURE_CUBE_MAP_POSITIVE_X + bakeFace, envCube, 0);
                    const float *b = kCubeFaces[bakeFace];
                    glUniform3f(glGetUniformLocation(bakeProgram, "uFwd"), b[0], b[1], b[2]);
                    glUniform3f(glGetUniformLocation(bakeProgram, "uRight"), b[3], b[4], b[5]);
                    glUniform3f(glGetUniformLocation(bakeProgram, "uUp"), b[6], b[7], b[8]);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    bakeFace = (bakeFace + 1) % 6;
                }
                glBindVertexArray(0);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
            } else {
                bakedEnv = -1;
            }

            for (uint32_t i = 0; i < located; i++) {
                XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
                uint32_t imageIndex = 0;
                xrAcquireSwapchainImage(swapchains[i], &ai, &imageIndex);
                XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
                wi.timeout = XR_INFINITE_DURATION;
                xrWaitSwapchainImage(swapchains[i], &wi);

                const int w = (int) configViews[i].recommendedImageRectWidth;
                const int h = (int) configViews[i].recommendedImageRectHeight;
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffers[i][imageIndex]);
                glViewport(0, 0, w, h);
                glDisable(GL_DEPTH_TEST);
                glDisable(GL_CULL_FACE);
                const bool see = passthroughOn && gLayout.load() >= SCREEN_2D;
                glClearColor(0, 0, 0, see ? 0 : 1);
                glClear(GL_COLOR_BUFFER_BIT);

                float proj[16], view[16], vp[16];
                projection(proj, views[i].fov, 0.1f, 100.f);
                viewRotation(view, views[i].pose.orientation, yaw);
                matMul(vp, proj, view);

                glUseProgram(program);
                glUniformMatrix4fv(glGetUniformLocation(program, "uViewProj"), 1, GL_FALSE, vp);
                glUniformMatrix4fv(glGetUniformLocation(program, "uTexMatrix"), 1, GL_FALSE,
                                   texMatrix);
                glUniform1i(glGetUniformLocation(program, "uLayout"), gLayout.load());
                glUniform1i(glGetUniformLocation(program, "uEye"), (int) i);
                glUniform1i(glGetUniformLocation(program, "uEnv"), see ? ENV_PASSTHROUGH :
                        (environment == ENV_PASSTHROUGH ? ENV_DARK : environment));
                glUniform1f(glGetUniformLocation(program, "uTime"), envTime);
                glUniform1i(glGetUniformLocation(program, "uBake"), 0);
                glUniform1i(glGetUniformLocation(program, "uCubeReady"), baked ? 1 : 0);
                glUniform1i(glGetUniformLocation(program, "uEnvCube"), 1);
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_CUBE_MAP, baked ? envCube : 0);
                glActiveTexture(GL_TEXTURE0);
                glUniform2f(glGetUniformLocation(program, "uVideoSize"), (float) glVideoW,
                            (float) glVideoH);
                {
                    // shape of the picture one eye sees ("half" 3D formats are squeezed)
                    float a = glVideoH > 0 ? (float) glVideoW / (float) glVideoH : 16.f / 9.f;
                    const int lay = gLayout.load();
                    if (lay == SCREEN_SIDE_BY_SIDE && a > 2.5f) a /= 2;   // full side-by-side
                    if (lay == SCREEN_TOP_BOTTOM && a < 1.0f) a *= 2;     // full over-under
                    glUniform1f(glGetUniformLocation(program, "uScreenAspect"), a);
                }
                glUniform1i(glGetUniformLocation(program, "uTex"), 0);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTex);
                glBindVertexArray(vao);
                glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_SHORT, nullptr);
                glBindVertexArray(0);

                if (hasCaption) {
                    // screen views: exactly over the screen; 360/180: a picture in front
                    float cx = 0, cy = 0, cz = -2.98f, halfW, halfH;
                    if (gLayout.load() >= SCREEN_2D) {
                        float a = glVideoH > 0 ? (float) glVideoW / (float) glVideoH : 16.f / 9.f;
                        const int lay = gLayout.load();
                        if (lay == SCREEN_SIDE_BY_SIDE && a > 2.5f) a /= 2;
                        if (lay == SCREEN_TOP_BOTTOM && a < 1.0f) a *= 2;
                        halfW = 1.75f;
                        halfH = halfW / a;
                        if (a < 1.0f) {
                            halfH = 1.3f;
                            halfW = halfH * a;
                        }
                        // the caption picture has the video's shape; stretch it onto the screen
                    } else {
                        cy = -0.35f;
                        cz = -2.2f;
                        halfW = 1.1f;
                        halfH = halfW / captionAspect;
                    }
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE,
                                        GL_ONE_MINUS_SRC_ALPHA);
                    glUseProgram(panelProgram);
                    glUniformMatrix4fv(glGetUniformLocation(panelProgram, "uViewProj"), 1,
                                       GL_FALSE, vp);
                    glUniform3f(glGetUniformLocation(panelProgram, "uCenter"), cx, cy, cz);
                    glUniform3f(glGetUniformLocation(panelProgram, "uRight"), halfW, 0, 0);
                    glUniform3f(glGetUniformLocation(panelProgram, "uUp"), 0, halfH, 0);
                    glUniform1f(glGetUniformLocation(panelProgram, "uAlpha"), 1.0f);
                    glUniform1i(glGetUniformLocation(panelProgram, "uTex"), 0);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, captionTex);
                    glBindVertexArray(labelVao);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    glBindVertexArray(0);
                    glDisable(GL_BLEND);
                }

                if (hasChat && chatVisible) {
                    // placed in the room (default: right of the screen), facing the viewer
                    const float cx = chatX, cy = chatY, cz = chatZ;
                    const float len = std::max(0.1f, sqrtf(cx * cx + cz * cz));
                    const float nx = -cx / len, nz = -cz / len;      // towards the viewer
                    const float rx = nz, rz = -nx;                    // up x n
                    const float halfH = 0.85f * chatScale, halfW = halfH * chatAspect;
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE,
                                        GL_ONE_MINUS_SRC_ALPHA);
                    glUseProgram(panelProgram);
                    glUniformMatrix4fv(glGetUniformLocation(panelProgram, "uViewProj"), 1,
                                       GL_FALSE, vp);
                    glUniform3f(glGetUniformLocation(panelProgram, "uCenter"), cx, cy, cz);
                    glUniform3f(glGetUniformLocation(panelProgram, "uRight"), rx * halfW, 0,
                                rz * halfW);
                    glUniform3f(glGetUniformLocation(panelProgram, "uUp"), 0, halfH, 0);
                    glUniform1f(glGetUniformLocation(panelProgram, "uAlpha"), 1.0f);
                    glUniform1i(glGetUniformLocation(panelProgram, "uTex"), 0);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, chatTex);
                    glBindVertexArray(labelVao);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    glBindVertexArray(0);
                    glDisable(GL_BLEND);
                }

                // Shorts panels
                auto drawPanel = [&](const Panel &pg, GLuint tex) {
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE,
                                        GL_ONE_MINUS_SRC_ALPHA);
                    glUseProgram(panelProgram);
                    glUniformMatrix4fv(glGetUniformLocation(panelProgram, "uViewProj"), 1,
                                       GL_FALSE, vp);
                    glUniform3f(glGetUniformLocation(panelProgram, "uCenter"), pg.c[0], pg.c[1],
                                pg.c[2]);
                    glUniform3f(glGetUniformLocation(panelProgram, "uRight"), pg.r[0], pg.r[1],
                                pg.r[2]);
                    glUniform3f(glGetUniformLocation(panelProgram, "uUp"), pg.u[0], pg.u[1],
                                pg.u[2]);
                    glUniform1f(glGetUniformLocation(panelProgram, "uAlpha"), 1.0f);
                    glUniform1i(glGetUniformLocation(panelProgram, "uTex"), 0);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, tex);
                    glBindVertexArray(labelVao);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    glBindVertexArray(0);
                    glDisable(GL_BLEND);
                };
                if (hasShortsPanel) drawPanel(shortsPanelGeo, shortsTex);
                if (hasCommentsPanel) drawPanel(commentsPanelGeo, commentsTex);

                // pointer rays, only while there is something to press
                if (hasShortsPanel || hasCommentsPanel) {
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE,
                                        GL_ONE_MINUS_SRC_ALPHA);
                    glUseProgram(solidProgram);
                    glUniformMatrix4fv(glGetUniformLocation(solidProgram, "uViewProj"), 1,
                                       GL_FALSE, vp);
                    glBindVertexArray(solidVao);
                    glBindBuffer(GL_ARRAY_BUFFER, solidVbo);
                    for (const Pointer &pt : pointers) {
                        if (!pt.valid) continue;
                        const float len = pt.hits ? pt.dist : 2.5f;
                        const float e[3] = {pt.o[0] + pt.d[0] * len, pt.o[1] + pt.d[1] * len,
                                            pt.o[2] + pt.d[2] * len};
                        // sideways for the ray's width: across the ray, roughly level
                        float wx = -pt.d[2], wz = pt.d[0];
                        const float wl = std::max(1e-4f, sqrtf(wx * wx + wz * wz));
                        const float hw = 0.0035f;
                        wx = wx / wl * hw;
                        wz = wz / wl * hw;
                        const float quad[12] = {pt.o[0] - wx, pt.o[1], pt.o[2] - wz,
                                                pt.o[0] + wx, pt.o[1], pt.o[2] + wz,
                                                e[0] - wx, e[1], e[2] - wz,
                                                e[0] + wx, e[1], e[2] + wz};
                        glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(quad), quad);
                        glUniform4f(glGetUniformLocation(solidProgram, "uColor"), 0.75f, 0.85f,
                                    1.0f, pt.hits ? 0.85f : 0.45f);
                        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                        if (pt.hits) {
                            // cursor dot on the panel (slightly in front of it)
                            const Panel &pg = pt.panel == 1 ? shortsPanelGeo : commentsPanelGeo;
                            const float rl = sqrtf(pg.r[0] * pg.r[0] + pg.r[2] * pg.r[2]);
                            const float s = 0.014f;
                            const float rx = pg.r[0] / rl * s, rz = pg.r[2] / rl * s;
                            const float h[3] = {pt.hit[0] - pt.d[0] * 0.01f,
                                                pt.hit[1] - pt.d[1] * 0.01f,
                                                pt.hit[2] - pt.d[2] * 0.01f};
                            const float dot[12] = {h[0] - rx, h[1] - s, h[2] - rz,
                                                   h[0] + rx, h[1] - s, h[2] + rz,
                                                   h[0] - rx, h[1] + s, h[2] - rz,
                                                   h[0] + rx, h[1] + s, h[2] + rz};
                            glBufferSubData(GL_ARRAY_BUFFER, 0, sizeof(dot), dot);
                            glUniform4f(glGetUniformLocation(solidProgram, "uColor"), 1.0f,
                                        1.0f, 1.0f, 0.95f);
                            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                        }
                    }
                    glBindVertexArray(0);
                    glDisable(GL_BLEND);
                }

                if (labelAlpha > 0) {
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE,
                                        GL_ONE_MINUS_SRC_ALPHA);
                    glUseProgram(labelProgram);
                    glUniformMatrix4fv(glGetUniformLocation(labelProgram, "uProj"), 1,
                                       GL_FALSE, proj);
                    const float halfH = 0.1f;
                    glUniform2f(glGetUniformLocation(labelProgram, "uSize"),
                                halfH * labelAspect, halfH);
                    glUniform1f(glGetUniformLocation(labelProgram, "uAlpha"), labelAlpha);
                    glUniform1i(glGetUniformLocation(labelProgram, "uTex"), 0);
                    glActiveTexture(GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, labelTex);
                    glBindVertexArray(labelVao);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    glBindVertexArray(0);
                    glDisable(GL_BLEND);
                }
                glBindFramebuffer(GL_FRAMEBUFFER, 0);

                XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
                xrReleaseSwapchainImage(swapchains[i], &ri);

                projViews[i].pose = views[i].pose;
                projViews[i].fov = views[i].fov;
                projViews[i].subImage.swapchain = swapchains[i];
                projViews[i].subImage.imageRect.offset = {0, 0};
                projViews[i].subImage.imageRect.extent = {w, h};
            }
            if (located == viewCount) {
                if (hasLayerSettings) {
                    // ask the compositor to super-sample and sharpen this layer
                    layerSettings.layerFlags = XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SUPER_SAMPLING_BIT_FB
                            | XR_COMPOSITION_LAYER_SETTINGS_NORMAL_SHARPENING_BIT_FB;
                    layer.next = &layerSettings;
                }
                layer.space = space;
                layer.viewCount = viewCount;
                layer.views = projViews.data();
                if (passthroughOn && gLayout.load() >= SCREEN_2D) {
                    // the room underneath, the screen (with see-through surroundings) on top
                    layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                    layers[layerCount++] = (XrCompositionLayerBaseHeader *) &passthroughComp;
                } else {
                    layer.layerFlags = 0;
                }
                layers[layerCount++] = (XrCompositionLayerBaseHeader *) &layer;
            }
        }

        XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};
        end.displayTime = frameState.predictedDisplayTime;
        end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        end.layerCount = layerCount;
        end.layers = layers;
        xrEndFrame(session, &end);
    }

cleanup:
    LOGI("leaving immersive mode %s", error.c_str());
    if (session != XR_NULL_HANDLE && sessionRunning) xrEndSession(session);
    for (auto sc : swapchains) xrDestroySwapchain(sc);
    if (videoSwapchain != XR_NULL_HANDLE) xrDestroySwapchain(videoSwapchain);
    if (leftAimSpace != XR_NULL_HANDLE) xrDestroySpace(leftAimSpace);
    if (rightAimSpace != XR_NULL_HANDLE) xrDestroySpace(rightAimSpace);
    if (passthroughLayer != XR_NULL_HANDLE && pDestroyPassthroughLayer) {
        pDestroyPassthroughLayer(passthroughLayer);
    }
    if (passthrough != XR_NULL_HANDLE && pDestroyPassthrough) pDestroyPassthrough(passthrough);
    if (actionSet != XR_NULL_HANDLE) xrDestroyActionSet(actionSet);
    if (space != XR_NULL_HANDLE) xrDestroySpace(space);
    if (session != XR_NULL_HANDLE) xrDestroySession(session);
    if (instance != XR_NULL_HANDLE) xrDestroyInstance(instance);
    if (context != EGL_NO_CONTEXT) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display, pbuffer);
        eglDestroyContext(display, context);
    }
    jstring jError = env->NewStringUTF(error.c_str());
    env->CallVoidMethod(gBridge.activity, gBridge.onFinished, jError);
    env->DeleteLocalRef(jError);
    env->DeleteLocalRef(jTexMatrix);
    gBridge.vm->DetachCurrentThread();
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_org_schabi_newpipe_pipevr_ImmersiveVideoActivity_nativeStart(
        JNIEnv *env, jobject activity, jint layout) {
    if (gRunning.load()) return;
    env->GetJavaVM(&gBridge.vm);
    gBridge.activity = env->NewGlobalRef(activity);
    jclass cls = env->GetObjectClass(activity);
    gBridge.createSurface = env->GetMethodID(cls, "onCreateVideoTexture", "(I)V");
    gBridge.updateTexture = env->GetMethodID(cls, "onUpdateVideoTexture", "([F)Z");
    gBridge.onAction = env->GetMethodID(cls, "onXrAction", "(II)V");
    gBridge.onFinished = env->GetMethodID(cls, "onXrFinished", "(Ljava/lang/String;)V");
    gBridge.onSurface = env->GetMethodID(cls, "onCreateVideoSurface", "(Landroid/view/Surface;)V");
    gBridge.videoSize = env->GetMethodID(cls, "getVideoSize", "()[I");
    gBridge.label = env->GetMethodID(cls, "renderOverlay", "(I)Landroid/graphics/Bitmap;");
    gBridge.chat = env->GetMethodID(cls, "renderChat", "(I)Landroid/graphics/Bitmap;");
    gBridge.chatLayout = env->GetMethodID(cls, "getChatLayout", "()[F");
    gBridge.saveChatLayout = env->GetMethodID(cls, "onChatLayout", "(FFFFZ)V");
    gBridge.getEnvironment = env->GetMethodID(cls, "getEnvironment", "()I");
    gBridge.captionVersion = env->GetMethodID(cls, "getCaptionVersion", "()I");
    gBridge.panelsVersion = env->GetMethodID(cls, "getPanelsVersion", "()I");
    gBridge.shortsPanel = env->GetMethodID(cls, "renderShortsPanel",
                                           "()Landroid/graphics/Bitmap;");
    gBridge.commentsPanel = env->GetMethodID(cls, "renderCommentsPanel",
                                             "()Landroid/graphics/Bitmap;");
    gBridge.panelClick = env->GetMethodID(cls, "onPanelClick", "(IFF)V");
    gBridge.captions = env->GetMethodID(cls, "renderCaptions", "()Landroid/graphics/Bitmap;");
    gBridge.saveEnvironment = env->GetMethodID(cls, "onEnvironment", "(I)V");
    gLayout = layout;
    gRunning = true;
    gThread = std::thread(renderLoop);
}

extern "C" JNIEXPORT void JNICALL
Java_org_schabi_newpipe_pipevr_ImmersiveVideoActivity_nativeStop(JNIEnv *env, jobject) {
    gRunning = false;
    if (gThread.joinable()) gThread.join();
    if (gBridge.activity) {
        env->DeleteGlobalRef(gBridge.activity);
        gBridge.activity = nullptr;
    }
}

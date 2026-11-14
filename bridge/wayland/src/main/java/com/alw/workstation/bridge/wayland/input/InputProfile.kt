package com.alw.workstation.bridge.wayland.input

/**
 * Input profile presets tailoring hardware interaction to specific workstation workloads.
 */
enum class InputProfile {
    /**
     * Optimized for digital art, illustration, and design (Krita, Blender, Inkscape, GIMP).
     * Suppresses the default Android hover circle cursor (PointerIcon.TYPE_NULL) and routes
     * stylus hardware telemetry exclusively to zwp_tablet_tool_v2.
     */
    CREATIVE_PEN,

    /**
     * Optimized for high-performance desktop productivity and gaming.
     * Enforces relative pointer capture (View.requestPointerCapture) for unconstrained 360-degree
     * mouse look and 1000Hz raw mouse update loops.
     */
    GAMING_RAW
}

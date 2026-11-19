-- Sekai Pop theme layer (ALW, TASK-025)
-- Palette: Blapples/SekaiPop (sekaipop/services/PaletteService.qml)
local base    = "#555473"
local surface = "#6e6d94"
local accent  = "#04feee"
local accent2 = "#00ffcb"
local text    = "#d9fff6"

hl.config({
    general = {
        gaps_in = 4,
        gaps_out = 10,
        border_size = 2,
        col = {
            active_border = { colors = { accent, accent2 }, angle = 45 },
            inactive_border = surface,
        },
    },
    group = {
        col = { border_active = accent, border_inactive = surface },
    },
    decoration = {
        rounding = 12,
        active_opacity = 0.96,
        inactive_opacity = 0.90,
        shadow = { enabled = true, range = 18, render_power = 3, color = "rgba(55547388)" },
        -- Software rendering (kms_swrast) inside PRoot: keep blur off for frame pacing.
        blur = { enabled = false },
    },
    misc = { col = { splash = accent } },
})

hl.env("XCURSOR_THEME", "Adwaita")

-- ALW: keep the demo terminal floating so the Sekai Pop wallpaper stays visible
-- on the tablet instead of being covered by a tiled full-screen window.
hl.window_rule({
    match = { class = "^(org\\.kde\\.konsole|konsole|kitty)$" },
    float = true,
    center = true,
    size = { "monitor_w*0.66", "monitor_h*0.62" },
})

// ALW live FPS pill (TASK-025): polls the host bridge once per second.
import Quickshell
import Quickshell.Io
import Quickshell.Wayland
import QtQuick
import QtQuick.Layouts

ShellRoot {
    id: root

    property string fpsValue: "--"
    property string scaleValue: "1.00"

    function refresh(raw) {
        var f = raw.match(/fps=(\d+)/)
        var s = raw.match(/scale=([0-9.]+)/)
        if (f) root.fpsValue = f[1]
        if (s) root.scaleValue = s[1]
    }

    Process {
        id: probe
        command: ["alw-host-query", "fps"]
        stdout: StdioCollector {
            onStreamFinished: root.refresh(text)
        }
    }

    Timer {
        interval: 1000
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: probe.running = true
    }

    Variants {
        model: Quickshell.screens

        delegate: PanelWindow {
            required property var modelData
            screen: modelData
            anchors.bottom: true
            anchors.right: true
            margins.bottom: 96
            margins.right: 28
            implicitWidth: pill.width
            implicitHeight: pill.height
            color: "transparent"
            exclusionMode: ExclusionMode.Ignore
            WlrLayershell.layer: WlrLayer.Overlay

            Rectangle {
                id: pill
                radius: 12
                color: "#CC2A293D"
                border.width: 2
                border.color: "#04FEEE"
                width: row.implicitWidth + 24
                height: 34

                RowLayout {
                    id: row
                    anchors.centerIn: parent
                    spacing: 8
                    Text {
                        text: "\uf135"
                        color: "#04FEEE"
                        font.pixelSize: 15
                    }
                    Text {
                        text: root.fpsValue + " FPS"
                        color: "#D9FFF6"
                        font.pixelSize: 14
                        font.bold: true
                    }
                    Text {
                        text: root.scaleValue + "x"
                        color: "#6E6D94"
                        font.pixelSize: 12
                    }
                }
            }
        }
    }
}

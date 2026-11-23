import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    width: 420
    height: 700
    visible: true
    title: "USB Serial Terminal"

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 10
        spacing: 8

        Label {
            id: statusLabel
            text: "Статус: не подключено"
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }

        RowLayout {
            Layout.fillWidth: true
            Button {
                text: "Открыть порт"
                Layout.fillWidth: true
                onClicked: serialReader.connectPort()
            }
            Button {
                text: "Закрыть"
                onClicked: serialReader.disconnectPort()
            }
            Button {
                text: "Очистить"
                onClicked: logArea.text = ""
            }
        }

        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true

            TextArea {
                id: logArea
                readOnly: true
                font.family: "monospace"
                font.pixelSize: 12
                wrapMode: TextArea.NoWrap
                background: Rectangle { color: "#111111" }
                color: "#e0e0e0"
            }
        }
    }

    Connections {
        target: serialReader
        function onStatusChanged(text) {
            statusLabel.text = "Статус: " + text
        }
        function onLineReceived(line) {
            logArea.append(line)
        }
    }
}
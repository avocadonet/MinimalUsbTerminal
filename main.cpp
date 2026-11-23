#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include "SerialReader.h"

int main(int argc, char* argv[]) {
    QGuiApplication app(argc, argv);

    SerialReader reader;

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty("serialReader", &reader);
    engine.loadFromModule("MinimalUsbTerminal", "Main");

    return app.exec();
}
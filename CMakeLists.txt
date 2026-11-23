cmake_minimum_required(VERSION 3.21)
project(MinimalUsbTerminal LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 REQUIRED COMPONENTS Core Gui Qml Quick)

qt_add_executable(MinimalUsbTerminal
    main.cpp
    SerialReader.h
    SerialReader.cpp
)

qt_add_qml_module(MinimalUsbTerminal
    URI MinimalUsbTerminal
    QML_FILES Main.qml
)

target_link_libraries(MinimalUsbTerminal PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Qml
    Qt6::Quick
)

set_target_properties(MinimalUsbTerminal PROPERTIES
    QT_ANDROID_PACKAGE_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/android"
)
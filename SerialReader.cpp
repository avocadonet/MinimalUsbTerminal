#include "SerialReader.h"
#include <QJniEnvironment>
#include <unistd.h>

static SerialReader* s_instance = nullptr;
SerialReader* g_serialReader() { return s_instance; }

SerialReader::SerialReader(QObject* parent) : QObject(parent) {
    s_instance = this;
#ifdef Q_OS_ANDROID
    QJniObject ctx = QNativeInterface::QAndroidApplication::context();
    m_opener = QJniObject(
        "com/example/usbterm/UsbPortOpener",
        "(Landroid/content/Context;)V",
        ctx.object<jobject>()
    );
#endif
}

SerialReader::~SerialReader() {
    disconnectPort();
    if (s_instance == this) s_instance = nullptr;
}

void SerialReader::connectPort() {
#ifdef Q_OS_ANDROID
    if (m_opener.isValid()) {
        emit statusChanged("Запрос доступа к порту...");
        m_opener.callMethod<void>("openPort");
    }
#endif
}

void SerialReader::disconnectPort() {
#ifdef Q_OS_ANDROID
    if (m_opener.isValid()) {
        m_opener.callMethod<void>("closePort");
    }
#endif
    if (m_fd >= 0) {
        ::close(m_fd);
        m_fd = -1;
    }
    m_buf.clear();
    emit statusChanged("Отключено");
}

void SerialReader::handleOpened(int fd, const QString& info) {
    m_fd = fd;
    emit statusChanged("Открыт: " + info + " (fd=" + QString::number(fd) + ")");
}

void SerialReader::handleError(const QString& msg) {
    emit statusChanged("Ошибка: " + msg);
}

void SerialReader::handleBytes(const QByteArray& chunk) {
    m_buf.append(chunk);
    if (m_buf.size() > 16384) m_buf.clear();

    int idx;
    while ((idx = m_buf.indexOf('\n')) != -1) {
        QString line = QString::fromLatin1(m_buf.left(idx)).trimmed();
        m_buf.remove(0, idx + 1);
        if (!line.isEmpty()) {
            emit lineReceived(line);
        }
    }
}

#ifdef Q_OS_ANDROID
extern "C" JNIEXPORT void JNICALL
Java_com_example_usbterm_UsbPortOpener_onPortOpened(JNIEnv*, jclass, jint fd, jstring info) {
    if (!g_serialReader()) return;
    QJniObject jInfo(info);
    QMetaObject::invokeMethod(g_serialReader(), [fd, s = jInfo.toString()]() {
        g_serialReader()->handleOpened(fd, s);
    }, Qt::QueuedConnection);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_usbterm_UsbPortOpener_onPortError(JNIEnv*, jclass, jstring msg) {
    if (!g_serialReader()) return;
    QJniObject jMsg(msg);
    QMetaObject::invokeMethod(g_serialReader(), [s = jMsg.toString()]() {
        g_serialReader()->handleError(s);
    }, Qt::QueuedConnection);
}
#endif
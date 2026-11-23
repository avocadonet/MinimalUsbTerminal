#pragma once
#include <QObject>
#include <QByteArray>
#include <QString>
#include <QJniObject>

class SerialReader : public QObject {
    Q_OBJECT
public:
    explicit SerialReader(QObject* parent = nullptr);
    ~SerialReader() override;

    Q_INVOKABLE void connectPort();
    Q_INVOKABLE void disconnectPort();

    void handleOpened(int fd, const QString& info);
    void handleError(const QString& msg);
    void handleBytes(const QByteArray& chunk);

signals:
    void statusChanged(const QString& text);
    void lineReceived(const QString& line);

private:
    QByteArray m_buf;
    QJniObject m_opener;
    int m_fd = -1;
};

SerialReader* g_serialReader();
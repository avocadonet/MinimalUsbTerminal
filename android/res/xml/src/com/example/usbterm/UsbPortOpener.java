package com.example.usbterm;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.*;
import android.os.Build;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class UsbPortOpener {
    private static final String ACTION_PERM = "com.example.usbterm.USB_PERMISSION";
    private final Context ctx;
    private final UsbManager usbManager;
    private UsbDeviceConnection conn;
    private boolean receiverRegistered = false;

    public static native void onPortOpened(int fd, String info);
    public static native void onPortError(String msg);

    public UsbPortOpener(Context context) {
        this.ctx = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void openPort() {
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == 0x152A || isCdc(dev)) {
                if (!usbManager.hasPermission(dev)) {
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ACTION_PERM), flags);
                    if (!receiverRegistered) {
                        ctx.registerReceiver(permReceiver, new IntentFilter(ACTION_PERM), Context.RECEIVER_EXPORTED);
                        receiverRegistered = true;
                    }
                    usbManager.requestPermission(dev, pi);
                } else {
                    claimAndConfigure(dev);
                }
                return;
            }
        }
        onPortError("USB serial устройство не найдено");
    }

    private boolean isCdc(UsbDevice dev) {
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            if (dev.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) return true;
        }
        return false;
    }

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_PERM.equals(intent.getAction())) return;
            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                claimAndConfigure(dev);
            } else {
                onPortError("Доступ к USB запрещён");
            }
        }
    };

    private void claimAndConfigure(UsbDevice dev) {
        UsbInterface commIf = null;
        UsbInterface dataIf = null;
        int inEpAddr = -1;

        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) commIf = iface;
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                dataIf = iface;
                for (int j = 0; j < iface.getEndpointCount(); j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        inEpAddr = ep.getAddress();
                    }
                }
            }
        }

        if (dataIf == null) {
            onPortError("CDC Data интерфейс не найден");
            return;
        }

        conn = usbManager.openDevice(dev);
        if (conn == null) {
            onPortError("Не удалось открыть соединение");
            return;
        }

        if (commIf != null) conn.claimInterface(commIf, true);
        conn.claimInterface(dataIf, true);

        // 115200 8N1 (CDC SET_LINE_CODING = 0x20)
        ByteBuffer cfg = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
        cfg.putInt(115200);
        cfg.put((byte) 0); // 1 stop bit
        cfg.put((byte) 0); // No parity
        cfg.put((byte) 8); // 8 data bits
        conn.controlTransfer(0x21, 0x20, 0, 0, cfg.array(), 7, 1000);

        // DTR + RTS (CDC SET_CONTROL_LINE_STATE = 0x22)
        conn.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 1000);

        // Передаём fd в C++ (в bulk-режиме на Android fd используется для libusb / ioctl / read)
        int fd = conn.getFileDescriptor();
        onPortOpened(fd, dev.getDeviceName() + " (IN EP 0x" + Integer.toHexString(inEpAddr) + ")");
    }

    public void closePort() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }
}
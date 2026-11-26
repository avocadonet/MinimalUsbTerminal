$javaCode = @'
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
    private UsbInterface claimedComm;
    private UsbInterface claimedData;
    private Thread readThread;
    private volatile boolean running = false;
    private boolean receiverRegistered = false;

    public static native void onPortOpened(int fd, String info);
    public static native void onPortError(String msg);
    public static native void onDataReceived(byte[] data);

    public UsbPortOpener(Context context) {
        this.ctx = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void openPort() {
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            if (dev.getVendorId() == 0x152A || isCdc(dev)) {
                if (!usbManager.hasPermission(dev)) {
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT;
                    Intent permIntent = new Intent(ACTION_PERM);
                    permIntent.setPackage(ctx.getPackageName());
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, permIntent, flags);
                    if (!receiverRegistered) {
                        IntentFilter filter = new IntentFilter(ACTION_PERM);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ctx.registerReceiver(permReceiver, filter, Context.RECEIVER_EXPORTED);
                        } else {
                            ctx.registerReceiver(permReceiver, filter);
                        }
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
        closePort();

        UsbInterface commIf = null;
        UsbInterface dataIf = null;
        UsbEndpoint inEp = null;

        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            int cls = iface.getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_COMM && commIf == null) {
                commIf = iface;
            } else if (cls == UsbConstants.USB_CLASS_CDC_DATA && dataIf == null) {
                for (int j = 0; j < iface.getEndpointCount(); j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        dataIf = iface;
                        inEp = ep;
                        break;
                    }
                }
            }
            if (commIf != null && dataIf != null && inEp != null) break;
        }

        if (dataIf == null || inEp == null) {
            onPortError("CDC Data интерфейс с Bulk IN не найден");
            return;
        }

        conn = usbManager.openDevice(dev);
        if (conn == null) {
            onPortError("Не удалось открыть USB-соединение");
            return;
        }

        if (commIf != null && conn.claimInterface(commIf, true)) {
            claimedComm = commIf;
        }
        if (!conn.claimInterface(dataIf, true)) {
            onPortError("Не удалось захватить CDC Data интерфейс");
            closePort();
            return;
        }
        claimedData = dataIf;

        int ctrlIfIndex = (commIf != null) ? commIf.getId() : dataIf.getId();

        // SET_LINE_CODING: 115200 8N1
        ByteBuffer cfg = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
        cfg.putInt(115200);
        cfg.put((byte) 0);
        cfg.put((byte) 0);
        cfg.put((byte) 8);
        conn.controlTransfer(0x21, 0x20, 0, ctrlIfIndex, cfg.array(), 7, 1000);

        // SET_CONTROL_LINE_STATE: DTR=1, RTS=1
        conn.controlTransfer(0x21, 0x22, 0x03, ctrlIfIndex, null, 0, 1000);

        final UsbEndpoint readEp = inEp;
        final UsbDeviceConnection activeConn = conn;
        running = true;
        readThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (running && activeConn != null) {
                int n = activeConn.bulkTransfer(readEp, buf, buf.length, 500);
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    onDataReceived(chunk);
                }
            }
        }, "UsbBulkReadThread");
        readThread.start();

        int fd = conn.getFileDescriptor();
        onPortOpened(fd, dev.getDeviceName() + " if=" + dataIf.getId() + " EP=0x" + Integer.toHexString(inEp.getAddress()));
    }

    public void closePort() {
        running = false;
        if (readThread != null) {
            try { readThread.join(600); } catch (InterruptedException ignored) {}
            readThread = null;
        }
        if (conn != null) {
            if (claimedData != null) {
                conn.releaseInterface(claimedData);
                claimedData = null;
            }
            if (claimedComm != null) {
                conn.releaseInterface(claimedComm);
                claimedComm = null;
            }
            conn.close();
            conn = null;
        }
    }
}
'@
[System.IO.File]::WriteAllText("C:\p\usb\android\src\com\example\usbterm\UsbPortOpener.java", $javaCode, (New-Object System.Text.UTF8Encoding $false))
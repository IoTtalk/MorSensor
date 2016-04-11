package tw.org.cic.imusensor;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BLEManager {
    static final long SCAN_PERIOD_MILLIS = 10000;
    static public final int REQUEST_ENABLE_BT = 1;

    static BluetoothAdapter bluetooth_adapter;
    static BluetoothAdapter.LeScanCallback ble_scan_callback;
    static Handler searching_handler;
    static private final Set<Subscriber> event_subscribers = Collections.synchronizedSet(new HashSet<Subscriber>());

    static public enum EventTag {
        START_SEARCHING,
        STOP_SEARCHING,
    }

    static public boolean init (Activity activity) {
        logging("init()");

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(activity, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            return false;
        }

        final BluetoothManager bluetoothManager =
                (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetooth_adapter = bluetoothManager.getAdapter();

        if (bluetooth_adapter == null) {
            return false;
        }

        if (!bluetooth_adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        searching_handler = new Handler();

        return true;
    }

    static public void subscribe (Subscriber s) {
        synchronized (event_subscribers) {
            if (!event_subscribers.contains(s)) {
                event_subscribers.add(s);
            }
        }
    }

    static public void unsubscribe (Subscriber s) {
        synchronized (event_subscribers) {
            if (event_subscribers.contains(s)) {
                event_subscribers.remove(s);
            }
        }
    }

    static public boolean is_searching () {
        return ble_scan_callback != null;
    }

    static public boolean search (BluetoothAdapter.LeScanCallback callback) {
        logging("search()");
        if (ble_scan_callback != null) {
            return false;
        }
        ble_scan_callback = callback;
        bluetooth_adapter.startLeScan(ble_scan_callback);
        broadcast_event(EventTag.START_SEARCHING, "");
        searching_handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stop_searching();
            }
        }, SCAN_PERIOD_MILLIS);
        return true;
    }

    static public boolean stop_searching() {
        logging("stop_searching()");
        if (ble_scan_callback == null) {
            logging("stop_searching(): already stopped");
            return false;
        }
        bluetooth_adapter.stopLeScan(ble_scan_callback);
        ble_scan_callback = null;
        broadcast_event(EventTag.STOP_SEARCHING, "");
        return true;
    }

    static abstract class Subscriber {
        abstract public void on_event (EventTag event_tag, String message);
    }

    static private void broadcast_event (EventTag event_tag, String message) {
        synchronized (event_subscribers) {
            for (Subscriber handler: event_subscribers) {
                handler.on_event(event_tag, message);
            }
        }
    }

    static public void logging (String message) {
        Log.i(Constants.log_tag, "[BLEManager] " + message);
    }
}

package tw.org.cic.imusensor;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BLEManager {
    static final long SCAN_PERIOD_MILLIS = 10000;
    static public final int REQUEST_ENABLE_BT = 1;

    static Context application_context = null;
    static BluetoothAdapter bluetooth_adapter;
    static final BluetoothAdapter.LeScanCallback ble_scan_callback = new BLEScanCallback();
    static boolean searching;
    static Handler searching_handler;
    static private final Set<Subscriber> event_subscribers = Collections.synchronizedSet(new HashSet<Subscriber>());

    static BluetoothLeService ble_service;
    static final BLEServiceConnection ble_service_connection = new BLEServiceConnection();
    static final GattUpdateReceiver gatt_update_receiver = new GattUpdateReceiver();
    static IDA pending_ida;

    static public enum EventTag {
        START_SEARCHING,
        FOUND_NEW_IDA,
        STOP_SEARCHING,
        CONNECTION_FAILED,
        CONNECTED,
        DISCONNECTION_FAILED,
        DISCONNECTED,
    }

    static public boolean init (Activity activity) {
        logging("init()");

        application_context = activity.getApplicationContext();

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
        searching = false;
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
        return searching;
    }

    static public boolean search () {
        logging("search()");
        if (searching) {
            logging("search(): already searching");
            return false;
        }
        bluetooth_adapter.startLeScan(ble_scan_callback);
        searching = true;
        broadcast_event(EventTag.START_SEARCHING, null);
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
        if (!searching) {
            logging("stop_searching(): already stopped");
            return false;
        }
        bluetooth_adapter.stopLeScan(ble_scan_callback);
        searching = false;
        broadcast_event(EventTag.STOP_SEARCHING, null);
        return true;
    }

    static public void connect (final IDA ida) {
        logging("connect()");
        if (ble_service != null) {
            logging("connect(): already connected");
            return;
        }

        if (pending_ida != null) {
            logging("connect(): already connecting to "+ pending_ida.addr);
            return;
        }
        pending_ida = ida;
        Intent gattServiceIntent = new Intent(application_context, BluetoothLeService.class);
        application_context.getApplicationContext().bindService(
                gattServiceIntent, ble_service_connection, Context.BIND_AUTO_CREATE);

        //Register BluetoothLe Receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        application_context.registerReceiver(gatt_update_receiver, intentFilter);
    }

    static public void disconnect () {
        logging("disconnect()");
        if (ble_service == null) {
            logging("disconnect(): Not connected to any device");
            return;
        }

        ble_service.disconnect();
        application_context.unregisterReceiver(gatt_update_receiver);
        application_context.unbindService(ble_service_connection);
        ble_service = null;
    }

    static abstract class Subscriber {
        abstract public void on_event (final EventTag event_tag, final Object message);
    }

    static public class IDA {
        public String name;
        public String addr;
        public int rssi;

        public IDA (String name, String addr, int rssi) {
            this.name = name;
            this.addr = addr;
            this.rssi = rssi;
        }

        @Override
        public boolean equals (Object obj) {
            if (!(obj instanceof IDA)) {
                return false;
            }

            IDA another = (IDA) obj;
            if (this.addr == null) {
                return false;
            }
            return this.addr.equals(another.addr);
        }
    }

    static class BLEScanCallback implements BluetoothAdapter.LeScanCallback {
        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi, byte[] scanRecord) {
            IDA new_found_ida = new IDA(device.getName(), device.getAddress(), rssi);
            broadcast_event(EventTag.FOUND_NEW_IDA, new_found_ida);
        }
    }

    static class BLEServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            ble_service = ((BluetoothLeService.LocalBinder) service).getService();
            if (!ble_service.initialize()) {
                broadcast_event(EventTag.CONNECTION_FAILED, "Unable to initialize Bluetooth");
                return;
            }
            // Automatically connects to the device upon successful start-up initialization.
            if (!(ble_service.connect(pending_ida.addr))) {
                broadcast_event(EventTag.CONNECTION_FAILED, "Unable to connect to "+ pending_ida.addr);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            logging("==== onServiceDisconnected ====");
            logging("==== SHOULDn't HAPPENED ====");
            ble_service = null;
        }
    }

    static class GattUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                logging("==== ACTION_GATT_CONNECTED ====");
                pending_ida = null;

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                logging("==== ACTION_GATT_DISCONNECTED ====");

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                logging("==== ACTION_DATA_AVAILABLE ====");
            }
        }
    }

    static private void broadcast_event (EventTag event_tag, Object message) {
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

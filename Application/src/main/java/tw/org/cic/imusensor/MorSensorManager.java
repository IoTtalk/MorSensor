package tw.org.cic.imusensor;

import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MorSensorManager extends Service {
    static final int REQUEST_ENABLE_BT = 1;
    static final int SCAN_PERIOD = 5000;

    static private final Set<Subscriber> event_subscribers = Collections.synchronizedSet(new HashSet<Subscriber>());
    static MorSensorManager self;
    static boolean request_search;
    static boolean is_searching;
    static final Handler searching_stop_timer = new Handler();
    static BluetoothAdapter bluetooth_adapter;
    static final BLEServiceConnection ble_service_connection = new BLEServiceConnection();
    static BluetoothLeService bluetooth_le_service;
    static final GattUpdateReceiver gatt_update_receiver = new GattUpdateReceiver();
    static final BLEScanCallback ble_scan_callback = new BLEScanCallback();

    static public enum EventTag {
        INITIALIZATION_FAILED,
        SEARCHING_STARTED,
        FOUND_NEW_IDA,
        SEARCHING_STOPPED,
        CONNECTION_FAILED,
        CONNECTED,
        DISCONNECTION_FAILED,
        DISCONNECTED,
        DATA_AVAILABLE,
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        logging("onCreate()");
        self = this;

        //Start BluetoothLe Service
        Intent gattServiceIntent = new Intent(self, BluetoothLeService.class);
        self.getApplicationContext().bindService(gattServiceIntent, ble_service_connection, Context.BIND_AUTO_CREATE);

        //Register BluetoothLe Receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        self.registerReceiver(gatt_update_receiver, intentFilter);

        if (request_search) {
            search();
        }
    }

    @Override
    public void onDestroy() {
        logging("onDestroy()");
        self = null;
    }


    /**
     * Public API
     */

    static public boolean init(Activity activity) {
        logging("init()");
        final BluetoothManager bluetoothManager =
                (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetooth_adapter = bluetoothManager.getAdapter();

        if (bluetooth_adapter == null) {
            logging("init(): INITIALIZATION_FAILED: Bluetooth not supported");
            broadcast_event(EventTag.INITIALIZATION_FAILED, "Bluetooth not supported");
            return false;
        }
        request_search = false;

        activity.startService(new Intent(activity, MorSensorManager.class));
        return true;
    }

    static public void subscribe(Subscriber s) {
        synchronized (event_subscribers) {
            if (!event_subscribers.contains(s)) {
                event_subscribers.add(s);
            }
        }
    }

    static public void unsubscribe(Subscriber s) {
        synchronized (event_subscribers) {
            if (event_subscribers.contains(s)) {
                event_subscribers.remove(s);
            }
        }
    }

    static public void search() {
        logging("search()");
        if (self == null) {
            logging("search(): Service is not ready yet, keep it first");
            request_search = true;
            return;
        }
        // Stops scanning after a pre-defined scan period.
        searching_stop_timer.postDelayed(new Runnable() {
            @Override
            public void run() {
                stop_searching();
            }
        }, SCAN_PERIOD);

        is_searching = true;
        bluetooth_adapter.startLeScan(ble_scan_callback);
        broadcast_event(EventTag.SEARCHING_STARTED, null);
    }

    static public void stop_searching() {
        logging("stop_searching()");
        if (self == null) {
            logging("stop_searching(): Service is not ready yet, keep it first");
            request_search = false;
            return;
        }
        is_searching = false;
        bluetooth_adapter.stopLeScan(ble_scan_callback);
        broadcast_event(EventTag.SEARCHING_STOPPED, null);
    }

    static public boolean is_searching() {
        return is_searching;
    }

    static public void connect(IDA ida) {
        logging("connect()");

    }

    static public void write(byte[] command) {
        logging("write()");

    }

    static public void disconnect() {
        logging("disconnect()");
    }

    static public void shutdown() {
        logging("shutdown()");
        self.stopSelf();
    }

    static public class IDA {
        String name;
        String addr;
        int rssi;

        public IDA (String addr, String name, int rssi) {
            this.addr = addr;
            this.name = name;
            this.rssi = rssi;
        }

        @Override
        public boolean equals(Object another) {
            if (!(another instanceof IDA)) {
                return false;
            }

            if (this.addr == null) {
                return false;
            }

            return this.addr.equals(((IDA) another).addr);
        }
    }

    static public abstract class Subscriber {
        abstract public void on_event(final EventTag event_tag, final Object message);
    }


    /**
     * Private Helper Classes
     */

    static class BLEScanCallback implements BluetoothAdapter.LeScanCallback {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            broadcast_event(EventTag.FOUND_NEW_IDA, new IDA(device.getAddress(), device.getName(), rssi));
        }
    }

    static class BLEServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            bluetooth_le_service = ((BluetoothLeService.LocalBinder) service).getService();
            if (!bluetooth_le_service.initialize()) {
                broadcast_event(EventTag.INITIALIZATION_FAILED, "Bluetooth service initialization failed");
                bluetooth_le_service = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            logging("==== onServiceDisconnected ====");
            logging("==== SHOULDn't HAPPENED ====");
            bluetooth_le_service = null;
        }
    }

    static class GattUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                logging("==== ACTION_GATT_CONNECTED ====");

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                logging("==== ACTION_GATT_DISCONNECTED ====");

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");
//                displayGattServices(mBluetoothLeService.getSupportedGattServices());
//                CommandSender.send_command(MorSensorCommand.GetSensorList());

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {

            }
        }
    }


    /**
     * Private Helper Functions
     */

    static void broadcast_event(EventTag event_tag, Object message) {
        synchronized (event_subscribers) {
            for (Subscriber handler : event_subscribers) {
                handler.on_event(event_tag, message);
            }
        }
    }

    static private void logging(String message) {
        Log.i(Constants.log_tag, "[SelectMorSensorActivity] " + message);
    }
}
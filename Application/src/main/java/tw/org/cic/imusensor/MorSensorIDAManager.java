package tw.org.cic.imusensor;

import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class MorSensorIDAManager extends Service implements IDAManager {

    static Subscriber init_subscriber;
    static MorSensorIDAManager self;
    static boolean is_initializing;

    final Set<IDAManager.Subscriber> event_subscribers = Collections.synchronizedSet(new HashSet<IDAManager.Subscriber>());
    boolean is_searching;
    final Handler searching_stop_timer = new Handler();
    BluetoothAdapter bluetooth_adapter;
    final ServiceConnection ble_service_connection = new BLEServiceConnection();
    BluetoothLeService bluetooth_le_service;
    final GattUpdateReceiver gatt_update_receiver = new GattUpdateReceiver();
    final BluetoothAdapter.LeScanCallback ble_scan_callback = new BLEScanCallback();
    BluetoothGattCharacteristic write_gatt_characteristic;
    BluetoothGattCharacteristic read_gatt_characteristic;
    IDAManager.IDA target_ida;
    boolean is_connected;
    boolean user_request_disconnect;
    final HashMap<String, Object> info = new HashMap<String, Object>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        logging("onCreate()");
        self = this;

        final BluetoothManager bluetoothManager =
                (BluetoothManager) self.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetooth_adapter = bluetoothManager.getAdapter();

        if (bluetooth_adapter == null) {
            logging("init(): INITIALIZATION_FAILED: Bluetooth not supported");
            init_subscriber.on_event(EventTag.INITIALIZATION_FAILED, "Bluetooth not supported");
        }

        //Start BluetoothLe Service
        Intent gattServiceIntent = new Intent(self, BluetoothLeService.class);
        self.getApplicationContext().bindService(gattServiceIntent, ble_service_connection, Context.BIND_AUTO_CREATE);
    }


    /**
     * Public API
     */

    static public void init(Activity activity, Subscriber s) {
        logging("init()");
        if (is_initializing) {
            logging("service is initializing");
//            init_subscriber.on_event(EventTag.INITIALIZATION_FAILED, "Still initializing");
            return;
        }

        if (self != null) {
            logging("already initialized");
            s.on_event(EventTag.INITIALIZED, "Already initialized");
            return;
        }

        init_subscriber = s;

        activity.startService(new Intent(activity, MorSensorIDAManager.class));
    }

    static public MorSensorIDAManager instance() {
        return self;
    }

    @Override
    public void subscribe(IDAManager.Subscriber s) {
        synchronized (event_subscribers) {
            if (!event_subscribers.contains(s)) {
                event_subscribers.add(s);
            }
        }
    }

    @Override
    public void unsubscribe(IDAManager.Subscriber s) {
        synchronized (event_subscribers) {
            if (event_subscribers.contains(s)) {
                event_subscribers.remove(s);
            }
        }
    }

    @Override
    public void search() {
        logging("search()");
        if (is_initializing) {
            logging("service is initializing");
            return;
        }

        if (self == null) {
            logging("search(): Service is not ready yet");
            return;
        }

        if (is_searching) {
            logging("search(): already searching");
            return;
        }

        // Stops scanning after a pre-defined scan period.
        searching_stop_timer.postDelayed(new Runnable() {
            @Override
            public void run() {
                stop_searching();
            }
        }, Constants.BLUETOOTH_SCANNING_PERIOD);

        is_searching = true;
        bluetooth_adapter.startLeScan(ble_scan_callback);
        broadcast_event(EventTag.SEARCHING_STARTED, null);
    }

    @Override
    public void stop_searching() {
        logging("stop_searching()");
        if (self == null) {
            logging("stop_searching(): Service is not ready yet");
            return;
        }

        if (!is_searching) {
            logging("stop_searching(): already stopped");
            return;
        }
        is_searching = false;
        bluetooth_adapter.stopLeScan(ble_scan_callback);
        broadcast_event(EventTag.SEARCHING_STOPPED, null);
    }

    @Override
    public void connect(IDAManager.IDA ida) {
        logging("connect("+ ida.id +")");
        target_ida = ida;
        bluetooth_le_service.connect(target_ida.id);
    }

    @Override
    public void write(byte[] command) {
        logging("write()");
        CommandSenderThread.instance().pend_out(command);
    }

    @Override
    public void disconnect() {
        logging("disconnect()");
        user_request_disconnect = true;
        bluetooth_le_service.disconnect();
    }

    @Override
    public boolean is_connected() {
        return is_connected;
    }

    public void put_info(String key, Object value) {
        info.put(key, value);
    }

    public Object get_info(String key) {
        return info.get(key);
    }

    public void shutdown() {
        logging("shutdown()");
        CommandSenderThread.instance().kill();
        self.getApplicationContext().unbindService(ble_service_connection);
        self.unregisterReceiver(gatt_update_receiver);
        self.stopSelf();
        self = null;
    }

    static public class MorSensorIDA extends IDAManager.IDA {
        String name;
        int rssi;

        public MorSensorIDA(String addr, String name, int rssi) {
            this.id = addr;
            this.name = name;
            this.rssi = rssi;
        }

        @Override
        public boolean equals(Object another) {
            if (!(another instanceof MorSensorIDA)) {
                return false;
            }

            if (this.id == null) {
                return false;
            }

            return this.id.equals(((MorSensorIDA) another).id);
        }
    }


    /**
     * Private Helper Classes
     */

    class BLEScanCallback implements BluetoothAdapter.LeScanCallback {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            broadcast_event(EventTag.FOUND_NEW_IDA, new MorSensorIDA(device.getAddress(), device.getName(), rssi));
        }
    }

    class BLEServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            if (user_request_disconnect) {
                is_connected = false;
                return;
            }

            bluetooth_le_service = ((BluetoothLeService.LocalBinder) service).getService();

            if (!bluetooth_le_service.initialize()) {
                init_subscriber.on_event(EventTag.INITIALIZATION_FAILED, "Bluetooth service initialization failed");
                bluetooth_le_service = null;
            } else {
                init_subscriber.on_event(EventTag.INITIALIZED, "Bluetooth service initialized");
                self.subscribe(init_subscriber);

                //Register BluetoothLe Receiver
                final IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
                intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
                intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
                intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
                self.registerReceiver(gatt_update_receiver, intentFilter);

                CommandSenderThread.instance().start();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            logging("==== onServiceDisconnected ====");
            logging("==== SHOULDn't HAPPENED ====");
            bluetooth_le_service = null;
        }
    }

    class GattUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                logging("==== ACTION_GATT_CONNECTED ====");

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                logging("==== ACTION_GATT_DISCONNECTED ====");
                if (user_request_disconnect) {
                    is_connected = false;
                } else {
                    // accidentally disconnected
                }

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");
                get_gatt_characteristics();
                is_connected = true;
                broadcast_event(EventTag.CONNECTED, target_ida);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                logging("==== ACTION_DATA_AVAILABLE ====");
                byte[] incoming_data = hex_to_bytes(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                CommandSenderThread.instance().pend_in(incoming_data);
                broadcast_event(EventTag.DATA_AVAILABLE, incoming_data);

            }
        }
    }

    static class CommandSenderThread extends Thread {
        static final int SCANNING_PERIOD = 50;
        static final int RESEND_CYCLE = 10;

        static final Semaphore instance_lock = new Semaphore(1);
        static CommandSenderThread self;

        final LinkedBlockingQueue<byte[]> outgoing_queue =
                new LinkedBlockingQueue<byte[]>();
        final LinkedBlockingQueue<byte[]> incoming_queue =
                new LinkedBlockingQueue<byte[]>();
        int running_cycle;

        private CommandSenderThread() {
            outgoing_queue.clear();
            incoming_queue.clear();
            running_cycle = 0;
        }

        static public CommandSenderThread instance() {
            try {
                instance_lock.acquire();
                if (self == null) {
                    logging("CommandSenderThread.instance(): create instance");
                    self = new CommandSenderThread();
                }
                instance_lock.release();
            } catch (InterruptedException e) {
                logging("CommandSenderThread.instance(): InterruptedException");
                e.printStackTrace();
            }
            return self;
        }

        public void pend_out(byte[] command) {
            outgoing_queue.add(command);
        }

        public void pend_in(byte[] command) {
            incoming_queue.add(command);
        }

        @Override
        public void run() {
            logging("CommandSenderThread started");
            try {
                while (true) {
                    if (running_cycle == 0) {
                        // check outgoing_queue every <RESEND_CYCLE> cycles
                        if (!outgoing_queue.isEmpty()) {
                            logging("output_queue is not empty, send command");
                            // something waiting in output_queue, send one out
                            //  but keep it in queue, because it may drop
                            byte[] outgoing_command = outgoing_queue.peek();
                            MorSensorIDAManager.self.write_gatt_characteristic.setValue(outgoing_command);
                            MorSensorIDAManager.self.bluetooth_le_service.writeCharacteristic(MorSensorIDAManager.self.write_gatt_characteristic);
                        }
                    }

                    byte[] outgoing_command = outgoing_queue.peek();
                    // MorSensor may send data at very high data rate.
                    // To prevent starvation, I check the queue size,
                    //  and only process these commands in the queue.
                    int incoming_queue_size = incoming_queue.size();
                    for (int i = 0; i < incoming_queue_size; i++) {
                        byte[] incoming_command = incoming_queue.take();
                        if (outgoing_command != null && outgoing_command[0] == incoming_command[0]) {
                            // there is a command pending out, and that command matches its response
                            // pop it from outgoing_queue
                            outgoing_queue.take();
                        }
                    }

                    Thread.sleep(SCANNING_PERIOD);
                    running_cycle = (running_cycle + 1) % RESEND_CYCLE;
                }
            } catch (InterruptedException e) {
                logging("CommandSenderThread.run(): InterruptedException");
            }
            logging("CommandSenderThread ended");
        }

        public void kill() {
            logging("CommandSenderThread.kill()");
            try {
                instance_lock.acquire();
                if (self == null) {
                    logging("CommandSenderThread.kill(): not running, skip");
                    return;
                }

                self.interrupt();
                try {
                    self.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                self = null;
                logging("CommandSenderThread.kill(): singleton cleaned");
                instance_lock.release();
            } catch (InterruptedException e) {
                logging("CommandSenderThread.kill(): InterruptedException");
            }
        }
    }


    /**
     * Private Helper Functions
     */

    static void get_gatt_characteristics() {
        for (BluetoothGattService gatt_service : self.bluetooth_le_service.getSupportedGattServices()) {
            for (BluetoothGattCharacteristic gatt_characteristic : gatt_service.getCharacteristics()) {

                if (gatt_characteristic.getUuid().toString().contains("00002a37-0000-1000-8000-00805f9b34fb")) {
                    final int charaProp = gatt_characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                        if (self.read_gatt_characteristic != null) {
                            self.bluetooth_le_service.setCharacteristicNotification(self.read_gatt_characteristic, false);
                            self.read_gatt_characteristic = null;
                        }
                    }
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        self.read_gatt_characteristic = gatt_characteristic;
                        self.bluetooth_le_service.setCharacteristicNotification(self.read_gatt_characteristic, true);
                    }
                }

                if (gatt_characteristic.getUuid().toString().contains("00001525-1212-efde-1523-785feabcd123")) {
                    final int charaProp = gatt_characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                        if (self.write_gatt_characteristic != null) {
                            self.write_gatt_characteristic = null;
                        }
                        self.write_gatt_characteristic = gatt_characteristic;
                    }
                }
            }
        }
    }

    static public byte[] hex_to_bytes(String hex_string) {
        int len = hex_string.length();
        byte[] ret = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            ret[i / 2] = (byte) ((Character.digit(hex_string.charAt(i), 16) << 4) | Character.digit(hex_string.charAt(i + 1), 16));
        }
        return ret;
    }

    static void broadcast_event(IDAManager.EventTag event_tag, Object message) {
        synchronized (self.event_subscribers) {
            for (Subscriber handler : self.event_subscribers) {
                handler.on_event(event_tag, message);
            }
        }
    }

    static void logging(String message) {
        Log.i(Constants.log_tag, "[MorSensorManager] " + message);
    }
}
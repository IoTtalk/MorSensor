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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

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
    static BluetoothGattCharacteristic write_gatt_characteristic;
    static BluetoothGattCharacteristic read_gatt_characteristic;

    static IDA connecting_ida;

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

        CommandSenderThread.instance().start();

        if (request_search) {
            search();
        }
    }

    @Override
    public void onDestroy() {
        logging("onDestroy()");
        self.unregisterReceiver(gatt_update_receiver);
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
        is_searching = false;
        connecting_ida = null;

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

        if (!is_searching) {
            logging("stop_searching(): already stopped");
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
        connecting_ida = ida;
        bluetooth_le_service.connect(connecting_ida.addr);
    }

    static public void write(byte[] command) {
        logging("write()");
        CommandSenderThread.instance().pend_out(command);
    }

    static public void disconnect() {
        logging("disconnect()");
        bluetooth_le_service.disconnect();
    }

    static public void shutdown() {
        logging("shutdown()");
        self.stopSelf();
        CommandSenderThread.instance().kill();
    }

    static public class IDA {
        String name;
        String addr;
        int rssi;

        public IDA(String addr, String name, int rssi) {
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
                get_gatt_characteristics();
                broadcast_event(EventTag.CONNECTED, connecting_ida);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                logging("==== ACTION_DATA_AVAILABLE ====");
                byte[] incoming_data = hex_to_bytes(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                CommandSenderThread.instance().pend_in(incoming_data);
                broadcast_event(EventTag.DATA_AVAILABLE, incoming_data);

            }
        }
    }

    static class CommandSenderThread extends Thread {
        static private final Semaphore instance_lock = new Semaphore(1);
        static private CommandSenderThread self;

        private final LinkedBlockingQueue<byte[]> outgoing_queue =
                new LinkedBlockingQueue<byte[]>();
        private final LinkedBlockingQueue<byte[]> incoming_queue =
                new LinkedBlockingQueue<byte[]>();

        private CommandSenderThread() {
        }

        static public CommandSenderThread instance() {
            try {
                instance_lock.acquire();
                if (self == null) {
                    logging("CommandSenderThread.instance(): create instance");
                    self = new CommandSenderThread();
                    self.outgoing_queue.clear();
                    self.incoming_queue.clear();
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
                    if (!outgoing_queue.isEmpty()) {
                        logging("output_queue is not empty, send out");
                        // something waiting in output_queue, send one out
                        //  but keep it in queue, because it may drop
                        byte[] outgoing_command = outgoing_queue.peek();
                        write_gatt_characteristic.setValue(outgoing_command);
                        bluetooth_le_service.writeCharacteristic(write_gatt_characteristic);
                    }

                    // To prevent starvation, I check the queue size, and only process them.
                    // MorSensor may send data at very high data rate
                    int incoming_queue_size = incoming_queue.size();
                    for (int i = 0; i < incoming_queue_size; i++) {
                        byte[] outgoing_command = outgoing_queue.peek();
                        byte[] incoming_command = incoming_queue.take();
                        byte out_opcode = outgoing_command[0];
                        byte in_opcode = incoming_command[0];

                        if (out_opcode == in_opcode) {
                            // in/out command matched, pop one out from outgoing_queue
                            outgoing_queue.take();
                        } else if (in_opcode == MorSensorCommand.IN_SENSOR_DATA) {
                            // data packet comes, keep waiting for command response
                        } else {
                            logging("****** In/out command mismatch, something fxcked up ******");
                        }
                    }

                    Thread.sleep(2000);
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
        for (BluetoothGattService gatt_service : bluetooth_le_service.getSupportedGattServices()) {
            for (BluetoothGattCharacteristic gatt_characteristic : gatt_service.getCharacteristics()) {

                if (gatt_characteristic.getUuid().toString().contains("00002a37-0000-1000-8000-00805f9b34fb")) {
                    final int charaProp = gatt_characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                        if (read_gatt_characteristic != null) {
                            bluetooth_le_service.setCharacteristicNotification(read_gatt_characteristic, false);
                            read_gatt_characteristic = null;
                        }
                    }
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        read_gatt_characteristic = gatt_characteristic;
                        bluetooth_le_service.setCharacteristicNotification(read_gatt_characteristic, true);
                    }
                }

                if (gatt_characteristic.getUuid().toString().contains("00001525-1212-efde-1523-785feabcd123")) {
                    final int charaProp = gatt_characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                        if (write_gatt_characteristic != null) {
                            write_gatt_characteristic = null;
                        }
                        write_gatt_characteristic = gatt_characteristic;
                    }
                }
            }
        }
    }

    static public byte[] hex_to_bytes(String hex_string) {
        char[] hex = hex_string.toCharArray();
        //轉rawData長度減半
        int length = hex.length / 2;
        byte[] raw_data = new byte[length];
        for (int i = 0; i < length; i++) {
            //先將hex資料轉10進位數值
            int high = Character.digit(hex[i * 2], 16);
            int low = Character.digit(hex[i * 2 + 1], 16);
            //將第一個值的二進位值左平移4位,ex: 00001000 => 10000000 (8=>128)
            //然後與第二個值的二進位值作聯集ex: 10000000 | 00001100 => 10001100 (137)
            int value = (high << 4) | low;
            //與FFFFFFFF作補集
            if (value > 127)
                value -= 256;
            //最後轉回byte就OK
            raw_data[i] = (byte) value;
        }
        return raw_data;
    }

    static void broadcast_event(EventTag event_tag, Object message) {
        synchronized (event_subscribers) {
            for (Subscriber handler : event_subscribers) {
                handler.on_event(event_tag, message);
            }
        }
    }

    static private void logging(String message) {
        Log.i(Constants.log_tag, "[MorSensorManager] " + message);
    }
}
package tw.org.cic.imusensor;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

public class BLEIDA extends Service implements ServiceConnection {
    MainActivity.UIhandler ui_handler;
    String device_addr;
    final Object global_lock = new Object();
    BluetoothLeService bluetooth_le_service;
    HandlerThread handler_thread = new HandlerThread("receiver");
    final GattUpdateReceiver gatt_update_receiver = new GattUpdateReceiver();
    private final IBinder service_binder = new LocalBinder();
    String read_characteristic_uuid;
    String write_characteristic_uuid;
    BluetoothGattCharacteristic read_characteristic;
    BluetoothGattCharacteristic write_characteristic;

    public class LocalBinder extends Binder {
        BLEIDA getService() {
            return BLEIDA.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return service_binder;
    }

    @Override
    public void onCreate() {
        logging("onCreate()");
    }

    @Override
    public void onDestroy () {
        logging("onDestroy()");
        bluetooth_le_service.disconnect();
        this.getApplicationContext().unbindService(this);
        this.unregisterReceiver(gatt_update_receiver);
    }

    public String init (MainActivity.UIhandler ui_handler, String read_characteristic_uuid, String write_characteristic_uuid) {
        this.ui_handler = ui_handler;
        this.read_characteristic_uuid = read_characteristic_uuid;
        this.write_characteristic_uuid = write_characteristic_uuid;

        /* Check if Bluetooth is supported */
        final BluetoothManager bluetoothManager = (BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetooth_adapter = bluetoothManager.getAdapter();
        if (bluetooth_adapter == null) {
            logging("init(): INITIALIZATION_FAILED: Bluetooth not supported");
            ui_handler.send_info("INITIALIZATION_FAILED", "Bluetooth not supported");
            return "";
        }

        /* Check if we are able to scan bluetooth devices*/
        final BluetoothLeScanner bluetooth_le_scanner = bluetooth_adapter.getBluetoothLeScanner();
        if (bluetooth_le_scanner == null) {
            logging("init(): INITIALIZATION_FAILED: Cannot get bluetooth scanner");
            ui_handler.send_info("INITIALIZATION_FAILED", "Cannot get bluetooth scanner");
            return "";
        }

        //Start BluetoothLe Service
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        this.getApplicationContext().bindService(gattServiceIntent, this, Context.BIND_AUTO_CREATE);

        logging("Starting BluetoothLeService...");
        wait_for_lock();
        search(bluetooth_le_scanner);

        if (connect()) {
            return device_addr;
        }
        return "";
    }

    private void search (final BluetoothLeScanner bluetooth_le_scanner) {
        ui_handler.send_info("SEARCH_STARTED");
        bluetooth_le_scanner.startScan(new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                BluetoothDevice device = result.getDevice();
                device_addr = device.getAddress();
                ui_handler.send_info("IDA_DISCOVERED", device_addr);
                logging("Found device: %s", device_addr);
                release_lock();
                bluetooth_le_scanner.stopScan(this);
            }
        });
        logging("scanning...");
        wait_for_lock();
    }

    private boolean connect () {
        ui_handler.send_info("CONNECTING");
        bluetooth_le_service.connect(device_addr);
        logging("connecting...");
        wait_for_lock();
        return true;
    }

    private void wait_for_lock () {
        logging("[Global lock] locked");
        try {
            synchronized (global_lock) {
                global_lock.wait();
            }
        } catch (InterruptedException e) {
            logging("search(): InterruptedException");
        }
    }

    private void release_lock () {
        logging("[Global lock] released");
        synchronized (global_lock) {
            global_lock.notify();
        }
    }


    /* -------------------------------------------------- */
    /* Code for ServiceConnection (to BluetoothLeService) */
    /* ================================================== */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        bluetooth_le_service = ((BluetoothLeService.LocalBinder) service).getService();
        logging("onServiceConnected");
        if (!bluetooth_le_service.initialize()) {
            ui_handler.send_info("INITIALIZATION_FAILED", "Bluetooth service initialization failed");
            bluetooth_le_service = null;
            stopSelf();

        } else {
            logging("Bluetooth service initialized");
            ui_handler.send_info("INITIALIZATION_SUCCEEDED");
            handler_thread.start();

            //Register BluetoothLe Receiver
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
            intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
//            this.registerReceiver(gatt_update_receiver, intentFilter);
            this.registerReceiver(gatt_update_receiver, intentFilter, null, new Handler(handler_thread.getLooper()));
        }

        release_lock();
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        logging("==== onServiceDisconnected ====");
        logging("==== SHOULDn't HAPPENED ====");
        bluetooth_le_service = null;
    }

    class GattUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            logging("Current Thread: %s", Thread.currentThread().getName());
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                logging("==== ACTION_GATT_CONNECTED ====");

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                logging("==== ACTION_GATT_DISCONNECTED ====");
//                if (target_id != null) {
//                    display_info(IDAapi.Event.CONNECTION_FAILED.name(), target_id);
//                    bluetooth_le_service.connect(target_id);
//                }

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");
                get_characteristics();
                ui_handler.send_info(IDAapi.Event.CONNECTION_SUCCEEDED.name(), device_addr);
                release_lock();
//                get_cmd("MORSENSOR_VERSION").run(new JSONArray(), null);
//                get_cmd("FIRMWARE_VERSION").run(new JSONArray(), null);
//                get_cmd("GET_DF_LIST").run(new JSONArray(), null);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
//                byte[] packet = hex_to_bytes(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
//                message_queue.receive(packet);
            }
        }
    }

    void get_characteristics() {
        for (BluetoothGattService gatt_service : this.bluetooth_le_service.getSupportedGattServices()) {
            for (BluetoothGattCharacteristic gatt_characteristic : gatt_service.getCharacteristics()) {
                if (gatt_characteristic.getUuid().toString().contains(read_characteristic_uuid)) {
                    final int charaProp = gatt_characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                        if (read_characteristic != null) {
                            bluetooth_le_service.setCharacteristicNotification(this.read_characteristic, false);
                            read_characteristic = null;
                        }
                    }
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        read_characteristic = gatt_characteristic;
                        bluetooth_le_service.setCharacteristicNotification(this.read_characteristic, true);
                    }
                }

                if (gatt_characteristic.getUuid().toString().contains(write_characteristic_uuid)) {
                    final int charaProp = gatt_characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                        if (write_characteristic != null) {
                            write_characteristic = null;
                        }
                        write_characteristic = gatt_characteristic;
                    }
                }
            }
        }
    }
    /* -------------------------------------------------- */

    static private void logging (String format, Object... args) {
        Log.i("MorSensor", String.format("[BLEIDA] "+ format, args));
    }
}

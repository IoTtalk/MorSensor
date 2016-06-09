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
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class MorSensorIDAapi extends Service implements ServiceConnection, IDAapi {
    /* -------------------------------- */
    /* Code for MorSensorIDAapi Service */
    /* ================================ */
    private final IBinder service_binder = new LocalBinder();
    public class LocalBinder extends Binder {
        MorSensorIDAapi getService() {
            return MorSensorIDAapi.this;
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        return service_binder;
    }

    @Override
    public void onCreate() {
        logging("onCreate()");
        command_sender_thread.start();
    }

    @Override
    public void onDestroy () {
        logging("onDestroy()");
        this.getApplicationContext().unbindService(this);
        this.unregisterReceiver(gatt_update_receiver);

        logging("onDestroy(): cleaning command_sender_thread");
        command_sender_thread.interrupt();
        try {
            logging("onDestroy(): waiting for command_sender_thread.join()");
            command_sender_thread.join();
        } catch (InterruptedException e) {
            logging("onDestroy(): InterruptedException");
        }
        logging("onDestroy(): command_sender_thread cleaned");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }
    /* -------------------------------- */

    /* -------------------------- */
    /* Code for ServiceConnection */
    /* ========================== */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        if (idf_handler_ref == null) {
            logging("idf_handler_ref is null");
            this.getApplicationContext().unbindService(this);
            this.stopSelf();
            return;
        }

        bluetooth_le_service = ((BluetoothLeService.LocalBinder) service).getService();

        if (!bluetooth_le_service.initialize()) {
            send_command_to_dai(Event.INITIALIZATION_FAILED.name(), "Bluetooth service initialization failed");
            bluetooth_le_service = null;

        } else {
            logging("Bluetooth service initialized");
            is_initializing = false;
            send_command_to_dai(Event.INITIALIZATION_SUCCEEDED.name(), "Initialized");

            //Register BluetoothLe Receiver
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
            intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
            this.registerReceiver(gatt_update_receiver, intentFilter);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        logging("==== onServiceDisconnected ====");
        logging("==== SHOULDn't HAPPENED ====");
        bluetooth_le_service = null;
    }
    /* -------------------------- */

    static abstract class Command {
        public Command(String name, int... opcodes) {
            this.name = name;
            this.opcodes = new byte[opcodes.length];
            for (int i = 0; i < opcodes.length; i++) {
                this.opcodes[i] = (byte) opcodes[i];
            }
        }
        public String name;
        public byte[] opcodes;
        abstract public void run(JSONArray args, ByteArrayInputStream reader);
    }

    static String log_tag = MorSensorIDAapi.class.getSimpleName();
    public IDFhandler idf_handler_ref;
    BluetoothLeScanner bluetooth_le_scanner;
    final ScanCallback scan_call_back = new BLEScanCallback();
    boolean is_initializing;
    boolean is_searching;
    final Handler searching_auto_stop_timer = new Handler();
    final MessageQueue command_sender_thread = new MessageQueue();
    BluetoothLeService bluetooth_le_service;
    final GattUpdateReceiver gatt_update_receiver = new GattUpdateReceiver();
    BluetoothGattCharacteristic write_gatt_characteristic;
    BluetoothGattCharacteristic read_gatt_characteristic;
    String target_id = null;
    final HashMap<String, Object> info = new HashMap<>();

    @Override
    public void init(IDFhandler idf_handler_obj, Object... args) {
        idf_handler_ref = idf_handler_obj;

        is_initializing = true;

        init_cmds();
        /* Check if Bluetooth is supported */
        final BluetoothManager bluetoothManager =
                (BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetooth_adapter = bluetoothManager.getAdapter();
        if (bluetooth_adapter == null) {
            logging("init(): INITIALIZATION_FAILED: Bluetooth not supported");
            send_command_to_dai(Event.INITIALIZATION_FAILED.name(), "Bluetooth not supported");
            return;
        }

        bluetooth_le_scanner = bluetooth_adapter.getBluetoothLeScanner();
        if (bluetooth_le_scanner == null) {
            logging("init(): INITIALIZATION_FAILED: Cannot get bluetooth scanner");
            send_command_to_dai(Event.INITIALIZATION_FAILED.name(), "Cannot get bluetooth scanner");
            return;
        }

        //Start BluetoothLe Service
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        this.getApplicationContext().bindService(gattServiceIntent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void search() {
        logging("search()");
        if (is_initializing) {
            logging("service is still initializing");
            return;
        }

        if (is_searching) {
            logging("search(): already searching");
            return;
        }

        // Stops scanning after a pre-defined scan period.
        searching_auto_stop_timer.postDelayed(new Runnable() {
            @Override
            public void run() {
                stop_searching();
            }
        }, Constants.BLUETOOTH_SCANNING_PERIOD);

        is_searching = true;
        bluetooth_le_scanner.startScan(scan_call_back);
        send_command_to_dai(Event.SEARCH_STARTED.name(), "");
    }

    @Override
    public void connect(String id) {
        logging("connect("+ id +")");
        target_id = id;
        stop_searching();
        bluetooth_le_service.connect(target_id);
    }

    @Override
    public void write(String odf, JSONArray data) {
        logging("write(%s)", odf);
        try {
            if (odf.equals("Control")) {
                String command = data.getString(0);
                JSONArray args = data.getJSONObject(1).getJSONArray("args");
                for (Command cmd: cmd_list) {
                    if (command.equals(cmd.name)) {
                        cmd.run(args, null);
                        return;
                    }
                }
                logging("write(%s): Unknown command: %s", odf, command);
                /* Reports the exception to EC */
            } else {
                logging("write(%s): Unknown ODF", odf);
                /* Reports the exception to EC */
            }
        } catch (JSONException e) {
            logging("JSONException in write(%s)", odf);
        }
    }

    @Override
    public void disconnect() {
        logging("disconnect()");
        target_id = null;
        bluetooth_le_service.disconnect();
    }

    private void stop_searching() {
        logging("stop_searching()");

        if (!is_searching) {
            logging("stop_searching(): already stopped");
            return;
        }

        is_searching = false;
        bluetooth_le_scanner.stopScan(scan_call_back);
        send_command_to_dai(Event.SEARCH_STOPPED.name(), "");
    }

    void subscribe (String df_name) {
        //MessageQueue.instance().pend_out(command);
    }

    void unsubscribe (String df_name) {
        //MessageQueue.instance().pend_out(command);
    }

    void resume () {
        //MessageQueue.instance().pend_out(command);
    }

    void suspend () {
        //MessageQueue.instance().pend_out(command);
    }

    void send_command_to_dai (String command, String arg) {
        JSONArray args = new JSONArray();
        args.put(arg);
        send_command_to_dai(command, args);
    }

    void send_command_to_dai (String command, JSONArray args) {
        try {
            JSONArray data = new JSONArray();
            data.put(command);
            JSONObject param2 = new JSONObject();
            param2.put("args", args);
            data.put(param2);
            send_data_to_dai("Control", data);
        } catch (JSONException e) {
            logging("JSONException in send_command_to_dai()");
        }
    }

    void send_data_to_dai (String idf, JSONArray data) {
        if (idf_handler_ref != null) {
            idf_handler_ref.receive(idf, data);
        }
    }

    void send_command_to_morsensor(byte[] command) {
        command_sender_thread.pend_out(command);
    }

    public void put_info(String key, Object value) {
        info.put(key, value);
    }

    public Object get_info(String key) {
        return info.get(key);
    }

    static private void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    static void logging(String message) {
        Log.i(Constants.log_tag, "["+ MorSensorIDAapi.log_tag +"] " + message);
    }
    /* --------------- */


    /**
     * Private Helper Classes
     */

    class BLEScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            JSONArray args = new JSONArray();
            args.put(device.getAddress());
            args.put(device.getName());
            args.put(result.getRssi());
            send_command_to_dai(Event.IDA_DISCOVERED.name(), args);
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
                if (target_id != null) {
                    // accidentally disconnected
                    send_command_to_dai(Event.CONNECTION_FAILED.name(), target_id);
                    bluetooth_le_service.connect(target_id);
                }

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");
                get_characteristics();
                send_command_to_dai(Event.CONNECTION_SUCCEEDED.name(), target_id);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
//                logging("==== ACTION_DATA_AVAILABLE ====");
                byte[] packet = hex_to_bytes(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                command_sender_thread.pend_in(packet);
                handle_morsensor_packet(new ByteArrayInputStream(packet));
            }
        }
    }

    class MessageQueue extends Thread {
        final LinkedBlockingQueue<byte[]> outgoing_queue = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<byte[]> incoming_queue = new LinkedBlockingQueue<>();
        int running_cycle;
        boolean ending;
        int fail_count;

        private MessageQueue() {
            outgoing_queue.clear();
            incoming_queue.clear();
            running_cycle = 0;
            ending = false;
            fail_count = 0;
        }

        public void pend_out(byte[] command) {
            outgoing_queue.add(command);
        }

        public void pend_in(byte[] command) {
            incoming_queue.add(command);
        }

        @Override
        public void run() {
            logging("MessageQueue started");
            try {
                while (!outgoing_queue.isEmpty() || !ending) {
                    if (running_cycle == 0) {
                        // check outgoing_queue every <COMMAND_RESEND_CYCLES> cycles
                        if (!outgoing_queue.isEmpty()) {
                            // something waiting in output_queue, send one out
                            //  but keep it in queue, because it may drop
                            byte[] outgoing_command = outgoing_queue.peek();
                            logging("output_queue is not empty, send command %02X", outgoing_command[0]);
                            write_gatt_characteristic.setValue(outgoing_command);
                            bluetooth_le_service.writeCharacteristic(write_gatt_characteristic);
                        }
                    }

                    // TODO: if target_id == null, which indicates user decided to disconnect,
                    //  take all commands from outgoing_queue

                    byte[] outgoing_command = outgoing_queue.peek();
                    // MorSensor may send data at very high data rate.
                    // To prevent starvation, I check the queue size,
                    //  and only process these commands in the queue.
                    int incoming_queue_size = incoming_queue.size();
                    if (incoming_queue_size != 0) {
                        logging("incoming_queue.size() = " + incoming_queue_size);
                    }
                    for (int i = 0; i < incoming_queue_size; i++) {
                        byte[] incoming_command = incoming_queue.take();
                        if (outgoing_command != null && outgoing_command[0] == incoming_command[0]) {
                            // there is a command pending out, and that command matches its response
                            // pop it from outgoing_queue
                            logging("response %02X received", outgoing_command[0]);
                            outgoing_queue.take();
                            outgoing_command = null;
                        }
                    }

                    if (outgoing_command == null) {
                        fail_count = 0;
                    } else {
                        fail_count += 1;
                    }

                    if (fail_count >= Constants.COMMAND_FAIL_RETRY * Constants.COMMAND_RESEND_CYCLES) {
                        logging("send command failed up to %d times, abort this command", Constants.COMMAND_FAIL_RETRY);
                        outgoing_queue.take();
                        fail_count = 0;
                    }

                    Thread.sleep(Constants.COMMAND_SCANNING_PERIOD);
                    running_cycle = (running_cycle + 1) % Constants.COMMAND_RESEND_CYCLES;
                }
            } catch (InterruptedException e) {
                logging("MessageQueue.run(): InterruptedException");
            }
            logging("MessageQueue ended");
        }
    }


    /**
     * Private Helper Functions
     */

    void get_characteristics() {
        for (BluetoothGattService gatt_service : this.bluetooth_le_service.getSupportedGattServices()) {
            for (BluetoothGattCharacteristic gatt_characteristic : gatt_service.getCharacteristics()) {
                if (gatt_characteristic.getUuid().toString().contains("00002a37-0000-1000-8000-00805f9b34fb")) {
                    final int charaProp = gatt_characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                        if (read_gatt_characteristic != null) {
                            bluetooth_le_service.setCharacteristicNotification(this.read_gatt_characteristic, false);
                            read_gatt_characteristic = null;
                        }
                    }
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        read_gatt_characteristic = gatt_characteristic;
                        bluetooth_le_service.setCharacteristicNotification(this.read_gatt_characteristic, true);
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

    byte[] hex_to_bytes(String hex_string) {
        int len = hex_string.length();
        byte[] ret = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            ret[i / 2] = (byte) ((Character.digit(hex_string.charAt(i), 16) << 4) | Character.digit(hex_string.charAt(i + 1), 16));
        }
        return ret;
    }

    void handle_morsensor_packet (ByteArrayInputStream reader) {
        int packet_opcode = reader.read();
        for (Command cmd: cmd_list) {
            for (byte cmd_opcode: cmd.opcodes) {
                if (cmd_opcode == packet_opcode) {
                    cmd.run(null, reader);
                    return;
                }
            }
        }

        logging("handle_morsensor_packet(): Unknown device_command:");
        for (int i = 0; i < 5; i++) {
            String s = "  ";
            for (int j = 0; j < 4; j++) {
                s += String.format("%02X", reader.read());
            }
            logging(s);
        }
        /* Reports the exception to EC */
    }

    final ArrayList<Command> cmd_list = new ArrayList<>();
    private void add_command (Command... cmd_set) {
        for (Command cmd: cmd_set) {
            cmd_list.add(cmd);
        }
    }

    /* ---------------- */
    /* Network Commands */
    /* ================ */
    private void init_cmds () {
        add_command(
            new Command("DF_STATUS") {
                @Override
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        try {
                            String flags = args.getString(0);
                            logging("DF_STATUS: %s", flags);
                        } catch (JSONException e) {
                            logging("JSONException in DF_STATUS");
                        }
                    } else {

                    }
                }
            },
            new Command("MORSENSOR_VERSION", MorSensorCommand.IN_MORSENSOR_VERSION) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        send_command_to_morsensor(MorSensorCommand.GetMorSensorVersion());
                    } else {
                        int major = reader.read();
                        int minor = reader.read();
                        int patch = reader.read();
                        String morsensor_version = String.format("%d.%d.%d", major, minor, patch);
                        send_command_to_dai("MORSENSOR_VERSION", morsensor_version);
                    }
                }
            },
            new Command("FIRMWARE_VERSION", MorSensorCommand.IN_FIRMWARE_VERSION) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        send_command_to_morsensor(MorSensorCommand.GetFirmwareVersion());
                    } else {
                        int major = reader.read();
                        int minor = reader.read();
                        int patch = reader.read();
                        String firmware_version = String.format("%d.%d.%d", major, minor, patch);
                        send_command_to_dai("FIRMWARE_VERSION", firmware_version);
                    }
                }
            },
            new Command("DF_LIST", MorSensorCommand.IN_SENSOR_LIST) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        send_command_to_morsensor(MorSensorCommand.GetSensorList());
                    } else {
                        JSONArray df_list = new JSONArray();
                        int sensor_count = reader.read();
                        for (int i = 0; i < sensor_count; i++) {
                            byte sensor_id = (byte) reader.read();
                            logging("Sensor %02X:", sensor_id);
                            for (String df_name: Constants.get_df_list(sensor_id)) {
                                df_list.put(df_name);
                            }
                            send_command_to_morsensor(MorSensorCommand.SetSensorStopTransmission(sensor_id));
                            send_command_to_morsensor(MorSensorCommand.SetSensorTransmissionModeContinuous(sensor_id));
                        }
                        send_command_to_dai("DF_LIST", df_list);
                    }
                }
            },
            new Command("", MorSensorCommand.IN_STOP_TRANSMISSION) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                    } else {
                        logging("Transmission stopped: %02X", reader.read());
                    }
                }
            },
            new Command("", MorSensorCommand.IN_SET_TRANSMISSION_MODE) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                    } else {
                        int sensor_id = reader.read();
                        int mode = reader.read();
                        logging("Transmission Mode(%02X): %02X", sensor_id, mode);
                    }
                }
            }
        );
    }
    /* --------------------- */
}
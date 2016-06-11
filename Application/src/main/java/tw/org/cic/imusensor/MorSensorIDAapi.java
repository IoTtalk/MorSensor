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
import android.os.Message;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
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
    }

    @Override
    public void onDestroy () {
        logging("onDestroy()");
        this.getApplicationContext().unbindService(this);
        this.unregisterReceiver(gatt_update_receiver);
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
        bluetooth_le_service = ((BluetoothLeService.LocalBinder) service).getService();

        if (!bluetooth_le_service.initialize()) {
            display_info(Event.INITIALIZATION_FAILED.name(), "Bluetooth service initialization failed");
            bluetooth_le_service = null;

        } else {
            logging("Bluetooth service initialized");
            is_initializing = false;
            display_info(Event.INITIALIZATION_SUCCEEDED.name());

            //Register BluetoothLe Receiver
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
            intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
            this.registerReceiver(gatt_update_receiver, intentFilter);
            this.search();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        logging("==== onServiceDisconnected ====");
        logging("==== SHOULDn't HAPPENED ====");
        bluetooth_le_service = null;
    }
    /* -------------------------- */

    /* ------------------------------- */
    /* Code for MorSensorInfoDisplayer */
    /* =============================== */
    static abstract class AbstactMorSensorInfoDisplayer extends Handler {
        class Information {
            String key;
            Object[] values;
            public Information(String key, Object... values) {
                this.key = key;
                this.values = values;
            }
        }
        final public void input_info (String key, Object... values) {
            Message msg_obj = this.obtainMessage();
            msg_obj.obj = new Information(key, values);
            this.sendMessage(msg_obj);
        }
        @Override
        final public void handleMessage (Message msg) {
            Information info = (Information) msg.obj;
            display(info.key, info.values);
        }
        abstract public void display (String key, Object... values);
    }
    public AbstactMorSensorInfoDisplayer morsensor_info_displayer;
    public void display_info (String key, Object... values) {
        if (morsensor_info_displayer != null) {
            morsensor_info_displayer.input_info(key, values);
        }
    }
    /* ------------------------------- */

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
    BluetoothLeScanner bluetooth_le_scanner;
    final ScanCallback scan_call_back = new BLEScanCallback();
    boolean is_initializing;
    boolean is_searching;
    final Handler searching_auto_stop_timer = new Handler();
    final MessageQueue message_queue = new MessageQueue();
    BluetoothLeService bluetooth_le_service;
    final GattUpdateReceiver gatt_update_receiver = new GattUpdateReceiver();
    BluetoothGattCharacteristic write_gatt_characteristic;
    BluetoothGattCharacteristic read_gatt_characteristic;
    String target_id = null;
    byte[] sensor_list;
    boolean[] selected;
    boolean[] status_responsed;
    boolean suspended;

    @Override
    public void init() {
        is_initializing = true;

        init_cmds();
        /* Check if Bluetooth is supported */
        final BluetoothManager bluetoothManager =
                (BluetoothManager) this.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetooth_adapter = bluetoothManager.getAdapter();
        if (bluetooth_adapter == null) {
            logging("init(): INITIALIZATION_FAILED: Bluetooth not supported");
            display_info(Event.INITIALIZATION_FAILED.name(), "Bluetooth not supported");
            return;
        }

        bluetooth_le_scanner = bluetooth_adapter.getBluetoothLeScanner();
        if (bluetooth_le_scanner == null) {
            logging("init(): INITIALIZATION_FAILED: Cannot get bluetooth scanner");
            display_info(Event.INITIALIZATION_FAILED.name(), "Cannot get bluetooth scanner");
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
        display_info(Event.SEARCH_STARTED.name());
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
            logging("write(%s): JSONException", odf);
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
        display_info(Event.SEARCH_STOPPED.name());
    }

//    void send_command_to_iottalk (String command, String arg) {
//        JSONArray args = new JSONArray();
//        args.put(arg);
//        send_command_to_iottalk(command, args);
//    }
//
//    void send_command_to_iottalk (String command, JSONArray args) {
//        try {
//            JSONArray data = new JSONArray();
//            data.put(command);
//            JSONObject param2 = new JSONObject();
//            param2.put("args", args);
//            data.put(param2);
//            DAN.push("Control", data);
//        } catch (JSONException e) {
//            logging("send_command_to_iottalk(): JSONException");
//        }
//    }

    void send_command_to_morsensor(byte[] command) {
        message_queue.write(command);
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
            display_info(Event.IDA_DISCOVERED.name(), device.getAddress(), device.getName(), result.getRssi());
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
                    display_info(Event.CONNECTION_FAILED.name(), target_id);
                    bluetooth_le_service.connect(target_id);
                }

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");
                get_characteristics();
                display_info(Event.CONNECTION_SUCCEEDED.name(), target_id);
                send_command_to_morsensor(MorSensorCommand.GetMorSensorVersion());
                send_command_to_morsensor(MorSensorCommand.GetFirmwareVersion());
                send_command_to_morsensor(MorSensorCommand.GetSensorList());

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
//                logging("==== ACTION_DATA_AVAILABLE ====");
                byte[] packet = hex_to_bytes(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                message_queue.receive(packet);
            }
        }
    }

    class MessageQueue {
        final LinkedBlockingQueue<byte[]> cmd_queue = new LinkedBlockingQueue<>();
        final Handler timer = new Handler();
        final Runnable timeout_task = new Runnable () {
            @Override
            public void run () {
                logging("Timeout!");
                send_pending_cmd();
            }
        };

        public void write(byte[] packet) {
            logging("MessageQueue.write(%02X)", packet[0]);
            try {
                cmd_queue.put(packet);
                if (cmd_queue.size() == 1) {
                    logging("MessageQueue.write(%02X): got only one command, send it", packet[0]);
                    send_pending_cmd();
                }
            } catch (InterruptedException e) {
                logging("MessageQueue.write(%02X): cmd_queue full", packet[0]);
            }
        }

        public void receive(byte[] packet) {
            logging("MessageQueue.receive(%02X)", packet[0]);
            byte[] pending_cmd = cmd_queue.peek();
            if (pending_cmd != null) {
                if (packet[0] == pending_cmd[0]) {
                    logging("MessageQueue.receive(%02X): match, cancel old timer", packet[0]);
                    try {
                        /* cancel the timer */
                        timer.removeCallbacks(timeout_task);
                        cmd_queue.take();

                        /* if cmd_queue is not empty, send next command */
                        if (!cmd_queue.isEmpty()) {
                            logging("MessageQueue.receive(%02X): send next command", packet[0]);
                            send_pending_cmd();
                        }
                    } catch (InterruptedException e) {
                        logging("MessageQueue.receive(): InterruptedException");
                    }
                }
            }
            handle_morsensor_packet(new ByteArrayInputStream(packet));
        }

        private void send_pending_cmd () {
            byte[] outgoing_cmd = cmd_queue.peek();
            if (outgoing_cmd == null) {
                logging("MessageQueue.send_pending_cmd(): [bug] no pending command");
                return;
            }
            logging("MessageQueue.send_pending_cmd(): send command %02X", outgoing_cmd[0]);
            write_gatt_characteristic.setValue(outgoing_cmd);
            bluetooth_le_service.writeCharacteristic(write_gatt_characteristic);
            logging("MessageQueue.send_pending_cmd(): start timer");
            timer.postDelayed(timeout_task, Constants.COMMAND_TIMEOUT);
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
                            for (int i = 0; i < flags.length(); i++) {
                                if (flags.charAt(i) == '1') {
                                    selected[i] = true;
                                } else {
                                    selected[i] = false;
                                }
                            }
                        } catch (JSONException e) {
                            logging("DF_STATUS: JSONException");
                        }
                    } else {
                    }
                }
            },
            new Command("RESUME") {
                @Override
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        suspended = false;
                    } else {
                    }
                }
            },
            new Command("SUSPEND") {
                @Override
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        suspended = true;
                        for (byte sensor_id: sensor_list) {
                            send_command_to_morsensor(MorSensorCommand.SetSensorStopTransmission(sensor_id));
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
                        display_info("MORSENSOR_VERSION", morsensor_version);
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
                        display_info("FIRMWARE_VERSION", firmware_version);
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
                        sensor_list = new byte[sensor_count];
                        for (int i = 0; i < sensor_count; i++) {
                            byte sensor_id = (byte) reader.read();
                            sensor_list[i] = sensor_id;
                            logging("Found sensor %02X", sensor_id);
                            for (String df_name: Constants.get_df_list(sensor_id)) {
                                df_list.put(df_name);
                            }
                            send_command_to_morsensor(MorSensorCommand.SetSensorStopTransmission(sensor_id));
                            send_command_to_morsensor(MorSensorCommand.SetSensorTransmissionModeContinuous(sensor_id));
                        }
                        display_info("DF_LIST", df_list);
                    }
                }
            },
            new Command("", MorSensorCommand.IN_STOP_TRANSMISSION) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                    } else {
                        logging("Sensor transmission stopped: %02X", reader.read());
                    }
                }
            },
            new Command("", MorSensorCommand.IN_SET_TRANSMISSION_MODE) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                    } else {
                        int sensor_id = reader.read();
                        int mode = reader.read();
                        logging("Sensor transmission Mode(%02X): %d", sensor_id, mode);
                    }
                }
            }
        );
    }
    /* --------------------- */
}
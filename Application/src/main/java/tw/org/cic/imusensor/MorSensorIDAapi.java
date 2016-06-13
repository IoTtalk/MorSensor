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
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import DAN.DAN;

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

    /* -------------------------------------------------- */
    /* Code for ServiceConnection (to BluetoothLeService) */
    /* ================================================== */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        bluetooth_le_service = ((BluetoothLeService.LocalBinder) service).getService();
        if (!bluetooth_le_service.initialize()) {
            display_info(Event.INITIALIZATION_FAILED.name(), "Bluetooth service initialization failed");
            bluetooth_le_service = null;
            stopSelf();

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
    /* -------------------------------------------------- */

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
        abstract public void run (JSONArray args, ByteArrayInputStream reader);
    }

    static abstract class IDFhandler {
        public IDFhandler(int sensor_id) {
            this.sensor_id = (byte) sensor_id;
        }
        public byte sensor_id;
        abstract public void push(ByteArrayInputStream reader);
    }

    static abstract class IDF {
        public IDF (String name) {
            this.name = name;
        }
        public String name;
        abstract public void push(byte[] bytes);
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
    boolean[] status_responded;
    boolean suspended;

    @Override
    public void init() {
        is_initializing = true;
        init_cmds();
        init_idf_handlers();
        init_idfs();

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

    void send_command_to_iottalk (String command, String arg) {
        JSONArray args = new JSONArray();
        args.put(arg);
        send_command_to_iottalk(command, args);
    }

    void send_command_to_iottalk (String command, JSONArray args) {
        try {
            JSONArray data = new JSONArray();
            data.put(command);
            JSONObject param2 = new JSONObject();
            param2.put("args", args);
            data.put(param2);
            DAN.push("Control", data);
        } catch (JSONException e) {
            logging("send_command_to_iottalk(): JSONException");
        }
    }

    void send_command_to_morsensor(byte[] command) {
        send_command_to_morsensor("", command);
    }

    void send_command_to_morsensor(String source, byte[] command) {
        message_queue.write(source, command);
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
                get_command_by_name("MORSENSOR_VERSION").run(null, null);
                get_command_by_name("FIRMWARE_VERSION").run(null, null);
                get_command_by_name("DF_LIST").run(null, null);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
//                logging("==== ACTION_DATA_AVAILABLE ====");
                byte[] packet = hex_to_bytes(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                message_queue.receive(packet);
            }
        }
    }

    class MessageQueue {
        final LinkedBlockingQueue<byte[]> ocmd_queue = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> source_queue = new LinkedBlockingQueue<>();
        final Handler timer = new Handler();
        final Runnable timeout_task = new Runnable () {
            @Override
            public void run () {
                logging("Timeout!");
                send_ocmd();
            }
        };

        public void write(String source, byte[] packet) {
            logging("MessageQueue.write(%02X)", packet[0]);
            try {
                ocmd_queue.put(packet);
                source_queue.put(source);
                if (ocmd_queue.size() == 1) {
                    logging("MessageQueue.write(%02X): got only one command, send it", packet[0]);
                    send_ocmd();
                }
            } catch (InterruptedException e) {
                logging("MessageQueue.write(%02X): ocmd_queue full", packet[0]);
            }
        }

        public void receive(byte[] icmd) {
            logging("MessageQueue.push(%02X)", icmd[0]);
            byte[] ocmd = ocmd_queue.peek();
            String source = source_queue.peek();
            if (source == null) {
                source = "";
            }
            if (ocmd != null) {
                if (opcode_match(ocmd, icmd)) {
                    logging("MessageQueue.push(%02X): match, cancel old timer", icmd[0]);
                    try {
                        /* cancel the timer */
                        timer.removeCallbacks(timeout_task);
                        ocmd_queue.take();
                        source_queue.take();

                        /* if ocmd_queue is not empty, send next command */
                        if (!ocmd_queue.isEmpty()) {
                            logging("MessageQueue.push(%02X): send next command", icmd[0]);
                            send_ocmd();
                        }
                    } catch (InterruptedException e) {
                        logging("MessageQueue.push(): InterruptedException");
                    }
                }
            }

            int i_opcode = icmd[0];
            for (Command cmd: cmd_list) {
                for (byte cmd_opcode: cmd.opcodes) {
                    if (cmd_opcode == i_opcode) {
                        if (source.equals(cmd.name)) {
                            ByteArrayInputStream reader = new ByteArrayInputStream(icmd);
                            cmd.run(null, reader);
                            try {
                                reader.close();
                            } catch (IOException e) {
                                logging("MessageQueue.push(%02X): IOException", icmd[0]);
                            }
                            return;
                        }
                    }
                }
            }

            logging("MessageQueue.push(%02X): Unknown MorSensor command:", i_opcode);
            for (int i = 0; i < 5; i++) {
                String s = "    ";
                for (int j = 0; j < 4; j++) {
                    s += String.format("%02X", icmd[i * 4 + j]);
                }
                logging(s);
            }
            /* Reports the exception to EC */
        }

        private void send_ocmd() {
            byte[] ocmd = ocmd_queue.peek();
            if (ocmd == null) {
                logging("MessageQueue.send_ocmd(): [bug] ocmd not exists");
                return;
            }
            logging("MessageQueue.send_ocmd(): send ocmd %02X", ocmd[0]);
            write_gatt_characteristic.setValue(ocmd);
            bluetooth_le_service.writeCharacteristic(write_gatt_characteristic);
            logging("MessageQueue.send_ocmd(): start timer");
            timer.postDelayed(timeout_task, Constants.COMMAND_TIMEOUT);
        }

        private boolean opcode_match (byte[] ocmd, byte[] icmd) {
            if (ocmd[0] == icmd[0]) {
                if (ocmd[0] == MorSensorCommandTable.IN_SENSOR_DATA) {
                    return ocmd[1] == icmd[1];
                }
                return true;
            }
            return false;
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

    final ArrayList<Command> cmd_list = new ArrayList<>();
    private void add_commands(Command... cmds) {
        for (Command cmd: cmds) {
            cmd_list.add(cmd);
        }
    }

    Command get_command_by_name (String name) {
        for (Command cmd: cmd_list) {
            if (name.equals(cmd.name)) {
                return cmd;
            }
        }
        return null;
    }

    final ArrayList<IDFhandler> idf_handler_list = new ArrayList<>();
    private void add_idf_handlers(IDFhandler... idf_handlers) {
        for (IDFhandler idf_handler: idf_handlers) {
            idf_handler_list.add(idf_handler);
        }
    }

    final ArrayList<IDF> idf_list = new ArrayList<>();
    private void add_idfs(IDF... idfs) {
        for (IDF idf: idfs) {
            idf_list.add(idf);
        }
    }

    IDF get_idf_by_name(String name) {
        for (IDF idf: idf_list) {
            if (name.equals(idf.name)) {
                return idf;
            }
        }
        return null;
    }

    /* -------- */
    /* Commands */
    /* ======== */
    private void init_cmds () {
        add_commands(
            new Command("DF_STATUS") {
                @Override
                public void run(JSONArray args, ByteArrayInputStream reader) {
                }
            },
            new Command("RESUME", MorSensorCommandTable.IN_SENSOR_DATA) {
                @Override
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        suspended = false;
                        for (byte sensor_id: sensor_list) {
                            send_command_to_morsensor("RESUME", MorSensorCommandTable.RetrieveSensorData(sensor_id));
                        }
                    } else {
                        byte opcode = (byte) reader.read();
                        byte sensor_id = (byte) reader.read();
                        logging("RESUME response from %02X", sensor_id);
                    }
                }
            },
            new Command("SUSPEND", MorSensorCommandTable.IN_STOP_TRANSMISSION) {
                @Override
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        suspended = true;
                        for (byte sensor_id: sensor_list) {
                            send_command_to_morsensor("SUSPEND", MorSensorCommandTable.SetSensorStopTransmission(sensor_id));
                        }
                    } else {
                        byte opcode = (byte) reader.read();
                        logging("Sensor transmission stopped: %02X", reader.read());
                    }
                }
            },
            new Command("MORSENSOR_VERSION", MorSensorCommandTable.IN_MORSENSOR_VERSION) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        send_command_to_morsensor("MORSENSOR_VERSION", MorSensorCommandTable.GetMorSensorVersion());
                    } else {
                        byte opcode = (byte) reader.read();
                        int major = reader.read();
                        int minor = reader.read();
                        int patch = reader.read();
                        String morsensor_version = String.format("%d.%d.%d", major, minor, patch);
                        display_info("MORSENSOR_VERSION", morsensor_version);
                    }
                }
            },
            new Command("FIRMWARE_VERSION", MorSensorCommandTable.IN_FIRMWARE_VERSION) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        send_command_to_morsensor("FIRMWARE_VERSION", MorSensorCommandTable.GetFirmwareVersion());
                    } else {
                        byte opcode = (byte) reader.read();
                        int major = reader.read();
                        int minor = reader.read();
                        int patch = reader.read();
                        String firmware_version = String.format("%d.%d.%d", major, minor, patch);
                        display_info("FIRMWARE_VERSION", firmware_version);
                    }
                }
            },
            new Command("DF_LIST", MorSensorCommandTable.IN_SENSOR_LIST) {
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                        send_command_to_morsensor("DF_LIST", MorSensorCommandTable.GetSensorList());
                    } else {
                        byte opcode = (byte) reader.read();
                        int sensor_count = reader.read();
                        JSONArray df_list = new JSONArray();
                        sensor_list = new byte[sensor_count];
                        selected = new boolean[sensor_count];
                        status_responded = new boolean[sensor_count];
                        for (int i = 0; i < sensor_count; i++) {
                            byte sensor_id = (byte) reader.read();
                            sensor_list[i] = sensor_id;
                            logging("Found sensor %02X", sensor_id);
                            for (String df_name: Constants.get_df_list(sensor_id)) {
                                df_list.put(df_name);
                            }
                            send_command_to_morsensor(MorSensorCommandTable.SetSensorStopTransmission(sensor_id));
                            send_command_to_morsensor(MorSensorCommandTable.SetSensorTransmissionModeContinuous(sensor_id));
                        }
                        display_info("DF_LIST", df_list);
                    }
                }
            },
            new Command("", MorSensorCommandTable.IN_SENSOR_DATA) {
                @Override
                public void run(JSONArray args, ByteArrayInputStream reader) {
                    if (reader == null) {
                    } else {
                        if (!DAN.session_status()) {
                            return;
                        }
                        byte opcode = (byte) reader.read();
                        byte sensor_id = (byte) reader.read();
                        logging("Sensor data from %02X", sensor_id);
                        for (IDFhandler sensor_handler: idf_handler_list) {
                            if (sensor_id == sensor_handler.sensor_id) {
                                sensor_handler.push(reader);
                            }
                        }
                    }
                }
            }
        );
    }
    /* -------- */

    private void init_idf_handlers() {
        add_idf_handlers(
            new IDFhandler(0xD0) {
                @Override
                public void push(ByteArrayInputStream reader) {
                    byte[] bytes = new byte[6];
                    reader.read(bytes, 0, 6);
                    get_idf_by_name("Gyroscope").push(bytes);

                    reader.read(bytes, 0, 6);
                    get_idf_by_name("Acceleration").push(bytes);

                    reader.read(bytes, 0, 6);
                    get_idf_by_name("Magnetometer").push(bytes);
                }
            },
            new IDFhandler(0xC0) {
                @Override
                public void push(ByteArrayInputStream reader) {
                    byte[] bytes = new byte[2];
                    reader.read(bytes, 0, 2);
                    get_idf_by_name("UV").push(bytes);
                }
            },
            new IDFhandler(0x80) {
                @Override
                public void push(ByteArrayInputStream reader) {
                    byte[] bytes = new byte[2];
                    reader.read(bytes, 0, 2);
                    get_idf_by_name("Temperature").push(bytes);

                    reader.read(bytes, 0, 2);
                    get_idf_by_name("Humidity").push(bytes);
                }
            }
        );
    }

    private void init_idfs () {
        add_idfs(
            new IDF("Gyroscope") {
                @Override
                public void push(byte[] bytes) {
                    final float gyro_x = (float) (((short)bytes[0] * 256 + (short)bytes[1]) / 32.8);
                    final float gyro_y = (float) (((short)bytes[2] * 256 + (short)bytes[3]) / 32.8);
                    final float gyro_z = (float) (((short)bytes[4] * 256 + (short)bytes[5]) / 32.8);
                    try {
                        JSONArray data = new JSONArray();
                        data.put(gyro_x);
                        data.put(gyro_y);
                        data.put(gyro_z);
                        DAN.push("Gyroscope", data);
                        logging("push(Gyroscope, %s)", data);
                    } catch (JSONException e) {
                        logging("push(Gyroscope): JSONException");
                    }
                }
            },
            new IDF("Acceleration") {
                @Override
                public void push(byte[] bytes) {
                    try {
                        JSONArray data = new JSONArray();
                        data.put((float) (((short)bytes[0] * 256 + (short)bytes[1]) / 4096.0) * (float) 9.8);
                        data.put((float) (((short)bytes[2] * 256 + (short)bytes[3]) / 4096.0) * (float) 9.8);
                        data.put((float) (((short)bytes[4] * 256 + (short)bytes[5]) / 4096.0) * (float) 9.8);
                        DAN.push("Acceleration", data);
                        logging("push(Acceleration, %s)", data);
                    } catch (JSONException e) {
                        logging("push(Acceleration): JSONException");
                    }
                }
            },
            new IDF("Magnetometer") {
                @Override
                public void push(byte[] bytes) {
                    try {
                        JSONArray data = new JSONArray();
                        data.put((float) (((short)bytes[0] * 256 + (short)bytes[1]) / 3.41 / 100));
                        data.put((float) (((short)bytes[2] * 256 + (short)bytes[3]) / 3.41 / 100));
                        data.put((float) (((short)bytes[4] * 256 + (short)bytes[5]) / 3.41 /-100));
                        DAN.push("Magnetometer", data);
                        logging("push(Magnetometer, %s)", data);
                    } catch (JSONException e) {
                        logging("push(Magnetometer): JSONException");
                    }
                }
            },
            new IDF("UV") {
                @Override
                public void push(byte[] bytes) {
                    final float uv_data = (float) (bytes[1] * 256 + (bytes[0]) / 100.0);
                    DAN.push("UV", new float[]{uv_data});
                    logging("push(UV, [%f])", uv_data);
                }
            },
            new IDF("Temperature") {
                @Override
                public void push(byte[] bytes) {
                    final float temperature = (float) ((bytes[0] * 256 + bytes[1]) * 175.72 / 65536.0 - 46.85);
                    DAN.push("Temperature", new float[]{temperature});
                    logging("push(Temperature, [%f])", temperature);
                }
            },
            new IDF("Humidity") {
                @Override
                public void push(byte[] bytes) {
                    final float humidity = (float) ((bytes[0] * 256 + bytes[1]) * 125.0 / 65536.0 - 6.0);
                    DAN.push("Humidity", new float[]{humidity});
                    logging("push(Humidity, [%f])", humidity);
                }
            }
        );
    }
}
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
import android.os.Message;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import CSMAPI.CSMAPI;
import DAN.DAN;

public class MorSensorIDAapi extends Service implements ServiceConnection, IDAapi {
    static abstract class IDFhandler {
        public IDFhandler(int sensor_id, String... df_list) {
            this.sensor_id = (byte) sensor_id;
            this.df_list = df_list;
        }
        public byte sensor_id;
        public String[] df_list;
        abstract public void push(ByteArrayInputStream ul_cmd_params);
    }

    static abstract class IDF {
        public IDF (String name) {
            this.name = name;
        }
        public String name;
        public boolean selected;
        abstract public void push(byte[] bytes);
    }

    static abstract class ODF {
        public ODF (String name) {
            this.name = name;
        }
        public String name;
        public boolean selected;
        abstract public void pull(JSONArray data);
    }

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
        abstract public void run (JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params);
    }

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
        global_information.df_list = null;
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
    HandlerThread handler_thread = new HandlerThread("receiver");
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

            handler_thread.start();

            //Register BluetoothLe Receiver
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
            intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
            intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
//            this.registerReceiver(gatt_update_receiver, intentFilter);
            this.registerReceiver(gatt_update_receiver, intentFilter, null, new Handler(handler_thread.getLooper()));
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

    static String log_tag = MorSensorIDAapi.class.getSimpleName();
    BluetoothLeScanner bluetooth_le_scanner;
    final ScanCallback scan_callback = new BLEScanCallback();
    boolean is_initializing;
    boolean is_searching;
    final Handler searching_auto_stop_timer = new Handler();
    final MessageQueue message_queue = new MessageQueue();
    BluetoothLeService bluetooth_le_service;
    final GattUpdateReceiver gatt_update_receiver = new GattUpdateReceiver();
    BluetoothGattCharacteristic write_gatt_characteristic;
    BluetoothGattCharacteristic read_gatt_characteristic;
    String target_id = null;

    MorSensorApplication global_information;
    byte[] sensor_list;
    boolean[] sensor_activate;
    boolean[] sensor_responded;
    boolean suspended = true;

    @Override
    public void init() {
        is_initializing = true;
        init_cmds();
        init_idf_handlers();
        init_idfs();
        global_information = (MorSensorApplication)getApplication();

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
        bluetooth_le_scanner.startScan(scan_callback);
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
            if (odf.equals("__Ctl_O__")) {
                String cmd_name = data.getString(0);
                JSONArray dl_cmd_params = data.getJSONObject(1).getJSONArray("cmd_params");
                for (Command cmd: cmd_list) {
                    if (cmd_name.equals(cmd.name)) {
                        cmd.run(dl_cmd_params, null);
                        return;
                    }
                }
                logging("write(%s): Unknown cmd: %s", odf, cmd_name);
                send_cmd_to_iottalk("UNKNOWN_CMD", cmd_name);
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
        bluetooth_le_scanner.stopScan(scan_callback);
        display_info(Event.SEARCH_STOPPED.name());
    }

    void send_cmd_to_iottalk(String cmd, String cmd_param) {
        JSONArray cmd_params = new JSONArray();
        cmd_params.put(cmd_param);
        send_cmd_to_iottalk(cmd, cmd_params);
    }

    void send_cmd_to_iottalk(final String cmd, final JSONArray cmd_params) {
        try {
            DAN.push("__Ctl_I__", new JSONArray(){{
                put(cmd);
                put(new JSONObject(){{
                    put("cmd_params", cmd_params);
                }});
            }});
        } catch (JSONException e) {
            logging("send_cmd_to_iottalk(): JSONException");
        }
    }

    void send_cmd_to_morsensor(byte[] cmd) {
        send_cmd_to_morsensor("", cmd);
    }

    void send_cmd_to_morsensor(String source, byte[] cmd) {
        message_queue.write(source, cmd);
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
            logging("Current Thread: %s", Thread.currentThread().getName());
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                logging("==== ACTION_GATT_CONNECTED ====");

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                logging("==== ACTION_GATT_DISCONNECTED ====");
                if (target_id != null) {
                    // accidentally disconnected
//                    if (retry count >= N) {
//                         retry a few times before alert
                        display_info(Event.CONNECTION_FAILED.name(), target_id);
//                    }
                    bluetooth_le_service.connect(target_id);
                }

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");
                get_characteristics();
                display_info(Event.CONNECTION_SUCCEEDED.name(), target_id);
                get_cmd("MORSENSOR_VERSION").run(new JSONArray(), null);
                get_cmd("FIRMWARE_VERSION").run(new JSONArray(), null);
                get_cmd("GET_DF_LIST").run(new JSONArray(), null);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
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
            logging("MessageQueue.write('%s', %02X)", source, packet[0]);
            try {
                source_queue.put(source);
                ocmd_queue.put(packet);
                if (ocmd_queue.size() == 1) {
                    logging("MessageQueue.write(%02X): got only one command, send it", packet[0]);
                    send_ocmd();
                }
            } catch (InterruptedException e) {
                logging("MessageQueue.write(%02X): ocmd_queue full", packet[0]);
            }
        }

        public void receive(byte[] icmd) {
            logging("MessageQueue.receive(%02X)", icmd[0]);
            byte[] ocmd = ocmd_queue.peek();
            String source = source_queue.peek();
            if (source == null) {
                source = "";
            }
            if (ocmd != null) {
                if (opcode_match(ocmd, icmd)) {
                    logging("MessageQueue.receive(%02X): match, cancel old timer", icmd[0]);
                    try {
                        /* cancel the timer */
                        timer.removeCallbacks(timeout_task);
                        source_queue.take();
                        ocmd_queue.take();

                        /* if ocmd_queue is not empty, send next command */
                        if (!ocmd_queue.isEmpty()) {
                            logging("MessageQueue.receive(%02X): send next command", icmd[0]);
                            send_ocmd();
                        }
                    } catch (InterruptedException e) {
                        logging("MessageQueue.receive(): InterruptedException");
                    }
                }
            }

            byte i_opcode = icmd[0];
            for (Command cmd: cmd_list) {
                for (byte cmd_opcode: cmd.opcodes) {
                    if (cmd_opcode == i_opcode && source.equals(cmd.name)) {
                        ByteArrayInputStream ul_cmd_params = new ByteArrayInputStream(icmd);
                        cmd.run(null, ul_cmd_params);
                        try {
                            ul_cmd_params.close();
                        } catch (IOException e) {
                            logging("MessageQueue.receive(%02X): IOException", icmd[0]);
                        }
                        return;
                    }
                }
            }

            logging("MessageQueue.receive(%02X): Unknown MorSensor command:", i_opcode);
            for (int i = 0; i < 5; i++) {
                String s = "    ";
                for (int j = 0; j < 4; j++) {
                    s += String.format("%02X ", icmd[i * 4 + j]);
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


    /* ------------------------ */
    /* Private Helper Functions */
    /* ======================== */
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

    boolean all (boolean[] array) {
        for (boolean b: array) {
            if (!b) {
                return false;
            }
        }
        return true;
    }
    /* ------------------------ */

    /* ---- */
    /* IDFs */
    /* ==== */
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

    IDFhandler get_idf_handler (byte sensor_id) {
        for (IDFhandler idf_handler: idf_handler_list) {
            if (sensor_id == idf_handler.sensor_id) {
                return idf_handler;
            }
        }
        return null;
    }

    IDF get_idf(String name) {
        for (IDF idf: idf_list) {
            if (name.equals(idf.name)) {
                return idf;
            }
        }
        logging("get_idf(%s): null", name);
        return null;
    }

    private void init_idf_handlers() {
        add_idf_handlers(
            new IDFhandler(0xD0, "Gyroscope", "Acceleration", "Magnetometer") {
                @Override
                public void push(ByteArrayInputStream ul_cmd_params) {
                    byte[] bytes = new byte[6];
                    ul_cmd_params.read(bytes, 0, 6);
                    IDF gyro_idf = get_idf("Gyroscope");
                    if (gyro_idf.selected) {
                        gyro_idf.push(bytes);
                    }

                    ul_cmd_params.read(bytes, 0, 6);
                    IDF acc_idf = get_idf("Acceleration");
                    if (acc_idf.selected) {
                        acc_idf.push(bytes);
                    }

                    ul_cmd_params.read(bytes, 0, 6);
                    IDF mag_idf = get_idf("Magnetometer");
                    if (mag_idf.selected) {
                        mag_idf.push(bytes);
                    }
                }
            },
            new IDFhandler(0xC0, "UV") {
                @Override
                public void push(ByteArrayInputStream ul_cmd_params) {
                    byte[] bytes = new byte[2];
                    ul_cmd_params.read(bytes, 0, 2);
                    IDF uv_idf = get_idf("UV");
                    if (uv_idf.selected) {
                        uv_idf.push(bytes);
                    }
                }
            },
            new IDFhandler(0x80, "Temperature", "Humidity") {
                @Override
                public void push(ByteArrayInputStream ul_cmd_params) {
                    byte[] bytes = new byte[2];
                    ul_cmd_params.read(bytes, 0, 2);
                    IDF temp_idf = get_idf("Temperature");
                    if (temp_idf.selected) {
                        temp_idf.push(bytes);
                    }

                    ul_cmd_params.read(bytes, 0, 2);
                    IDF hum_idf = get_idf("Humidity");
                    if (hum_idf.selected) {
                        hum_idf.push(bytes);
                    }
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
                        final JSONArray data = new JSONArray();
                        data.put(gyro_x);
                        data.put(gyro_y);
                        data.put(gyro_z);
//                        DAN.push("Gyroscope", data);
                        CSMAPI.push("D29C596FDACF", "Gyroscope", new JSONObject(){{
                            put("data", data);
                        }});
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
                        final JSONArray data = new JSONArray();
                        data.put((float) (((short)bytes[0] * 256 + (short)bytes[1]) / 4096.0) * (float) 9.8);
                        data.put((float) (((short)bytes[2] * 256 + (short)bytes[3]) / 4096.0) * (float) 9.8);
                        data.put((float) (((short)bytes[4] * 256 + (short)bytes[5]) / 4096.0) * (float) 9.8);
                        CSMAPI.push("D29C596FDACF", "Acceleration", new JSONObject(){{
                            put("data", data);
                        }});
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
                        final JSONArray data = new JSONArray();
                        data.put((float) (((short)bytes[0] * 256 + (short)bytes[1]) / 3.41 / 100));
                        data.put((float) (((short)bytes[2] * 256 + (short)bytes[3]) / 3.41 / 100));
                        data.put((float) (((short)bytes[4] * 256 + (short)bytes[5]) / 3.41 /-100));
//                        DAN.push("Magnetometer", data);
                        CSMAPI.push("D29C596FDACF", "Magnetometer", new JSONObject(){{
                            put("data", data);
                        }});
                        logging("push(Magnetometer, %s)", data);
                    } catch (JSONException e) {
                        logging("push(Magnetometer): JSONException");
                    }
                }
            },
            new IDF("UV") {
                @Override
                public void push(byte[] bytes) {
                    try {
                        final float uv_data = (float) (bytes[1] * 256 + (bytes[0]) / 100.0);
    //                    DAN.push("UV", new float[]{uv_data});
                        CSMAPI.push("D29C596FDACF", "UV", new JSONObject(){{
                                put("data", new JSONArray(){{
                                    put(uv_data);
                                }});
                        }});
                        logging("push(UV, [%f])", uv_data);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            },
            new IDF("Temperature") {
                @Override
                public void push(byte[] bytes) {
                    try {
                        final float temperature = (float) ((bytes[0] * 256 + bytes[1]) * 175.72 / 65536.0 - 46.85);
    //                    DAN.push("Temperature", new float[]{temperature});
                        CSMAPI.push("D29C596FDACF", "Temperature", new JSONObject(){{
                            put("data", new JSONArray(){{
                                put(temperature);
                            }});
                        }});
                        logging("push(Temperature, [%f])", temperature);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            },
            new IDF("Humidity") {
                @Override
                public void push(byte[] bytes) {
                    try {
                        final float humidity = (float) ((bytes[0] * 256 + bytes[1]) * 125.0 / 65536.0 - 6.0);
    //                    DAN.push("Humidity", new float[]{humidity});
                        CSMAPI.push("D29C596FDACF", "Humidity", new JSONObject(){{
                            put("data", new JSONArray(){{
                                put(humidity);
                            }});
                        }});
                        logging("push(Humidity, [%f])", humidity);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        );
    }
    /* ---- */

    /* -------- */
    /* Commands */
    /* ======== */
    final ArrayList<Command> cmd_list = new ArrayList<>();
    private void add_cmds(Command... cmds) {
        for (Command cmd: cmds) {
            cmd_list.add(cmd);
        }
    }

    Command get_cmd(String name) {
        for (Command cmd: cmd_list) {
            if (name.equals(cmd.name)) {
                return cmd;
            }
        }
        return null;
    }

    private void init_cmds () {
        add_cmds(
            new Command("SET_DF_STATUS", MorSensorCommandTable.IN_SENSOR_DATA, MorSensorCommandTable.IN_STOP_TRANSMISSION) {
                @Override
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        try {
                            String flags = dl_cmd_params.getString(0);
                            for (int i = 0; i < flags.length(); i++) {
                                String df_name = global_information.df_list.getString(i);
                                IDF idf = get_idf(df_name);
                                if (idf == null) {
                                    continue;
                                }
                                if (flags.charAt(i) == '0') {
                                    idf.selected = false;
                                } else {
                                    idf.selected = true;
                                }
                            }

                            for (int i = 0; i < sensor_list.length; i++) {
                                IDFhandler idf_handler = get_idf_handler(sensor_list[i]);
                                boolean any_selected = false;
                                for (String df_name : idf_handler.df_list) {
                                    IDF idf = get_idf(df_name);
                                    any_selected |= idf.selected;
                                }
                                if (any_selected != sensor_activate[i]) {
                                    sensor_activate[i] = any_selected;
                                    sensor_responded[i] = false;
                                    if (!suspended) {
                                        if (any_selected) {
                                            send_cmd_to_morsensor("SET_DF_STATUS", MorSensorCommandTable.RetrieveSensorData(idf_handler.sensor_id));
                                        } else {
                                            send_cmd_to_morsensor("SET_DF_STATUS", MorSensorCommandTable.SetSensorStopTransmission(idf_handler.sensor_id));
                                        }
                                    }
                                } else {
                                    sensor_responded[i] = true;
                                }
                            }
                            if (suspended || all(sensor_responded)) {
                                send_cmd_to_iottalk("SET_DF_STATUS_RSP", flags);
                            }
                        } catch (JSONException e) {
                            logging("SET_DF_STATUS: JSONException");
                        }
                    } else {
                        try {
                            byte opcode = (byte) ul_cmd_params.read();
                            byte sensor_id = (byte) ul_cmd_params.read();
                            logging("SET_DF_STATUS_RSP: %02X %02X", opcode, sensor_id);
                            for (int i = 0; i < sensor_list.length; i++) {
                                if (sensor_id == sensor_list[i]) {
                                    if (sensor_activate[i] && opcode == MorSensorCommandTable.IN_SENSOR_DATA) {
                                        sensor_responded[i] = true;
                                    } else if (!sensor_activate[i] && opcode == MorSensorCommandTable.IN_STOP_TRANSMISSION) {
                                        sensor_responded[i] = true;
                                    }
                                    break;
                                }
                            }
                            if (all(sensor_responded)) {
                                String flags = "";
                                for (int i = 0; i < global_information.df_list.length(); i++) {
                                    IDF idf = get_idf(global_information.df_list.getString(i));
                                    if (idf == null) { // wait for DANapi
                                        continue;
                                    }
                                    if (idf.selected) {
                                        flags += "1";
                                    } else {
                                        flags += "0";
                                    }
                                }
                                send_cmd_to_iottalk("SET_DF_STATUS_RSP", flags);
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                }
            },
            new Command("RESUME", MorSensorCommandTable.IN_SENSOR_DATA) {
                @Override
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        suspended = false;
                        for (int i = 0; i < sensor_list.length; i++) {
                            if (sensor_activate[i]) {
                                sensor_responded[i] = false;
                                send_cmd_to_morsensor("RESUME", MorSensorCommandTable.RetrieveSensorData(sensor_list[i]));
                            } else {
                                sensor_responded[i] = true;
                            }
                        }
                        if (all(sensor_responded)) {
                            send_cmd_to_iottalk("RESUME_RSP", "OK");
                        }
                    } else {
                        byte opcode = (byte) ul_cmd_params.read();
                        byte sensor_id = (byte) ul_cmd_params.read();
                        logging("RESUME_RSP: %02X %02X", opcode, sensor_id);
                        for (int i = 0; i < sensor_list.length; i++) {
                            if (sensor_id == sensor_list[i]) {
                                if (sensor_activate[i] && opcode == MorSensorCommandTable.IN_SENSOR_DATA) {
                                    sensor_responded[i] = true;
                                }
                                break;
                            }
                        }
                        if (all(sensor_responded)) {
                            send_cmd_to_iottalk("RESUME_RSP", "OK");
                        }
                    }
                }
            },
            new Command("SUSPEND", MorSensorCommandTable.IN_STOP_TRANSMISSION) {
                @Override
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        suspended = true;
                        for (int i = 0; i < sensor_list.length; i++) {
                            if (sensor_activate[i]) {
                                sensor_responded[i] = false;
                                send_cmd_to_morsensor("SUSPEND", MorSensorCommandTable.SetSensorStopTransmission(sensor_list[i]));
                            } else {
                                sensor_responded[i] = true;
                            }
                        }
                        if (all(sensor_responded)) {
                            send_cmd_to_iottalk("SUSPEND_RSP", "OK");
                        }
                    } else {
                        byte opcode = (byte) ul_cmd_params.read();
                        byte sensor_id = (byte) ul_cmd_params.read();
                        for (int i = 0; i < sensor_list.length; i++) {
                            if (sensor_id == sensor_list[i]) {
                                if (sensor_activate[i] && opcode == MorSensorCommandTable.IN_STOP_TRANSMISSION) {
                                    sensor_responded[i] = true;
                                }
                                break;
                            }
                        }
                        if (all(sensor_responded)) {
                            send_cmd_to_iottalk("SUSPEND_RSP", "OK");
                        }
                    }
                }
            },
            new Command("MORSENSOR_VERSION", MorSensorCommandTable.IN_MORSENSOR_VERSION) {
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        send_cmd_to_morsensor("MORSENSOR_VERSION", MorSensorCommandTable.GetMorSensorVersion());
                    } else {
                        byte opcode = (byte) ul_cmd_params.read();
                        int major = ul_cmd_params.read();
                        int minor = ul_cmd_params.read();
                        int patch = ul_cmd_params.read();
                        String morsensor_version = String.format("%d.%d.%d", major, minor, patch);
                        global_information.morsensor_version = morsensor_version;
                        display_info("MORSENSOR_VERSION", morsensor_version);
                    }
                }
            },
            new Command("FIRMWARE_VERSION", MorSensorCommandTable.IN_FIRMWARE_VERSION) {
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        send_cmd_to_morsensor("FIRMWARE_VERSION", MorSensorCommandTable.GetFirmwareVersion());
                    } else {
                        byte opcode = (byte) ul_cmd_params.read();
                        int major = ul_cmd_params.read();
                        int minor = ul_cmd_params.read();
                        int patch = ul_cmd_params.read();
                        String firmware_version = String.format("%d.%d.%d", major, minor, patch);
                        global_information.firmware_version = firmware_version;
                        display_info("FIRMWARE_VERSION", firmware_version);
                    }
                }
            },
            new Command("GET_DF_LIST", MorSensorCommandTable.IN_SENSOR_LIST) {
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        send_cmd_to_morsensor("GET_DF_LIST", MorSensorCommandTable.GetSensorList());
                    } else {
                        byte opcode = (byte) ul_cmd_params.read();
                        int sensor_count = ul_cmd_params.read();
                        JSONArray df_list = new JSONArray();
                        sensor_list = new byte[sensor_count];
                        for (int i = 0; i < sensor_count; i++) {
                            byte sensor_id = (byte) ul_cmd_params.read();
                            sensor_list[i] = sensor_id;
                            logging("Found sensor %02X", sensor_id);
                            for (String df_name: get_idf_handler(sensor_id).df_list) {
                                df_list.put(df_name);
                            }
                            send_cmd_to_morsensor(MorSensorCommandTable.SetSensorStopTransmission(sensor_id));
                            send_cmd_to_morsensor(MorSensorCommandTable.SetSensorTransmissionModeContinuous(sensor_id));
                        }
                        df_list.put("__Ctl_I__");
                        df_list.put("__Ctl_O__");
                        logging(df_list.toString());
                        if (global_information.df_list == null) {
                            global_information.df_list = df_list;
                            sensor_activate = new boolean[sensor_count];
                            sensor_responded = new boolean[sensor_count];
                        } else {
                            get_cmd("RESUME").run(new JSONArray(), null);
                        }
                        display_info("MORSENSOR_OK");
                    }
                }
            },
            new Command("", MorSensorCommandTable.IN_SENSOR_DATA) {
                @Override
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                    } else {
                        if (!DAN.session_status()) {
                            return;
                        }
                        byte opcode = (byte) ul_cmd_params.read();
                        byte sensor_id = (byte) ul_cmd_params.read();
                        logging("Sensor data from %02X", sensor_id);
                        get_idf_handler(sensor_id).push(ul_cmd_params);
                    }
                }
            }
        );
    }
    /* -------- */
}
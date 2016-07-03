package tw.org.cic.imusensor;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;

public class DAI extends Thread implements DAN.DAN2DAI, BLEIDA.IDA2DAI {
    BLEIDA ble_ida;
    DAN dan;
    MainActivity.UIhandler ui_handler;
    final ArrayList<Command> cmd_list = new ArrayList<>();
    final ArrayList<IDFhandler> idf_handler_list = new ArrayList<>();
    final ArrayList<IDF> idf_list = new ArrayList<>();
    JSONArray df_list;
    String mac_addr;
    long last_timestamp;
    final long threshold = 50;
    byte[] sensor_list;
    boolean[] sensor_activate;
    boolean[] sensor_responded;
    boolean suspended;
    String endpoint = "";

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

    public DAI (BLEIDA ble_ida, DAN dan, MainActivity.UIhandler ui_handler) {
        this.ble_ida = ble_ida;
        this.dan = dan;
        this.ui_handler = ui_handler;
    }

    @Override
    public void run () {
        init_cmds();
        init_idf_handlers();
        init_idfs();

        logging("DAI.run()");
        mac_addr = ble_ida.init(
            this,
            ui_handler,
            "00002a37-0000-1000-8000-00805f9b34fb",
            "00001525-1212-efde-1523-785feabcd123",
            get_cmd("INIT_PROCEDURE")
        );
    }

    @Override
    public void pull(String odf_name, JSONArray data) {
        try {
            if (odf_name.equals("Control")) {
                String cmd_name = data.getString(0);
                JSONArray dl_cmd_params = data.getJSONObject(1).getJSONArray("cmd_params");
                for (Command cmd: cmd_list) {
                    if (cmd_name.equals(cmd.name)) {
                        cmd.run(dl_cmd_params, null);
                        return;
                    }
                }
                logging("write(%s): Unknown cmd: %s", odf_name, cmd_name);
                push_cmd_to_iottalk("UNKNOWN_CMD", cmd_name);
                /* Reports the exception to EC */
            } else {
                logging("write(%s): Unknown ODF", odf_name);
                /* Reports the exception to EC */
            }
        } catch (JSONException e) {
            logging("write(%s): JSONException", odf_name);
        }
    }

    @Override
    public boolean msg_match(byte[] o_msg, byte[] i_msg) {
        if (o_msg[0] == i_msg[0]) {
            if (o_msg[0] == MorSensorCommandTable.IN_SENSOR_DATA) {
                return o_msg[1] == i_msg[1];
            }
            return true;
        }
        return false;
    }

    @Override
    public void receive(String source, byte[] msg) {
        byte i_opcode = msg[0];
        logging("DAI.receive(%s, %02X)", source, i_opcode);
        if (source.equals("") && i_opcode == MorSensorCommandTable.IN_SENSOR_DATA) {
            long timestamp = System.currentTimeMillis();
            if (timestamp - last_timestamp < threshold) {
                logging("DAI.receive(%s, %02X): MorSensor data rate too high, drop packet", source, i_opcode);
            } else {
                ByteArrayInputStream ul_cmd_params = new ByteArrayInputStream(msg);
                last_timestamp = timestamp;
                byte opcode = (byte) ul_cmd_params.read();
                byte sensor_id = (byte) ul_cmd_params.read();
                logging("DAI.receive(%s, %02X): Sensor data from %02X", source, i_opcode, sensor_id);
                get_idf_handler(sensor_id).push(ul_cmd_params);
            }
        } else {
            for (Command cmd : cmd_list) {
                for (byte cmd_opcode : cmd.opcodes) {
                    if (cmd_opcode == i_opcode && source.equals(cmd.name)) {
                        ByteArrayInputStream ul_cmd_params = new ByteArrayInputStream(msg);
                        cmd.run(null, ul_cmd_params);
                        try {
                            ul_cmd_params.close();
                        } catch (IOException e) {
                            logging("DAI.receive(%s, %02X): IOException", source, i_opcode);
                        }
                        return;
                    }
                }
            }
        }

        logging("DAI.receive(%s, %02X): Unknown MorSensor command:", source, i_opcode);
        for (int i = 0; i < 5; i++) {
            String s = "    ";
            for (int j = 0; j < 4; j++) {
                s += String.format("%02X ", msg[i * 4 + j]);
            }
            logging(s);
        }
        /* Reports the exception to EC */
    }

    boolean all (boolean[] array) {
        for (boolean b: array) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    void push_cmd_to_iottalk(String cmd, String cmd_param) {
        JSONArray cmd_params = new JSONArray();
        cmd_params.put(cmd_param);
        push_cmd_to_iottalk(cmd, cmd_params);
    }

    void push_cmd_to_iottalk(final String cmd, final JSONArray cmd_params) {
        try {
            dan.push("Control", new JSONArray(){{
                put(cmd);
                put(new JSONObject(){{
                    put("cmd_params", cmd_params);
                }});
            }});
        } catch (JSONException e) {
            logging("push_cmd_to_iottalk(): JSONException");
        }
    }

    void add_idf_handlers(IDFhandler... idf_handlers) {
        for (IDFhandler idf_handler: idf_handlers) {
            idf_handler_list.add(idf_handler);
        }
    }

    void add_idfs(IDF... idfs) {
        for (IDF idf: idfs) {
            idf_list.add(idf);
        }
    }

    private void add_cmds(Command... cmds) {
        for (Command cmd: cmds) {
            cmd_list.add(cmd);
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

    Command get_cmd(String name) {
        for (Command cmd: cmd_list) {
            if (name.equals(cmd.name)) {
                return cmd;
            }
        }
        return null;
    }

    void init_idf_handlers() {
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
            },
            new IDFhandler(0x52, "Color-I") {
                @Override
                public void push(ByteArrayInputStream ul_cmd_params) {
                    byte[] bytes = new byte[8];
                    ul_cmd_params.read(bytes, 0, 8);
                    IDF color_idf = get_idf("Color-I");
                    if (color_idf.selected) {
                        color_idf.push(bytes);
                    }
                }
            }
        );
    }

    void init_idfs () {
        add_idfs(
            new IDF("Gyroscope") {
                float last_x;
                float last_y;
                float last_z;
                float threshold = 10;
                @Override
                public void push(byte[] bytes) {
                    final float x = (float) (((short)bytes[0] * 256 + (short)bytes[1]) / 32.8);
                    final float y = (float) (((short)bytes[2] * 256 + (short)bytes[3]) / 32.8);
                    final float z = (float) (((short)bytes[4] * 256 + (short)bytes[5]) / 32.8);

                    float diff_x = Math.abs(last_x - x);
                    float diff_y = Math.abs(last_y - y);
                    float diff_z = Math.abs(last_z - z);

                    if (diff_x > threshold || diff_y > threshold || diff_z > threshold) {
                        last_x = x;
                        last_y = y;
                        last_z = z;
                        try {
                            final JSONArray data = new JSONArray();
                            data.put(x);
                            data.put(y);
                            data.put(z);
                            dan.push("Gyroscope", data);
                            logging("push(Gyroscope, %s)", data);
                        } catch (JSONException e) {
                            logging("push(Gyroscope): JSONException");
                        }
                    } else {
                        logging("Gyro diff too small (%f, %f, %f) -> (%f, %f, %f)", last_x, last_y, last_z, x, y, z);
                    }
                }
            },
            new IDF("Acceleration") {
                float last_x;
                float last_y;
                float last_z;
                float threshold = 1;
                @Override
                public void push(byte[] bytes) {
                    final float x = (float) (((short)bytes[0] * 256 + (short)bytes[1]) / 4096.0) * (float) 9.8;
                    final float y = (float) (((short)bytes[2] * 256 + (short)bytes[3]) / 4096.0) * (float) 9.8;
                    final float z = (float) (((short)bytes[4] * 256 + (short)bytes[5]) / 4096.0) * (float) 9.8;

                    float diff_x = Math.abs(last_x - x);
                    float diff_y = Math.abs(last_y - y);
                    float diff_z = Math.abs(last_z - z);

                    if (diff_x > threshold || diff_y > threshold || diff_z > threshold) {
                        last_x = x;
                        last_y = y;
                        last_z = z;
                        try {
                            final JSONArray data = new JSONArray();
                            data.put(x);
                            data.put(y);
                            data.put(z);
                            dan.push("Acceleration", data);
                            logging("push(Acceleration, %s)", data);
                        } catch (JSONException e) {
                            logging("push(Acceleration): JSONException");
                        }
                    } else {
                        logging("Acc diff too small (%f, %f, %f) -> (%f, %f, %f)", last_x, last_y, last_z, x, y, z);
                    }
                }
            },
            new IDF("Magnetometer") {
                float last_x;
                float last_y;
                float last_z;
                float threshold = 10;
                @Override
                public void push(byte[] bytes) {
                    final float x = (float) (((short)bytes[0] * 256 + (short)bytes[1]) / 3.41 / 100);
                    final float y = (float) (((short)bytes[2] * 256 + (short)bytes[3]) / 3.41 / 100);
                    final float z = (float) (((short)bytes[4] * 256 + (short)bytes[5]) / 3.41 /-100);

                    float diff_x = Math.abs(last_x - x);
                    float diff_y = Math.abs(last_y - y);
                    float diff_z = Math.abs(last_z - z);

                    if (diff_x > threshold || diff_y > threshold || diff_z > threshold) {
                        last_x = x;
                        last_y = y;
                        last_z = z;
                        try {
                            final JSONArray data = new JSONArray();
                            data.put(x);
                            data.put(y);
                            data.put(z);
                            dan.push("Magnetometer", data);
                            logging("push(Magnetometer, %s)", data);
                        } catch (JSONException e) {
                            logging("push(Magnetometer): JSONException");
                        }
                    } else {
                        logging("Mag diff too small (%f, %f, %f) -> (%f, %f, %f)", last_x, last_y, last_z, x, y, z);
                    }
                }
            },
            new IDF("UV") {
                @Override
                public void push(byte[] bytes) {
                    try {
                        final float uv_data = (float) (bytes[1] * 256 + (bytes[0]) / 100.0);
                        dan.push("UV", new JSONArray(){{
                            put(uv_data);
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
                        dan.push("Temperature", new JSONArray(){{
                            put(temperature);
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
                        dan.push("Humidity", new JSONArray(){{
                            put(humidity);
                        }});
                        logging("push(Humidity, [%f])", humidity);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            },
            new IDF("Color-I") {
                int last_r, last_g, last_b;
                int threshold = 1;
                @Override
                public void push(byte[] bytes) {
                    final JSONArray data = new JSONArray();
                    int r = bytes[1] & 0xFF;
                    int g = bytes[3] & 0xFF;
                    int b = bytes[5] & 0xFF;

                    int diff_r = Math.abs(last_r - r);
                    int diff_g = Math.abs(last_g - g);
                    int diff_b = Math.abs(last_b - b);

                    if (diff_r > threshold || diff_g > threshold || diff_b > threshold) {
                        last_r = r;
                        last_g = g;
                        last_b = b;
                        data.put(r);
                        data.put(g);
                        data.put(b);
                        dan.push("Color-I", data);
                        logging("push(Color-I, %s)", data);
                    } else {
                        logging("Color diff too small (%d, %d, %d) -> (%d, %d, %d)", last_r, last_g, last_b, r, g, b);
                    }
                }
            }
        );
    }

    void init_cmds () {
        add_cmds(
            new Command("INIT_PROCEDURE", MorSensorCommandTable.IN_MORSENSOR_VERSION, MorSensorCommandTable.IN_FIRMWARE_VERSION, MorSensorCommandTable.IN_SENSOR_LIST) {
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        suspended = true;
                        ble_ida.write("INIT_PROCEDURE", MorSensorCommandTable.GetMorSensorVersion());
                        ble_ida.write("INIT_PROCEDURE", MorSensorCommandTable.GetFirmwareVersion());
                        ble_ida.write("INIT_PROCEDURE", MorSensorCommandTable.GetSensorList());
                    } else {
                        switch ((byte) ul_cmd_params.read()) {
                            case MorSensorCommandTable.IN_MORSENSOR_VERSION:
                                int major = ul_cmd_params.read();
                                int minor = ul_cmd_params.read();
                                int patch = ul_cmd_params.read();
                                String morsensor_version = String.format("%d.%d.%d", major, minor, patch);
                                ui_handler.send_info("MORSENSOR_VERSION", morsensor_version);
                                break;
                            case MorSensorCommandTable.IN_FIRMWARE_VERSION:
                                major = ul_cmd_params.read();
                                minor = ul_cmd_params.read();
                                patch = ul_cmd_params.read();
                                String firmware_version = String.format("%d.%d.%d", major, minor, patch);
                                ui_handler.send_info("FIRMWARE_VERSION", firmware_version);
                                break;
                            case MorSensorCommandTable.IN_SENSOR_LIST:
                                int sensor_count = ul_cmd_params.read();
                                df_list = new JSONArray();
                                sensor_list = new byte[sensor_count];
                                for (int i = 0; i < sensor_count; i++) {
                                    byte sensor_id = (byte) ul_cmd_params.read();
                                    sensor_list[i] = sensor_id;
                                    logging("Found sensor %02X", sensor_id);
                                    for (String df_name: get_idf_handler(sensor_id).df_list) {
                                        df_list.put(df_name);
                                    }
                                    ble_ida.write("", MorSensorCommandTable.SetSensorStopTransmission(sensor_id));
                                    ble_ida.write("", MorSensorCommandTable.SetSensorTransmissionModeContinuous(sensor_id));
                                }
                                ui_handler.send_info("DF_LIST", df_list);
                                logging(df_list.toString());
                                sensor_activate = new boolean[sensor_count];
                                sensor_responded = new boolean[sensor_count];

                                try {
                                    JSONObject profile = new JSONObject() {{
                                        put("df_list", df_list);
                                        put("dm_name", "MorSensor");
                                        put("is_sim", false);
                                        put("u_name", "yb");
                                    }};
                                    if (endpoint.equals("")) {
                                        endpoint = dan.init(DAI.this, null, mac_addr, profile);
                                    } else {
                                        dan.register(endpoint, profile);
                                    }
                                    ui_handler.send_info("REGISTRATION_SUCCEED", endpoint);
                                } catch (JSONException e) {
                                    logging("DAI.run(): init: JSONException");
                                }
                                break;
                        }
                    }
                }
            },
            new Command("SET_DF_STATUS", MorSensorCommandTable.IN_SENSOR_DATA, MorSensorCommandTable.IN_STOP_TRANSMISSION) {
                @Override
                public void run(JSONArray dl_cmd_params, ByteArrayInputStream ul_cmd_params) {
                    if (ul_cmd_params == null) {
                        try {
                            final String flags = dl_cmd_params.getString(0);
                            for (int i = 0; i < flags.length(); i++) {
                                String df_name = df_list.getString(i);
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
                                boolean any_df_selected = false;
                                for (String df_name : idf_handler.df_list) {
                                    IDF idf = get_idf(df_name);
                                    any_df_selected |= idf.selected;
                                }

                                if (suspended) {
                                    sensor_responded[i] = true;
                                } else if (any_df_selected == sensor_activate[i]) {
                                    sensor_responded[i] = true;
                                } else {
                                    sensor_responded[i] = false;
                                    if (any_df_selected) {
                                        if (sensor_list[i] == 0x52) {
                                            ble_ida.write("", MorSensorCommandTable.ModifyLEDState((byte) 1));
                                        }
                                        ble_ida.write("SET_DF_STATUS", MorSensorCommandTable.RetrieveSensorData(idf_handler.sensor_id));
                                    } else {
                                        if (sensor_list[i] == 0x52) {
                                            ble_ida.write("", MorSensorCommandTable.ModifyLEDState((byte) 0));
                                        }
                                        ble_ida.write("SET_DF_STATUS", MorSensorCommandTable.SetSensorStopTransmission(idf_handler.sensor_id));
                                    }
                                }
                                sensor_activate[i] = any_df_selected;
                                logging("Sensor responded: %02X %b", sensor_list[i], sensor_responded[i]);
                            }
                            if (all(sensor_responded)) {
                                ui_handler.send_info("SET_DF_STATUS", flags);
                                push_cmd_to_iottalk("SET_DF_STATUS_RSP", flags);
                            } else {
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
                                for (int i = 0; i < df_list.length(); i++) {
                                    IDF idf = get_idf(df_list.getString(i));
                                    if (idf == null || !idf.selected) {
                                        flags += "0";
                                    } else {
                                        flags += "1";
                                    }
                                }
                                ui_handler.send_info("SET_DF_STATUS", flags);
                                push_cmd_to_iottalk("SET_DF_STATUS_RSP", flags);
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
                                if (sensor_list[i] == 0x52) {
                                    ble_ida.write("", MorSensorCommandTable.ModifyLEDState((byte) 1));
                                }
                                ble_ida.write("RESUME", MorSensorCommandTable.RetrieveSensorData(sensor_list[i]));
                            } else {
                                sensor_responded[i] = true;
                            }
                        }
                        if (all(sensor_responded)) {
                            ui_handler.send_info("RESUMED");
                            push_cmd_to_iottalk("RESUME_RSP", "OK");
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
                            ui_handler.send_info("RESUMED");
                            push_cmd_to_iottalk("RESUME_RSP", "OK");
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
                                if (sensor_list[i] == 0x52) {
                                    ble_ida.write("", MorSensorCommandTable.ModifyLEDState((byte) 0));
                                }
                                ble_ida.write("SUSPEND", MorSensorCommandTable.SetSensorStopTransmission(sensor_list[i]));
                            } else {
                                sensor_responded[i] = true;
                            }
                        }
                        if (all(sensor_responded)) {
                            ui_handler.send_info("SUSPENDED");
                            push_cmd_to_iottalk("SUSPEND_RSP", "OK");
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
                            ui_handler.send_info("SUSPENDED");
                            push_cmd_to_iottalk("SUSPEND_RSP", "OK");
                        }
                    }
                }
            }
        );
    }

    static private void logging (String format, Object... args) {
        Log.i("MorSensor", String.format("[DAI] "+ format, args));
    }
}

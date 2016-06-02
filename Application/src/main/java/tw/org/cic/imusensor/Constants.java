package tw.org.cic.imusensor;

import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

public class Constants {
    static public final String version = "20160506";
    static public final String dm_name = "MorSensor";
    static public final String u_name = "yb";
    static public final String log_tag = dm_name;

    static public final int MENU_ITEM_ID_DAN_VERSION = 0;
    static public final int MENU_ITEM_ID_DAI_VERSION = 1;
    static public final int MENU_ITEM_REQUEST_INTERVAL = 2;
    static public final int MENU_ITEM_REREGISTER = 3;
    static public final int MENU_ITEM_WIFI_SSID = 4;

    /* MorSensorIDAapi related values */
    static public final int REQUEST_ENABLE_BT = 1;
    static public final int BLUETOOTH_SCANNING_PERIOD = 5000;

    static public final String INFO_MORSENSOR_VERSION = "MORSENSOR_VERSION";
    static public final String INFO_FIRMWARE_VERSION = "FIRMWARE_VERSION";
    static public final String INFO_SENSOR_LIST = "SENSOR_LIST";

    static final int COMMAND_SCANNING_PERIOD = 50;
    static final int COMMAND_RESEND_CYCLES = 10;
    static final int COMMAND_FAIL_RETRY = 30;

    static public byte toByte(short s) {
        return (byte) (s & 0xFF);
    }

    static public byte toByte(int i) {
        return (byte) (i & 0xFF);
    }

    static public int fromByte(byte b) {
        return b & 0xFF;
    }

    static final HashMap<Byte, String[]> sensor_id_to_feature_mapping = new HashMap<Byte, String[]>() {{
        put(toByte(0xD0), new String[]{"Gyroscope", "Acceleration", "Magnetometer"});
        put(toByte(0xC0), new String[]{"UV"});
        put(toByte(0x80), new String[]{"Temperature", "Humidity"});
    }};

    static public String[] get_feature_list_from_sensor_id(byte sensor_id) {
        if (sensor_id_to_feature_mapping.containsKey(sensor_id)) {
            return sensor_id_to_feature_mapping.get(sensor_id);
        }
        return new String[]{};
    }

    static public String[] get_feature_list_from_sensor_list(byte[] sensor_list) {
        ArrayList<String> ret = new ArrayList<String>();
        for (byte sensor_id : sensor_list) {
            logging("iterate to sensor: " + sensor_id);
            for (String n : sensor_id_to_feature_mapping.get(sensor_id)) {
                ret.add(n);
            }
        }
        return ret.toArray(new String[0]);
    }

    static public String get_feature_button_name_from_sensor(byte sensor_id) {
        return TextUtils.join(", ", sensor_id_to_feature_mapping.get(fromByte(sensor_id)));
    }

    private static void logging(String _) {
        Log.i(Constants.log_tag, "[Constants.java]" + _);
    }
}
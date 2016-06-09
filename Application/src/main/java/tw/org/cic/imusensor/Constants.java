package tw.org.cic.imusensor;

import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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
    static public final int BLUETOOTH_SCANNING_PERIOD = 10000;

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

    static final HashMap<String, Byte> df_name_to_sensor_id = new HashMap<String, Byte>() {{
        put("Gyroscope",    toByte(0xD0));
        put("Acceleration", toByte(0xD0));
        put("Magnetometer", toByte(0xD0));
        put("UV",           toByte(0xC0));
        put("Temperature",  toByte(0x80));
        put("Humidity",     toByte(0x80));
    }};

    static public ArrayList<String> get_df_list(byte sensor_id) {
        ArrayList<String> ret = new ArrayList<>();
        for (Map.Entry<String, Byte> df_name_sid: df_name_to_sensor_id.entrySet()) {
            if (df_name_sid.getValue() == sensor_id) {
                ret.add(df_name_sid.getKey());
            }
        }
        return ret;
    }

    private static void logging(String _) {
        Log.i(Constants.log_tag, "[Constants.java]" + _);
    }
}
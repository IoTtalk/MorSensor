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

    static final int COMMAND_TIMEOUT = 1000;

    static public byte toByte(short s) {
        return (byte) (s & 0xFF);
    }

    static public byte toByte(int i) {
        return (byte) (i & 0xFF);
    }

    static public int fromByte(byte b) {
        return b & 0xFF;
    }

    private static void logging(String _) {
        Log.i(Constants.log_tag, "[Constants.java]" + _);
    }
}
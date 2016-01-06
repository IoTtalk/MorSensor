package tw.org.cic.imusensor;

import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;

public class C {
    static public final String dm_name = "MorSensor";
    static public final String u_name = "yb";
    static public final String log_tag = dm_name;

    static public byte toByte (short s) {
        return (byte) (s & 0xFF);
    }

    static public byte toByte (int i) {
        return (byte) (i & 0xFF);
    }

    static public int fromByte (byte b) {
        return b & 0xFF;
    }

    static public String[] get_feature_list_from_sensor (byte sensor_id) {
        ArrayList<String> ret = new ArrayList<String>();
        switch (fromByte(sensor_id)) {
            case 0xD0:
                ret.add("Gyroscope");
                ret.add("Acceleration");
                ret.add("Magnetometer");
                break;
            case 0xC0:
                ret.add("UV");
                break;
            case 0x80:
                ret.add("Temperature");
                ret.add("Humidity");
                break;
            default:
                ret.add("Unknown");
        }
        return ret.toArray(new String[0]);
    }

    static public String[] get_feature_list_from_sensor_list(byte[] sensor_list) {
        ArrayList<String> ret = new ArrayList<String>();
        for (byte sensor_id: sensor_list) {
            logging("iterate to sensor: "+sensor_id+" "+fromByte(sensor_id));
            for (String n: get_feature_list_from_sensor(sensor_id)) {
                ret.add(n);
            }
        }
        return ret.toArray(new String[0]);
    }

    static public String get_feature_button_name_from_sensor (byte sensor_id) {
        return TextUtils.join(", ", get_feature_list_from_sensor(sensor_id));
    }

    private static void logging (String _) {
        Log.i(C.log_tag, "[C.java]" + _);
    }
}
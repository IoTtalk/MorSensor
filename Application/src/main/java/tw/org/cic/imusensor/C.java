package tw.org.cic.imusensor;

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

    static public String[] gen_feature_list_from_sensor_id_list (byte[] sensor_list) {
        ArrayList<String> ret = new ArrayList<String>();
        for (byte id: sensor_list) {
            logging("iterate to sensor: "+id+" "+fromByte(id));
            switch (fromByte(id)) {
                case 0xD0:
                    ret.add("Gyroscope");
                    ret.add("Accelerometer");
                    ret.add("Magnetometer");
                    break;
                case 0xC0:
                    ret.add("UV");
                    break;
                case 0x80:
                    ret.add("Temperature");
                    ret.add("Humidity");
                    break;
            }
        }
        return ret.toArray(new String[0]);
    }

    private static void logging (String _) {
        Log.i(C.log_tag, "[C.java]" + _);
    }
}
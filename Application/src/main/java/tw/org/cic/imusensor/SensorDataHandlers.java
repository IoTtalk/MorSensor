package tw.org.cic.imusensor;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import DAN.DAN;

public class SensorDataHandlers {
    static public void sensor_handler_D0(byte[] packet) { // IMU
        //Gryo: value[2][3] / 32.8
        final float gyro_x = (float) (((short) packet[2] * 256 + (short) packet[3]) / 32.8); //Gryo x
        final float gyro_y = (float) (((short) packet[4] * 256 + (short) packet[5]) / 32.8); //Gryo y
        final float gyro_z = (float) (((short) packet[6] * 256 + (short) packet[7]) / 32.8); //Gryo z

        //Acc: value[8][9] / 4096
        final float acc_x = (float) (((short) packet[8] * 256 + (short) packet[9]) / 4096.0) * (float)9.8; //Acc x
        final float acc_y = (float) (((short) packet[10] * 256 + (short) packet[11]) / 4096.0) * (float)9.8; //Acc y
        final float acc_z = (float) (((short) packet[12] * 256 + (short) packet[13]) / 4096.0) * (float)9.8; //Acc z

        //Mag: value[15][14] / 3.41 / 100 (注意:MagZ 需乘上-1)
        final float mag_x = (float) (((short) packet[15] * 256 + (short) packet[14]) / 3.41 / 100); //Mag x
        final float mag_y = (float) (((short) packet[17] * 256 + (short) packet[16]) / 3.41 / 100); //Mag y
        final float mag_z = (float) (((short) packet[19] * 256 + (short) packet[18]) / 3.41 / -100); //Mag z

        try {
            JSONArray data = new JSONArray();
            data.put(gyro_x); data.put(gyro_y); data.put(gyro_z);
            DAN.push("Gyroscope", data);
            logging("push(\"Gyroscope\", "+ gyro_x +","+ gyro_y +","+ gyro_z +")");

            data = new JSONArray();
            data.put(acc_x); data.put(acc_y); data.put(acc_z);
            DAN.push("Acceleration", data);
            logging("push(\"Accelerometer\", "+ acc_x +","+ acc_y +","+ acc_z +")");

            data = new JSONArray();
            data.put(mag_x); data.put(mag_y); data.put(mag_z);
            DAN.push("Magnetometer", data);
            logging("push(\"Magnetometer\", "+ mag_x +","+ mag_y +","+ mag_z +")");

        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    static public void sensor_handler_C0(byte[] packet) { // UV
        final float uv_data = (float) ((((short) packet[3]) * 256 + ((short) packet[2])) / 100.0);
        DAN.push("UV", new float[]{uv_data});
        logging("push(\"UV\", [" + uv_data + "])");
    }

    static public void sensor_handler_80(byte[] packet) { // Temperature & Humidity
        final float temp_data = (float) (((short) packet[2] * 256 + (short) packet[3]) * 175.72 / 65536.0 - 46.85);
        final float humidity_data = (float) (((short) packet[4] * 256 + (short) packet[5]) * 125.0 / 65536.0 - 6.0);

        DAN.push("Temperature", new float[]{temp_data});
        logging("push(\"Temperature\", [" + temp_data + "])");

        DAN.push("Humidity", new float[]{humidity_data});
        logging("push(\"Humidity\", [" + humidity_data + "])");
    }

    static private void logging (String _) {
        Log.i(Constants.log_tag, "[SensorDataHandlers]" + _);
    }
}

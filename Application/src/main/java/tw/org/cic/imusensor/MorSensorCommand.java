package tw.org.cic.imusensor;


import android.util.Log;

public class MorSensorCommand {
//    protected static final String TAG = "MorSensorCommand";
    protected static final String TAG = "MorSensor";

    public static final int MAX_COMMAND_LENGTH = 20;

    /* output commands */
    public static final byte OUT_ECHO = 1; //0x01
    public static final byte OUT_SENSOR_LIST = 2; //0x02 Internal use
    public static final byte OUT_MORSENSOR_VERSION = 3; //0x03 Internal use
    public static final byte OUT_FIRMWARE_VERSION = 4; //0x04 Internal use
    public static final byte OUT_SENSOR_VERSION = 17; //0x11
    public static final byte OUT_REGISTER_CONTENT = 18; //0x12 Internal use
    public static final byte OUT_LOST_SENSOR_DATA = 19; //0x13
    public static final byte OUT_TRANSMISSION_MODE = 20; //0x14 Internal use
    public static final byte OUT_SET_TRANSMISSION_MODE = 33; //0x21
    //Third level commands for 0x21
    public static final byte OUT_OUT_SET_TRANSMISSION_SINGLE = 0; //0x00
    public static final byte OUT_OUT_SET_TRANSMISSION_ONCE = OUT_OUT_SET_TRANSMISSION_SINGLE; // alias
    public static final byte OUT_OUT_SET_TRANSMISSION_CONTINUOUS = 1; //0x01
    public static final byte OUT_STOP_TRANSMISSION = 34; //0x22
    public static final byte OUT_SET_REGISTER_CONTENT = 35; //0x23
    public static final byte OUT_MODIFY_LED_STATE = 49; //0x31
    //Second level commands for 0x31
    public static final byte OUT_OUT_MODIFY_MCU_LED_D2 = 1; //0x01
    public static final byte OUT_OUT_MODIFY_MCU_LED_D3 = 2; //0x02
    public static final byte OUT_OUT_MODIFY_COLOR_SENSOR_LED = 3; //0x03
    //Third level commands for 0x01~0x03
    public static final byte OUT_OUT_OUT_MODIFY_LED_OFF = 0; //0x00
    public static final byte OUT_OUT_OUT_MODIFY_LED_ON = 1; //0x01
    public static final byte OUT_FILE_DATA_SIZE = 241 - 256; //0xF1
    public static final byte OUT_FILE_DATA = 242 - 256; //0xF2
    public static final byte OUT_SENSOR_DATA = 243 - 256; //0xF3

    public static final byte IN_ECHO = 1; //0x01
    public static final byte IN_SENSOR_LIST = 2; //0x02 Internal use
    public static final byte IN_MORSENSOR_VERSION = 3; //0x03 Internal use
    public static final byte IN_FIRMWARE_VERSION = 4; //0x04 Internal use
    public static final byte IN_SENSOR_VERSION = 17; //0x11
    public static final byte IN_REGISTER_CONTENT = 18; //0x12 Internal use
    public static final byte IN_LOST_SENSOR_DATA = 19; //0x13
    public static final byte IN_TRANSMISSION_MODE = 20; //0x14 Internal use
    public static final byte IN_SET_TRANSMISSION_MODE = 33; //0x21
    public static final byte IN_STOP_TRANSMISSION = 34; //0x22
    public static final byte IN_SET_REGISTER_CONTENT = 35; //0x23
    public static final byte IN_MODIFY_LED_STATE = 49; //0x31
    public static final byte IN_IN_MCU_LED_D2 = 1; //0x01
    public static final byte IN_IN_MCU_LED_D3 = 2; //0x02
    public static final byte IN_IN_COLOR_SENSOR_LED = 3; //0x03
    public static final byte IN_FILE_DATA_SIZE = 241 - 256; //0xF1
    public static final byte IN_FILE_DATA = 242 - 256; //0xF2
    public static final byte IN_SENSOR_DATA = 243 - 256; //0xF3
    public static final byte IN_ERROR = 225 - 256; //0xE1

    //Encode
    public static byte[] GetSensorList(){
        logging("o 0x02: Retrieve sensor list");
        byte[] command = new byte[20];
        command[0] = OUT_SENSOR_LIST;
        return command;
    }

    public static byte[] GetMorSensorVersion(){
        logging("o 0x03: Retrieve MorSensor version");
        byte[] command = new byte[20];
        command[0] = OUT_MORSENSOR_VERSION;
        return command;
    }

    public static byte[] GetFirmwareVersion(){
        logging("o 0x04: Retrieve firmware version");
        byte[] command = new byte[20];
        command[0] = OUT_FIRMWARE_VERSION;
        return command;
    }

    public static byte[] SetSensorTransmissionModeOnce(byte SensorID){
        logging("o 0x21 0x__ 0x00: Set transmission mode once");
        byte[] command = new byte[20];
        command[0] = OUT_SET_TRANSMISSION_MODE;
        command[1] = SensorID;
        command[2] = OUT_OUT_SET_TRANSMISSION_ONCE;
        return command;
    }

    public static byte[] SetSensorTransmissionModeContinuous(byte SensorID){
        logging("o 0x14 0x__: Get transmission mode");
        byte[] command = new byte[20];
        command[0] = OUT_TRANSMISSION_MODE;
        command[1] = SensorID;
        return command;
    }

    public static byte[] GetSensorTransmissionMode(byte SensorID){
        logging("o 0x21 0x__ 0x01: Set transmission mode continuous");
        byte[] command = new byte[20];
        command[0] = OUT_SET_TRANSMISSION_MODE;
        command[1] = SensorID;
        command[2] = OUT_OUT_SET_TRANSMISSION_CONTINUOUS;
        return command;
    }

    public static byte[] RetrieveSensorData (byte SensorID){
        logging("o 0xF3: Retrieve sensor data");
        byte[] command = new byte[20];
        command[0] = OUT_SENSOR_DATA;
        command[1] = SensorID;
        return command;
    }

    public static byte[] SetSensorStopTransmission(byte SensorID){
        logging("o 0x22: Stop transmission");
        byte[] command = new byte[20];
        command[0] = OUT_STOP_TRANSMISSION;
        command[1] = SensorID;
        return command;
    }

    public static byte[] Echo () {
        byte[] command = new byte[20];
        command[0] = OUT_ECHO;
        return command;
    }

    private static void logging (String _) {
        Log.i(TAG, "[MorsensorCommand]"+ _);
    }
}

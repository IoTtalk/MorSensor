package tw.org.cic.imusensor;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class IMUViewActivity extends Activity {
    static private IMUViewActivity self;

    static byte[] sensor_list = null;
    static byte[] MorSensorVersion = {0, 0, 0};
    static byte[] FirmwareVersion = {0, 0, 0};

    static private final int STATE_WAIT_SENSOR_LIST = 0;
    static private final int STATE_WAIT_MORSENSOR_VERSION = 1;
    static private final int STATE_WAIT_FIRMWARE_VERSION = 2;
    static private final int STATE_WAIT_DATA = 3;
    static private final int STATE_RECONNECTING = 4;
    static private final int STATE_DISCONNECTED = 5;
    static private int state;

    enum ECStatus {
        REGISTER_TRYING,
        REGISTER_FAILED,
        REGISTER_SUCCEED,
    }

    //------------------------------ BLE -------------------------------------------
    static private BluetoothLeService mBluetoothLeService = null;
    // Local Bluetooth adapter
    static private BluetoothAdapter mBluetoothAdapter = null;
    static private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    static private BluetoothGattCharacteristic mReadCharacteristic,mWriteCharacteristic;
    static public String mDeviceAddress="123",mDeviceName="",mDeviceData="";
    static public final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    static public final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    static public boolean mConnected = false;
    static private final int REQUEST_CONNECT_DEVICE = 1;

    static TextView tv_GryoX, tv_GryoY, tv_GryoZ, tv_AccX, tv_AccY, tv_AccZ, tv_MagX, tv_MagY, tv_MagZ;
    static TextView tv_MorSensorVersion ,tv_FirmwaveVersion, tv_MorSensorID;
    static LinearLayout ll_feature_switches;

    static long imu_timestamp = 0;
    static long uv_timestamp = 0;
    static long humidity_timestamp = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_imu_view);
        Log.e(C.log_tag, "-- IMUViewActivity --");

        self = this;

        tv_GryoX = (TextView)findViewById(R.id.GryoX);
        tv_GryoY = (TextView)findViewById(R.id.GryoY);
        tv_GryoZ = (TextView)findViewById(R.id.GryoZ);
        tv_AccX = (TextView)findViewById(R.id.AccX);
        tv_AccY = (TextView)findViewById(R.id.AccY);
        tv_AccZ = (TextView)findViewById(R.id.AccZ);
        tv_MagX = (TextView)findViewById(R.id.MagX);
        tv_MagY = (TextView)findViewById(R.id.MagY);
        tv_MagZ = (TextView)findViewById(R.id.MagZ);

        ll_feature_switches = (LinearLayout)findViewById(R.id.ll_feature_switches);

        tv_MorSensorVersion = (TextView)findViewById(R.id.MorSensor_Version);
        tv_FirmwaveVersion = (TextView)findViewById(R.id.Firmwave_Version);
        tv_MorSensorID = (TextView)findViewById(R.id.MorSensor_ID);

        //Receive DeviceScanActivity DeviceAddress.
        final Intent intent = getIntent();
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        state = STATE_WAIT_SENSOR_LIST;
        CommandSender.init();

        if(!mConnected){
            // Use this check to determine whether BLE is supported on the device.  Then you can
            // selectively disable BLE-related features.
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Toast.makeText(this, "ble_not_supported", Toast.LENGTH_SHORT).show();
                finish();
            }

            // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
            // BluetoothAdapter through BluetoothManager.
            final BluetoothManager bluetoothManager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();

            // Checks if Bluetooth is supported on the device.
            if (mBluetoothAdapter == null) {
                Toast.makeText(this, "error_bluetooth_not_supported", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            StartBLE();
        }

        DAN.init(IMUViewActivity.this, "MorSensor");
        show_ec_status(ECStatus.REGISTER_TRYING, csmapi.ENDPOINT);

        DAN.Subscriber ec_status_handler = new DAN.Subscriber() {
            public void odf_handler (DAN.ODFObject odf_object) {
                switch (odf_object.event_tag) {
                    case REGISTER_FAILED:
                        show_ec_status(ECStatus.REGISTER_FAILED, odf_object.message);
                        break;

                    case REGISTER_SUCCEED:
                        show_ec_status(ECStatus.REGISTER_SUCCEED, odf_object.message);
                        String d_name = DAN.get_d_name();
                        ((TextView)findViewById(R.id.tv_d_name)).setText(d_name);
                        break;
                }
            }
        };
        DAN.subscribe("Control_channel", ec_status_handler);

        String d_name = DAN.get_d_name();
        logging("Get d_name:"+ d_name);
        TextView tv_d_name = (TextView)findViewById(R.id.tv_d_name);
        tv_d_name.setText(d_name);

        Button btn_detach = (Button)findViewById(R.id.btn_deregister);
        btn_detach.setOnClickListener(new View.OnClickListener () {
            @Override
            public void onClick (View v) {
                if(DeviceScanActivity.mDeviceScanActivity!=null)
                    DeviceScanActivity.mDeviceScanActivity.finish();

                BtDisConnect();
            }
        });
    }

    public void show_ec_status (ECStatus status, String host) {
        ((TextView)findViewById(R.id.tv_ec_host_address)).setText(host);
        TextView tv_ec_host_status = (TextView)findViewById(R.id.tv_ec_host_status);
        switch (status) {
            case REGISTER_TRYING:
                tv_ec_host_status.setText("...");
                tv_ec_host_status.setTextColor(Color.rgb(128, 0, 0));
                break;

            case REGISTER_FAILED:
                tv_ec_host_status.setText("!");
                tv_ec_host_status.setTextColor(Color.rgb(128, 0, 0));
                break;

            case REGISTER_SUCCEED:
                tv_ec_host_status.setText("~");
                tv_ec_host_status.setTextColor(Color.rgb(0, 128, 0));
                break;

        }

    }


    @Override
    public void onStart() {
        super.onStart();
        Log.e(C.log_tag, "++ ON START PreferenceActivity ++");
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        Log.e(C.log_tag, "+ ON RESUME PreferenceActivity +");

    }

    @Override
    public synchronized void onPause() {
        super.onPause();
        Log.e(C.log_tag, "- ON PAUSE PreferenceActivity -");
        if (isFinishing()) {
            self = null;
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.e(C.log_tag, "-- ON STOP PreferenceActivity --");

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(C.log_tag, "--- ON DESTROY PreferenceActivity ---");
        BtDisConnect();
        CommandSender.end();
        DAN.deregister();
    }

    static public boolean activity_running () {
        return self != null;
    }

    public void BtDisConnect(){
        mConnected = false;
        mDeviceAddress="123";

        if (sensor_list != null) {
            for (int i = 0; i < sensor_list.length; i++) {
                CommandSender.send_command(MorSensorCommand.SetSensorStopTransmission(sensor_list[i]));
            }
        }
        state = STATE_DISCONNECTED;

        if (mBluetoothLeService != null) {
            mBluetoothLeService.disconnect();
            unregisterReceiver(mGattUpdateReceiver);
//                unbindService(mServiceConnection);
            mBluetoothLeService = null;
        }
        state = STATE_WAIT_SENSOR_LIST;
        logging("BluetoothLe Disconnected.");
        this.finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_meter_view, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
//            Log.e(C.log_tag, "onActivityResult " + resultCode + "request: " + requestCode);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                try {
                    final Intent intent = getIntent();
                    mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

                    StartBLE();
                    DeviceScanActivity.mDeviceScanActivity.finish();
                    logging("REQUEST_CONNECT_DEVICE ");
                }catch (Exception e){
                    Log.e(C.log_tag, "BLE Connect Error: " + e);
                }
                break;
        }
    }

    private void StartBLE () {
        Log.v(C.log_tag, "++ StartBLE PreferenceActivity ++");

//        Log.v(TAG, "SttartBLE_mDeviceAddress: "+mDeviceAddress);
        logging("mDeviceAddress: "+ mDeviceAddress);
        if (!mConnected) {
            if (mDeviceAddress == null || mDeviceAddress.length() < 17) { //未獲取MAC
                Intent serverIntent = new Intent(this, DeviceScanActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                finish();
            } else {
                //Start BluetoothLe Service
                Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
                this.getApplicationContext().bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

                //Register BluetoothLe Receiver
                registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

                logging("IMU Sensor connected.");
            }
        }
    }

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(C.log_tag, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            if (!mConnected) {
                mConnected = mBluetoothLeService.connect(mDeviceAddress);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            logging("==== onServiceDisconnected ====");
            logging("==== SHOULDn't HAPPENED ====");
            mConnected = false;
            mBluetoothLeService = null;
        }
    };

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                logging("==== ACTION_GATT_CONNECTED ====");
                invalidateOptionsMenu();

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                logging("==== ACTION_GATT_DISCONNECTED ====");
                invalidateOptionsMenu();
                logging("mDeviceAddress: "+ mDeviceAddress);
                mConnected = false;
                state = STATE_RECONNECTING;
                ll_feature_switches.removeAllViews();
                tv_MorSensorID.setTextColor(Color.rgb(255, 0, 0));
                tv_MorSensorVersion.setTextColor(Color.rgb(255, 0, 0));
                tv_FirmwaveVersion.setTextColor(Color.rgb(255, 0, 0));
                //BtDisConnect();
                //StartBLE();

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                logging("==== ACTION_GATT_SERVICES_DISCOVERED ====");
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
                CommandSender.send_command(MorSensorCommand.GetSensorList());

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                mDeviceData = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                CommandSender.process_packet(hexToBytes(mDeviceData));

            }
        }
    };

    static public void state_machine_input (byte[] values) {
//        Log.i(TAG, "state: "+ state);
        dump_data_packet(values, "i");
        switch (state) {
            case STATE_WAIT_SENSOR_LIST:
                state_machine_wait_sensor_list(values);
                break;
            case STATE_WAIT_MORSENSOR_VERSION:
                state_machine_wait_morsensor_version(values);
                break;
            case STATE_WAIT_FIRMWARE_VERSION:
                state_machine_wait_firmware_version(values);
                break;
            case STATE_WAIT_DATA:
                state_machine_wait_data(values);
                break;
        }
    }

    static public void state_machine_wait_sensor_list (byte[] values) {
        switch (values[0]) {
            case MorSensorCommand.IN_SENSOR_LIST:
                logging("IN_SENSOR_LIST");
                state = STATE_WAIT_MORSENSOR_VERSION;
                // [IN_SENSOR_LIST][# sensors][sensor ID][sensor ID][sensor ID]....
                sensor_list = new byte[values[1]];
                String sensor_list_str = "";
                for (int i = 0; i < sensor_list.length; i++) {
                    sensor_list[i] = values[i + 2];
                    logging("Get SensorID:" + sensor_list[i]);
                    sensor_list_str += C.fromByte(sensor_list[i]) + " ";
                }

                /* Attach to EasyConnect */
                JSONObject profile = new JSONObject();
                try {
                    profile.put("d_name", "MorSensor-"+ DAN.get_clean_mac_addr(mDeviceAddress).substring(8).toUpperCase());
                    profile.put("dm_name", C.dm_name);
                    JSONArray feature_list = new JSONArray();
                    logging("Found features:");
                    for (String f: C.get_feature_list_from_sensor_list(sensor_list)) {
                        feature_list.put(f);
                        logging("feature: " + f);
                    }
                    profile.put("df_list", feature_list);
                    profile.put("u_name", C.u_name);
                    profile.put("monitor", DAN.get_mac_addr());
                    DAN.register(DAN.get_d_id(mDeviceAddress), profile);

                    for (byte f: sensor_list) {
                        String text = C.get_feature_button_name_from_sensor(f);
                        ToggleButton btn = new ToggleButton(self);
                        btn.setTag(f);
                        btn.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                        ll_feature_switches.addView(btn);

                        btn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                                byte sensor_id = (Byte)buttonView.getTag();
                                if (isChecked) {
                                    // The toggle is now enabled
                                    CommandSender.send_command(MorSensorCommand.SetSensorTransmissionModeContinuous(sensor_id));
                                    CommandSender.send_command(MorSensorCommand.RetrieveSensorData(sensor_id));
                                } else {
                                    // The toggle is now disabled
                                    CommandSender.send_command(MorSensorCommand.SetSensorStopTransmission(sensor_id));
                                }
                            }
                        });

                        btn.setText(text);
                        btn.setTextOff(text);
                        btn.setTextOn(text);
                        btn.setChecked(false);  // default off

                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                tv_MorSensorID.setText(sensor_list_str);
                tv_MorSensorID.setTextColor(Color.rgb(0, 0, 0));

                for (int i = 0; i < sensor_list.length; i++) {
                    CommandSender.send_command(MorSensorCommand.SetSensorStopTransmission(sensor_list[i]));
//                    CommandSender.send_command(MorSensorCommand.SetSensorTransmissionModeOnce(sensor_list[i]));
                }
                CommandSender.send_command(MorSensorCommand.GetMorSensorVersion());
                break;

//            case MorSensorCommand.IN_SENSOR_DATA:
//                byte sensor_id = values[1];
//                CommandSender.send_command(MorSensorCommand.SetSensorStopTransmission(sensor_id));
//                break;

            case MorSensorCommand.IN_ERROR:
                logging("MorSensor Error Report");
                break;

            default:
                logging("Warning: Incorrect command format");
                break;
        }
    }

    static public void state_machine_wait_morsensor_version (byte[] values) {
        switch (values[0]) {
            case MorSensorCommand.IN_MORSENSOR_VERSION:
                state = STATE_WAIT_FIRMWARE_VERSION;
                logging("i 0x04:MorSensor Version "+ values[1] +"."+ values[2] +"."+ values[3]);
                MorSensorVersion[0] = values[1];
                MorSensorVersion[1] = values[2];
                MorSensorVersion[2] = values[3];
                tv_MorSensorVersion.setText(MorSensorVersion[0] + "." + MorSensorVersion[1] + "." + MorSensorVersion[2]);
                tv_MorSensorVersion.setTextColor(Color.rgb(0, 0, 0));
                CommandSender.send_command(MorSensorCommand.GetFirmwareVersion());
                break;

//            case MorSensorCommand.IN_SENSOR_DATA:
//                byte sensor_id = values[1];
//                CommandSender.send_command(MorSensorCommand.SetSensorStopTransmission(sensor_id));
//                break;
        }
    }

    static public void state_machine_wait_firmware_version (byte[] values) {
        switch (values[0]) {
            case MorSensorCommand.IN_FIRMWARE_VERSION:
                state = STATE_WAIT_DATA;
                logging("i 0x05:Firmware Version "+values[1]+"."+values[2]+"."+values[3]);
                FirmwareVersion[0] = values[1];
                FirmwareVersion[1] = values[2];
                FirmwareVersion[2] = values[3];
                tv_FirmwaveVersion.setText(FirmwareVersion[0] + "." + FirmwareVersion[1] + "." + FirmwareVersion[2]);
                tv_FirmwaveVersion.setTextColor(Color.rgb(0, 0, 0));

//                CommandSender.send_command(MorSensorCommand.Echo());
//                for (int i = 0; i < sensor_list.length; i++) {
//                    CommandSender.send_command(MorSensorCommand.SetSensorTransmissionModeContinuous(sensor_list[i]));
//                    CommandSender.send_command(MorSensorCommand.RetrieveSensorData(sensor_list[i]));
//                }
                break;

//            case MorSensorCommand.IN_SENSOR_DATA:
//                byte sensor_id = values[1];
//                CommandSender.send_command(MorSensorCommand.SetSensorStopTransmission(sensor_id));
//                break;
        }
    }

    static public void state_machine_wait_data (byte[] values) {
        dump_data_packet(values, "i");
        switch (values[0]) {
            case MorSensorCommand.IN_SENSOR_DATA:
                state = STATE_WAIT_DATA;
                process_sensor_data(values);
                break;

//            case MorSensorCommand.IN_ECHO:
//                for (int i = 0; i < sensor_list.length; i++) {
//                    logging("Retrieve sensor data: "+ C.fromByte(sensor_list[i]) +"("+ sensor_list[i] +")");
//                    if (C.fromByte(sensor_list[i]) == 0x80) {
//                        CommandSender.send_command(MorSensorCommand.RetrieveSensorData(sensor_list[i]));
//                    }
//                }
//                CommandSender.send_command(MorSensorCommand.Echo());
//                break;
        }
    }

    static public void dump_data_packet(byte[] data, String from) {
        for (int i = 0; i < 4; i++) {
            String _ = "";
            for (int j = 0; j < 5; j++) {
                _ += String.format("%02X ", data[i * 5 + j]);
            }
            logging(from +" "+ _);
        }
    }

    static public void process_sensor_data(byte[] value) {
        long current_time = System.currentTimeMillis();

        switch (C.fromByte(value[1])) {
            case 0xD0: // IMU
                if (current_time - imu_timestamp >= 200) {
                    //Gryo: value[2][3] / 32.8
                    final float gyro_x = (float) (((short) value[2] * 256 + (short) value[3]) / 32.8); //Gryo x
                    final float gyro_y = (float) (((short) value[4] * 256 + (short) value[5]) / 32.8); //Gryo y
                    final float gyro_z = (float) (((short) value[6] * 256 + (short) value[7]) / 32.8); //Gryo z

                    //Acc: value[8][9] / 4096
                    final float acc_x = (float) (((short) value[8] * 256 + (short) value[9]) / 4096.0) * (float)9.8; //Acc x
                    final float acc_y = (float) (((short) value[10] * 256 + (short) value[11]) / 4096.0) * (float)9.8; //Acc y
                    final float acc_z = (float) (((short) value[12] * 256 + (short) value[13]) / 4096.0) * (float)9.8; //Acc z

                    //Mag: value[15][14] / 3.41 / 100 (注意:MagZ 需乘上-1)
                    final float mag_x = (float) (((short) value[15] * 256 + (short) value[14]) / 3.41 / 100); //Mag x
                    final float mag_y = (float) (((short) value[17] * 256 + (short) value[16]) / 3.41 / 100); //Mag y
                    final float mag_z = (float) (((short) value[19] * 256 + (short) value[18]) / 3.41 / -100); //Mag z

                    String str_gyro1 = new BigDecimal(gyro_x - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";
                    String str_gyro2 = new BigDecimal(gyro_y - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";
                    String str_gyro3 = new BigDecimal(gyro_z - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";

                    String str_acc1 = new BigDecimal(acc_x - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";
                    String str_acc2 = new BigDecimal(acc_y - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";
                    String str_acc3 = new BigDecimal(acc_z - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";

                    String str_mag1 = new BigDecimal(mag_x - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";
                    String str_mag2 = new BigDecimal(mag_y - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";
                    String str_mag3 = new BigDecimal(mag_z - 0.0001).setScale(3, BigDecimal.ROUND_HALF_UP).doubleValue() + "";
                    // to ensure GUI not toooo busy
                    tv_GryoX.setText(str_gyro1); //Gryo x
                    tv_GryoY.setText(str_gyro2); //Gryo y
                    tv_GryoZ.setText(str_gyro3); //Gryo z

                    tv_AccX.setText(str_acc1); //Acc x
                    tv_AccY.setText(str_acc2); //Acc y
                    tv_AccZ.setText(str_acc3); //Acc z

                    tv_MagX.setText(str_mag1); //Mag x
                    tv_MagY.setText(str_mag2); //Mag y
                    tv_MagZ.setText(str_mag3); //Mag z

                    try {
                        JSONArray data = new JSONArray();
                        data.put(gyro_x); data.put(gyro_y); data.put(gyro_z);
                        DAN.push("Gyroscope", data);
                        logging("push(\"Gyroscope\", "+ gyro_x +","+ gyro_y +","+ gyro_z +")");

                        data = new JSONArray();
                        data.put(acc_x); data.put(acc_y); data.put(acc_z);
                        DAN.push("Accelerometer", data);
                        logging("push(\"Accelerometer\", "+ acc_x +","+ acc_y +","+ acc_z +")");

                        data = new JSONArray();
                        data.put(mag_x); data.put(mag_y); data.put(mag_z);
                        DAN.push("Magnetometer", data);
                        logging("push(\"Magnetometer\", "+ mag_x +","+ mag_y +","+ mag_z +")");

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    imu_timestamp = current_time;
                }
                break;

            case 0xC0: // UV
                if (current_time - uv_timestamp >= 200) {
                    final float uv_data = (float) ((((short) value[3]) * 256 + ((short) value[2])) / 100.0);
                    DAN.push("UV", uv_data);
                    logging("push(\"UV\", " + uv_data + ")");
                    uv_timestamp = current_time;
                }
                break;

            case 0x80: // Humidity and Temperature
                if (current_time - humidity_timestamp >= 200) {
                    final float temp_data = (float) (((short) value[2] * 256 + (short) value[3]) * 175.72 / 65536.0 - 46.85);
                    final float humidity_data = (float) (((short) value[4] * 256 + (short) value[5]) * 125.0 / 65536.0 - 6.0);

                    DAN.push("Temperature", temp_data);
                    logging("push(\"Temperature\", " + temp_data + ")");
                    DAN.push("Humidity", humidity_data);
                    logging("push(\"Humidity\", " + humidity_data + ")");

                    humidity_timestamp = current_time;
                }
                break;

            default:
                logging("Unknown sensor id:"+ value[1] +"("+ C.fromByte(value[1]) +")");
                break;
        }
    }

    static private class CommandSender {
        // This class handles command sending / receiving / resending
        static private LinkedBlockingQueue<byte[]> queue;
        static private byte[] pending_command;
        static private boolean pending_command_received;
        static private boolean resend_loop_running;
        static private boolean resend_permission;
        static private Semaphore resending_lock;
        static private Handler resend_loop;
        static private long last_receive_command_timestamp;
        static private long next_send_delay;

        static public void init () {
            queue = new LinkedBlockingQueue<byte[]>();
            pending_command = null;
            resend_loop_running = false;
            resending_lock = new Semaphore(1);
            resend_loop = new Handler();
            resend_permission = true;
            last_receive_command_timestamp = 0;
            next_send_delay = 50;
        }

        static private Runnable resender = new Runnable() {
            @Override
            public void run() {
                logging("[CommandSender] Resender wakeup, state: " + state);
                if (!resend_permission || state == STATE_DISCONNECTED) {
                    return;
                }

                if (state == STATE_RECONNECTING) {
                    if (mBluetoothLeService != null) {
                        mConnected = mBluetoothLeService.connect(mDeviceAddress);
                    }
                    logging("Reconnecting: " + mConnected);
                    if (mConnected) {
                        state = STATE_WAIT_SENSOR_LIST;
                    }
                } else {
                    try {
                        if (pending_command != null) {
                            if (pending_command_received) {
                                // one command just received, retrieve next command
                                pending_command_received = false;
                                pending_command = null;
                                if (!queue.isEmpty()) {
                                    logging("[CommandSender] before queue.take()");
                                    pending_command = queue.take();
                                    logging("[CommandSender] after queue.take()");
                                } else {
                                    logging("[CommandSender] queue is empty");
                                }

                                if (next_send_delay > 50) {
                                    next_send_delay /= 2;
                                    logging("decrease delay to "+ next_send_delay);
                                }
                            } else {
                                // last command not received
                                if (next_send_delay < 1000) {
                                    next_send_delay *= 2;
                                    logging("increase delay to "+ next_send_delay);
                                }
                            }
                        } else {
                            // currently no pending command, take one
                            logging("[CommandSender] pending_command == null");
                            if (!queue.isEmpty()) {
                                logging("[CommandSender] before queue.take()");
                                pending_command = queue.take();
                                logging("[CommandSender] after queue.take()");
                            } else {
                                logging("[CommandSender] queue is empty");
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // queue.take() may take a long time
                    if (!resend_permission || state == STATE_DISCONNECTED) {
                        return;
                    }

                    send_pending_command();
                }

                if (state == STATE_DISCONNECTED) {
                    logging("[CommandSender] Resender ends");
                } else {
                    resend_loop.postDelayed(this, next_send_delay);
                }
            }
        };

        static public void send_command (byte[] command) {
            // this function cannot be blocking, because receiving packet is event-driven
            try {
                queue.put(Arrays.copyOf(command, command.length));
                resending_lock.acquire();
                if (!resend_loop_running) {
                    resend_loop_running = true;
                    pending_command_received = false;
                    resend_loop.postDelayed(resender, 50);  // 50 is not a meaningful number, just a little number
                }
                resending_lock.release();
            } catch (InterruptedException e) {
                logging("[CommandSender] send_command(): InterruptedException");
                e.printStackTrace();
            }
        }

        static public void end () {
            resend_permission = false;
        }

        static public void process_packet (byte[] packet) {
            if (pending_command != null) {
                long current_time = System.currentTimeMillis();
                if (pending_command[0] == MorSensorCommand.IN_SENSOR_DATA && current_time - last_receive_command_timestamp < 50) {
                    logging("morsensor send data too fast");
                    return;
                }
                last_receive_command_timestamp = current_time;

                if (packet[0] == pending_command[0]) {
                    // yeah, we got the command response
                    pending_command_received = true;
                }
            }

            // not a response of previous command, but we can't ignore it
            // because that may be a sensor data packet
            state_machine_input(packet);
        }

        static private void send_pending_command () {
            if (pending_command == null) {
                logging("[CommandSender] send_pending_command(): null");
                return;
            }
            logging("[send_pending_command] resend:");
            dump_data_packet(pending_command, "o");
            try {
                Thread.sleep(50);
                mWriteCharacteristic.setValue(pending_command);
                mBluetoothLeService.writeCharacteristic(mWriteCharacteristic);
            } catch (NullPointerException e) {
                logging("[CommandSender] send_pending_command(): mWriteCharacteristic is not ready yet");
                e.printStackTrace();
            } catch (InterruptedException e) {
                logging("[CommandSender] send_pending_command(): InterruptedException");
                e.printStackTrace();
            }
        }
    }

    // HexString to Byte[]
    static public byte[] hexToBytes(String hexString) {
        char[] hex = hexString.toCharArray();
        //轉rawData長度減半
        int length = hex.length / 2;
        byte[] rawData = new byte[length];
        for (int i = 0; i < length; i++) {
            //先將hex資料轉10進位數值
            int high = Character.digit(hex[i * 2], 16);
            int low = Character.digit(hex[i * 2 + 1], 16);
            //將第一個值的二進位值左平移4位,ex: 00001000 => 10000000 (8=>128)
            //然後與第二個值的二進位值作聯集ex: 10000000 | 00001100 => 10001100 (137)
            int value = (high << 4) | low;
            //與FFFFFFFF作補集
            if (value > 127)
                value -= 256;
            //最後轉回byte就OK
            rawData[i] = (byte) value;
        }
        return rawData;
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharaString = getResources().getString(R.string.unknown_characteristic);
        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            currentServiceData.put(
                    LIST_NAME, SampleGattAttributes.lookup(uuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics)
            {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                currentCharaData.put(
                        LIST_NAME, SampleGattAttributes.lookup(uuid, unknownCharaString));
                currentCharaData.put(LIST_UUID, uuid);
                gattCharacteristicGroupData.add(currentCharaData);

                // Read
                if(gattCharacteristic.getUuid().toString().contains("00002a37-0000-1000-8000-00805f9b34fb"))
                {
                    final int charaProp = gattCharacteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                        // If there is an active notification on a characteristic, clear
                        // it first so it doesn't update the data field on the user interface.
                        if (mReadCharacteristic != null) {
                            mBluetoothLeService.setCharacteristicNotification(
                                    mReadCharacteristic, false);
                            mReadCharacteristic = null;
                        }
                    }
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        mReadCharacteristic = null;
                        mReadCharacteristic = gattCharacteristic;
                        mBluetoothLeService.setCharacteristicNotification(
                                gattCharacteristic, true);
                    }
                }

                // SendCommands
                if(gattCharacteristic.getUuid().toString().contains("00001525-1212-efde-1523-785feabcd123"))
                {
                    final int charaProp = gattCharacteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                        // If there is an active notification on a characteristic, clear
                        // it first so it doesn't update the data field on the user interface.
                        if (mWriteCharacteristic != null) {
                            mWriteCharacteristic = null;
                        }
                        mWriteCharacteristic = gattCharacteristic;
                    }
                }
            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }
    }

    static private IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    @Override
    public  boolean  onKeyDown ( int  keyCode ,  KeyEvent event )  {
        if  ( keyCode  ==  KeyEvent. KEYCODE_BACK )  {
            AlertDialog.Builder builder = new AlertDialog.Builder(this); //創建訊息方塊
            builder.setMessage("確定要離開？");
            builder.setTitle("離開");
            builder.setPositiveButton("確認", new DialogInterface.OnClickListener()  {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss(); //dismiss為關閉dialog,Activity還會保留dialog的狀態
                    if(DeviceScanActivity.mDeviceScanActivity!=null)
                        DeviceScanActivity.mDeviceScanActivity.finish();

                    BtDisConnect();
                }
            });

            builder.setNegativeButton("取消", new DialogInterface.OnClickListener()  {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss(); //dismiss為關閉dialog,Activity還會保留dialog的狀態
                }
            });
            builder.create().show();
            return  false ;
        }

        return  super.onKeyDown ( keyCode ,  event );
    }

    static private void logging (String _) {
       Log.i(C.log_tag, "[IMUViewActivity]"+ _);
    }
}
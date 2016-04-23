package tw.org.cic.imusensor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import DAN.DAN;

public class FeatureManagerActivity extends Activity {
    static TableLayout table_monitor;
    static HashMap<String, TextView> monitor_pool;
    static HashMap<String, Long> timestamp_pool;
    static LinearLayout ll_feature_switches;
    final IDAManager morsensor_idamanager = MorSensorIDAManager.instance();
    final DAN.Subscriber dan_event_subscriber = new DANEventSubscriber();
    final IDAManager.Subscriber ida_event_subscriber = new IDAEventSubscriber();
    boolean user_request_disconnect;
    final HashSet<Byte> waiting_sensor = new HashSet<Byte>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_feature_manager);
        logging("================== FeatureManagerActivity start ==================");

        table_monitor = (TableLayout)findViewById(R.id.table_monitor);
        monitor_pool = new HashMap<String, TextView>();
        timestamp_pool = new HashMap<String, Long>();

        ll_feature_switches = (LinearLayout)findViewById(R.id.ll_feature_switches);

        show_ec_status(true, DAN.ec_endpoint());
        ((TextView)findViewById(R.id.tv_d_name)).setText(DAN.get_d_name());

        String morsensor_version_str = (String) MorSensorIDAManager.instance().get_info(Constants.INFO_MORSENSOR_VERSION);
        String firmware_version_str = (String) MorSensorIDAManager.instance().get_info(Constants.INFO_FIRMWARE_VERSION);
        LinearLayout ll_feature_switches = (LinearLayout)findViewById(R.id.ll_feature_switches);

        String sensor_list_str = "";
        for (byte sensor_id: (ArrayList<Byte>)MorSensorIDAManager.instance().get_info(Constants.INFO_SENSOR_LIST)) {
            sensor_list_str += Constants.fromByte(sensor_id) +" ";
            String btn_text = "";
            for (String df_name: Constants.get_feature_list_from_sensor_id(sensor_id)) {
                btn_text += df_name + " ";
            }
            ToggleButton btn = new ToggleButton(this);
            btn.setTag(sensor_id);
            btn.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            btn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    byte sensor_id = (Byte)buttonView.getTag();
                    logging("Feature button %02X clicked", sensor_id);
                    if (isChecked) {
                        // The toggle is now enabled
                        morsensor_idamanager.write(MorSensorCommand.SetSensorTransmissionModeContinuous(sensor_id));
                        morsensor_idamanager.write(MorSensorCommand.RetrieveSensorData(sensor_id));
                    } else {
                        // The toggle is now disabled
                        morsensor_idamanager.write(MorSensorCommand.SetSensorStopTransmission(sensor_id));
                    }
                }
            });

            btn.setText(btn_text);
            btn.setTextOff(btn_text);
            btn.setTextOn(btn_text);
            btn.setChecked(false);  // default off

            ll_feature_switches.addView(btn);
        }
        ((TextView)findViewById(R.id.tv_morsensor_version)).setText(morsensor_version_str);
        ((TextView)findViewById(R.id.tv_firmware_version)).setText(firmware_version_str);
        ((TextView)findViewById(R.id.tv_sensor_list)).setText(sensor_list_str);

        final Button btn_deregister = (Button)findViewById(R.id.btn_deregister);
        btn_deregister.setOnClickListener(new View.OnClickListener () {
            @Override
            public void onClick (View v) {
                DAN.deregister();
                DAN.shutdown();
                if (!user_request_disconnect) {
                    for (byte sensor_id: (ArrayList<Byte>) MorSensorIDAManager.instance().get_info(Constants.INFO_SENSOR_LIST)) {
                        morsensor_idamanager.write(MorSensorCommand.SetSensorStopTransmission(sensor_id));
                        waiting_sensor.add(sensor_id);
                    }
                    user_request_disconnect = true;
                } else {
                    morsensor_idamanager.disconnect();
                    MorSensorIDAManager.instance().shutdown();
                    Utils.remove_all_notification(FeatureManagerActivity.this);
                    finish();
                    return;
                }
            }
        });

        DAN.subscribe("Control_channel", dan_event_subscriber);
        morsensor_idamanager.subscribe(ida_event_subscriber);
    }

    @Override
    public void onPause () {
        super.onPause();
        DAN.unsubcribe(dan_event_subscriber);
    }

    public void show_ec_status (boolean status, String host) {
        ((TextView)findViewById(R.id.tv_ec_endpoint)).setText(host);
        TextView tv_ec_host_status = (TextView)findViewById(R.id.tv_ec_status);
        String status_str;
        int status_color;
        if (status) {
            status_str = "~";
            status_color = Color.rgb(0, 128, 0);
        } else {
            status_str = "!";
            status_color = Color.rgb(128, 0, 0);
        }

        tv_ec_host_status.setText(status_str);
        tv_ec_host_status.setTextColor(status_color);
    }



    class DANEventSubscriber extends DAN.Subscriber {
        public void odf_handler (final DAN.ODFObject odf_object) {
            runOnUiThread(new Thread () {
                @Override
                public void run () {
                    switch (odf_object.event_tag) {
                        case REGISTER_FAILED:
                            show_ec_status(false, odf_object.message);
                            Utils.show_ec_status_on_notification(FeatureManagerActivity.this, odf_object.message, false);
                            break;
                        case REGISTER_SUCCEED:
                            show_ec_status(true, odf_object.message);
                            break;
                    }
                }
            });
        }
    }

    class IDAEventSubscriber implements IDAManager.Subscriber {
        @Override
        public void on_event(IDAManager.EventTag event_tag, Object message) {
            switch (event_tag) {
                case INITIALIZATION_FAILED:
                    break;
                case INITIALIZED:
                    break;
                case SEARCHING_STARTED:
                    break;
                case FOUND_NEW_IDA:
                    break;
                case SEARCHING_STOPPED:
                    break;
                case CONNECTION_FAILED:
                    break;
                case CONNECTED:
                    break;
                case WRITE_FAILED:
                    break;
                case DATA_AVAILABLE:
                    byte[] data = (byte[]) message;
                    if (data[0] == MorSensorCommand.IN_SENSOR_DATA) {
                        switch(Constants.fromByte(data[1])) {
                            case 0xD0:
                                SensorDataHandlers.sensor_handler_D0(data);
                                break;
                            case 0xC0:
                                SensorDataHandlers.sensor_handler_C0(data);
                                break;
                            case 0x80:
                                SensorDataHandlers.sensor_handler_80(data);
                                break;
                            default:
                                logging("Unknown sensor id: %02X", data[1]);
                        }
                    } else if (user_request_disconnect && data[0] == MorSensorCommand.IN_STOP_TRANSMISSION) {
                        if (waiting_sensor.contains(data[1])) {
                            waiting_sensor.remove(data[1]);
                        }

                        if (waiting_sensor.size() == 0) {
                            morsensor_idamanager.disconnect();
                            MorSensorIDAManager.instance().shutdown();
                            Utils.remove_all_notification(FeatureManagerActivity.this);
                            finish();
                            return;
                        }
                    }
                    break;
                case DISCONNECTION_FAILED:
                    break;
                case DISCONNECTED:
                    break;
            }
        }
    }

    static private void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    static private void logging (String _) {
       Log.i(Constants.log_tag, "[FeatureManagerActivity] "+ _);
    }
}
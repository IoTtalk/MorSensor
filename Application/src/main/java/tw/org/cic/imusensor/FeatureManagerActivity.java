package tw.org.cic.imusensor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;

import DAN.DAN;

public class FeatureManagerActivity extends Activity {
    static TableLayout table_monitor;
    static HashMap<String, TextView> monitor_pool;
    static HashMap<String, Long> timestamp_pool;
    static LinearLayout ll_feature_switches;
    final IDAManager morsensor_idamanager = MorSensorIDAManager.instance();
    final DAN.Subscriber event_subscriber = new EventSubscriber();

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
        String sensor_list_str = "";
        for (byte b: (ArrayList<Byte>)MorSensorIDAManager.instance().get_info(Constants.INFO_SENSOR_LIST)) {
            sensor_list_str += Constants.fromByte(b) +" ";
        }
        ((TextView)findViewById(R.id.tv_morsensor_version)).setText(morsensor_version_str);
        ((TextView)findViewById(R.id.tv_firmware_version)).setText(firmware_version_str);
        ((TextView)findViewById(R.id.tv_sensor_list)).setText(sensor_list_str);

        DAN.subscribe("Control_channel", event_subscriber);
    }

    @Override
    public void onPause () {
        super.onPause();
        DAN.unsubcribe(event_subscriber);
        if (isFinishing()) {
            morsensor_idamanager.disconnect();
            ((MorSensorIDAManager) morsensor_idamanager).shutdown();

            Utils.remove_all_notification(FeatureManagerActivity.this);
            DAN.deregister();
            DAN.shutdown();
        }
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



    class EventSubscriber extends DAN.Subscriber {
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

    static private void logging (String _) {
       Log.i(Constants.log_tag, "[FeatureManagerActivity]"+ _);
    }
}
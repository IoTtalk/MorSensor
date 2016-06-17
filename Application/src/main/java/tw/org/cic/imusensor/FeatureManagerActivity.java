package tw.org.cic.imusensor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

import DAN.DAN;

public class FeatureManagerActivity extends Activity implements ServiceConnection {
    /* -------------------------- */
    /* Code for ServiceConnection */
    /* ========================== */
    IDAapi morsensor_ida_api;
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        morsensor_ida_api = ((MorSensorIDAapi.LocalBinder) service).getService();
        ((MorSensorIDAapi)morsensor_ida_api).morsensor_info_displayer = new MorSensorInfoDisplayer();
        DAN.subscribe("__Ctl_O__", new DAN.Subscriber() {
            @Override
            public void odf_handler(String odf, DAN.ODFObject odf_object) {
                morsensor_ida_api.write(odf, odf_object.data);
            }
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        logging("onServiceDisconnected()");
        morsensor_ida_api = null;
    }
    /* -------------------------- */

    static class MorSensorInfoDisplayer extends MorSensorIDAapi.AbstactMorSensorInfoDisplayer {
        @Override
        public void display(String key, Object... values) {
            switch (key) {
                case "CONNECTION_FAILED":
                    dialog.show();
                    break;
                case "MORSENSOR_OK":
                    dialog.cancel();
                    break;
            }
        }
    }

    final String indicator_prefix = "indicator_";
    final int indicator_light_on = Color.rgb(0, 0, 255);
    final int indicator_light_off = Color.rgb(255, 0, 255);
    final int indicator_light_wait = Color.rgb(180, 180, 0);
    final int indicator_light_done = Color.rgb(0, 180, 0);
    static AlertDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_feature_manager);
        logging("================== FeatureManagerActivity start ==================");

        AlertDialog.Builder dialog_builder = new AlertDialog.Builder(FeatureManagerActivity.this);
        dialog_builder.setTitle("MorSensor disconnected!");
        dialog_builder.setMessage("Wait for it, or click \"Deregister\" button to leave");
        dialog_builder.setPositiveButton("Deregister", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int id) {
                finish();
            }
        });
        dialog = dialog_builder.create();

        show_ec_status(true, DAN.ec_endpoint());
        ((TextView)findViewById(R.id.tv_d_name)).setText(DAN.get_d_name());

        String morsensor_version_str = ((MorSensorApplication) getApplication()).morsensor_version;
        ((TextView)findViewById(R.id.tv_morsensor_version)).setText(morsensor_version_str);

        String firmware_version_str = ((MorSensorApplication) getApplication()).firmware_version;
        ((TextView)findViewById(R.id.tv_firmware_version)).setText(firmware_version_str);

//        setup_feature_switches();

        final Button btn_deregister = (Button)findViewById(R.id.btn_deregister);
        btn_deregister.setOnClickListener(new View.OnClickListener () {
            @Override
            public void onClick (View v) {
                finish();
            }
        });

        Intent intent = new Intent(this, MorSensorIDAapi.class);
        startService(intent);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onPause () {
        super.onPause();
        if (isFinishing()) {
            DAN.deregister();
            DAN.shutdown();
            Utils.remove_all_notification(FeatureManagerActivity.this);
            if (morsensor_ida_api != null) {
                morsensor_ida_api.disconnect();

                Intent intent = new Intent(this, MorSensorIDAapi.class);
                unbindService(this);
                stopService(intent);
            }
        }
    }

//    private void setup_feature_switches() {
//        TableLayout tl_feature_switches = (TableLayout)findViewById(R.id.tl_feature_switches);
//
//        for (byte sensor_id: (ArrayList<Byte>) MorSensorIDAapi.instance().get_info(Constants.INFO_SENSOR_LIST)) {
//            TableRow tr = new TableRow(this);
//            tr.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT));
//
//            ToggleButton btn = new ToggleButton(this);
//            btn.setTag(sensor_id);
//            btn.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT));
//            btn.setTextAppearance(this, android.R.style.TextAppearance_Medium);
//            btn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
//                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
//                    byte sensor_id = (Byte)buttonView.getTag();
//                    logging("Feature button %02X clicked", sensor_id);
//                    if (isChecked) {
//                        // The toggle is now enabled
//                        morsensor_idamanager.write(MorSensorCommandTable.SetSensorTransmissionModeContinuous(sensor_id));
//                        morsensor_idamanager.write(MorSensorCommandTable.RetrieveSensorData(sensor_id));
//                    } else {
//                        // The toggle is now disabled
//                        morsensor_idamanager.write(MorSensorCommandTable.SetSensorStopTransmission(sensor_id));
//                    }
//                }
//            });
//
//            String btn_text = String.format("%02X", Constants.fromByte(sensor_id));
//            logging("Create button %s", btn_text);
//            btn.setText(btn_text);
//            btn.setTextOff(btn_text);
//            btn.setTextOn(btn_text);
//            btn.setChecked(false);  // default off
//
//            tr.addView(btn);
//
//            TextView tv_feature_names = new TextView(this);
//            tv_feature_names.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT));
//            tv_feature_names.setTextAppearance(this, android.R.style.TextAppearance_Medium);
//            String feature_names = "";
//            for (String df_name: Constants.get_df_list(sensor_id)) {
//                feature_names += df_name +" ";
//            }
//            tv_feature_names.setText(feature_names);
//            tv_feature_names.setTag(indicator_prefix+ btn_text);
//
//            tr.addView(tv_feature_names);
//            tl_feature_switches.addView(tr);
//        }
//    }

    public void show_ec_status (boolean status, String host) {
        ((TextView)findViewById(R.id.tv_ec_endpoint)).setText(host);
        TextView tv_ec_host_status = (TextView)findViewById(R.id.tv_ec_status);
        String status_str;
        int status_color;
        if (status) {
            status_str = "~";
            status_color = Color.rgb(0, 128, 0);
        } else {
            status_str = "x";
            status_color = Color.rgb(128, 0, 0);
        }

        tv_ec_host_status.setText(status_str);
        tv_ec_host_status.setTextColor(status_color);
    }

//    class DANEventSubscriber extends DAN.Subscriber {
//        public void odf_handler (String feature, final DAN.ODFObject odf_object) {
//            runOnUiThread(new Thread () {
//                @Override
//                public void run () {
//                    switch (odf_object.event) {
//                        case REGISTER_FAILED:
//                            show_ec_status(false, odf_object.message);
//                            Utils.show_ec_status_on_notification(FeatureManagerActivity.this, odf_object.message, false);
//                            break;
//                        case REGISTER_SUCCEED:
//                            show_ec_status(true, odf_object.message);
//                            break;
//                    }
//                }
//            });
//        }
//    }
//
//    class IDAEventSubscriber implements IDAapi.Subscriber {
//        @Override
//        public void on_event(IDAapi.Event event, Object message) {
//            switch (event) {
//                case CONNECTION_FAILED:
//                    logging("CONNECTION_FAILED");
//                    state = State.RECONNECTING;
//                    runOnUiThread(new Runnable() {
//                        @Override
//                        public void run() {
//                            TableLayout tl_feature_switches = (TableLayout)findViewById(R.id.tl_feature_switches);
//                            tl_feature_switches.removeAllViews();
//                        }
//                    });
//                    dialog.show();
//                    break;
//                case CONNECTED:
//                    logging("(re)CONNECTED");
//                    state = State.WAITING_FEATURE_LIST;
//                    morsensor_idamanager.write(MorSensorCommandTable.GetSensorList());
//                    dialog.cancel();
//                    break;
//                case WRITE_FAILED:
//                    break;
//                case DATA_AVAILABLE:
//                    byte[] data = (byte[]) message;
//                    switch (state) {
//                        case NORMAL:
//                            handle_sensor_data(data);
//                            break;
//                        case DISCONNECTING:
//                            if (data[0] == MorSensorCommandTable.IN_STOP_TRANSMISSION) {
//                                handle_sensor_stop(data[1]);
//                            }
//                            break;
//                        case WAITING_FEATURE_LIST:
//                            handle_sensor_list(data);
//                    }
//                    break;
//                case DISCONNECTION_FAILED:
//                    break;
//                case DISCONNECTED:
//                    break;
//                default:
//                    logging("Event %s should not happen", event.name());
//            }
//        }
//    }
//
//
//    private void handle_sensor_data (byte[] data) {
//        if (data[0] == MorSensorCommandTable.IN_SENSOR_DATA) {
//            switch (Constants.fromByte(data[1])) {
//                case 0xD0:
//                    SensorDataHandlers.sensor_handler_D0(data);
//                    toggle_indicator(data[1]);
//                    break;
//                case 0xC0:
//                    SensorDataHandlers.sensor_handler_C0(data);
//                    toggle_indicator(data[1]);
//                    break;
//                case 0x80:
//                    SensorDataHandlers.sensor_handler_80(data);
//                    toggle_indicator(data[1]);
//                    break;
//                default:
//                    logging("Unknown sensor id: %02X", data[1]);
//            }
//        }
//    }
//
//    private void toggle_indicator(byte sensor_id) {
//        TableLayout tl_feature_switches = (TableLayout)findViewById(R.id.tl_feature_switches);
//        TextView indicator = (TextView)tl_feature_switches.findViewWithTag(String.format("%s%02X", indicator_prefix, Constants.fromByte(sensor_id)));
//
//        if (indicator != null) {
//            if (indicator.getCurrentTextColor() == indicator_light_on) {
//                indicator.setTextColor(indicator_light_off);
//            } else {
//                indicator.setTextColor(indicator_light_on);
//            }
//        } else {
//            logging("indicator null");
//        }
//    }
//
//    private void set_indicator(byte sensor_id, int color) {
//        TableLayout tl_feature_switches = (TableLayout)findViewById(R.id.tl_feature_switches);
//        TextView indicator = (TextView)tl_feature_switches.findViewWithTag(String.format("%s%02X", indicator_prefix, Constants.fromByte(sensor_id)));
//
//        if (indicator != null) {
//            indicator.setTextColor(color);
//        } else {
//            logging("indicator null");
//        }
//    }
//
//    private void handle_sensor_stop (byte sensor_id) {
//        if (waiting_sensor.contains(sensor_id)) {
//            waiting_sensor.remove(sensor_id);
//            set_indicator(sensor_id, indicator_light_done);
//        }
//
//        if (waiting_sensor.size() == 0) {
//            morsensor_idamanager.disconnect();
//            MorSensorIDAapi.instance().shutdown();
//            Utils.remove_all_notification(FeatureManagerActivity.this);
//            finish();
//            return;
//        }
//    }
//
//    private void handle_sensor_list (byte[] data) {
//        if (data[0] == MorSensorCommandTable.IN_SENSOR_LIST) {
//            ArrayList<Byte> sensor_list = new ArrayList<Byte>();
//            for (int i = 0; i < data[1]; i++) {
//                logging("IDFhandler %02X:", data[i + 2]);
//                sensor_list.add(data[i + 2]);
//                morsensor_idamanager.write(MorSensorCommandTable.SetSensorStopTransmission(data[i + 2]));
//            }
//            ((MorSensorIDAapi) morsensor_idamanager).put_info(Constants.INFO_SENSOR_LIST, sensor_list);
//
//            runOnUiThread(new Runnable() {
//                @Override
//                public void run() {
//                    setup_feature_switches();
//                }
//            });
//
//            state = State.NORMAL;
//
//            // reregister due to feature list changed
//            String clean_mac_addr = DAN.get_clean_mac_addr(Utils.get_mac_addr(FeatureManagerActivity.this));
//            final ArrayList<String> df_list = new ArrayList<String>();
//            for (byte b: (ArrayList<Byte>) MorSensorIDAapi.instance().get_info(Constants.INFO_SENSOR_LIST)) {
//                for (String df_name: Constants.get_df_list(b)) {
//                    df_list.add(df_name);
//                }
//            }
//
//            JSONObject profile = new JSONObject();
//            try {
//                profile.put("d_name", "MorSensor"+ clean_mac_addr.substring(0, 2) + clean_mac_addr.substring(10));
//                profile.put("dm_name", Constants.dm_name);
//                JSONArray feature_list = new JSONArray();
//                for (String f: df_list) {
//                    feature_list.put(f);
//                }
//                profile.put("df_list", feature_list);
//                profile.put("u_name", Constants.u_name);
//                profile.put("monitor", clean_mac_addr);
//                DAN.register(DAN.ec_endpoint(), clean_mac_addr, profile);
//            } catch (JSONException e) {
//                e.printStackTrace();
//            }
//
//        }
//    }

    static private void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    static private void logging (String _) {
       Log.i(Constants.log_tag, "[FeatureManagerActivity] "+ _);
    }
}
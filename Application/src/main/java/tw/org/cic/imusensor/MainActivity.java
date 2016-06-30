package tw.org.cic.imusensor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

public class MainActivity extends Activity implements ServiceConnection {
    class UIhandler extends Handler {
        class Information {
            String key;
            Object[] values;
            public Information(String key, Object... values) {
                this.key = key;
                this.values = values;
            }
        }
        final public void send_info (String key, Object... values) {
            Message msg_obj = this.obtainMessage();
            msg_obj.obj = new Information(key, values);
            this.sendMessage(msg_obj);
        }
        @Override
        final public void handleMessage (Message msg) {
            Information info = (Information) msg.obj;
            show_info(info.key, info.values);
        }
        public void show_info (String key, Object... values) {
            switch (key) {
                case "INITIALIZATION_FAILED":
                    String message = (String)(values[0]);
                    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                    finish();
                    break;
                case "SEARCH_STARTED":
                    ((TextView)findViewById(R.id.tv_morsensor_addr)).setText(R.string.searching);
                    break;
                case "IDA_DISCOVERED":
                    String device_addr = (String)(values[0]);
                    ((TextView)findViewById(R.id.tv_morsensor_addr)).setText(device_addr);
                    findViewById(R.id.df_list_prompt).setVisibility(View.VISIBLE);
                    break;
                case "CONNECTING":
                    ((TextView)findViewById(R.id.tv_morsensor_version)).setText(R.string.connecting);
                    ((TextView)findViewById(R.id.tv_firmware_version)).setText(R.string.connecting);
                    ((LinearLayout)findViewById(R.id.ll_df_status)).removeAllViews();
                    break;
                case "MORSENSOR_VERSION":
                    message = (String)(values[0]);
                    ((TextView)findViewById(R.id.tv_morsensor_version)).setText(message);
                    break;
                case "FIRMWARE_VERSION":
                    message = (String)(values[0]);
                    ((TextView)findViewById(R.id.tv_firmware_version)).setText(message);
                    break;
                case "DF_LIST":
                    logging("DF_LIST: %s -> %s", df_list, (JSONArray)(values[0]));
                    df_list = (JSONArray)(values[0]);
                    LinearLayout ll_df_status = (LinearLayout)findViewById(R.id.ll_df_status);
                    ll_df_status.removeAllViews();
                    LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    for (int i = 0; i < df_list.length(); i++) {
                        String df_name = "<JSONException>";
                        try {
                            df_name = df_list.getString(i);
                        } catch (JSONException e) {
                            logging("UI: JSONException");
                            continue;
                        }
                        View inflated_view = inflater.inflate(R.layout.item_df_status, null);
                        CheckBox cb_status = (CheckBox) inflated_view.findViewById(R.id.cb_status);
                        cb_status.setTag(df_name);
                        TextView tv_df_name = (TextView) inflated_view.findViewById(R.id.tv_df_name);
                        tv_df_name.setText(df_name);
                        cb_status.setEnabled(false);
                        ll_df_status.addView(inflated_view);
                    }
                    break;
                case "REGISTRATION_SUCCEED":
                    String endpoint = (String)(values[0]);
                    ((TextView)findViewById(R.id.tv_endpoint)).setText(endpoint);
                    break;
                case "SET_DF_STATUS":
                    String flags = (String)(values[0]);
                    logging(flags);
                    if (flags.length() != df_list.length()) {
                        logging("SET_DF_STATUS flag length & df_list mismatch, abort");
                        return;
                    }
                    for (int i = 0; i < df_list.length(); i++) {
                        String df_name = "<JSONException>";
                        try {
                            df_name = df_list.getString(i);
                        } catch (JSONException e) {
                            logging("UI: JSONException");
                            continue;
                        }
                        ll_df_status = (LinearLayout)findViewById(R.id.ll_df_status);
                        CheckBox cb_status = (CheckBox) ll_df_status.findViewWithTag(df_name);
                        if (flags.charAt(i) == '0') {
                            cb_status.setChecked(false);
                        } else {
                            cb_status.setChecked(true);
                        }
                    }
                    break;
                default:
                    logging("Unknown key: %s", key);
            }
        }
    }

    UIhandler ui_handler = new UIhandler();
    BLEIDA ble_ida;
    DAN dan;
    JSONArray df_list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        logging("========================= onCreate() =========================");

        Intent intent = new Intent(this, BLEIDA.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        logging("onServiceConnected()");
        ble_ida = ((BLEIDA.LocalBinder) service).getService();
        dan = new DAN();
        new DAI(ble_ida, dan, ui_handler).start();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        logging("onServiceDisconnected()");
        ble_ida = null;
    }

    @Override
    public void onPause () {
        super.onPause();
        if (isFinishing()) {
            new Thread () {
                @Override
                public void run() {
                    dan.deregister();
                }
            }.start();
            ble_ida.disconnect();
            this.unbindService(this);
        }
    }

    static private void logging (String format, Object... args) {
        Log.i("MorSensor", String.format("[MainActivity] "+ format, args));
    }
}

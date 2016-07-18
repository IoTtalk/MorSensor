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
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
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
                    ((TextView) findViewById(R.id.tv_suspended)).setText("Suspended");
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
                case "SUSPENDED":
                    ((TextView) findViewById(R.id.tv_suspended)).setText("Suspended");
                    break;
                case "RESUMED":
                    ((TextView) findViewById(R.id.tv_suspended)).setText("Resumed");
                    break;
                default:
                    logging("Unknown key: %s", key);
            }
        }
    }

    UIhandler ui_handler = new UIhandler();
    BLEIDA ble_ida;
    DAN dan;
    DAI dai;
    JSONArray df_list;
    String endpoint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        logging("========================= onCreate() =========================");

        findViewById(R.id.btn_auto_search).setOnClickListener(new View.OnClickListener () {
            @Override
            public void onClick (View v) {
                endpoint = null;
                ((TextView)findViewById(R.id.tv_endpoint)).setText("Searching...");
                ((LinearLayout) findViewById(R.id.ll_endpoint_panel)).removeAllViews();
                findViewById(R.id.morsensor_addr_prompt).setVisibility(View.VISIBLE);
                Intent intent = new Intent(MainActivity.this, BLEIDA.class);
                bindService(intent, MainActivity.this, Context.BIND_AUTO_CREATE);
            }
        });

        findViewById(R.id.btn_set_endpoint).setOnClickListener(new View.OnClickListener () {
            @Override
            public void onClick (View v) {
                endpoint = ((EditText) findViewById(R.id.et_endpoint)).getText().toString();
                if (!endpoint.startsWith("http://")) {
                    endpoint = "http://" + endpoint;
                }

                if (endpoint.length() - endpoint.replace(":", "").length() == 1) {
                    endpoint += ":9999";
                }
                ((TextView)findViewById(R.id.tv_endpoint)).setText(endpoint);

                ((LinearLayout) findViewById(R.id.ll_endpoint_panel)).removeAllViews();
                findViewById(R.id.morsensor_addr_prompt).setVisibility(View.VISIBLE);
                Intent intent = new Intent(MainActivity.this, BLEIDA.class);
                bindService(intent, MainActivity.this, Context.BIND_AUTO_CREATE);
            }
        });
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        logging("onServiceConnected()");
        ble_ida = ((BLEIDA.LocalBinder) service).getService();
        dan = new DAN();
        dai = new DAI(endpoint, ble_ida, dan, ui_handler);
        dai.start();
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
            if (ble_ida != null) {
                this.unbindService(this);
            }
            if (dai != null) {
                (new Thread() {
                    public void run() {
                        dai.deregister();
                    }
                }).start();
            }
        }
    }

    static private void logging (String format, Object... args) {
        Log.i("MorSensor", String.format("[MainActivity] "+ format, args));
    }
}

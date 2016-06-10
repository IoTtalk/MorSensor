package tw.org.cic.imusensor;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import DAN.DAN;

public class SelectMorSensorActivity extends Activity implements ServiceConnection {
    /* -------------------------- */
    /* Code for ServiceConnection */
    /* ========================== */
    IDAapi morsensor_ida_api;
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        morsensor_ida_api = ((MorSensorIDAapi.LocalBinder) service).getService();
        morsensor_ida_api.init(this);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        logging("onServiceDisconnected()");
        morsensor_ida_api = null;
    }
    /* -------------------------- */

    final ArrayList<MorSensorListItem> morsensor_list = new ArrayList<>();
    ArrayAdapter<MorSensorListItem> adapter;
    boolean ble_scanning;
    boolean morsensor_connected;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        logging("================== SelectMorSensorActivity start ==================");
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_select_morsensor);
        getActionBar().setTitle(R.string.title_devices);

        adapter = new MorSensorListAdapter(this, R.layout.item_morsensor_list, morsensor_list);

        // show available MorSensors
        final ListView lv_available_morsensors = (ListView) findViewById(R.id.lv_available_morsensors);
        lv_available_morsensors.setAdapter(adapter);
        lv_available_morsensors.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent,
                                    View view, int position, long id) {
                MorSensorListItem morsensor_item = morsensor_list.get(position);
                TextView tv_hint = (TextView)findViewById(R.id.tv_hint);
                tv_hint.setText(getResources().getString(R.string.connecting));
                tv_hint.setVisibility(View.VISIBLE);
                findViewById(R.id.ll_morsensor_info).setVisibility(View.VISIBLE);
                morsensor_ida_api.connect(morsensor_item.id);
            }
        });

        Intent intent = new Intent(this, MorSensorIDAapi.class);
        startService(intent);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isFinishing()) {
            unbindService(this);
            if (!morsensor_connected) {
                Intent intent = new Intent(this, MorSensorIDAapi.class);
                stopService(intent);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == Constants.REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_select_morsensor, menu);
//        menu.findItem(R.id.item_scan).setVisible(true);

        menu.add(0, Constants.MENU_ITEM_ID_DAN_VERSION, 0, "DAN Version: "+ DAN.version);
        menu.add(0, Constants.MENU_ITEM_ID_DAI_VERSION, 0, "DAI Version: "+ Constants.version);
        menu.add(0, Constants.MENU_ITEM_WIFI_SSID, 0, "WiFi: "+ Utils.get_wifi_ssid(getApplicationContext()));

        if (ble_scanning) {
            menu.findItem(R.id.item_scan).setTitle(R.string.menu_scanning);
        } else {
            menu.findItem(R.id.item_scan).setTitle(R.string.menu_scan);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.item_scan:
                if (!ble_scanning) {
                    morsensor_list.clear();
                    adapter.notifyDataSetChanged();
                    morsensor_ida_api.search();
                }
                break;
        }
        return true;
    }

    public class MorSensorListItem {
        String id;
        String name;
        int rssi;
        public boolean connecting;

        public MorSensorListItem(String id) {
            this.id = id;
            this.name = "<null>";
            this.rssi = 0;
            this.connecting = false;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MorSensorListItem)) {
                return false;
            }
            MorSensorListItem another = (MorSensorListItem) obj;
            return this.id.equals(another.id);
        }
    }

    public class MorSensorListAdapter extends ArrayAdapter<MorSensorListItem> {
        Context context;
        int layout_resource_id;
        ArrayList<MorSensorListItem> data = null;

        public MorSensorListAdapter(Context context, int layout_resource_id, ArrayList<MorSensorListItem> data) {
            super(context, layout_resource_id, data);
            this.context = context;
            this.layout_resource_id = layout_resource_id;
            this.data = data;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            MorSensorHolder holder = null;

            if (row == null) {
                LayoutInflater inflater = ((Activity) context).getLayoutInflater();
                row = inflater.inflate(layout_resource_id, parent, false);
                holder = new MorSensorHolder();
                holder.tv_name = (TextView) row.findViewById(R.id.tv_name);
                holder.tv_addr = (TextView) row.findViewById(R.id.tv_addr);
                holder.tv_rssi = (TextView) row.findViewById(R.id.tv_rssi);
//	            holder.connecting = (TextView)row.findViewById(R.id.tv_connecting);
                row.setTag(holder);
            } else {
                holder = (MorSensorHolder) row.getTag();
            }

            MorSensorListItem morsensor_list_item = data.get(position);
            holder.tv_name.setText(morsensor_list_item.name == null ? "<null>" : morsensor_list_item.name);
            holder.tv_addr.setText(morsensor_list_item.id);
            holder.tv_rssi.setText(String.format("%d", morsensor_list_item.rssi));
//	        if (i.connecting) {
//		        holder.connecting.setText("...");
//	        } else {
//	        	holder.connecting.setText("");
//	        }
            return row;
        }

        class MorSensorHolder {
            TextView tv_name;
            TextView tv_addr;
            TextView tv_rssi;
//	        TextView connecting;
        }
    }

    public void receive(final String odf, final JSONArray data) {
        try {
            if (odf.equals("Control")) {
                final String command = data.getString(0);
                final JSONArray args = data.getJSONObject(1).getJSONArray("args");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        switch (command) {
                            case "INITIALIZATION_FAILED":
                                try {
                                    String message = args.getString(0);
                                    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                                } catch (JSONException e) {
                                    logging("JSONException of INITIALIZATION_FAILED");
                                }
                                finish();
                                return;
                            case "INITIALIZATION_SUCCEEDED":
                                morsensor_ida_api.search();
                                break;
                            case "SEARCH_STARTED":
                                ble_scanning = true;
                                invalidateOptionsMenu();
                                TextView tv_hint = (TextView) findViewById(R.id.tv_hint);
                                tv_hint.setText(getResources().getString(R.string.searching));
                                tv_hint.setVisibility(View.VISIBLE);
                                break;
                            case "IDA_DISCOVERED":
                                try {
                                    String id = args.getString(0);
                                    String name = args.getString(1);
                                    int rssi = args.getInt(2);
                                    MorSensorListItem morsensor_item = new MorSensorListItem(id);

                                    int index = morsensor_list.indexOf(morsensor_item);
                                    if (index == -1) {
                                        morsensor_list.add(morsensor_item);
                                    } else {
                                        morsensor_item = morsensor_list.get(index);
                                    }

                                    morsensor_item.name = name;
                                    morsensor_item.rssi = rssi;
                                    adapter.notifyDataSetChanged();
                                } catch (JSONException e) {
                                    logging("JSONException of IDA_DISCOVERED");
                                }
                                break;
                            case "SEARCH_STOPPED":
                                ble_scanning = false;
                                invalidateOptionsMenu();
                                findViewById(R.id.tv_hint).setVisibility(View.GONE);
                                break;
                            case "CONNECTION_FAILED":
                                try {
                                    String message = "CONNECTION_FAILED "+ args.getString(0);
                                    Toast.makeText(SelectMorSensorActivity.this, message, Toast.LENGTH_LONG).show();
                                } catch (JSONException e) {
                                    logging("JSONException of CONNECTION_FAILED");
                                }
                                break;
                            case "CONNECTION_SUCCEEDED":
                                morsensor_connected = true;
                                logging("CONNECTION_SUCCEEDED");
                                try {
                                    JSONArray write_data = new JSONArray();
                                    write_data.put("MORSENSOR_VERSION");
                                    JSONObject write_args = new JSONObject();
                                    write_args.put("args", new JSONArray());
                                    write_data.put(write_args);
                                    morsensor_ida_api.write("Control", write_data);
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                                break;
                            case "DISCONNECTION_FAILED":
                                break;
                            case "DISCONNECTION_SUCCEEDED":
                                break;
                            case "MORSENSOR_VERSION":
                                try {
                                    String morsensor_version = args.getString(0);
                                    logging("MORSENSOR_VERSION: %s", morsensor_version);
                                    ((TextView) findViewById(R.id.tv_morsensor_version)).setText("MorSensor ver.:" + morsensor_version);

                                    JSONArray write_data = new JSONArray();
                                    write_data.put("FIRMWARE_VERSION");
                                    JSONObject write_args = new JSONObject();
                                    write_args.put("args", new JSONArray());
                                    write_data.put(write_args);
                                    morsensor_ida_api.write("Control", write_data);
                                } catch (JSONException e) {
                                    logging("JSONException of MORSENSOR_VERSION");
                                }
                                break;
                            case "FIRMWARE_VERSION":
                                try {
                                    String firmware_version = args.getString(0);
                                    logging("FIRMWARE_VERSION: %s", firmware_version);
                                    ((TextView) findViewById(R.id.tv_firmware_version)).setText("Firmware ver.:" + firmware_version);

                                    JSONArray write_data = new JSONArray();
                                    write_data.put("DF_LIST");
                                    JSONObject write_args = new JSONObject();
                                    write_args.put("args", new JSONArray());
                                    write_data.put(write_args);
                                    morsensor_ida_api.write("Control", write_data);
                                } catch (JSONException e) {
                                    logging("JSONException of FIRMWARE_VERSION");
                                }
                                break;
                            case "DF_LIST":
//                                try {
                                    logging("DF_LIST: %s", args.toString());
//                                } catch (JSONException e) {
//                                    logging("JSONException of GET_FEATURE_LIST");
//                                }
                                ((MorSensorApplication) getApplication()).df_list = args;
                                Intent intent = new Intent(SelectMorSensorActivity.this, SelectECActivity.class);
                                startActivity(intent);
                                finish();
                                return;
                        }
                    }
                });
            } else {
//                case DATA_AVAILABLE:
//                    byte[] data = (byte[]) message;
//                    dump_data_packet(data);
//                    switch (data[0]) {
//                        case MorSensorCommand.IN_MORSENSOR_VERSION:
//                            String morsensor_version_str = String.format("%d.%d.%d", data[0], data[1], data[2]);
//                            logging("MorSensor Version: %s", morsensor_version_str);
//                            ((TextView) findViewById(R.id.tv_morsensor_version)).setText("MorSensor ver.:" + morsensor_version_str);
//                            ((MorSensorIDAapi) morsensor_ida_api).put_info(Constants.INFO_MORSENSOR_VERSION, morsensor_version_str);
//                            break;
//                        case MorSensorCommand.IN_FIRMWARE_VERSION:
//                            String firmware_version_str = String.format("%d.%d.%d", data[0], data[1], data[2]);
//                            logging("Firmware Version: %s", firmware_version_str);
//                            ((TextView) findViewById(R.id.tv_firmware_version)).setText("Firmware ver.:" + firmware_version_str);
//                            ((MorSensorIDAapi) morsensor_ida_api).put_info(Constants.INFO_FIRMWARE_VERSION, firmware_version_str);
//                            break;
//                        case MorSensorCommand.IN_SENSOR_LIST:
//                            ArrayList<Byte> sensor_list = new ArrayList<>();
//                            for (int i = 0; i < data[1]; i++) {
//                                logging("Sensor %02X:", data[i + 2]);
//                                sensor_list.add(data[i + 2]);
//                                morsensor_ida_api.write(MorSensorCommand.SetSensorStopTransmission(data[i + 2]));
//                            }
//                            ((MorSensorIDAapi) morsensor_ida_api).put_info(Constants.INFO_SENSOR_LIST, sensor_list);
//                            Intent intent = new Intent(SelectMorSensorActivity.this, SelectECActivity.class);
//                            startActivity(intent);
//                            finish();
//                            return;
//                    }
//                    break;
            }
        } catch (JSONException e) {
            e.printStackTrace();
            logging("JSONException in receive()");
        }
    }

    public void dump_data_packet(byte[] data) {
        logging("Receive data packet:");
        for (int i = 0; i < 4; i++) {
            String _ = "";
            for (int j = 0; j < 5; j++) {
                _ += String.format("%02X ", data[i * 5 + j]);
            }
            logging(_);
        }
    }

    static private void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    static public void logging(String message) {
        Log.i(Constants.log_tag, "[SelectMorSensorActivity] " + message);
    }

}

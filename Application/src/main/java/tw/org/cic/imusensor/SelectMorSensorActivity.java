package tw.org.cic.imusensor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
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

import java.util.ArrayList;

public class SelectMorSensorActivity extends Activity {
    final ArrayList<MorSensorListItem> morsensor_list = new ArrayList<MorSensorListItem>();
    ArrayAdapter<MorSensorListItem> adapter;
    final IDAManager.Subscriber event_subscriber = new EventSubscriber();
    IDAManager morsensor_idamanager;
    boolean scan_button_neg;

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
                morsensor_idamanager.stop_searching();
                MorSensorListItem morsensor_item = morsensor_list.get(position);
                morsensor_idamanager.connect(morsensor_item.ida);
            }
        });

        scan_button_neg = true;

        MorSensorIDAManager.init(this, event_subscriber);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isFinishing()) {
            morsensor_idamanager.stop_searching();
            morsensor_idamanager.unsubscribe(event_subscriber);
            morsensor_idamanager.disconnect();
            ((MorSensorIDAManager) morsensor_idamanager).shutdown();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == MorSensorIDAManager.REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_select_morsensor, menu);
//        menu.findItem(R.id.item_scan).setVisible(true);

        if (scan_button_neg) {
            menu.findItem(R.id.item_scan).setTitle(R.string.menu_stop_scanning);
        } else {
            menu.findItem(R.id.item_scan).setTitle(R.string.menu_start_scanning);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.item_scan:
                if (scan_button_neg) {
                    morsensor_idamanager.stop_searching();
                } else {
                    morsensor_list.clear();
                    morsensor_idamanager.search();
                }
                break;
        }
        return true;
    }

    public class MorSensorListItem {
        MorSensorIDAManager.MorSensorIDA ida;
        public boolean connecting;

        public MorSensorListItem(MorSensorIDAManager.MorSensorIDA ida) {
            this.ida = ida;
            this.connecting = false;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MorSensorListItem)) {
                return false;
            }
            MorSensorListItem another = (MorSensorListItem) obj;
            return this.ida.equals(another.ida);
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

            MorSensorListItem i = data.get(position);
            holder.tv_name.setText(i.ida.name == null ? "<null>" : i.ida.name);
            holder.tv_addr.setText(i.ida.id);
            holder.tv_rssi.setText("" + i.ida.rssi);
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

    class EventSubscriber implements IDAManager.Subscriber {
        @Override
        public void on_event(final MorSensorIDAManager.EventTag event_tag, final Object message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    switch (event_tag) {
                        case INITIALIZATION_FAILED:
                            Toast.makeText(getApplicationContext(), (String) message, Toast.LENGTH_LONG).show();
                            finish();
                            break;
                        case INITIALIZED:
                            morsensor_idamanager = MorSensorIDAManager.instance();
                            morsensor_idamanager.search();
                            break;
                        case SEARCHING_STARTED:
                            scan_button_neg = true;
                            invalidateOptionsMenu();
                            findViewById(R.id.tv_searching_hint).setVisibility(View.VISIBLE);
                            break;
                        case FOUND_NEW_IDA:
                            MorSensorIDAManager.MorSensorIDA new_found_ida = (MorSensorIDAManager.MorSensorIDA) message;
                            MorSensorListItem morsensor_item = new MorSensorListItem(new_found_ida);
                            if (!morsensor_list.contains(morsensor_item)) {
                                morsensor_list.add(morsensor_item);
                            }

                            for (MorSensorListItem i : morsensor_list) {
                                if (i.equals(morsensor_item)) {
                                    i.ida.rssi = new_found_ida.rssi;
                                    i.ida.name = new_found_ida.name;
                                    break;
                                }
                            }
                            adapter.notifyDataSetChanged();
                            break;
                        case SEARCHING_STOPPED:
                            scan_button_neg = false;
                            invalidateOptionsMenu();
                            findViewById(R.id.tv_searching_hint).setVisibility(View.GONE);
                            break;
                        case CONNECTION_FAILED:
                            Toast.makeText(SelectMorSensorActivity.this, (String) message, Toast.LENGTH_LONG).show();
                            break;
                        case CONNECTED:
                            // retrieve sensor list, then start SelectECActivity
                            logging("write command: GetSensorList");
                            morsensor_idamanager.write(MorSensorCommand.GetMorSensorVersion());
                            morsensor_idamanager.write(MorSensorCommand.GetFirmwareVersion());
                            morsensor_idamanager.write(MorSensorCommand.GetSensorList());
                            break;
                        case DISCONNECTION_FAILED:
                            break;
                        case DISCONNECTED:
                            break;
                        case DATA_AVAILABLE:
                            byte[] data = (byte[]) message;
                            dump_data_packet(data);
                            switch (data[0]) {
                                case MorSensorCommand.IN_MORSENSOR_VERSION:
                                    logging("MorSensor Version: %d %d %d", data[0], data[1], data[2]);
                                    break;
                                case MorSensorCommand.IN_FIRMWARE_VERSION:
                                    logging("Firmware Version: %d %d %d", data[0], data[1], data[2]);
                            }
                            break;
                    }
                }
            });
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

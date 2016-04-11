package tw.org.cic.imusensor;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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

import DAN.DAN;

public class SelectMorSensorActivity extends Activity {
	final ArrayList<MorSensorListItem> morsensor_list = new ArrayList<MorSensorListItem>();
    ArrayAdapter<MorSensorListItem> adapter;
    final EventSubscriber event_subscriber = new EventSubscriber();
    final BluetoothAdapter.LeScanCallback ble_scan_callback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, final int rssi, byte[] scanRecord) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            MorSensorListItem morsensor_item = new MorSensorListItem(device.getName(), device.getAddress());
                            if (!morsensor_list.contains(morsensor_item)) {
                                morsensor_list.add(morsensor_item);
                            }

                            for (MorSensorListItem i: morsensor_list) {
                                if (i.equals(morsensor_item)) {
                                    i.rssi = rssi;
                                }
                            }
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            };
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        logging("================== SelectECActivity start ==================");
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_select_morsensor);
        getActionBar().setTitle(R.string.title_devices);

        DAN.init(Constants.log_tag);
        if (DAN.session_status()) {
        	logging("Already registered, jump to SessionActivity");
//            Intent intent = new Intent(SelectECActivity.this, FeatureActivity.class);
//            startActivity(intent);
            finish();
        }
        
        adapter = new MorSensorListAdapter(this, R.layout.item_morsensor_list, morsensor_list);

        // show available EC ENDPOINTS
        final ListView lv_available_morsensors = (ListView)findViewById(R.id.lv_available_morsensors);
        lv_available_morsensors.setAdapter(adapter);
        lv_available_morsensors.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent,
                                    View view, int position, long id) {
                MorSensorListItem morsensor_item = morsensor_list.get(position);
            }
        });

        // initialize the bluetooth interface
        if (!BLEManager.init(this)) {
            Toast.makeText(getApplicationContext(), "BLEManager.init() failed", Toast.LENGTH_LONG).show();
            finish();
        }
        BLEManager.subscribe(event_subscriber);
        BLEManager.search(ble_scan_callback);
    }

    @Override
    public void onPause () {
    	super.onPause();
        if (isFinishing()) {
            BLEManager.stop_searching();
            BLEManager.unsubscribe(event_subscriber);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == BLEManager.REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_select_morsensor, menu);
//        menu.findItem(R.id.item_scan).setVisible(true);
        if (BLEManager.is_searching()) {
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
                if (BLEManager.is_searching()) {
                    BLEManager.stop_searching();
                } else {
                    morsensor_list.clear();
                    BLEManager.search(ble_scan_callback);
                }
                break;
        }
        return true;
    }

    public class MorSensorListItem {
    	public String name;
    	public String addr;
        public int rssi;
    	public boolean connecting;
    	public MorSensorListItem(String n, String a) {
    		this.name = n;
    		this.addr = a;
    		this.connecting = false;
    	}

        @Override
        public boolean equals (Object obj) {
            if (obj instanceof MorSensorListItem) {
                MorSensorListItem another = (MorSensorListItem) obj;
                return name.equals(another.name) && addr.equals(another.addr);
            }
            return false;
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
	    public View getView (int position, View convertView, ViewGroup parent) {
	        View row = convertView;
	        MorSensorHolder holder = null;
	        
	        if(row == null) {
	            LayoutInflater inflater = ((Activity)context).getLayoutInflater();
	            row = inflater.inflate(layout_resource_id, parent, false);
	            holder = new MorSensorHolder();
	            holder.tv_name = (TextView)row.findViewById(R.id.tv_name);
	            holder.tv_addr = (TextView)row.findViewById(R.id.tv_addr);
	            holder.tv_rssi = (TextView)row.findViewById(R.id.tv_rssi);
//	            holder.connecting = (TextView)row.findViewById(R.id.tv_connecting);
	            row.setTag(holder);
	        } else {
	            holder = (MorSensorHolder)row.getTag();
	        }
	        
	        MorSensorListItem i = data.get(position);
	        holder.tv_name.setText(i.name);
	        holder.tv_addr.setText(i.addr);
	        holder.tv_rssi.setText(""+ i.rssi);
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

    class EventSubscriber extends BLEManager.Subscriber {
        @Override
        public void on_event(BLEManager.EventTag event_tag, String message) {
            switch (event_tag) {
                case START_SEARCHING:
                    invalidateOptionsMenu();
                    ((TextView)findViewById(R.id.tv_searching_hint)).setVisibility(View.VISIBLE);
                    break;
                case STOP_SEARCHING:
                    invalidateOptionsMenu();
                    ((TextView)findViewById(R.id.tv_searching_hint)).setVisibility(View.GONE);
                    break;
            }
        }
    }
    
    static public void logging (String message) {
        Log.i(Constants.log_tag, "[SelectMorSensorActivity] " + message);
    }

}

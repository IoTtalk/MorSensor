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
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
                    ((TextView)findViewById(R.id.tv_morsensor_df_list)).setText(R.string.connecting);
                    break;
            }
        }
    }

    UIhandler ui_handler = new UIhandler();
    BLEIDA ble_ida;
    DAN dan;

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
            MainActivity.this.unbindService(MainActivity.this);
        }
    }

    static private void logging (String format, Object... args) {
        Log.i("MorSensor", String.format("[MainActivity] "+ format, args));
    }
}

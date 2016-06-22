package tw.org.cic.imusensor;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DAI extends Thread implements DAN.DAN2DAI {
    BLEIDA ble_ida;
    DAN dan;
    MainActivity.UIhandler ui_handler;
    public DAI (BLEIDA ble_ida, DAN dan, MainActivity.UIhandler ui_handler) {
        this.ble_ida = ble_ida;
        this.dan = dan;
        this.ui_handler = ui_handler;
    }

    @Override
    public void run () {
        logging("DAI.run()");
        String mac_addr = ble_ida.init(ui_handler, "00002a37-0000-1000-8000-00805f9b34fb", "00001525-1212-efde-1523-785feabcd123");
        if (mac_addr.equals("")) {
            return;
        }

        try {
            JSONObject profile = new JSONObject() {{
                put("df_list", new JSONArray(){{
                    put("idf1");
                    put("idf2");
                    put("odf1");
                }});
                put("dm_name", "MorSensor");
                put("is_sim", "False");
                put("u_name", "cychih");
            }};
            dan.init("http://140.113.215.10:9999", mac_addr, profile, this);
        } catch (JSONException e) {
            logging("DAI.run(): register: JSONException");
        }

        dan.push("idf1", new JSONArray(){{
            put(1);
            put(2);
            put(3);
        }});
    }

    @Override
    public void pull(String odf_name, JSONArray data) {
        logging("%s: %s", odf_name, data);
    }

    static private void logging (String format, Object... args) {
        Log.i("MorSensor", String.format("[DAI] "+ format, args));
    }
}

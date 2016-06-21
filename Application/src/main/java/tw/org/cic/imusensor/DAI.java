package tw.org.cic.imusensor;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DAI extends Thread implements DAN.DAN2DAI {
    String log_tag = "MorSensor";
    Handler ui_handler;
    DAN dan;
    public DAI (Handler ui_handler, DAN dan) {
        this.ui_handler = ui_handler;
        this.dan = dan;
    }

    @Override
    public void run () {
        try {
            JSONObject profile = new JSONObject() {{
                put("d_name", "test-da");
                put("df_list", new JSONArray(){{
                    put("idf1");
                    put("idf2");
                    put("odf1");
                }});
                put("dm_name", "test-da");
                put("is_sim", "False");
                put("u_name", "cychih");
            }};
            dan.init("http://140.113.215.10:9999", "test", profile, this);
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

    void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    void logging (String message) {
        System.out.printf("[%s][DAI] %s%n", log_tag, message);
    }
}

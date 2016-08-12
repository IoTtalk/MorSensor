package tw.org.cic.imusensor;

import android.app.Application;

import org.json.JSONArray;

public class MorSensorApplication extends Application {
    String d_id;
    String morsensor_version;
    String firmware_version;
    JSONArray df_list;
}

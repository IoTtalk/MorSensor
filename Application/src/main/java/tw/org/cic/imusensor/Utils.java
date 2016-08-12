package tw.org.cic.imusensor;

import java.util.Random;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

public class Utils {
	static final int NOTIFICATION_ID = 1;
    
    static public String get_mac_addr (Context context) {
        logging("get_mac_addr()");

        // Generate error mac address
        final Random rn = new Random();
        String ret = "E2202";
        for (int i = 0; i < 7; i++) {
        	ret += "0123456789ABCDEF".charAt(rn.nextInt(16));
        }

        WifiManager wifiMan = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiMan == null) {
            logging("Cannot get WiFiManager system service");
            return ret;
        }

        WifiInfo wifiInf = wifiMan.getConnectionInfo();
        if (wifiInf == null) {
            logging("Cannot get connection info");
            return ret;
        }

        return wifiInf.getMacAddress();
    }

    static public String get_wifi_ssid (Context context) {
        final WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        final WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        return wifiInfo.getSSID();
    }
    
    static public void logging (String message) {
        Log.i(Constants.log_tag, "[Utils] " + message);
    }
}

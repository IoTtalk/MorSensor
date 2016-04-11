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
    
    static public void show_ec_status_on_notification (Context context, String host, boolean status) {
        String text = status ? host : "Connecting";
        NotificationManager notification_manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder notification_builder = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(Constants.dm_name)
                .setContentText(text);

        PendingIntent pending_intent = PendingIntent.getActivity(
        		context, 0,
                new Intent(context, SelectMorSensorActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        notification_builder.setContentIntent(pending_intent);
        notification_manager.notify(NOTIFICATION_ID, notification_builder.build());
    }
    
    static public void remove_all_notification (Context context) {
    	NotificationManager notification_manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    	notification_manager.cancelAll();
    }
    
    static public void logging (String message) {
        Log.i(Constants.log_tag, "[Utils] " + message);
    }
}

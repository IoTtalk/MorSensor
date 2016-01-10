package tw.org.cic.imusensor;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class DAN extends Service {
    static public final String version = "20160107b";

    static public class ODFObject {
        enum Type {CONTROL_CHANNEL, ODF}
        public Type odf_type;

        // EVENT object
        public EventTag event_tag;
        public String message;

        // ODF object
        public String feature;
        public DataSet dataset;

        public ODFObject (EventTag event_name, String message) {
            this.odf_type = Type.CONTROL_CHANNEL;
            this.event_tag = event_name;
            this.message = message;
            this.feature = null;
            this.dataset = null;
        }

        public ODFObject (String feature, DataSet dataset) {
            this.odf_type = Type.ODF;
            this.event_tag = null;
            this.message = null;
            this.feature = feature;
            this.dataset = dataset;
        }
    }

    static public abstract class Subscriber {
        static class _Handler extends Handler {}
        private final Handler handler = new _Handler () {
            public void handleMessage (Message msg) {
                Subscriber.this.odf_handler((ODFObject)msg.obj);
            }
        };

        public void send_event (EventTag event, String message) {
            Message msgObj = handler.obtainMessage();
            ODFObject odf_object = new ODFObject(event, message);
            msgObj.obj = odf_object;
            handler.sendMessage(msgObj);
        }

        public void send_odf (String feature, DataSet dataset) {
            Message msgObj = handler.obtainMessage();
            ODFObject odf_object = new ODFObject(feature, dataset);
            msgObj.obj = odf_object;
            handler.sendMessage(msgObj);
        }

        public abstract void odf_handler (ODFObject odf_object);
    }

    static private DAN self = null;
    static private boolean ec_service_started;
    static private Context creater = null;
    static private Class<? extends Context> on_click_action;
    static private String log_tag = "EasyConenct";
    static private String mac_addr_cache = null;
    static private String mac_addr_error_prefix = "E2202";

    static HashSet<Subscriber> event_subscribers = null;
    static public enum EventTag {
        REGISTER_FAILED,
        REGISTER_SUCCEED,
    };

    static private final int NOTIFICATION_ID = 1;
    static private Semaphore attach_lock;
    static final   String    DEFAULT_EC_HOST   = "http://openmtc.darkgerm.com:9999";
    static public  int       EC_BROADCAST_PORT = 17000;
    static private String d_id;
    static private JSONObject profile;
    static private Semaphore ec_status_lock;
    static private boolean   ec_status = false;

    static private long request_interval = 150;
    static HashMap<String, UpStreamThread> upstream_thread_pool;
    static HashMap<String, DownStreamThread> downstream_thread_pool;

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logging("onStartCommand()");
        self = this;
        show_ec_status_on_notification(ec_status);
        return START_STICKY;
    }

    // *********** //
    // * Threads * //
    // *********** //

    static private class DetectLocalECThread extends Thread {
        static DetectLocalECThread self = null;
        private DetectLocalECThread () {}

        static DatagramSocket socket;
        static boolean working_permission;
        static String candidate_ec_host = null;
        static int receive_count = 0;

        static public void start_working () {
            logging("DetectLocalECThread.start_working()");
            if (self != null) {
                logging("already working");
                return;
            }
            working_permission = true;
            self = new DetectLocalECThread();
            self.start();
        }

        static public void stop_working () {
            logging("DetectLocalECThread.stop_working()");
            if (self == null) {
                logging("Already stopped");
                return;
            }
            socket.close();
        }

        public void run () {
            logging("DetectLocalECThread starts");
            show_ec_status_on_notification(ec_status);
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", DAN.EC_BROADCAST_PORT));
                byte[] lmessage = new byte[20];
                DatagramPacket packet = new DatagramPacket(lmessage, lmessage.length);
                while (working_permission) {
                    socket.receive(packet);
                    String input_data = new String( lmessage, 0, packet.getLength() );
                    if (input_data.equals("easyconnect")) {
                        // It's easyconnect packet
                        InetAddress ec_raw_addr = packet.getAddress();
                        String ec_addr = ec_raw_addr.getHostAddress();
                        process_easyconnect_packet(ec_addr);
                    }
                }
            } catch (SocketException e) {
                logging("SocketException");
                e.printStackTrace();
            } catch (IOException e) {
                logging("IOException");
                e.printStackTrace();
            } catch (InterruptedException e) {
                logging("InterruptedException");
                e.printStackTrace();
            } finally {
                logging("DetectLocalECThread stops");
                working_permission = false;
                self = null;
            }
        }

        private void process_easyconnect_packet (String _) throws InterruptedException {
            String new_ec_host = "http://" + _ +":9999";

            if (csmapi.ENDPOINT.equals(DEFAULT_EC_HOST)) {
                // we are now on default EC, and we should use local EC first
                candidate_ec_host = new_ec_host;
                receive_count = 10;
            } else if (receive_count == 0 || !candidate_ec_host.equals(new_ec_host)) {
                // we don't have any candidate host, or found another host
                candidate_ec_host = new_ec_host;
                receive_count = 1;
            } else {
                // contiguously receiving EC packet from the same host
                receive_count += 1;
            }

            // prevent overflow
            if (receive_count >= 10) {
                receive_count = 10;
            }

            if (!csmapi.ENDPOINT.equals(candidate_ec_host) && receive_count >= 5) {
                // we are using different EC host, and it's stable
                attach_lock.acquire();
                if (!ec_status) {
                    csmapi.ENDPOINT = new_ec_host;
                } else {
                    csmapi.delete(d_id);
                    ec_status = false;
                    csmapi.ENDPOINT = new_ec_host;
                    RegisterThread.start_working();
                }
                attach_lock.release();
            }
        }
    }

    static private class RegisterThread extends Thread {
        static private RegisterThread self;
        private RegisterThread () {}
        static boolean working_permission;

        static public void start_working () {
            logging("RegisterThread.start_working()");
            if (ec_status) {
                logging("already registered");
                show_ec_status_on_notification(ec_status);
                return;
            }

            if (self != null) {
                logging("already working");
                show_ec_status_on_notification(ec_status);
                return;
            }
            working_permission = true;
            self = new RegisterThread();
            self.start();
        }

        static public void stop_working () {
            logging("RegisterThread.stop_working()");
            if (self == null) {
                logging("Already stopped");
                return;
            }
            working_permission = false;
            self.interrupt();
        }

        @Override
        public void run () {
            logging("RegisterThread starts");
            try {
                if (csmapi.ENDPOINT.equals(DEFAULT_EC_HOST)) {
                    // Wait for a while before attaching to global EC
                    Thread.sleep(2000);
                }

                boolean attach_success = false;

                while ( working_permission && !attach_success ) {
                    if (ec_status) {
                        break;
                    }
                    attach_lock.acquire();
                    attach_success = csmapi.create(d_id, profile);
                    show_ec_status_on_notification(attach_success);
                    logging("Attach result: " + csmapi.ENDPOINT +": "+ attach_success);
                    attach_lock.release();
                    if (attach_success) {
                        break;
                    }
                    notify_all_subscribers(EventTag.REGISTER_FAILED, csmapi.ENDPOINT);
                    logging("Attach failed, wait for 2000ms and try again");
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                logging("RegisterThread stops");
                working_permission = false;
                self = null;
            }
        }
    }

    static private class DeregisterThread extends Thread {
        static DeregisterThread self;
        private DeregisterThread () {}

        static public void start_working () {
            if (self != null) {
                logging("already working");
                show_ec_status_on_notification(ec_status);
                return;
            }
            self = new DeregisterThread();
            self.start();
            try {
                self.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run () {
            DetectLocalECThread.stop_working();
            RegisterThread.stop_working();

            ec_status = false;
            boolean deregister_result = csmapi.delete(d_id);
            logging("Deregistered from EasyConnect, result: "+ deregister_result);

            NotificationManager notification_manager = (NotificationManager) get_reliable_context().getSystemService(Context.NOTIFICATION_SERVICE);
            notification_manager.cancelAll();

            if (DAN.self != null) {
                DAN.self.stopSelf();
            }

            self = null;
            reset();
        }
    }

    static private void reset () {
        csmapi.ENDPOINT = DEFAULT_EC_HOST;
        DAN.self = null;
        DAN.request_interval = 150;
        DAN.ec_service_started = false;
    }

    static private class UpStreamThread extends Thread {
        boolean working_permission;
        String feature;
        LinkedBlockingQueue<DANDataObject> queue;
        long timestamp;

        public UpStreamThread (String feature) {
            this.feature = feature;
            this.queue = new LinkedBlockingQueue<DANDataObject>();
            this.timestamp = 0;
        }

        public void stop_working () {
            working_permission = false;
            this.interrupt();
        }

        public void enqueue (DANDataObject data) {
            try {
                queue.put(data);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void run () {
            logging("UpStreamThread("+ feature +") starts");
            try {
                if (!json_array_has_string(DAN.profile.getJSONArray("df_list"), feature)) {
                    logging("UpStreamThread("+ feature +"): feature not exists, exit");
                    return;
                }
            } catch (JSONException e1) {
                logging("UpStreamThread("+ feature +") checking failed");
                return;
            }
            working_permission = true;
            while (working_permission) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - timestamp < request_interval) {
                        Thread.sleep(request_interval - (now - timestamp));
                    }
                    timestamp = System.currentTimeMillis();

                    DANDataObject acc = queue.take();
                    int buffer_count = 1;
                    int queue_len = queue.size();
                    for (int i = 0; i < queue_len; i++) {
//    				while (!queue.isEmpty()) {	// This may cause starvation
                        DANDataObject tmp = queue.take();
                        if (!working_permission) {
                            logging("UpStreamThread("+ feature +") droped");
                            return;
                        }
                        acc.accumulate(tmp);
                        buffer_count += 1;
                    }
                    acc.average(buffer_count);

                    JSONObject data = acc.toJSONObject();
                    if (ec_status) {
                        logging("UpStreamThread("+ feature +") push data: "+ data);
                        csmapi.push(d_id, feature, data);
                    } else {
                        logging("UpStreamThread("+ feature +") skip. (ec_status == false)");
                    }
                } catch (InterruptedException e) {
                    logging("UpStreamThread("+ feature +") interrupted");
                    e.printStackTrace();
                }
            }
            logging("UpStreamThread("+ feature +") stops");
        }
    }

    static private class DownStreamThread extends Thread {
        boolean working_permission;
        String feature;
        Subscriber subscriber;
        long timestamp;
        String data_timestamp;

        public DownStreamThread (String feature, Subscriber callback) {
            this.feature = feature;
            this.subscriber = callback;
            this.timestamp = 0;
        }

        public void stop_working () {
            working_permission = false;
            this.interrupt();
        }

        public void run () {
            logging("DownStreamThread("+ feature +") starts");
            try {
                if (!json_array_has_string(DAN.profile.getJSONArray("df_list"), feature)) {
                    logging("DownStreamThread("+ feature +"): feature not exists, exit");
                    return;
                }
            } catch (JSONException e1) {
                logging("DownStreamThread("+ feature +") checking failed");
                return;
            }
            working_permission = true;
            data_timestamp = "";
            while (working_permission) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - timestamp < request_interval) {
                        Thread.sleep(request_interval - (now - timestamp));
                    }
                    timestamp = System.currentTimeMillis();
                    if (ec_status) {
                        logging("DownStreamThread("+ feature +") pull data");
                        deliver_data(csmapi.pull(d_id, feature));
                    } else {
                        logging("DownStreamThread("+ feature +") skip. (ec_status == false)");
                    }
                } catch (JSONException e) {
                    logging("DownStreamThread("+ feature +") JSONException");
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    logging("DownStreamThread("+ feature +") interrupted");
                    e.printStackTrace();
                }
            }
            logging("DownStreamThread("+ feature +") stops");
        }

        private void deliver_data (JSONArray data) throws JSONException {
            DataSet ds = new DataSet(data);
            if (ds.timestamp.equals(data_timestamp)) {
                // no new data
                return;
            }

            if (ds.size() == 0) {
                // server responded, but the container is empty
                return;
            }
            if (ds.newest().data.equals(null)) {
                // server responded, but newest data is null
                return;
            }
            data_timestamp = ds.timestamp;
            subscriber.send_odf(feature, new DataSet(data));
        }
    }

    static public class DataSet {
        public String timestamp;
        private JSONArray dataset;

        public DataSet (JSONArray in) {
            this.timestamp = "";
            this.dataset = null;

            try {
                this.dataset = in;
                this.timestamp = this.dataset.getJSONArray(0).getString(0);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        public Data newest () throws JSONException {
            return nth(0);
        }

        public Data nth (int n) throws JSONException {
            Data ret = new Data();
            JSONArray tmp = this.dataset.getJSONArray(n);
            ret.timestamp = tmp.getString(0);
            ret.data = tmp.getJSONArray(1);
            return ret;
        }

        public int size () {
            return this.dataset.length();
        }

        @Override
        public String toString () {
            JSONObject ret = new JSONObject();
            try {
                ret.put("timestamp", this.timestamp);
                ret.put("data", this.dataset);
                return ret.toString();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return "";
        }
    }

    static public class Data {
        public String timestamp;
        public JSONArray data;

        public Data () {
            this.timestamp = "";
            this.data = new JSONArray();
        }
    }

    // *************************** //
    // * Internal Used Functions * //
    // *************************** //

    static private boolean json_array_has_string (JSONArray json_array, String str) {
        for (int i = 0; i < json_array.length(); i++) {
            try {
                if (json_array.getString(i).equals(str)) {
                    return true;
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    static private void show_ec_status_on_notification (boolean new_ec_status) {
        try {
            ec_status_lock.acquire();
            ec_status = new_ec_status;
            if (ec_status) {
                notify_all_subscribers(EventTag.REGISTER_SUCCEED, csmapi.ENDPOINT);
            }
            logging("show notification: "+ ec_status);
            Context ctx = get_reliable_context();
            if (ctx == null) {
                return;
            }
            String text = ec_status ? csmapi.ENDPOINT : "Connecting";
            ec_status_lock.release();
            NotificationManager notification_manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder notification_builder =
                    new Notification.Builder(ctx)
                            .setSmallIcon(R.drawable.ic_launcher)
                            .setContentTitle(log_tag)
                            .setContentText(text);

            PendingIntent pending_intent = PendingIntent.getActivity(
                    ctx,
                    0,
                    new Intent(ctx, on_click_action),
                    PendingIntent.FLAG_UPDATE_CURRENT
            );

            notification_builder.setContentIntent(pending_intent);
            notification_manager.notify(NOTIFICATION_ID, notification_builder.build());
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static private void logging (String message) {
        Log.i(log_tag, "[DAN] " + message);
    }

    static private void notify_all_subscribers (EventTag event, String message) {
        if (event_subscribers == null) {
            logging("Broadcast: No subscribers");
            return;
        }
        for (Subscriber handler: event_subscribers) {
            handler.send_event(event, message);
        }
    }

    static private Context get_reliable_context () {
        if (self == null) {
            logging("DAN Service is null, use creater instead");
            return creater;
        }
        return self;
    }

    static private class DANDataObject {
        private Object value;

        static private boolean is_double_array (Object obj) {
            if (obj instanceof JSONArray) {
                try {
                    JSONArray tmp = (JSONArray)obj;
                    for (int i = 0; i < tmp.length(); i++) {
                        if (!(tmp.get(i) instanceof Double)) {
                            return false;
                        }
                    }
                    // YA, it's double array
                    return true;
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            if (obj instanceof double[]) {
                return true;
            } else if (obj instanceof float[]) {
                return true;
            }
            return false;
        }

        static private double[] to_double_array (Object obj) throws JSONException {
            if (obj instanceof JSONArray) {
                JSONArray tmp = (JSONArray)obj;
                int length = tmp.length();
                double[] ret = new double[length];
                for (int i = 0; i < length; i++) {
                    ret[i] = tmp.getDouble(i);
                }
                return ret;
            } else if (obj instanceof float[]) {
                float[] tmp = (float[])obj;
                int length = tmp.length;
                double[] ret = new double[length];
                for (int i = 0; i < length; i++) {
                    ret[i] = (double)tmp[i];
                }
                return ret;
            }
            return null;
        }

        public DANDataObject (Object obj) {
            if (is_double_array(obj)) {
                try {
                    value = to_double_array(obj);
                } catch (JSONException e) {
                    value = obj;
                    e.printStackTrace();
                }
            } else {
                value = obj;
            }
        }

        public JSONObject toJSONObject () {
            JSONArray data = new JSONArray();
            JSONObject ret = new JSONObject();
            try {
                if (value instanceof Integer) {
                    data.put((Integer)value);
                } else if (value instanceof Float) {
                    data.put((Float)value);
                } else if (value instanceof Double) {
                    data.put((Double)value);
                } else if (value instanceof int[]) {
                    for (int i: (int[])value) {
                        data.put(i);
                    }
                } else if (value instanceof float[]) {
                    for (float i: (float[])value) {
                        data.put(i);
                    }
                } else if (value instanceof double[]) {
                    for (double i: (double[])value) {
                        data.put(i);
                    }
                } else if (value instanceof byte[]) {
                    for (byte i: (byte[])value) {
                        data.put(i);
                    }
                } else if (value instanceof String) {
                    return ret.put("data", (String)value);
                } else if (value instanceof JSONArray) {
                    data = (JSONArray)value;
                } else if (value instanceof JSONObject) {
                    data.put((JSONObject)value);
                }
                ret.put("data", data);
            } catch (JSONException e) {
                logging("DANDataObject.toString() JSONException");
                e.printStackTrace();
            }
            return ret;
        }

        public void accumulate (DANDataObject obj) {
            if (!value.getClass().equals(obj.value.getClass())) {
                return;
            }

            if (value instanceof Integer) {
                value = (Integer)value + (Integer)obj.value;
            } else if (value instanceof Float) {
                value = (Float)value + (Float)obj.value;
            } else if (value instanceof Double) {
                value = (Double)value + (Double)obj.value;
            } else if (value instanceof int[]) {
                for (int i = 0; i < ((int[])value).length; i++) {
                    ((int[])value)[i] += ((int[])obj.value)[i];
                }
            } else if (value instanceof float[]) {
                for (int i = 0; i < ((float[])value).length; i++) {
                    ((float[])value)[i] += ((float[])obj.value)[i];
                }
            } else if (value instanceof double[]) {
                for (int i = 0; i < ((double[])value).length; i++) {
                    ((double[])value)[i] += ((double[])obj.value)[i];
                }
            }
        }

        public void average (int count) {
            if (value instanceof Integer) {
                value = (int)((Integer)value / count);
            } else if (value instanceof Float) {
                value = (Float)value / count;
            } else if (value instanceof Double) {
                value = (Double)value / count;
            } else if (value instanceof int[]) {
                for (int i = 0; i < ((int[])value).length; i++) {
                    ((int[])value)[i] = (int)(((int[])value)[i] / count);
                }
            } else if (value instanceof float[]) {
                for (int i = 0; i < ((float[])value).length; i++) {
                    ((float[])value)[i] = ((float[])value)[i] / count;
                }
            } else if (value instanceof double[]) {
                for (int i = 0; i < ((double[])value).length; i++) {
                    ((double[])value)[i] = ((double[])value)[i] / count;
                }
            } else if (value instanceof JSONArray) {
                return; // good JSONArray is translated into double[]
            } else if (value instanceof byte[]) {
                return;	// don't accumulate byte[]
            } else if (value instanceof String) {
                return;	// don't know now to accumulate String
            } else if (value instanceof JSONObject) {
                return;	// don't know now to accumulate JSONObject
            }
        }
    }

    // ************** //
    // * Public API * //
    // ************** //
    static public void init (Context ctx, String device_model) {
        if (ec_service_started) {
            logging("DAN.init(): already started");
            return;
        }
        logging("DAN.init()");
        ec_service_started = true;
        creater = ctx;
        DAN.log_tag = device_model;
        csmapi.log_tag = device_model;
        upstream_thread_pool = new HashMap<String, UpStreamThread>();
        downstream_thread_pool = new HashMap<String, DownStreamThread>();
        attach_lock = new Semaphore(1);
        ec_status_lock = new Semaphore(1);
        DetectLocalECThread.start_working();
        if (on_click_action == null) {
            on_click_action = ctx.getClass();
        }

        // Generate error mac address
        Random rn = new Random();
        for (int i = 0; i < 7; i++) {
            int a = rn.nextInt(16);
            mac_addr_error_prefix += "0123456789ABCDEF".charAt(a);
        }

        // start this service
        Intent intent = new Intent(ctx, DAN.class);
        ctx.getApplicationContext().startService(intent);
    }

    static public void set_on_click_action (Class<? extends Context> c) {
        on_click_action = c;
    }

    static public String get_mac_addr () {
        logging("get_mac_addr()");
        if (mac_addr_cache != null) {
            logging("We have mac address cache: "+ mac_addr_cache);
            return mac_addr_cache;
        }

        mac_addr_cache = mac_addr_error_prefix;
        Context ctx = get_reliable_context();

        if (ctx == null) {
            logging("Oops, we have no reliable context");
            return mac_addr_cache;
        }

        WifiManager wifiMan = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wifiMan == null) {
            logging("Cannot get WiFiManager system service");
            return mac_addr_cache;
        }

        WifiInfo wifiInf = wifiMan.getConnectionInfo();
        if (wifiInf == null) {
            logging("Cannot get connection info");
            return mac_addr_cache;
        }

        mac_addr_cache = wifiInf.getMacAddress().replace(":", "");
        return mac_addr_cache;
    }

    static public String get_clean_mac_addr (String mac_addr) {
        return mac_addr.replace(":", "");
    }

    static public String get_d_id (String mac_addr) {
        return get_clean_mac_addr(mac_addr);
    }

    static public String get_d_name () {
        try {
            if (profile == null) {
                return "Error";
            }
            return profile.getString("d_name");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return "Error";
    }

    static public void register (String d_id, JSONObject profile) {
        DAN.d_id = d_id;
        DAN.profile = profile;
        if (!DAN.profile.has("is_sim")) {
            try {
                DAN.profile.put("is_sim", false);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        RegisterThread.start_working();
    }

    static public void push (String feature, Object data) {
        DANDataObject ary = new DANDataObject(data);
        if (!upstream_thread_pool.containsKey(feature)) {
            UpStreamThread ust = new UpStreamThread(feature);
            upstream_thread_pool.put(feature, ust);
            ust.start();
        }
        UpStreamThread ust = upstream_thread_pool.get(feature);
        ust.enqueue(ary);
    }

    static public void subscribe (String feature, Subscriber subscriber) {
        if (feature.equals("Control_channel")) {
            if (event_subscribers == null) {
                event_subscribers = new HashSet<Subscriber>();
            }
            event_subscribers.add(subscriber);
        } else {
            if (!downstream_thread_pool.containsKey(feature)) {
                DownStreamThread dst = new DownStreamThread(feature, subscriber);
                downstream_thread_pool.put(feature, dst);
                dst.start();
            }
        }
    }

    static public void unsubscribe (String feature) {
        DownStreamThread dst = downstream_thread_pool.get(feature);
        if (dst != null) {
            dst.stop_working();
            downstream_thread_pool.remove(feature);
        }
    }

    static public void unsubcribe (Subscriber handler) {
        event_subscribers.remove(handler);
    }

    static public void deregister () {
        if (upstream_thread_pool != null) {
            for (String feature: upstream_thread_pool.keySet()) {
                upstream_thread_pool.get(feature).stop_working();
            }
        }
        if (downstream_thread_pool != null) {
            for (String feature: downstream_thread_pool.keySet()) {
                downstream_thread_pool.get(feature).stop_working();
            }
        }
        DeregisterThread.start_working();
    }

    static public long get_request_interval () {
        return DAN.request_interval;
    }

    static public void set_request_interval (long request_interval) {
        if (request_interval > 0) {
            logging("Set DAN.request_interval = "+ request_interval);
            DAN.request_interval = request_interval;
        }
    }
}

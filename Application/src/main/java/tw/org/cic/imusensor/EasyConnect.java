package tw.org.cic.imusensor;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

public class EasyConnect extends Service {
	static public final String version = "20151208c-M";
	static private EasyConnect self = null;
	static private boolean ec_service_started;
	static private Context creater = null;
	static private Class<? extends Context> on_click_action;
	static private String device_model = "EasyConenct";
	static private String mac_addr_cache = null;
	static private String mac_addr_error_prefix = "E2202";
	
	static HashSet<Handler> subscribers = null;
	static public enum Tag {
		D_NAME_GENEREATED,
		ATTACH_TRYING,
		ATTACH_FAILED,
		ATTACH_SUCCESS,
		DETACH_SUCCESS,
	};
	
	static private final int NOTIFICATION_ID = 1;
    static public  int       EC_PORT           = 9999;
    static private Semaphore attach_lock;
    static final   String    DEFAULT_EC_HOST   = "openmtc.darkgerm.com:"+ EC_PORT;
    static         String    EC_HOST           = DEFAULT_EC_HOST;
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
    	return START_NOT_STICKY;
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
				socket.bind(new InetSocketAddress("0.0.0.0", EasyConnect.EC_BROADCAST_PORT));
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
        	String new_ec_host = _ +":"+ EasyConnect.EC_PORT;
    		
    		if (EC_HOST.equals(DEFAULT_EC_HOST)) {
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

            if (!EC_HOST.equals(candidate_ec_host) && receive_count >= 5) {
                // we are using different EC host, and it's stable
                attach_lock.acquire();
                if (!ec_status) {
                    EC_HOST = new_ec_host;
                } else {
                    detach_api();
                    ec_status = false;
                }
                EC_HOST = new_ec_host;
                attach_lock.release();
                RegisterThread.start_working();
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
			self.interrupt();
    	}
    	
    	@Override
        public void run () {
    		logging("RegisterThread starts");
    		boolean attach_success = false;
    		notify_all_subscribers(Tag.ATTACH_TRYING, EC_HOST);
    		
        	try {
				notify_all_subscribers(Tag.D_NAME_GENEREATED, profile.getString("d_name"));
			} catch (JSONException e1) {
				e1.printStackTrace();
			} catch (NullPointerException e2) {
                logging("[RegisterThread] profile is null, try to work on");
                e2.printStackTrace();
            }
    		try {
	            while ( working_permission && !attach_success ) {
	            	if (ec_status) {
	            		break;
	            	}
	            	attach_lock.acquire();
	            	attach_success = EasyConnect.attach_api(profile);
            		show_ec_status_on_notification(attach_success);
	            	attach_lock.release();
	            	if (attach_success) {
	            		logging("Attach Successed:" + EC_HOST);
		            	break;
	            	}
	    			notify_all_subscribers(Tag.ATTACH_FAILED, EC_HOST);
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
    
    static private class DetachThread extends Thread {
    	static DetachThread self;
    	private DetachThread () {}
    	
    	static public void start_working () {
    		if (self != null) {
    			logging("already working");
    	    	show_ec_status_on_notification(ec_status);
    			return;
    		}
    		self = new DetachThread();
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
            boolean detach_result = detach_api();
    		logging("Detached from EasyConnect, result: "+ detach_result);
            if (detach_result) {
            	notify_all_subscribers(Tag.DETACH_SUCCESS, EC_HOST);
            }
            
            NotificationManager notification_manager = (NotificationManager) get_reliable_context().getSystemService(Context.NOTIFICATION_SERVICE);
            notification_manager.cancelAll();

            if (EasyConnect.self != null) {
                EasyConnect.self.stopSelf();
            }
            self = null;
            reset();
    	}
    }
    
    static private void reset () {
        EasyConnect.EC_PORT = 9999;
        EasyConnect.EC_HOST = DEFAULT_EC_HOST;
        EasyConnect.self = null;
        EasyConnect.request_interval = 150;
        EasyConnect.ec_service_started = false;
    }
    
    static private class UpStreamThread extends Thread {
    	boolean working_permission;
    	String feature;
    	LinkedBlockingQueue<EasyConnectDataObject> queue;
    	long timestamp;
        String url;
    	
    	public UpStreamThread (String feature) {
    		this.feature = feature;
    		this.queue = new LinkedBlockingQueue<EasyConnectDataObject>();
    		this.timestamp = 0;
    		this.url = d_id +"/"+ feature;
    	}
    	
    	public void stop_working () {
			working_permission = false;
			this.interrupt();
    	}
    	
    	public void enqueue (EasyConnectDataObject data) {
    		try {
				queue.put(data);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
    	}
    	
    	public void run () {
    		logging("UpStreamThread("+ feature +") starts");
    		try {
				if (!json_array_has_string(EasyConnect.profile.getJSONArray("df_list"), feature)) {
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
    				
					EasyConnectDataObject acc = queue.take();
    				int buffer_count = 1;
    				int queue_len = queue.size();
    				for (int i = 0; i < queue_len; i++) {
//    				while (!queue.isEmpty()) {	// This may cause starvation
    					EasyConnectDataObject tmp = queue.take();
    					if (!working_permission) {
        		    		logging("UpStreamThread("+ feature +") droped");
    						return;
    					}
    					acc.accumulate(tmp);
    					buffer_count += 1;
    				}
    				acc.average(buffer_count);
    				
    				String tmp = acc.toString();
    				if (ec_status) {
						logging("UpStreamThread("+ feature +") push data: "+ tmp);
						http.put("http://"+ EC_HOST +"/"+ url, tmp);
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
    	String url;
    	Handler subscriber;
    	long timestamp;
    	String data_timestamp;
    	
    	public DownStreamThread (String feature, Handler callback) {
    		this.feature = feature;
    		this.url = d_id +"/"+ feature;
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
				if (!json_array_has_string(EasyConnect.profile.getJSONArray("df_list"), feature)) {
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
				        http.response res = http.get("http://"+ EC_HOST +"/"+ url);
    					logging("DownStreamThread("+ feature +") pull data: "+ res.status_code);
				        if (res.status_code == 200) {
		                	deliver_data(new JSONObject(res.body));
				        }
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
    	
    	private void deliver_data (JSONObject data) throws JSONException {
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
    		// We got new data
            Message msgObj = subscriber.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putParcelable("dataset", new DataSet(data));
            bundle.putString("feature", feature);
            msgObj.setData(bundle);
            subscriber.sendMessage(msgObj);
    	}
    }
    
    static public class DataSet implements Parcelable {
    	public String timestamp;
    	private JSONArray dataset;
    	
    	public DataSet (Parcel in) {
    		this.timestamp = in.readString();
			this.dataset = null;
			
			try {
				this.dataset = new JSONArray(in.readString());
			} catch (JSONException e) {
				e.printStackTrace();
			}
    	}
    	
    	public DataSet (JSONObject in) {
			this.timestamp = "";
			this.dataset = null;
			
    		try {
				this.dataset = in.getJSONArray("samples");
				this.timestamp = this.dataset.getJSONArray(0).getString(0);
			} catch (JSONException e) {
				e.printStackTrace();
			}
    	}
    	
    	public Data newest () {
    		Data ret = new Data();
    		try {
    			JSONArray tmp = this.dataset.getJSONArray(0);
    			ret.timestamp = tmp.getString(0);
	    		ret.data = tmp.getJSONArray(1);
			} catch (JSONException e) {
				e.printStackTrace();
			}
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

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeString(this.timestamp);
			dest.writeString(this.dataset.toString());
		}
		
		public static final Parcelable.Creator<DataSet> CREATOR
		        = new Parcelable.Creator<DataSet>() {
		    public DataSet createFromParcel(Parcel in) {
		        return new DataSet(in);
		    }
		
		    public DataSet[] newArray(int size) {
		        return new DataSet[size];
		    }
		};
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
	        	notify_all_subscribers(Tag.ATTACH_SUCCESS, EC_HOST);
                try {
                    notify_all_subscribers(Tag.D_NAME_GENEREATED, profile.getString("d_name"));
                } catch (JSONException e) {
                }
            }
	    	logging("show notification: "+ ec_status);
	    	Context ctx = get_reliable_context();
	    	if (ctx == null) {
	    		return;
	    	}
	    	String text = ec_status ? EasyConnect.EC_HOST : "Connecting";
	    	ec_status_lock.release();
	        NotificationManager notification_manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
	        Notification.Builder notification_builder =
	    		new Notification.Builder(ctx)
		    	.setSmallIcon(R.drawable.ic_launcher)
		    	.setContentTitle(device_model)
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
        Log.i(device_model, "[EasyConnect] " + message);
    }
    
    static private void notify_all_subscribers (Tag tag, String message) {
    	if (subscribers == null) {
    		logging("Broadcast: No subscribers");
    		return;
    	}
    	for (Handler handler: subscribers) {
    		send_message_to(handler, tag, message);
    	}
    }
    
    static private void send_message_to (Handler handler, Tag tag, String message) {
        Message msgObj = handler.obtainMessage();
        Bundle bundle = new Bundle();
        bundle.putSerializable("tag", tag);
        bundle.putString("message", message);
        msgObj.setData(bundle);
        handler.sendMessage(msgObj);
    }
    
    static private Context get_reliable_context () {
    	if (self == null) {
    		logging("EasyConnect Service is null, use creater instead");
    		return creater;
    	}
    	return self;
    }
    
    static private class EasyConnectDataObject {
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
    	
    	public EasyConnectDataObject (Object obj) {
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
    	
    	public String toString () {
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
	    			return ret.put("data", (String)value).toString();
	    		} else if (value instanceof JSONArray) {
	    			data = (JSONArray)value;
	    		} else if (value instanceof JSONObject) {
	    			data.put((JSONObject)value);
	    		}
	    		ret.put("data", data);
    		} catch (JSONException e) {
    			logging("EasyConnectDataObject.toString() JSONException");
    			e.printStackTrace();
    		}
    		return ret.toString();
    	}
    	
    	public void accumulate (EasyConnectDataObject obj) {
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
    static public void start (Context ctx, String device_model) {
    	if (ec_service_started) {
    		logging("EasyConnect.start(): already started");
    		return;
    	}
        logging("EasyConnect.start()");
    	ec_service_started = true;
    	creater = ctx;
    	EasyConnect.device_model = device_model;
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
        Intent intent = new Intent(ctx, EasyConnect.class);
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
    
    static public String get_d_id (String mac_addr) {
        return mac_addr;
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
    
    static public void register (Handler handler) {
    	if (subscribers == null) {
    		subscribers = new HashSet<Handler>();
    	}
    	subscribers.add(handler);
    }
    
    static public void deregister (Handler handler) {
    	subscribers.remove(handler);
    }
    
    static public void attach (String d_id, JSONObject profile) {
    	EasyConnect.d_id = d_id;
    	EasyConnect.profile = profile;
    	if (!EasyConnect.profile.has("is_sim")) {
    		try {
				EasyConnect.profile.put("is_sim", false);
			} catch (JSONException e) {
				e.printStackTrace();
			}
    	}
    	
    	RegisterThread.start_working();
    }

    static public void push_data (String feature, Object data) {
    	EasyConnectDataObject ary = new EasyConnectDataObject(data);
        push_data(feature, ary);
    }
    
    static private void push_data (String feature, EasyConnectDataObject data) {
    	if (!upstream_thread_pool.containsKey(feature)) {
    		UpStreamThread ust = new UpStreamThread(feature);
    		upstream_thread_pool.put(feature, ust);
    		ust.start();
    	}
    	UpStreamThread ust = upstream_thread_pool.get(feature);
    	ust.enqueue(data);
    }
    
    static public void subscribe (String feature, Handler callback) {
    	if (!downstream_thread_pool.containsKey(feature)) {
    		DownStreamThread dst = new DownStreamThread(feature, callback);
    		downstream_thread_pool.put(feature, dst);
    		dst.start();
    	}
    }
    
    static public void unsubscribe (String feature) {
		DownStreamThread dst = downstream_thread_pool.get(feature);
		if (dst != null) {
			dst.stop_working();
			downstream_thread_pool.remove(feature);
		}
    }

    static public void detach () {
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
    	DetachThread.start_working();
    }
    
    static public long get_request_interval () {
		return EasyConnect.request_interval;
    }
    
    static public void set_request_interval (long request_interval) {
    	if (request_interval > 0) {
    		logging("Set EasyConnect.request_interval = "+ request_interval);
            EasyConnect.request_interval = request_interval;
    	}
    }
    
    // ********************* //
    // * Internal HTTP API * //
    // ********************* //

    static private boolean attach_api (JSONObject profile) {
		try {
			logging(d_id +" attaching to "+ EC_HOST);
	        String url = "http://"+ EC_HOST +"/"+ d_id;
			JSONObject tmp = new JSONObject();
			tmp.put("profile", profile);
			http.response res = http.post(url, tmp);
			if (res.status_code != 200) {
				logging("[attach_api] "+ "Response Code: "+ res.status_code);
				logging("[attach_api] "+ "Response from "+ url);
				logging("[attach_api] "+ res.body);
			}
	        return res.status_code == 200;
		} catch (NullPointerException e) {
			e.printStackTrace();
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return false;
    }

    static private boolean detach_api () {
		try {
			logging(d_id +" detaching from "+ EC_HOST);
			String url = "http://"+ EC_HOST +"/"+ d_id;
	        return http.delete(url).status_code == 200;
		} catch (NullPointerException e) {
			e.printStackTrace();
		}
		return false;
    }
    
    static private class http {
    	static public class response {
        	public String body;
        	public int status_code;
        	public response (String body, int status_code) {
                this.body = body;
                this.status_code = status_code;
            }
        }
    	
    	static public response get (String url_str) {
    		return request("GET", url_str, null);
        }
    	
    	static public response post (String url_str, JSONObject post_body) {
    		return request("POST", url_str, post_body.toString());
    	}
    	
    	static public response delete (String url_str) {
    		return request("DELETE", url_str, null);
    	}
    	
    	static public response put (String url_str, JSONObject put_body) {
    		return put(url_str, put_body.toString());
    	}
    	
    	static public response put (String url_str, String post_body) {
    		return request("PUT", url_str, post_body);
    	}
    	
    	static private response request (String method, String url_str, String request_body) {
    		try {
    			URL url = new URL(url_str);
    			HttpURLConnection connection = (HttpURLConnection)url.openConnection();
    			connection.setRequestMethod(method);
    			
    			if (method.equals("POST") || method.equals("PUT")) {
	    			connection.setDoOutput(true);	// needed, even if method had been set to POST
	    			connection.setRequestProperty("Content-Type", "application/json");
	    			
	    			OutputStream os = connection.getOutputStream();
				    os.write(request_body.getBytes());
    			}
    			
                int status_code = connection.getResponseCode();
            	InputStream in;
                
                if(status_code >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    in = new BufferedInputStream(connection.getErrorStream());
                } else {
                    in = new BufferedInputStream(connection.getInputStream());
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String body = "";
                String line = "";
                while ((line = reader.readLine()) != null) {
                    body += line + "\n";
                }
                connection.disconnect();
                reader.close();
                return new response(body, status_code);
    		} catch (MalformedURLException e) {
    			e.printStackTrace();
    			logging("MalformedURLException");
            	return new response("MalformedURLException", 400);
    		} catch (IOException e) {
    			e.printStackTrace();
    			logging("IOException");
            	return new response("IOException", 400);
    		}
    	}
    	
        static private void logging (String message) {
            Log.i(device_model, "[EasyConnect.http] " + message);
        }  	
    }
}

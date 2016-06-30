package tw.org.cic.imusensor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class DAN extends Thread {
    interface DAN2DAI {
        void pull(String odf_name, JSONArray data);
    }

    final int IOTTALK_BROADCAST_PORT = 17000;
    final int RETRY_COUNT = 3;
    final int RETRY_INTERVAL = 2000;
    public final String log_tag = "MorSensor";
    DAN2DAI dai2dai_ref;
    String mac_addr;
    JSONObject profile;
    boolean registered;
    String[] df_list;
    boolean[] df_selected;
    boolean[] df_is_odf;
    String[] df_timestamp;
    String ctl_timestamp;
    boolean suspended;

    public String init(DAN2DAI dai2dai_ref, String endpoint, String mac_addr, JSONObject profile) {
        logging("init()");
        this.dai2dai_ref = dai2dai_ref;
        this.mac_addr = mac_addr.replace(":", "");
        if (endpoint == null) {
            endpoint = search();
        }

        if (register(endpoint, profile)) {
            return CSMapi.ENDPOINT;
        }
        return "";
    }

    public boolean register (String endpoint, JSONObject profile) {
        if (endpoint != null) {
            CSMapi.ENDPOINT = endpoint;
        }
        if (CSMapi.ENDPOINT == null) {
            return false;
        }

        this.profile = profile;
        try {
            JSONArray json_df_list = profile.getJSONArray("df_list");
            df_list = new String[json_df_list.length()];
            df_selected = new boolean[df_list.length];
            df_is_odf = new boolean[df_list.length];
            df_timestamp = new String[df_list.length];
            for (int i = 0; i < df_list.length; i++) {
                df_list[i] = json_df_list.getString(i);
                df_selected[i] = false;
                df_is_odf[i] = true;
                df_timestamp[i] = "";
            }
            ctl_timestamp = "";
            suspended = true;

            profile.put("d_name", profile.getString("dm_name") + this.mac_addr.substring(this.mac_addr.length() - 4));
        } catch (JSONException e) {
            logging("init(): JSONException");
            return false;
        }


        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                if (CSMapi.register(this.mac_addr, profile)) {
                    logging("init(): Register succeed: %s", CSMapi.ENDPOINT);
                    if (!registered) {
                        registered = true;
                        this.start();
                    }
                    return true;
                }
            } catch (CSMapi.CSMError e) {
                logging("init(): REGISTER: CSMError: %s", e.getMessage());
            } catch (JSONException e) {
                logging("init(): JSONException: %s", e.getMessage());
            } catch (InterruptedIOException e) {
                logging("init(): InterruptedIOException: %s", e.getMessage());
            }
            logging("init(): Register failed, wait %d milliseconds before retry", RETRY_INTERVAL);
            try {
                Thread.sleep(RETRY_INTERVAL);
            } catch (InterruptedException e) {
                logging("init(): InterruptedException");
            }
        }
        return false;
    }

    public boolean push(String idf_name, JSONArray data) {
        logging("push(%s)", idf_name);
        try {
            if (idf_name.equals("Control")) {
                idf_name = "__Ctl_I__";
            }
            for (int i = 0; i < df_list.length; i++) {
                if (idf_name.equals(df_list[i])) {
                    df_is_odf[i] = false;
                    if (!df_selected[i]) {
                        return false;
                    }
                }
            }
            if (suspended) {
                return false;
            }
            return CSMapi.push(mac_addr, idf_name, data);
        } catch (CSMapi.CSMError e) {
            logging("push(): CSMError: %s", e.getMessage());
        } catch (JSONException e) {
            logging("push(): JSONException: %s", e.getMessage());
        } catch (InterruptedIOException e) {
            logging("deregister(): DEREGISTER: InterruptedIOException: %s", e.getMessage());
        }
        return false;
    }

    public boolean deregister() {
        logging("deregister()");
        if (!registered) {
            return true;
        }
        // stop polling first
        registered = false;
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                if (CSMapi.deregister(mac_addr)) {
                    logging("deregister(): Deregister succeed: %s", CSMapi.ENDPOINT);
                    return true;
                }
            } catch (CSMapi.CSMError e) {
                logging("deregister(): DEREGISTER: CSMError: %s", e.getMessage());
            } catch (InterruptedIOException e) {
                logging("deregister(): DEREGISTER: InterruptedIOException: %s", e.getMessage());
            }
            logging("deregister(): Deregister failed, wait %d milliseconds before retry", RETRY_INTERVAL);
            try {
                Thread.sleep(RETRY_INTERVAL);
            } catch (InterruptedException e) {
                logging("deregister(): InterruptedException");
            }
        }
        // sorry, I give up
        return false;
    }

    public void run () {
        logging("Polling: starts");
        while (registered) {
            try {
                JSONArray data = pull("__Ctl_O__", 0);
                if (data != null) {
                    if (handle_control_message(data)) {
                        dai2dai_ref.pull("Control", data);
                    } else {
                        logging("The command message is problematic, abort");
                    }
                }

                for (int i = 0; i < df_list.length; i++) {
                    if (!registered || suspended) {
                        break;
                    }
                    if (!df_is_odf[i] || !df_selected[i]) {
                        continue;
                    }
                    data = pull(df_list[i], i);
                    if (data == null) {
                        continue;
                    }
                    dai2dai_ref.pull(df_list[i], data);
                }
            } catch (JSONException e) {
                logging("Polling: JSONException: %s", e.getMessage());
            } catch (CSMapi.CSMError e) {
                logging("Polling: CSMError: %s", e.getMessage());
            } catch (InterruptedIOException e) {
                logging("Polling: InterruptedIOException: %s", e.getMessage());
                break;
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                logging("Polling: InterruptedException: %s", e.getMessage());
            }
        }
        logging("Polling: stops");
    }

    JSONArray pull (String odf_name, int index) throws JSONException, CSMapi.CSMError, InterruptedIOException {
        JSONArray dataset = CSMapi.pull(mac_addr, odf_name);
        if (dataset == null || dataset.length() == 0) {
            return null;
        }
        String timestamp = dataset.getJSONArray(0).getString(0);
        if (odf_name.equals("__Ctl_O__")) {
            if (ctl_timestamp.equals(timestamp)) {
                return null;
            }
            ctl_timestamp = timestamp;
        } else {
            if (df_timestamp[index].equals(timestamp)) {
                return null;
            }
            df_timestamp[index] = timestamp;
        }
        return dataset.getJSONArray(0).getJSONArray(1);
    }

    boolean handle_control_message (JSONArray data) {
        logging(data.toString());
        try {
            switch (data.getString(0)) {
                case "RESUME":
                    suspended = false;
                    break;
                case "SUSPEND":
                    suspended = true;
                    break;
                case "SET_DF_STATUS":
                    final String flags = data.getJSONObject(1).getJSONArray("cmd_params").getString(0);
                    if (flags.length() != df_list.length) {
                        logging("SET_DF_STATUS flag length & df_list mismatch, abort");
                        return false;
                    }
                    for (int i = 0; i < flags.length(); i++) {
                        if (flags.charAt(i) == '0') {
                            df_selected[i] = false;
                        } else {
                            df_selected[i] = true;
                        }
                    }
                    break;
            }
            return true;
        } catch (JSONException e) {
            logging("handle_control_message(): JSONException");
        }
        return false;
    }

    // ***************************** //
    // * Internal Helper Functions * //
    // ***************************** //

    String search () {
        try {
            DatagramSocket socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", IOTTALK_BROADCAST_PORT));
            byte[] lmessage = new byte[20];
            DatagramPacket packet = new DatagramPacket(lmessage, lmessage.length);
            while (true) {
                socket.receive(packet);
                String broadcast_message = new String(lmessage, 0, packet.getLength());
                if (broadcast_message.equals("easyconnect")) {
                    InetAddress ec_addr = packet.getAddress();
                    socket.close();
                    return "http://"+ ec_addr.getHostAddress() +":9999";
                }
            }
        } catch (IOException e) {
            logging("init(): IOException");
            return null;
        }
    }

    void logging (String format, Object... args) {
        logging(String.format(format, args));
    }

    void logging (String message) {
        System.out.printf("[%s][DAN] %s%n", log_tag, message);
    }
}
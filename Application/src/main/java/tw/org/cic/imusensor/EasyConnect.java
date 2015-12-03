package tw.org.cic.imusensor;

import java.util.Arrays;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

class EasyConnect {
    static public  int      EC_PORT           = 9999;
    static         String   EC_HOST           = "openmtc.darkgerm.com:"+ EC_PORT;
    static public  int      EC_BROADCAST_PORT = 17000;
    static private String   _mac_addr;
    static private String   _d_id;
    static private String   _dm_name;
    static private String[] _df_list;
    static private String   _d_name;
    static private String   _u_name;
    static private String   _phone_mac_addr;

    static public boolean attach (
        String mac_addr,
        String dm_name,
        String[] df_list,
        String d_name,
        String u_name,
        String phone_mac_addr) {
    	
    	if (mac_addr == null) {
    		return false;
    	}

        _mac_addr = _get_clean_addr(mac_addr);
        _d_id = _get_device_id(mac_addr);
        _dm_name = dm_name;
        _df_list = df_list;
        _d_name = d_name;
        _u_name = u_name;
        _phone_mac_addr = _get_clean_addr(phone_mac_addr);

        try {
            JSONObject profile = new JSONObject();
            profile.put("dm_name", _dm_name);
            profile.put("d_name", _d_name);

            JSONArray dfl = new JSONArray();
            for (String i : _df_list) {
                dfl.put(i);
            }
            profile.put("features", dfl);

            profile.put("monitor", _phone_mac_addr);
            profile.put("u_name", _u_name);

            String profile_str = profile.toString().replace(" ", "");

            String url = "http://"+ EC_HOST +"/create/"+ _d_id +"?profile="+ profile_str;
            return HttpRequest.get(url).status_code == 200;
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return false;

    }
    
    static public boolean reattach_to (String new_host) {
    	detach();
    	EC_HOST = new_host;
    	return attach(_mac_addr, _dm_name, _df_list, _d_name, _u_name, _phone_mac_addr);
    }

    static public boolean push_data (String feature, int data) {
        return _push_data(feature, "["+ data +"]");
    }
    static public boolean push_data (String feature, int[] data) {
        return _push_data(feature, Arrays.toString(data).replace(" ", ""));
    }
    static public boolean push_data (String feature, float data) {
        return _push_data(feature, "["+ data +"]");
    }
    static public boolean push_data (String feature, float[] data) {
        return _push_data(feature, Arrays.toString(data).replace(" ", ""));
    }
    static public boolean push_data (String feature, double data) {
        return _push_data(feature, "["+ data +"]");
    }
    static public boolean push_data (String feature, double[] data) {
        return _push_data(feature, Arrays.toString(data).replace(" ", ""));
    }
    static public boolean push_data (String feature, byte[] data) {
        return _push_data(feature, Arrays.toString(data).replace(" ", ""));
    }
    static public boolean push_data (String feature, JSONArray data) {
        return _push_data(feature, data.toString().replace(" ", ""));
    }
    static public boolean push_data (String feature, String data) {
    	return _push_data(feature, "[\""+ data +"\"]");
    }

    static private boolean _push_data (String feature, String data) {
        String url = "http://"+ EC_HOST +"/push/"+ _d_id +"/"+ feature +"?data="+ data;
        return HttpRequest.get(url).status_code == 200;

    }

    static public JSONObject pull_data (String feature) {
        String url = "http://"+ EC_HOST +"/pull/"+ _d_id +"/"+ feature;
        HttpRequest.HttpResponse a = HttpRequest.get(url);

        if (a.status_code != 200) {
            try {
                JSONObject ret = new JSONObject();
                ret.put("timestamp", "none");
                ret.put("data", new JSONArray());
                return ret;
                
            } catch (JSONException e) {
                e.printStackTrace();
            }
            
        }

        try {
            return new JSONObject(a.body);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static public boolean detach () {
        String url = "http://"+ EC_HOST +"/delete/"+ _d_id;
        return HttpRequest.get(url).status_code == 200;

    }

    static private String _get_clean_addr (String m) {
        return m.replace(":", "");
    }

    static private String _get_device_id (String m) {
        return "defema"+ _get_clean_addr(m);
    }
    
    static public void reset_ec_host () {
    	EC_PORT = 9999;
    	EC_HOST = "openmtc.darkgerm.com:"+ EC_PORT;
    	EC_BROADCAST_PORT = 17000;
    }

}

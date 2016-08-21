package tw.org.cic.imusensor;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class CSMapi {
	static private String log_tag = "MorSensor";
	static public String ENDPOINT = "http://openmtc.darkgerm.com:9999";

	public static class CSMError extends Exception {
		public CSMError (String message) {
	        super(message);
		}
	}

	static public boolean register (String d_id, JSONObject profile) throws CSMError, JSONException, InterruptedIOException {
		try {
	        String url = ENDPOINT +"/"+ d_id;
			logging("register(): Response from %s", url);
			JSONObject tmp = new JSONObject();
			tmp.put("profile", profile);
			Response res = request("POST", url, tmp.toString());
			if (res.status_code != 200) {
				logging("register(): Response from %s", url);
				logging("register(): Response Code: %d", res.status_code);
				logging("register(): %s", res.body);
				throw new CSMError(res.body);
			}
	        return true;
		} catch (NullPointerException e) {
			logging("pull(): %s", e);
		}
		return false;
	}

    static public boolean deregister (String d_id) throws CSMError, InterruptedIOException {
		try {
			String url = ENDPOINT +"/"+ d_id;
			logging("[deregister] "+ url);
			Response res = request("DELETE", url, null);
			if (res.status_code != 200) {
				logging("deregister(): Response from %s", url);
				logging("deregister(): Response Code: %d", res.status_code);
				logging("deregister(): %s", res.body);
				throw new CSMError(res.body);
			}
	        return true;
		} catch (NullPointerException e) {
			logging("pull(): %s", e);
		}
		return false;
    }

    static public boolean push (String d_id, String df_name, JSONArray data) throws CSMError, JSONException, InterruptedIOException {
    	try {
			//logging(mac_addr +" pushing to "+ ENDPOINT);
    	    JSONObject obj = new JSONObject();
    	    obj.put("data", data);
			String url = ENDPOINT +"/"+ d_id + "/" + df_name;
			Response res = request("PUT", url, obj.toString());
			if (res.status_code != 200) {
				logging("push(): Response from %s", url);
				logging("push(): Response Code: %d", res.status_code);
				logging("push(): %s", res.body);
				throw new CSMError(res.body);
			}
	        return true;
		} catch (NullPointerException e) {
			logging("pull(): %s", e);
		}
		return false;
    }

    static public JSONArray pull (String d_id, String df_name) throws JSONException, CSMError, InterruptedIOException {
	    try {
			//logging(mac_addr +" pulling from "+ ENDPOINT);
			String url = ENDPOINT +"/"+ d_id + "/" + df_name;
	        Response res = request("GET", url, null);
			if (res.status_code != 200) {
				logging("pull(): Response from %s", url);
				logging("pull(): Response Code: %d", res.status_code);
				logging("pull(): %s", res.body);
				throw new CSMError(res.body);
			}
			JSONObject tmp = new JSONObject(res.body);
	        return tmp.getJSONArray("samples");

		} catch (NullPointerException e) {
			logging("pull(): %s", e);
		}
		return null;
	}

    static public JSONObject tree () throws CSMError, InterruptedIOException {
	    try {
			//logging(mac_addr +" pulling from "+ ENDPOINT);
			String url = ENDPOINT +"/tree";
	        Response res = request("GET", url, null);
			if (res.status_code != 200) {
				logging("tree(): Response from %s", url);
				logging("tree(): Response Code: %d", res.status_code);
				logging("tree(): %s", res.body);
				throw new CSMError(res.body);
			}
			return new JSONObject(res.body);

		} catch (NullPointerException | JSONException e) {
			logging("pull(): %s", e);
		}
		return null;
    }

	static public class Response {
		public String body;
		public int status_code;
		public Response(String body, int status_code) {
			this.body = body;
			this.status_code = status_code;
		}
	}

	static private Response request (String method, String url_str, String request_body) throws InterruptedIOException {
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
			String line;
			while ((line = reader.readLine()) != null) {
				body += line + "\n";
			}
			connection.disconnect();
			reader.close();
			return new Response(body, status_code);
		} catch (MalformedURLException e) {
			e.printStackTrace();
			logging("MalformedURLException");
			return new Response("MalformedURLException", 400);
		} catch (InterruptedIOException e) {
			e.printStackTrace();
			throw e;
		} catch (IOException e) {
			e.printStackTrace();
			logging("IOException");
			return new Response("IOException", 400);
		}
	}

	static void logging (String format, Object... args) {
		logging(String.format(format, args));
	}

	static void logging (String message) {
		System.out.printf("[%s][CSMapi] %s%n", log_tag, message);
	}
}

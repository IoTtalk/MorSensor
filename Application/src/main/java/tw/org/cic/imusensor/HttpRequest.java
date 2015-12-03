package tw.org.cic.imusensor;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import android.util.Log;

class HttpRequest {

    static public class HttpResponse {
        public String body;
        public int status_code;
        
        public HttpResponse (String body, int status_code) {
            this.body = body;
            this.status_code = status_code;
        }
    }

    static public HttpResponse get (String url_str) {
        
        try {
            Thread.sleep(10);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }
        
        HttpURLConnection urlConnection = null;
        BufferedReader reader = null;
        String body = "";
        int status_code = 0;
        
        notify_message(url_str);
        
        try {
            URL url = new URL(url_str);
            urlConnection = (HttpURLConnection) url.openConnection();
            
            status_code = urlConnection.getResponseCode();
            
            InputStream in;
            
            if(status_code >= HttpURLConnection.HTTP_BAD_REQUEST)
                in = new BufferedInputStream(urlConnection.getErrorStream());
            else
                in = new BufferedInputStream(urlConnection.getInputStream());
            
            reader = new BufferedReader(new InputStreamReader(in) );
            body = "";
            String line = "";
            while ((line = reader.readLine()) != null) {
                body += line + "\n";
            }
            
            return new HttpResponse(body, status_code);
            
        } catch (MalformedURLException e) {
            notify_message("MalformedURLException");
            e.printStackTrace();
            return new HttpResponse("", status_code);
        } catch (FileNotFoundException e) {
            notify_message("FileNotFoundException");
            e.printStackTrace();
            return new HttpResponse("", status_code);
        } catch (IOException e) {
            notify_message("IOException");
            e.printStackTrace();
            return new HttpResponse("", status_code);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            
        }
    }
    
    static boolean logging = false;
    
    static private void notify_message (String message) {
        if ( !logging ) return;
        
        //System.out.println("[HttpRequest] " + message);
        Log.i(C.log_tag, "[HttpRequest] " + message);
        
    }

}

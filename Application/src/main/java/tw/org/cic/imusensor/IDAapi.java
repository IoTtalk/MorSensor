package tw.org.cic.imusensor;
import org.json.JSONArray;
public interface IDAapi {
    enum Event {
        INITIALIZATION_FAILED,
        INITIALIZATION_SUCCEEDED,
        SEARCH_STARTED,
        IDA_DISCOVERED,
        SEARCH_STOPPED,
        CONNECTION_FAILED,
        CONNECTION_SUCCEEDED,
        WRITE_FAILED,
        WRITE_SUCCEEDED,
        READ_FAILED,
        DISCONNECTION_FAILED,
        DISCONNECTION_SUCCEEDED,
    }
    interface IDFhandler {
        void receive(String idf, JSONArray data);
    }
    void init(IDFhandler idf_handler_obj, Object... args);
    void search();
    void connect(String id);
    void write(String odf, JSONArray data);
    void disconnect();
}
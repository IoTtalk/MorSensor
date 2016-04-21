package tw.org.cic.imusensor;

public interface IDAManager {
    public enum EventTag {
        INITIALIZATION_FAILED,
        INITIALIZED,
        SEARCHING_STARTED,
        FOUND_NEW_IDA,
        SEARCHING_STOPPED,
        CONNECTION_FAILED,
        CONNECTED,
        WRITE_FAILED,
        DATA_AVAILABLE,
        DISCONNECTION_FAILED,
        DISCONNECTED,
    }

    public interface Subscriber {
        abstract public void on_event(final EventTag event_tag, final Object message);
    }

    public abstract class IDA {
        String id;
    }

    public void subscribe(Subscriber s);
    public void unsubscribe(Subscriber s);
    public void search();
    public void stop_searching();
    public void connect(IDA ida);
    public void write(byte[] command);
    public void disconnect();
}
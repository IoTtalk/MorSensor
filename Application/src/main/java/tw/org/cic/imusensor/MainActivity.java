package tw.org.cic.imusensor;

import android.content.Intent;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;

public class MainActivity extends Activity {
    Handler ui_handler = new Handler();
    DAN dan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dan = new DAN();
        new DAI(ui_handler, dan).start();
    }

    @Override
    public void onPause () {
        super.onPause();
        if (isFinishing()) {
            new Thread () {
                @Override
                public void run() {
                    dan.deregister();
                }
            }.start();
        }
    }
}

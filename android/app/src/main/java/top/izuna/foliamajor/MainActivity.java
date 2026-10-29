package top.izuna.foliamajor;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Capacitor 8: custom plugins MUST be registered here, otherwise the JS
        // side gets "not implemented" for every LocalMedia call and SAF never opens.
        registerPlugin(LocalMediaPlugin.class);
    }
}

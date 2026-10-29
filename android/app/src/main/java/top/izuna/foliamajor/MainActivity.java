package top.izuna.foliamajor;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Capacitor: registerPlugin() MUST run BEFORE super.onCreate() --
        // the bridge is built inside super.onCreate() and freezes the plugin
        // registry. Registering after it is a silent no-op ("not implemented").
        registerPlugin(LocalMediaPlugin.class);
        registerPlugin(KugouServerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}

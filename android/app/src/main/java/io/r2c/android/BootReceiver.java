package io.r2c.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the proxy after a reboot or an app update, when the user opted in.
 *
 * <p>Android does not resume foreground services on its own, so this is the only way
 * to have the port come back without opening the app.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        AppSettings settings = AppSettings.load(context);
        if (!settings.startOnBoot) return;
        if (!AppSettings.isValidPort(settings.port)) return;
        ProxyService.start(context);
    }
}
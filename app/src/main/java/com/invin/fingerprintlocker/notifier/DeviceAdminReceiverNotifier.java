package com.invin.fingerprintlocker.notifier;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.invin.fingerprintlocker.R;

/**
 * {@link DeviceAdminReceiver} that notifies upon 'Device Administrator' status.
 */
public class DeviceAdminReceiverNotifier extends DeviceAdminReceiver {
    private void showToast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        showToast(context, context.getString(R.string.admin_receiver_status_enabled));
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        showToast(context, context.getString(R.string.admin_receiver_status_disabled));
    }
}

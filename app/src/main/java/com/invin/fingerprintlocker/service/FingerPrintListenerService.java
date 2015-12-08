package com.invin.fingerprintlocker.service;

import android.app.IntentService;
import android.content.Intent;
import android.os.SystemClock;
import android.text.format.DateFormat;

import com.invin.fingerprintlocker.R;

/**
 * Created by Neil on 12/07/15.
 */
public class FingerPrintListenerService extends IntentService {

    public static final String PARAMETER = "FingerPrintListenerService";

    public FingerPrintListenerService() {
        super("FingerPrintLocker");
    }

    @Override
    protected void onHandleIntent(Intent workIntent) {
        // Gets data from the incoming Intent
        String dataString = workIntent.getDataString();
        while(true) {
            System.out.println("In Background");
        }
    }
}

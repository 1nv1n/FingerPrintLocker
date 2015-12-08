package com.invin.fingerprintlocker.application;

import android.app.Application;
import android.util.Log;

import com.invin.fingerprintlocker.module.FingerprintModule;

import dagger.ObjectGraph;

/**
 * The {@link Application} that holds the ObjectGraph in Dagger and enables dependency injection.
 */
public class InjectedApplication extends Application {

    private static final String TAG = InjectedApplication.class.getSimpleName();

    private ObjectGraph mObjectGraph;

    @Override
    public void onCreate() {
        super.onCreate();
        initObjectGraph(new FingerprintModule(this));
    }

    /**
     * Initialize the Dagger module.
     *
     * @param module for Dagger
     */
    public void initObjectGraph(Object module) {
        mObjectGraph = module != null ? ObjectGraph.create(module) : null;
    }

    /**
     * Injects an {@link Object}.
     *
     * @param object
     */
    public void inject(Object object) {
        if (mObjectGraph == null) {
            Log.i(TAG, "Object graph is not initialized.");
            return;
        }
        mObjectGraph.inject(object);
    }
}

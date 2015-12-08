package com.invin.fingerprintlocker.activity;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.support.v4.app.NotificationCompat;
import android.util.Base64;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.invin.fingerprintlocker.R;
import com.invin.fingerprintlocker.application.InjectedApplication;
import com.invin.fingerprintlocker.fragment.FingerprintAuthenticationDialogFragment;
import com.invin.fingerprintlocker.notifier.DeviceAdminReceiverNotifier;
import com.invin.fingerprintlocker.service.FingerPrintListenerService;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.inject.Inject;

/**
 * Main Activity for the App.
 * Locks the device.
 */
public class LockActivity extends Activity {

    private static final String TAG = LockActivity.class.getSimpleName();
    private static final String FINGERPRINT_DIALOG_FRAGMENT_TAG = "FingerPrintDialogFragment";
    private static final String SECRET_MESSAGE = "Message";

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private int field = 0x00000020;

    /** Alias for our key in the Android Key Store */
    private static final String KEY_NAME = "FingerPrintLockerKey";

    @Inject KeyguardManager mKeyguardManager;
    @Inject FingerprintManager mFingerprintManager;
    @Inject FingerprintAuthenticationDialogFragment mFragment;
    @Inject KeyStore mKeyStore;
    @Inject KeyGenerator mKeyGenerator;
    @Inject Cipher mCipher;
    @Inject SharedPreferences mSharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((InjectedApplication) getApplication()).inject(this);

        setContentView(R.layout.lock_activity);

        if(PackageManager.PERMISSION_GRANTED != checkCallingOrSelfPermission("android.permission.USE_FINGERPRINT")) {
            Toast.makeText(this, "Fingerprint usage permission was not granted.", Toast.LENGTH_LONG).show();
            return;
        }

        Button lockButton = (Button) findViewById(R.id.lock_button);
        if (!mKeyguardManager.isKeyguardSecure()) {
            Toast.makeText(this, "Secure lock screen has not been set up.\n" + "Please go to: 'Settings -> Security -> Fingerprint' to set up a fingerprint", Toast.LENGTH_LONG).show();
            lockButton.setEnabled(false);
            return;
        }

        if (!mFingerprintManager.hasEnrolledFingerprints()) {
            Toast.makeText(this, "Go to 'Settings -> Security -> Fingerprint' and register at least one fingerprint", Toast.LENGTH_LONG).show();
            lockButton.setEnabled(false);
            return;
        }

        // Setup the App on the Status Bar
        setToStatusBar();

        createKey();

        lockButton.setEnabled(true);
        lockButton.setVisibility(View.GONE);
        lockButton.setBackgroundColor(Color.TRANSPARENT);
        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findViewById(R.id.confirmation_message).setVisibility(View.GONE);
                findViewById(R.id.encrypted_message).setVisibility(View.GONE);

                // Set up the crypto object for later.
                // The object will be authenticated by use of the fingerprint.
                if (initCipher()) {
                    mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mCipher));
                    boolean useFingerprintPreference = mSharedPreferences.getBoolean(getString(R.string.checkbox_fingerprint_authentication_key), true);
                    if (useFingerprintPreference) {
                        mFragment.setStage(FingerprintAuthenticationDialogFragment.Stage.FINGERPRINT);
                    } else {
                        mFragment.setStage(FingerprintAuthenticationDialogFragment.Stage.PASSWORD);
                    }
                    mFragment.show(getFragmentManager(), FINGERPRINT_DIALOG_FRAGMENT_TAG);
                } else {
                    // This happens if the lock screen has been disabled or if a fingerprint got
                    // enrolled. Show the dialog to authenticate with their password first
                    // and ask the user if they want to authenticate with fingerprints in the
                    // future
                    mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mCipher));
                    mFragment.setStage(FingerprintAuthenticationDialogFragment.Stage.NEW_FINGERPRINT_ENROLLED);
                    mFragment.show(getFragmentManager(), FINGERPRINT_DIALOG_FRAGMENT_TAG);
                }
            }
        });
        lockButton.performClick();

        FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
        fragmentTransaction.hide(mFragment);
        fragmentTransaction.commit();

        Intent lockIntent = new Intent(this, FingerPrintListenerService.class);
        lockIntent.putExtra(FingerPrintListenerService.PARAMETER, "SendToBackground");
        startService(lockIntent);
    }

    /**
     * Adds the App to the Status Bar.
     */
    private void setToStatusBar() {
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.drawable.ic_fingerprint_success)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.app_name));

        Intent resultIntent = new Intent(this, LockActivity.class);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(
                this,
                0,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        mBuilder.setContentIntent(resultPendingIntent);

        // Sets an ID for the notification
        int mNotificationId = 001;
        // Gets an instance of the NotificationManager service
        NotificationManager mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Builds the notification and issues it.
        mNotifyMgr.notify(mNotificationId, mBuilder.build());
    }

    /**
     * Initialize the {@link Cipher} instance with the created key in the {@link #createKey()}
     * method.
     *
     * @return {@code true} if initialization is successful, {@code false} if the lock screen has
     * been disabled or reset after the key was generated, or if a fingerprint got enrolled after
     * the key was generated.
     */
    private boolean initCipher() {
        try {
            mKeyStore.load(null);
            SecretKey key = (SecretKey) mKeyStore.getKey(KEY_NAME, null);
            mCipher.init(Cipher.ENCRYPT_MODE, key);
            return true;
        } catch (KeyPermanentlyInvalidatedException e) {
            return false;
        } catch (KeyStoreException | CertificateException | UnrecoverableKeyException | IOException
                | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to init Cipher", e);
        }
    }

    /**
     * Additional processing once the authentication has been processed.
     *
     * @param withFingerprint (Was authentication with a fingerprint or not)
     */
    public void onLockClick(boolean withFingerprint) {
        if (withFingerprint) {
            // If the user has authenticated with fingerprint, verify that using cryptography and
            // then show the confirmation message.
            tryEncrypt();
        } else {
            // Authentication happened with backup password. Just show the confirmation message.
            showConfirmation(null);
        }
    }

    /**
     * Show the confirmation, if the fingerprint was used.
     *
     * @param encrypted (Array of encrypted bytes)
     */
    private void showConfirmation(byte[] encrypted) {
        findViewById(R.id.confirmation_message).setVisibility(View.VISIBLE);
        TextView encryptedTextView = (TextView) findViewById(R.id.encrypted_message);
        encryptedTextView.setVisibility(View.VISIBLE);
        if (encrypted != null) {
            encryptedTextView.setText(Base64.encodeToString(encrypted, 0));
            lockDevice(getApplicationContext());
        } else {
            encryptedTextView.setText("Encyption Unsuccessful");
        }
        this.finish();
    }

    /**
     * Turns the screen off and locks the device.
     * Provided that proper rights are given.
     *
     * @param context (The application context)
     */
    static void lockDevice(final Context context) {
        DevicePolicyManager policyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);

        if (policyManager.isAdminActive(new ComponentName(context, DeviceAdminReceiverNotifier.class))) {
            policyManager.lockNow();
        } else {
            Toast.makeText(context, R.string.device_admin_not_enabled, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Tries to encrypt some data with the generated key in {@link #createKey}.
     * This only works if the user has just authenticated via fingerprint.
     */
    private void tryEncrypt() {
        try {
            byte[] encrypted = mCipher.doFinal(SECRET_MESSAGE.getBytes());
            showConfirmation(encrypted);
        } catch (BadPaddingException | IllegalBlockSizeException e) {
            Toast.makeText(this, "Failed to encrypt the data with the generated key. "
                    + "Retry the purchase", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Failed to encrypt the data with the generated key." + e.getMessage());
        }
    }

    /**
     * Creates a symmetric key in the Android Key Store which can only be used after the user has
     * authenticated with fingerprint.
     */
    public void createKey() {
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of
        // enrolled fingerprints has changed.
        try {
            mKeyStore.load(null);
            // Set the alias of the entry in Android KeyStore where the key will appear
            // and the constrains (purposes) in the constructor of the Builder
            mKeyGenerator.init(new KeyGenParameterSpec.Builder(KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    // Require the user to authenticate with a fingerprint to authorize every use
                    // of the key
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());
            mKeyGenerator.generateKey();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException
                | CertificateException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_settings, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}


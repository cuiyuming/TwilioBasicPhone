/*
 *  Copyright (c) 2011 by Twilio, Inc., all rights reserved.
 *
 *  Use of this software is subject to the terms and conditions of 
 *  the Twilio Terms of Service located at http://www.twilio.com/legal/tos
 */

package com.twilio.example.basicphone;

import java.util.Map;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.util.Log;

import com.twilio.client.Connection;
import com.twilio.client.ConnectionListener;
import com.twilio.client.Device;
import com.twilio.client.Device.Capability;
import com.twilio.client.DeviceListener;
import com.twilio.client.PresenceEvent;
import com.twilio.client.Twilio;

public class BasicPhone implements DeviceListener,
                                   ConnectionListener
{
    private static final String TAG = "BasicPhone";

    // TODO: change this to point to the script on your public server
    private static final String AUTH_PHP_SCRIPT = "http://6a3892d0.ngrok.io/token";

    public interface LoginListener
    {
        public void onLoginStarted();
        public void onLoginFinished();
        public void onLoginError(Exception error);
    }

    public interface BasicConnectionListener
    {
        public void onIncomingConnectionDisconnected();
        public void onConnectionConnecting();
        public void onConnectionConnected();
        public void onConnectionFailedConnecting(Exception error);
        public void onConnectionDisconnecting();
        public void onConnectionDisconnected();
        public void onConnectionFailed(Exception error);
    }

    public interface BasicDeviceListener
    {
        public void onDeviceStartedListening();
        public void onDeviceStoppedListening(Exception error);
    }

    private static BasicPhone instance;
    public static final BasicPhone getInstance(Context context)
    {
        if (instance == null)
            instance = new BasicPhone(context);
        return instance;
    }

    private final Context context;
    private LoginListener loginListener;
    private BasicConnectionListener basicConnectionListener;
    private BasicDeviceListener basicDeviceListener;

    private static boolean twilioSdkInited;
    private static boolean twilioSdkInitInProgress;
    private boolean queuedConnect;

    private Device device;
    private Connection pendingIncomingConnection;
    private Connection connection;
    private boolean speakerEnabled;
    
    private String lastClientName;
    private boolean lastAllowOutgoing;
    private boolean lastAllowIncoming;

    private BasicPhone(Context context)
    {
        this.context = context;
    }

    public void setListeners(LoginListener loginListener,
                             BasicConnectionListener basicConnectionListener,
                             BasicDeviceListener basicDeviceListener)
    {
        this.loginListener = loginListener;
        this.basicConnectionListener = basicConnectionListener;
        this.basicDeviceListener = basicDeviceListener;
    }

    private void obtainCapabilityToken(String clientName, 
    								  boolean allowOutgoing, 
    								  boolean allowIncoming)
    {
    	StringBuilder url = new StringBuilder();
    	url.append(AUTH_PHP_SCRIPT);
    	url.append("?allowOutgoing=").append(allowOutgoing);
    	if (allowIncoming && (clientName != null)) {
    		url.append("&&client=").append(clientName);
    	}
    	
        // This runs asynchronously!
    	new GetAuthTokenAsyncTask().execute(url.toString());
    }

    private boolean isCapabilityTokenValid()
    {
        if (device == null || device.getCapabilities() == null)
            return false;
        long expTime = (Long)device.getCapabilities().get(Capability.EXPIRATION);
        return expTime - System.currentTimeMillis() / 1000 > 0;
    }

    private void updateAudioRoute()
    {
        AudioManager audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(speakerEnabled);
    }

    public void login(final String clientName, 
			  		  final boolean allowOutgoing, 
			  		  final boolean allowIncoming)
    {
        if (loginListener != null)
            loginListener.onLoginStarted();
        
        this.lastClientName = clientName;
        this.lastAllowOutgoing = allowOutgoing;
        this.lastAllowIncoming = allowIncoming;

        if (!twilioSdkInited) {
            if (twilioSdkInitInProgress)
                return;

            twilioSdkInitInProgress = true;
            Twilio.setLogLevel(Log.DEBUG);
            
            Twilio.initialize(context, new Twilio.InitListener()
            {
                @Override
                public void onInitialized()
                {
                    twilioSdkInited = true;
                    twilioSdkInitInProgress = false;
                    obtainCapabilityToken(clientName, allowOutgoing, allowIncoming);
                }

                @Override
                public void onError(Exception error)
                {
                    twilioSdkInitInProgress = false;
                    if (loginListener != null)
                        loginListener.onLoginError(error);
                }
            });
        } else {
        	obtainCapabilityToken(clientName, allowOutgoing, allowIncoming);
        }
    }

    private void reallyLogin(final String capabilityToken)
    {
        try {
            if (device == null) {
                device = Twilio.createDevice(capabilityToken, this);
                Intent intent = new Intent(context, BasicPhoneActivity.class);
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                device.setIncomingIntent(pendingIntent);
            } else
                device.updateCapabilityToken(capabilityToken);

            if (loginListener != null)
                loginListener.onLoginFinished();

            if (queuedConnect) {
                // If someone called connect() before we finished initializing
                // the SDK, let's take care of that here.
                connect(null);
                queuedConnect = false;
            }
        } catch (Exception e) {
            if (device != null)
                device.release();
            device = null;

            if (loginListener != null)
                loginListener.onLoginError(e);
        }
    }

    public void setSpeakerEnabled(boolean speakerEnabled)
    {
        if (speakerEnabled != this.speakerEnabled) {
            this.speakerEnabled = speakerEnabled;
            updateAudioRoute();
        }
    }

    public void connect(Map<String, String> inParams)
    {
        if (twilioSdkInitInProgress) {
            // If someone calls connect() before the SDK is initialized, we'll remember
            // that fact and try to connect later.
            queuedConnect = true;
            return;
        }

        if (!isCapabilityTokenValid())
            login(lastClientName, lastAllowOutgoing, lastAllowIncoming);

        if (device == null)
            return;

        if (canMakeOutgoing()) {
            disconnect();

            connection = device.connect(inParams, this);
            if (connection == null && basicConnectionListener != null)
                basicConnectionListener.onConnectionFailedConnecting(new Exception("Couldn't create new connection"));
        }
    }

    public void disconnect()
    {
        if (connection != null) {
            connection.disconnect();  // will null out in onDisconnected()
            if (basicConnectionListener != null)
                basicConnectionListener.onConnectionDisconnecting();
        }
    }

    public void acceptConnection()
    {
        if (pendingIncomingConnection != null) {
            if (connection != null)
                disconnect();

            pendingIncomingConnection.accept();
            connection = pendingIncomingConnection;
            pendingIncomingConnection = null;
        }
    }

    public void ignoreIncomingConnection()
    {
        if (pendingIncomingConnection != null) {
            pendingIncomingConnection.ignore();
        }
    }

    public boolean isConnected()
    {
        return connection != null && connection.getState() == Connection.State.CONNECTED;
    }

    public Connection.State getConnectionState()
    {
        return connection != null ? connection.getState() : Connection.State.DISCONNECTED;
    }

    public boolean hasPendingConnection()
    {
        return pendingIncomingConnection != null;
    }

    public boolean handleIncomingIntent(Intent intent)
    {
        Device inDevice = intent.getParcelableExtra(Device.EXTRA_DEVICE);
        Connection inConnection = intent.getParcelableExtra(Device.EXTRA_CONNECTION);
        if (inDevice == null && inConnection == null)
            return false;

        intent.removeExtra(Device.EXTRA_DEVICE);
        intent.removeExtra(Device.EXTRA_CONNECTION);

        if (pendingIncomingConnection != null) {
            Log.i(TAG, "A pending connection already exists");
            inConnection.ignore();
            return false;
        }

        pendingIncomingConnection = inConnection;
        pendingIncomingConnection.setConnectionListener(this);

        return true;
    }

    public boolean canMakeOutgoing()
    {
        if (device == null)
            return false;

        Map<Capability, Object> caps = device.getCapabilities();
        return caps.containsKey(Capability.OUTGOING) && (Boolean)caps.get(Capability.OUTGOING);
    }

    public boolean canAcceptIncoming()
    {
        if (device == null)
            return false;

        Map<Capability, Object> caps = device.getCapabilities();
        return caps.containsKey(Capability.INCOMING) && (Boolean)caps.get(Capability.INCOMING);
    }
    
    public void setCallMuted(boolean isMuted) {
    	if (connection != null) {
    		connection.setMuted(isMuted);
    	}
    }

    @Override  /* DeviceListener */
    public void onStartListening(Device inDevice)
    {
        if (basicDeviceListener != null)
            basicDeviceListener.onDeviceStartedListening();
    }

    @Override  /* DeviceListener */
    public void onStopListening(Device inDevice)
    {
        if (basicDeviceListener != null)
            basicDeviceListener.onDeviceStoppedListening(null);
    }

    @Override  /* DeviceListener */
    public void onStopListening(Device inDevice, int inErrorCode, String inErrorMessage)
    {
        if (basicDeviceListener != null)
            basicDeviceListener.onDeviceStoppedListening(new Exception(inErrorMessage));
    }

    @Override  /* DeviceListener */
    public boolean receivePresenceEvents(Device inDevice)
    {
        return false;
    }

    @Override  /* DeviceListener */
    public void onPresenceChanged(Device inDevice, PresenceEvent inPresenceEvent) { }

    @Override  /* ConnectionListener */
    public void onConnecting(Connection inConnection)
    {
        if (basicConnectionListener != null)
            basicConnectionListener.onConnectionConnecting();
    }

    @Override  /* ConnectionListener */
    public void onConnected(Connection inConnection)
    {
        updateAudioRoute();
        if (basicConnectionListener != null)
            basicConnectionListener.onConnectionConnected();
    }

    @Override  /* ConnectionListener */
    public void onDisconnected(Connection inConnection)
    {
        if (inConnection == connection) {
            connection = null;
            if (basicConnectionListener != null)
                basicConnectionListener.onConnectionDisconnected();
        } else if (inConnection == pendingIncomingConnection) {
            pendingIncomingConnection = null;
            if (basicConnectionListener != null)
                basicConnectionListener.onIncomingConnectionDisconnected();
        }
    }

    @Override  /* ConnectionListener */
    public void onDisconnected(Connection inConnection, int inErrorCode, String inErrorMessage)
    {
        if (inConnection == connection) {
            connection = null;
            if (basicConnectionListener != null)
                basicConnectionListener.onConnectionFailedConnecting(new Exception(inErrorMessage));
        }
    }
    
    
    private class GetAuthTokenAsyncTask extends AsyncTask<String, Void, String> {

		@Override
		protected void onPostExecute(String result) {
			super.onPostExecute(result);
			BasicPhone.this.reallyLogin(result);
		}

		@Override
		protected String doInBackground(String... params) {
			String capabilityToken = null;
			try {
				capabilityToken = HttpHelper.httpGet(params[0]);;
			} catch (Exception e) {
				e.printStackTrace();
			}
			return capabilityToken;
		}
    }

}

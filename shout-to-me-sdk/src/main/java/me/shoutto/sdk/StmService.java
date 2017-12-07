package me.shoutto.sdk;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.shoutto.sdk.internal.ChannelManager;
import me.shoutto.sdk.internal.ProximitySensorClient;
import me.shoutto.sdk.internal.S3Client;
import me.shoutto.sdk.internal.http.ChannelSubscriptionUrlProvider;
import me.shoutto.sdk.internal.http.CountResponseAdapter;
import me.shoutto.sdk.internal.http.GsonListResponseAdapter;
import me.shoutto.sdk.internal.http.NullResponseAdapter;
import me.shoutto.sdk.internal.http.MessageCountUrlProvider;
import me.shoutto.sdk.internal.http.TopicUrlProvider;
import me.shoutto.sdk.internal.location.UpdateUserLocationController;
import me.shoutto.sdk.internal.usecases.CreateChannelSubscription;
import me.shoutto.sdk.internal.usecases.CreateTopicPreference;
import me.shoutto.sdk.internal.usecases.DeleteChannelSubscription;
import me.shoutto.sdk.internal.usecases.DeleteTopicPreference;
import me.shoutto.sdk.internal.usecases.GetChannelSubscription;
import me.shoutto.sdk.internal.usecases.GetMessage;
import me.shoutto.sdk.internal.usecases.GetMessageCount;
import me.shoutto.sdk.internal.usecases.GetMessages;
import me.shoutto.sdk.internal.usecases.GetUser;
import me.shoutto.sdk.internal.usecases.UpdateUser;
import me.shoutto.sdk.internal.usecases.UploadShout;
import me.shoutto.sdk.internal.StmPreferenceManager;
import me.shoutto.sdk.internal.http.DefaultUrlProvider;
import me.shoutto.sdk.internal.http.GsonRequestAdapter;
import me.shoutto.sdk.internal.http.GsonObjectResponseAdapter;
import me.shoutto.sdk.internal.http.DefaultAsyncEntityRequestProcessor;
import me.shoutto.sdk.internal.http.StmHttpSender;
import me.shoutto.sdk.internal.http.StmRequestQueue;

/**
 * The main entry point to interact with the Shout to Me platform.  <code>StmService</code> is implemented as
 * an Android service and can therefore be bound or started in accordance with standard Android
 * usage.
 * <p>
 *
 * @see <a href="https://developer.android.com/guide/components/services.html" target="_blank">Android Services</a>
 */
public class StmService extends Service {

    /**
     * The shared preferences key that <code>StmService</code> uses.
     */
    @Deprecated
    public static final String STM_SETTINGS_KEY = "stm_settings";

    /**
     * The Shout to Me REST API url.
     */
    public static final String DEFAULT_SERVER_URL = "https://app.shoutto.me/api/v1";

    /**
     * For general usage in communicating a failure result.
     */
    public static final String FAILURE = "me.shoutto.sdk.FAILURE";

    /**
     * For general usage in communicating a successful result.
     */
    public static final String SUCCESS = "me.shoutto.sdk.SUCCESS";

    /**
     * The key for the Shout to Me channel ID in the Android manifest.
     */
    public static final String CHANNEL_ID = "me.shoutto.sdk.CHANNEL_ID";

    /**
     * The key for the Shout to Me client token in the Android manifest.
     */
    public static final String CLIENT_TOKEN = "me.shoutto.sdk.CLIENT_TOKEN";

    /**
     * AWS Cognito Identity Pool ID for use with the AWS SDK
     */
    public static final String AWS_COGNITO_IDENTITY_POOL_ID = "us-east-1:4ec2b44e-0dde-43e6-a279-6ee1cf241b05";

    private static final String TAG = StmService.class.getSimpleName();
    private final IBinder stmBinder = new StmBinder();
    private String accessToken;
    private User user;
    private StmHttpSender stmHttpSender;
    private StmCallback<Shout> shoutCreationCallback;
    private ExecutorService executorService;
    private ProximitySensorClient proximitySensorClient;
    private List<HandWaveGestureListener> handWaveGestureListenerList = new ArrayList<>();
    private StmPreferenceManager stmPreferenceManager;
    private HandWaveGestureListener overlay;
    private ChannelManager channelManager;
    private UpdateUserLocationController updateUserLocationController;

    public StmService() {
    }

    /**
     * The class to be used when binding via the Android bound service method.
     */
    public class StmBinder extends Binder {
        public StmService getService() {
            return StmService.this;
        }
    }

    /**
     * Adds a topic preference to the user's record. This will result in a user only receiving
     * notifications for the specified topics.
     * @param topic The topic
     * @param callback An optional callback or null
     */
    public void addTopicPreference(String topic, StmCallback<Void> callback) {

        if (topic == null) {
            String validationErrorMessage = "topic cannot be null";
            if (callback != null) {
                StmError error = new StmError(validationErrorMessage, false, StmError.SEVERITY_MINOR);
                callback.onError(error);
                return;
            } else {
                throw new IllegalArgumentException(validationErrorMessage);
            }
        }

        DefaultAsyncEntityRequestProcessor<Void> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new NullResponseAdapter(),
                getUserAuthToken(),
                new TopicUrlProvider(getServerUrl(), user)
        );

        CreateTopicPreference createTopicPreference = new CreateTopicPreference(defaultAsyncEntityRequestProcessor);
        createTopicPreference.create(topic, callback);
    }

    /**
     * The method to create a new shout programmatically, as opposed to through the Shout to Me
     * Recording Overlay
     * @param createShoutRequest A CreateShoutRequest object with all required fields
     * @param callback An optional callback or null
     */
    public void createShout(CreateShoutRequest createShoutRequest, StmCallback<Shout> callback) {
        refreshUserLocation();

        DefaultAsyncEntityRequestProcessor<Shout> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new GsonObjectResponseAdapter<Shout>(Shout.SERIALIZATION_KEY, Shout.getSerializationType()),
                getUserAuthToken(),
                new DefaultUrlProvider(this.getServerUrl())
        );
        UploadShout shoutUploader = new UploadShout(this, new S3Client(this), defaultAsyncEntityRequestProcessor);
        shoutUploader.upload(createShoutRequest, callback);
    }

    /**
     * Gets the client access token that was set in the Android manifest.
     * @return The client access token.
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Returns the Shout to Me channel ID from local storage.
     * @return The Shout to Me channel ID
     */
    public String getChannelId() {
        return stmPreferenceManager.getChannelId();
    }

    /**
     * Calls the service asynchronously to get the available list of channels and returns
     * that list in the callback.
     * @param callback The callback to be executed or null.
     */
    public void getChannels(final StmCallback<List<Channel>> callback) {
        if (channelManager == null) {
            channelManager = new ChannelManager(this);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                channelManager.getChannels(StmService.this, callback);
            }
        }).start();
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Gets the Shout to ME SDK installation ID.  Creates one if it didn't previously exist.
     * @return The Shout to Me SDK installation ID.
     */
    public String getInstallationId() {

        String installationId = stmPreferenceManager.getInstallationId();
        if (installationId == null) {
            stmPreferenceManager.setInstallationId(UUID.randomUUID().toString());
        }

        return stmPreferenceManager.getInstallationId();
    }

    /**
     * Returns the <code>UpdateUserLocationController</code>.
     * @return The UpdateUserLocationController.
     */
    public UpdateUserLocationController getUpdateUserLocationController() {
        return updateUserLocationController;
    }

    /**
     * Gets a single message from the Shout to Me service
     * @param messageId The message ID
     * @param callback An optional callback or null
     */
    public void getMessage(String messageId, StmCallback<Message> callback) {
        DefaultAsyncEntityRequestProcessor<Message> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                null,
                StmRequestQueue.getInstance(),
                new GsonObjectResponseAdapter<Message>(Message.SERIALIZATION_KEY, Message.getSerializationType()),
                getUserAuthToken(),
                new DefaultUrlProvider(getServerUrl())
        );

        GetMessage getMessage = new GetMessage(defaultAsyncEntityRequestProcessor);
        getMessage.get(messageId, callback);
    }

    /**
     * Calls the service to get the list of user's messages and returns the list in the callback.
     * Currently only returns 1000 records.
     * @param callback The callback to execute or null.
     */
    public void getMessages(final StmCallback<List<Message>> callback) {
        DefaultAsyncEntityRequestProcessor<List<Message>> defaultAsyncEntityRequestProcessor
                = new DefaultAsyncEntityRequestProcessor<>(
                null,
                StmRequestQueue.getInstance(),
                new GsonListResponseAdapter<List<Message>, Message>(
                        Message.LIST_SERIALIZATION_KEY,
                        Message.SERIALIZATION_KEY,
                        Message.getSerializationListType(),
                        Message.class
                ),
                getUserAuthToken(),
                new DefaultUrlProvider(getServerUrl())
        );

        GetMessages getMessages = new GetMessages(defaultAsyncEntityRequestProcessor);
        getMessages.get(callback);
    }

    /**
     * Returns the Shout to Me API URL.
     * @return The Shout to Me API URL.
     */
    public String getServerUrl() {
        return stmPreferenceManager.getServerUrl();
    }

    StmCallback<Shout> getShoutCreationCallback() {
        return shoutCreationCallback;
    }

    StmHttpSender getStmHttpSender() {
        return stmHttpSender;
    }

    /**
     * Calls the service to get the unread message count and returns the count to the callback.
     * @param callback The callback to execute or null.
     */
    public void getUnreadMessageCount(final StmCallback<Integer> callback) {
        DefaultAsyncEntityRequestProcessor<Integer> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                null,
                StmRequestQueue.getInstance(),
                new CountResponseAdapter(),
                getUserAuthToken(),
                new MessageCountUrlProvider(getServerUrl(), true)
        );

        GetMessageCount getUnreadMessageCount = new GetMessageCount(defaultAsyncEntityRequestProcessor);
        getUnreadMessageCount.get(callback);
    }

    /**
     * Gets the in memory User object. If not previously instantiated, returns an empty
     * User object.
     * @return The User object.
     */
    public User getUser() {
        return user;
    }

    /**
     * Instantiates a User object asynchronously and returns it in the Callback.
     * @param callback The Callback to be executed or null.
     */
    public void getUser(final StmCallback<User> callback) {

        if (user.getId() == null) {
            StmError stmError = new StmError("User has not been initialized", true, StmError.SEVERITY_MINOR);
            callback.onError(stmError);
            return;
        }

        DefaultAsyncEntityRequestProcessor<User> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new GsonObjectResponseAdapter<User>(User.SERIALIZATION_KEY, User.getSerializationType()),
                getUserAuthToken(),
                new DefaultUrlProvider(getServerUrl())
        );
        GetUser getUser = new GetUser(defaultAsyncEntityRequestProcessor);
        getUser.get(user.getId(), callback);
    }

    private void initializeUserSession() {
        if (!user.isInitialized()) {
            String userId = stmPreferenceManager.getUserId();
            String authToken = stmPreferenceManager.getAuthToken();
            if (userId == null || authToken == null) {
                createOrGetUserAccount();
                authToken = stmPreferenceManager.getAuthToken();
                userId = stmPreferenceManager.getUserId();
            }
            user.setId(userId);
            user.setAuthToken(authToken);
            user.setIsInitialized(true);
            Log.d(TAG, "User has been initialized");
        }
    }

    private void createOrGetUserAccount() {
        try {
            stmHttpSender.getUserWithClientToken(user);
            stmPreferenceManager.setAuthToken(user.getAuthToken());
            stmPreferenceManager.setUserId(user.getId());
        } catch (Exception ex) {
            Log.e(TAG, "Could not create or get user account.", ex);
        }
    }

    /**
     * Returns the user auth token from local storage or gets it from the service if not in local
     * storage.  This token can be used to make calls to the Shout to Me REST API outside of the SDK.
     * @return The user auth token.
     */
    public String getUserAuthToken() {
        synchronized (this) {
            initializeUserSession();
            return stmPreferenceManager.getAuthToken();
        }
    }

    /**
     * Calls registered listeners when a hand wave gesture occurs.
     */
    public void handleHandWaveGesture() {
        if (overlay != null) {
            overlay.onHandWaveGesture();
        } else if (handWaveGestureListenerList.size() > 0) {
            for (HandWaveGestureListener handWaveGestureListener : handWaveGestureListenerList) {
                handWaveGestureListener.onHandWaveGesture();
            }
        }
    }

    /**
     * Sends request to the Shout to Me service to see if the user is subscribed to the specified
     * channel.
     * @param channelId The channel ID to check subscription status.
     * @param callback The callback to be executed or null.
     */
    public void isSubscribedToChannel(String channelId, final StmCallback<Boolean> callback) {

        if (channelId == null) {
            String validationErrorMessage = "channelId cannot be null";
            if (callback != null) {
                StmError error = new StmError(validationErrorMessage, false, StmError.SEVERITY_MINOR);
                callback.onError(error);
                return;
            } else {
                throw new IllegalArgumentException(validationErrorMessage);
            }
        }

        DefaultAsyncEntityRequestProcessor<User> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new GsonObjectResponseAdapter<User>(User.SERIALIZATION_KEY, User.getSerializationType()),
                getUserAuthToken(),
                new DefaultUrlProvider(getServerUrl())
        );

        GetChannelSubscription getChannelSubscription = new GetChannelSubscription(defaultAsyncEntityRequestProcessor);
        getChannelSubscription.get(channelId, user.getId(), callback);
    }

    /**
     * Handles the Android bind lifecycle event. This is where most of the initialization takes place.
     * @param   intent The Intent that was used to bind to the service.
     * @return  The IBinder through which clients can call on to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {

        if (stmPreferenceManager == null) {
            stmPreferenceManager = new StmPreferenceManager(this);
        }

        try {
            ServiceInfo serviceInfo = getPackageManager().getServiceInfo(new ComponentName(this, this.getClass()), PackageManager.GET_META_DATA);
            Bundle bundle = serviceInfo.metaData;
            if (bundle == null) {
                Log.e(TAG, "Metadata with client token is missing. Please make sure to include the client token metadata in AndroidManifest.xml");
            } else {
                accessToken = bundle.getString(CLIENT_TOKEN);
                if (accessToken == null) {
                    Log.w(TAG, "Access token is null. Please make sure to include the access token when binding.");
                }

                String channelId = bundle.getString(CHANNEL_ID);
                Log.d(TAG, "Channel ID from manifest: " + channelId);
                if (channelId != null) {
                    setChannelId(channelId);
                }
            }
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, "Package name not found. Cannot start StmService.", ex);
        }

        // Initialize the RequestQueue
        StmRequestQueue.setInstance(this);

        this.stmHttpSender = new StmHttpSender(this);

        // Create or get user
        this.user = new User(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                initializeUserSession();
            }
        }).start();

        updateUserLocationController = UpdateUserLocationController.getInstance(this);
        updateUserLocationController.startTrackingUserLocation(this);
        updateUserLocationController.updateUserLocation(this);

        executorService = Executors.newFixedThreadPool(10);

        proximitySensorClient = new ProximitySensorClient(this);

        return stmBinder;
    }

    /**
     * Handles the Android <code>startService</code> event.
     * @param   intent The Intent used to the start the service.
     * @param   flags The flags passed into the start service process.
     * @param   startId The unique ID used to identify the start service request.
     * @return  The behavior of how to continue the service if it was killed.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        proximitySensorClient.stopListening();
    }

    /**
     * Clears the user auth token from local storage and gets it from the service.
     * @throws Exception The exception that occurred.
     */
    public void refreshUserAuthToken() throws Exception {
        synchronized (this) {
            stmPreferenceManager.setAuthToken(null);
            stmPreferenceManager.setUserId(null);
            getUserAuthToken();
        }
    }

    public void refreshUserLocation() {
        updateUserLocationController.updateUserLocation(this);
    }

    /**
     * Registers a listener for hand wave gestures and starts listening if the first registration.
     * @param handWaveGestureListener The listener for hand wave gestures.
     */
    public void registerHandGestureListener(HandWaveGestureListener handWaveGestureListener) {
        if (!handWaveGestureListenerList.contains(handWaveGestureListener)) {
            handWaveGestureListenerList.add(handWaveGestureListener);
        }
        if (handWaveGestureListenerList.size() == 1) {
            proximitySensorClient.startListening();
        }
    }

    /**
     * Reinitializes the user by making a call to the service. Overrides previous values stored
     * in Shared Preferences following the call to the service.
     *
     * @param callback The callback to be executed or null
     */
    public void reloadUser(final StmCallback<User> callback) {
        synchronized (this) {
            user.setIsInitialized(false);
            stmPreferenceManager.setAuthToken(null);
            stmPreferenceManager.setUserId(null);
            getUser(callback);
        }
    }

    /**
     * Removes a topic preference from the user's record.  If additional topics are still in the
     * user's record, they will no longer receive shouts with the specified topic. If removing the
     * last topic preference and the user has no more topic preferences, then the user will
     * receive shouts from all topics.
     * @param topic The topic to remove
     * @param callback An optional callback or null
     */
    public void removeTopicPreference(String topic, StmCallback<Void> callback) {
        if (topic == null) {
            String validationErrorMessage = "topic cannot be null";
            if (callback != null) {
                StmError error = new StmError(validationErrorMessage, false, StmError.SEVERITY_MINOR);
                callback.onError(error);
                return;
            } else {
                throw new IllegalArgumentException(validationErrorMessage);
            }
        }

        DefaultAsyncEntityRequestProcessor<Void> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new NullResponseAdapter(),
                getUserAuthToken(),
                new TopicUrlProvider(getServerUrl(), user)
        );

        DeleteTopicPreference deleteTopicPreference = new DeleteTopicPreference(defaultAsyncEntityRequestProcessor);
        deleteTopicPreference.delete(topic, callback);
    }

    /**
     * Sets the Shout to Me channel ID in local storage.
     * @param channelId The Shout to Me channel ID.
     */
    public void setChannelId(String channelId) {
        stmPreferenceManager.setChannelId(channelId);
    }

    void setOverlay(HandWaveGestureListener overlay) {
        this.overlay = overlay;
    }

    /**
     * Sets the Shout to Me API URL.  This method is not normally used for production releases.
     * It can be used to point to testing environments.  Contact Shout to Me for more information.
     * @param serverUrl The Shout to Me API server URL to use for HTTP calls.
     */
    public void setServerUrl(String serverUrl) {
        stmPreferenceManager.setServerUrl(serverUrl);
    }

    /**
     * Sets the callback to be executed following the creation of a shout.
     * @param shoutCreationCallback The callback to be executed following the creation of a shout or null.
     */
    public void setShoutCreationCallback(StmCallback<Shout> shoutCreationCallback) {
        this.shoutCreationCallback = shoutCreationCallback;
    }

    /**
     * Registers the user to receive notifications from the specified channel.
     * @param channelId The channel ID to subscribe to.
     * @param callback The callback to be executed or null.
     */
    public void subscribeToChannel(final String channelId, final StmCallback<Void> callback) {

        if (channelId == null) {
            String validationErrorMessage = "channelId cannot be null";
            if (callback != null) {
                StmError error = new StmError(validationErrorMessage, false, StmError.SEVERITY_MINOR);
                callback.onError(error);
                return;
            } else {
                throw new IllegalArgumentException(validationErrorMessage);
            }
        }

        DefaultAsyncEntityRequestProcessor<Void> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new NullResponseAdapter(),
                getUserAuthToken(),
                new ChannelSubscriptionUrlProvider(getServerUrl(), user)
        );

        CreateChannelSubscription createChannelSubscription =
                new CreateChannelSubscription(defaultAsyncEntityRequestProcessor);
        createChannelSubscription.create(channelId, callback);
    }


    /**
     * Unregisters a previously registered <code>HandWaveGestureListener</code>.
     * @param handWaveGestureListener The <code>HandWaveGestureListener</code> to unregister.
     */
    public void unregisterHandGestureListener(HandWaveGestureListener handWaveGestureListener) {
        handWaveGestureListenerList.remove(handWaveGestureListener);
        if (handWaveGestureListenerList.size() == 0) {
            proximitySensorClient.stopListening();
        }
    }

    /**
     * Unsubscribes the user from receiving notifications from the specified channel.
     * @param channelId The channel ID to unsubscribed from.
     * @param callback The callback to be executed or null.
     */
    public void unsubscribeFromChannel(final String channelId, final StmCallback<Void> callback) {

        if (channelId == null) {
            String validationErrorMessage = "channelId cannot be null";
            if (callback != null) {
                StmError error = new StmError(validationErrorMessage, false, StmError.SEVERITY_MINOR);
                callback.onError(error);
                return;
            } else {
                throw new IllegalArgumentException(validationErrorMessage);
            }
        }

        DefaultAsyncEntityRequestProcessor<Void> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new NullResponseAdapter(),
                getUserAuthToken(),
                new ChannelSubscriptionUrlProvider(getServerUrl(), user)
        );

        DeleteChannelSubscription deleteChannelSubscription = new DeleteChannelSubscription(defaultAsyncEntityRequestProcessor);
        deleteChannelSubscription.delete(channelId, callback);
    }

    /**
     * Updates a user with properties from a {@link UpdateUserRequest} object
     * @param updateUserRequest The object containing the updated properties.
     * @param callback The callback to be executed or null.
     */
    public void updateUser(UpdateUserRequest updateUserRequest, StmCallback<User> callback) {

        if (user.getId() == null) {
            String validationErrorMessage = "Shout to Me user not initialized";
            if (callback != null) {
                StmError error = new StmError(validationErrorMessage, false, StmError.SEVERITY_MAJOR);
                callback.onError(error);
                return;
            } else {
                throw new IllegalArgumentException(validationErrorMessage);
            }
        }

        DefaultAsyncEntityRequestProcessor<User> defaultAsyncEntityRequestProcessor = new DefaultAsyncEntityRequestProcessor<>(
                new GsonRequestAdapter(),
                StmRequestQueue.getInstance(),
                new GsonObjectResponseAdapter<User>(User.SERIALIZATION_KEY, User.getSerializationType()),
                getUserAuthToken(),
                new DefaultUrlProvider(this.getServerUrl())
        );

        UpdateUser updateUser = new UpdateUser(defaultAsyncEntityRequestProcessor, this);
        updateUser.update(updateUserRequest, user.getId(), callback);
    }
}

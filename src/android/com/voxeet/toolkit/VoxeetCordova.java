package com.voxeet.toolkit;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.voxeet.android.media.audio.AudioRoute;
import com.voxeet.toolkit.controllers.VoxeetToolkit;
import com.voxeet.toolkit.implementation.overlays.OverlayState;
import com.voxeet.toolkit.notification.CordovaIncomingBundleChecker;
import com.voxeet.toolkit.notification.CordovaIncomingCallActivity;
import com.voxeet.toolkit.notification.RNBundleChecker;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import eu.codlab.simplepromise.Promise;
import eu.codlab.simplepromise.solve.ErrorPromise;
import eu.codlab.simplepromise.solve.PromiseExec;
import eu.codlab.simplepromise.solve.Solver;
import voxeet.com.sdk.core.FirebaseController;
import voxeet.com.sdk.core.VoxeetSdk;
import voxeet.com.sdk.core.preferences.VoxeetPreferences;
import voxeet.com.sdk.core.services.AudioService;
import voxeet.com.sdk.core.services.authenticate.token.RefreshTokenCallback;
import voxeet.com.sdk.core.services.authenticate.token.TokenCallback;
import voxeet.com.sdk.events.error.ConferenceLeftError;
import voxeet.com.sdk.events.error.PermissionRefusedEvent;
import voxeet.com.sdk.events.success.ConferenceDestroyedPushEvent;
import voxeet.com.sdk.events.success.ConferenceJoinedSuccessEvent;
import voxeet.com.sdk.events.success.ConferenceLeftSuccessEvent;
import voxeet.com.sdk.events.success.ConferenceRefreshedEvent;
import voxeet.com.sdk.events.success.ConferenceUserJoinedEvent;
import voxeet.com.sdk.events.success.SocketConnectEvent;
import voxeet.com.sdk.events.success.SocketStateChangeEvent;
import voxeet.com.sdk.factories.VoxeetIntentFactory;
import voxeet.com.sdk.json.UserInfo;
import voxeet.com.sdk.json.internal.MetadataHolder;
import voxeet.com.sdk.json.internal.ParamsHolder;
import voxeet.com.sdk.models.ConferenceResponse;
import voxeet.com.sdk.utils.Validate;

/**
 * Voxeet implementation for Cordova
 */

public class VoxeetCordova extends CordovaPlugin {

    private static final String ERROR_SDK_NOT_INITIALIZED = "ERROR_SDK_NOT_INITIALIZED";
    private static final String ERROR_SDK_NOT_LOGGED_IN = "ERROR_SDK_NOT_LOGGED_IN";
    private static final String SDK_ALREADY_CONFIGURED_ERROR = "The SDK is already configured";
    private static final String TAG = VoxeetCordova.class.getSimpleName();

    //static to let CordovaIncomingBundleChecker to check the state of it
    //it'll do the trick until the sdk is able to create and maintain a proper
    //conference configuration holder (something expected second half 2019)
    public static boolean startVideoOnJoin = false;

    private final Handler mHandler;
    private UserInfo _current_user;
    private CallbackContext _log_in_callback;
    private ReentrantLock lock = new ReentrantLock();
    private ReentrantLock lockAwaitingToken = new ReentrantLock();
    private List<TokenCallback> mAwaitingTokenCallback;
    private CallbackContext refreshAccessTokenCallbackInstance;
    private MicrophonePermissionWait waitMicrophonePermission;
    private CordovaWebView mWebView;

    public VoxeetCordova() {
        super();
        mHandler = new Handler(Looper.getMainLooper());
        mAwaitingTokenCallback = new ArrayList<>();

        Promise.setHandler(mHandler);
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        mWebView = webView;
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);

        //check for permission result
        //actually not testing it in the permission callback to prevent issue with flow
        MicrophonePermissionWait current = waitMicrophonePermission;
        if (null != current) {
            waitMicrophonePermission = null;
            if (Validate.hasMicrophonePermissions(mWebView.getContext())) {
                join(current.getConfId(), current.getCb());
            } else if (null != current) {
                current.getCb().error("microphone permission rejected");
            }
        }

        setVolumeVoiceCall();
        checkForAwaitingConference(null);
    }

    @Override
    public void onPause(boolean multitasking) {
        setVolumeVoiceCall();

        super.onPause(multitasking);

        if (null != AudioService.getSoundManager()) {
            AudioService.getSoundManager().requestAudioFocus();
        }
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        super.onRequestPermissionResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case PermissionRefusedEvent.RESULT_MICROPHONE: {
                /*MicrophonePermissionWait current = waitMicrophonePermission;
                int index = index(permissions, PermissionRefusedEvent.Permission.MICROPHONE.getPermissions()[0]);

                //remove the current instance
                waitMicrophonePermission = null;
                if (index >= 0
                        && index < grantResults.length
                        && grantResults[index] == PackageManager.PERMISSION_GRANTED
                        && null != VoxeetSdk.getInstance() && null != current) {
                    join(current.getConfId(), current.getCb());
                } else if (null != current) {
                    current.getCb().error("microphone permission rejected");
                }*/
                break;
            }
            case PermissionRefusedEvent.RESULT_CAMERA: {
                if (null != VoxeetSdk.getInstance() && VoxeetSdk.getInstance().getConferenceService().isLive()) {
                    VoxeetSdk.getInstance().getConferenceService().startVideo()
                            .then(new PromiseExec<Boolean, Object>() {
                                @Override
                                public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {

                                }
                            })
                            .error(new ErrorPromise() {
                                @Override
                                public void onError(@NonNull Throwable error) {
                                    error.printStackTrace();
                                }
                            });
                }
                return;
            }
            default:
        }
    }

    private int index(@NonNull String[] permissions, @NonNull String permission) {
        int i = 0;
        for (String perm : permissions) {
            if (permission.equalsIgnoreCase(perm)) return i;
            i++;
        }
        return -1;
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        Log.d("VoxeetCordova", "execute: request " + action);
        if (action != null) {
            switch (action) {
                case "onAccessTokenOk":
                    onAccessTokenOk(args.getString(0), callbackContext);
                    break;
                case "onAccessTokenKo":
                    onAccessTokenKo(args.getString(0), callbackContext);
                    break;
                case "initializeToken":
                    initialize(args.getString(0), callbackContext);
                    break;
                case "initialize":
                    initialize(args.getString(0),
                            args.getString(1),
                            callbackContext);
                    break;
                case "refreshAccessTokenCallback":
                    refreshAccessTokenCallback(callbackContext);
                    break;
                case "connect":
                case "openSession":
                    JSONObject userInfo = null;
                    if (!args.isNull(0)) userInfo = args.getJSONObject(0);
                    UserInfo user = null;

                    if (null != userInfo) {
                        user = new UserInfo(userInfo.getString("name"),
                                userInfo.getString("externalId"),
                                userInfo.getString("avatarUrl"));
                    }

                    openSession(user, callbackContext);
                    break;
                case "disconnect":
                case "closeSession":
                    closeSession(callbackContext);
                    break;
                case "isUserLoggedIn":
                    isUserLoggedIn(callbackContext);
                    break;
                case "checkForAwaitingConference":
                    checkForAwaitingConference(callbackContext);
                    break;
                case "create":
                    try {
                        JSONObject parameters = args.getJSONObject(0);


                        String confAlias = parameters.getString("alias");
                        JSONObject object = null;
                        if (!parameters.isNull("metadata")) {
                            object = parameters.getJSONObject("metadata");
                        }

                        MetadataHolder holder = new MetadataHolder();
                        if (null != object) {
                            Iterator<String> keys = object.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                holder.putValue(key, object.get(key));
                            }
                        }

                        if (!parameters.isNull("params")) {
                            object = parameters.getJSONObject("params");
                        }
                        ParamsHolder pholder = new ParamsHolder();
                        if (null != object) {
                            Iterator<String> keys = object.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                pholder.putValue(key, object.get(key));
                            }
                        }

                        create(confAlias, holder, pholder, callbackContext);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                    break;
                case "join":
                    try {
                        boolean listener = false;
                        String confId = args.getString(0);
                        if (!args.isNull(1)) {
                            try {
                                JSONObject object = args.optJSONObject(1);
                                if (null != object) {
                                    JSONObject params_user = object.optJSONObject("user");
                                    if (null != params_user) {
                                        String userType = params_user.optString("type");
                                        listener = "listener".equals(userType);
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        Log.d(TAG, "execute: listener := " + listener);
                        if (listener) {
                            listen(confId, callbackContext);
                        } else {
                            join(confId, callbackContext);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                    break;
                case "invite":
                    try {
                        String conferenceId = null;
                        if (!args.isNull(0)) conferenceId = args.getString(0);
                        JSONArray array = null;
                        if (!args.isNull(1)) array = args.getJSONArray(1);

                        List<UserInfo> participants = new ArrayList<>();
                        if (null != array) {
                            JSONObject object;
                            int index = 0;
                            while (index < array.length()) {
                                object = array.getJSONObject(index);

                                participants.add(new UserInfo(object.getString("name"),
                                        object.getString("externalId"),
                                        object.getString("avatarUrl")));

                                index++;
                            }
                        }

                        invite(participants, callbackContext);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                case "startConference":
                    try {
                        String confId = args.getString(0);
                        JSONArray array = null;
                        if (!args.isNull(1)) array = args.getJSONArray(1);

                        List<UserInfo> participants = new ArrayList<>();
                        if (null != array) {
                            JSONObject object;
                            int index = 0;
                            while (index < array.length()) {
                                object = array.getJSONObject(index);

                                participants.add(new UserInfo(object.getString("name"),
                                        object.getString("externalId"),
                                        object.getString("avatarUrl")));

                                index++;
                            }
                        }

                        startConference(confId, participants, callbackContext);
                    } catch (Exception e) {
                        e.printStackTrace();
                        callbackContext.error(e.getMessage());
                    }
                    break;
                case "leave":
                case "stopConference":
                    stopConference(callbackContext);
                    break;
                //create -> objet key -> value (value->key->value)
                //join
                //leave
                case "appearMaximized":
                    appearMaximized(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "defaultVideo":
                    defaultVideo(args.getBoolean(0));
                    callbackContext.success();
                case "defaultBuiltInSpeaker":
                    defaultBuiltInSpeaker(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "screenAutoLock":
                    screenAutoLock(args.getBoolean(0));
                    callbackContext.success();
                    break;
                case "sendBroadcastMessage":
                    sendBroadcastMessage(args.getString(0), callbackContext);
                    break;
                default:
                    return false;
            }

            return true; //default return false - so true is ok
        }
        return false;
    }

    private void defaultVideo(boolean startVideo) {
        startVideoOnJoin = startVideo;
        VoxeetPreferences.setDefaultVideoOn(startVideo);
    }


    private void initialize(final String accessToken,
                            final CallbackContext callbackContext) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Application application = (Application) cordova.getActivity().getApplicationContext();

                if (null == VoxeetSdk.getInstance()) {
                    VoxeetSdk.initialize(application,
                            accessToken,
                            new RefreshTokenCallback() {
                                @Override
                                public void onRequired(TokenCallback callback) {
                                    lock(lockAwaitingToken);
                                    if (!mAwaitingTokenCallback.contains(callback)) {
                                        mAwaitingTokenCallback.add(callback);
                                    }
                                    unlock(lockAwaitingToken);
                                    postRefreshAccessToken();
                                }
                            }, null /* no user info */);

                    internalInitialize(callbackContext);
                } else {
                    callbackContext.error(SDK_ALREADY_CONFIGURED_ERROR);
                }
            }
        });
    }

    private void initialize(final String consumerKey,
                            final String consumerSecret,
                            final CallbackContext callbackContext) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Application application = (Application) cordova.getActivity().getApplicationContext();

                if (null == VoxeetSdk.getInstance()) {
                    VoxeetSdk.initialize(application,
                            consumerKey, consumerSecret, null);

                    internalInitialize(callbackContext);
                } else {
                    callbackContext.error(SDK_ALREADY_CONFIGURED_ERROR);
                }
            }
        });
    }

    private void internalInitialize(final CallbackContext callbackContext) {
        VoxeetSdk.getInstance().getConferenceService().setTimeOut(-1); //no timeout by default in the cordova impl

        Application application = (Application) cordova.getActivity().getApplicationContext();

        //also enable the push token upload and log
        FirebaseController.getInstance()
                .log(true)
                .enable(true);
        FirebaseController
                .createNotificationChannel(application);

        //reset the incoming call activity, in case the SDK was no initialized, it would have
        //erased this method call
        VoxeetPreferences.setDefaultActivity(CordovaIncomingCallActivity.class.getCanonicalName());

        //set the 2 optional default configuration from previous saved state
        VoxeetCordova.startVideoOnJoin = VoxeetPreferences.isDefaultVideoOn();
        defaultBuiltInSpeaker(VoxeetPreferences.isDefaultBuiltinSpeakerOn());

        VoxeetToolkit
                .initialize(application, EventBus.getDefault())
                .enableOverlay(true);

        VoxeetToolkit.getInstance().getConferenceToolkit().enable(true);

        VoxeetSdk.getInstance().register(application, VoxeetCordova.this);

        callbackContext.success();
    }

    private void openSession(final UserInfo userInfo,
                             final CallbackContext cb) {

        mHandler.post(new Runnable() {
            @Override
            public void run() {

                if (null == VoxeetSdk.getInstance()) {
                    cb.error(ERROR_SDK_NOT_INITIALIZED);
                    return;
                }

                //if we are trying to connect the same user !
                if (isConnected() && isSameUser(userInfo)) {
                    cb.success();
                    return;
                }

                _log_in_callback = cb;
                if (_current_user == null) {
                    _current_user = userInfo;
                    logSelectedUser();
                } else {
                    _current_user = userInfo;
                    //we have an user
                    VoxeetSdk.getInstance()
                            .logout()
                            .then(new PromiseExec<Boolean, Object>() {
                                @Override
                                public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {
                                    logSelectedUser();
                                }
                            })
                            .error(new ErrorPromise() {
                                @Override
                                public void onError(Throwable error) {
                                    logSelectedUser();
                                }
                            });
                }
            }
        });
    }

    /**
     * Call this method to log the current selected user
     */
    public void logSelectedUser() {
        if (null != VoxeetSdk.getInstance()) {
            VoxeetSdk.getInstance().logUser(_current_user);
        }
    }

    public UserInfo getCurrentUser() {
        return _current_user;
    }

    private void closeSession(final CallbackContext cb) {
        if (null == VoxeetSdk.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                VoxeetSdk.getInstance()
                        .logout()
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean aBoolean, @NonNull Solver<Object> solver) {
                                _current_user = null;
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                _current_user = null;
                                cb.error("Error while logging out with the server");
                            }
                        });
            }
        });
    }

    private void isUserLoggedIn(final CallbackContext cb) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean logged_in = false;
                if (null != VoxeetSdk.getInstance()) {
                    logged_in = VoxeetSdk.getInstance().isSocketOpen();
                }

                cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, logged_in));
            }
        });
    }

    private void checkForAwaitingConference(@Nullable final CallbackContext cb) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                lock();
                if (null == VoxeetSdk.getInstance()) {
                    if (null != cb) cb.error(ERROR_SDK_NOT_INITIALIZED);
                } else {
                    CordovaIncomingBundleChecker checker = CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE;

                    if (null != CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_LAUNCH_ACCEPT) {
                        RNBundleChecker bundle = CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_LAUNCH_ACCEPT;
                        Activity activity = cordova.getActivity();
                        if (null != activity) {
                            Intent intent = VoxeetIntentFactory.buildFrom(activity, VoxeetPreferences.getDefaultActivity(), bundle.createExtraBundle());
                            if (intent != null)
                                activity.startActivity(intent);
                        }
                    } else if (null != CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_ACCEPT) {
                        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_ACCEPT.onAccept();
                    } else if (null != CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_DECLINE) {
                        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_DECLINE.onDecline();
                    } else if (null != checker && checker.isBundleValid()) {
                        if (VoxeetSdk.getInstance().isSocketOpen()) {
                            checker.onAccept();
                            CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
                            if (null != cb) cb.success();
                        } else {
                            if (null != cb) cb.error(ERROR_SDK_NOT_LOGGED_IN);
                        }
                    } else {
                        if (null != cb) cb.success();
                    }

                    cleanBundles();
                }
                unlock();
            }
        });
    }

    private void startConference(final String conferenceAlias,
                                 final List<UserInfo> participants,
                                 final CallbackContext cb) {
        if (null == VoxeetToolkit.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                VoxeetToolkit.getInstance()
                        .getConferenceToolkit()
                        .join(conferenceAlias)
                        .then(new PromiseExec<Boolean, List<ConferenceRefreshedEvent>>() {
                            @Override
                            public void onCall(@Nullable Boolean aBoolean, @NonNull final Solver<List<ConferenceRefreshedEvent>> solver) {
                                if (null != participants && participants.size() > 0) {
                                    solver.resolve(VoxeetToolkit.getInstance()
                                            .getConferenceToolkit()
                                            .invite(participants));
                                } else {
                                    solver.resolve(new ArrayList<ConferenceRefreshedEvent>());
                                }
                                if (startVideoOnJoin) {
                                    startVideo(null);
                                }
                            }
                        })
                        .then(new PromiseExec<List<ConferenceRefreshedEvent>, Object>() {
                            @Override
                            public void onCall(@Nullable List<ConferenceRefreshedEvent> conferenceRefreshedEvents, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                throwable.printStackTrace();
                                cb.error("Error while initializing the conference");
                            }
                        });
            }
        });
    }

    private void invite(final List<UserInfo> participants,
                        final CallbackContext cb) {
        if (null == VoxeetToolkit.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                VoxeetToolkit.getInstance()
                        .getConferenceToolkit()
                        .invite(participants)
                        .then(new PromiseExec<List<ConferenceRefreshedEvent>, Object>() {
                            @Override
                            public void onCall(@Nullable List<ConferenceRefreshedEvent> conferenceRefreshedEvents, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while initializing the conference");
                            }
                        });
            }
        });
    }

    private void create(String conferenceAlias,
                        MetadataHolder holder,
                        ParamsHolder pholder, final CallbackContext cb) {
        if (null == VoxeetSdk.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        VoxeetSdk.getInstance().getConferenceService().create(conferenceAlias, holder, pholder)
                .then(new PromiseExec<ConferenceResponse, Object>() {
                    @Override
                    public void onCall(@Nullable ConferenceResponse result, @NonNull Solver<Object> solver) {
                        //TODO add isNew
                        JSONObject object = new JSONObject();
                        try {
                            object.put("conferenceId", result.getConfId());
                            object.put("conferenceAlias", result.getConfAlias());
                            object.put("isNew", result.isNew());
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                        cb.success(object);
                    }
                })
                .error(new ErrorPromise() {
                    @Override
                    public void onError(@NonNull Throwable error) {
                        cb.error("Error while creating the conference " + conferenceAlias);
                    }
                });
    }

    private void join(@NonNull String conferenceId, @NonNull final CallbackContext cb) {
        Context context = mWebView.getContext();
        Log.d(TAG, "join: joining conference");

        if (null == VoxeetSdk.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        if (null != context && Validate.hasMicrophonePermissions(mWebView.getContext())) {
            VoxeetSdk.getInstance().getConferenceService().join(conferenceId)
                    .then(new PromiseExec<Boolean, Object>() {
                        @Override
                        public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {

                            cleanBundles();

                            if (startVideoOnJoin) {
                                startVideo(null);
                            }

                            cb.success();
                        }
                    })
                    .error(new ErrorPromise() {
                        @Override
                        public void onError(@NonNull Throwable error) {
                            cb.error("Error while joining the conference " + conferenceId);
                        }
                    });
        } else {
            waitMicrophonePermission = new MicrophonePermissionWait(conferenceId, cb);
            requestMicrophonePermission();
        }
    }

    private void listen(@NonNull String conferenceId, @NonNull final CallbackContext cb) {
        Context context = mWebView.getContext();
        Log.d(TAG, "listen: joining conference");

        if (null == VoxeetSdk.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        VoxeetSdk.getInstance().getConferenceService().listenConference(conferenceId)
                .then(new PromiseExec<Boolean, Object>() {
                    @Override
                    public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {
                        cleanBundles();

                        cb.success();
                    }
                })
                .error(new ErrorPromise() {
                    @Override
                    public void onError(@NonNull Throwable error) {
                        cb.error("Error while joining the conference " + conferenceId);
                    }
                });
    }

    private void startVideo(final CallbackContext cb) {
        if (null == VoxeetSdk.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        VoxeetSdk.getInstance().getConferenceService().startVideo()
                .then(new PromiseExec<Boolean, Object>() {
                    @Override
                    public void onCall(@Nullable Boolean result, @NonNull Solver<Object> solver) {
                        if (null != cb) cb.success();
                    }
                })
                .error(new ErrorPromise() {
                    @Override
                    public void onError(@NonNull Throwable error) {
                        if (null != cb) {
                            cb.error("Error while starting video");
                        }
                    }
                });
    }

    private void stopConference(final CallbackContext cb) {
        if (null == VoxeetSdk.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                VoxeetSdk.getInstance()
                        .getConferenceService()
                        .leave()
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean bool, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while leaving");
                            }
                        });
            }
        });
    }

    private void sendBroadcastMessage(final String message, final CallbackContext cb) {
        if (null == VoxeetSdk.getInstance()) {
            cb.error(ERROR_SDK_NOT_INITIALIZED);
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                VoxeetSdk.getInstance()
                        .getConferenceService()
                        .sendBroadcastMessage(message)
                        .then(new PromiseExec<Boolean, Object>() {
                            @Override
                            public void onCall(@Nullable Boolean aBoolean, @NonNull Solver<Object> solver) {
                                cb.success();
                            }
                        })
                        .error(new ErrorPromise() {
                            @Override
                            public void onError(@NonNull Throwable throwable) {
                                cb.error("Error while sending the message to the server");
                            }
                        });
            }
        });
    }

    private void appearMaximized(final Boolean enabled) {
        if (null == VoxeetToolkit.getInstance()) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                VoxeetToolkit.getInstance()
                        .getConferenceToolkit()
                        .setDefaultOverlayState(enabled ? OverlayState.EXPANDED
                                : OverlayState.MINIMIZED);
            }
        });
    }

    private void defaultBuiltInSpeaker(final boolean enabled) {
        if (null == VoxeetSdk.getInstance()) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                AudioRoute route = AudioRoute.ROUTE_PHONE;
                if (enabled) route = AudioRoute.ROUTE_SPEAKER;

                VoxeetPreferences.setDefaultBuiltInSpeakerOn(enabled);
                if (null != VoxeetSdk.getInstance()) {
                    VoxeetSdk.getInstance().getAudioService().setAudioRoute(route);
                } else {
                    Log.e("VoxeetCordova", "defaultBuiltInSpeaker: initialize the sdk first");
                }
            }
        });
    }

    private void screenAutoLock(Boolean enabled) {
        //TODO not available in the current sdk
    }

    private void postRefreshAccessToken() {
        Log.d("VoxeetCordova", "postRefreshAccessToken: sending call to javascript to refresh token");
        if (null != refreshAccessTokenCallbackInstance) {
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK);
            pluginResult.setKeepCallback(true);
            refreshAccessTokenCallbackInstance.sendPluginResult(pluginResult);
        }
    }

    private void onAccessTokenOk(final String accessToken,
                                 final CallbackContext callbackContext) {
        lock(lockAwaitingToken);
        for (TokenCallback callback : mAwaitingTokenCallback) {
            try {
                callback.ok(accessToken);
            } catch (Exception ee) {
                ee.printStackTrace();
            }
        }
        unlock(lockAwaitingToken);
        callbackContext.success();
    }

    private void onAccessTokenKo(final String reason,
                                 final CallbackContext callbackContext) {
        try {
            throw new Exception("refreshToken failed with reason := " + reason);
        } catch (Exception e) {
            lock(lockAwaitingToken);
            for (TokenCallback callback : mAwaitingTokenCallback) {
                try {
                    callback.error(e);
                } catch (Exception ee) {
                    ee.printStackTrace();
                }
            }
            unlock(lockAwaitingToken);
        }
        callbackContext.success();
    }

    private void refreshAccessTokenCallback(final CallbackContext callbackContext) {
        refreshAccessTokenCallbackInstance = callbackContext;
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(final SocketConnectEvent event) {
        if (null != _log_in_callback) {
            _log_in_callback.success();
            _log_in_callback = null;

            checkForIncomingConference();
        }
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(SocketStateChangeEvent event) {
        switch (event.message()) {
            case "CLOSING":
            case "CLOSED":
                if (null != _log_in_callback) {
                    _log_in_callback.error("Error while logging in");
                    _log_in_callback = null;

                    cancelIncomingConference();
                }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(PermissionRefusedEvent event) {
        if (null != event.getPermission()) {
            switch (event.getPermission()) {
                case CAMERA:
                    //Validate.requestMandatoryPermissions(VoxeetToolkit.getInstance().getCurrentActivity(),
                    //        new String[]{Manifest.permission.CAMERA},
                    //        PermissionRefusedEvent.RESULT_CAMERA);

                    PermissionHelper.requestPermissions(
                            this,
                            PermissionRefusedEvent.RESULT_CAMERA,
                            PermissionRefusedEvent.Permission.CAMERA.getPermissions()
                    );
                    break;
            }
        }
    }

    private void requestMicrophonePermission() {
        PermissionHelper.requestPermissions(
                this,
                PermissionRefusedEvent.RESULT_MICROPHONE,
                PermissionRefusedEvent.Permission.MICROPHONE.getPermissions()
        );
    }

    private boolean isConnected() {
        return VoxeetSdk.getInstance() != null
                && VoxeetSdk.getInstance().isSocketOpen();
    }

    private boolean isSameUser(@NonNull UserInfo userInfo) {
        return userInfo.getExternalId().equals(getCurrentUser());
    }

    private boolean checkForIncomingConference() {
        CordovaIncomingBundleChecker checker = CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE;
        if (null != checker && checker.isBundleValid() && null != VoxeetSdk.getInstance()) {
            if (VoxeetSdk.getInstance().isSocketOpen()) {
                checker.onAccept();
                CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
                return true;
            } else {
                return false;
            }
        }
        return true;
    }

    private void cancelIncomingConference() {
        CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
    }

    private void lock(ReentrantLock lock) {
        try {
            lock.lock();
        } catch (Exception e) {

        }
    }

    private void unlock(ReentrantLock lock) {
        try {
            if (lock.isLocked())
                lock.unlock();
        } catch (Exception e) {


        }
    }

    private void lock() {
        lock(lock);
    }

    private void unlock() {
        unlock(lock);
    }

    private class MicrophonePermissionWait {
        private String confId;
        private CallbackContext cb;

        private MicrophonePermissionWait() {

        }

        public MicrophonePermissionWait(@NonNull String confId, @NonNull CallbackContext cb) {
            this();
            this.confId = confId;
            this.cb = cb;
        }

        public String getConfId() {
            return confId;
        }

        public CallbackContext getCb() {
            return cb;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ConferenceUserJoinedEvent event) {
        setVolumeVoiceCall();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ConferenceJoinedSuccessEvent event) {
        setVolumeVoiceCall();

        Log.d(TAG, "onEvent: ConferenceJoinedSuccessEvent :: removing the various bundles");
        cleanBundles();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ConferenceLeftSuccessEvent event) {
        setVolumeStream();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ConferenceLeftError event) {
        setVolumeStream();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(ConferenceDestroyedPushEvent event) {
        setVolumeStream();
    }

    private void setVolumeVoiceCall() {
        cordova.getActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        if (null != AudioService.getSoundManager()) {
            AudioService.getSoundManager().requestAudioFocus();
        }
    }

    private void setVolumeStream() {
        cordova.getActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (null != AudioService.getSoundManager()) {
            AudioService.getSoundManager().abandonAudioFocusRequest();
        }
    }

    private void cleanBundles() {
        //and finally clear them
        CordovaIncomingCallActivity.CORDOVA_ROOT_BUNDLE = null;
        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_ACCEPT = null;
        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_DECLINE = null;
        CordovaIncomingCallActivity.CORDOVA_AWAITING_BUNDLE_TO_BE_MANAGE_FOR_LAUNCH_ACCEPT = null;
    }
}

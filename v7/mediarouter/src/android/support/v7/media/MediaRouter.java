/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.v7.media;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.hardware.display.DisplayManagerCompat;
import android.support.v7.media.MediaRouteProvider.ProviderMetadata;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MediaRouter allows applications to control the routing of media channels
 * and streams from the current device to external speakers and destination devices.
 * <p>
 * A MediaRouter instance is retrieved through {@link #getInstance}.  Applications
 * can query the media router about the currently selected route and its capabilities
 * to determine how to send content to the route's destination.  Applications can
 * also {@link RouteInfo#sendControlRequest send control requests} to the route
 * to ask the route's destination to perform certain remote control functions
 * such as playing media.
 * </p><p>
 * See also {@link MediaRouteProvider} for information on how an application
 * can publish new media routes to the media router.
 * </p><p>
 * The media router API is not thread-safe; all interactions with it must be
 * done from the main thread of the process.
 * </p>
 */
public final class MediaRouter {
    private static final String TAG = "MediaRouter";
    private static final boolean DEBUG = false;

    // Maintains global media router state for the process.
    // This field is initialized in MediaRouter.getInstance() before any
    // MediaRouter objects are instantiated so it is guaranteed to be
    // valid whenever any instance method is invoked.
    static GlobalMediaRouter sGlobal;

    // Context-bound state of the media router.
    final Context mContext;
    final CopyOnWriteArrayList<CallbackRecord> mCallbackRecords =
            new CopyOnWriteArrayList<CallbackRecord>();

    /**
     * Flag for {@link #addCallback}: Actively scan for routes while this callback
     * is registered.
     * <p>
     * When this flag is specified, the media router will actively scan for new
     * routes.  Certain routes, such as wifi display routes, may not be discoverable
     * except when actively scanning.  This flag is typically used when the route picker
     * dialog has been opened by the user to ensure that the route information is
     * up to date.
     * </p><p>
     * Active scanning may consume a significant amount of power and may have intrusive
     * effects on wireless connectivity.  Therefore it is important that active scanning
     * only be requested when it is actually needed to satisfy a user request to
     * discover and select a new route.
     * </p>
     */
    public static final int CALLBACK_FLAG_ACTIVE_SCAN = 1 << 0;

    /**
     * Flag for {@link #addCallback}: Do not filter route events.
     * <p>
     * When this flag is specified, the callback will be invoked for events that affect any
     * route event if they do not match the callback's associated media route selector.
     * </p>
     */
    public static final int CALLBACK_FLAG_UNFILTERED_EVENTS = 1 << 1;

    /**
     * Flag for {@link #isRouteAvailable}: Ignore the default route.
     * <p>
     * This flag is used to determine whether a matching non-default route is available.
     * This constraint may be used to decide whether to offer the route chooser dialog
     * to the user.  There is no point offering the chooser if there are no
     * non-default choices.
     * </p>
     */
    public static final int AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE = 1 << 0;

    /**
     * Flag for {@link #isRouteAvailable}: Consider whether matching routes
     * might be discovered if an active scan were performed.
     * <p>
     * If no existing routes match the route selector, then this flag is used to
     * determine whether to consider whether any route providers that require active
     * scans might discover matching routes if an active scan were actually performed.
     * </p><p>
     * This flag may be used to decide whether to offer the route chooser dialog to the user.
     * When the dialog is opened, an active scan will be performed which may cause
     * additional routes to be discovered by any providers that require active scans.
     * </p>
     */
    public static final int AVAILABILITY_FLAG_CONSIDER_ACTIVE_SCAN = 1 << 1;

    MediaRouter(Context context) {
        mContext = context;
    }

    /**
     * Gets an instance of the media router service from the context.
     */
    public static MediaRouter getInstance(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        checkCallingThread();

        if (sGlobal == null) {
            sGlobal = new GlobalMediaRouter(context.getApplicationContext());
            sGlobal.start();
        }
        return sGlobal.getRouter(context);
    }

    /**
     * Gets information about the {@link MediaRouter.RouteInfo routes} currently known to
     * this media router.
     */
    public List<RouteInfo> getRoutes() {
        checkCallingThread();
        return sGlobal.getRoutes();
    }

    /**
     * Gets information about the {@link MediaRouter.ProviderInfo route providers}
     * currently known to this media router.
     */
    public List<ProviderInfo> getProviders() {
        checkCallingThread();
        return sGlobal.getProviders();
    }

    /**
     * Gets the default route for playing media content on the system.
     * <p>
     * The system always provides a default route.
     * </p>
     *
     * @return The default route, which is guaranteed to never be null.
     */
    public RouteInfo getDefaultRoute() {
        checkCallingThread();
        return sGlobal.getDefaultRoute();
    }

    /**
     * Gets the currently selected route.
     * <p>
     * The application should examine the route's
     * {@link RouteInfo#getControlFilters media control intent filters} to assess the
     * capabilities of the route before attempting to use it.
     * </p>
     *
     * <h3>Example</h3>
     * <pre>
     * public boolean playMovie() {
     *     MediaRouter mediaRouter = MediaRouter.getInstance(context);
     *     MediaRouter.RouteInfo route = mediaRouter.getSelectedRoute();
     *
     *     // First try using the remote playback interface, if supported.
     *     if (route.supportsControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)) {
     *         // The route supports remote playback.
     *         // Try to send it the Uri of the movie to play.
     *         Intent intent = new Intent(MediaControlIntent.ACTION_PLAY);
     *         intent.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
     *         intent.setDataAndType("http://example.com/videos/movie.mp4", "video/mp4");
     *         if (route.supportsControlRequest(intent)) {
     *             route.sendControlRequest(intent, null);
     *             return true; // sent the request to play the movie
     *         }
     *     }
     *
     *     // If remote playback was not possible, then play locally.
     *     if (route.supportsControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)) {
     *         // The route supports live video streaming.
     *         // Prepare to play content locally in a window or in a presentation.
     *         return playMovieInWindow();
     *     }
     *
     *     // Neither interface is supported, so we can't play the movie to this route.
     *     return false;
     * }
     * </pre>
     *
     * @return The selected route, which is guaranteed to never be null.
     *
     * @see RouteInfo#getControlFilters
     * @see RouteInfo#supportsControlCategory
     * @see RouteInfo#supportsControlRequest
     */
    public RouteInfo getSelectedRoute() {
        checkCallingThread();
        return sGlobal.getSelectedRoute();
    }

    /**
     * Returns the selected route if it matches the specified selector, otherwise
     * selects the default route and returns it.
     *
     * @param selector The selector to match.
     * @return The previously selected route if it matched the selector, otherwise the
     * newly selected default route which is guaranteed to never be null.
     *
     * @see MediaRouteSelector
     * @see RouteInfo#matchesSelector
     * @see RouteInfo#isDefault
     */
    public RouteInfo updateSelectedRoute(MediaRouteSelector selector) {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }
        checkCallingThread();

        if (DEBUG) {
            Log.d(TAG, "updateSelectedRoute: " + selector);
        }
        RouteInfo route = sGlobal.getSelectedRoute();
        if (!route.isDefault() && !route.matchesSelector(selector)) {
            route = sGlobal.getDefaultRoute();
            sGlobal.selectRoute(route);
        }
        return route;
    }

    /**
     * Selects the specified route.
     *
     * @param route The route to select.
     */
    public void selectRoute(RouteInfo route) {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }
        checkCallingThread();

        if (DEBUG) {
            Log.d(TAG, "selectRoute: " + route);
        }
        sGlobal.selectRoute(route);
    }

    /**
     * Returns true if there is a route that matches the specified selector
     * or, depending on the specified availability flags, if it is possible to discover one.
     * <p>
     * This method first considers whether there are any available
     * routes that match the selector regardless of whether they are enabled or
     * disabled.  If not and the {@link #AVAILABILITY_FLAG_CONSIDER_ACTIVE_SCAN} flag
     * was specifies, then it considers whether any of the route providers
     * could discover a matching route if an active scan were performed.
     * </p>
     *
     * @param selector The selector to match.
     * @param flags Flags to control the determination of whether a route may be available.
     * May be zero or a combination of
     * {@link #AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE} and
     * {@link #AVAILABILITY_FLAG_CONSIDER_ACTIVE_SCAN}.
     * @return True if a matching route may be available.
     */
    public boolean isRouteAvailable(MediaRouteSelector selector, int flags) {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }
        checkCallingThread();

        return sGlobal.isRouteAvailable(selector, flags);
    }

    /**
     * Registers a callback to discover routes that match the selector and to receive
     * events when they change.
     * <p>
     * This is a convenience method that has the same effect as calling
     * {@link #addCallback(MediaRouteSelector, Callback, int)} without flags.
     * </p>
     *
     * @param selector A route selector that indicates the kinds of routes that the
     * callback would like to discover.
     * @param callback The callback to add.
     * @see #removeCallback
     */
    public void addCallback(MediaRouteSelector selector, Callback callback) {
        addCallback(selector, callback, 0);
    }

    /**
     * Registers a callback to discover routes that match the selector and to receive
     * events when they change.
     * <p>
     * The selector describes the kinds of routes that the application wants to
     * discover.  For example, if the application wants to use
     * live audio routes then it should include the
     * {@link MediaControlIntent#CATEGORY_LIVE_AUDIO live audio media control intent category}
     * in its selector when it adds a callback to the media router.
     * The selector may include any number of categories.
     * </p><p>
     * If the callback has already been registered, then the selector is added to
     * the set of selectors being monitored by the callback.
     * </p><p>
     * By default, the callback will only be invoked for events that affect routes
     * that match the specified selector.  Event filtering may be disabled by specifying
     * the {@link #CALLBACK_FLAG_UNFILTERED_EVENTS} flag when the callback is registered.
     * </p>
     *
     * <h3>Example</h3>
     * <pre>
     * public class MyActivity extends Activity {
     *     private MediaRouter mRouter;
     *     private MediaRouter.Callback mCallback;
     *     private MediaRouteSelector mSelector;
     *
     *     protected void onCreate(Bundle savedInstanceState) {
     *         super.onCreate(savedInstanceState);
     *
     *         mRouter = Mediarouter.getInstance(this);
     *         mCallback = new MyCallback();
     *         mSelector = new MediaRouteSelector.Builder()
     *                 .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
     *                 .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
     *                 .build();
     *     }
     *
     *     // Add the callback on resume to tell the media router what kinds of routes
     *     // the application is interested in so that it can try to discover suitable ones.
     *     public void onResume() {
     *         super.onResume();
     *
     *         mediaRouter.addCallback(mSelector, mCallback);
     *
     *         MediaRouter.RouteInfo route = mediaRouter.updateSelectedRoute(mSelector);
     *         // do something with the route...
     *     }
     *
     *     // Remove the selector on pause to tell the media router that it no longer
     *     // needs to invest effort trying to discover routes of these kinds for now.
     *     public void onPause() {
     *         super.onPause();
     *
     *         mediaRouter.removeCallback(mCallback);
     *     }
     *
     *     private final class MyCallback extends MediaRouter.Callback {
     *         // Implement callback methods as needed.
     *     }
     * }
     * </pre>
     *
     * @param selector A route selector that indicates the kinds of routes that the
     * callback would like to discover.
     * @param callback The callback to add.
     * @param flags Flags to control the behavior of the callback.
     * May be zero or a combination of {@link #CALLBACK_FLAG_ACTIVE_SCAN} and
     * {@link #CALLBACK_FLAG_UNFILTERED_EVENTS}.
     * @see #removeCallback
     */
    public void addCallback(MediaRouteSelector selector, Callback callback, int flags) {
        if (selector == null) {
            throw new IllegalArgumentException("selector must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        checkCallingThread();

        if (DEBUG) {
            Log.d(TAG, "addCallback: selector=" + selector
                    + ", callback=" + callback + ", flags=" + Integer.toHexString(flags));
        }

        CallbackRecord record;
        int index = findCallbackRecord(callback);
        if (index < 0) {
            record = new CallbackRecord(callback);
            mCallbackRecords.add(record);
        } else {
            record = mCallbackRecords.get(index);
        }
        boolean updateNeeded = false;
        if ((flags & ~record.mFlags) != 0) {
            record.mFlags |= flags;
            updateNeeded = true;
        }
        if (!record.mSelector.contains(selector)) {
            record.mSelector = new MediaRouteSelector.Builder(record.mSelector)
                    .addSelector(selector)
                    .build();
            updateNeeded = true;
        }
        if (updateNeeded) {
            sGlobal.updateDiscoveryRequest();
        }
    }

    /**
     * Removes the specified callback.  It will no longer receive events about
     * changes to media routes.
     *
     * @param callback The callback to remove.
     * @see #addCallback
     */
    public void removeCallback(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        checkCallingThread();

        if (DEBUG) {
            Log.d(TAG, "removeCallback: callback=" + callback);
        }

        int index = findCallbackRecord(callback);
        if (index >= 0) {
            mCallbackRecords.remove(index);
            sGlobal.updateDiscoveryRequest();
        }
    }

    private int findCallbackRecord(Callback callback) {
        final int count = mCallbackRecords.size();
        for (int i = 0; i < count; i++) {
            if (mCallbackRecords.get(i).mCallback == callback) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Registers a media route provider globally for this application process.
     *
     * @param providerInstance The media route provider instance to add.
     *
     * @see MediaRouteProvider
     * @see #removeCallback
     */
    public void addProvider(MediaRouteProvider providerInstance) {
        if (providerInstance == null) {
            throw new IllegalArgumentException("providerInstance must not be null");
        }
        checkCallingThread();

        if (DEBUG) {
            Log.d(TAG, "addProvider: " + providerInstance);
        }
        sGlobal.addProvider(providerInstance);
    }

    /**
     * Unregisters a media route provider globally for this application process.
     *
     * @param providerInstance The media route provider instance to remove.
     *
     * @see MediaRouteProvider
     * @see #addCallback
     */
    public void removeProvider(MediaRouteProvider providerInstance) {
        if (providerInstance == null) {
            throw new IllegalArgumentException("providerInstance must not be null");
        }
        checkCallingThread();

        if (DEBUG) {
            Log.d(TAG, "removeProvider: " + providerInstance);
        }
        sGlobal.removeProvider(providerInstance);
    }

    /**
     * Ensures that calls into the media router are on the correct thread.
     * It pays to be a little paranoid when global state invariants are at risk.
     */
    static void checkCallingThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("The media router service must only be "
                    + "accessed on the application's main thread.");
        }
    }

    static <T> boolean equal(T a, T b) {
        return a == b || (a != null && b != null && a.equals(b));
    }

    /**
     * Provides information about a media route.
     * <p>
     * Each media route has a list of {@link MediaControlIntent media control}
     * {@link #getControlFilters intent filters} that describe the capabilities of the
     * route and the manner in which it is used and controlled.
     * </p>
     */
    public static final class RouteInfo {
        private final ProviderInfo mProvider;
        private final String mDescriptorId;
        private String mName;
        private String mDescription;
        private boolean mEnabled;
        private boolean mConnecting;
        private final ArrayList<IntentFilter> mControlFilters = new ArrayList<IntentFilter>();
        private int mPlaybackType;
        private int mPlaybackStream;
        private int mVolumeHandling;
        private int mVolume;
        private int mVolumeMax;
        private Display mPresentationDisplay;
        private int mPresentationDisplayId = -1;
        private Bundle mExtras;
        private MediaRouteDescriptor mDescriptor;

        /**
         * The default playback type, "local", indicating the presentation of the media
         * is happening on the same device (e.g. a phone, a tablet) as where it is
         * controlled from.
         *
         * @see #getPlaybackType
         */
        public static final int PLAYBACK_TYPE_LOCAL = 0;

        /**
         * A playback type indicating the presentation of the media is happening on
         * a different device (i.e. the remote device) than where it is controlled from.
         *
         * @see #getPlaybackType
         */
        public static final int PLAYBACK_TYPE_REMOTE = 1;

        /**
         * Playback information indicating the playback volume is fixed, i.e. it cannot be
         * controlled from this object. An example of fixed playback volume is a remote player,
         * playing over HDMI where the user prefers to control the volume on the HDMI sink, rather
         * than attenuate at the source.
         *
         * @see #getVolumeHandling
         */
        public static final int PLAYBACK_VOLUME_FIXED = 0;

        /**
         * Playback information indicating the playback volume is variable and can be controlled
         * from this object.
         *
         * @see #getVolumeHandling
         */
        public static final int PLAYBACK_VOLUME_VARIABLE = 1;

        static final int CHANGE_GENERAL = 1 << 0;
        static final int CHANGE_VOLUME = 1 << 1;
        static final int CHANGE_PRESENTATION_DISPLAY = 1 << 2;

        RouteInfo(ProviderInfo provider, String descriptorId) {
            mProvider = provider;
            mDescriptorId = descriptorId;
        }

        /**
         * Gets information about the provider of this media route.
         */
        public ProviderInfo getProvider() {
            return mProvider;
        }

        /**
         * Gets the user-visible name of the route.
         * <p>
         * The route name identifies the destination represented by the route.
         * It may be a user-supplied name, an alias, or device serial number.
         * </p>
         *
         * @return The user-visible name of a media route.  This is the string presented
         * to users who may select this as the active route.
         */
        public String getName() {
            return mName;
        }

        /**
         * Gets the user-visible description of the route.
         * <p>
         * The route description describes the kind of destination represented by the route.
         * It may be a user-supplied string, a model number or brand of device.
         * </p>
         *
         * @return The description of the route, or null if none.
         */
        public String getDescription() {
            return mDescription;
        }

        /**
         * Returns true if this route is enabled and may be selected.
         *
         * @return True if this route is enabled.
         */
        public boolean isEnabled() {
            return mEnabled;
        }

        /**
         * Returns true if the route is in the process of connecting and is not
         * yet ready for use.
         *
         * @return True if this route is in the process of connecting.
         */
        public boolean isConnecting() {
            return mConnecting;
        }

        /**
         * Returns true if this route is currently selected.
         *
         * @return True if this route is currently selected.
         *
         * @see MediaRouter#getSelectedRoute
         */
        public boolean isSelected() {
            checkCallingThread();
            return sGlobal.getSelectedRoute() == this;
        }

        /**
         * Returns true if this route is the default route.
         *
         * @return True if this route is the default route.
         *
         * @see MediaRouter#getDefaultRoute
         */
        public boolean isDefault() {
            checkCallingThread();
            return sGlobal.getDefaultRoute() == this;
        }

        /**
         * Gets a list of {@link MediaControlIntent media control intent} filters that
         * describe the capabilities of this route and the media control actions that
         * it supports.
         *
         * @return A list of intent filters that specifies the media control intents that
         * this route supports.
         *
         * @see MediaControlIntent
         * @see #supportsControlCategory
         * @see #supportsControlRequest
         */
        public List<IntentFilter> getControlFilters() {
            return mControlFilters;
        }

        /**
         * Returns true if the route supports at least one of the capabilities
         * described by a media route selector.
         *
         * @param selector The selector that specifies the capabilities to check.
         * @return True if the route supports at least one of the capabilities
         * described in the media route selector.
         */
        public boolean matchesSelector(MediaRouteSelector selector) {
            if (selector == null) {
                throw new IllegalArgumentException("selector must not be null");
            }
            checkCallingThread();
            return selector.matchesControlFilters(mControlFilters);
        }

        /**
         * Returns true if the route supports the specified
         * {@link MediaControlIntent media control} category.
         * <p>
         * Media control categories describe the capabilities of this route
         * such as whether it supports live audio streaming or remote playback.
         * </p>
         *
         * @param category A {@link MediaControlIntent media control} category
         * such as {@link MediaControlIntent#CATEGORY_LIVE_AUDIO},
         * {@link MediaControlIntent#CATEGORY_LIVE_VIDEO},
         * {@link MediaControlIntent#CATEGORY_REMOTE_PLAYBACK}, or a provider-defined
         * media control category.
         * @return True if the route supports the specified intent category.
         *
         * @see MediaControlIntent
         * @see #getControlFilters
         */
        public boolean supportsControlCategory(String category) {
            if (category == null) {
                throw new IllegalArgumentException("category must not be null");
            }
            checkCallingThread();

            int count = mControlFilters.size();
            for (int i = 0; i < count; i++) {
                if (mControlFilters.get(i).hasCategory(category)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns true if the route supports the specified
         * {@link MediaControlIntent media control} request.
         * <p>
         * Media control requests are used to request the route to perform
         * actions such as starting remote playback of a media item.
         * </p>
         *
         * @param intent A {@link MediaControlIntent media control intent}.
         * @return True if the route can handle the specified intent.
         *
         * @see MediaControlIntent
         * @see #getControlFilters
         */
        public boolean supportsControlRequest(Intent intent) {
            if (intent == null) {
                throw new IllegalArgumentException("intent must not be null");
            }
            checkCallingThread();

            ContentResolver contentResolver = sGlobal.getContentResolver();
            int count = mControlFilters.size();
            for (int i = 0; i < count; i++) {
                if (mControlFilters.get(i).match(contentResolver, intent, true, TAG) >= 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Sends a {@link MediaControlIntent media control} request to be performed
         * asynchronously by the route's destination.
         * <p>
         * Media control requests are used to request the route to perform
         * actions such as starting remote playback of a media item.
         * </p><p>
         * This function may only be called on a selected route.  Control requests
         * sent to unselected routes will fail.
         * </p>
         *
         * @param intent A {@link MediaControlIntent media control intent}.
         * @param callback A {@link ControlRequestCallback} to invoke with the result
         * of the request, or null if no result is required.
         *
         * @see MediaControlIntent
         */
        public void sendControlRequest(Intent intent, ControlRequestCallback callback) {
            if (intent == null) {
                throw new IllegalArgumentException("intent must not be null");
            }
            checkCallingThread();

            sGlobal.sendControlRequest(this, intent, callback);
        }

        /**
         * Gets the type of playback associated with this route.
         *
         * @return The type of playback associated with this route: {@link #PLAYBACK_TYPE_LOCAL}
         * or {@link #PLAYBACK_TYPE_REMOTE}.
         */
        public int getPlaybackType() {
            return mPlaybackType;
        }

        /**
         * Gets the audio stream over which the playback associated with this route is performed.
         *
         * @return The stream over which the playback associated with this route is performed.
         */
        public int getPlaybackStream() {
            return mPlaybackStream;
        }

        /**
         * Gets information about how volume is handled on the route.
         *
         * @return How volume is handled on the route: {@link #PLAYBACK_VOLUME_FIXED}
         * or {@link #PLAYBACK_VOLUME_VARIABLE}.
         */
        public int getVolumeHandling() {
            return mVolumeHandling;
        }

        /**
         * Gets the current volume for this route. Depending on the route, this may only
         * be valid if the route is currently selected.
         *
         * @return The volume at which the playback associated with this route is performed.
         */
        public int getVolume() {
            return mVolume;
        }

        /**
         * Gets the maximum volume at which the playback associated with this route is performed.
         *
         * @return The maximum volume at which the playback associated with
         * this route is performed.
         */
        public int getVolumeMax() {
            return mVolumeMax;
        }

        /**
         * Requests a volume change for this route asynchronously.
         * <p>
         * This function may only be called on a selected route.  It will have
         * no effect if the route is currently unselected.
         * </p>
         *
         * @param volume The new volume value between 0 and {@link #getVolumeMax}.
         */
        public void requestSetVolume(int volume) {
            checkCallingThread();
            sGlobal.requestSetVolume(this, Math.min(mVolumeMax, Math.max(0, volume)));
        }

        /**
         * Requests an incremental volume update for this route asynchronously.
         * <p>
         * This function may only be called on a selected route.  It will have
         * no effect if the route is currently unselected.
         * </p>
         *
         * @param delta The delta to add to the current volume.
         */
        public void requestUpdateVolume(int delta) {
            checkCallingThread();
            if (delta != 0) {
                sGlobal.requestUpdateVolume(this, delta);
            }
        }

        /**
         * Gets the {@link Display} that should be used by the application to show
         * a {@link android.app.Presentation} on an external display when this route is selected.
         * Depending on the route, this may only be valid if the route is currently
         * selected.
         * <p>
         * The preferred presentation display may change independently of the route
         * being selected or unselected.  For example, the presentation display
         * of the default system route may change when an external HDMI display is connected
         * or disconnected even though the route itself has not changed.
         * </p><p>
         * This method may return null if there is no external display associated with
         * the route or if the display is not ready to show UI yet.
         * </p><p>
         * The application should listen for changes to the presentation display
         * using the {@link Callback#onRoutePresentationDisplayChanged} callback and
         * show or dismiss its {@link android.app.Presentation} accordingly when the display
         * becomes available or is removed.
         * </p><p>
         * This method only makes sense for
         * {@link MediaControlIntent#CATEGORY_LIVE_VIDEO live video} routes.
         * </p>
         *
         * @return The preferred presentation display to use when this route is
         * selected or null if none.
         *
         * @see MediaControlIntent#CATEGORY_LIVE_VIDEO
         * @see android.app.Presentation
         */
        public Display getPresentationDisplay() {
            checkCallingThread();
            if (mPresentationDisplayId >= 0 && mPresentationDisplay == null) {
                mPresentationDisplay = sGlobal.getDisplay(mPresentationDisplayId);
            }
            return mPresentationDisplay;
        }

        /**
         * Gets a collection of extra properties about this route that were supplied
         * by its media route provider, or null if none.
         */
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * Selects this media route.
         */
        public void select() {
            checkCallingThread();
            sGlobal.selectRoute(this);
        }

        @Override
        public String toString() {
            return "MediaRouter.RouteInfo{ name=" + mName
                    + ", description=" + mDescription
                    + ", enabled=" + mEnabled
                    + ", connecting=" + mConnecting
                    + ", playbackType=" + mPlaybackType
                    + ", playbackStream=" + mPlaybackStream
                    + ", volumeHandling=" + mVolumeHandling
                    + ", volume=" + mVolume
                    + ", volumeMax=" + mVolumeMax
                    + ", presentationDisplayId=" + mPresentationDisplayId
                    + ", extras=" + mExtras
                    + ", providerPackageName=" + mProvider.getPackageName()
                    + " }";
        }

        int updateDescriptor(MediaRouteDescriptor descriptor) {
            int changes = 0;
            if (mDescriptor != descriptor) {
                mDescriptor = descriptor;
                if (descriptor != null) {
                    if (!equal(mName, descriptor.getName())) {
                        mName = descriptor.getName();
                        changes |= CHANGE_GENERAL;
                    }
                    if (!equal(mDescription, descriptor.getDescription())) {
                        mDescription = descriptor.getDescription();
                        changes |= CHANGE_GENERAL;
                    }
                    if (mEnabled != descriptor.isEnabled()) {
                        mEnabled = descriptor.isEnabled();
                        changes |= CHANGE_GENERAL;
                    }
                    if (mConnecting != descriptor.isConnecting()) {
                        mConnecting = descriptor.isConnecting();
                        changes |= CHANGE_GENERAL;
                    }
                    if (!mControlFilters.equals(descriptor.getControlFilters())) {
                        mControlFilters.clear();
                        mControlFilters.addAll(descriptor.getControlFilters());
                        changes |= CHANGE_GENERAL;
                    }
                    if (mPlaybackType != descriptor.getPlaybackType()) {
                        mPlaybackType = descriptor.getPlaybackType();
                        changes |= CHANGE_GENERAL;
                    }
                    if (mPlaybackStream != descriptor.getPlaybackStream()) {
                        mPlaybackStream = descriptor.getPlaybackStream();
                        changes |= CHANGE_GENERAL;
                    }
                    if (mVolumeHandling != descriptor.getVolumeHandling()) {
                        mVolumeHandling = descriptor.getVolumeHandling();
                        changes |= CHANGE_GENERAL | CHANGE_VOLUME;
                    }
                    if (mVolume != descriptor.getVolume()) {
                        mVolume = descriptor.getVolume();
                        changes |= CHANGE_GENERAL | CHANGE_VOLUME;
                    }
                    if (mVolumeMax != descriptor.getVolumeMax()) {
                        mVolumeMax = descriptor.getVolumeMax();
                        changes |= CHANGE_GENERAL | CHANGE_VOLUME;
                    }
                    if (mPresentationDisplayId != descriptor.getPresentationDisplayId()) {
                        mPresentationDisplayId = descriptor.getPresentationDisplayId();
                        mPresentationDisplay = null;
                        changes |= CHANGE_GENERAL | CHANGE_PRESENTATION_DISPLAY;
                    }
                    if (!equal(mExtras, descriptor.getExtras())) {
                        mExtras = descriptor.getExtras();
                        changes |= CHANGE_GENERAL;
                    }
                }
            }
            return changes;
        }

        String getDescriptorId() {
            return mDescriptorId;
        }

        MediaRouteProvider getProviderInstance() {
            return mProvider.getProviderInstance();
        }
    }

    /**
     * Provides information about a media route provider.
     * <p>
     * This object may be used to determine which media route provider has
     * published a particular route.
     * </p>
     */
    public static final class ProviderInfo {
        private final MediaRouteProvider mProviderInstance;
        private final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        private final ArrayList<IntentFilter> mDiscoverableControlFilters =
                new ArrayList<IntentFilter>();

        private final ProviderMetadata mMetadata;
        private MediaRouteProviderDescriptor mDescriptor;
        private Resources mResources;
        private boolean mResourcesNotAvailable;

        ProviderInfo(MediaRouteProvider provider) {
            mProviderInstance = provider;
            mMetadata = provider.getMetadata();
        }

        /**
         * Gets the provider's underlying {@link MediaRouteProvider} instance.
         */
        public MediaRouteProvider getProviderInstance() {
            checkCallingThread();
            return mProviderInstance;
        }

        /**
         * Gets the package name of the media route provider service.
         */
        public String getPackageName() {
            return mMetadata.getPackageName();
        }

        /**
         * Gets the {@link MediaRouter.RouteInfo routes} published by this route provider.
         */
        public List<RouteInfo> getRoutes() {
            checkCallingThread();
            return mRoutes;
        }

        /**
         * Returns true if the provider requires active scans to discover routes.
         * <p>
         * To provide the best user experience, a media route provider should passively
         * discover and publish changes to route descriptors in the background.
         * However, for some providers, scanning for routes may use a significant
         * amount of power or may interfere with wireless network connectivity.
         * If this is the case, then the provider will indicate that it requires
         * active scans to discover routes by setting this flag.  Active scans
         * will be performed when the user opens the route chooser dialog.
         * </p>
         */
        public boolean isActiveScanRequired() {
            checkCallingThread();
            return mDescriptor != null && mDescriptor.isActiveScanRequired();
        }

        /**
         * Gets a list of {@link MediaControlIntent media route control filters} that
         * describe the union of capabilities of all routes that this provider can
         * possibly discover.
         * <p>
         * Because a route provider may not know what to look for until an
         * application actually asks for it, the contents of the discoverable control
         * filter list may change depending on the route selectors that applications have
         * actually specified when {@link MediaRouter#addCallback registering callbacks}
         * on the media router to discover routes.
         * </p>
         */
        public List<IntentFilter> getDiscoverableControlFilters() {
            checkCallingThread();
            return mDiscoverableControlFilters;
        }

        Resources getResources() {
            if (mResources == null && !mResourcesNotAvailable) {
                String packageName = getPackageName();
                Context context = sGlobal.getProviderContext(packageName);
                if (context != null) {
                    mResources = context.getResources();
                } else {
                    Log.w(TAG, "Unable to obtain resources for route provider package: "
                            + packageName);
                    mResourcesNotAvailable = true;
                }
            }
            return mResources;
        }

        boolean updateDescriptor(MediaRouteProviderDescriptor descriptor) {
            if (mDescriptor != descriptor) {
                mDescriptor = descriptor;
                if (descriptor != null) {
                    if (!mDiscoverableControlFilters.equals(
                            descriptor.getDiscoverableControlFilters())) {
                        mDiscoverableControlFilters.clear();
                        mDiscoverableControlFilters.addAll(
                                descriptor.getDiscoverableControlFilters());
                    }
                }
                return true;
            }
            return false;
        }

        int findRouteByDescriptorId(String id) {
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                if (mRoutes.get(i).mDescriptorId.equals(id)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String toString() {
            return "MediaRouter.RouteProviderInfo{ packageName=" + getPackageName()
                    + ", isActiveScanRequired=" + isActiveScanRequired()
                    + " }";
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     * All methods of this interface will be called from the application's main thread.
     * <p>
     * A Callback will only receive events relevant to routes that the callback
     * was registered for unless the {@link MediaRouter#CALLBACK_FLAG_UNFILTERED_EVENTS}
     * flag was specified in {@link MediaRouter#addCallback(MediaRouteSelector, Callback, int)}.
     * </p>
     *
     * @see MediaRouter#addCallback(MediaRouteSelector, Callback, int)
     * @see MediaRouter#removeCallback(Callback)
     */
    public static abstract class Callback {
        /**
         * Called when the supplied media route becomes selected as the active route.
         *
         * @param router The media router reporting the event.
         * @param route The route that has been selected.
         */
        public void onRouteSelected(MediaRouter router, RouteInfo route) {
        }

        /**
         * Called when the supplied media route becomes unselected as the active route.
         *
         * @param router The media router reporting the event.
         * @param route The route that has been unselected.
         */
        public void onRouteUnselected(MediaRouter router, RouteInfo route) {
        }

        /**
         * Called when a media route has been added.
         *
         * @param router The media router reporting the event.
         * @param route The route that has become available for use.
         */
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
        }

        /**
         * Called when a media route has been removed.
         *
         * @param router The media router reporting the event.
         * @param route The route that has been removed from availability.
         */
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
        }

        /**
         * Called when a property of the indicated media route has changed.
         *
         * @param router The media router reporting the event.
         * @param route The route that was changed.
         */
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
        }

        /**
         * Called when a media route's volume changes.
         *
         * @param router The media router reporting the event.
         * @param route The route whose volume changed.
         */
        public void onRouteVolumeChanged(MediaRouter router, RouteInfo route) {
        }

        /**
         * Called when a media route's presentation display changes.
         * <p>
         * This method is called whenever the route's presentation display becomes
         * available, is removed or has changes to some of its properties (such as its size).
         * </p>
         *
         * @param router The media router reporting the event.
         * @param route The route whose presentation display changed.
         *
         * @see RouteInfo#getPresentationDisplay()
         */
        public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo route) {
        }

        /**
         * Called when a media route provider has been added.
         *
         * @param router The media router reporting the event.
         * @param provider The provider that has become available for use.
         */
        public void onProviderAdded(MediaRouter router, ProviderInfo provider) {
        }

        /**
         * Called when a media route provider has been removed.
         *
         * @param router The media router reporting the event.
         * @param provider The provider that has been removed from availability.
         */
        public void onProviderRemoved(MediaRouter router, ProviderInfo provider) {
        }

        /**
         * Called when a property of the indicated media route provider has changed.
         *
         * @param router The media router reporting the event.
         * @param provider The provider that was changed.
         */
        public void onProviderChanged(MediaRouter router, ProviderInfo provider) {
        }
    }

    /**
     * Callback which is invoked with the result of a media control request.
     *
     * @see RouteInfo#sendControlRequest
     */
    public static abstract class ControlRequestCallback {
        /**
         * Called when a media control request succeeds.
         *
         * @param data Result data, or null if none.
         * Contents depend on the {@link MediaControlIntent media control action}.
         */
        public void onResult(Bundle data) {
        }

        /**
         * Called when a media control request fails.
         *
         * @param error A localized error message which may be shown to the user, or null
         * if the cause of the error is unclear.
         * @param data Error data, or null if none.
         * Contents depend on the {@link MediaControlIntent media control action}.
         */
        public void onError(String error, Bundle data) {
        }
    }

    private static final class CallbackRecord {
        public final Callback mCallback;
        public MediaRouteSelector mSelector;
        public int mFlags;

        public CallbackRecord(Callback callback) {
            mCallback = callback;
            mSelector = MediaRouteSelector.EMPTY;
        }

        public boolean filterRouteEvent(RouteInfo route) {
            return (mFlags & CALLBACK_FLAG_UNFILTERED_EVENTS) != 0
                    || route.matchesSelector(mSelector);
        }
    }

    /**
     * Global state for the media router.
     * <p>
     * Media routes and media route providers are global to the process; their
     * state and the bulk of the media router implementation lives here.
     * </p>
     */
    private static final class GlobalMediaRouter implements SystemMediaRouteProvider.SyncCallback {
        private final Context mApplicationContext;
        private final MediaRouter mApplicationRouter;
        private final WeakHashMap<Context, MediaRouter> mRouters =
                new WeakHashMap<Context, MediaRouter>();
        private final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        private final ArrayList<ProviderInfo> mProviders =
                new ArrayList<ProviderInfo>();
        private final ProviderCallback mProviderCallback = new ProviderCallback();
        private final CallbackHandler mCallbackHandler = new CallbackHandler();
        private final DisplayManagerCompat mDisplayManager;
        private final SystemMediaRouteProvider mSystemProvider;

        private RegisteredMediaRouteProviderWatcher mRegisteredProviderWatcher;
        private RouteInfo mDefaultRoute;
        private RouteInfo mSelectedRoute;
        private MediaRouteProvider.RouteController mSelectedRouteController;
        private MediaRouteDiscoveryRequest mDiscoveryRequest;

        GlobalMediaRouter(Context applicationContext) {
            mApplicationContext = applicationContext;
            mDisplayManager = DisplayManagerCompat.getInstance(applicationContext);
            mApplicationRouter = getRouter(applicationContext);

            // Add the system media route provider for interoperating with
            // the framework media router.  This one is special and receives
            // synchronization messages from the media router.
            mSystemProvider = SystemMediaRouteProvider.obtain(applicationContext, this);
            addProvider(mSystemProvider);
        }

        public void start() {
            // Start watching for routes published by registered media route
            // provider services.
            mRegisteredProviderWatcher = new RegisteredMediaRouteProviderWatcher(
                    mApplicationContext, mApplicationRouter);
            mRegisteredProviderWatcher.start();
        }

        public MediaRouter getRouter(Context context) {
            MediaRouter router = mRouters.get(context);
            if (router == null) {
                router = new MediaRouter(context);
                mRouters.put(context, router);
            }
            return router;
        }

        public ContentResolver getContentResolver() {
            return mApplicationContext.getContentResolver();
        }

        public Context getProviderContext(String packageName) {
            if (packageName.equals(SystemMediaRouteProvider.PACKAGE_NAME)) {
                return mApplicationContext;
            }
            try {
                return mApplicationContext.createPackageContext(
                        packageName, Context.CONTEXT_RESTRICTED);
            } catch (NameNotFoundException ex) {
                return null;
            }
        }

        public Display getDisplay(int displayId) {
            return mDisplayManager.getDisplay(displayId);
        }

        public void sendControlRequest(RouteInfo route,
                Intent intent, ControlRequestCallback callback) {
            if (route == mSelectedRoute && mSelectedRouteController != null) {
                if (mSelectedRouteController.onControlRequest(intent, callback)) {
                    return;
                }
            }
            if (callback != null) {
                callback.onError(null, null);
            }
        }

        public void requestSetVolume(RouteInfo route, int volume) {
            if (route == mSelectedRoute && mSelectedRouteController != null) {
                mSelectedRouteController.onSetVolume(volume);
            }
        }

        public void requestUpdateVolume(RouteInfo route, int delta) {
            if (route == mSelectedRoute && mSelectedRouteController != null) {
                mSelectedRouteController.onUpdateVolume(delta);
            }
        }

        public List<RouteInfo> getRoutes() {
            return mRoutes;
        }

        public List<ProviderInfo> getProviders() {
            return mProviders;
        }

        public RouteInfo getDefaultRoute() {
            if (mDefaultRoute == null) {
                // This should never happen once the media router has been fully
                // initialized but it is good to check for the error in case there
                // is a bug in provider initialization.
                throw new IllegalStateException("There is no default route.  "
                        + "The media router has not yet been fully initialized.");
            }
            return mDefaultRoute;
        }

        public RouteInfo getSelectedRoute() {
            if (mSelectedRoute == null) {
                // This should never happen once the media router has been fully
                // initialized but it is good to check for the error in case there
                // is a bug in provider initialization.
                throw new IllegalStateException("There is no currently selected route.  "
                        + "The media router has not yet been fully initialized.");
            }
            return mSelectedRoute;
        }

        public void selectRoute(RouteInfo route) {
            if (!mRoutes.contains(route)) {
                Log.w(TAG, "Ignoring attempt to select removed route: " + route);
                return;
            }
            if (!route.mEnabled) {
                Log.w(TAG, "Ignoring attempt to select disabled route: " + route);
                return;
            }

            setSelectedRouteInternal(route);
        }

        public boolean isRouteAvailable(MediaRouteSelector selector, int flags) {
            // Check whether any existing routes match the selector.
            final int routeCount = mRoutes.size();
            for (int i = 0; i < routeCount; i++) {
                RouteInfo route = mRoutes.get(i);
                if ((flags & AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE) != 0
                        && route.isDefault()) {
                    continue;
                }
                if (route.matchesSelector(selector)) {
                    return true;
                }
            }

            // Check whether any provider could possibly discover a matching route
            // if a required active scan were performed.
            if ((flags & AVAILABILITY_FLAG_CONSIDER_ACTIVE_SCAN) != 0) {
                final int providerCount = mProviders.size();
                for (int i = 0; i < providerCount; i++) {
                    ProviderInfo provider = mProviders.get(i);
                    if (provider.isActiveScanRequired() && selector.matchesControlFilters(
                            provider.getDiscoverableControlFilters())) {
                        return true;
                    }
                }
            }

            // It doesn't look like we can find a matching route right now.
            return false;
        }

        public void updateDiscoveryRequest() {
            // Combine all of the callback selectors and active scan flags.
            boolean activeScan = false;
            MediaRouteSelector.Builder builder = new MediaRouteSelector.Builder();
            for (MediaRouter router : mRouters.values()) {
                final int count = router.mCallbackRecords.size();
                for (int i = 0; i < count; i++) {
                    CallbackRecord callback = router.mCallbackRecords.get(i);
                    builder.addSelector(callback.mSelector);
                    if ((callback.mFlags & CALLBACK_FLAG_ACTIVE_SCAN) != 0) {
                        activeScan = true;
                    }
                }
            }
            MediaRouteSelector selector = builder.build();

            // Create a new discovery request.
            if (mDiscoveryRequest != null
                    && mDiscoveryRequest.getSelector().equals(selector)
                    && mDiscoveryRequest.isActiveScan() == activeScan) {
                return; // no change
            }
            if (selector.isEmpty() && !activeScan) {
                // Discovery is not needed.
                if (mDiscoveryRequest == null) {
                    return; // no change
                }
                mDiscoveryRequest = null;
            } else {
                // Discovery is needed.
                mDiscoveryRequest = new MediaRouteDiscoveryRequest(selector, activeScan);
            }
            if (DEBUG) {
                Log.d(TAG, "Updated discovery request: " + mDiscoveryRequest);
            }

            // Notify providers.
            final int providerCount = mProviders.size();
            for (int i = 0; i < providerCount; i++) {
                mProviders.get(i).mProviderInstance.setDiscoveryRequest(mDiscoveryRequest);
            }
        }

        public void addProvider(MediaRouteProvider providerInstance) {
            int index = findProviderInfo(providerInstance);
            if (index < 0) {
                // 1. Add the provider to the list.
                ProviderInfo provider = new ProviderInfo(providerInstance);
                mProviders.add(provider);
                if (DEBUG) {
                    Log.d(TAG, "Provider added: " + provider);
                }
                mCallbackHandler.post(CallbackHandler.MSG_PROVIDER_ADDED, provider);
                // 2. Create the provider's contents.
                updateProviderContents(provider, providerInstance.getDescriptor());
                // 3. Register the provider callback.
                providerInstance.setCallback(mProviderCallback);
                // 4. Set the discovery request.
                providerInstance.setDiscoveryRequest(mDiscoveryRequest);
            }
        }

        public void removeProvider(MediaRouteProvider providerInstance) {
            int index = findProviderInfo(providerInstance);
            if (index >= 0) {
                // 1. Unregister the provider callback.
                providerInstance.setCallback(null);
                // 2. Clear the discovery request.
                providerInstance.setDiscoveryRequest(null);
                // 3. Delete the provider's contents.
                ProviderInfo provider = mProviders.get(index);
                updateProviderContents(provider, null);
                // 4. Remove the provider from the list.
                if (DEBUG) {
                    Log.d(TAG, "Provider removed: " + provider);
                }
                mCallbackHandler.post(CallbackHandler.MSG_PROVIDER_REMOVED, provider);
                mProviders.remove(index);
            }
        }

        private void updateProviderDescriptor(MediaRouteProvider providerInstance,
                MediaRouteProviderDescriptor descriptor) {
            int index = findProviderInfo(providerInstance);
            if (index >= 0) {
                // Update the provider's contents.
                ProviderInfo provider = mProviders.get(index);
                updateProviderContents(provider, descriptor);
            }
        }

        private int findProviderInfo(MediaRouteProvider providerInstance) {
            final int count = mProviders.size();
            for (int i = 0; i < count; i++) {
                if (mProviders.get(i).mProviderInstance == providerInstance) {
                    return i;
                }
            }
            return -1;
        }

        private void updateProviderContents(ProviderInfo provider,
                MediaRouteProviderDescriptor providerDescriptor) {
            if (provider.updateDescriptor(providerDescriptor)) {
                // Update all existing routes and reorder them to match
                // the order of their descriptors.
                int targetIndex = 0;
                if (providerDescriptor != null) {
                    if (providerDescriptor.isValid()) {
                        final List<MediaRouteDescriptor> routeDescriptors =
                                providerDescriptor.getRoutes();
                        final int routeCount = routeDescriptors.size();
                        for (int i = 0; i < routeCount; i++) {
                            final MediaRouteDescriptor routeDescriptor = routeDescriptors.get(i);
                            final String id = routeDescriptor.getId();
                            final int sourceIndex = provider.findRouteByDescriptorId(id);
                            if (sourceIndex < 0) {
                                // 1. Add the route to the list.
                                RouteInfo route = new RouteInfo(provider, id);
                                provider.mRoutes.add(targetIndex++, route);
                                mRoutes.add(route);
                                // 2. Create the route's contents.
                                route.updateDescriptor(routeDescriptor);
                                // 3. Notify clients about addition.
                                if (DEBUG) {
                                    Log.d(TAG, "Route added: " + route);
                                }
                                mCallbackHandler.post(CallbackHandler.MSG_ROUTE_ADDED, route);
                            } else if (sourceIndex < targetIndex) {
                                Log.w(TAG, "Ignoring route descriptor with duplicate id: "
                                        + routeDescriptor);
                            } else {
                                // 1. Reorder the route within the list.
                                RouteInfo route = provider.mRoutes.get(sourceIndex);
                                Collections.swap(provider.mRoutes,
                                        sourceIndex, targetIndex++);
                                // 2. Update the route's contents.
                                int changes = route.updateDescriptor(routeDescriptor);
                                // 3. Unselect route if needed before notifying about changes.
                                unselectRouteIfNeeded(route);
                                // 4. Notify clients about changes.
                                if ((changes & RouteInfo.CHANGE_GENERAL) != 0) {
                                    if (DEBUG) {
                                        Log.d(TAG, "Route changed: " + route);
                                    }
                                    mCallbackHandler.post(
                                            CallbackHandler.MSG_ROUTE_CHANGED, route);
                                }
                                if ((changes & RouteInfo.CHANGE_VOLUME) != 0) {
                                    if (DEBUG) {
                                        Log.d(TAG, "Route volume changed: " + route);
                                    }
                                    mCallbackHandler.post(
                                            CallbackHandler.MSG_ROUTE_VOLUME_CHANGED, route);
                                }
                                if ((changes & RouteInfo.CHANGE_PRESENTATION_DISPLAY) != 0) {
                                    if (DEBUG) {
                                        Log.d(TAG, "Route presentation display changed: "
                                                + route);
                                    }
                                    mCallbackHandler.post(CallbackHandler.
                                            MSG_ROUTE_PRESENTATION_DISPLAY_CHANGED, route);
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "Ignoring invalid provider descriptor: " + providerDescriptor);
                    }
                }

                // Dispose all remaining routes that do not have matching descriptors.
                for (int i = provider.mRoutes.size() - 1; i >= targetIndex; i--) {
                    // 1. Delete the route's contents.
                    RouteInfo route = provider.mRoutes.get(i);
                    route.updateDescriptor(null);
                    // 2. Remove the route from the list.
                    mRoutes.remove(route);
                    provider.mRoutes.remove(i);
                    // 3. Unselect route if needed before notifying about removal.
                    unselectRouteIfNeeded(route);
                    // 4. Notify clients about removal.
                    if (DEBUG) {
                        Log.d(TAG, "Route removed: " + route);
                    }
                    mCallbackHandler.post(CallbackHandler.MSG_ROUTE_REMOVED, route);
                }

                // Notify provider changed.
                if (DEBUG) {
                    Log.d(TAG, "Provider changed: " + provider);
                }
                mCallbackHandler.post(CallbackHandler.MSG_PROVIDER_CHANGED, provider);

                // Choose a new selected route if needed.
                selectRouteIfNeeded();
            }
        }

        private void unselectRouteIfNeeded(RouteInfo route) {
            if (mDefaultRoute == route && !isRouteSelectable(route)) {
                Log.i(TAG, "Choosing a new default route because the current one "
                        + "is no longer selectable: " + route);
                mDefaultRoute = null;
            }
            if (mSelectedRoute == route && !isRouteSelectable(route)) {
                Log.i(TAG, "Choosing a new selected route because the current one "
                        + "is no longer selectable: " + route);
                setSelectedRouteInternal(null);
            }
        }

        private void selectRouteIfNeeded() {
            if (mDefaultRoute == null && !mRoutes.isEmpty()) {
                for (RouteInfo route : mRoutes) {
                    if (isSystemDefaultRoute(route) && isRouteSelectable(route)) {
                        mDefaultRoute = route;
                        break;
                    }
                }
            }
            if (mSelectedRoute == null) {
                setSelectedRouteInternal(mDefaultRoute);
            }
        }

        private boolean isRouteSelectable(RouteInfo route) {
            // This tests whether the route is still valid and enabled.
            // The route descriptor field is set to null when the route is removed.
            return route.mDescriptor != null && route.mEnabled;
        }

        private boolean isSystemDefaultRoute(RouteInfo route) {
            return route.getProviderInstance() == mSystemProvider
                    && route.mDescriptorId.equals(
                            SystemMediaRouteProvider.DEFAULT_ROUTE_ID);
        }

        private void setSelectedRouteInternal(RouteInfo route) {
            if (mSelectedRoute != route) {
                if (mSelectedRoute != null) {
                    if (DEBUG) {
                        Log.d(TAG, "Route unselected: " + mSelectedRoute);
                    }
                    mCallbackHandler.post(CallbackHandler.MSG_ROUTE_UNSELECTED, mSelectedRoute);
                    if (mSelectedRouteController != null) {
                        mSelectedRouteController.onUnselect();
                        mSelectedRouteController.onRelease();
                        mSelectedRouteController = null;
                    }
                }

                mSelectedRoute = route;

                if (mSelectedRoute != null) {
                    mSelectedRouteController = route.getProviderInstance().onCreateRouteController(
                            route.mDescriptorId);
                    if (mSelectedRouteController != null) {
                        mSelectedRouteController.onSelect();
                    }
                    if (DEBUG) {
                        Log.d(TAG, "Route selected: " + mSelectedRoute);
                    }
                    mCallbackHandler.post(CallbackHandler.MSG_ROUTE_SELECTED, mSelectedRoute);
                }
            }
        }

        @Override
        public RouteInfo getSystemRouteByDescriptorId(String id) {
            int providerIndex = findProviderInfo(mSystemProvider);
            if (providerIndex >= 0) {
                ProviderInfo provider = mProviders.get(providerIndex);
                int routeIndex = provider.findRouteByDescriptorId(id);
                if (routeIndex >= 0) {
                    return provider.mRoutes.get(routeIndex);
                }
            }
            return null;
        }

        private final class ProviderCallback extends MediaRouteProvider.Callback {
            @Override
            public void onDescriptorChanged(MediaRouteProvider provider,
                    MediaRouteProviderDescriptor descriptor) {
                updateProviderDescriptor(provider, descriptor);
            }
        }

        private final class CallbackHandler extends Handler {
            private final ArrayList<MediaRouter> mTempMediaRouters =
                    new ArrayList<MediaRouter>();

            private static final int MSG_TYPE_MASK = 0xff00;
            private static final int MSG_TYPE_ROUTE = 0x0100;
            private static final int MSG_TYPE_PROVIDER = 0x0200;

            public static final int MSG_ROUTE_ADDED = MSG_TYPE_ROUTE | 1;
            public static final int MSG_ROUTE_REMOVED = MSG_TYPE_ROUTE | 2;
            public static final int MSG_ROUTE_CHANGED = MSG_TYPE_ROUTE | 3;
            public static final int MSG_ROUTE_VOLUME_CHANGED = MSG_TYPE_ROUTE | 4;
            public static final int MSG_ROUTE_PRESENTATION_DISPLAY_CHANGED = MSG_TYPE_ROUTE | 5;
            public static final int MSG_ROUTE_SELECTED = MSG_TYPE_ROUTE | 6;
            public static final int MSG_ROUTE_UNSELECTED = MSG_TYPE_ROUTE | 7;

            public static final int MSG_PROVIDER_ADDED = MSG_TYPE_PROVIDER | 1;
            public static final int MSG_PROVIDER_REMOVED = MSG_TYPE_PROVIDER | 2;
            public static final int MSG_PROVIDER_CHANGED = MSG_TYPE_PROVIDER | 3;

            public void post(int msg, Object obj) {
                obtainMessage(msg, obj).sendToTarget();
            }

            @Override
            public void handleMessage(Message msg) {
                final int what = msg.what;
                final Object obj = msg.obj;

                // Synchronize state with the system media router.
                syncWithSystemProvider(what, obj);

                // Invoke all registered callbacks.
                mTempMediaRouters.addAll(mRouters.values());
                try {
                    final int routerCount = mTempMediaRouters.size();
                    for (int i = 0; i < routerCount; i++) {
                        final MediaRouter router = mTempMediaRouters.get(i);
                        if (!router.mCallbackRecords.isEmpty()) {
                            for (CallbackRecord record : router.mCallbackRecords) {
                                invokeCallback(router, record, what, obj);
                            }
                        }
                    }
                } finally {
                    mTempMediaRouters.clear();
                }
            }

            private void syncWithSystemProvider(int what, Object obj) {
                switch (what) {
                    case MSG_ROUTE_ADDED:
                        mSystemProvider.onSyncRouteAdded((RouteInfo)obj);
                        break;
                    case MSG_ROUTE_REMOVED:
                        mSystemProvider.onSyncRouteRemoved((RouteInfo)obj);
                        break;
                    case MSG_ROUTE_CHANGED:
                        mSystemProvider.onSyncRouteChanged((RouteInfo)obj);
                        break;
                    case MSG_ROUTE_SELECTED:
                        mSystemProvider.onSyncRouteSelected((RouteInfo)obj);
                        break;
                }
            }

            private void invokeCallback(MediaRouter router, CallbackRecord record,
                    int what, Object obj) {
                final MediaRouter.Callback callback = record.mCallback;
                switch (what & MSG_TYPE_MASK) {
                    case MSG_TYPE_ROUTE: {
                        final RouteInfo route = (RouteInfo)obj;
                        if (!record.filterRouteEvent(route)) {
                            break;
                        }
                        switch (what) {
                            case MSG_ROUTE_ADDED:
                                callback.onRouteAdded(router, route);
                                break;
                            case MSG_ROUTE_REMOVED:
                                callback.onRouteRemoved(router, route);
                                break;
                            case MSG_ROUTE_CHANGED:
                                callback.onRouteChanged(router, route);
                                break;
                            case MSG_ROUTE_VOLUME_CHANGED:
                                callback.onRouteVolumeChanged(router, route);
                                break;
                            case MSG_ROUTE_PRESENTATION_DISPLAY_CHANGED:
                                callback.onRoutePresentationDisplayChanged(router, route);
                                break;
                            case MSG_ROUTE_SELECTED:
                                callback.onRouteSelected(router, route);
                                break;
                            case MSG_ROUTE_UNSELECTED:
                                callback.onRouteUnselected(router, route);
                                break;
                        }
                        break;
                    }
                    case MSG_TYPE_PROVIDER: {
                        final ProviderInfo provider = (ProviderInfo)obj;
                        switch (what) {
                            case MSG_PROVIDER_ADDED:
                                callback.onProviderAdded(router, provider);
                                break;
                            case MSG_PROVIDER_REMOVED:
                                callback.onProviderRemoved(router, provider);
                                break;
                            case MSG_PROVIDER_CHANGED:
                                callback.onProviderChanged(router, provider);
                                break;
                        }
                    }
                }
            }
        }
    }
}

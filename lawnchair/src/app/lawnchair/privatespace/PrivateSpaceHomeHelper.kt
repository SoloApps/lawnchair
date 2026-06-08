/*
 * Copyright 2024, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.privatespace

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.UserProfileManager
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.ApiWrapper
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.SafeCloseable
import com.patrykmichalik.opto.core.firstBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central decision point for the "Private Space on the home screen" feature.
 *
 * All decisions ("is this a Private Space item?", "is the Private Space unlocked?",
 * "may this item be pinned?", "request unlock then run") flow through this single
 * helper. Upstream Launcher3 files only contain tiny, marked call-sites that delegate
 * here, which keeps the fork conflict-free during upstream merges.
 *
 * Every system/binder call is wrapped in a try/catch with a safe fallback so the
 * launcher never crashes: when in doubt we fall back to the original (safe) behaviour
 * where Private Space items are not pinnable and stay locked.
 */
object PrivateSpaceHomeHelper {

    private const val TAG = "PrivateSpaceHomeHelper"

    /**
     * Safety timeout (ms) after which a pending unlock start is discarded. This prevents
     * a cancelled/dismissed credential prompt (which produces no broadcast) from leaking
     * the registered listener and the captured app-start [Runnable] indefinitely.
     */
    private const val UNLOCK_TIMEOUT_MS = 60_000L

    /** Guards [pendingUnlockAction] and its lifecycle transitions. */
    private val lock = Any()

    /**
     * Pending deferred start, registered while a Private Space unlock is requested.
     * Only the most recent request is kept (see [registerPendingAction]); a newer tap
     * replaces and tears down an older pending start.
     */
    @Volatile
    private var pendingUnlockAction: PendingUnlockAction? = null

    /**
     * A single deferred app-start awaiting the Private Space to become available.
     *
     * Owns the resources that must be cleaned up exactly once: the [SafeCloseable]
     * returned by [UserCache.addUserEventListener] and the timeout callback posted on the
     * main thread. [consumed] makes firing/cancelling idempotent so the start can never
     * run twice and resources are released exactly once.
     */
    private class PendingUnlockAction(
        val user: UserHandle,
        val onUnlocked: Runnable,
    ) {
        @Volatile
        var listener: SafeCloseable? = null

        @Volatile
        var timeout: Runnable? = null

        private val consumed = AtomicBoolean(false)

        /** Returns true exactly once; the winning caller owns firing/cleanup. */
        fun tryConsume(): Boolean = consumed.compareAndSet(false, true)
    }

    /**
     * Returns whether the feature is enabled via [PreferenceManager2.allowPrivateSpaceOnHome].
     *
     * On any failure this returns false, which maps to the original Lawnchair behaviour
     * (the feature is treated as off, i.e. Private Space items are not pinnable).
     */
    fun isFeatureEnabled(context: Context): Boolean {
        return try {
            PreferenceManager2.getInstance(context).allowPrivateSpaceOnHome.firstBlocking()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read allowPrivateSpaceOnHome preference", e)
            false
        }
    }

    /**
     * Returns true when [info] belongs to the private profile.
     *
     * Returns false when [info] or its [ItemInfo.user] is null, or on any failure.
     */
    fun isPrivateItem(context: Context, info: ItemInfo?): Boolean {
        val user = info?.user ?: return false
        return try {
            UserCache.getInstance(context).getUserInfo(user).isPrivate
        } catch (e: Exception) {
            Log.d(TAG, "Failed to resolve private profile for item", e)
            false
        }
    }

    /**
     * Returns true when the Private Space is currently unlocked
     * ([UserProfileManager.STATE_ENABLED]).
     *
     * Returns false on any failure, which is the safe default (treat as locked).
     */
    fun isPrivateSpaceUnlocked(launcher: Launcher): Boolean {
        return try {
            launcher.appsView?.privateProfileManager?.currentState == UserProfileManager.STATE_ENABLED
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read Private Space unlock state", e)
            false
        }
    }

    /**
     * Context-only overload of [isPrivateSpaceUnlocked] for call-sites that only have a
     * [Context]. Resolves the [Launcher] via [Launcher.getLauncher] and returns false on
     * any failure (safe default: treat as locked).
     */
    fun isPrivateSpaceUnlocked(context: Context): Boolean {
        return try {
            isPrivateSpaceUnlocked(Launcher.getLauncher(context))
        } catch (e: Exception) {
            Log.d(TAG, "Failed to resolve launcher for unlock state", e)
            false
        }
    }

    /**
     * Central pinnability rule for Private Space items.
     *
     * This is only meant to be called by the gate-keepers once an item has been
     * identified as a Private Space item, but it is defensive about its inputs:
     * - feature off -> false (original behaviour: not pinnable)
     * - feature on and Private Space unlocked -> true
     * - otherwise -> false (locked Private Space is not pinnable)
     */
    fun canPinPrivateItem(context: Context, info: ItemInfo?): Boolean {
        if (!isFeatureEnabled(context)) return false
        if (!isPrivateItem(context, info)) return false
        return isPrivateSpaceUnlocked(context)
    }

    /**
     * Requests the system unlock of the private profile and runs [onUnlocked] once the
     * profile actually becomes available (the deferred app start).
     *
     * Flow (Requirements 3.2–3.5):
     * 1. Resolve the private [UserHandle]; if none, do nothing.
     * 2. Register the deferred start, keeping only the latest pending start.
     * 3. Set up an unlock detector and a safety timeout.
     * 4. Call [UserManager.requestQuietModeEnabled] to show the system credential prompt.
     *    On success the framework emits a profile-available broadcast, which fires the
     *    deferred start with the exact app that was tapped. On cancel/failure no broadcast
     *    arrives, so nothing starts and the timeout cleans up the registration.
     *
     * Unlock detection uses [UserCache.addUserEventListener], the codebase-native wrapper
     * around the `ACTION_PROFILE_AVAILABLE` / `ACTION_MANAGED_PROFILE_AVAILABLE` broadcasts.
     * It is registered against the already-correctly-configured receiver in [UserCache],
     * which avoids manual receiver-flag handling and is the most reliable option here.
     *
     * Every system call is wrapped so the launcher never crashes under any failure
     * condition; on failure the pending start and listener are torn down and nothing runs.
     */
    fun requestUnlockThenRun(launcher: Launcher, info: ItemInfo, onUnlocked: Runnable) {
        val privateUser = resolvePrivateUser(launcher) ?: return
        val userManager = try {
            launcher.getSystemService(UserManager::class.java)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to obtain UserManager", e)
            null
        } ?: return

        // Register the deferred start (and tear down any earlier one) before requesting
        // the unlock, so a very fast unlock cannot race ahead of the registration.
        val action = PendingUnlockAction(privateUser, onUnlocked)
        registerPendingAction(launcher, action)

        try {
            // Real profile lock toggle: shows the system credential prompt when a profile
            // lock is configured. This is the user's confirmed action.
            userManager.requestQuietModeEnabled(false, privateUser)
        } catch (se: SecurityException) {
            // Launcher is not the default HOME app: reuse the existing pattern from
            // UserProfileManager.setQuietModeSafely and bail out without starting anything.
            Log.d(TAG, "Missing HOME role for Private Space unlock", se)
            cancelPendingAction(action)
            try {
                ApiWrapper.INSTANCE.get(launcher).assignDefaultHomeRole(launcher)
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to request default HOME role", t)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to request Private Space unlock", e)
            cancelPendingAction(action)
        }
    }

    /**
     * Resolves the private profile [UserHandle] via the [PrivateProfileManager], falling
     * back to scanning [UserCache] profiles. Returns null when no private profile exists
     * or on any failure (safe default: caller does nothing).
     */
    private fun resolvePrivateUser(launcher: Launcher): UserHandle? {
        return try {
            launcher.appsView?.privateProfileManager?.profileUser
                ?: resolvePrivateUserFromCache(launcher)
        } catch (e: Exception) {
            try {
                resolvePrivateUserFromCache(launcher)
            } catch (e2: Exception) {
                Log.d(TAG, "Failed to resolve private profile user", e2)
                null
            }
        }
    }

    private fun resolvePrivateUserFromCache(context: Context): UserHandle? {
        val userCache = UserCache.getInstance(context)
        return userCache.userProfiles.firstOrNull { userCache.getUserInfo(it).isPrivate }
    }

    /**
     * Stores [action] as the single pending start (replacing any earlier one), registers
     * the unlock detector and schedules the safety timeout.
     */
    private fun registerPendingAction(launcher: Launcher, action: PendingUnlockAction) {
        synchronized(lock) {
            // Keep only the latest pending start; discard a previous one without firing it.
            clearPendingLocked()
            pendingUnlockAction = action
        }

        val appContext = launcher.applicationContext
        try {
            val listener = UserCache.getInstance(appContext)
                .addUserEventListener { user, eventAction ->
                    if (isProfileAvailableAction(eventAction) && user == action.user) {
                        onProfileMaybeAvailable(appContext, action)
                    }
                }
            // If a newer request replaced us during registration, close immediately.
            synchronized(lock) {
                if (pendingUnlockAction === action) {
                    action.listener = listener
                } else {
                    try {
                        listener.close()
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to close stale unlock listener", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to register Private Space unlock listener", e)
        }

        // Safety timeout: a dismissed/cancelled prompt produces no broadcast, so discard
        // the pending start after a while to avoid leaking the listener and the Runnable.
        val timeout = Runnable {
            Log.d(TAG, "Private Space unlock timed out; discarding pending start")
            cancelPendingAction(action)
        }
        action.timeout = timeout
        try {
            MAIN_EXECUTOR.handler.postDelayed(timeout, UNLOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to schedule Private Space unlock timeout", e)
        }
    }

    private fun isProfileAvailableAction(action: String?): Boolean {
        return action == UserCache.ACTION_PROFILE_AVAILABLE ||
            action == Intent.ACTION_MANAGED_PROFILE_AVAILABLE
    }

    /**
     * Called when an "available" broadcast arrives for the pending profile. Confirms the
     * profile is genuinely available (quiet mode off) before firing the deferred start.
     */
    private fun onProfileMaybeAvailable(context: Context, action: PendingUnlockAction) {
        val available = try {
            val userManager = context.getSystemService(UserManager::class.java)
            userManager != null && !userManager.isQuietModeEnabled(action.user)
        } catch (e: Exception) {
            // Could not confirm; trust the "available" broadcast we just received.
            true
        }
        if (available) {
            firePendingAction(action)
        }
    }

    /**
     * Runs the deferred start exactly once on the main thread, then releases resources.
     * No-op if [action] was already fired or cancelled.
     */
    private fun firePendingAction(action: PendingUnlockAction) {
        if (!action.tryConsume()) return
        cleanupResources(action)
        synchronized(lock) {
            if (pendingUnlockAction === action) {
                pendingUnlockAction = null
            }
        }
        MAIN_EXECUTOR.execute {
            try {
                action.onUnlocked.run()
            } catch (e: Exception) {
                Log.d(TAG, "Failed to run deferred Private Space app start", e)
            }
        }
    }

    /**
     * Discards the deferred start exactly once (without running it) and releases resources.
     * No-op if [action] was already fired or cancelled.
     */
    private fun cancelPendingAction(action: PendingUnlockAction) {
        if (!action.tryConsume()) return
        cleanupResources(action)
        synchronized(lock) {
            if (pendingUnlockAction === action) {
                pendingUnlockAction = null
            }
        }
    }

    /** Tears down the current pending action (if any) while [lock] is held. */
    private fun clearPendingLocked() {
        val previous = pendingUnlockAction ?: return
        pendingUnlockAction = null
        if (previous.tryConsume()) {
            cleanupResources(previous)
        }
    }

    /** Closes the unlock listener and removes the timeout callback. Safe to call twice. */
    private fun cleanupResources(action: PendingUnlockAction) {
        action.listener?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.d(TAG, "Failed to close Private Space unlock listener", e)
            }
        }
        action.listener = null
        action.timeout?.let {
            try {
                MAIN_EXECUTOR.handler.removeCallbacks(it)
            } catch (e: Exception) {
                Log.d(TAG, "Failed to remove Private Space unlock timeout", e)
            }
        }
        action.timeout = null
    }
}

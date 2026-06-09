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
 * Two responsibilities:
 *  1. Decide whether a Private Space item may be dragged/pinned to the home screen, based on
 *     the live unlock state of the Private Space ([canPinPrivateItem]).
 *  2. When a *locked* Private Space app on the home screen is tapped, show the system unlock
 *     prompt exactly once and launch the app afterwards ([requestUnlockThenRun]).
 *
 * Upstream Launcher3 files only contain tiny, marked call-sites that delegate here, which keeps
 * the fork conflict-free during upstream merges.
 *
 * Every system/binder call is wrapped in a try/catch with a safe fallback so the launcher never
 * crashes; when in doubt we fall back to the original (safe) behaviour.
 */
object PrivateSpaceHomeHelper {

    private const val TAG = "PrivateSpaceHomeHelper"

    /** Highly visible diagnostic tag so a clean logcat shows exactly what our unlock flow does. */
    private const val DBG = "PSChairUnlock"

    /** Safety timeout (ms) after which a pending unlock is abandoned (e.g. prompt dismissed). */
    private const val UNLOCK_TIMEOUT_MS = 60_000L

    /** Polling for "profile fully unlocked" as a fallback if the unlocked broadcast is missed. */
    private const val READY_POLL_INTERVAL_MS = 150L
    private const val READY_MAX_ATTEMPTS = 120 // ~18s worst case (CE unlock can take several seconds)

    /**
     * Guards against showing more than one credential prompt at a time. A second tap while an
     * unlock is already pending is ignored, which prevents duplicate pincode prompts.
     */
    private val unlockInProgress = AtomicBoolean(false)

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
     * ([UserProfileManager.STATE_ENABLED]). Used by the drag/pin gate-keepers, which run in the
     * All Apps context where this state is accurate.
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
     * Context-only overload of [isPrivateSpaceUnlocked] for call-sites that only have a [Context].
     * Resolves the [Launcher] via [Launcher.getLauncher] and returns false on any failure.
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
     * Central pinnability rule for Private Space items (drag/pin):
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
     * Returns true when [info] is a private-profile item whose profile is currently *locked*
     * (quiet mode on). This uses the authoritative [UserManager.isQuietModeEnabled] system state
     * rather than the cached PrivateProfileManager state, because on the home screen that cached
     * state can lag behind a just-completed unlock (which previously caused a second prompt).
     */
    fun isPrivateProfileLocked(context: Context, info: ItemInfo?): Boolean {
        val user = info?.user ?: return false
        return try {
            if (!isPrivateItem(context, info)) return false
            val userManager = context.getSystemService(UserManager::class.java)
            userManager != null && userManager.isQuietModeEnabled(user)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read quiet mode state", e)
            false
        }
    }

    /**
     * Shows the system unlock prompt for the locked Private Space profile of [info] exactly once,
     * and runs [onUnlocked] after the profile actually becomes available.
     *
     * Single-prompt guarantees:
     *  - [unlockInProgress] ignores additional taps while a prompt is already pending.
     *  - The caller's guard uses [isPrivateProfileLocked] (authoritative), so the post-unlock
     *    re-entry sees an unlocked profile and just launches instead of prompting again.
     *
     * Unlock detection uses [UserCache.addUserEventListener] (the codebase-native wrapper around
     * the profile-available broadcast). All listener mutations are posted on the main handler so
     * they never run during UserCache's own dispatch loop (avoids ConcurrentModificationException).
     */
    fun requestUnlockThenRun(launcher: Launcher, info: ItemInfo, onUnlocked: Runnable) {
        val user = info.user ?: return
        if (!isFeatureEnabled(launcher)) return
        Log.w(DBG, "requestUnlockThenRun: tap on locked private app, user=$user")
        // Only one credential prompt at a time -> no duplicate pincode prompts.
        if (!unlockInProgress.compareAndSet(false, true)) {
            Log.w(DBG, "requestUnlockThenRun: SKIP - an unlock is already in progress")
            return
        }
        Log.w(DBG, "requestUnlockThenRun: starting unlock flow (will request quiet mode = false)")

        val appContext = launcher.applicationContext
        val finished = AtomicBoolean(false)
        val listenerHolder = arrayOfNulls<SafeCloseable>(1)
        val timeoutHolder = arrayOfNulls<Runnable>(1)

        fun releaseResources() {
            listenerHolder[0]?.let { l ->
                // Defer the removal so it never mutates UserCache's listener list mid-dispatch.
                MAIN_EXECUTOR.handler.post {
                    try {
                        l.close()
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to close unlock listener", e)
                    }
                }
            }
            listenerHolder[0] = null
            timeoutHolder[0]?.let { MAIN_EXECUTOR.handler.removeCallbacks(it) }
            timeoutHolder[0] = null
        }

        fun finish(runAction: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            unlockInProgress.set(false)
            releaseResources()
            if (runAction) {
                MAIN_EXECUTOR.handler.post {
                    try {
                        onUnlocked.run()
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to run deferred Private Space app start", e)
                    }
                }
            }
        }

        // The credential prompt turns quiet mode OFF first, but the profile needs a brief moment
        // afterwards to become fully unlocked (CE storage / RUNNING_UNLOCKED). Launching the app
        // during that transitional window makes the platform show a SECOND credential prompt.
        // So we wait until the profile is fully unlocked before launching -> single prompt.
        val launchScheduled = AtomicBoolean(false)

        fun launchWhenFullyUnlocked(attempt: Int) {
            if (finished.get()) return
            val ready = try {
                val um = appContext.getSystemService(UserManager::class.java)
                um != null && !um.isQuietModeEnabled(user) && um.isUserUnlocked(user)
            } catch (e: Exception) {
                false
            }
            if (ready) {
                Log.w(DBG, "profile fully unlocked (poll) -> launching app (attempt=$attempt)")
                finish(runAction = true)
            } else if (attempt < READY_MAX_ATTEMPTS) {
                MAIN_EXECUTOR.handler.postDelayed(
                    { launchWhenFullyUnlocked(attempt + 1) },
                    READY_POLL_INTERVAL_MS,
                )
            } else {
                // Do NOT launch prematurely: launching before the profile is fully unlocked
                // (CE storage) makes the platform show a SECOND credential prompt. Give up quietly;
                // the authoritative unlocked-broadcast path will normally have launched already.
                Log.w(DBG, "profile never reported fully unlocked; not launching to avoid 2nd prompt")
            }
        }

        try {
            listenerHolder[0] = UserCache.getInstance(appContext)
                .addUserEventListener { changedUser, action ->
                    if (changedUser != user) return@addUserEventListener
                    when {
                        // Authoritative signal: profile is fully unlocked & accessible. Launching
                        // now does NOT trigger a second credential prompt.
                        isProfileUnlockedAction(action) -> {
                            MAIN_EXECUTOR.handler.post {
                                Log.w(DBG, "profile accessible/unlocked -> launching app")
                                finish(runAction = true)
                            }
                        }
                        // Quiet mode just turned off, but CE storage may not be unlocked yet.
                        // Start a fallback poll in case the unlocked broadcast doesn't reach us.
                        isProfileAvailableAction(action) -> {
                            if (!launchScheduled.getAndSet(true)) {
                                Log.w(DBG, "profile available -> waiting for full unlock")
                                MAIN_EXECUTOR.handler.post { launchWhenFullyUnlocked(0) }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to register Private Space unlock listener", e)
        }

        val timeout = Runnable { finish(runAction = false) }
        timeoutHolder[0] = timeout
        try {
            MAIN_EXECUTOR.handler.postDelayed(timeout, UNLOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to schedule unlock timeout", e)
        }

        try {
            val userManager = launcher.getSystemService(UserManager::class.java)
            // Real profile lock toggle: shows the system credential prompt when a lock is set.
            Log.w(DBG, "calling UserManager.requestQuietModeEnabled(false) -> system shows ONE prompt")
            userManager?.requestQuietModeEnabled(false, user)
        } catch (se: SecurityException) {
            // Launcher is not the default HOME app: reuse the existing platform pattern and bail.
            Log.d(TAG, "Missing HOME role for Private Space unlock", se)
            finish(runAction = false)
            try {
                ApiWrapper.INSTANCE.get(launcher).assignDefaultHomeRole(launcher)
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to request default HOME role", t)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to request Private Space unlock", e)
            finish(runAction = false)
        }
    }

    private fun isProfileAvailableAction(action: String?): Boolean {
        return action == UserCache.ACTION_PROFILE_AVAILABLE ||
            action == Intent.ACTION_MANAGED_PROFILE_AVAILABLE
    }

    /**
     * Authoritative "profile is fully unlocked & accessible" actions. These fire only after the
     * profile reaches RUNNING_UNLOCKED (CE storage unlocked), which is exactly when an app can be
     * launched into it without the platform showing a second credential prompt.
     */
    private fun isProfileUnlockedAction(action: String?): Boolean {
        return action == UserCache.ACTION_PROFILE_UNLOCKED || // ACTION_PROFILE_ACCESSIBLE on U+
            action == Intent.ACTION_MANAGED_PROFILE_UNLOCKED
    }
}

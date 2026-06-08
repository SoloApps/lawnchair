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
import android.util.Log
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.Launcher
import com.android.launcher3.allapps.UserProfileManager
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.pm.UserCache
import com.patrykmichalik.opto.core.firstBlocking

/**
 * Central decision point for the "Private Space on the home screen" feature.
 *
 * This helper decides whether a Private Space item may be dragged/pinned to the home
 * screen based on the live unlock state of the Private Space. Upstream Launcher3 files
 * only contain tiny, marked call-sites that delegate here, which keeps the fork
 * conflict-free during upstream merges.
 *
 * Launching a locked Private Space app from the home screen is intentionally NOT handled
 * here: stock Launcher3/Android already shows the system unlock prompt when such an app is
 * launched (see ItemClickHandler#handleDisabledItemClicked for FLAG_DISABLED_QUIET_USER).
 * Adding our own unlock request on top of that produced duplicate credential prompts, so we
 * rely on the platform's single, native prompt instead.
 *
 * Every system/binder call is wrapped in a try/catch with a safe fallback so the launcher
 * never crashes: when in doubt we fall back to the original (safe) behaviour where Private
 * Space items are not pinnable.
 */
object PrivateSpaceHomeHelper {

    private const val TAG = "PrivateSpaceHomeHelper"

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
}

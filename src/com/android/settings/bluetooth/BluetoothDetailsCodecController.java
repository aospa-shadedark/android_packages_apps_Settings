/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.settings.bluetooth;

import android.bluetooth.BluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.core.lifecycle.Lifecycle;

/** Hosts the A2DP codec chooser for a single paired device. */
public class BluetoothDetailsCodecController extends BluetoothDetailsController {
    private static final String KEY_BT_AUDIO_DEVICE_TYPE_GROUP =
            "bluetooth_audio_device_type_group";
    private static final String KEY_BT_AUDIO_CODEC = "bluetooth_audio_codec";
    private final LocalBluetoothProfileManager mProfileManager;
    @VisibleForTesting PreferenceGroup mCodecContainer;
    @Nullable private BluetoothCodecPreference mCodecPreference;
    private final BroadcastReceiver mCodecConfigReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refresh();
                }
            };

    public BluetoothDetailsCodecController(
            Context context,
            PreferenceFragmentCompat fragment,
            LocalBluetoothManager manager,
            CachedBluetoothDevice device,
            Lifecycle lifecycle) {
        super(context, fragment, device, lifecycle);
        mProfileManager = manager.getProfileManager();
    }

    @Override
    public boolean isAvailable() {
        A2dpProfile profile = mProfileManager.getA2dpProfile();
        return profile != null && profile.isEnabled(mCachedDevice.getDevice());
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(mCodecConfigReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onPause() {
        super.onPause();
        mContext.unregisterReceiver(mCodecConfigReceiver);
    }

    @Override
    public String getPreferenceKey() {
        return KEY_BT_AUDIO_DEVICE_TYPE_GROUP;
    }

    @Override
    protected void init(PreferenceScreen screen) {
        mCodecContainer = screen.findPreference(getPreferenceKey());
        refresh();
    }

    @Override
    protected void refresh() {
        if (mCodecContainer == null) return;
        if (mCodecPreference == null) {
            mCodecPreference = new BluetoothCodecPreference(mCodecContainer.getContext());
            mCodecPreference.setKey(KEY_BT_AUDIO_CODEC);
            mCodecPreference.configure(
                    mCachedDevice::getDevice, mProfileManager::getA2dpProfile, false);
            mCodecContainer.addPreference(mCodecPreference);
        }
        mCodecPreference.refresh();
    }

    @VisibleForTesting
    @Nullable
    ListPreference getCodecPreference() {
        return mCodecPreference;
    }
}

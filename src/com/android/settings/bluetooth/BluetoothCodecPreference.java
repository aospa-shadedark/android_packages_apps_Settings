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

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;

import androidx.preference.ListPreference;

import com.android.settings.R;
import com.android.settingslib.bluetooth.A2dpProfile;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Per-device A2DP codec chooser with validated connection capabilities. */
public final class BluetoothCodecPreference extends ListPreference {
    private static final String TAG = "BluetoothCodecPreference";
    private Supplier<BluetoothDevice> mDevice = () -> null;
    private Supplier<A2dpProfile> mProfile = () -> null;
    private boolean mRequireActiveDevice;
    private String mDisplayedAddress;
    private String mDialogAddress;

    public BluetoothCodecPreference(Context context) {
        this(context, null);
    }

    public BluetoothCodecPreference(Context context, AttributeSet attributes) {
        super(context, attributes);
        // This is a live connection preference, not an independently saved codec choice.
        setPersistent(false);
        setTitle(R.string.bluetooth_details_audio_codec_title);
        setDialogTitle(R.string.bluetooth_details_audio_codec_title);
        setOnPreferenceChangeListener((preference, value) -> select(value));
    }

    public void configure(
            Supplier<BluetoothDevice> device,
            Supplier<A2dpProfile> profile,
            boolean requireActiveDevice) {
        mDevice = device;
        mProfile = profile;
        mRequireActiveDevice = requireActiveDevice;
    }

    public void refresh() {
        try {
            BluetoothDevice device = mDevice.get();
            refresh(device, status(mProfile.get(), device));
        } catch (IllegalStateException | SecurityException e) {
            unavailable();
            Log.d(TAG, "Codec information unavailable during service transition", e);
        }
    }

    /** Bind the same selected-device snapshot used by the surrounding format fields. */
    public void refresh(BluetoothDevice device, BluetoothCodecStatus status) {
        mDisplayedAddress = device == null ? null : device.getAddress();
        LinkedHashMap<String, CharSequence> choices = new LinkedHashMap<>();
        if (status != null) {
            for (BluetoothCodecConfig config : status.getCodecsSelectableCapabilities()) {
                BluetoothCodecType type = codecType(config);
                if (type != null
                        && type.getCodecName() != null
                        && !type.getCodecName().isBlank()
                        && !type.getCodecName().startsWith("UNKNOWN CODEC")
                        && !type.getCodecName().equals("INVALID CODEC")) {
                    choices.put(Long.toString(type.getCodecId()), type.getCodecName());
                }
            }
        }
        setEntries(choices.values().toArray(new CharSequence[0]));
        setEntryValues(choices.keySet().toArray(new CharSequence[0]));
        setEnabled(!choices.isEmpty());
        BluetoothCodecType current =
                status == null || status.getCodecConfig() == null
                        ? null
                        : codecType(status.getCodecConfig());
        String id = current == null ? null : Long.toString(current.getCodecId());
        setValue(choices.containsKey(id) ? id : null);
        if (id != null && choices.containsKey(id)) {
            setSummary(choices.get(id));
        } else {
            setSummary(R.string.bluetooth_details_audio_codec_unavailable);
        }
    }

    @Override
    protected void onClick() {
        refresh();
        if (!isEnabled()) return;
        // Do not retarget an open dialog if the default output changes underneath it.
        mDialogAddress = mDisplayedAddress;
        super.onClick();
    }

    private boolean select(Object value) {
        if (!(value instanceof String) || mDialogAddress == null) return false;
        String dialogAddress = mDialogAddress;
        mDialogAddress = null;
        try {
            BluetoothDevice device = mDevice.get();
            if (device == null || !Objects.equals(dialogAddress, device.getAddress())) return false;
            A2dpProfile profile = mProfile.get();
            BluetoothCodecStatus status = status(profile, device);
            if (status == null) return false;
            for (BluetoothCodecConfig config : status.getCodecsSelectableCapabilities()) {
                BluetoothCodecType type = codecType(config);
                if (type != null && value.equals(Long.toString(type.getCodecId()))) {
                    profile.setCodecConfigPreference(
                            device,
                            new BluetoothCodecConfig.Builder()
                                    .setExtendedCodecType(type)
                                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                                    .build());
                    break;
                }
            }
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Unable to request the selected codec", e);
        }
        // Wait for the actual negotiated configuration before changing the displayed value.
        return false;
    }

    private BluetoothCodecStatus status(A2dpProfile profile, BluetoothDevice device) {
        if (profile == null
                || device == null
                || profile.getConnectionStatus(device) != BluetoothProfile.STATE_CONNECTED
                || (mRequireActiveDevice && !device.equals(profile.getActiveDevice()))) return null;
        return profile.getCodecStatus(device);
    }

    private static BluetoothCodecType codecType(BluetoothCodecConfig config) {
        BluetoothCodecType type = config.getExtendedCodecType();
        return type != null ? type : BluetoothCodecType.createFromType(config.getCodecType());
    }

    private void unavailable() {
        mDisplayedAddress = null;
        setEntries(new CharSequence[0]);
        setEntryValues(new CharSequence[0]);
        setValue(null);
        setEnabled(false);
        setSummary(R.string.bluetooth_details_audio_codec_unavailable);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState state = new SavedState(super.onSaveInstanceState());
        state.dialogAddress = mDialogAddress;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        SavedState saved = (SavedState) state;
        super.onRestoreInstanceState(saved.getSuperState());
        mDialogAddress = saved.dialogAddress;
    }

    public static final class SavedState extends BaseSavedState {
        String dialogAddress;

        SavedState(Parcelable state) {
            super(state);
        }

        SavedState(Parcel source) {
            super(source);
            dialogAddress = source.readString();
        }

        @Override
        public void writeToParcel(Parcel destination, int flags) {
            super.writeToParcel(destination, flags);
            destination.writeString(dialogAddress);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<>() {
                    @Override
                    public SavedState createFromParcel(Parcel source) {
                        return new SavedState(source);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }
}

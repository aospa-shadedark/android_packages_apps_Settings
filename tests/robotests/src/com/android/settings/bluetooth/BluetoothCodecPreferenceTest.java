/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.bluetooth.A2dpProfile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class BluetoothCodecPreferenceTest {
    private final A2dpProfile mProfile = mock(A2dpProfile.class);
    private final BluetoothDevice mFirst = mock(BluetoothDevice.class);
    private final BluetoothDevice mSecond = mock(BluetoothDevice.class);
    private final AtomicReference<BluetoothDevice> mCurrent = new AtomicReference<>();
    private final BluetoothCodecConfig mAac = config(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC);
    private final BluetoothCodecConfig mSbc = config(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC);
    private BluetoothCodecPreference mPreference;

    @Before
    public void setUp() {
        when(mFirst.getAddress()).thenReturn("00:11:22:33:44:55");
        when(mSecond.getAddress()).thenReturn("00:11:22:33:44:66");
        mCurrent.set(mFirst);
        when(mProfile.getActiveDevice()).thenReturn(mFirst);
        when(mProfile.getConnectionStatus(any())).thenReturn(BluetoothProfile.STATE_CONNECTED);
        when(mProfile.getCodecStatus(any())).thenReturn(status(mAac, List.of(mAac, mSbc)));
        mPreference = preference();
        mPreference.refresh();
    }

    @Test
    public void selection_waitsForBluetoothConfirmationBeforeChangingSummary() {
        mPreference.onClick();
        assertThat(mPreference.callChangeListener(id(mSbc))).isFalse();

        ArgumentCaptor<BluetoothCodecConfig> request =
                ArgumentCaptor.forClass(BluetoothCodecConfig.class);
        verify(mProfile).setCodecConfigPreference(eq(mFirst), request.capture());
        assertThat(request.getValue().getExtendedCodecType())
                .isEqualTo(mSbc.getExtendedCodecType());
        assertThat(mPreference.getValue()).isEqualTo(id(mAac));

        when(mProfile.getCodecStatus(mFirst)).thenReturn(status(mSbc, List.of(mAac, mSbc)));
        mPreference.refresh();
        assertThat(mPreference.getValue()).isEqualTo(id(mSbc));
    }

    @Test
    public void outputChangedWithDialogOpen_doesNotRetargetSelection() {
        mPreference.onClick();
        mCurrent.set(mSecond);
        when(mProfile.getActiveDevice()).thenReturn(mSecond);
        mPreference.refresh();

        mPreference.callChangeListener(id(mSbc));

        verify(mProfile, never()).setCodecConfigPreference(any(), any());
    }

    @Test
    public void codecNoLongerSelectable_isNotRequested() {
        mPreference.onClick();
        when(mProfile.getCodecStatus(mFirst)).thenReturn(status(mAac, List.of(mAac)));

        mPreference.callChangeListener(id(mSbc));

        verify(mProfile, never()).setCodecConfigPreference(any(), any());
    }

    @Test
    public void serviceDisconnected_clearsStaleValueAndDisablesControl() {
        when(mProfile.getConnectionStatus(mFirst)).thenReturn(BluetoothProfile.STATE_DISCONNECTED);

        mPreference.refresh();

        assertThat(mPreference.isEnabled()).isFalse();
        assertThat(mPreference.getValue()).isNull();
        assertThat(mPreference.getEntries()).isEmpty();
    }

    @Test
    public void dialogTargetSurvivesParcelRestore_andStillRejectsAnotherDevice() {
        mPreference.onClick();
        Parcel parcel = Parcel.obtain();
        Parcelable saved = mPreference.onSaveInstanceState();
        saved.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        Parcelable restored = BluetoothCodecPreference.SavedState.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        BluetoothCodecPreference replacement = preference();
        replacement.onRestoreInstanceState(restored);
        mCurrent.set(mSecond);
        when(mProfile.getActiveDevice()).thenReturn(mSecond);
        replacement.refresh();

        replacement.callChangeListener(id(mSbc));

        verify(mProfile, never()).setCodecConfigPreference(any(), any());
    }

    private BluetoothCodecPreference preference() {
        Context context = RuntimeEnvironment.application;
        PreferenceManager manager = new PreferenceManager(context);
        PreferenceScreen screen = manager.createPreferenceScreen(context);
        BluetoothCodecPreference preference = new BluetoothCodecPreference(context);
        preference.setKey("bluetooth_audio_codec");
        preference.configure(mCurrent::get, () -> mProfile, true);
        screen.addPreference(preference);
        return preference;
    }

    private static BluetoothCodecConfig config(int type) {
        return new BluetoothCodecConfig.Builder()
                .setExtendedCodecType(BluetoothCodecType.createFromType(type))
                .build();
    }

    private static BluetoothCodecStatus status(
            BluetoothCodecConfig current, List<BluetoothCodecConfig> choices) {
        return new BluetoothCodecStatus(current, choices, choices);
    }

    private static String id(BluetoothCodecConfig config) {
        return Long.toString(config.getExtendedCodecType().getCodecId());
    }
}

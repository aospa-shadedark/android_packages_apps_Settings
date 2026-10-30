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
package com.android.settings.sound;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecType;
import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioFormat;
import android.media.AudioProfile;

import com.android.settings.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
public class AudioOutputInfoTest {
    private Context mContext;

    @Before
    public void setUp() {
        Context app = RuntimeEnvironment.application;
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocale(Locale.US);
        mContext = app.createConfigurationContext(config);
    }

    @Test
    public void capabilityMasks_areNotShownAsNegotiatedFormats() {
        int rates = BluetoothCodecConfig.SAMPLE_RATE_44100 | BluetoothCodecConfig.SAMPLE_RATE_96000;
        int depths =
                BluetoothCodecConfig.BITS_PER_SAMPLE_16 | BluetoothCodecConfig.BITS_PER_SAMPLE_24;
        String unreported = mContext.getString(R.string.audio_output_unreported);

        assertThat(AudioOutputInfo.codecRates(mContext, rates, true)).isEqualTo(unreported);
        assertThat(AudioOutputInfo.codecDepths(mContext, depths, true)).isEqualTo(unreported);
        assertThat(AudioOutputInfo.codecRates(mContext, rates, false))
                .isEqualTo("44.1 kHz, 96 kHz");
        assertThat(AudioOutputInfo.codecDepths(mContext, depths, false))
                .isEqualTo("16-bit, 24-bit");
    }

    @Test
    public void usbProfiles_keepRatesAndChannelsAttachedToTheirOwnFormat() {
        AudioProfile stereo16 =
                new AudioProfile(
                        AudioFormat.ENCODING_PCM_16BIT,
                        new int[] {44100, 48000},
                        new int[] {AudioFormat.CHANNEL_OUT_STEREO},
                        new int[0],
                        AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE);
        AudioProfile mono24 =
                new AudioProfile(
                        AudioFormat.ENCODING_PCM_24BIT_PACKED,
                        new int[] {192000},
                        new int[] {AudioFormat.CHANNEL_OUT_MONO},
                        new int[0],
                        AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE);

        String first = AudioOutputInfo.profileSummary(mContext, stereo16);
        String second = AudioOutputInfo.profileSummary(mContext, mono24);

        assertThat(first).contains("44.1 kHz, 48 kHz");
        assertThat(first).contains("Channel counts: 2");
        assertThat(first).doesNotContain("192 kHz");
        assertThat(second).contains("192 kHz");
        assertThat(second).contains("Channel counts: 1");
        assertThat(second).doesNotContain("48 kHz");
    }

    @Test
    public void emptyDriverProfiles_doNotInventRateOrDepth() {
        AudioProfile profile =
                new AudioProfile(
                        AudioFormat.ENCODING_PCM_FLOAT,
                        new int[0],
                        new int[0],
                        new int[0],
                        AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE);

        assertThat(AudioOutputInfo.profileSummary(mContext, profile))
                .contains(mContext.getString(R.string.audio_output_driver_unspecified));
        assertThat(AudioOutputInfo.encoding(mContext, profile.getFormat())).isEqualTo("PCM float");
        assertThat(AudioOutputInfo.profileSummary(mContext, profile)).doesNotContain("96 kHz");
    }

    @Test
    public void vendorCodec_usesExtendedIdentityWithInvalidLegacyIndex() {
        BluetoothCodecConfig config =
                new BluetoothCodecConfig.Builder()
                        .setExtendedCodecType(
                                new BluetoothCodecType(
                                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID,
                                        BluetoothCodecType.CODEC_ID_LHDCV5,
                                        "LHDCv5"))
                        .build();

        assertThat(AudioOutputInfo.codecName(mContext, config)).isEqualTo("LHDCv5");
    }
}

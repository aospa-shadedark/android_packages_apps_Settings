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

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecType;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioProfile;

import com.android.settings.R;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Formats reported values without turning capability masks into active formats. */
final class AudioOutputInfo {
    private static final int[] RATE_BITS = {
        BluetoothCodecConfig.SAMPLE_RATE_8000, BluetoothCodecConfig.SAMPLE_RATE_16000,
        BluetoothCodecConfig.SAMPLE_RATE_24000, BluetoothCodecConfig.SAMPLE_RATE_32000,
        BluetoothCodecConfig.SAMPLE_RATE_44100, BluetoothCodecConfig.SAMPLE_RATE_48000,
        BluetoothCodecConfig.SAMPLE_RATE_88200, BluetoothCodecConfig.SAMPLE_RATE_96000,
        BluetoothCodecConfig.SAMPLE_RATE_176400, BluetoothCodecConfig.SAMPLE_RATE_192000
    };
    private static final int[] RATES = {
        8000, 16000, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000
    };

    private AudioOutputInfo() {}

    static String codecName(Context context, BluetoothCodecConfig config) {
        BluetoothCodecType type = config.getExtendedCodecType();
        String name = type == null ? null : type.getCodecName();
        if (name == null || name.isBlank()) {
            type = BluetoothCodecType.createFromType(config.getCodecType());
            name = type == null ? null : type.getCodecName();
        }
        return name == null
                        || name.isBlank()
                        || name.startsWith("UNKNOWN CODEC")
                        || name.equals("INVALID CODEC")
                ? context.getString(R.string.audio_output_unreported)
                : name.trim();
    }

    static String codecRates(Context context, int mask, boolean current) {
        if (current && Integer.bitCount(mask) != 1) return unknown(context);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < RATE_BITS.length; i++) {
            if ((mask & RATE_BITS[i]) != 0) values.add(khz(context, RATES[i]));
        }
        return values.isEmpty() ? unknown(context) : String.join(", ", values);
    }

    static String codecDepths(Context context, int mask, boolean current) {
        if (current && Integer.bitCount(mask) != 1) return unknown(context);
        List<String> values = new ArrayList<>();
        int[] bits = {
            BluetoothCodecConfig.BITS_PER_SAMPLE_16,
            BluetoothCodecConfig.BITS_PER_SAMPLE_24,
            BluetoothCodecConfig.BITS_PER_SAMPLE_32
        };
        int[] depths = {16, 24, 32};
        for (int i = 0; i < bits.length; i++) {
            if ((mask & bits[i]) != 0) {
                values.add(context.getString(R.string.audio_output_bits, depths[i]));
            }
        }
        return values.isEmpty() ? unknown(context) : String.join(", ", values);
    }

    static String codecChannels(Context context, int mask, boolean current) {
        if (current && Integer.bitCount(mask) != 1) return unknown(context);
        List<String> values = new ArrayList<>();
        if ((mask & BluetoothCodecConfig.CHANNEL_MODE_MONO) != 0) {
            values.add(context.getString(R.string.audio_output_mono));
        }
        if ((mask & BluetoothCodecConfig.CHANNEL_MODE_STEREO) != 0) {
            values.add(context.getString(R.string.audio_output_stereo));
        }
        return values.isEmpty() ? unknown(context) : String.join(", ", values);
    }

    static String profileSummary(Context context, AudioProfile profile) {
        TreeSet<Integer> channels = new TreeSet<>();
        for (int mask : profile.getChannelMasks()) {
            int count = AudioFormat.channelCountFromOutChannelMask(mask);
            if (count > 0) channels.add(count);
        }
        for (int mask : profile.getChannelIndexMasks()) {
            if (mask != 0) channels.add(Integer.bitCount(mask));
        }
        List<String> counts = new ArrayList<>();
        NumberFormat numbers =
                NumberFormat.getIntegerInstance(
                        context.getResources().getConfiguration().getLocales().get(0));
        for (int count : channels) counts.add(numbers.format(count));
        TreeSet<Integer> rates = new TreeSet<>();
        for (int rate : profile.getSampleRates()) if (rate > 0) rates.add(rate);
        List<String> rateLabels = new ArrayList<>();
        for (int rate : rates) rateLabels.add(khz(context, rate));
        String unspecified = context.getString(R.string.audio_output_driver_unspecified);
        String summary =
                context.getString(
                        R.string.audio_output_profile_summary,
                        rateLabels.isEmpty() ? unspecified : String.join(", ", rateLabels),
                        counts.isEmpty() ? unspecified : String.join(", ", counts));
        String encapsulation;
        switch (profile.getEncapsulationType()) {
            case AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE:
                encapsulation = context.getString(R.string.audio_output_encapsulation_none);
                break;
            case AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937:
                encapsulation = "IEC 61937";
                break;
            case AudioProfile.AUDIO_ENCAPSULATION_TYPE_PCM:
                encapsulation = "PCM";
                break;
            default:
                encapsulation = unknown(context);
        }
        return context.getString(R.string.audio_output_encapsulation, summary, encapsulation);
    }

    static String encoding(Context context, int encoding) {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                return context.getString(R.string.audio_output_pcm_bits, 8);
            case AudioFormat.ENCODING_PCM_16BIT:
                return context.getString(R.string.audio_output_pcm_bits, 16);
            case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                return context.getString(R.string.audio_output_pcm_bits, 24);
            case AudioFormat.ENCODING_PCM_32BIT:
                return context.getString(R.string.audio_output_pcm_bits, 32);
            case AudioFormat.ENCODING_PCM_FLOAT:
                return context.getString(R.string.audio_output_pcm_float);
            default:
                String name = AudioFormat.toLogFriendlyEncoding(encoding);
                return name.startsWith("ENCODING_")
                        ? name.substring("ENCODING_".length()).replace('_', ' ')
                        : context.getString(R.string.audio_output_format_id, encoding);
        }
    }

    static boolean isUsb(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    static boolean isBluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_HEARING_AID
                || type == AudioDeviceInfo.TYPE_BLE_HEARING_AID
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    static int transport(int type) {
        if (isUsb(type)) return R.string.audio_output_usb;
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return R.string.audio_output_bluetooth;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return R.string.audio_output_calls;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return R.string.audio_output_le;
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_BLE_HEARING_AID:
                return R.string.audio_output_hearing_aid;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
                return R.string.audio_output_wired;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE:
                return R.string.audio_output_speaker;
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return R.string.audio_output_hdmi;
            default:
                return R.string.audio_output_other;
        }
    }

    private static String khz(Context context, int rate) {
        NumberFormat number =
                NumberFormat.getNumberInstance(
                        context.getResources().getConfiguration().getLocales().get(0));
        number.setMaximumFractionDigits(3);
        return context.getString(R.string.audio_output_profile_rates, number.format(rate / 1000.0));
    }

    private static String unknown(Context context) {
        return context.getString(R.string.audio_output_unreported);
    }
}

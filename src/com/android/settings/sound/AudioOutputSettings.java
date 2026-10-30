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

import android.app.ActivityManager;
import android.app.settings.SettingsEnums;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.AudioProfile;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.MediaOutputConstants;
import com.android.settingslib.search.SearchIndexable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/** Live diagnostics and existing audio controls for the default media output. */
@SearchIndexable
public class AudioOutputSettings extends DashboardFragment {
    private static final String TAG = "AudioOutputSettings";
    private static final ComponentName APTX_PREFERENCES =
            new ComponentName(
                    "com.qualcomm.qtil.aptxacu",
                    "com.qualcomm.qtil.aptxacu.aptxacuSettingsActivity");
    private static final AudioAttributes MEDIA =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mResumed;
    private final Runnable mRefresh =
            () -> {
                if (mResumed) refresh();
            };
    private AudioManager mAudioManager;
    private LocalBluetoothManager mBluetoothManager;
    private String mReport = "";

    private final AudioManager.OnDevicesForAttributesChangedListener mRoutingListener =
            (attributes, devices) -> scheduleRefresh();
    private final AudioDeviceCallback mDeviceCallback =
            new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
                    scheduleRefresh();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                    scheduleRefresh();
                }
            };
    private final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    scheduleRefresh();
                }
            };
    private final LocalBluetoothProfileManager.ServiceListener mServiceListener =
            new LocalBluetoothProfileManager.ServiceListener() {
                @Override
                public void onServiceConnected() {
                    scheduleRefresh();
                }

                @Override
                public void onServiceDisconnected() {
                    scheduleRefresh();
                }
            };

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.audio_output_settings;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.SOUND;
    }

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        super.onCreatePreferences(state, rootKey);
        mAudioManager = requireContext().getSystemService(AudioManager.class);
        mBluetoothManager =
                com.android.settings.bluetooth.Utils.getLocalBtManager(requireContext());
        PreferenceCategory capabilities = findPreference("audio_output_capabilities");
        PreferenceCategory mixers = findPreference("audio_output_mixers");
        capabilities.setInitialExpandedChildrenCount(0);
        mixers.setInitialExpandedChildrenCount(0);
        findPreference("audio_output_device_settings")
                .setOnPreferenceClickListener(
                        preference -> {
                            openDeviceSettings();
                            return true;
                        });
        findPreference("audio_output_aptx_preferences")
                .setOnPreferenceClickListener(
                        preference -> {
                            openAptxPreferences();
                            return true;
                        });
        findPreference("audio_output_switch")
                .setOnPreferenceClickListener(
                        preference -> {
                            Intent intent =
                                    new Intent(
                                                    MediaOutputConstants
                                                            .ACTION_LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG)
                                            .setPackage(MediaOutputConstants.SYSTEMUI_PACKAGE_NAME);
                            if (com.android.media.flags.Flags.fixOutputSwitcherMultiuserSupport()) {
                                intent.putExtra(
                                        MediaOutputConstants.EXTRA_USER_HANDLE,
                                        requireContext().getUser());
                                requireContext().sendBroadcastAsUser(intent, UserHandle.SYSTEM);
                            } else {
                                requireContext().sendBroadcast(intent);
                            }
                            return true;
                        });
        findPreference("audio_output_copy")
                .setOnPreferenceClickListener(
                        preference -> {
                            refresh();
                            requireContext()
                                    .getSystemService(ClipboardManager.class)
                                    .setPrimaryClip(
                                            ClipData.newPlainText(
                                                    getString(R.string.audio_output_report_label),
                                                    mReport));
                            return true;
                        });
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        mResumed = true;
        mAudioManager.registerAudioDeviceCallback(mDeviceCallback, mHandler);
        mAudioManager.addOnDevicesForAttributesChangedListener(
                MEDIA, requireContext().getMainExecutor(), mRoutingListener);
        IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        requireContext().registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        if (mBluetoothManager != null) {
            mBluetoothManager.getProfileManager().addServiceListener(mServiceListener);
        }
        refresh();
    }

    @Override
    public void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mRefresh);
        mAudioManager.unregisterAudioDeviceCallback(mDeviceCallback);
        mAudioManager.removeOnDevicesForAttributesChangedListener(mRoutingListener);
        requireContext().unregisterReceiver(mReceiver);
        if (mBluetoothManager != null) {
            mBluetoothManager.getProfileManager().removeServiceListener(mServiceListener);
        }
        super.onPause();
    }

    private void scheduleRefresh() {
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
    }

    private void refresh() {
        Context context = requireContext();
        List<AudioDeviceInfo> outputs = mAudioManager.getAudioDevicesForAttributes(MEDIA);
        Preference devicePreference = findPreference("audio_output_device");
        PreferenceCategory capabilities = findPreference("audio_output_capabilities");
        PreferenceCategory mixers = findPreference("audio_output_mixers");
        Preference note = findPreference("audio_output_format_note");
        capabilities.removeAll();
        mixers.removeAll();
        mixers.setVisible(false);
        for (String key :
                new String[] {
                    "audio_output_codec",
                    "audio_output_rate",
                    "audio_output_depth",
                    "audio_output_channels"
                }) {
            Preference value = findPreference(key);
            value.setVisible(false);
            value.setSummary(R.string.audio_output_unreported);
        }
        findPreference("audio_output_codec_help").setVisible(false);
        findPreference("audio_output_device_settings").setVisible(false);
        findPreference("audio_output_aptx_preferences").setVisible(false);
        findPreference("audio_output_copy").setEnabled(!outputs.isEmpty());
        StringBuilder report =
                new StringBuilder(getString(R.string.audio_output_title)).append('\n');
        if (outputs.size() != 1) {
            devicePreference.setTitle(
                    outputs.isEmpty()
                            ? R.string.audio_output_none
                            : R.string.audio_output_multiple);
            List<String> names = new ArrayList<>();
            for (AudioDeviceInfo output : outputs) names.add(output.getProductName().toString());
            devicePreference.setSummary(String.join(", ", names));
            capabilities.setVisible(false);
            note.setVisible(true);
            note.setSummary(
                    outputs.isEmpty()
                            ? R.string.audio_output_no_active_format
                            : R.string.audio_output_multiple_note);
            report.append(devicePreference.getTitle())
                    .append('\n')
                    .append(devicePreference.getSummary())
                    .append('\n');
        } else {
            AudioDeviceInfo output = outputs.get(0);
            BluetoothDevice bluetoothDevice = bluetoothDevice(output);
            CachedBluetoothDevice cached =
                    bluetoothDevice == null
                            ? null
                            : mBluetoothManager
                                    .getCachedDeviceManager()
                                    .findDevice(bluetoothDevice);
            devicePreference.setTitle(cached == null ? output.getProductName() : cached.getName());
            devicePreference.setSummary(AudioOutputInfo.transport(output.getType()));
            report.append(devicePreference.getTitle())
                    .append('\n')
                    .append(devicePreference.getSummary())
                    .append('\n');
            findPreference("audio_output_device_settings").setVisible(bluetoothDevice != null);
            boolean a2dp = output.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
            BluetoothCodecStatus status = a2dp ? codecStatus(bluetoothDevice) : null;
            findPreference("audio_output_aptx_preferences")
                    .setVisible(supportsAptx(status) && aptxPreferencesIntent() != null);
            BluetoothCodecConfig config = status == null ? null : status.getCodecConfig();
            if (a2dp) {
                findPreference("audio_output_codec_help").setVisible(true);
                String unknown = getString(R.string.audio_output_unreported);
                setValue(
                        "audio_output_codec",
                        config == null ? unknown : AudioOutputInfo.codecName(context, config),
                        report);
                setValue(
                        "audio_output_rate",
                        config == null
                                ? unknown
                                : AudioOutputInfo.codecRates(context, config.getSampleRate(), true),
                        report);
                setValue(
                        "audio_output_depth",
                        config == null
                                ? unknown
                                : AudioOutputInfo.codecDepths(
                                        context, config.getBitsPerSample(), true),
                        report);
                setValue(
                        "audio_output_channels",
                        config == null
                                ? unknown
                                : AudioOutputInfo.codecChannels(
                                        context, config.getChannelMode(), true),
                        report);
            }
            note.setVisible(config == null);
            note.setSummary(R.string.audio_output_no_active_format);
            capabilities.setTitle(
                    a2dp
                            ? R.string.audio_output_supported_codecs
                            : R.string.audio_output_supported_profiles);
            if (a2dp && status != null) {
                for (BluetoothCodecConfig selectable : status.getCodecsSelectableCapabilities()) {
                    addInfo(
                            capabilities,
                            AudioOutputInfo.codecName(context, selectable),
                            getString(
                                    R.string.audio_output_codec_capabilities,
                                    AudioOutputInfo.codecRates(
                                            context, selectable.getSampleRate(), false),
                                    AudioOutputInfo.codecDepths(
                                            context, selectable.getBitsPerSample(), false),
                                    AudioOutputInfo.codecChannels(
                                            context, selectable.getChannelMode(), false)),
                            report);
                }
            } else if (!AudioOutputInfo.isBluetooth(output.getType())) {
                for (AudioProfile profile : output.getAudioProfiles()) {
                    addInfo(
                            capabilities,
                            AudioOutputInfo.encoding(context, profile.getFormat()),
                            AudioOutputInfo.profileSummary(context, profile),
                            report);
                }
            }
            capabilities.setVisible(capabilities.getPreferenceCount() > 0);
            if (AudioOutputInfo.isUsb(output.getType())) {
                for (AudioMixerAttributes mixer :
                        mAudioManager.getSupportedMixerAttributes(output)) {
                    AudioFormat f = mixer.getFormat();
                    NumberFormat numbers =
                            NumberFormat.getIntegerInstance(
                                    getResources().getConfiguration().getLocales().get(0));
                    addInfo(
                            mixers,
                            getString(
                                    mixer.getMixerBehavior()
                                                    == AudioMixerAttributes
                                                            .MIXER_BEHAVIOR_BIT_PERFECT
                                            ? R.string.audio_output_mixer_bitperfect
                                            : R.string.audio_output_mixer_default),
                            getString(
                                    R.string.audio_output_mixer_format,
                                    AudioOutputInfo.encoding(context, f.getEncoding()),
                                    numbers.format(f.getSampleRate()),
                                    numbers.format(f.getChannelCount())),
                            report);
                }
                mixers.setVisible(mixers.getPreferenceCount() > 0);
            }
        }
        mReport =
                report.append('\n')
                        .append(getString(R.string.audio_output_report_notes))
                        .toString();
    }

    private void setValue(String key, String value, StringBuilder report) {
        Preference preference = findPreference(key);
        preference.setVisible(true);
        preference.setSummary(value);
        report.append(preference.getTitle()).append(": ").append(value).append('\n');
    }

    private void addInfo(
            PreferenceCategory category, String title, String summary, StringBuilder report) {
        Preference preference = new Preference(getPrefContext());
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        category.addPreference(preference);
        report.append(category.getTitle())
                .append(" — ")
                .append(title)
                .append('\n')
                .append(summary)
                .append('\n');
    }

    private BluetoothDevice bluetoothDevice(AudioDeviceInfo output) {
        if (!AudioOutputInfo.isBluetooth(output.getType()) || mBluetoothManager == null)
            return null;
        BluetoothManager manager = requireContext().getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(output.getAddress()))
            return null;
        BluetoothDevice device = adapter.getRemoteDevice(output.getAddress());
        return mBluetoothManager.getCachedDeviceManager().findDevice(device) == null
                ? null
                : device;
    }

    private BluetoothCodecStatus codecStatus(BluetoothDevice device) {
        if (device == null || mBluetoothManager == null) return null;
        A2dpProfile profile = mBluetoothManager.getProfileManager().getA2dpProfile();
        try {
            if (profile == null
                    || !device.equals(profile.getActiveDevice())
                    || profile.getConnectionStatus(device) != BluetoothProfile.STATE_CONNECTED)
                return null;
            return profile.getCodecStatus(device);
        } catch (IllegalStateException | SecurityException e) {
            Log.d(TAG, "Bluetooth format unavailable during service transition", e);
            return null;
        }
    }

    private boolean supportsAptx(BluetoothCodecStatus status) {
        if (status == null) return false;
        for (BluetoothCodecConfig config : status.getCodecsSelectableCapabilities()) {
            BluetoothCodecType type = config.getExtendedCodecType();
            if (type == null) type = BluetoothCodecType.createFromType(config.getCodecType());
            if (type == null) continue;
            long id = type.getCodecId();
            if (id == BluetoothCodecType.CODEC_ID_APTX
                    || id == BluetoothCodecType.CODEC_ID_APTX_HD
                    || id == BluetoothCodecType.CODEC_ID_APTX_AD) {
                return true;
            }
        }
        return false;
    }

    private Intent aptxPreferencesIntent() {
        // Use the same vendor activity as Connection preferences, without copying its controls.
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(APTX_PREFERENCES);
        ActivityInfo activity =
                intent.resolveActivityInfo(
                        requireContext().getPackageManager(), PackageManager.MATCH_SYSTEM_ONLY);
        if (activity == null
                || !activity.enabled
                || !activity.applicationInfo.enabled
                || !activity.applicationInfo.isSystemApp()
                || ActivityManager.checkComponentPermission(
                                activity.permission,
                                Process.myUid(),
                                activity.applicationInfo.uid,
                                activity.exported)
                        != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        return intent;
    }

    private void openAptxPreferences() {
        // Check the current route, capabilities and activity again when tapped.
        List<AudioDeviceInfo> outputs = mAudioManager.getAudioDevicesForAttributes(MEDIA);
        BluetoothCodecStatus status =
                outputs.size() == 1
                                && outputs.get(0).getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        ? codecStatus(bluetoothDevice(outputs.get(0)))
                        : null;
        Intent intent = aptxPreferencesIntent();
        if (supportsAptx(status) && intent != null) {
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "aptX preferences are no longer available", e);
                refresh();
            }
        } else {
            refresh();
        }
    }

    private void openDeviceSettings() {
        // Resolve again at click time; never open controls for a device that was unplugged.
        List<AudioDeviceInfo> outputs = mAudioManager.getAudioDevicesForAttributes(MEDIA);
        BluetoothDevice device = outputs.size() == 1 ? bluetoothDevice(outputs.get(0)) : null;
        if (device == null) return;
        Bundle args = new Bundle();
        args.putString("device_address", device.getAddress());
        startActivity(
                new Intent("com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS")
                        .setPackage(requireContext().getPackageName())
                        .putExtra(":settings:show_fragment_args", args));
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.audio_output_settings) {
                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    // Search the hub and its persistent options, not route-dependent actions
                    // or live values which may be absent when the result is opened.
                    keys.addAll(
                            List.of(
                                    "audio_output_device",
                                    "audio_output_codec",
                                    "audio_output_rate",
                                    "audio_output_depth",
                                    "audio_output_channels",
                                    "audio_output_codec_help",
                                    "audio_output_format_note",
                                    "audio_output_aptx_preferences",
                                    "audio_output_device_settings",
                                    "audio_output_capabilities",
                                    "audio_output_mixers",
                                    "audio_output_copy",
                                    "audio_output_footer"));
                    return keys;
                }
            };
}

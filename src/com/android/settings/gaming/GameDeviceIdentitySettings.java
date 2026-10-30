/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.gaming;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.settings.SettingsEnums;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.widget.FooterPreference;
import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.MainSwitchPreference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Per-game Build identity profiles, applied by ActivityThread when each game starts. */
public class GameDeviceIdentitySettings extends SettingsPreferenceFragment {
    private static final String TAG = "GameDeviceIdentity";
    private static final String SETTING_GAME_IDENTITIES_ENABLED = "game_device_identity_enabled";
    private static final String SETTING_GAME_IDENTITIES = "game_device_identity_profiles";
    static final String VALUE_NONE = "none";
    static final String VALUE_CUSTOM = "custom_json";
    static final String[] PROFILE_VALUES = {
            VALUE_NONE, "pixel_6_pro", "pixel_7_pro", "pixel_8_pro", "pixel_9_pro"
    };
    private static final String[] BUILD_FIELDS = {
            "BRAND", "DEVICE", "MANUFACTURER", "MODEL", "FINGERPRINT", "PRODUCT"
    };
    private static final int REQUEST_GAME_PROFILE_JSON = 7412;
    private static final int MAX_GAME_PROFILE_JSON_BYTES = 256 * 1024;
    private static final int MAX_IMPORTED_PACKAGES = 512;
    private static final String PROFILE_DIALOG_TAG = "game_identity_profile";

    private MainSwitchPreference mEnabled;
    private PreferenceCategory mGames;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Context context = getPrefContext();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        final LayoutPreference intro = new LayoutPreference(context,
                R.layout.game_device_identity_intro);
        intro.setSelectable(false);
        screen.addPreference(intro);

        mEnabled = new MainSwitchPreference(context);
        mEnabled.setKey(SETTING_GAME_IDENTITIES_ENABLED);
        mEnabled.setPersistent(false);
        mEnabled.setTitle(R.string.game_device_identity_enable_title);
        mEnabled.setIconSpaceReserved(false);
        mEnabled.setOnPreferenceChangeListener((preference, value) -> {
            final boolean enabled = Boolean.TRUE.equals(value);
            if (!Settings.System.putIntForUser(context.getContentResolver(),
                    SETTING_GAME_IDENTITIES_ENABLED, enabled ? 1 : 0, UserHandle.myUserId())) {
                Toast.makeText(context, R.string.game_device_identity_save_error,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            restartGamePackages(context, () -> readIdentities(context).keys(), true);
            updateGamePreferences();
            return true;
        });
        screen.addPreference(mEnabled);

        final PreferenceCategory profiles = new PreferenceCategory(context);
        profiles.setTitle(R.string.game_device_identity_profiles_title);
        screen.addPreference(profiles);

        final Preference importProfiles = new Preference(context);
        importProfiles.setKey("import_game_identity_profiles");
        importProfiles.setTitle(R.string.game_device_identity_import_title);
        importProfiles.setSummary(R.string.game_device_identity_import_summary);
        importProfiles.setIcon(R.drawable.ic_upload);
        importProfiles.setWidgetLayoutResource(R.layout.game_device_identity_chevron);
        importProfiles.setOnPreferenceClickListener(preference -> {
            final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent, REQUEST_GAME_PROFILE_JSON);
            return true;
        });
        profiles.addPreference(importProfiles);

        mGames = new PreferenceCategory(context);
        mGames.setTitle(R.string.game_device_identity_games_title);
        screen.addPreference(mGames);

        final FooterPreference footer = new FooterPreference(context);
        footer.setTitle(R.string.game_device_identity_footer);
        screen.addPreference(footer);

        getChildFragmentManager().setFragmentResultListener(
                GameDeviceIdentityProfileDialog.RESULT_KEY, this, (key, result) -> {
                    final String packageName = result.getString(
                            GameDeviceIdentityProfileDialog.RESULT_PACKAGE, "");
                    final String profile = result.getString(
                            GameDeviceIdentityProfileDialog.RESULT_PROFILE, VALUE_NONE);
                    if (!isValidPackageName(packageName)
                            || !saveProfile(context, packageName, profile)) {
                        Toast.makeText(context, R.string.game_device_identity_save_error,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    restartGamePackages(context, Collections.singleton(packageName));
                    updateGamePreferences();
                });
        updateGamePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateGamePreferences();
    }

    private void updateGamePreferences() {
        if (mGames == null || getContext() == null) return;

        final Context context = getPrefContext();
        final boolean enabled = isGameIdentityEnabled(context);
        mEnabled.setChecked(enabled);
        mEnabled.setSummary(enabled ? R.string.game_device_identity_enabled_summary
                : R.string.game_device_identity_enable_summary);
        mGames.removeAll();

        final List<ApplicationInfo> installedGames = getInstalledGames(context.getPackageManager());
        if (installedGames.isEmpty()) {
            final LayoutPreference empty = new LayoutPreference(context,
                    R.layout.game_device_identity_empty);
            empty.setSelectable(false);
            mGames.addPreference(empty);
            return;
        }

        final JSONObject identities = readIdentities(context);
        for (ApplicationInfo app : installedGames) {
            final Preference preference = new Preference(context);
            final String packageName = app.packageName;
            final CharSequence label = context.getPackageManager().getApplicationLabel(app);
            preference.setKey("game_identity_" + packageName);
            preference.setPersistent(false);
            preference.setTitle(label);
            preference.setIcon(context.getPackageManager().getApplicationIcon(app));
            preference.setWidgetLayoutResource(R.layout.game_device_identity_chevron);

            final JSONObject customProfile = identities.optJSONObject(packageName);
            final String currentProfile = identities.optString(packageName, VALUE_NONE);
            final String selectedProfile;
            final CharSequence currentSummary;
            if (customProfile != null) {
                currentSummary = getCustomProfileSummary(context, customProfile);
                selectedProfile = VALUE_CUSTOM;
            } else {
                selectedProfile = isKnownProfile(currentProfile) ? currentProfile : VALUE_NONE;
                currentSummary = getProfileSummary(context, currentProfile);
            }
            preference.setSummary(!enabled && !VALUE_NONE.equals(selectedProfile)
                    ? context.getString(R.string.game_device_identity_profile_paused, currentSummary)
                    : currentSummary);
            preference.setOnPreferenceClickListener(clicked -> {
                if (getChildFragmentManager().findFragmentByTag(PROFILE_DIALOG_TAG) == null) {
                    GameDeviceIdentityProfileDialog.newInstance(packageName, label.toString(),
                            selectedProfile, customProfile == null ? null : currentSummary.toString())
                            .show(getChildFragmentManager(), PROFILE_DIALOG_TAG);
                }
                return true;
            });
            mGames.addPreference(preference);
        }
    }

    private List<ApplicationInfo> getInstalledGames(PackageManager packageManager) {
        final List<ApplicationInfo> games = new ArrayList<>();
        for (ApplicationInfo app : packageManager.getInstalledApplications(0)) {
            if (app.category == ApplicationInfo.CATEGORY_GAME && app.enabled
                    && !TextUtils.equals(app.packageName, getContext().getPackageName())
                    && packageManager.getLaunchIntentForPackage(app.packageName) != null) {
                games.add(app);
            }
        }
        games.sort(Comparator.comparing(
                app -> packageManager.getApplicationLabel(app).toString(),
                String.CASE_INSENSITIVE_ORDER));
        return games;
    }

    private JSONObject readIdentities(Context context) {
        final String stored = Settings.System.getStringForUser(context.getContentResolver(),
                SETTING_GAME_IDENTITIES, UserHandle.myUserId());
        if (TextUtils.isEmpty(stored)) return new JSONObject();
        try {
            return new JSONObject(stored);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private boolean saveProfile(Context context, String packageName, String profile) {
        final JSONObject identities = readIdentities(context);
        try {
            if (VALUE_NONE.equals(profile)) {
                identities.remove(packageName);
            } else if (VALUE_CUSTOM.equals(profile)) {
                if (identities.optJSONObject(packageName) == null) return false;
            } else if (isKnownProfile(profile)) {
                identities.put(packageName, profile);
            } else {
                return false;
            }
            final String value = identities.length() == 0 ? null : identities.toString();
            return Settings.System.putStringForUser(context.getContentResolver(),
                    SETTING_GAME_IDENTITIES, value, UserHandle.myUserId());
        } catch (JSONException e) {
            return false;
        }
    }

    private boolean isGameIdentityEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                SETTING_GAME_IDENTITIES_ENABLED, 0, UserHandle.myUserId()) == 1;
    }

    private void restartGamePackages(Context context, Iterator<String> packageNames) {
        final Set<String> packages = new HashSet<>();
        while (packageNames.hasNext()) packages.add(packageNames.next());
        restartGamePackages(context, packages, false);
    }

    private void restartGamePackages(Context context, Iterable<String> packageNames) {
        restartGamePackages(context, packageNames, false);
    }

    private void restartGamePackages(Context context, Iterable<String> packageNames,
            boolean forceWhenDisabled) {
        if (!forceWhenDisabled && !isGameIdentityEnabled(context)) return;
        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) return;

        final PackageManager packageManager = context.getPackageManager();
        for (String packageName : packageNames) {
            if (!isValidPackageName(packageName)) continue;
            try {
                final ApplicationInfo app = packageManager.getApplicationInfo(packageName, 0);
                if (app.category != ApplicationInfo.CATEGORY_GAME || !app.enabled
                        || packageManager.getLaunchIntentForPackage(packageName) == null) {
                    continue;
                }
                activityManager.forceStopPackage(packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                Log.w(TAG, "Unable to restart game after identity update: " + packageName, e);
            }
        }
    }

    private boolean isKnownProfile(String profile) {
        for (String value : PROFILE_VALUES) {
            if (value.equals(profile)) return true;
        }
        return false;
    }

    private CharSequence getProfileSummary(Context context, String profile) {
        if (VALUE_NONE.equals(profile) || TextUtils.isEmpty(profile)) {
            return context.getString(R.string.game_device_identity_profile_off);
        }
        final String[] values = context.getResources().getStringArray(
                R.array.game_device_identity_profile_values);
        final String[] entries = context.getResources().getStringArray(
                R.array.game_device_identity_profile_names);
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(profile)) return entries[i];
        }
        return context.getString(R.string.game_device_identity_profile_off);
    }

    private CharSequence getCustomProfileSummary(Context context, JSONObject profile) {
        if (profile == null) {
            return context.getString(R.string.game_device_identity_profile_off);
        }
        String label = profile.optString("MODEL", "");
        if (TextUtils.isEmpty(label)) label = profile.optString("DEVICE", "");
        if (TextUtils.isEmpty(label)) label = profile.optString("MANUFACTURER", "");
        if (TextUtils.isEmpty(label)) {
            label = context.getString(R.string.game_device_identity_custom_profile_fallback);
        }
        return context.getString(R.string.game_device_identity_custom_profile, label);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GAME_PROFILE_JSON || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        importGameProfiles(getPrefContext(), data.getData());
    }

    private void importGameProfiles(Context context, Uri uri) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Unable to open selected JSON file");
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int totalBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > MAX_GAME_PROFILE_JSON_BYTES) {
                    throw new IOException("Game profile JSON file is too large");
                }
                output.write(buffer, 0, read);
            }

            final JSONObject root = new JSONObject(
                    new String(output.toByteArray(), StandardCharsets.UTF_8));
            final JSONObject identities = readIdentities(context);
            int importedPackages = 0;
            final Set<String> importedPackageNames = new HashSet<>();
            final Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                if (!key.startsWith("PACKAGES_") || key.endsWith("_DEVICE")) continue;

                final JSONArray packages = root.optJSONArray(key);
                final JSONObject device = root.optJSONObject(key + "_DEVICE");
                if (packages == null || device == null) continue;
                final JSONObject profile = sanitizeProfile(device);
                if (profile.length() == 0) continue;

                for (int i = 0; i < packages.length(); i++) {
                    final String packageName = packages.optString(i, "");
                    if (!isValidPackageName(packageName)) continue;
                    if (importedPackages >= MAX_IMPORTED_PACKAGES) {
                        throw new IOException("Too many game profiles in JSON file");
                    }
                    identities.put(packageName, new JSONObject(profile.toString()));
                    importedPackageNames.add(packageName);
                    importedPackages++;
                }
            }

            if (importedPackages == 0) {
                throw new IOException("No valid package profiles were found");
            }
            if (!Settings.System.putStringForUser(context.getContentResolver(),
                    SETTING_GAME_IDENTITIES, identities.toString(), UserHandle.myUserId())) {
                throw new IOException("Unable to save imported game profiles");
            }
            restartGamePackages(context, importedPackageNames);
            updateGamePreferences();
            Toast.makeText(context,
                    context.getResources().getQuantityString(
                            R.plurals.game_device_identity_import_success, importedPackages,
                            importedPackages), Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException | RuntimeException e) {
            Toast.makeText(context, R.string.game_device_identity_import_error,
                    Toast.LENGTH_LONG).show();
        }
    }

    private JSONObject sanitizeProfile(JSONObject source) throws JSONException {
        final JSONObject profile = new JSONObject();
        for (String field : BUILD_FIELDS) {
            final Object value = source.opt(field);
            if (value instanceof String && !TextUtils.isEmpty((String) value)) {
                profile.put(field, value);
            }
        }
        return profile;
    }

    private boolean isValidPackageName(String packageName) {
        return !TextUtils.isEmpty(packageName)
                && packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.SETTINGS_SYSTEM_CATEGORY;
    }
}

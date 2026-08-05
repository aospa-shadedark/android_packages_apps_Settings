/*
 * SPDX-FileCopyrightText: 2026 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.settings.gaming;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Restorable, single-choice profile picker using the expressive Settings dialog theme. */
public class GameDeviceIdentityProfileDialog extends DialogFragment {
    static final String RESULT_KEY = "game_identity_profile_result";
    static final String RESULT_PACKAGE = "package_name";
    static final String RESULT_PROFILE = "profile";
    private static final String ARG_LABEL = "app_label";
    private static final String ARG_CUSTOM_SUMMARY = "custom_summary";

    static GameDeviceIdentityProfileDialog newInstance(String packageName, String label,
            String selectedProfile, @Nullable String customSummary) {
        final Bundle args = new Bundle();
        args.putString(RESULT_PACKAGE, packageName);
        args.putString(ARG_LABEL, label);
        args.putString(RESULT_PROFILE, selectedProfile);
        args.putString(ARG_CUSTOM_SUMMARY, customSummary);
        final GameDeviceIdentityProfileDialog fragment = new GameDeviceIdentityProfileDialog();
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Bundle args = requireArguments();
        final List<String> values = new ArrayList<>(Arrays.asList(
                getResources().getStringArray(R.array.game_device_identity_profile_values)));
        final List<String> titles = new ArrayList<>(Arrays.asList(
                getResources().getStringArray(R.array.game_device_identity_profile_entries)));
        final List<String> summaries = new ArrayList<>(Arrays.asList(
                getResources().getStringArray(R.array.game_device_identity_profile_descriptions)));
        final String customSummary = args.getString(ARG_CUSTOM_SUMMARY);
        if (!TextUtils.isEmpty(customSummary)) {
            values.add(GameDeviceIdentitySettings.VALUE_CUSTOM);
            titles.add(getString(R.string.game_device_identity_imported_title));
            summaries.add(customSummary);
        }
        final String selected = args.getString(RESULT_PROFILE,
                GameDeviceIdentitySettings.VALUE_NONE);
        final int selectedIndex = Math.max(0, values.indexOf(selected));
        return new AlertDialog.Builder(requireContext())
                .setTitle(args.getString(ARG_LABEL))
                .setSingleChoiceItems(new ProfileAdapter(requireContext(), titles, summaries),
                        selectedIndex, (dialog, position) -> {
                            // Re-selecting the current profile must not close a running game.
                            if (!values.get(position).equals(selected)) {
                                final Bundle result = new Bundle();
                                result.putString(RESULT_PACKAGE, args.getString(RESULT_PACKAGE));
                                result.putString(RESULT_PROFILE, values.get(position));
                                getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                            }
                            dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        final ListView list = ((AlertDialog) requireDialog()).getListView();
        final int inset = getResources().getDimensionPixelSize(
                R.dimen.game_device_identity_dialog_inset);
        final int gap = getResources().getDimensionPixelSize(
                R.dimen.game_device_identity_choice_gap);
        list.setPadding(inset, gap, inset, gap);
        list.setClipToPadding(false);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(gap);
    }

    private static final class ProfileAdapter extends BaseAdapter {
        private final Context mContext;
        private final List<String> mTitles;
        private final List<String> mSummaries;

        ProfileAdapter(Context context, List<String> titles, List<String> summaries) {
            mContext = context;
            mTitles = titles;
            mSummaries = summaries;
        }

        @Override
        public int getCount() {
            return mTitles.size();
        }

        @Override
        public String getItem(int position) {
            return mTitles.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final CheckedTextView row = convertView instanceof CheckedTextView
                    ? (CheckedTextView) convertView
                    : (CheckedTextView) LayoutInflater.from(mContext).inflate(
                            R.layout.game_device_identity_profile_choice, parent, false);
            final SpannableStringBuilder text = new SpannableStringBuilder(getItem(position));
            text.setSpan(new TextAppearanceSpan(mContext,
                            R.style.TextAppearance_GameIdentity_ChoiceTitle),
                    0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append('\n');
            final int summaryStart = text.length();
            text.append(mSummaries.get(position));
            text.setSpan(new TextAppearanceSpan(mContext,
                            R.style.TextAppearance_GameIdentity_ChoiceSummary),
                    summaryStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setText(text);
            // ListView owns checked state, focus and accessibility, including recycled rows.
            return row;
        }
    }
}

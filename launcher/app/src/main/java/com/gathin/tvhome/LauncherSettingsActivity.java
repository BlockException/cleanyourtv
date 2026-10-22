package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView wallpaperUrlChoice;
    private TextView wallpaperIntervalChoice;
    private TextView wallpaperCrossfadeChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaperUrlChoice = findViewById(R.id.wallpaperUrlChoice);
        wallpaperUrlChoice.setSingleLine(true);
        wallpaperUrlChoice.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        wallpaperUrlChoice.setOnClickListener(v -> editWallpaperUrl());
        wallpaperIntervalChoice = findViewById(R.id.wallpaperIntervalChoice);
        wallpaperIntervalChoice.setOnClickListener(v -> chooseWallpaperInterval());
        wallpaperCrossfadeChoice = findViewById(R.id.wallpaperCrossfadeChoice);
        wallpaperCrossfadeChoice.setOnClickListener(v -> chooseWallpaperCrossfade());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.WALLPAPER_MODE_KEY).remove(Config.WALLPAPER_URL_KEY)
                    .remove(Config.WALLPAPER_INTERVAL_KEY).remove(Config.WALLPAPER_CROSSFADE_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 2];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_dynamic);
        labels[images.size() + 1] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String mode = choices.getString(Config.WALLPAPER_MODE_KEY, "");
        int checked = "dynamic".equals(mode) ? images.size()
                : "none".equals(mode) || "none".equals(current) ? images.size() + 1
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        editWallpaperUrl();
                        return;
                    }
                    if (which == images.size() + 1) {
                        choices.edit().putString(Config.WALLPAPER_MODE_KEY, "none")
                                .putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                        images.get(which).getName())
                                .putString(Config.WALLPAPER_MODE_KEY, "static").apply();
                    }
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)
                            .putString(Config.WALLPAPER_MODE_KEY, "theme").apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editWallpaperUrl() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.wallpaper_url_hint);
        input.setText(choices.getString(Config.WALLPAPER_URL_KEY, ""));
        input.setSelection(input.length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_url_label)
                .setMessage(R.string.wallpaper_url_help)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String url = WallpaperSettings.validUrl(input.getText().toString());
                    if (url == null) {
                        input.setError(getString(R.string.wallpaper_url_invalid));
                        return;
                    }
                    SharedPreferences.Editor edit = choices.edit().putString(Config.WALLPAPER_URL_KEY, url)
                            .putString(Config.WALLPAPER_MODE_KEY, "dynamic");
                    if ("none".equals(choices.getString(Config.WALLPAPER_CHOICE_KEY, null))) {
                        edit.remove(Config.WALLPAPER_CHOICE_KEY);
                    }
                    edit.apply();
                    setResult(RESULT_OK);
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    private void chooseWallpaperInterval() {
        int[] seconds = {10, 15, 20, 30, 45, 60, 120, 300, 600, 1800};
        String[] labels = new String[seconds.length + 1];
        for (int i = 0; i < seconds.length; i++) {
            labels[i] = getResources().getQuantityString(R.plurals.wallpaper_seconds, seconds[i], seconds[i]);
        }
        labels[seconds.length] = getString(R.string.wallpaper_custom_interval);
        int current = WallpaperSettings.intervalSeconds(choices.getInt(
                Config.WALLPAPER_INTERVAL_KEY, WallpaperSettings.DEFAULT_INTERVAL_SECONDS));
        int checked = -1;
        for (int i = 0; i < seconds.length; i++) if (seconds[i] == current) checked = i;
        new AlertDialog.Builder(this).setTitle(R.string.wallpaper_interval_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == seconds.length) editWallpaperInterval(current);
                    else saveWallpaperInterval(seconds[which]);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void editWallpaperInterval(int current) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelection(input.length());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.wallpaper_custom_interval)
                .setMessage(R.string.wallpaper_interval_help).setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString());
                    } catch (NumberFormatException invalid) {
                        value = 0;
                    }
                    if (value < WallpaperSettings.MIN_INTERVAL_SECONDS
                            || value > WallpaperSettings.MAX_INTERVAL_SECONDS) {
                        input.setError(getString(R.string.wallpaper_interval_invalid));
                        return;
                    }
                    dialog.dismiss();
                    saveWallpaperInterval(value);
                }));
        dialog.show();
    }

    private void saveWallpaperInterval(int seconds) {
        choices.edit().putInt(Config.WALLPAPER_INTERVAL_KEY, seconds).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void chooseWallpaperCrossfade() {
        String[] labels = new String[WallpaperSettings.MAX_CROSSFADE_SECONDS];
        for (int seconds = WallpaperSettings.MIN_CROSSFADE_SECONDS;
             seconds <= WallpaperSettings.MAX_CROSSFADE_SECONDS; seconds++) {
            labels[seconds - 1] = getResources().getQuantityString(
                    R.plurals.wallpaper_seconds, seconds, seconds);
        }
        int current = WallpaperSettings.crossfadeSeconds(choices.getInt(
                Config.WALLPAPER_CROSSFADE_KEY, WallpaperSettings.DEFAULT_CROSSFADE_SECONDS));
        new AlertDialog.Builder(this).setTitle(R.string.wallpaper_crossfade_label)
                .setSingleChoiceItems(labels, current - 1, (dialog, which) -> {
                    choices.edit().putInt(Config.WALLPAPER_CROSSFADE_KEY, which + 1).apply();
                    setResult(RESULT_OK);
                    dialog.dismiss();
                    refresh();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String wallpaperMode = choices.getString(Config.WALLPAPER_MODE_KEY, "");
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "dynamic".equals(wallpaperMode) ? getString(R.string.wallpaper_dynamic)
                        : "none".equals(wallpaperMode) || "none".equals(wallpaperCurrent)
                        ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        String url = choices.getString(Config.WALLPAPER_URL_KEY, "");
        wallpaperUrlChoice.setText(getString(R.string.wallpaper_url_state,
                url.isEmpty() ? getString(R.string.wallpaper_url_unset) : url));
        int interval = WallpaperSettings.intervalSeconds(choices.getInt(
                Config.WALLPAPER_INTERVAL_KEY, WallpaperSettings.DEFAULT_INTERVAL_SECONDS));
        wallpaperIntervalChoice.setText(getString(R.string.wallpaper_interval_state,
                getResources().getQuantityString(R.plurals.wallpaper_seconds, interval, interval)));
        int crossfade = WallpaperSettings.crossfadeSeconds(choices.getInt(
                Config.WALLPAPER_CROSSFADE_KEY, WallpaperSettings.DEFAULT_CROSSFADE_SECONDS));
        wallpaperCrossfadeChoice.setText(getString(R.string.wallpaper_crossfade_state,
                getResources().getQuantityString(R.plurals.wallpaper_seconds, crossfade, crossfade)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.WALLPAPER_MODE_KEY)
                || choices.contains(Config.WALLPAPER_URL_KEY) || choices.contains(Config.WALLPAPER_INTERVAL_KEY)
                || choices.contains(Config.WALLPAPER_CROSSFADE_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}

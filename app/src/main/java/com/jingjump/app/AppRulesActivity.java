package com.jingjump.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AppRulesActivity extends Activity {
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> visibleApps = new ArrayList<>();
    private AppAdapter adapter;
    private TextView countView;
    private TextView emptyView;
    private EditText searchView;
    private volatile boolean appsLoading;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildScreen());
        loadApps();
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(getColor(R.color.surface));
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
    }

    private View buildScreen() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(getColor(R.color.background));

        LinearLayout root = UiKit.vertical(this);
        int side = UiKit.dp(this, 18);
        root.setPadding(side, UiKit.dp(this, 10), side, UiKit.dp(this, 20));
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }

        LinearLayout toolbar = UiKit.horizontal(this);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setBackgroundResource(android.R.drawable.list_selector_background);
        back.setContentDescription(getString(R.string.back));
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, UiKit.lp(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titleGroup = UiKit.vertical(this);
        titleGroup.addView(UiKit.text(this, getString(R.string.app_rules_title),
                22f, getColor(R.color.ink), true));
        countView = UiKit.text(this, "", 12f, getColor(R.color.muted), false);
        titleGroup.addView(countView);
        LinearLayout.LayoutParams titleParams = UiKit.weighted(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        titleParams.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        toolbar.addView(titleGroup, titleParams);
        root.addView(toolbar);

        EditText search = new EditText(this);
        searchView = search;
        search.setHint(R.string.search_apps_hint);
        search.setHintTextColor(getColor(R.color.muted));
        search.setTextColor(getColor(R.color.ink));
        search.setTextSize(15f);
        search.setSingleLine(true);
        search.setBackgroundResource(R.drawable.bg_input);
        search.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 10),
                UiKit.dp(this, 13), UiKit.dp(this, 10));
        root.addView(search, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 50),
                0, 14, 0, 12
        ));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        FrameLayout listHolder = new FrameLayout(this);
        ListView listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, UiKit.dp(this, 12));
        adapter = new AppAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                showRuleDialog(visibleApps.get(position)));
        listHolder.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        emptyView = UiKit.text(this, getString(R.string.no_search_results),
                14f, getColor(R.color.muted), false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listHolder.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(listHolder, UiKit.weighted(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        return frame;
    }

    /**
     * Reads the launcher app list off the main thread. Loading every label and
     * icon for 200+ packages used to run in {@code onCreate} and blocked the
     * first frame for a noticeable moment.
     */
    private void loadApps() {
        if (appsLoading) {
            return;
        }
        appsLoading = true;
        countView.setText(R.string.apps_loading);
        new Thread(() -> {
            List<AppEntry> loaded = new ArrayList<>();
            boolean failed = false;
            try {
                Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolved = getPackageManager()
                        .queryIntentActivities(launcherIntent, 0);
                Set<String> seen = new HashSet<>();
                for (ResolveInfo info : resolved) {
                    if (info.activityInfo == null || info.activityInfo.packageName == null) {
                        continue;
                    }
                    String packageName = info.activityInfo.packageName;
                    if (packageName.equals(getPackageName()) || !seen.add(packageName)) {
                        continue;
                    }
                    CharSequence loadedLabel = info.loadLabel(getPackageManager());
                    String label = loadedLabel == null ? packageName : loadedLabel.toString();
                    Drawable icon = info.loadIcon(getPackageManager());
                    loaded.add(new AppEntry(label, packageName, icon));
                }
                Collator collator = Collator.getInstance(Locale.CHINA);
                loaded.sort(Comparator.comparing(entry -> entry.label, collator));
            } catch (Throwable ignored) {
                loaded.clear();
                failed = true;
            }
            final List<AppEntry> result = loaded;
            final boolean loadFailed = failed;
            runOnUiThread(() -> {
                if (destroyed || isFinishing()) {
                    return;
                }
                appsLoading = false;
                allApps.clear();
                allApps.addAll(result);
                if (loadFailed) {
                    countView.setText(R.string.apps_load_failed);
                    Toast.makeText(this, R.string.apps_load_failed, Toast.LENGTH_LONG).show();
                } else {
                    countView.setText(getString(R.string.apps_loaded, allApps.size()));
                }
                filter(searchView == null ? "" : searchView.getText().toString());
            });
        }, "jingjump-app-list").start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void filter(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        visibleApps.clear();
        for (AppEntry entry : allApps) {
            if (normalized.isEmpty()
                    || entry.label.toLowerCase(Locale.ROOT).contains(normalized)
                    || entry.packageName.toLowerCase(Locale.ROOT).contains(normalized)) {
                visibleApps.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        emptyView.setVisibility(visibleApps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showRuleDialog(AppEntry entry) {
        RuleStore.AppRule current = RuleStore.get(this, entry.packageName);

        LinearLayout content = UiKit.vertical(this);
        content.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 4),
                UiKit.dp(this, 20), UiKit.dp(this, 4));
        TextView packageView = UiKit.text(
                this,
                getString(R.string.app_rule_package, entry.packageName),
                12f,
                getColor(R.color.muted),
                false
        );
        content.addView(packageView);

        LinearLayout switchRow = UiKit.horizontal(this);
        TextView switchLabel = UiKit.text(
                this,
                getString(R.string.app_rule_enabled),
                14f,
                getColor(R.color.ink),
                true
        );
        switchRow.addView(switchLabel, UiKit.weighted(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        Switch enabled = new Switch(this);
        enabled.setChecked(current.enabled);
        switchRow.addView(enabled);
        content.addView(switchRow, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 16, 0, 12
        ));

        LinearLayout persistentRow = UiKit.horizontal(this);
        LinearLayout persistentLabels = UiKit.vertical(this);
        persistentLabels.addView(UiKit.text(
                this,
                getString(R.string.app_rule_continuous_title),
                14f,
                getColor(R.color.ink),
                true
        ));
        TextView persistentDescription = UiKit.text(
                this,
                getString(R.string.app_rule_continuous_description),
                12f,
                getColor(R.color.muted),
                false
        );
        persistentLabels.addView(persistentDescription, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
        ));
        persistentRow.addView(persistentLabels, UiKit.weighted(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        Switch continuous = new Switch(this);
        continuous.setChecked(current.continuousScan);
        continuous.setEnabled(enabled.isChecked());
        persistentRow.addView(continuous, UiKit.margins(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                14, 0, 0, 0
        ));
        content.addView(persistentRow, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            continuous.setEnabled(checked);
            if (!checked) {
                continuous.setChecked(false);
            }
        });

        final int[] sensitivityLevel = {current.sensitivity};
        content.addView(buildSensitivitySection(sensitivityLevel), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 16, 0, 10
        ));

        LinearLayout safetyRow = UiKit.horizontal(this);
        LinearLayout safetyLabels = UiKit.vertical(this);
        safetyLabels.addView(UiKit.text(
                this,
                getString(R.string.app_rule_safety_title),
                14f,
                getColor(R.color.ink),
                true
        ));
        safetyLabels.addView(UiKit.text(
                this,
                getString(R.string.app_rule_safety_description),
                12f,
                getColor(R.color.muted),
                false
        ), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
        ));
        safetyRow.addView(safetyLabels, UiKit.weighted(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        Switch safetyMode = new Switch(this);
        safetyMode.setChecked(current.safetyMode);
        safetyRow.addView(safetyMode, UiKit.margins(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                14, 0, 0, 0
        ));
        content.addView(safetyRow, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 16, 0, 10
        ));

        TextView pauseTitle = UiKit.text(
                this,
                getString(R.string.app_rule_pause_markers_title),
                13f,
                getColor(R.color.ink),
                true
        );
        content.addView(pauseTitle);
        EditText pauseMarkers = createSmallEditor(
                getString(R.string.app_rule_pause_markers_hint),
                String.join("\n", current.pausePageMarkers)
        );
        content.addView(pauseMarkers, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 72),
                0, 5, 0, 0
        ));

        TextView blockedTitle = UiKit.text(
                this,
                getString(R.string.app_rule_blocked_words_title),
                13f,
                getColor(R.color.ink),
                true
        );
        content.addView(blockedTitle, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 12, 0, 5
        ));
        EditText blockedWords = createSmallEditor(
                getString(R.string.app_rule_blocked_words_hint),
                String.join("\n", current.blockedClickWords)
        );
        content.addView(blockedWords, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 72)
        ));

        updateSafetyEditors(safetyMode.isChecked(), pauseMarkers, blockedWords);
        safetyMode.setOnCheckedChangeListener((button, checked) ->
                updateSafetyEditors(checked, pauseMarkers, blockedWords));

        EditText keywords = new EditText(this);
        keywords.setHint(R.string.app_rule_keywords_hint);
        keywords.setText(String.join("\n", current.keywords));
        keywords.setTextColor(getColor(R.color.ink));
        keywords.setHintTextColor(getColor(R.color.muted));
        keywords.setTextSize(14f);
        keywords.setGravity(Gravity.TOP | Gravity.START);
        keywords.setMinLines(2);
        keywords.setMaxLines(5);
        keywords.setBackgroundResource(R.drawable.bg_input);
        keywords.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        content.addView(keywords, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 88)
        ));

        TextView help = UiKit.text(this, getString(R.string.app_rule_keywords_help),
                12f, getColor(R.color.muted), false);
        content.addView(help, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 6, 0, 0
        ));

        TextView selectorTitle = UiKit.text(
                this,
                getString(R.string.app_rule_selectors_title),
                13f,
                getColor(R.color.ink),
                true
        );
        content.addView(selectorTitle, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 14, 0, 5
        ));
        EditText selectors = new EditText(this);
        selectors.setHint(R.string.app_rule_selectors_hint);
        selectors.setText(String.join("\n", current.selectors));
        selectors.setTextColor(getColor(R.color.ink));
        selectors.setHintTextColor(getColor(R.color.muted));
        selectors.setTextSize(13f);
        selectors.setGravity(Gravity.TOP | Gravity.START);
        selectors.setMinLines(2);
        selectors.setMaxLines(5);
        selectors.setBackgroundResource(R.drawable.bg_input);
        selectors.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        content.addView(selectors, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 78)
        ));
        TextView selectorHelp = UiKit.text(
                this,
                getString(R.string.app_rule_selectors_help),
                12f,
                getColor(R.color.muted),
                false
        );
        content.addView(selectorHelp, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 6, 0, 0
        ));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_rule_title, entry.label))
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(view -> {
                List<String> parsed = splitKeywords(keywords.getText().toString());
                RuleStore.save(
                        AppRulesActivity.this,
                        entry.packageName,
                        enabled.isChecked(),
                        parsed,
                        splitSelectors(selectors.getText().toString()),
                        continuous.isChecked(),
                        safetyMode.isChecked(),
                        splitMarkers(pauseMarkers.getText().toString()),
                        splitMarkers(blockedWords.getText().toString()),
                        sensitivityLevel[0]
                );
                adapter.notifyDataSetChanged();
                dialog.dismiss();
                Toast.makeText(
                        AppRulesActivity.this,
                        R.string.rules_saved,
                        Toast.LENGTH_SHORT
                ).show();
            });
        });
        dialog.show();
    }

    private static List<String> splitKeywords(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        for (String value : raw.split("[\\n,，;；|]+")) {
            if (!value.trim().isEmpty()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static List<String> splitSelectors(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        for (String value : raw.split("\\n")) {
            if (!value.trim().isEmpty()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static List<String> splitMarkers(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        for (String value : raw.split("[\\n,，;；|]+")) {
            if (!value.trim().isEmpty() && values.size() < 32) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private EditText createSmallEditor(String hint, String value) {
        EditText editor = new EditText(this);
        editor.setHint(hint);
        editor.setText(value);
        editor.setTextColor(getColor(R.color.ink));
        editor.setHintTextColor(getColor(R.color.muted));
        editor.setTextSize(13f);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(2);
        editor.setMaxLines(4);
        editor.setBackgroundResource(R.drawable.bg_input);
        editor.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 9),
                UiKit.dp(this, 12), UiKit.dp(this, 9));
        return editor;
    }

    private View buildSensitivitySection(int[] selectedLevel) {
        LinearLayout section = UiKit.vertical(this);
        section.addView(UiKit.text(
                this,
                getString(R.string.app_rule_sensitivity_title),
                14f,
                getColor(R.color.ink),
                true
        ));
        section.addView(UiKit.text(
                this,
                getString(R.string.app_rule_sensitivity_description),
                12f,
                getColor(R.color.muted),
                false
        ), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 8
        ));

        int[] levels = {
                SensitivityProfile.LEVEL_GLOBAL,
                SensitivityProfile.LEVEL_CONSERVATIVE,
                SensitivityProfile.LEVEL_STANDARD,
                SensitivityProfile.LEVEL_SENSITIVE,
                SensitivityProfile.LEVEL_AGGRESSIVE
        };
        List<TextView> chips = new ArrayList<>();
        List<Integer> chipLevels = new ArrayList<>();
        int[][] rowDefinitions = {{0, 1, 2}, {3, 4}};
        for (int[] rowDefinition : rowDefinitions) {
            LinearLayout row = UiKit.horizontal(this);
            for (int index : rowDefinition) {
                final int level = levels[index];
                TextView chip = UiKit.text(
                        this,
                        sensitivityLabel(level),
                        12f,
                        getColor(R.color.muted),
                        true
                );
                chip.setGravity(Gravity.CENTER);
                chip.setMinHeight(UiKit.dp(this, 36));
                chip.setPadding(
                        UiKit.dp(this, 6),
                        UiKit.dp(this, 8),
                        UiKit.dp(this, 6),
                        UiKit.dp(this, 8)
                );
                chip.setOnClickListener(view -> {
                    selectedLevel[0] = level;
                    refreshSensitivityChips(chips, chipLevels, selectedLevel[0]);
                });
                chips.add(chip);
                chipLevels.add(level);
                row.addView(chip, UiKit.margins(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        3, 3, 3, 3
                ));
                ((LinearLayout.LayoutParams) chip.getLayoutParams()).weight = 1f;
            }
            if (rowDefinition.length < 3) {
                View spacer = new View(this);
                row.addView(spacer, UiKit.weighted(0, 1, 1f));
            }
            section.addView(row);
        }
        refreshSensitivityChips(chips, chipLevels, selectedLevel[0]);
        return section;
    }

    private void refreshSensitivityChips(
            List<TextView> chips,
            List<Integer> levels,
            int selectedLevel
    ) {
        for (int i = 0; i < chips.size(); i++) {
            boolean selected = levels.get(i) == selectedLevel;
            TextView chip = chips.get(i);
            chip.setTextColor(getColor(selected ? R.color.accent_dark : R.color.muted));
            chip.setBackground(selected
                    ? UiKit.rounded(this, getColor(R.color.accent_soft), 6f)
                    : UiKit.bordered(
                            this,
                            getColor(R.color.surface),
                            getColor(R.color.border),
                            6f
                    ));
        }
    }

    private String sensitivityLabel(int level) {
        if (level == SensitivityProfile.LEVEL_GLOBAL) {
            int global = SettingsStore.sensitivity(this);
            return getString(
                    R.string.sensitivity_follow_global_value,
                    getString(sensitivityLabelRes(global))
            );
        }
        return getString(sensitivityLabelRes(level));
    }

    private static int sensitivityLabelRes(int level) {
        switch (level) {
            case SensitivityProfile.LEVEL_CONSERVATIVE:
                return R.string.sensitivity_conservative;
            case SensitivityProfile.LEVEL_SENSITIVE:
                return R.string.sensitivity_sensitive;
            case SensitivityProfile.LEVEL_AGGRESSIVE:
                return R.string.sensitivity_aggressive;
            case SensitivityProfile.LEVEL_STANDARD:
            default:
                return R.string.sensitivity_standard;
        }
    }

    private static void updateSafetyEditors(
            boolean enabled,
            EditText pauseMarkers,
            EditText blockedWords
    ) {
        pauseMarkers.setEnabled(enabled);
        blockedWords.setEnabled(enabled);
        pauseMarkers.setAlpha(enabled ? 1f : 0.45f);
        blockedWords.setAlpha(enabled ? 1f : 0.45f);
    }

    private String ruleStatus(String packageName) {
        RuleStore.AppRule rule = RuleStore.get(this, packageName);
        if (!rule.enabled) {
            return getString(R.string.rule_excluded);
        }
        String base;
        if (rule.safetyMode && rule.continuousScan) {
            base = getString(R.string.rule_safety_continuous);
        } else if (rule.safetyMode) {
            base = getString(R.string.rule_safety);
        } else if (rule.continuousScan) {
            base = getString(R.string.rule_continuous);
        } else {
            int customCount = rule.keywords.size() + rule.selectors.size();
            base = customCount > 0
                    ? getString(R.string.rule_custom, customCount)
                    : getString(R.string.rule_follow_global);
        }
        if (rule.sensitivity != SensitivityProfile.LEVEL_GLOBAL) {
            String level = getString(sensitivityLabelRes(rule.sensitivity));
            return getString(R.string.rule_sensitivity_value, level, base);
        }
        return base;
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visibleApps.size();
        }

        @Override
        public Object getItem(int position) {
            return visibleApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return visibleApps.get(position).packageName.hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = UiKit.horizontal(AppRulesActivity.this);
                row.setPadding(UiKit.dp(AppRulesActivity.this, 12),
                        UiKit.dp(AppRulesActivity.this, 11),
                        UiKit.dp(AppRulesActivity.this, 12),
                        UiKit.dp(AppRulesActivity.this, 11));
                row.setBackgroundColor(getColor(R.color.surface));

                ImageView icon = new ImageView(AppRulesActivity.this);
                row.addView(icon, UiKit.lp(
                        UiKit.dp(AppRulesActivity.this, 42),
                        UiKit.dp(AppRulesActivity.this, 42)
                ));

                LinearLayout labels = UiKit.vertical(AppRulesActivity.this);
                TextView appName = UiKit.text(
                        AppRulesActivity.this,
                        "",
                        15f,
                        getColor(R.color.ink),
                        true
                );
                TextView packageName = UiKit.text(
                        AppRulesActivity.this,
                        "",
                        11f,
                        getColor(R.color.muted),
                        false
                );
                packageName.setSingleLine(true);
                packageName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                labels.addView(appName);
                labels.addView(packageName, UiKit.margins(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0, 2, 0, 0
                ));
                row.addView(labels, UiKit.margins(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        12, 0, 10, 0
                ));
                ((LinearLayout.LayoutParams) labels.getLayoutParams()).weight = 1f;

                TextView status = UiKit.text(
                        AppRulesActivity.this,
                        "",
                        11f,
                        getColor(R.color.accent_dark),
                        true
                );
                status.setGravity(Gravity.CENTER);
                status.setPadding(UiKit.dp(AppRulesActivity.this, 8),
                        UiKit.dp(AppRulesActivity.this, 4),
                        UiKit.dp(AppRulesActivity.this, 8),
                        UiKit.dp(AppRulesActivity.this, 4));
                status.setBackground(UiKit.rounded(
                        AppRulesActivity.this,
                        getColor(R.color.accent_soft),
                        5f
                ));
                row.addView(status);

                holder = new RowHolder(icon, appName, packageName, status);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            AppEntry entry = visibleApps.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.name.setText(entry.label);
            holder.packageName.setText(entry.packageName);
            holder.status.setText(ruleStatus(entry.packageName));
            return convertView;
        }
    }

    private static final class RowHolder {
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView status;

        RowHolder(ImageView icon, TextView name, TextView packageName, TextView status) {
            this.icon = icon;
            this.name = name;
            this.packageName = packageName;
            this.status = status;
        }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;

        AppEntry(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }
}

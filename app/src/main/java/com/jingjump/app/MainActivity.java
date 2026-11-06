package com.jingjump.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1201;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable dnsStateTicker = new Runnable() {
        @Override
        public void run() {
            refreshDnsState();
            uiHandler.postDelayed(this, 1000L);
        }
    };
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView statusBadge;
    private TextView primaryAction;
    private TextView batteryAction;
    private TextView skipCountView;
    private TextView recentActionView;
    private Switch autoCloseSwitch;
    private Switch cornerSwitch;
    private TextView sensitivityValue;
    private TextView dnsStatusView;
    private TextView dnsCountView;
    private TextView dnsRuleView;
    private TextView dnsAction;
    private TextView dnsLearnedView;
    private Switch dnsAutoLearnSwitch;
    private TextView dnsBlockModeValue;
    private Switch dnsFullListSwitch;
    private Switch dnsStrictSwitch;
    private TextView dnsPrivateDnsWarning;
    private boolean privateDnsCached;
    private int lastDnsUiState = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildScreen());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        uiHandler.removeCallbacks(dnsStateTicker);
        uiHandler.post(dnsStateTicker);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(dnsStateTicker);
        super.onPause();
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

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        frame.addView(scrollView, scrollParams);

        LinearLayout content = UiKit.vertical(this);
        int side = UiKit.dp(this, 22);
        content.setPadding(side, UiKit.dp(this, 18), side, UiKit.dp(this, 36));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }

        content.addView(buildBrandRow());
        UiKit.addSpace(content, this, 22);
        content.addView(buildStatusPanel());
        UiKit.addSpace(content, this, 14);

        primaryAction = UiKit.button(this, getString(R.string.open_accessibility), true);
        primaryAction.setOnClickListener(view -> showAccessibilityDisclosure());
        content.addView(primaryAction, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 52)
        ));

        UiKit.addSpace(content, this, 26);
        content.addView(sectionHeading(getString(R.string.dns_title), null));
        content.addView(buildDnsCard(), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 10, 0, 0
        ));

        UiKit.addSpace(content, this, 26);
        content.addView(sectionHeading(
                getString(R.string.background_title),
                getString(R.string.reset_stats)
        ));
        content.addView(buildBackgroundCard(), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 10, 0, 0
        ));

        UiKit.addSpace(content, this, 26);
        content.addView(sectionHeading(getString(R.string.settings_title), null));
        content.addView(buildSettingsCard(), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 10, 0, 0
        ));

        UiKit.addSpace(content, this, 26);
        content.addView(sectionHeading(getString(R.string.rules_title), null));
        content.addView(buildRulesCard(), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 10, 0, 0
        ));

        UiKit.addSpace(content, this, 26);
        content.addView(buildInfoPanel(
                getString(R.string.shake_title),
                getString(R.string.shake_body),
                true
        ));
        UiKit.addSpace(content, this, 12);
        content.addView(buildInfoPanel(
                getString(R.string.safety_title),
                getString(R.string.safety_body),
                false
        ));
        UiKit.addSpace(content, this, 12);
        content.addView(buildInfoPanel(
                getString(R.string.privacy_title),
                getString(R.string.privacy_body),
                false
        ));

        UiKit.addSpace(content, this, 22);
        TextView version = UiKit.text(
                this,
                getString(R.string.version_label, BuildConfig.VERSION_NAME),
                12f,
                getColor(R.color.muted),
                false
        );
        version.setGravity(Gravity.CENTER);
        content.addView(version, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return frame;
    }

    private View buildDnsCard() {
        LinearLayout card = UiKit.vertical(this);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 15),
                UiKit.dp(this, 16), UiKit.dp(this, 15));
        card.setBackground(UiKit.bordered(
                this,
                getColor(R.color.surface),
                getColor(R.color.border),
                8f
        ));

        LinearLayout top = UiKit.horizontal(this);
        LinearLayout labels = UiKit.vertical(this);
        labels.addView(UiKit.text(this, getString(R.string.dns_description_title),
                14f, getColor(R.color.ink), true));
        TextView description = UiKit.text(
                this,
                getString(R.string.dns_description),
                12f,
                getColor(R.color.muted),
                false
        );
        labels.addView(description, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
        ));
        top.addView(labels, UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dnsStatusView = UiKit.text(this, getString(R.string.dns_off),
                11f, getColor(R.color.accent_dark), true);
        dnsStatusView.setGravity(Gravity.CENTER);
        dnsStatusView.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4),
                UiKit.dp(this, 8), UiKit.dp(this, 4));
        top.addView(dnsStatusView, UiKit.margins(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                12, 0, 0, 0
        ));
        card.addView(top);

        LinearLayout counters = UiKit.horizontal(this);
        counters.setPadding(0, UiKit.dp(this, 13), 0, UiKit.dp(this, 11));
        dnsCountView = UiKit.text(this, getString(R.string.dns_count_value, 0),
                13f, getColor(R.color.ink), true);
        counters.addView(dnsCountView, UiKit.weighted(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        dnsRuleView = UiKit.text(this, getString(R.string.dns_rule_value, 0),
                12f, getColor(R.color.muted), false);
        counters.addView(dnsRuleView);
        card.addView(counters);

        addDivider(card);
        dnsAutoLearnSwitch = addSwitchRow(
                card,
                getString(R.string.auto_learn_title),
                getString(R.string.auto_learn_description)
        );
        dnsAutoLearnSwitch.setChecked(SettingsStore.autoLearn(this));
        dnsAutoLearnSwitch.setOnCheckedChangeListener((button, checked) -> {
            SettingsStore.setAutoLearn(this, checked);
            // refreshSettings() runs before the rule fingerprint check, so the
            // running service picks the flag up without re-parsing the list.
            DnsVpnService.reloadRules();
        });

        dnsLearnedView = UiKit.text(
                this,
                getString(R.string.auto_learn_manage, 0),
                12f,
                getColor(R.color.accent),
                true
        );
        dnsLearnedView.setPadding(0, UiKit.dp(this, 8),
                0, UiKit.dp(this, 11));
        dnsLearnedView.setOnClickListener(view -> showAutoLearnedDomains());
        card.addView(dnsLearnedView);

        addDivider(card);
        dnsBlockModeValue = addSelectionRow(
                card,
                getString(R.string.dns_block_mode_title),
                getString(R.string.dns_block_mode_description),
                view -> showDnsBlockModeDialog()
        );

        addDivider(card);
        dnsFullListSwitch = addSwitchRow(
                card,
                getString(R.string.dns_full_list_title),
                getString(R.string.dns_full_list_description)
        );
        dnsFullListSwitch.setChecked(SettingsStore.dnsFullList(this));
        dnsFullListSwitch.setOnCheckedChangeListener((button, checked) -> {
            SettingsStore.setDnsFullList(this, checked);
            DnsVpnService.reloadRules();
            uiHandler.postDelayed(this::refreshDnsState, 900L);
        });

        addDivider(card);
        dnsStrictSwitch = addSwitchRow(
                card,
                getString(R.string.dns_strict_title),
                getString(R.string.dns_strict_description)
        );
        dnsStrictSwitch.setChecked(SettingsStore.dnsStrictMode(this));
        dnsStrictSwitch.setOnCheckedChangeListener((button, checked) -> {
            SettingsStore.setDnsStrictMode(this, checked);
            DnsVpnService.reloadRules();
            uiHandler.postDelayed(this::refreshDnsState, 400L);
        });

        dnsPrivateDnsWarning = UiKit.text(
                this,
                getString(R.string.dns_private_dns_warning),
                11f,
                getColor(R.color.warning_ink),
                false
        );
        dnsPrivateDnsWarning.setBackgroundResource(R.drawable.bg_warning);
        dnsPrivateDnsWarning.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        dnsPrivateDnsWarning.setVisibility(View.GONE);
        card.addView(dnsPrivateDnsWarning, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 8, 0, 0
        ));

        TextView rulesAction = UiKit.button(
                this, getString(R.string.dns_rules_action), false);
        rulesAction.setOnClickListener(view ->
                startActivity(new Intent(this, DnsRulesActivity.class)));
        card.addView(rulesAction, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 46),
                0, 12, 0, 0
        ));

        dnsAction = UiKit.button(this, getString(R.string.dns_start), true);
        dnsAction.setOnClickListener(view -> toggleDnsFilter());
        card.addView(dnsAction, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 48)
        ));

        TextView note = UiKit.text(this, getString(R.string.dns_note),
                11f, getColor(R.color.muted), false);
        card.addView(note, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 9, 0, 0
        ));
        return card;
    }

    private View buildBrandRow() {
        LinearLayout row = UiKit.horizontal(this);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_skip);
        icon.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        icon.setBackgroundResource(R.drawable.bg_icon_tile);
        row.addView(icon, UiKit.lp(UiKit.dp(this, 46), UiKit.dp(this, 46)));

        LinearLayout titles = UiKit.vertical(this);
        TextView appName = UiKit.text(this, getString(R.string.app_name),
                24f, getColor(R.color.ink), true);
        TextView tagline = UiKit.text(this, getString(R.string.app_tagline),
                13f, getColor(R.color.muted), false);
        titles.addView(appName);
        titles.addView(tagline, UiKit.margins(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 1, 0, 0
        ));
        LinearLayout.LayoutParams titleParams = UiKit.weighted(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        titleParams.setMargins(UiKit.dp(this, 14), 0, 0, 0);
        row.addView(titles, titleParams);
        return row;
    }

    private View buildStatusPanel() {
        LinearLayout panel = UiKit.vertical(this);
        panel.setBackgroundResource(R.drawable.bg_dark_panel);
        panel.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 17),
                UiKit.dp(this, 18), UiKit.dp(this, 17));

        LinearLayout heading = UiKit.horizontal(this);
        ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.ic_shield);
        heading.addView(shield, UiKit.lp(UiKit.dp(this, 28), UiKit.dp(this, 28)));

        LinearLayout statusText = UiKit.vertical(this);
        statusTitle = UiKit.text(this, getString(R.string.status_inactive),
                20f, getColor(R.color.white), true);
        statusDetail = UiKit.text(this, getString(R.string.status_inactive_detail),
                13f, Color.parseColor("#CFE3DC"), false);
        statusText.addView(statusTitle);
        statusText.addView(statusDetail, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
        ));
        LinearLayout.LayoutParams statusTextParams = UiKit.weighted(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        statusTextParams.setMargins(UiKit.dp(this, 12), 0, 0, 0);
        heading.addView(statusText, statusTextParams);

        statusBadge = UiKit.text(this, "OFF", 11f, Color.WHITE, true);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4),
                UiKit.dp(this, 8), UiKit.dp(this, 4));
        heading.addView(statusBadge, UiKit.margins(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                12, 0, 0, 0
        ));
        panel.addView(heading, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#356F63"));
        panel.addView(line, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 1),
                0, 16, 0, 14
        ));

        LinearLayout stats = UiKit.horizontal(this);
        LinearLayout countGroup = UiKit.vertical(this);
        skipCountView = UiKit.text(this, "0", 22f, Color.WHITE, true);
        TextView countLabel = UiKit.text(this, getString(R.string.skip_count_label),
                12f, Color.parseColor("#BFD7D0"), false);
        countGroup.addView(skipCountView);
        countGroup.addView(countLabel);
        stats.addView(countGroup, UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.34f));

        LinearLayout recentGroup = UiKit.vertical(this);
        TextView recentLabel = UiKit.text(this, getString(R.string.recent_action_label),
                12f, Color.parseColor("#BFD7D0"), false);
        recentActionView = UiKit.text(this, getString(R.string.recent_action_empty),
                13f, Color.WHITE, false);
        recentActionView.setMaxLines(2);
        recentGroup.addView(recentLabel);
        recentGroup.addView(recentActionView, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
        ));
        stats.addView(recentGroup, UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.66f));
        panel.addView(stats);
        return panel;
    }

    private View sectionHeading(String title, String action) {
        LinearLayout row = UiKit.horizontal(this);
        TextView titleView = UiKit.text(this, title, 17f, getColor(R.color.ink), true);
        row.addView(titleView, UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            TextView actionView = UiKit.text(this, action, 13f, getColor(R.color.accent), true);
            actionView.setGravity(Gravity.CENTER_VERTICAL);
            actionView.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6),
                    UiKit.dp(this, 2), UiKit.dp(this, 6));
            actionView.setOnClickListener(view -> {
                SettingsStore.resetStats(this);
                refreshState();
                Toast.makeText(this, R.string.stats_reset_done, Toast.LENGTH_SHORT).show();
            });
            row.addView(actionView);
        }
        return row;
    }

    private View buildBackgroundCard() {
        LinearLayout card = UiKit.vertical(this);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 6),
                UiKit.dp(this, 16), UiKit.dp(this, 6));
        card.setBackground(UiKit.bordered(
                this,
                getColor(R.color.surface),
                getColor(R.color.border),
                8f
        ));

        TextView description = UiKit.text(this, getString(R.string.background_description),
                12f, getColor(R.color.muted), false);
        description.setPadding(0, UiKit.dp(this, 10),
                0, UiKit.dp(this, 10));
        card.addView(description);
        addDivider(card);

        batteryAction = addBackgroundRow(
                card,
                getString(R.string.battery_whitelist),
                getString(R.string.battery_description),
                view -> openBatterySettings()
        );
        addDivider(card);
        addBackgroundRow(
                card,
                getString(R.string.redmi_autostart_title),
                getString(R.string.redmi_autostart_description),
                view -> openMiuiAutostartSettings()
        );
        addDivider(card);
        addBackgroundRow(
                card,
                getString(R.string.redmi_power_title),
                getString(R.string.redmi_power_description),
                view -> openMiuiPowerSettings()
        );
        return card;
    }

    private TextView addBackgroundRow(
            LinearLayout parent,
            String title,
            String description,
            View.OnClickListener click
    ) {
        LinearLayout row = UiKit.vertical(this);
        row.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 12));
        TextView titleView = UiKit.text(this, title, 14f, getColor(R.color.ink), true);
        TextView detailView = UiKit.text(this, description, 12f, getColor(R.color.muted), false);
        row.addView(titleView);
        row.addView(detailView, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
        ));
        row.setOnClickListener(click);
        parent.addView(row);
        return titleView;
    }

    private View buildSettingsCard() {
        LinearLayout card = UiKit.vertical(this);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 6),
                UiKit.dp(this, 16), UiKit.dp(this, 6));
        card.setBackground(UiKit.bordered(
                this,
                getColor(R.color.surface),
                getColor(R.color.border),
                8f
        ));

        autoCloseSwitch = addSwitchRow(
                card,
                getString(R.string.auto_close_title),
                getString(R.string.auto_close_description)
        );
        autoCloseSwitch.setChecked(SettingsStore.autoClose(this));
        autoCloseSwitch.setOnCheckedChangeListener((button, checked) ->
                SettingsStore.setAutoClose(this, checked));

        addDivider(card);
        cornerSwitch = addSwitchRow(
                card,
                getString(R.string.corner_fallback_title),
                getString(R.string.corner_fallback_description)
        );
        cornerSwitch.setChecked(SettingsStore.cornerFallback(this));
        cornerSwitch.setOnCheckedChangeListener((button, checked) ->
                SettingsStore.setCornerFallback(this, checked));

        addDivider(card);
        sensitivityValue = addSelectionRow(
                card,
                getString(R.string.sensitivity_title),
                getString(R.string.sensitivity_description),
                view -> showSensitivityDialog()
        );
        return card;
    }

    private TextView addSelectionRow(
            LinearLayout parent,
            String title,
            String description,
            View.OnClickListener click
    ) {
        LinearLayout row = UiKit.horizontal(this);
        row.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 12));

        LinearLayout labels = UiKit.vertical(this);
        labels.addView(UiKit.text(this, title, 14f, getColor(R.color.ink), true));
        labels.addView(UiKit.text(this, description, 12f, getColor(R.color.muted), false),
                UiKit.margins(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        0, 3, 0, 0
                ));
        row.addView(labels, UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = UiKit.text(this, "", 12f, getColor(R.color.accent_dark), true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(UiKit.dp(this, 9), UiKit.dp(this, 5),
                UiKit.dp(this, 9), UiKit.dp(this, 5));
        value.setBackground(UiKit.rounded(this, getColor(R.color.accent_soft), 5f));
        row.addView(value, UiKit.margins(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                12, 0, 0, 0
        ));
        row.setOnClickListener(click);
        parent.addView(row, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return value;
    }

    private void showSensitivityDialog() {
        final int[] levels = {
                SensitivityProfile.LEVEL_CONSERVATIVE,
                SensitivityProfile.LEVEL_STANDARD,
                SensitivityProfile.LEVEL_SENSITIVE,
                SensitivityProfile.LEVEL_AGGRESSIVE
        };
        String[] labels = new String[levels.length];
        int checked = 0;
        int current = SettingsStore.sensitivity(this);
        for (int i = 0; i < levels.length; i++) {
            labels[i] = sensitivityLabel(levels[i]);
            if (levels[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sensitivity_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    SettingsStore.setSensitivity(this, levels[which]);
                    dialog.dismiss();
                    refreshState();
                    Toast.makeText(this, R.string.sensitivity_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String sensitivityLabel(int level) {
        switch (level) {
            case SensitivityProfile.LEVEL_CONSERVATIVE:
                return getString(R.string.sensitivity_conservative);
            case SensitivityProfile.LEVEL_SENSITIVE:
                return getString(R.string.sensitivity_sensitive);
            case SensitivityProfile.LEVEL_AGGRESSIVE:
                return getString(R.string.sensitivity_aggressive);
            case SensitivityProfile.LEVEL_STANDARD:
            default:
                return getString(R.string.sensitivity_standard);
        }
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String description) {
        LinearLayout row = UiKit.horizontal(this);
        row.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 12));

        LinearLayout labels = UiKit.vertical(this);
        labels.addView(UiKit.text(this, title, 14f, getColor(R.color.ink), true));
        TextView detail = UiKit.text(this, description, 12f, getColor(R.color.muted), false);
        labels.addView(detail, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 3, 0, 0
        ));
        row.addView(labels, UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        row.addView(toggle, UiKit.margins(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                14, 0, 0, 0
        ));
        parent.addView(row, UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return toggle;
    }

    private void addDivider(LinearLayout parent) {
        parent.addView(UiKit.divider(this, getColor(R.color.border)), UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 1)
        ));
    }

    private View buildRulesCard() {
        LinearLayout card = UiKit.vertical(this);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 16));
        card.setBackground(UiKit.bordered(
                this,
                getColor(R.color.surface),
                getColor(R.color.border),
                8f
        ));

        TextView description = UiKit.text(this, getString(R.string.rules_description),
                13f, getColor(R.color.muted), false);
        card.addView(description);
        TextView manage = UiKit.button(this, getString(R.string.manage_app_rules), false);
        manage.setOnClickListener(view -> startActivity(
                new Intent(this, AppRulesActivity.class)
        ));
        card.addView(manage, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 48),
                0, 14, 0, 0
        ));
        return card;
    }

    private View buildInfoPanel(String title, String body, boolean warning) {
        LinearLayout panel = UiKit.vertical(this);
        panel.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14),
                UiKit.dp(this, 16), UiKit.dp(this, 14));
        panel.setBackgroundResource(warning ? R.drawable.bg_warning : R.drawable.bg_secondary_button);

        TextView titleView = UiKit.text(
                this,
                title,
                14f,
                getColor(warning ? R.color.warning_ink : R.color.ink),
                true
        );
        TextView bodyView = UiKit.text(
                this,
                body,
                12f,
                getColor(warning ? R.color.warning_ink : R.color.muted),
                false
        );
        panel.addView(titleView);
        panel.addView(bodyView, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 5, 0, 0
        ));
        return panel;
    }

    private void refreshState() {
        boolean enabled = isAccessibilityEnabled();
        int count = SettingsStore.skipCount(this);
        String lastAction = SettingsStore.lastAction(this);

        if (statusTitle == null) {
            return;
        }
        statusTitle.setText(enabled ? R.string.status_active : R.string.status_inactive);
        statusDetail.setText(enabled
                ? R.string.status_active_detail
                : R.string.status_inactive_detail);
        primaryAction.setText(enabled
                ? R.string.accessibility_enabled_action
                : R.string.open_accessibility);
        statusBadge.setText(enabled ? "ON" : "OFF");
        statusBadge.setBackground(roundedBadge(enabled));
        skipCountView.setText(String.valueOf(count));
        recentActionView.setText(lastAction.isEmpty()
                ? getString(R.string.recent_action_empty)
                : lastAction);

        boolean batteryIgnored = isBatteryWhitelisted();
        batteryAction.setText(batteryIgnored
                ? R.string.battery_whitelisted
                : R.string.battery_whitelist);
        autoCloseSwitch.setChecked(SettingsStore.autoClose(this));
        cornerSwitch.setChecked(SettingsStore.cornerFallback(this));
        if (sensitivityValue != null) {
            sensitivityValue.setText(sensitivityLabel(SettingsStore.sensitivity(this)));
        }
        dnsAutoLearnSwitch.setChecked(SettingsStore.autoLearn(this));
        dnsFullListSwitch.setChecked(SettingsStore.dnsFullList(this));
        dnsStrictSwitch.setChecked(SettingsStore.dnsStrictMode(this));
        privateDnsCached = isPrivateDnsActive();
        refreshDnsState();
    }

    private void refreshDnsState() {
        if (dnsStatusView == null) {
            return;
        }
        int state = SettingsStore.dnsState(this);
        if ((state == SettingsStore.DNS_STATE_STARTING
                || state == SettingsStore.DNS_STATE_RUNNING)
                && !DnsVpnService.isAlive()) {
            long age = System.currentTimeMillis() - SettingsStore.dnsStateChangedAt(this);
            if (state == SettingsStore.DNS_STATE_RUNNING || age > 3_000L) {
                SettingsStore.setDnsState(this, SettingsStore.DNS_STATE_IDLE);
                state = SettingsStore.DNS_STATE_IDLE;
            }
        }
        boolean running = state == SettingsStore.DNS_STATE_RUNNING;
        boolean starting = state == SettingsStore.DNS_STATE_STARTING;
        boolean error = state == SettingsStore.DNS_STATE_ERROR;
        // The ticker runs once per second; rebuilding the badge drawable and the
        // button styling on every tick made the page re-layout for no reason.
        if (state != lastDnsUiState) {
            lastDnsUiState = state;
            if (starting) {
                dnsStatusView.setText(R.string.dns_starting);
                dnsStatusView.setBackground(roundedDnsBadge(false));
                dnsAction.setText(R.string.dns_stop);
                dnsAction.setBackgroundResource(R.drawable.bg_secondary_button);
                dnsAction.setTextColor(getColor(R.color.ink));
            } else if (running) {
                dnsStatusView.setText(R.string.dns_on);
                dnsStatusView.setBackground(roundedDnsBadge(true));
                dnsAction.setText(R.string.dns_stop);
                dnsAction.setBackgroundResource(R.drawable.bg_secondary_button);
                dnsAction.setTextColor(getColor(R.color.ink));
            } else {
                dnsStatusView.setText(R.string.dns_off);
                dnsStatusView.setBackground(roundedDnsBadge(false));
                dnsAction.setText(R.string.dns_start);
                dnsAction.setBackgroundResource(R.drawable.bg_primary_button);
                dnsAction.setTextColor(getColor(R.color.white));
            }
            if (error) {
                dnsStatusView.setText(R.string.dns_error);
                dnsStatusView.setBackground(roundedDnsBadge(false));
            }
        }
        dnsCountView.setText(getString(
                R.string.dns_count_value,
                SettingsStore.dnsBlockCount(this)
        ));
        DnsFilterSnapshot snapshot = DnsVpnService.currentSnapshot();
        int rules = snapshot.totalRules();
        String ruleText = rules == 0
                ? getString(R.string.dns_rule_loading_text)
                : getString(R.string.dns_rule_value, rules);
        if (!ruleText.contentEquals(dnsRuleView.getText())) {
            dnsRuleView.setText(ruleText);
        }
        String learnedText = getString(
                R.string.auto_learn_manage,
                AutoLearnStore.learnedCount(this)
        );
        if (!learnedText.contentEquals(dnsLearnedView.getText())) {
            dnsLearnedView.setText(learnedText);
        }
        if (dnsBlockModeValue != null) {
            String modeText = blockModeLabel(SettingsStore.dnsBlockMode(this));
            if (!modeText.contentEquals(dnsBlockModeValue.getText())) {
                dnsBlockModeValue.setText(modeText);
            }
        }
        if (dnsPrivateDnsWarning != null) {
            dnsPrivateDnsWarning.setVisibility(privateDnsCached
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void showAutoLearnedDomains() {
        List<String> domains = AutoLearnStore.learnedDomains(this);
        String message = domains.isEmpty()
                ? getString(R.string.auto_learn_empty)
                : String.join("\n", domains);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.auto_learn_list_title)
                .setMessage(message)
                .setPositiveButton(R.string.close, null);
        if (!domains.isEmpty()) {
            builder.setNeutralButton(R.string.clear_all, (dialog, which) -> {
                AutoLearnStore.clear(this);
                refreshDnsState();
                Toast.makeText(this, R.string.auto_learn_cleared, Toast.LENGTH_SHORT).show();
            });
        }
        builder.show();
    }

    private void showDnsBlockModeDialog() {
        final int[] modes = {
                DnsPacket.BLOCK_MODE_SINKHOLE,
                DnsPacket.BLOCK_MODE_NXDOMAIN
        };
        String[] labels = {
                getString(R.string.dns_block_mode_sinkhole),
                getString(R.string.dns_block_mode_nxdomain)
        };
        int current = SettingsStore.dnsBlockMode(this);
        int checked = current == DnsPacket.BLOCK_MODE_NXDOMAIN ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.dns_block_mode_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    SettingsStore.setDnsBlockMode(this, modes[which]);
                    dialog.dismiss();
                    DnsVpnService.reloadRules();
                    refreshDnsState();
                    Toast.makeText(this, R.string.dns_block_mode_saved,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String blockModeLabel(int mode) {
        return mode == DnsPacket.BLOCK_MODE_NXDOMAIN
                ? getString(R.string.dns_block_mode_nxdomain)
                : getString(R.string.dns_block_mode_sinkhole);
    }

    private boolean isPrivateDnsActive() {
        if (Build.VERSION.SDK_INT < 28) {
            return false;
        }
        try {
            String mode = Settings.Global.getString(
                    getContentResolver(), "private_dns_mode");
            return mode != null && !mode.isEmpty() && !"off".equals(mode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private GradientDrawable roundedDnsBadge(boolean active) {
        dnsStatusView.setTextColor(active
                ? getColor(R.color.accent_dark)
                : getColor(R.color.muted));
        return UiKit.rounded(
                this,
                active ? getColor(R.color.accent_soft) : Color.parseColor("#EEF1EF"),
                5f
        );
    }

    private void toggleDnsFilter() {
        int state = SettingsStore.dnsState(this);
        if (state == SettingsStore.DNS_STATE_RUNNING
                || state == SettingsStore.DNS_STATE_STARTING) {
            stopDnsFilter();
            return;
        }
        try {
            Intent permissionIntent = VpnService.prepare(this);
            if (permissionIntent == null) {
                startDnsFilterService();
            } else {
                startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST);
            }
        } catch (Throwable ignored) {
            Toast.makeText(this, R.string.dns_start_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void startDnsFilterService() {
        try {
            SettingsStore.setDnsState(this, SettingsStore.DNS_STATE_STARTING);
            refreshDnsState();
            requestDnsNotificationPermissionIfNeeded();
            Intent intent = new Intent(this, DnsVpnService.class)
                    .setAction(DnsVpnService.ACTION_START);
            startForegroundService(intent);
            uiHandler.postDelayed(this::refreshDnsState, 900L);
            uiHandler.postDelayed(() -> {
                if (!DnsVpnService.isRunning()) {
                    int current = SettingsStore.dnsState(this);
                    if (current == SettingsStore.DNS_STATE_STARTING
                            && !DnsVpnService.isAlive()) {
                        SettingsStore.setDnsState(this, SettingsStore.DNS_STATE_IDLE);
                    }
                }
                refreshDnsState();
            }, 2_200L);
        } catch (Throwable ignored) {
            SettingsStore.setDnsState(this, SettingsStore.DNS_STATE_IDLE);
            Toast.makeText(this, R.string.dns_start_failed, Toast.LENGTH_LONG).show();
            refreshDnsState();
        }
    }

    private void requestDnsNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    VPN_PERMISSION_REQUEST + 1
            );
        }
    }

    private void stopDnsFilter() {
        try {
            Intent intent = new Intent(this, DnsVpnService.class)
                    .setAction(DnsVpnService.ACTION_STOP);
            startService(intent);
        } catch (Throwable ignored) {
            Toast.makeText(this, R.string.dns_stop_failed, Toast.LENGTH_LONG).show();
        }
        uiHandler.postDelayed(this::refreshDnsState, 500L);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_PERMISSION_REQUEST) {
            return;
        }
        if (resultCode == RESULT_OK) {
            startDnsFilterService();
        } else {
            Toast.makeText(this, R.string.dns_permission_cancelled, Toast.LENGTH_LONG).show();
        }
    }

    private GradientDrawable roundedBadge(boolean enabled) {
        GradientDrawable drawable = UiKit.rounded(
                this,
                Color.parseColor(enabled ? "#DDF5E9" : "#355D55"),
                5f
        );
        statusBadge.setTextColor(enabled
                ? getColor(R.color.accent_dark)
                : Color.WHITE);
        return drawable;
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> services =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo service : services) {
            if (service.getResolveInfo() != null
                    && service.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(
                    service.getResolveInfo().serviceInfo.packageName)
                    && service.getResolveInfo().serviceInfo.name != null
                    && service.getResolveInfo().serviceInfo.name.endsWith(
                    AdSkipAccessibilityService.class.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isBatteryWhitelisted() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openMiuiAutostartSettings() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ));
            startActivity(intent);
        } catch (Throwable ignored) {
            openAppDetailsSettings();
        }
    }

    private void openMiuiPowerSettings() {
        try {
            Intent intent = new Intent("miui.intent.action.APP_POWERKEEPER")
                    .putExtra("package_name", getPackageName())
                    .putExtra("package_label", getString(R.string.app_name));
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            ));
            intent.putExtra("package_name", getPackageName());
            intent.putExtra("package_label", getString(R.string.app_name));
            startActivity(intent);
        } catch (Throwable ignored) {
            openAppDetailsSettings();
        }
    }

    private void openAppDetailsSettings() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
        } catch (Throwable ignored) {
            Toast.makeText(this, R.string.background_settings_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showAccessibilityDisclosure() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_disclosure_title)
                .setMessage(R.string.accessibility_disclosure_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_label, (dialog, which) ->
                        openAccessibilitySettings())
                .show();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable ignored) {
            Toast.makeText(this, R.string.service_error_toast, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("BatteryLife")
    private void openBatterySettings() {
        try {
            if (isBatteryWhitelisted()) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            }
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Throwable ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignoredAgain) {
                Toast.makeText(this, R.string.battery_error_toast, Toast.LENGTH_LONG).show();
            }
        }
    }
}

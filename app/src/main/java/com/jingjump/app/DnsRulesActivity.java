package com.jingjump.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * DNS rule management page: live rule breakdown, manual black/whitelists,
 * subscription file import and the recent block log.
 */
public final class DnsRulesActivity extends Activity {
    private static final int IMPORT_REQUEST = 3100;
    private static final long LOG_REFRESH_MS = 1200L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int lastLogVersion = -1;
    private final Runnable logTicker = new Runnable() {
        @Override
        public void run() {
            if (DnsBlockLog.version() != lastLogVersion) {
                refreshLog();
            }
            uiHandler.postDelayed(this, LOG_REFRESH_MS);
        }
    };

    private TextView statsView;
    private TextView blacklistValue;
    private TextView whitelistValue;
    private TextView importInfoView;
    private LinearLayout logList;
    private boolean importRunning;
    private final SimpleDateFormat logTime =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        setContentView(buildScreen());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        uiHandler.removeCallbacks(logTicker);
        uiHandler.postDelayed(logTicker, LOG_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(logTicker);
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
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(getColor(R.color.background));
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        frame.addView(scrollView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout root = UiKit.vertical(this);
        int side = UiKit.dp(this, 18);
        root.setPadding(side, UiKit.dp(this, 10), side, UiKit.dp(this, 24));
        scrollView.addView(root, new ScrollView.LayoutParams(
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
        titleGroup.addView(UiKit.text(this, getString(R.string.dns_rules_title),
                22f, getColor(R.color.ink), true));
        titleGroup.addView(UiKit.text(this, getString(R.string.log_click_hint),
                12f, getColor(R.color.muted), false));
        LinearLayout.LayoutParams titleParams = UiKit.weighted(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(UiKit.dp(this, 10), 0, 0, 0);
        toolbar.addView(titleGroup, titleParams);
        root.addView(toolbar);

        UiKit.addSpace(root, this, 8);
        root.addView(statsCard(), cardMargins());
        UiKit.addSpace(root, this, 12);
        root.addView(listsCard(), cardMargins());
        UiKit.addSpace(root, this, 12);
        root.addView(importCard(), cardMargins());
        UiKit.addSpace(root, this, 12);

        LinearLayout logCard = card();
        LinearLayout logHeading = UiKit.horizontal(this);
        logHeading.addView(UiKit.text(this, getString(R.string.log_section),
                        14f, getColor(R.color.ink), true),
                UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clearLog = UiKit.text(this, getString(R.string.log_clear),
                12f, getColor(R.color.accent), true);
        clearLog.setOnClickListener(view -> {
            DnsBlockLog.clear();
            refreshLog();
        });
        logHeading.addView(clearLog);
        logCard.addView(logHeading);
        logList = UiKit.vertical(this);
        logCard.addView(logList);
        root.addView(logCard, cardMargins());
        return frame;
    }

    private LinearLayout card() {
        LinearLayout card = UiKit.vertical(this);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12),
                UiKit.dp(this, 16), UiKit.dp(this, 12));
        card.setBackground(UiKit.bordered(
                this, getColor(R.color.surface), getColor(R.color.border), 8f));
        return card;
    }

    private ViewGroup.MarginLayoutParams cardMargins() {
        return UiKit.margins(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0);
    }

    private View statsCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.dns_stats_section),
                14f, getColor(R.color.ink), true));
        statsView = UiKit.text(this, "", 12f, getColor(R.color.muted), false);
        card.addView(statsView, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 2));
        card.addView(UiKit.text(this, getString(R.string.dns_attribution),
                11f, getColor(R.color.muted), false), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        return card;
    }

    private View listsCard() {
        LinearLayout card = card();
        blacklistValue = addListRow(card, getString(R.string.list_edit_blacklist),
                view -> showListEditor(false));
        addDivider(card);
        whitelistValue = addListRow(card, getString(R.string.list_edit_whitelist),
                view -> showListEditor(true));
        return card;
    }

    private TextView addListRow(LinearLayout parent, String title, View.OnClickListener click) {
        LinearLayout row = UiKit.horizontal(this);
        row.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 10));
        row.addView(UiKit.text(this, title, 14f, getColor(R.color.ink), true),
                UiKit.weighted(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = UiKit.text(this, "", 12f, getColor(R.color.accent_dark), true);
        row.addView(value);
        row.setOnClickListener(click);
        parent.addView(row);
        return value;
    }

    private View importCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, getString(R.string.import_title),
                14f, getColor(R.color.ink), true));
        card.addView(UiKit.text(this, getString(R.string.import_description),
                12f, getColor(R.color.muted), false), UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 8));
        importInfoView = UiKit.text(this, getString(R.string.import_empty),
                12f, getColor(R.color.ink), false);
        card.addView(importInfoView);
        LinearLayout actions = UiKit.horizontal(this);
        TextView importButton = UiKit.button(this, getString(R.string.import_button), true);
        importButton.setOnClickListener(view -> pickImportFile());
        actions.addView(importButton, UiKit.weighted(0, UiKit.dp(this, 46), 1f));
        TextView clearButton = UiKit.button(this, getString(R.string.import_clear), false);
        clearButton.setOnClickListener(view -> {
            DnsRuleStore.clearImport(this);
            DnsVpnService.reloadRules();
            refreshAll();
            Toast.makeText(this, R.string.dns_reload_done, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clearParams = UiKit.margins(
                UiKit.dp(this, 0), UiKit.dp(this, 46), 10, 0, 0, 0);
        clearParams.weight = 0.7f;
        actions.addView(clearButton, clearParams);
        card.addView(actions, UiKit.margins(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        return card;
    }

    private void addDivider(LinearLayout parent) {
        parent.addView(UiKit.divider(this, getColor(R.color.border)), UiKit.lp(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)));
    }

    // ----------------------------------------------------------------- logic

    private void refreshAll() {
        DnsFilterSnapshot snapshot = DnsVpnService.currentSnapshot();
        boolean running = DnsVpnService.isRunning();
        StringBuilder stats = new StringBuilder();
        if (running && snapshot.totalRules() > 0) {
            stats.append(getString(R.string.dns_stats_builtin, snapshot.builtinSize()));
            stats.append('\n');
            stats.append(getString(R.string.dns_stats_full, snapshot.fullSize(),
                    getString(SettingsStore.dnsFullList(this)
                            ? R.string.dns_switch_on : R.string.dns_switch_off)));
            stats.append('\n');
            stats.append(getString(R.string.dns_stats_custom,
                    snapshot.customBlockSize(), snapshot.allowSize()));
            stats.append('\n');
            stats.append(getString(R.string.dns_stats_learned, AutoLearnStore.learnedCount(this)));
            stats.append('\n');
            stats.append(getString(R.string.dns_stats_total, snapshot.totalRules()));
            stats.append(" · ");
            stats.append(getString(R.string.dns_count_value, SettingsStore.dnsBlockCount(this)));
        } else {
            stats.append(getString(R.string.dns_stats_full, 0,
                    getString(SettingsStore.dnsFullList(this)
                            ? R.string.dns_switch_on : R.string.dns_switch_off)));
            stats.append('\n');
            stats.append(getString(R.string.dns_stats_custom,
                    customListCount(SettingsStore.dnsCustomBlockText(this), false),
                    customListCount(SettingsStore.dnsCustomAllowText(this), true)));
            stats.append('\n');
            stats.append(getString(R.string.dns_stats_learned, AutoLearnStore.learnedCount(this)));
            stats.append('\n');
            stats.append(getString(R.string.dns_count_value, SettingsStore.dnsBlockCount(this)));
        }
        statsView.setText(stats);

        blacklistValue.setText(String.format(Locale.getDefault(), "%d",
                customListCount(SettingsStore.dnsCustomBlockText(this), false)));
        whitelistValue.setText(String.format(Locale.getDefault(), "%d",
                customListCount(SettingsStore.dnsCustomAllowText(this), true)));

        refreshImportInfo();
        refreshLog();
    }

    /**
     * Counts manual-list entries with the same full parser DnsRuleStore.load()
     * uses, so the badge never disagrees with the rules actually in effect
     * (plain domains, ||d^, @@||d^ and hosts lines all count).
     */
    private int customListCount(String text, boolean allowList) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        try {
            DnsRuleParser.Parsed parsed = DnsRuleParser.parse(
                    new java.io.StringReader(text),
                    DnsRuleStore.MAX_USER_LIST_RULES);
            return allowList
                    ? parsed.blocked.size() + parsed.allowed.size()
                    : parsed.blocked.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void refreshImportInfo() {
        String meta = SettingsStore.dnsImportMeta(this);
        if (meta == null || meta.isEmpty()) {
            importInfoView.setText(R.string.import_empty);
            return;
        }
        String[] parts = meta.split("\\|");
        if (parts.length < 6) {
            importInfoView.setText(R.string.import_empty);
            return;
        }
        try {
            importInfoView.setText(getString(
                    R.string.import_info,
                    parts[0],
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    "1".equals(parts[4]) ? getString(R.string.import_truncated) : ""
            ));
        } catch (NumberFormatException corrupted) {
            // A malformed meta line must never crash the page.
            importInfoView.setText(R.string.import_empty);
        }
    }

    private void refreshLog() {
        lastLogVersion = DnsBlockLog.version();
        logList.removeAllViews();
        List<DnsBlockLog.Entry> entries = DnsBlockLog.snapshot();
        if (entries.isEmpty()) {
            logList.addView(UiKit.text(this, getString(R.string.log_empty),
                    12f, getColor(R.color.muted), false), UiKit.margins(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
            return;
        }
        for (DnsBlockLog.Entry entry : entries) {
            TextView row = UiKit.text(this, formatEntry(entry),
                    12f, getColor(R.color.ink), false);
            row.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 7));
            row.setOnClickListener(view -> showAllowConfirm(entry.domain));
            logList.addView(row);
        }
    }

    private String formatEntry(DnsBlockLog.Entry entry) {
        return logTime.format(new Date(entry.timeMillis))
                + "  " + entry.domain
                + "  (" + DnsBlockLog.queryTypeLabel(entry.queryType) + ")"
                + "  " + sourceLabel(entry.source)
                + (entry.count > 1 ? " ×" + entry.count : "");
    }

    private String sourceLabel(int source) {
        switch (source) {
            case DnsFilterSnapshot.SOURCE_BUILTIN:
                return getString(R.string.source_builtin);
            case DnsFilterSnapshot.SOURCE_FULL:
                return getString(R.string.source_full);
            case DnsFilterSnapshot.SOURCE_IMPORTED:
                return getString(R.string.source_imported);
            case DnsFilterSnapshot.SOURCE_CUSTOM:
                return getString(R.string.source_custom);
            case DnsFilterSnapshot.SOURCE_LEARNED:
                return getString(R.string.source_learned);
            case DnsFilterSnapshot.SOURCE_DOH:
                return getString(R.string.source_doh);
            case DnsFilterSnapshot.SOURCE_CNAME:
                return getString(R.string.source_cname);
            default:
                return "";
        }
    }

    private void showListEditor(boolean allowList) {
        EditText editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHint(allowList
                ? R.string.list_whitelist_hint : R.string.list_blacklist_hint);
        editor.setTextSize(13f);
        editor.setMinLines(6);
        editor.setMaxLines(12);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setBackgroundResource(R.drawable.bg_input);
        editor.setTextColor(getColor(R.color.ink));
        editor.setHintTextColor(getColor(R.color.muted));
        editor.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12), UiKit.dp(this, 10));
        editor.setText(allowList
                ? SettingsStore.dnsCustomAllowText(this)
                : SettingsStore.dnsCustomBlockText(this));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(editor, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(allowList
                        ? R.string.list_edit_whitelist : R.string.list_edit_blacklist)
                .setView(scroll)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String value = editor.getText().toString();
                    if (allowList) {
                        SettingsStore.setDnsCustomAllowText(this, value);
                    } else {
                        SettingsStore.setDnsCustomBlockText(this, value);
                    }
                    DnsVpnService.reloadRules();
                    refreshAll();
                    Toast.makeText(this, R.string.list_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAllowConfirm(String domain) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.log_add_allow_title)
                .setMessage(getString(R.string.log_add_allow_message, domain))
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String current = SettingsStore.dnsCustomAllowText(this);
                    String merged = current.isEmpty() ? domain : current.trim() + "\n" + domain;
                    SettingsStore.setDnsCustomAllowText(this, merged);
                    DnsVpnService.reloadRules();
                    refreshAll();
                    Toast.makeText(this, R.string.log_added_allow, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickImportFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, IMPORT_REQUEST);
        } catch (Throwable ignored) {
            Toast.makeText(this, getString(R.string.import_failed, "SAF"),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null || importRunning) {
            return;
        }
        importRunning = true;
        Toast.makeText(this, R.string.import_working, Toast.LENGTH_SHORT).show();
        final String displayName = queryDisplayName(uri);
        // Persist the raw bytes first (bounded, off the UI thread), then parse
        // and reload so a large subscription never blocks or crashes the page.
        new Thread(() -> {
            int count = 0;
            int skipped = 0;
            String failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                DnsRuleParser.Parsed parsed = DnsRuleStore.importRules(this, input);
                count = parsed.ruleCount();
                skipped = parsed.skippedUnsupported;
                SettingsStore.setDnsImportMeta(
                        this, DnsRuleStore.describeImport(parsed, displayName));
                DnsVpnService.reloadRules();
            } catch (Throwable error) {
                failure = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
            }
            final int okCount = count;
            final int okSkipped = skipped;
            final String failReason = failure;
            runOnUiThread(() -> {
                importRunning = false;
                refreshAll();
                if (failReason == null) {
                    Toast.makeText(this,
                            getString(R.string.import_done, okCount, okSkipped),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            getString(R.string.import_failed, failReason),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "jingjump-dns-import").start();
    }

    private String queryDisplayName(Uri uri) {
        String fallback = uri.getLastPathSegment();
        if (fallback == null || fallback.contains("/")) {
            fallback = "订阅源";
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
            // display name is cosmetic; fall back below
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return fallback;
    }
}

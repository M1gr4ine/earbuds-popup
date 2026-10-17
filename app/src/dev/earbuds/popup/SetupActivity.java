package dev.earbuds.popup;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public final class SetupActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7410;
    private static final int REQUEST_OVERLAY = 7411;
    private static final int REQUEST_MEDIA = 7412;
    private static final int REQUEST_EARBUD_IMAGE = 7413;
    private static final String STATE_IMPORTING_FOR_MODEL = "media.importingForModel";
    private static final int REQUEST_ASSOCIATION = CompanionLink.REQUEST_CODE;
    private static final String ACTION_BATTERY_UPDATED = "dev.earbuds.popup.BATTERY_UPDATED";
    private static final int COLOR_TEXT = Color.rgb(32, 36, 44);
    private static final int COLOR_MUTED = Color.rgb(112, 119, 130);
    private static final int COLOR_ACCENT = Color.rgb(47, 103, 219);
    private static final int[] BACKGROUND_COLORS = {
            0xFFFFFFFF, 0xFFF8F5EF, 0xFFF3F6FA, 0xFF272B32
    };
    private static final String[] BACKGROUND_LABELS = {
            "纯白", "暖白", "雾蓝白", "深灰"
    };

    private LinearLayout content;
    private TextView statusView;
    private TextView mediaView;
    private TextView earbudView;
    private TextView batteryView;
    private TextView saveStateView;
    private DeviceStore.DeviceRecord selected;
    private PopupConfig config;
    private Switch fitCropToggle;
    private boolean syncingFitCropToggle;
    private boolean rebuilding;
    private boolean pendingEnable;
    private boolean pendingPickDevice;
    private boolean pendingPreview;
    private boolean awaitingOverlay;
    private boolean associationPending;
    private String importingForModel;
    private boolean batteryReceiverRegistered;
    private final BroadcastReceiver batteryUpdatedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_BATTERY_UPDATED.equals(intent.getAction())) {
                refreshBattery();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) importingForModel = state.getString(STATE_IMPORTING_FOR_MODEL);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        loadSelectionAndConfig();
        buildSettings();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (importingForModel != null) state.putString(STATE_IMPORTING_FOR_MODEL, importingForModel);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ACTION_BATTERY_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryUpdatedReceiver, filter);
        }
        batteryReceiverRegistered = true;
    }

    @Override protected void onStop() {
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryUpdatedReceiver);
            batteryReceiverRegistered = false;
        }
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume();
        continueAfterOverlay();
        refreshFromStore();
        refreshStatus();
        refreshBattery();
    }

    private void continueAfterOverlay() {
        if (!awaitingOverlay) return;
        awaitingOverlay = false;
        if (Settings.canDrawOverlays(this)) {
            if (pendingEnable) enableMonitoring();
            else if (pendingPreview) previewPopup();
        } else {
            pendingEnable = false;
            pendingPreview = false;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA || requestCode == REQUEST_EARBUD_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                if (requestCode == REQUEST_EARBUD_IMAGE) beginEarbudImport(data.getData());
                else beginImport(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_ASSOCIATION) {
            boolean handled = CompanionLink.handleActivityResult(this, requestCode, resultCode, data);
            associationPending = false;
            if (handled && pendingEnable) {
                if (CompanionLink.hasAssociation(this)) startMonitoring();
                else pendingEnable = false;
            }
            refreshStatus();
            return;
        }
        if (requestCode == REQUEST_OVERLAY) continueAfterOverlay();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            pendingEnable = false;
            pendingPickDevice = false;
            Toast.makeText(this, "未授予所需权限", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        if (pendingEnable) enableMonitoring();
        else if (pendingPickDevice) showBondedDevicePicker(false);
    }

    private void loadSelectionAndConfig() {
        selected = DeviceStore.selected(this);
        config = DeviceStore.loadConfig(this, currentModelId()).sanitized();
    }

    private String currentModelId() {
        return selected == null ? DeviceModel.MODEL_UNKNOWN : selected.modelId;
    }

    private void refreshFromStore() {
        DeviceStore.DeviceRecord fresh = DeviceStore.selected(this);
        if (!sameSelection(selected, fresh)) {
            selected = fresh;
            config = DeviceStore.loadConfig(this, currentModelId()).sanitized();
            buildSettings();
        } else if (config == null) {
            config = DeviceStore.loadConfig(this, currentModelId()).sanitized();
        }
    }

    private static boolean sameSelection(DeviceStore.DeviceRecord a,
                                         DeviceStore.DeviceRecord b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return safe(a.address).equalsIgnoreCase(safe(b.address))
                && safe(a.modelId).equals(safe(b.modelId));
    }

    private void buildSettings() {
        rebuilding = true;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                v.setPadding(safe.left,safe.top,safe.right,safe.bottom);
            }else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}
            return insets;
        });
        scroll.setBackgroundColor(Color.rgb(245, 246, 249));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = text("耳机弹窗", 30, COLOR_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(content, title, 3);
        add(content, text("连接提示、设备型号和每个型号的外观设置", 14, COLOR_MUTED), 20);

        buildDeviceSection();
        buildMonitorSection();
        buildBatterySection();
        buildMediaSection();
        buildAppearanceSection();
        buildActions();

        rebuilding = false;
        refreshStatus();
        refreshBattery();
    }

    private void buildDeviceSection() {
        LinearLayout card = section("设备");
        TextView current = text(selected == null ? "尚未选择耳机" : safe(selected.name),
                18, COLOR_TEXT);
        current.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(card, current, 5);
        add(card, text(selected == null
                ? "选择一台已在系统中配对的耳机，再完成一次设备关联。"
                : "已为此设备保存独立配置。未知型号会使用单独的空白素材配置。",
                13, COLOR_MUTED), 12);

        Button choose = button("选择已配对耳机");
        choose.setOnClickListener(v -> chooseDevice());
        add(card, choose, 12);

        add(card, text("使用型号配置", 13, COLOR_MUTED), 4);
        String[] modelIds = DeviceModel.availableModelIds();
        String[] labels = new String[modelIds.length];
        for (int i = 0; i < modelIds.length; i++) {
            labels[i] = DeviceModel.MODEL_UNKNOWN.equals(modelIds[i])
                    ? "未知型号 · 独立配置" : DeviceModel.displayName(modelIds[i]);
        }
        Spinner modelPicker = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelPicker.setAdapter(adapter);
        modelPicker.setEnabled(selected != null);
        modelPicker.setSelection(spinnerIndex(modelIds, selected == null
                ? DeviceModel.MODEL_UNKNOWN : selected.modelId));
        modelPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                   View view, int position, long id) {
                if (rebuilding || selected == null || position < 0 || position >= modelIds.length) return;
                String next = modelIds[position];
                if (DeviceModel.MODEL_UNKNOWN.equals(next)) {
                    next = DeviceModel.unknownProfileId(selected.address);
                }
                if (next.equals(selected.modelId)) return;
                DeviceStore.setModel(SetupActivity.this, selected.address, next);
                DeviceStore.DeviceRecord updated = DeviceStore.findByAddress(
                        SetupActivity.this, selected.address);
                DeviceStore.select(SetupActivity.this, updated);
                selected = DeviceStore.selected(SetupActivity.this);
                config = DeviceStore.loadConfig(SetupActivity.this, currentModelId()).sanitized();
                buildSettings();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        add(card, modelPicker, 0);
    }

    private void buildMonitorSection() {
        LinearLayout card = section("后台连接提示");
        statusView = text("", 14, COLOR_MUTED);
        statusView.setLineSpacing(dp(4), 1f);
        add(card, statusView, 12);
        LinearLayout actions = row();
        Button enable = button("启用监听");
        enable.setOnClickListener(v -> enableMonitoring());
        Button disable = button("关闭监听");
        disable.setOnClickListener(v -> disableMonitoring());
        add(actions, enable, 0, 1f);
        add(actions, disable, 0, 1f);
        add(card, actions, 0);
    }

    private void buildBatterySection() {
        LinearLayout card = section("电量");
        batteryView = text("", 14, COLOR_MUTED);
        batteryView.setLineSpacing(dp(4), 1f);
        add(card, batteryView, 10);
    }

    private void buildMediaSection() {
        LinearLayout card = section("当前型号素材");
        mediaView = text("", 14, COLOR_MUTED);
        mediaView.setLineSpacing(dp(4), 1f);
        add(card, mediaView, 12);
        LinearLayout actions = row();
        Button importButton = button("导入图片 / GIF / MP4");
        importButton.setOnClickListener(v -> chooseMedia());
        Button clearButton = button("清除素材");
        clearButton.setOnClickListener(v -> clearMedia());
        add(actions, importButton, 0, 1f);
        importButton.setEnabled(selected != null);
        clearButton.setEnabled(selected != null);
        add(actions, clearButton, 0, 1f);
        add(card, actions, 0);
        updateMediaSummary();

        add(card, text("耳机本体", 16, COLOR_TEXT), 2);
        Switch earbudToggle = addSwitch(card, "显示耳机本体", config.showEarbuds,
                (button, checked) -> {
                    config.showEarbuds = checked;
                    updateEarbudSummary();
                });
        earbudToggle.setEnabled(selected != null);
        earbudView = text("", 13, COLOR_MUTED);
        earbudView.setLineSpacing(dp(3), 1f);
        add(card, earbudView, 8);
        LinearLayout earbudActions = row();
        Button importEarbudButton = button("导入耳机透明图");
        importEarbudButton.setOnClickListener(v -> chooseEarbudImage());
        Button clearEarbudButton = button("清除耳机图");
        clearEarbudButton.setOnClickListener(v -> clearEarbudImage());
        importEarbudButton.setEnabled(selected != null);
        clearEarbudButton.setEnabled(selected != null);
        add(earbudActions, importEarbudButton, 0, 1f);
        add(earbudActions, clearEarbudButton, 0, 1f);
        add(card, earbudActions, 0);
        updateEarbudSummary();
    }

    private void buildAppearanceSection() {
        LinearLayout card = section("弹窗外观");
        addPositionPicker(card);
        addSlider(card, "垂直偏移", -80, 80, config.offsetDp,
                value -> value + " dp", value -> config.offsetDp = value);
        addSlider(card, "卡片宽度", 65, 100, config.widthPercent,
                value -> value + "%", value -> config.widthPercent = value);
        addSlider(card, "素材高度", 140, 300, config.mediaHeightDp,
                value -> value + " dp", value -> config.mediaHeightDp = value);
        addSlider(card, "圆角", 0, 48, config.radiusDp,
                value -> value + " dp", value -> config.radiusDp = value);
        addSlider(card, "背景不透明度", 60, 100, config.backgroundAlpha,
                value -> value + "%", value -> config.backgroundAlpha = value);
        addSlider(card, "视频速度", 5, 20, Math.round(config.videoSpeed * 10f),
                value -> String.format(java.util.Locale.ROOT, "%.1f 倍", value / 10f),
                value -> config.videoSpeed = value / 10f);

        Button color = button("卡片底色：" + colorLabel(config.backgroundColor));
        color.setOnClickListener(v -> {
            int next = nextBackgroundColor(config.backgroundColor);
            config.backgroundColor = next;
            saveConfig();
            color.setText("卡片底色：" + colorLabel(next));
        });
        add(card, color, 8);
        fitCropToggle = addSwitch(card, "图片裁切填满", config.fitCrop,
                (button, checked) -> config.fitCrop = checked);
        addSwitch(card, "显示电量信息", config.showBattery,
                (button, checked) -> config.showBattery = checked);
    }

    private void addPositionPicker(LinearLayout card) {
        add(card, text("弹窗位置", 13, COLOR_MUTED), 3);
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"顶部", "居中", "底部"};
        for (int i = 0; i < names.length; i++) {
            RadioButton choice = new RadioButton(this);
            choice.setId(110 + i);
            choice.setText(names[i]);
            choice.setTextSize(14);
            choice.setTextColor(COLOR_TEXT);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setMinHeight(dp(48));
            choice.setPadding(dp(8), 0, dp(8), 0);
            choice.setButtonTintList(stateTintList(COLOR_ACCENT,
                    Color.rgb(142, 151, 165), Color.rgb(188, 196, 207)));
            group.addView(choice, new RadioGroup.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (config.position == i) group.check(choice.getId());
        }
        group.setOnCheckedChangeListener((view, checkedId) -> {
            if (rebuilding || checkedId < 110 || checkedId > 112) return;
            config.position = checkedId - 110;
            saveConfig();
        });
        add(card, group, 10);
    }

    private void addSlider(LinearLayout parent, String label, int min, int max, int initial,
                           IntLabel formatter, IntValueSetter setter) {
        LinearLayout heading = row();
        TextView name = text(label, 14, COLOR_TEXT);
        TextView value = text(formatter.format(clamp(initial, min, max)), 13, COLOR_MUTED);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        add(heading, name, 0, 1f);
        add(heading, value, 0, 0f);
        add(parent, heading, 0);

        SeekBar slider = new SeekBar(this);
        slider.setMax(max - min);
        slider.setProgress(clamp(initial, min, max) - min);
        slider.setMinHeight(dp(48));
        slider.setProgressTintList(stateTintList(COLOR_ACCENT, COLOR_ACCENT,
                Color.rgb(181, 190, 203)));
        slider.setThumbTintList(stateTintList(COLOR_ACCENT, COLOR_ACCENT,
                Color.rgb(181, 190, 203)));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(211, 217, 226)));
        slider.setSecondaryProgressTintList(ColorStateList.valueOf(Color.rgb(211, 217, 226)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                   boolean fromUser) {
                int next = min + progress;
                value.setText(formatter.format(next));
                if (fromUser && !rebuilding) {
                    setter.set(next);
                    saveConfig();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        add(parent, slider, 9);
    }

    private Switch addSwitch(LinearLayout parent, String label, boolean initial,
                           CompoundButton.OnCheckedChangeListener listener) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(14);
        toggle.setTextColor(COLOR_TEXT);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(dp(48));
        toggle.setPadding(0, dp(4), 0, dp(4));
        toggle.setThumbTintList(stateTintList(Color.WHITE, Color.rgb(245, 247, 250),
                Color.rgb(218, 223, 231)));
        toggle.setTrackTintList(stateTintList(Color.rgb(91, 137, 226),
                Color.rgb(194, 202, 214), Color.rgb(224, 228, 235)));
        toggle.setChecked(initial);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (rebuilding || syncingFitCropToggle) return;
            listener.onCheckedChanged(button, checked);
            saveConfig();
        });
        add(parent, toggle, 2);
        return toggle;
    }

    private void buildActions() {
        LinearLayout card = section("保存与预览");
        saveStateView = text("每项调整会立即保存到当前型号。", 13, COLOR_MUTED);
        add(card, saveStateView, 12);
        LinearLayout actions = row();
        Button save = button("保存配置");
        save.setOnClickListener(v -> {
            saveConfig();
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        });
        Button preview = button("预览弹窗");
        preview.setOnClickListener(v -> previewPopup());
        add(actions, save, 0, 1f);
        add(actions, preview, 0, 1f);
        add(card, actions, 10);
        Button reset = button("恢复当前型号默认外观");
        reset.setOnClickListener(v -> resetProfile());
        add(card, reset, 0);
    }

    private LinearLayout section(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(20));
        card.setBackground(background);
        card.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(14);
        content.addView(card, params);
        TextView heading = text(title, 16, COLOR_TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add(card, heading, 12);
        return card;
    }

    private void chooseDevice() {
        pendingPickDevice = true;
        if (!ensureRuntimePermissions(false)) return;
        pendingPickDevice = false;
        showBondedDevicePicker(false);
    }

    private void showBondedDevicePicker(boolean continueEnable) {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            pendingEnable = false;
            Toast.makeText(this, "此设备没有可用的蓝牙适配器", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<BluetoothDevice> bonded;
        try {
            bonded = new ArrayList<>(adapter.getBondedDevices());
        } catch (SecurityException e) {
            pendingEnable = false;
            Toast.makeText(this, "请授予蓝牙设备权限", Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(bonded, new Comparator<BluetoothDevice>() {
            @Override public int compare(BluetoothDevice first, BluetoothDevice second) {
                return safe(first.getName()).compareToIgnoreCase(safe(second.getName()));
            }
        });
        if (bonded.isEmpty()) {
            pendingEnable = false;
            Toast.makeText(this, "请先在系统蓝牙设置中配对耳机", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[bonded.size()];
        for (int i = 0; i < bonded.size(); i++) {
            String name = bonded.get(i).getName();
            labels[i] = name == null || name.trim().isEmpty() ? "蓝牙设备" : name;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择已配对耳机")
                .setItems(labels, (dialog, which) -> {
                    BluetoothDevice device = bonded.get(which);
                    String name = labels[which];
                    DeviceStore.DeviceRecord prior = DeviceStore.findByAddress(
                            this, device.getAddress());
                    int associationId = prior == null ? -1 : prior.associationId;
                    DeviceStore.DeviceRecord record = DeviceStore.upsertDevice(
                            this, device.getAddress(), name, associationId);
                    DeviceStore.select(this, record);
                    loadSelectionAndConfig();
                    buildSettings();
                    if (continueEnable) enableMonitoring();
                })
                .setNegativeButton("取消", (dialog, which) -> pendingEnable = false)
                .show();
    }

    private void enableMonitoring() {
        pendingEnable = true;
        if (!ensureRuntimePermissions(true)) return;
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true;
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
            return;
        }
        if (selected == null) {
            showBondedDevicePicker(true);
            return;
        }
        if (associationPending) return;
        if (Build.VERSION.SDK_INT >= 31 && !CompanionLink.hasAssociation(this)) {
            associationPending = true;
            CompanionLink.requestAssociation(this, selected.address, selected.name, () -> {
                associationPending = false;
                if (pendingEnable) startMonitoring();
                refreshStatus();
            }, () -> {
                associationPending = false;
                pendingEnable = false;
                refreshStatus();
                Toast.makeText(this, "设备关联未完成，后台监听未启动", Toast.LENGTH_SHORT).show();
            });
            refreshStatus();
            return;
        }
        startMonitoring();
    }

    private boolean ensureRuntimePermissions(boolean forEnable) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (forEnable && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) return true;
        requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        return false;
    }

    private void startMonitoring() {
        if (!pendingEnable || selected == null) return;
        pendingEnable = false;
        if (Build.VERSION.SDK_INT >= 31 && !CompanionLink.hasAssociation(this)) {
            Toast.makeText(this, "设备尚未完成系统关联", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        int associationId = getSharedPreferences("popup", MODE_PRIVATE)
                .getInt(CompanionLink.PREF_ASSOCIATION_ID, -1);
        if (associationId > 0) {
            DeviceStore.DeviceRecord linked = DeviceStore.upsertDevice(
                    this, selected.address, selected.name, associationId);
            DeviceStore.select(this, linked);
            selected = DeviceStore.selected(this);
        }
        CompanionLink.resumeObservation(this);
        try {
            startForegroundService(new Intent(this, PopupService.class)
                    .setAction(PopupService.START));
            refreshStatus();
            Toast.makeText(this, "后台连接提示已启用", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "无法启动后台服务，请稍后重试", Toast.LENGTH_LONG).show();
            refreshStatus();
        }
    }

    private void disableMonitoring() {
        pendingEnable = false;
        getSharedPreferences("popup", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        try {
            startService(new Intent(this, PopupService.class).setAction(PopupService.STOP));
        } catch (RuntimeException ignored) {
        }
        refreshStatus();
        Toast.makeText(this, "后台连接提示已关闭", Toast.LENGTH_SHORT).show();
    }

    private void previewPopup() {
        pendingPreview = true;
        if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true;
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQUEST_OVERLAY);
            return;
        }
        pendingPreview = false;
        saveConfig();
        try {
            // Preview is a one-shot action and does not start monitoring or change the saved switch.
            startForegroundService(new Intent(this, PopupService.class)
                    .setAction(PopupService.PREVIEW));
        } catch (RuntimeException e) {
            Toast.makeText(this, "无法启动预览，请确认悬浮窗权限", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseMedia() {
        importingForModel = currentModelId();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"image/*", "video/mp4", "image/gif", "image/webp"});
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_MEDIA);
    }

    private void chooseEarbudImage() {
        importingForModel = currentModelId();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/webp"});
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_EARBUD_IMAGE);
    }

    private void beginImport(Uri uri) {
        final String targetModel = importingForModel == null
                ? currentModelId() : importingForModel;
        Toast.makeText(this, "正在验证并导入素材…", Toast.LENGTH_SHORT).show();
        MediaLibrary.importMedia(this, uri, new MediaLibrary.Callback() {
            @Override public void onImported(String assetId) {
                PopupConfig imported = DeviceStore.loadConfig(SetupActivity.this, targetModel);
                imported.assetId = assetId;
                imported.fitCrop = true;
                DeviceStore.saveConfig(SetupActivity.this, targetModel, imported);
                if (targetModel.equals(currentModelId())) {
                    config = DeviceStore.loadConfig(SetupActivity.this, targetModel);
                    if (fitCropToggle != null) {
                        syncingFitCropToggle = true;
                        try {
                            fitCropToggle.setChecked(config.fitCrop);
                        } finally {
                            syncingFitCropToggle = false;
                        }
                    }
                    updateMediaSummary();
                }
                Toast.makeText(SetupActivity.this, "素材已保存到当前型号", Toast.LENGTH_SHORT).show();
                refreshStatus();
            }

            @Override public void onError(String message) {
                Toast.makeText(SetupActivity.this,
                        message == null ? "素材导入失败，原配置已保留" : message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void beginEarbudImport(Uri uri) {
        final String targetModel = importingForModel == null
                ? currentModelId() : importingForModel;
        Toast.makeText(this, "正在检查耳机图的透明度…", Toast.LENGTH_SHORT).show();
        MediaLibrary.importEarbudImage(this, uri, new MediaLibrary.Callback() {
            @Override public void onImported(String assetId) {
                PopupConfig imported = DeviceStore.loadConfig(SetupActivity.this, targetModel);
                imported.earbudAssetId = assetId;
                DeviceStore.saveConfig(SetupActivity.this, targetModel, imported);
                if (targetModel.equals(currentModelId())) {
                    config = DeviceStore.loadConfig(SetupActivity.this, targetModel);
                    updateEarbudSummary();
                }
                Toast.makeText(SetupActivity.this, "耳机透明图已保存到当前型号",
                        Toast.LENGTH_SHORT).show();
            }

            @Override public void onError(String message) {
                Toast.makeText(SetupActivity.this,
                        message == null ? "耳机图导入失败，原配置已保留" : message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void clearMedia() {
        config.assetId = "";
        saveConfig();
        updateMediaSummary();
    }

    private void clearEarbudImage() {
        config.earbudAssetId = "";
        saveConfig();
        updateEarbudSummary();
    }

    private void resetProfile() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认外观？")
                .setMessage("只重置当前型号的弹窗外观，不会删除导入素材或设备关联。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> {
                    config = PopupConfig.defaults(currentModelId());
                    DeviceStore.saveConfig(this, currentModelId(), config);
                    config = DeviceStore.loadConfig(this, currentModelId()).sanitized();
                    buildSettings();
                    Toast.makeText(this, "当前型号已恢复默认", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void saveConfig() {
        if (config == null) return;
        DeviceStore.saveConfig(this, currentModelId(), config);
        config = DeviceStore.loadConfig(this, currentModelId()).sanitized();
        if (saveStateView != null) saveStateView.setText("已保存到当前型号。");
    }

    private void updateMediaSummary() {
        if (mediaView == null || config == null) return;
        String description = MediaLibrary.describe(this, config.assetId);
        mediaView.setText(description + "\n素材仅用于 "
                + (selected == null ? "未知型号" : DeviceModel.displayName(currentModelId()))
                + " 配置。");
    }

    private void updateEarbudSummary() {
        if (earbudView == null || config == null) return;
        earbudView.setText(MediaLibrary.describeEarbud(
                this, currentModelId(), config.earbudAssetId) + "\n仅在自定义背景时叠加。");
    }

    private void refreshStatus() {
        if (statusView == null) return;
        selected = DeviceStore.selected(this);
        boolean enabled = getSharedPreferences("popup", MODE_PRIVATE)
                .getBoolean("enabled", false);
        boolean bluetoothGranted = Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        boolean overlayGranted = Settings.canDrawOverlays(this);
        boolean associated = selected != null && CompanionLink.hasAssociation(this);
        String associationStatus = Build.VERSION.SDK_INT < 31
                ? "CDM presence \u4E0D\u652F\u6301\uFF08Android 10 \u53CA\u4EE5\u4E0B\u4F7F\u7528\u540E\u53F0\u84DD\u7259\u76D1\u542C\uFF09"
                : associated ? "\u5DF2\u5173\u8054" : "\u5C1A\u672A\u5173\u8054";
        String device = selected == null ? "未选择" : safe(selected.name);
        statusView.setText("当前设备：" + device
                + "\n连接监听：" + (enabled ? "已开启" : "已关闭")
                + "\n蓝牙权限：" + (bluetoothGranted ? "已授权" : "待授权")
                + "\n悬浮窗权限：" + (overlayGranted ? "已授权" : "待授权")
                + "\n系统设备关联：" + associationStatus);
    }

    private void refreshBattery() {
        if (batteryView == null) return;
        selected = DeviceStore.selected(this);
        if (selected == null) {
            batteryView.setText("\u9009\u62E9\u8033\u673A\u540E\uFF0C\u8FDE\u63A5\u65F6\u4F1A\u81EA\u52A8\u8BFB\u53D6\u7535\u91CF\u3002");
            return;
        }
        BatterySnapshot snapshot = BatteryCache.load(this, selected.address);
        if (snapshot == null || !snapshot.hasAny()) {
            batteryView.setText("\u8FDE\u63A5\u8033\u673A\u540E\u4F1A\u81EA\u52A8\u8BFB\u53D6\u7535\u91CF\uFF1B\u6682\u65E0\u53EF\u7528\u6570\u636E\u3002\u672A\u77E5\u503C\u4E0D\u4F1A\u7528\u793A\u4F8B\u6570\u5B57\u4EE3\u66FF\u3002");
            return;
        }
        StringBuilder value = new StringBuilder();
        if (snapshot.hasComponents()) {
            value.append("左耳 ").append(level(snapshot.left))
                    .append("   右耳 ").append(level(snapshot.right))
                    .append("   充电盒 ").append(level(snapshot.caseLevel));
        } else {
            value.append("耳机 ").append(level(snapshot.overall));
        }
        if (snapshot.updatedAtMillis > 0L) {
            value.append("\n上次读取 ")
                    .append(DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(snapshot.updatedAtMillis)));
        }
        batteryView.setText(value.toString());
    }

    private String level(int value) {
        return value < 0 ? "未知" : value + "%";
    }

    private void add(LinearLayout parent, View child, int bottomDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(bottomDp);
        parent.addView(child, params);
    }

    private void add(LinearLayout parent, View child, int bottomDp, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                weight > 0f ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.bottomMargin = dp(bottomDp);
        parent.addView(child, params);
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private static ColorStateList stateTintList(int checkedColor, int uncheckedColor,
                                                int disabledColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{disabledColor, checkedColor, uncheckedColor});
    }

    private Button button(String label) {
        Button value = new Button(this, null, 0);
        value.setText(label);
        if (label != null && label.length() <= 10) value.setGravity(Gravity.CENTER);
        value.setTextSize(14);
        value.setAllCaps(false);
        value.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Color.rgb(119, 128, 143), COLOR_ACCENT}));
        value.setMinHeight(dp(48));
        value.setPadding(dp(14), dp(6), dp(14), dp(6));
        value.setBackgroundTintList(null);
        value.setForeground(null);
        value.setStateListAnimator(null);
        value.setElevation(0f);
        value.setTranslationZ(0f);
        value.setOutlineProvider(null);

        ColorStateList fill = new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{
                        Color.rgb(233, 237, 243),
                        Color.rgb(235, 241, 253),
                        Color.rgb(247, 249, 253)
                });
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(15));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(15));
        RippleDrawable background = new RippleDrawable(
                ColorStateList.valueOf(Color.argb(32, 47, 103, 219)), shape, mask);
        value.setBackground(background);
        return value;
    }

    private TextView text(String value, int sp, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(sp);
        label.setTextColor(color);
        label.setLineSpacing(dp(3), 1f);
        return label;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static int spinnerIndex(String[] ids, String modelId) {
        String target = DeviceModel.MODEL_UNKNOWN.equals(modelId)
                || modelId == null || modelId.startsWith("unknown_")
                ? DeviceModel.MODEL_UNKNOWN : modelId;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(target)) return i;
        }
        return ids.length - 1;
    }

    private static int nextBackgroundColor(int current) {
        for (int i = 0; i < BACKGROUND_COLORS.length; i++) {
            if (BACKGROUND_COLORS[i] == current) {
                return BACKGROUND_COLORS[(i + 1) % BACKGROUND_COLORS.length];
            }
        }
        return BACKGROUND_COLORS[0];
    }

    private static String colorLabel(int color) {
        for (int i = 0; i < BACKGROUND_COLORS.length; i++) {
            if (BACKGROUND_COLORS[i] == color) return BACKGROUND_LABELS[i];
        }
        return "自定义";
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "蓝牙耳机" : value.trim();
    }

    private interface IntLabel {
        String format(int value);
    }

    private interface IntValueSetter {
        void set(int value);
    }
}

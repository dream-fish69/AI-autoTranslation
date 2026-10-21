package com.dsh.autotranslation;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Config screen reachable from the mod list ("Config" button).
 *
 * <p>Edits the three fields that matter for translation (endpoint / key / model) directly,
 * saves them to {@code config/auto-translation/config.json} and can kick off a manual run. No rendering
 * overrides, so it survives the 26.x render-state refactor.</p>
 */
public final class AutoTranslationConfigScreen extends Screen {

    private static final int PANEL = 320;

    private final Screen parent;
    private StringWidget statusLine;
    private EditBox endpointBox;
    private EditBox keyBox;
    private EditBox modelBox;
    private Button rescanButton;

    private String flash = "";
    private long flashUntil;

    public AutoTranslationConfigScreen(Screen parent) {
        super(Component.literal("AutoTranslation · 自动汉化"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - PANEL / 2;
        int y = Math.max(20, this.height / 2 - 100);

        StringWidget title = new StringWidget(this.title, this.font);
        title.setPosition(this.width / 2 - this.font.width(this.title) / 2, y);
        this.addRenderableWidget(title);

        this.statusLine = new StringWidget(Component.literal(""), this.font);
        this.statusLine.setWidth(PANEL);
        this.statusLine.setMaxWidth(PANEL);
        this.statusLine.setPosition(x, y + 16);
        this.addRenderableWidget(this.statusLine);

        int fieldTop = y + 40;
        this.endpointBox = addField(x, fieldTop,
                "接口地址（OpenAI 兼容，填完整的 chat/completions URL）",
                AutoTranslationMod.apiEndpoint(),
                "https://api.deepseek.com/v1/chat/completions",
                512);
        this.keyBox = addField(x, fieldTop + 36,
                "API Key",
                AutoTranslationMod.apiKeyValue(),
                "sk-... （留空则自动使用内置离线词典）",
                512);
        this.keyBox.addFormatter((text, cursor) ->
                Component.literal(maskKey(text)).getVisualOrderText());
        this.modelBox = addField(x, fieldTop + 72,
                "模型",
                AutoTranslationMod.apiModel(),
                "deepseek-chat",
                128);

        int buttonY = fieldTop + 110;
        int gap = 10;
        int buttonWidth = (PANEL - 2 * gap) / 3;

        Button save = Button.builder(Component.literal("保存并翻译"), button -> save())
                .bounds(x, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(save);

        Button rescan = Button.builder(Component.literal("仅重新扫描"), button -> {
            boolean started = AutoTranslationMod.triggerManual();
            flash(started ? "已开始重新扫描…" : "正在运行中，请稍候…");
        }).bounds(x + buttonWidth + gap, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(rescan);
        this.rescanButton = rescan;

        Button close = Button.builder(Component.literal("完成"), button -> this.onClose())
                .bounds(x + 2 * (buttonWidth + gap), buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(close);

        StringWidget hint = new StringWidget(
                Component.literal("设置会写入 config/auto-translation/config.json"), this.font);
        hint.setPosition(x, buttonY + 26);
        this.addRenderableWidget(hint);

        Button status = Button.builder(Component.literal("各模组状态…"), button -> {
            if (this.minecraft != null && this.minecraft.gui != null) {
                this.minecraft.gui.setScreen(new AutoTranslationStatusScreen(this));
            }
        }).bounds(x + PANEL / 2 - 60, buttonY + 44, 120, 20).build();
        this.addRenderableWidget(status);

        refresh();
    }

    private EditBox addField(int x, int y, String label, String value, String hint, int maxLength) {
        StringWidget labelWidget = new StringWidget(Component.literal(label), this.font);
        labelWidget.setPosition(x, y);
        this.addRenderableWidget(labelWidget);

        EditBox box = new EditBox(this.font, x, y + 11, PANEL, 18, Component.literal(label));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setHint(Component.literal(hint));
        this.addRenderableWidget(box);
        return box;
    }

    private void save() {
        if (!AutoTranslationMod.saveApiSettings(endpointBox.getValue(), keyBox.getValue(), modelBox.getValue())) {
            flash("接口地址必须以 http:// 或 https:// 开头");
            return;
        }
        boolean started = AutoTranslationMod.triggerManual();
        String warning = AutoTranslationMod.keyWarning(endpointBox.getValue(), keyBox.getValue());
        if (!warning.isEmpty()) {
            flash(warning);
        } else {
            flash(started ? "已保存，开始翻译…" : "已保存；当前正在运行，结束后可点「仅重新扫描」");
        }
    }

    private void flash(String message) {
        this.flash = message;
        this.flashUntil = System.currentTimeMillis() + 4_000L;
    }

    @Override
    public void tick() {
        refresh();
    }

    private void refresh() {
        if (this.statusLine == null) {
            return;
        }
        String text = System.currentTimeMillis() < this.flashUntil && !this.flash.isEmpty()
                ? this.flash
                : AutoTranslationMod.statusText();
        this.statusLine.setMessage(Component.literal(text));
        if (this.rescanButton != null) {
            this.rescanButton.active = !AutoTranslationMod.isRunning();
        }
    }

    /** Keeps the rendered length identical so the caret stays aligned. */
    private static String maskKey(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 4) + "*".repeat(value.length() - 8)
                + value.substring(value.length() - 4);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}

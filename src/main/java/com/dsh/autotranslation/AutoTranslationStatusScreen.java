package com.dsh.autotranslation;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-mod overview: how much of each namespace came from the mod itself / CFPA / AI, plus a
 * per-mod "retranslate" button. Vanilla widgets only, no render overrides.
 */
public final class AutoTranslationStatusScreen extends Screen {

    private static final int ROWS = 6;
    private static final int PANEL = 360;

    private final Screen parent;
    private int page;
    private StringWidget statusLine;
    private StringWidget pageLine;

    public AutoTranslationStatusScreen(Screen parent) {
        super(Component.literal("AutoTranslation · 各模组状态"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - PANEL / 2;
        int y = Math.max(18, this.height / 2 - 110);

        StringWidget title = new StringWidget(this.title, this.font);
        title.setPosition(this.width / 2 - this.font.width(this.title) / 2, y);
        this.addRenderableWidget(title);

        this.statusLine = new StringWidget(Component.literal(RunState.text()), this.font);
        this.statusLine.setWidth(PANEL);
        this.statusLine.setMaxWidth(PANEL);
        this.statusLine.setPosition(x, y + 16);
        this.addRenderableWidget(this.statusLine);

        List<Map.Entry<String, String>> rows = new ArrayList<>(RunState.SUMMARIES.entrySet());
        rows.sort(Map.Entry.comparingByKey());
        int pages = Math.max(1, (rows.size() + ROWS - 1) / ROWS);
        this.page = Math.max(0, Math.min(this.page, pages - 1));

        int top = y + 34;
        for (int i = 0; i < ROWS; i++) {
            int index = this.page * ROWS + i;
            int rowY = top + i * 24;
            if (index >= rows.size()) {
                break;
            }
            String namespace = rows.get(index).getKey();
            String summary = rows.get(index).getValue();

            StringWidget label = new StringWidget(Component.literal(namespace + "  ·  " + summary), this.font);
            label.setWidth(PANEL - 66);
            label.setMaxWidth(PANEL - 66);
            label.setPosition(x, rowY + 5);
            this.addRenderableWidget(label);

            Button retranslate = Button.builder(Component.literal("重译"), button -> {
                RunState.SUMMARIES.put(namespace, "排队中…");
                AutoTranslationMod.triggerManualFor(namespace);
            }).bounds(x + PANEL - 58, rowY, 58, 20).build();
            this.addRenderableWidget(retranslate);
        }

        int navY = top + ROWS * 24 + 4;
        Button prev = Button.builder(Component.literal("<"), b -> {
            if (this.page > 0) {
                this.page--;
                this.rebuildWidgets();
            }
        }).bounds(x, navY, 40, 20).build();
        prev.active = this.page > 0;
        this.addRenderableWidget(prev);

        this.pageLine = new StringWidget(
                Component.literal((this.page + 1) + " / " + pages + " 页"), this.font);
        this.pageLine.setPosition(x + 50, navY + 5);
        this.addRenderableWidget(this.pageLine);

        Button next = Button.builder(Component.literal(">"), b -> {
            if (this.page + 1 < pages) {
                this.page++;
                this.rebuildWidgets();
            }
        }).bounds(x + PANEL - 40, navY, 40, 20).build();
        next.active = this.page + 1 < pages;
        this.addRenderableWidget(next);

        Button close = Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(x + PANEL / 2 - 40, navY + 28, 80, 20).build();
        this.addRenderableWidget(close);
    }

    @Override
    public void tick() {
        if (this.statusLine != null) {
            this.statusLine.setMessage(Component.literal(RunState.text()));
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}

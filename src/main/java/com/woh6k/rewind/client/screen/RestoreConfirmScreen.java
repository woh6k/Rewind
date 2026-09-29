package com.woh6k.rewind.client.screen;

import com.woh6k.rewind.snapshot.SnapshotSlot;
import com.woh6k.rewind.snapshot.SnapshotManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RestoreConfirmScreen extends Screen {
    private final Screen parent;
    private final SnapshotSlot slot;
    private final String token;
    @Override public boolean isPauseScreen() { return false; }

    public RestoreConfirmScreen(Screen parent, SnapshotSlot slot, String token) {
        super(Component.translatable("screen.rewind.restore_confirm.title"));
        this.parent = parent;
        this.slot = slot;
        this.token = token;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.rewind.restore_confirm.confirm"),
                button -> SnapshotManager.get().confirmRestore(token)).bounds(width / 2 - 105, height / 2 + 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                button -> minecraft.setScreen(parent)).bounds(width / 2 + 5, height / 2 + 24, 100, 20).build());
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 44, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("screen.rewind.restore_confirm.warning", slot.id()), width / 2, height / 2 - 18, 0xFFDD7777);
        graphics.drawCenteredString(font, Component.translatable("screen.rewind.restore_confirm.server"), width / 2, height / 2, 0xFFDD7777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}

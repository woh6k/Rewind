package com.woh6k.rewind.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Pauses an integrated server while a background snapshot copy reads its files. */
public final class SnapshotProgressScreen extends Screen {
    private Component status;

    public SnapshotProgressScreen(Component status) {
        super(Component.translatable("screen.rewind.saving.title"));
        this.status = status;
    }

    public void setStatus(Component status) {
        this.status = status;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        RewindTheme.background(graphics, width, height, false);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 18, RewindTheme.GOLD);
        graphics.drawCenteredString(font, status, width / 2, height / 2 + 4, RewindTheme.MUTED);
        graphics.drawCenteredString(font, Component.translatable("screen.rewind.please_wait"), width / 2, height / 2 + 28, RewindTheme.MUTED);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}

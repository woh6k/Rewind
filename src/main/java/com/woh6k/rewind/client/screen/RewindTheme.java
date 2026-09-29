package com.woh6k.rewind.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Procedural ink, cinnabar and brass decoration; no external texture dependencies. */
final class RewindTheme {
    static final int GOLD = 0xFFD2B67C, MUTED = 0xFF9F998B, INK = 0xFF111717;

    static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x+w, y+1, color); g.fill(x, y+h-1, x+w, y+h, color);
        g.fill(x, y, x+1, y+h, color); g.fill(x+w-1, y, x+w, y+h, color);
    }

    static void background(GuiGraphics g, int w, int h, boolean ritual) {
        g.fillGradient(0, 0, w, h, ritual ? 0xFF291514 : 0xFF101A1B, 0xFF090D0F);
        double time = net.minecraft.Util.getMillis() / 18000.0;
        int radius = Math.max(20, Math.min(w, h) / 3);
        for (int ring = 0; ring < 3; ring++) {
            double r = radius + ring * 13;
            for (int n = 0; n < 144; n++) {
                double a = n * Math.PI / 72 - time * (ring % 2 == 0 ? 1 : -0.6);
                int x = w/2 + (int)(Math.cos(a)*r), y = h/2 + (int)(Math.sin(a)*r);
                int size = n % 12 == 0 ? 3 : 1;
                g.fill(x, y, x+size, y+size, ritual ? 0xFF593A2A : 0xFF253330);
            }
        }
        for (int i = 0; i < 28; i++) {
            int x = Math.floorMod(i*97, Math.max(1,w));
            int y = Math.floorMod(i*53-(int)(time*9), Math.max(1,h));
            g.fill(x,y,x+1,y+1,0xFF665139);
        }
        frame(g, 6, 6, w-12, h-12, 0xFF514632);
    }

    static Button button(Component label, Button.OnPress action, int x, int y, int w) {
        return new Button(x,y,w,20,label,action, supplier -> supplier.get()) {
            @Override public void renderWidget(GuiGraphics g, int mx, int my, float dt) {
                boolean hover = isHoveredOrFocused();
                g.fill(getX(),getY(),getX()+getWidth(),getY()+getHeight(), active && hover ? 0xFF63362B : INK);
                frame(g,getX(),getY(),getWidth(),getHeight(),active ? (hover ? GOLD : 0xFF67583D) : 0xFF303633);
                renderScrollingString(g, Minecraft.getInstance().font, 4, active ? GOLD : 0xFF686D66);
            }
        };
    }

    /** A small stepper control: one immediate step, then a measured repeat while held. */
    static RepeatButton repeatButton(Component label, Button.OnPress action, int x, int y, int w) {
        return new RepeatButton(label, action, x, y, w);
    }

    static final class RepeatButton extends Button {
        private static final long REPEAT_DELAY_MS = 350L;
        private static final long REPEAT_PERIOD_MS = 150L;
        private boolean held;
        private long heldSince;
        private long lastRepeat;

        private RepeatButton(Component label, Button.OnPress action, int x, int y, int w) {
            super(x, y, w, 20, label, action, supplier -> supplier.get());
        }

        @Override public void onClick(double mouseX, double mouseY) {
            super.onClick(mouseX, mouseY);
            held = true;
            heldSince = lastRepeat = net.minecraft.Util.getMillis();
        }

        @Override public void onRelease(double mouseX, double mouseY) {
            held = false;
        }

        void tickRepeat() {
            Minecraft mc = Minecraft.getInstance();
            if (!active || !visible || !mc.isWindowActive() || !mc.mouseHandler.isLeftPressed()) held = false;
            if (!held) return;
            long now = net.minecraft.Util.getMillis();
            if (now - heldSince >= REPEAT_DELAY_MS && now - lastRepeat >= REPEAT_PERIOD_MS) {
                onPress();
                lastRepeat = now;
            }
        }

        boolean isRepeating() { return held; }
        void stopRepeating() { held = false; }

        @Override public void renderWidget(GuiGraphics g, int mx, int my, float dt) {
            boolean hover = isHoveredOrFocused();
            g.fill(getX(),getY(),getX()+getWidth(),getY()+getHeight(), active && hover ? 0xFF63362B : INK);
            frame(g,getX(),getY(),getWidth(),getHeight(),active ? (hover ? GOLD : 0xFF67583D) : 0xFF303633);
            renderScrollingString(g, Minecraft.getInstance().font, 4, active ? GOLD : 0xFF686D66);
        }
    }
}

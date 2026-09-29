package com.woh6k.rewind.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Stage text remains truthful; the moving line is an indeterminate activity indicator. */
public final class RewindTransitionScreen extends Screen {
    private Component stage;
    public RewindTransitionScreen(Component stage) {
        super(Component.translatable("screen.rewind.transition.title"));
        this.stage=stage;
    }
    public void setStage(Component stage) { this.stage=stage; }
    @Override public boolean isPauseScreen() { return true; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void render(GuiGraphics g,int mx,int my,float dt) {
        RewindTheme.background(g,width,height,true);
        int cy=height/2, cx=width/2;
        // A cinnabar seal at the heart of the slowly reversing formation.
        g.fill(cx-27,cy-44,cx+27,cy+2,0xFF542A24);
        RewindTheme.frame(g,cx-25,cy-42,50,42,0xFFAA7450);
        g.drawCenteredString(font,title,cx,cy-23,0xFFF0DDB4);
        g.drawCenteredString(font,stage,cx,cy+18,RewindTheme.MUTED);
        int half=Math.min(100,width/3), y=cy+38;
        g.fill(cx-half,y,cx+half,y+2,0xFF433329);
        int head=cx+half-(int)((net.minecraft.Util.getMillis()%2400L)/2400.0*(half*2));
        g.fill(Math.max(cx-half,head-24),y,head,y+2,0xFFC18A54);
        g.drawCenteredString(font,Component.translatable("screen.rewind.please_wait"),cx,cy+52,RewindTheme.MUTED);
    }
}

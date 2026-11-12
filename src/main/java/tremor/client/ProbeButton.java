package tremor.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * A button of the probe's menu ({@link ProbeScreen}): a vanilla button with an item for its picture and its name and
 * what it does in a tooltip (the user: the text buttons ran into each other; there will be more of them). The one
 * chosen of a group (the right click's mode, the kind called up) is framed. Its name is its message, for narration,
 * but is not drawn on it unless it is a wide one.
 */
final class ProbeButton extends Button {
    private final ItemStack icon;
    private final boolean labelled;
    private final boolean selected;

    ProbeButton(int x, int y, int width, int height, ItemStack icon, Component name, Component tip, boolean selected,
                boolean labelled, OnPress press) {
        super(x, y, width, height, name, press, DEFAULT_NARRATION);
        this.icon = icon;
        this.labelled = labelled;
        this.selected = selected;
        setTooltip(Tooltip.create(tip == null ? name : Component.empty().append(name).append("\n").append(tip)));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        int itemX = labelled ? getX() + 3 : getX() + (width - 16) / 2;
        graphics.renderItem(icon, itemX, getY() + (height - 16) / 2);
        if (selected) {
            graphics.renderOutline(getX(), getY(), width, height, 0xFFFFFFFF);
        }
    }

    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        if (labelled) {
            // The name beside the picture, in what is left of the button.
            renderScrollingString(graphics, font, Component.empty().append(getMessage()), getX() + 21, getY(),
                    getX() + width - 3, getY() + height, color);
        }
    }
}

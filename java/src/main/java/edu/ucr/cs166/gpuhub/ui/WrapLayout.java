package edu.ucr.cs166.gpuhub.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * A FlowLayout that asks for enough height when its components wrap onto several lines.
 * (A plain FlowLayout always asks for one line, so the rest would be cut off.)
 */
final class WrapLayout extends FlowLayout {

    WrapLayout(int hgap, int vgap) {
        super(FlowLayout.LEFT, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        // Before the first layout the width is unknown (0): assume one line.
        int available = target.getWidth() == 0 ? Integer.MAX_VALUE : target.getWidth();
        int widest = 0;
        int lineWidth = getHgap();
        int lineHeight = 0;
        int height = getVgap();
        for (Component component : target.getComponents()) {
            Dimension size = component.getPreferredSize();
            if (lineHeight > 0 && lineWidth + size.width + getHgap() > available) { // start a new line
                height += lineHeight + getVgap();
                lineWidth = getHgap();
                lineHeight = 0;
            }
            lineWidth += size.width + getHgap();
            lineHeight = Math.max(lineHeight, size.height);
            widest = Math.max(widest, lineWidth);
        }
        return new Dimension(widest, height + lineHeight + getVgap());
    }
}

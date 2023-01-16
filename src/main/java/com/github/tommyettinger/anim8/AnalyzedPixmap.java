package com.github.tommyettinger.anim8;

import java.util.Arrays;

class AnalyzedPixmap {
    private Integer index;
    private byte[] colorTab;
    private byte[] indexedPixels;

    public AnalyzedPixmap(Integer index, byte[] colorTab, byte[] indexedPixels) {
        this.index = index;
        this.colorTab = colorTab;
        this.indexedPixels = indexedPixels;
    }

    public Integer getIndex() {
        return index;
    }

    public byte[] getColorTab() {
        return colorTab;
    }

    public byte[] getIndexedPixels() {
        return indexedPixels;
    }

    @Override
    public String toString() {
        return "AnalyzedPixmap{" +
                "index=" + index +
                ", colorTab=" + Arrays.toString(colorTab) +
                ", indexedPixels=" + Arrays.toString(indexedPixels) +
                '}';
    }
}

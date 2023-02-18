/*
 * Copyright (c) 2023  Tommy Ettinger
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package com.github.tommyettinger.anim8;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntIntMap;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A GWT-incompatible, optimized replacement for {@link PaletteReducer}.
 * This reads pixels byte-by-byte from a Pixmap's {@link Pixmap#getPixels()} buffer, rather than relying on
 * {@link Pixmap#getPixel(int, int)} to form RGBA8888 ints for every pixel we read.
 */
public class FastPalette extends PaletteReducer {
    private final transient byte[] workspace = new byte[0x8000];
    /**
     * Constructs a default FastPalette that uses the "Aurora" 255-color-plus-transparent palette.
     * Note that this uses a more-detailed and higher-quality metric than you would get by just specifying
     * {@code new FastPalette(FastPalette.AURORA)}; this metric would be too slow to calculate at
     * runtime, but as pre-calculated data it works very well.
     */
    public FastPalette() {
        super();
    }

    /**
     * Constructs a FastPalette that uses the given array of RGBA8888 ints as a palette (see {@link #exact(int[])}
     * for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     */
    public FastPalette(int[] rgbaPalette) {
        super(rgbaPalette);
    }

    /**
     * Constructs a FastPalette that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], int)} for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     * @param limit       how many int items to use from rgbaPalette (this always starts at index 0)
     */
    public FastPalette(int[] rgbaPalette, int limit) {
        super(rgbaPalette, limit);
    }

    /**
     * Constructs a FastPalette that uses the given array of Color objects as a palette (see {@link #exact(Color[])}
     * for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public FastPalette(Color[] colorPalette) {
        super(colorPalette);
    }

    /**
     * Constructs a FastPalette that uses the given array of Color objects as a palette (see
     * {@link #exact(Color[], int)} for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     * @param limit
     */
    public FastPalette(Color[] colorPalette, int limit) {
        super(colorPalette, limit);
    }

    /**
     * Constructs a FastPalette that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap)} for more info).
     *
     * @param pixmap a Pixmap to analyze in detail to produce a palette
     */
    public FastPalette(Pixmap pixmap) {
        super(pixmap);
    }

    /**
     * Constructs a FastPalette that analyzes the given Pixmaps for color count and frequency to generate a palette
     * (see {@link #analyze(Array)} for more info).
     *
     * @param pixmaps an Array of Pixmap to analyze in detail to produce a palette
     */
    public FastPalette(Array<Pixmap> pixmaps) {
        super(pixmaps);
    }

    /**
     * Constructs a FastPalette that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], byte[])} for more info) and an encoded byte array to use to look up pre-loaded color data.
     * You can use {@link FastPalette#writePreloadFile(FileHandle)} to write the preload data for a given FastPalette, and
     * {@link #loadPreloadFile(FileHandle)} to get a byte array of preload data from a previously-written file.
     *
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array containing preload data
     */
    public FastPalette(int[] palette, byte[] preload) {
        super(palette, preload);
    }

    /**
     * Constructs a FastPalette that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap, double)} for more info).
     *
     * @param pixmap    a Pixmap to analyze in detail to produce a palette
     * @param threshold the minimum difference between colors required to put them in the palette (default 100)
     */
    public FastPalette(Pixmap pixmap, double threshold) {
        super(pixmap, threshold);
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or fewer colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 500, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    @Override
    public void analyze(Pixmap pixmap, double threshold, int limit) {
        ByteBuffer pixels = pixmap.getPixels();
        boolean hasAlpha = pixmap.getFormat().equals(Pixmap.Format.RGBA8888);
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        int r, g, b;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                r = pixels.get() & 0xF8;
                g = pixels.get() & 0xF8;
                b = pixels.get() & 0xF8;
                if (!hasAlpha || (pixels.get() & 0x80) != 0) {
                    color = r << 24 | g << 16 | b << 8;
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.125/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (differenceAnalyzing(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.125/colorCount);
        }
        if(reverseMap == null)
            reverseMap = new IntIntMap(colorCount);
        else
            reverseMap.clear(colorCount);

        for (int i = 0; i < colorCount; i++) {
            reverseMap.put(paletteArray[i], i);
        }
        int c2;
        int rr, gg, bb;
        double dist;
        for (r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        pixels.rewind();
    }


    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or fewer colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); if there are more than {@code limit} colors or any colors have 50% or less
     * alpha, it will reserve a palette entry for transparent (even if the image has no transparency). Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 500, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     * <br>
     * This does a faster and less accurate analysis, and is more suitable to do on each frame of a large animation when
     * time is better spent making more images than fewer images at higher quality. It should be about 5 times faster
     * than {@link #analyze(Pixmap, double, int)} with the same parameters.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    @Override
    public void analyzeFast(Pixmap pixmap, double threshold, int limit) {
        ByteBuffer pixels = pixmap.getPixels();
        boolean hasAlpha = pixmap.getFormat().equals(Pixmap.Format.RGBA8888);
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        Arrays.fill(workspace, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        int r, g, b;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                r = pixels.get() & 0xF8;
                g = pixels.get() & 0xF8;
                b = pixels.get() & 0xF8;
                if (!hasAlpha || (pixels.get() & 0x80) != 0) {
                    color = r << 24 | g << 16 | b << 8;
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                color = (color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F);
                workspace[color] = paletteMapping[color] = (byte) i;
                ++i;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.125/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (differenceAnalyzing(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                color = (color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F);
                workspace[color] = paletteMapping[color] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.125/colorCount);
        }
        if(reverseMap == null)
            reverseMap = new IntIntMap(colorCount);
        else
            reverseMap.clear(colorCount);

        for (int i = 0; i < colorCount; i++) {
            reverseMap.put(paletteArray[i], i);
        }
        if(colorCount <= 1)
            return;
        int c2;
        byte bt;
        int numUnassigned = 1, iterations = 0;
        while (numUnassigned != 0) {
            numUnassigned = 0;
            for (r = 0; r < 32; r++) {
                for (g = 0; g < 32; g++) {
                    for (b = 0; b < 32; b++) {
                        c2 = r << 10 | g << 5 | b;
                        if (workspace[c2] == 0) {
                            if(iterations++ != 2){
                                if (b < 31 && (bt = paletteMapping[c2 + 1]) != 0)
                                    workspace[c2] = bt;
                                else if (g < 31 && (bt = paletteMapping[c2 + 32]) != 0)
                                    workspace[c2] = bt;
                                else if (r < 31 && (bt = paletteMapping[c2 + 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (b > 0 && (bt = paletteMapping[c2 - 1]) != 0)
                                    workspace[c2] = bt;
                                else if (g > 0 && (bt = paletteMapping[c2 - 32]) != 0)
                                    workspace[c2] = bt;
                                else if (r > 0 && (bt = paletteMapping[c2 - 1024]) != 0)
                                    workspace[c2] = bt;
                                else numUnassigned++;
                            }
                            else {
                                iterations = 0;
                                if (b < 31 && (bt = paletteMapping[c2 + 1]) != 0)
                                    workspace[c2] = bt;
                                else if (g < 31 && (bt = paletteMapping[c2 + 32]) != 0)
                                    workspace[c2] = bt;
                                else if (r < 31 && (bt = paletteMapping[c2 + 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (b > 0 && (bt = paletteMapping[c2 - 1]) != 0)
                                    workspace[c2] = bt;
                                else if (g > 0 && (bt = paletteMapping[c2 - 32]) != 0)
                                    workspace[c2] = bt;
                                else if (r > 0 && (bt = paletteMapping[c2 - 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (b < 31 && g < 31 && (bt = paletteMapping[c2 + 1 + 32]) != 0)
                                    workspace[c2] = bt;
                                else if (b < 31 && r < 31 && (bt = paletteMapping[c2 + 1 + 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (g < 31 && r < 31 && (bt = paletteMapping[c2 + 32 + 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (b > 0 && g > 0 && (bt = paletteMapping[c2 - 1 - 32]) != 0)
                                    workspace[c2] = bt;
                                else if (b > 0 && r > 0 && (bt = paletteMapping[c2 - 1 - 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (g > 0 && r > 0 && (bt = paletteMapping[c2 - 32 - 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (b < 31 && g > 0 && (bt = paletteMapping[c2 + 1 - 32]) != 0)
                                    workspace[c2] = bt;
                                else if (b < 31 && r > 0 && (bt = paletteMapping[c2 + 1 - 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (g < 31 && r > 0 && (bt = paletteMapping[c2 + 32 - 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (b > 0 && g < 31 && (bt = paletteMapping[c2 - 1 + 32]) != 0)
                                    workspace[c2] = bt;
                                else if (b > 0 && r < 31 && (bt = paletteMapping[c2 - 1 + 1024]) != 0)
                                    workspace[c2] = bt;
                                else if (g > 0 && r < 31 && (bt = paletteMapping[c2 - 32 + 1024]) != 0)
                                    workspace[c2] = bt;
                                else numUnassigned++;

                            }
                        }
                    }
                }
            }
            System.arraycopy(workspace, 0, paletteMapping, 0, 0x8000);
        }
        pixels.rewind();
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 500, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    @Override
    public void analyze(Pixmap[] pixmaps, int pixmapCount, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        IntIntMap counts = new IntIntMap(limit);
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            ByteBuffer pixels = pixmap.getPixels();
            boolean hasAlpha = pixmap.getFormat().equals(Pixmap.Format.RGBA8888);
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            int r, g, b;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    r = pixels.get() & 0xF8;
                    g = pixels.get() & 0xF8;
                    b = pixels.get() & 0xF8;
                    if (!hasAlpha || (pixels.get() & 0x80) != 0) {
                        color = r << 24 | g << 16 | b << 8;
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        counts.getAndIncrement(color, 0, 1);
                    }
                }
            }
            pixels.rewind();
        }
        final int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.125/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            for (; i < limit && c < cs;) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    double diff = differenceAnalyzing(color, paletteArray[j]);
                    if (diff < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.125/colorCount);
        }
        if(reverseMap == null)
            reverseMap = new IntIntMap(colorCount);
        else
            reverseMap.clear(colorCount);

        for (int i = 0; i < colorCount; i++) {
            reverseMap.put(paletteArray[i], i);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, without dithering. This
     * produces blocky solid sections of color in most images where the palette isn't exact, instead of
     * checkerboard-like dithering patterns. If you want to reduce the colors in a Pixmap based on what it currently
     * contains, call {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    @Override
    public Pixmap reduceSolid (Pixmap pixmap) {
        ByteBuffer pixels = pixmap.getPixels();
        boolean hasAlpha = pixmap.getFormat().equals(Pixmap.Format.RGBA8888);
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                int rr = pixels.get() & 0xFF;
                int gg = pixels.get() & 0xFF;
                int bb = pixels.get() & 0xFF;
                if (hasAlpha){
                    if((pixels.get() & 0x80) == 0) {
                        if(hasTransparent)
                            pixels.position(pixels.position() - 4).putInt(0);
                        else
                            pixels.position(pixels.position()-4).putInt(
                                    paletteArray[paletteMapping[
                                            ((rr << 7) & 0x7C00)
                                                    | ((gg << 2) & 0x3E0)
                                                    | ((bb >>> 3))] & 0xFF]);
                    } else {
                        pixels.position(pixels.position()-4).putInt(
                                paletteArray[paletteMapping[
                                        ((rr << 7) & 0x7C00)
                                                | ((gg << 2) & 0x3E0)
                                                | ((bb >>> 3))] & 0xFF]);
                    }
                } else {
                    int rgba = paletteArray[paletteMapping[
                            ((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))] & 0xFF];
                    pixels.position(pixels.position()-3)
                            .put((byte)(rgba >>> 24)).put((byte)(rgba>>>16)).put((byte)(rgba>>>8));
                }
            }

        }
        pixmap.setBlending(blending);
        pixels.rewind();
        return pixmap;
    }

}

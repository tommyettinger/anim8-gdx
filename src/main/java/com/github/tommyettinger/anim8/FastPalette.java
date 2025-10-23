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

import static com.github.tommyettinger.anim8.ConstantData.ENCODED_SNUGGLY;

/**
 * This is just like {@link PaletteReducer}, except that it uses a higher-quality, slower color difference calculation
 * when creating a palette. This calculates the difference between colors using Euclidean distance in RGB color space,
 * rather than what PaletteReducer uses, which is Euclidean distance in Oklab.
 */
public class FastPalette extends PaletteReducer {
    /**
     * Constructs a default FastPalette that uses the "Snuggly" 255-color-plus-transparent palette.
     * Note that this uses a more-detailed and higher-quality metric than you would get by just specifying
     * {@code new FastPalette(PaletteReducer.SNUGGLY)}; this metric would be too slow to calculate at
     * runtime, but as pre-calculated data it works very well.
     */
    public FastPalette() {
        exact(SNUGGLY, ENCODED_SNUGGLY);
    }

    /**
     * Constructs a FastPalette that uses the given array of RGBA8888 ints as a palette (see {@link #exact(int[])}
     * for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     */
    public FastPalette(int[] rgbaPalette) {
        if(rgbaPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(rgbaPalette);
    }
    /**
     * Constructs a FastPalette that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], int)} for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     * @param limit how many int items to use from rgbaPalette (this always starts at index 0)
     */
    public FastPalette(int[] rgbaPalette, int limit) {
        if(rgbaPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(rgbaPalette, limit);
    }

    /**
     * Constructs a FastPalette that uses the given array of Color objects as a palette (see {@link #exact(Color[])}
     * for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public FastPalette(Color[] colorPalette) {
        if(colorPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(colorPalette);
    }

    /**
     * Constructs a FastPalette that uses the given array of Color objects as a palette (see
     * {@link #exact(Color[], int)} for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public FastPalette(Color[] colorPalette, int limit) {
        if(colorPalette == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        exact(colorPalette, limit);
    }

    /**
     * Constructs a FastPalette that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap)} for more info).
     *
     * @param pixmap a Pixmap to analyze in detail to produce a palette
     */
    public FastPalette(Pixmap pixmap) {
        if(pixmap == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        analyze(pixmap);
    }

    /**
     * Constructs a FastPalette that analyzes the given Pixmaps for color count and frequency to generate a palette
     * (see {@link #analyze(Array)} for more info).
     *
     * @param pixmaps an Array of Pixmap to analyze in detail to produce a palette
     */
    public FastPalette(Array<Pixmap> pixmaps) {
        if(pixmaps == null)
        {
            exact(SNUGGLY, ENCODED_SNUGGLY);
            return;
        }
        analyze(pixmaps);
    }
    /**
     * Constructs a FastPalette that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], byte[])} for more info) and an encoded byte array to use to look up pre-loaded color data.
     * You can use {@link #writePreloadFile(FileHandle)} to write the preload data for a given FastPalette, and
     * {@link #loadPreloadFile(FileHandle)} to get a byte array of preload data from a previously-written file.
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array containing preload data
     */
    public FastPalette(int[] palette, byte[] preload)
    {
        exact(palette, preload);
    }
    /**
     * Constructs a FastPalette that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap, double)} for more info).
     *
     * @param pixmap    a Pixmap to analyze in detail to produce a palette
     * @param threshold the minimum difference between colors required to put them in the palette (default 300)
     */
    public FastPalette(Pixmap pixmap, double threshold) {
        analyze(pixmap, threshold);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceMatch(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceMatch(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceAnalyzing(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceAnalyzing(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceHW(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceHW(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceHW(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceMatch(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceMatch(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25. If
     * you want to change this, just change {@link #differenceAnalyzing(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceAnalyzing(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25. If
     * you want to change this, just change {@link #differenceHW(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceHW(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceHW(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when matching to an existing palette, as with {@link #exact(int[])}.
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * differenceMatch(int, int, int, int, int, int),
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * FastPalette to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int r1, int g1, int b1, int r2, int g2, int b2) {
        return difference(r1, g1, b1, r2, g2, b2);
    }

//    public double totalDifference = 0.0;
    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when analyzing an image, as with {@link #analyze(Pixmap)}.
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * differenceAnalyzing(int, int, int, int, int, int), and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * FastPalette to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int r1, int g1, int b1, int r2, int g2, int b2) {
        return difference(r1, g1, b1, r2, g2, b2);
//        double total = difference(r1, g1, b1, r2, g2, b2);
//        totalDifference += total;
//        return total;
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when analyzing an image with {@link #analyzeHueWise(Pixmap, double, int)} .
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * differenceHW(int, int, int, int, int, int), but classes can (potentially anonymously) subclass
     * FastPalette to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance, between colors 1 and 2
     */
    public double differenceHW(int r1, int g1, int b1, int r2, int g2, int b2) {
        return difference(r1, g1, b1, r2, g2, b2);
    }

    public double difference(int color1, int color2) {
        if(((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return difference(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    public double difference(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return difference(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    public double difference(int r1, int g1, int b1, int r2, int g2, int b2) {
        int rf = (r1 - r2);
        int gf = (g1 - g2);
        int bf = (b1 - b2);
        return (rf * rf + gf * gf + bf * bf);
    }
}

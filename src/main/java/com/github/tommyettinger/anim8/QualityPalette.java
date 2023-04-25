/*
 * Copyright (c) 2022  Tommy Ettinger
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
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import static com.github.tommyettinger.anim8.ConstantData.ENCODED_AURORA;

/**
 * This is just like {@link PaletteReducer}, except that it uses a higher-quality, slower color difference calculation
 * when creating a palette. This calculates the difference between colors using Euclidean distance in the Oklab color
 * space, rather than what PaletteReducer uses, which is Euclidean distance in RGB.
 */
public class QualityPalette extends PaletteReducer {
    /**
     * Constructs a default QualityPalette that uses the "Aurora" 255-color-plus-transparent palette.
     * Note that this uses a more-detailed and higher-quality metric than you would get by just specifying
     * {@code new QualityPalette(PaletteReducer.AURORA)}; this metric would be too slow to calculate at
     * runtime, but as pre-calculated data it works very well.
     */
    public QualityPalette() {
        exact(AURORA, ENCODED_AURORA);
    }

    /**
     * Constructs a QualityPalette that uses the given array of RGBA8888 ints as a palette (see {@link #exact(int[])}
     * for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     */
    public QualityPalette(int[] rgbaPalette) {
        if(rgbaPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(rgbaPalette);
    }
    /**
     * Constructs a QualityPalette that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], int)} for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     * @param limit how many int items to use from rgbaPalette (this always starts at index 0)
     */
    public QualityPalette(int[] rgbaPalette, int limit) {
        if(rgbaPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(rgbaPalette, limit);
    }

    /**
     * Constructs a QualityPalette that uses the given array of Color objects as a palette (see {@link #exact(Color[])}
     * for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public QualityPalette(Color[] colorPalette) {
        if(colorPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(colorPalette);
    }

    /**
     * Constructs a QualityPalette that uses the given array of Color objects as a palette (see
     * {@link #exact(Color[], int)} for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public QualityPalette(Color[] colorPalette, int limit) {
        if(colorPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(colorPalette, limit);
    }

    /**
     * Constructs a QualityPalette that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap)} for more info).
     *
     * @param pixmap a Pixmap to analyze in detail to produce a palette
     */
    public QualityPalette(Pixmap pixmap) {
        if(pixmap == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        analyze(pixmap);
    }

    /**
     * Constructs a QualityPalette that analyzes the given Pixmaps for color count and frequency to generate a palette
     * (see {@link #analyze(Array)} for more info).
     *
     * @param pixmaps an Array of Pixmap to analyze in detail to produce a palette
     */
    public QualityPalette(Array<Pixmap> pixmaps) {
        if(pixmaps == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        analyze(pixmaps);
    }
    /**
     * Constructs a QualityPalette that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], byte[])} for more info) and an encoded byte array to use to look up pre-loaded color data.
     * You can use {@link #writePreloadFile(FileHandle)} to write the preload data for a given QualityPalette, and
     * {@link #loadPreloadFile(FileHandle)} to get a byte array of preload data from a previously-written file.
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array containing preload data
     */
    public QualityPalette(int[] palette, byte[] preload)
    {
        exact(palette, preload);
    }
    /**
     * Constructs a QualityPalette that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap, double)} for more info).
     *
     * @param pixmap    a Pixmap to analyze in detail to produce a palette
     * @param threshold the minimum difference between colors required to put them in the palette (default 100)
     */
    public QualityPalette(Pixmap pixmap, double threshold) {
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
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * QualityPalette to change one, some, or all of these methods. The other difference methods call the 6-argument
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

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when analyzing an image, as with {@link #analyze(Pixmap)}.
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * QualityPalette to change one, some, or all of these methods. The other difference methods call the 6-argument
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
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * QualityPalette to change one, some, or all of these methods. The other difference methods call the 6-argument
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

    /**
     * Changes the curve of a requested L value so that it matches the internally-used curve. This takes a curve with a
     * very-dark area similar to sRGB (a very small one), and makes it significantly larger. This is typically used on
     * "to Oklab" conversions.
     * @param L lightness, from 0 to 1 inclusive
     * @return an adjusted L value that can be used internally
     */
    public static double forwardLight(final double L) {
        final double shape = 0.64516133, turning = 0.95;
        final double d = turning - L;
        double r;
        if(d < 0)
            r = ((1.0 - turning) * (L - 1.0)) / (1.0 - (L + shape * d)) + 1.0;
        else
            r = (turning * L) / (1e-50 + (L + shape * d));
        return r * r;
    }

    /**
     * Changes the curve of the internally-used lightness when it is output to another format. This makes the very-dark
     * area smaller, matching (kind-of) the curve that the standard sRGB lightness uses. This is typically used on "from
     * Oklab" conversions.
     * @param L lightness, from 0 to 1 inclusive
     * @return an adjusted L value that can be fed into a conversion to RGBA or something similar
     */
    public static double reverseLight(double L) {
        L = Math.sqrt(L);
        final double shape = 1.55, turning = 0.95;
        final double d = turning - L;
        double r;
        if(d < 0)
            r = ((1.0 - turning) * (L - 1.0)) / (1.0 - (L + shape * d)) + 1.0;
        else
            r = (turning * L) / (1e-50 + (L + shape * d));
        return r;
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
        double r = r1 * 0.00392156862745098; r *= r;
        double g = g1 * 0.00392156862745098; g *= g;
        double b = b1 * 0.00392156862745098; b *= b;

        double l = Math.cbrt(0.4121656120 * r + 0.5362752080 * g + 0.0514575653 * b);
        double m = Math.cbrt(0.2118591070 * r + 0.6807189584 * g + 0.1074065790 * b);
        double s = Math.cbrt(0.0883097947 * r + 0.2818474174 * g + 0.6302613616 * b);

        double L1 = forwardLight(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s);
        double A1 = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double B1 = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
////LR alternate lightness estimate
//            final double k1 = 0.206, k2 = 0.03, k3 = 1.17087;
//            double t = (k3 * L1 - k1);
//            L1 = (t + Math.sqrt(t * t + 0.1405044 * L1)) * 0.5;

        r = r2 * 0.00392156862745098; r *= r;
        g = g2 * 0.00392156862745098; g *= g;
        b = b2 * 0.00392156862745098; b *= b;

        l = Math.cbrt(0.4121656120 * r + 0.5362752080 * g + 0.0514575653 * b);
        m = Math.cbrt(0.2118591070 * r + 0.6807189584 * g + 0.1074065790 * b);
        s = Math.cbrt(0.0883097947 * r + 0.2818474174 * g + 0.6302613616 * b);

        double L2 = forwardLight(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s);
        double A2 = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double B2 = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
//            t = (k3 * L2 - k1);
//            L2 = (t + Math.sqrt(t * t + 0.1405044 * L2)) * 0.5;

        double L = (L1 - L2);// * 1.25;
        double A = (A1 - A2);
        double B = (B1 - B2);

        return (L * L + A * A + B * B) * 150000;
    }
}

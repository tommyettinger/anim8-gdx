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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntIntMap;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.OrderedMap;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static com.github.tommyettinger.anim8.PaletteReducer.*;

/** 
 * PNG-8 encoder with compression; can write animated and non-animated PNG images in indexed-mode.
 * An instance can be reused to encode multiple PNGs with minimal allocation.
 * <br>
 * You can configure the target palette and how this can dither colors via the {@link #palette} field, which is a
 * {@link PaletteReducer} object that defaults to null and can be reused. If you assign a PaletteReducer to palette, the
 * methods {@link PaletteReducer#exact(Color[])} or {@link PaletteReducer#analyze(Pixmap)} can be used to make the
 * target palette match a specific set of colors or the colors in an existing image. If palette is null, this will
 * compute a palette for each PNG that closely fits its set of given animation frames. If the palette isn't an exact
 * match for the colors used in an animation (indexed mode has at most 256 colors), this will dither pixels so that from
 * a distance, they look closer to the original colors. You can us {@link PaletteReducer#setDitherStrength(float)} to
 * reduce (or increase) dither strength, typically between 0 and 2;
 * the dithering algorithm used here by default is based on Burkes error-diffusion dithering but with patterns
 * broken up using various forms of noise ({@link DitherAlgorithm#OVERBOARD}), but you can select alternatives with
 * {@link #setDitherAlgorithm(DitherAlgorithm)}, such as the slow but high-quality Knoll Ordered Dither using
 * {@link DitherAlgorithm#PATTERN}, or no dither at all with {@link DitherAlgorithm#NONE}.
 * <br>
 * This defaults to using a relatively high amount of compression, which makes writing many files or large files slower.
 * You can use {@link #setCompression(int)} to lower compression from the default of 6, down to 2 or even lower. Using
 * compression 2 is probably as low as you need to go; speed of writing the file is only negligibly different below 2,
 * but the file size is higher at 1 or especially 0.
 * <br>
 * Note that for many cases where you write a non-animated PNG, you will want to use
 * {@link #writePrecisely(FileHandle, Pixmap, boolean)} instead of {@link #write(FileHandle, Pixmap, boolean, boolean)},
 * since writePrecisely will attempt to reproduce the exact colors if there are 256 colors or less in the Pixmap, and
 * will automatically change to calling write() if there are more than 256 colors.
 * <br>
 * From LibGDX in the class PixmapIO, with modifications to support indexed-mode files, dithering, animation, etc.
 * <pre>
 * Copyright (c) 2007 Matthias Mann - www.matthiasmann.de
 * Copyright (c) 2014 Nathan Sweet
 * Copyright (c) 2018 Tommy Ettinger
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * </pre>
 * @author Matthias Mann
 * @author Nathan Sweet
 * @author Tommy Ettinger (PNG-8 parts only) */
public class PNG8 implements AnimationWriter, Dithered, Disposable {
    private static final byte[] SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    private static final int IHDR = 0x49484452, IDAT = 0x49444154, IEND = 0x49454E44,
            PLTE = 0x504C5445, TRNS = 0x74524E53,
            acTL = 0x6163544C, fcTL = 0x6663544C, fdAT = 0x66644154;
    private static final byte COLOR_INDEXED = 3;
    private static final byte COMPRESSION_DEFLATE = 0;
    private static final byte INTERLACE_NONE = 0;
    private static final byte FILTER_NONE = 0;
//    private static final byte FILTER_PAETH = 4;

    private final ChunkBuffer buffer;
    private final Deflater deflater;
    private ByteArray curLineBytes;
    private boolean flipY = true;

    public PaletteReducer palette;

    protected DitherAlgorithm ditherAlgorithm = DitherAlgorithm.OVERBOARD;

    @Override
    public PaletteReducer getPalette() {
        return palette;
    }

    public void setPalette(PaletteReducer palette) {
        this.palette = palette;
    }

    /**
     * Gets the {@link DitherAlgorithm} this is currently using.
     * @return which dithering algorithm this currently uses.
     */
    public DitherAlgorithm getDitherAlgorithm() {
        return ditherAlgorithm;
    }

    /**
     * Sets the dither algorithm (or disables it) using an enum constant from {@link DitherAlgorithm}. If this
     * is given null, it instead does nothing.
     * @param ditherAlgorithm which {@link DitherAlgorithm} to use for upcoming output
     */
    public void setDitherAlgorithm(DitherAlgorithm ditherAlgorithm) {
        if(ditherAlgorithm != null)
            this.ditherAlgorithm = ditherAlgorithm;
    }

    /**
     * Overrides the palette's dither strength; see {@link #getDitherStrength()}.
     * @see #getDitherStrength()
     */
    protected float ditherStrength = 1f;

    /**
     * Gets this PNG8's dither strength, which will override the {@link PaletteReducer#getDitherStrength()} in
     * the PaletteReducer this uses. This applies even if {@link #getPalette()} is null; in that case, when a temporary
     * PaletteReducer is created, it will use this dither strength.
     * @return the current dither strength override
     */
    public float getDitherStrength() {
        return ditherStrength;
    }

    /**
     * Sets this PNG8's dither strength, which will override the {@link PaletteReducer#getDitherStrength()} in
     * the PaletteReducer this uses. This applies even if {@link #getPalette()} is null; in that case, when a temporary
     * PaletteReducer is created, it will use this dither strength.
     * @param ditherStrength the desired dither strength, usually between 0 and 2 and defaulting to 1
     */
    public void setDitherStrength(float ditherStrength) {
        this.ditherStrength = Math.max(0f, ditherStrength);
    }

    public PNG8() {
        this(128 * 128);
    }

    public PNG8(int initialBufferSize) {
        buffer = new ChunkBuffer(initialBufferSize);
        deflater = new Deflater();
    }

    /** If true, the resulting PNG is flipped vertically. Default is true. */
    public void setFlipY (boolean flipY) {
        this.flipY = flipY;
    }

    /** Sets the deflate compression level. Default is {@link Deflater#DEFAULT_COMPRESSION}. */
    public void setCompression (int level) {
        deflater.setLevel(level);
    }

    /**
     * Writes the given Pixmap to the requested FileHandle, computing an 8-bit palette from the most common colors in
     * pixmap. If there are 256 or less colors and none are transparent, this will use 256 colors in its palette exactly
     * with no transparent entry, but if there are more than 256 colors or any are transparent, then one color will be
     * used for "fully transparent" and 255 opaque colors will be used.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given file
     */
    public void write (FileHandle file, Pixmap pixmap) {
        write(file, pixmap, true);
    }

    /**
     * Writes the given Pixmap to the requested FileHandle, optionally computing an 8-bit palette from the most common
     * colors in pixmap. When computePalette is true, if there are 256 or less colors and none are transparent, this
     * will use 256 colors in its palette exactly with no transparent entry, but if there are more than 256 colors or
     * any are transparent, then one color will be used for "fully transparent" and 255 opaque colors will be used. When
     * computePalette is false, this uses the last palette this had computed, or the 256-color "Snuggly" palette if no
     * palette had been computed yet.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given file
     * @param computePalette if true, this will analyze the Pixmap and use the most common colors
     */
    public void write (FileHandle file, Pixmap pixmap, boolean computePalette) {
        OutputStream output = file.write(false);
        try {
            write(output, pixmap, computePalette);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }
    /**
     * Writes the pixmap to the stream without closing the stream, optionally computing an 8-bit palette from the given
     * Pixmap. If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from the given Pixmap regardless of computePalette. Optionally dithers the result if
     * {@code dither} is true.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given output stream
     * @param computePalette if true, this will analyze the Pixmap and use the most common colors
     * @param dither true if this should dither colors that can't be represented exactly
     */
    public void write (FileHandle file, Pixmap pixmap, boolean computePalette, boolean dither) {
        OutputStream output = file.write(false);
        try {
            write(output, pixmap, computePalette, dither);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }
    /**
     * Writes the pixmap to the stream without closing the stream, optionally computing an 8-bit palette from the given
     * Pixmap. If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from the given Pixmap regardless of computePalette. Uses the given threshold while analyzing
     * the palette if this needs to compute a palette; threshold values can be as low as 0 to try to use as many colors
     * as possible (prefer {@link  #writePrecisely(FileHandle, Pixmap, boolean, int)} for that, though) and can range up
     * to very high numbers if very few colors should be used; usually threshold is from 100 to 800. Optionally dithers
     * the result if {@code dither} is true.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given output stream
     * @param computePalette if true, this will analyze the Pixmap and use the most common colors
     * @param dither true if this should dither colors that can't be represented exactly
     * @param threshold the analysis threshold to use if computePalette is true (min 0, practical max is over 100000)
     */
    public void write (FileHandle file, Pixmap pixmap, boolean computePalette, boolean dither, int threshold) {
        OutputStream output = file.write(false);
        try {
            write(output, pixmap, computePalette, dither, threshold);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /** Writes the pixmap to the stream without closing the stream and computes an 8-bit palette from the Pixmap.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     */
    public void write (OutputStream output, Pixmap pixmap) {
        writePrecisely(output, pixmap, true);
    }

    /**
     * Writes the pixmap to the stream without closing the stream, optionally computing an 8-bit palette from the given
     * Pixmap. If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from the given Pixmap regardless of computePalette.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param computePalette if true, this will analyze the Pixmap and use the most common colors
     */
    public void write (OutputStream output, Pixmap pixmap, boolean computePalette)
    {
        if(computePalette)
            writePrecisely(output, pixmap, true);
        else
            write(output, pixmap, false, true);
    }

    /**
     * Writes the pixmap to the stream without closing the stream, optionally computing an 8-bit palette from the given
     * Pixmap. If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from the given Pixmap regardless of computePalette.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param computePalette if true, this will analyze the Pixmap and use the most common colors
     * @param dither true if this should dither colors that can't be represented exactly
     */
    public void write (OutputStream output, Pixmap pixmap, boolean computePalette, boolean dither)
    {
        write(output, pixmap, computePalette, dither, 400);
    }
    /**
     * Writes the pixmap to the stream without closing the stream, optionally computing an 8-bit palette from the given
     * Pixmap. If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from the given Pixmap regardless of computePalette. This does not consider the ditherStrength
     * set in the palette, if non-null, but does use the {@link #getDitherStrength()} here.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param computePalette if true, this will analyze the Pixmap and use the most common colors
     * @param dither true if this should dither colors that can't be represented exactly
     * @param threshold the analysis threshold to use if computePalette is true (min 0, practical max is over 100000)
     */
    public void write (OutputStream output, Pixmap pixmap, boolean computePalette, boolean dither, int threshold) {
        boolean clearPalette = (palette == null);
        if(clearPalette)
        {
            palette = new PaletteReducer(pixmap, threshold);
        }
        else if(computePalette)
        {
            palette.analyze(pixmap, threshold);
        }
        palette.setDitherStrength(ditherStrength);

        if(dither) {
            writeDithered(output, pixmap);
        }
        else writeSolid(output, pixmap);
        if(clearPalette) palette = null;
    }

    /**
     * Uses the current {@link #ditherAlgorithm} to select which writing method to use, such as
     * {@link #writeWrenDithered(OutputStream, Pixmap)} or {@link #writePatternDithered(OutputStream, Pixmap)}.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     */
    public void writeDithered(OutputStream output, Pixmap pixmap) {
        if(ditherAlgorithm == null) {
            writeSolid(output, pixmap);
            return;
        }
        switch (ditherAlgorithm) {
            case NONE:
                writeSolid(output, pixmap);
                break;
            case GRADIENT_NOISE:
                writeGradientDithered(output, pixmap);
                break;
            case ROBERTS:
                writeRobertsDithered(output, pixmap);
                break;
            case PATTERN:
                writePatternDithered(output, pixmap);
                break;
            case CHAOTIC_NOISE:
                writeChaoticNoiseDithered(output, pixmap);
                break;
            case DIFFUSION:
                writeDiffusionDithered(output, pixmap);
                break;
            case BLUE_NOISE:
                writeBlueNoiseDithered(output, pixmap);
                break;
            case BLUNT:
                writeBluntDithered(output, pixmap);
                break;
            case BANTER:
                writeBanterDithered(output, pixmap);
                break;
            case SCATTER:
                writeScatterDithered(output, pixmap);
                break;
            case WOVEN:
                writeWovenDithered(output, pixmap);
                break;
            case DODGY:
                writeDodgyDithered(output, pixmap);
                break;
            case LOAF:
                writeLoafDithered(output, pixmap);
                break;
            case NEUE:
                writeNeueDithered(output, pixmap);
                break;
            case BURKES:
                writeBurkesDithered(output, pixmap);
                break;
            case OCEANIC:
                writeOceanicDithered(output, pixmap);
                break;
            case SEASIDE:
                writeSeasideDithered(output, pixmap);
                break;
            case GOURD:
                writeGourdDithered(output, pixmap);
                break;
            case OVERBOARD:
                writeOverboardDithered(output, pixmap);
                break;
            case MARTEN:
                writeMartenDithered(output, pixmap);
                break;
            case WREN:
            default:
                writeWrenDithered(output, pixmap);
                break;

        }
    }

    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to file; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this falls back to calling {@link #write(FileHandle, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette. The write() methods in this class that don't have
     * "Precise" in the name will reduce the color depth somewhat, but this will keep the non-alpha components of colors
     * exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given output stream
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     */
    public void writePrecisely (FileHandle file, Pixmap pixmap, boolean ditherFallback) {
        writePrecisely(file, pixmap, ditherFallback, 400);
    }
    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to file; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this falls back to calling {@link #write(FileHandle, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette, using the given threshold for analysis (which is
     * typically between 1 and 1000, and most often near 200-400). The write() methods in this class that don't have
     * "Precise" in the name will reduce the color depth somewhat, but this will keep the non-alpha components of colors
     * exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given output stream
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     * @param threshold the analysis threshold to use if there are too many colors (min 0, practical max is over 100000)
     */
    public void writePrecisely (FileHandle file, Pixmap pixmap, boolean ditherFallback, int threshold) {
        OutputStream output = file.write(false);
        try {
            writePrecisely(output, pixmap, ditherFallback, threshold);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to output; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the colors in the Pixmap can be accurately represented by some or all of {@code exactPalette} (and it is
     * non-null), then that palette will be used in full and in order. If {@code exactPalette} is null, this scans
     * through all the colors in pixmap, using the full set of colors if there are 256 or less (including transparent,
     * if present), or if there are too many colors, this falls back to calling
     * {@link #write(OutputStream, Pixmap, boolean, boolean)}, which can dither the image to use no more than 255 colors
     * (plus fully transparent) based on ditherFallback and will always analyze the Pixmap to get an accurate-enough
     * palette, using the given threshold for analysis (which is typically between 1 and 1000, and most often near
     * 200-400). The dither algorithm can be configured with {@link #setDitherAlgorithm(DitherAlgorithm)}, if it gets
     * used at all. The write() methods in this class that don't have "Precise" in the name will reduce the color depth
     * somewhat, but this will keep the non-alpha components of colors exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given output stream
     * @param exactPalette if non-null, will try to use this palette exactly, in order and including unused colors
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     * @param threshold the analysis threshold to use if there are too many colors (min 0, practical max is over 100000)
     */
    public void writePrecisely (FileHandle file, Pixmap pixmap, int[] exactPalette, boolean ditherFallback, int threshold) {
        OutputStream output = file.write(false);
        try {
            writePrecisely(output, pixmap, exactPalette, ditherFallback, threshold);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to output; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this falls back to calling {@link #write(OutputStream, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette. The write() methods in this class that don't have
     * "Precise" in the name will reduce the color depth somewhat, but this will keep the non-alpha components of colors
     * exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     */
    public void writePrecisely(OutputStream output, Pixmap pixmap, boolean ditherFallback) {
        writePrecisely(output, pixmap, ditherFallback, 400);
    }

    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to output; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this falls back to calling {@link #write(OutputStream, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette, using the given threshold for analysis (which is
     * typically between 1 and 1000, and most often near 200-400). The write() methods in this class that don't have
     * "Precise" in the name will reduce the color depth somewhat, but this will keep the non-alpha components of colors
     * exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     * @param threshold the analysis threshold to use if there are too many colors (min 0, practical max is over 100000)
     */
    public void writePrecisely(OutputStream output, Pixmap pixmap, boolean ditherFallback, int threshold) {
        writePrecisely(output, pixmap, null, ditherFallback, threshold);
    }

    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to output; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the colors in the Pixmap can be accurately represented by some or all of {@code exactPalette} (and it is
     * non-null), then that palette will be used in full and in order. If {@code exactPalette} is null, this scans
     * through all the colors in pixmap, using the full set of colors if there are 256 or less (including transparent,
     * if present), or if there are too many colors, this falls back to calling
     * {@link #write(OutputStream, Pixmap, boolean, boolean)}, which can dither the image to use no more than 255 colors
     * (plus fully transparent) based on ditherFallback and will always analyze the Pixmap to get an accurate-enough
     * palette, using the given threshold for analysis (which is typically between 1 and 1000, and most often near
     * 200-400). The dither algorithm can be configured with {@link #setDitherAlgorithm(DitherAlgorithm)}, if it gets
     * used at all. The write() methods in this class that don't have "Precise" in the name will reduce the color depth
     * somewhat, but this will keep the non-alpha components of colors exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param exactPalette if non-null, will try to use this palette exactly, in order and including unused colors
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     * @param threshold the analysis threshold to use if there are too many colors (min 0, practical max is over 100000)
     */
    public void writePrecisely(OutputStream output, Pixmap pixmap, int[] exactPalette, boolean ditherFallback, int threshold) {
        IntIntMap colorToIndex = new IntIntMap(256);
        colorToIndex.put(0, 0);
        int color;
        int hasTransparent = 0;
        final int w = pixmap.getWidth(), h = pixmap.getHeight();
        int[] paletteArray;
        if (exactPalette == null) {
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if ((color & 0xFE) != 0xFE && !colorToIndex.containsKey(color)) {
                        if (hasTransparent == 0 && colorToIndex.size >= 256) {
                            write(output, pixmap, true, ditherFallback, threshold);
                            return;
                        }
                        hasTransparent = 1;
                    } else if (!colorToIndex.containsKey(color)) {
                        colorToIndex.put(color, colorToIndex.size & 255);
                        if (colorToIndex.size == 257 && hasTransparent == 0) {
                            colorToIndex.remove(0, 0);
                        }
                        if (colorToIndex.size > 256) {
                            write(output, pixmap, true, ditherFallback, threshold);
                            return;
                        }
                    }
                }
            }
            paletteArray = new int[colorToIndex.size];
            for (IntIntMap.Entry ent : colorToIndex) {
                paletteArray[ent.value] = ent.key;
            }
        } else {
            hasTransparent = (exactPalette[0] == 0) ? 1 : 0;
            paletteArray = exactPalette;
            for (int i = hasTransparent; i < paletteArray.length; i++) {
                colorToIndex.put(paletteArray[i], i);
            }
        }
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            if (hasTransparent == 1) {
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int lineLen = pixmap.getWidth();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(lineLen)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(lineLen);
            }

            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    curLine[px] = (byte) colorToIndex.get(color, 0);
                }

//                lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                //Paeth
//                for (int x = 1; x < lineLen; x++) {
//                    int a = curLine[x - 1] & 0xff;
//                    int b = prevLine[x] & 0xff;
//                    int c = prevLine[x - 1] & 0xff;
//                    int p = a + b - c;
//                    int pa = p - a;
//                    if (pa < 0) pa = -pa;
//                    int pb = p - b;
//                    if (pb < 0) pb = -pb;
//                    int pc = p - c;
//                    if (pc < 0) pc = -pc;
//                    if (pa <= pb && pa <= pc)
//                        c = a;
//                    else if (pb <= pc)
//                        c = b;
//                    lineOut[x] = (byte) (curLine[x] - c);
//                }
//
//                deflaterOutput.write(FILTER_PAETH);
//                deflaterOutput.write(lineOut, 0, lineLen);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, lineLen);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }
    /**
     * Attempts to write a rectangular section of the given Pixmap exactly as a PNG-8 image to file; this attempt will
     * only succeed if there are no more than 256 colors in the Pixmap (treating all partially transparent colors as
     * fully transparent). If the attempt fails, this will throw an IllegalArgumentException. The write() methods in
     * this class that don't have "Precise" in the name will reduce the color depth somewhat, but this will keep the
     * non-alpha components of colors exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given output stream
     * @param exactPalette a palette with no more than 256 RGBA8888 ints in it; the colors will be used exactly if possible
     * @param startX start x-coordinate of the section in pixmap
     * @param startY start y-coordinate of the section in pixmap
     * @param width width of the section, in pixels
     * @param height height of the section, in pixels
     */
    public void writePreciseSection (FileHandle file, Pixmap pixmap, int[] exactPalette, int startX, int startY, int width, int height) {
        OutputStream output = file.write(false);
        try {
            writePreciseSection(output, pixmap, exactPalette, startX, startY, width, height);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * Attempts to write a rectangular section of the given Pixmap exactly as a PNG-8 image to output; this attempt will
     * only succeed if there are no more than 256 colors in the Pixmap (treating all partially transparent colors as
     * fully transparent). If the attempt fails, this will throw an IllegalArgumentException. The write() methods in
     * this class that don't have "Precise" in the name will reduce the color depth somewhat, but this will keep the
     * non-alpha components of colors exactly. For full precision on any color count, use {@link FastPNG},
     * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param exactPalette a palette with no more than 256 RGBA8888 ints in it; the colors will be used exactly if possible
     * @param startX start x-coordinate of the section in pixmap
     * @param startY start y-coordinate of the section in pixmap
     * @param width width of the section, in pixels
     * @param height height of the section, in pixels
     */
    public void writePreciseSection(OutputStream output, Pixmap pixmap, int[] exactPalette, int startX, int startY, int width, int height) {
        IntIntMap colorToIndex = new IntIntMap(256);
        colorToIndex.put(0, 0);
        int color;
        int hasTransparent = 0;
        final int w = startX + width, h = startY + height;
        int[] paletteArray;
        if(exactPalette == null) {
            for (int y = startY; y < h; y++) {
                int py = flipY ? (pixmap.getHeight() - y - 1) : y;
                for (int px = startX; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if ((color & 0xFE) != 0xFE && !colorToIndex.containsKey(color)) {
                        if (hasTransparent == 0 && colorToIndex.size >= 256) {
                            throw new IllegalArgumentException("Too many colors to write precisely!");
                        }
                        hasTransparent = 1;
                    } else if (!colorToIndex.containsKey(color)) {
                        colorToIndex.put(color, colorToIndex.size & 255);
                        if (colorToIndex.size == 257 && hasTransparent == 0) {
                            colorToIndex.remove(0, 0);
                        }
                        if (colorToIndex.size > 256) {
                            throw new IllegalArgumentException("Too many colors to write precisely!");
                        }
                    }
                }
            }
            paletteArray = new int[colorToIndex.size];
            for (IntIntMap.Entry ent : colorToIndex) {
                paletteArray[ent.value] = ent.key;
            }
        }
        else
        {
            hasTransparent = (exactPalette[0] == 0) ? 1 : 0;
            paletteArray = exactPalette;
            for (int i = hasTransparent; i < paletteArray.length; i++) {
                colorToIndex.put(paletteArray[i], i);
            }
        }
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
        dataOutput.write(SIGNATURE);

        buffer.writeInt(IHDR);
        buffer.writeInt(width);
        buffer.writeInt(height);
        buffer.writeByte(8); // 8 bits per component.
        buffer.writeByte(COLOR_INDEXED);
        buffer.writeByte(COMPRESSION_DEFLATE);
        buffer.writeByte(FILTER_NONE);
        buffer.writeByte(INTERLACE_NONE);
        buffer.endChunk(dataOutput);

        buffer.writeInt(PLTE);
        for (int i = 0; i < paletteArray.length; i++) {
            int p = paletteArray[i];
            buffer.write(p>>>24);
            buffer.write(p>>>16);
            buffer.write(p>>>8);
        }
        buffer.endChunk(dataOutput);

        if(hasTransparent == 1) {
            buffer.writeInt(TRNS);
            buffer.write(0);
            buffer.endChunk(dataOutput);
        }
        buffer.writeInt(IDAT);
        deflater.reset();

//        byte[] lineOut, curLine, prevLine;
        byte[] curLine;
        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }

            for (int y = startY; y < h; y++) {
            int py = flipY ? (pixmap.getHeight() - y - 1) : y;
            for (int px = startX; px < w; px++) {
                color = pixmap.getPixel(px, py);
                curLine[px - startX] = (byte) colorToIndex.get(color, 0);
            }

//            lineOut[0] = (byte)(curLine[0] - prevLine[0]);
//
//            //Paeth
//            for (int x = 1; x < lineLen; x++) {
//                int a = curLine[x - 1] & 0xff;
//                int b = prevLine[x] & 0xff;
//                int c = prevLine[x - 1] & 0xff;
//                int p = a + b - c;
//                int pa = p - a;
//                if (pa < 0) pa = -pa;
//                int pb = p - b;
//                if (pb < 0) pb = -pb;
//                int pc = p - c;
//                if (pc < 0) pc = -pc;
//                if (pa <= pb && pa <= pc)
//                    c = a;
//                else if (pb <= pc)
//                    c = b;
//                lineOut[x] = (byte)(curLine[x] - c);
//            }
//
//            deflaterOutput.write(FILTER_PAETH);
//            deflaterOutput.write(lineOut, 0, lineLen);

            deflaterOutput.write(FILTER_NONE);
            deflaterOutput.write(curLine, 0, width);
        }
        deflaterOutput.finish();
        buffer.endChunk(dataOutput);

        buffer.writeInt(IEND);
        buffer.endChunk(dataOutput);

        output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }
    public void writeSolid (OutputStream output, Pixmap pixmap){
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
        dataOutput.write(SIGNATURE);

        buffer.writeInt(IHDR);
        buffer.writeInt(pixmap.getWidth());
        buffer.writeInt(pixmap.getHeight());
        buffer.writeByte(8); // 8 bits per component.
        buffer.writeByte(COLOR_INDEXED);
        buffer.writeByte(COMPRESSION_DEFLATE);
        buffer.writeByte(FILTER_NONE);
        buffer.writeByte(INTERLACE_NONE);
        buffer.endChunk(dataOutput);

        buffer.writeInt(PLTE);
        for (int i = 0; i < paletteArray.length; i++) {
            int p = paletteArray[i];
            buffer.write(p>>>24);
            buffer.write(p>>>16);
            buffer.write(p>>>8);
        }
        buffer.endChunk(dataOutput);

        boolean hasTransparent = false;
        if(paletteArray[0] == 0) {
            hasTransparent = true;
            buffer.writeInt(TRNS);
            buffer.write(0);
            buffer.endChunk(dataOutput);
        }
        buffer.writeInt(IDAT);
        deflater.reset();

        int lineLen = pixmap.getWidth();
//        byte[] lineOut, curLine, prevLine;
        byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(lineLen)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(lineLen);
        }

            int color;
        final int w = pixmap.getWidth(), h = pixmap.getHeight();
        for (int y = 0; y < h; y++) {
            int py = flipY ? (h - y - 1) : y;
            for (int px = 0; px < w; px++) {
                color = pixmap.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    curLine[px] = 0;
                else {
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))];
                }
            }

//            lineOut[0] = (byte)(curLine[0] - prevLine[0]);
//
//            //Paeth
//            for (int x = 1; x < lineLen; x++) {
//                int a = curLine[x - 1] & 0xff;
//                int b = prevLine[x] & 0xff;
//                int c = prevLine[x - 1] & 0xff;
//                int p = a + b - c;
//                int pa = p - a;
//                if (pa < 0) pa = -pa;
//                int pb = p - b;
//                if (pb < 0) pb = -pb;
//                int pc = p - c;
//                if (pc < 0) pc = -pc;
//                if (pa <= pb && pa <= pc)
//                    c = a;
//                else if (pb <= pc)
//                    c = b;
//                lineOut[x] = (byte)(curLine[x] - c);
//            }
//
//            deflaterOutput.write(FILTER_PAETH);
//            deflaterOutput.write(lineOut, 0, lineLen);

            deflaterOutput.write(FILTER_NONE);
            deflaterOutput.write(curLine, 0, lineLen);
        }
        deflaterOutput.finish();
        buffer.endChunk(dataOutput);

        buffer.writeInt(IEND);
        buffer.endChunk(dataOutput);

        output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeGradientDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            int color;
            final float populationBias = palette.populationBias;
            final float strength = 0.9f * (float) Math.tanh(0.16f * ditherStrength * Math.pow(populationBias, -7.00));

            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        int rr = fromLinearLUT[Math.min(Math.max((int)(toLinearLUT[(color >>> 24)       ] + ((142 * (px + 0x5F) + 79 * (y - 0x96) & 255) - 127.5f) * strength), 0), 1023)] & 255;
                        int gg = fromLinearLUT[Math.min(Math.max((int)(toLinearLUT[(color >>> 16) & 0xFF] + ((142 * (px + 0xFA) + 79 * (y - 0xA3) & 255) - 127.5f) * strength), 0), 1023)] & 255;
                        int bb = fromLinearLUT[Math.min(Math.max((int)(toLinearLUT[(color >>> 8)  & 0xFF] + ((142 * (px + 0xA5) + 79 * (y - 0xC9) & 255) - 127.5f) * strength), 0), 1023)] & 255;
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];

                    }
                }

//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeRobertsDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            int color;
            final float populationBias = palette.populationBias;
            final float str = Math.min(48 * ditherStrength / (populationBias * populationBias * populationBias * populationBias), 127);
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        // We get a sub-random value from 0-1 using the R2 sequence.
                        // Offsetting this value by different values and feeding into triangleWave()
                        // gives 3 different values for r, g, and b, without much bias toward high or low values.
                        // There is correlation between r, g, and b in certain patterns.
                        final float theta = ((px * 0xC13FA9A9 + y * 0x91E10DA5 >>> 9) * 0x1p-23f);
                        int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str)] & 255;
                        int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.209f) * str)] & 255;
                        int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.518f) * str)] & 255;
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];

                    }
                }

//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeLoafDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            final float strength = Math.min(Math.max(2.5f + 5f * ditherStrength - 5.5f * palette.populationBias, 0f), 7.9f);

            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    int color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        int adj = (int)((((px + y & 1) << 5) - 16) * strength); // either + 16 * strength or - 16 * strength
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + adj, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + adj, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + adj, 0), 1023)] & 255;
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3))];
                    }
                }

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeGourdDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            int color;
            final float strength = (float)(ditherStrength * 0.7 * Math.pow(palette.populationBias, -5.50));
            for (int i = 0; i < 64; i++) {
                PaletteReducer.tempThresholdMatrix[i] = Math.min(Math.max((PaletteReducer.thresholdMatrix64[i] - 31.5f) * strength, -127), 127);
            }
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        float adj = PaletteReducer.tempThresholdMatrix[(px & 7) | (y & 7) << 3];
                        int rr = PaletteReducer.fromLinearLUT[(int)(PaletteReducer.toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                        int gg = PaletteReducer.fromLinearLUT[(int)(PaletteReducer.toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                        int bb = PaletteReducer.fromLinearLUT[(int)(PaletteReducer.toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3))];
                    }
                }

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBlueNoiseDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            int color;
            final float strength = 0.21875f * ditherStrength / (palette.populationBias * palette.populationBias);
            for (int oy = 0; oy < h; oy++) {
                int y = flipY ? (h - oy - 1) : oy;
                for (int x = 0; x < w; x++) {
                    color = pixmap.getPixel(x, y);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[x] = 0;
                    else {
                        float adj = Math.min(Math.max(((TRI_BLUE_NOISE  [(x & 63) | (y & 63) << 6] + ((x + y & 1) << 8) - 127.5f) * strength), -100.5f), 101.5f);
                        int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                        int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                        int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;

                        curLine[x] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];
                    }
                }

//                lineOut[0] = (byte)(curLine[0] - prevLine[0]);
//
//                //Paeth
//                for (int x = 1; x < w; x++) {
//                    int a = curLine[x - 1] & 0xff;
//                    int b = prevLine[x] & 0xff;
//                    int c = prevLine[x - 1] & 0xff;
//                    int p = a + b - c;
//                    int pa = p - a;
//                    if (pa < 0) pa = -pa;
//                    int pb = p - b;
//                    if (pb < 0) pb = -pb;
//                    int pc = p - c;
//                    if (pc < 0) pc = -pc;
//                    if (pa <= pb && pa <= pc)
//                        c = a;
//                    else if (pb <= pc)
//                        c = b;
//                    lineOut[x] = (byte)(curLine[x] - c);
//                }
//
//                deflaterOutput.write(FILTER_PAETH);
//                deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBluntDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            int color;
            final float populationBias = palette.populationBias;
            final float strength = Math.min(Math.max(0.35f * ditherStrength / (populationBias * populationBias * populationBias), -0.6f), 0.6f);
            for (int oy = 0; oy < h; oy++) {
                int y = flipY ? (h - oy - 1) : oy;
                for (int x = 0; x < w; x++) {
                    color = pixmap.getPixel(x, y);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[x] = 0;
                    else {
                        float adj = (x+y<<7&128)-63.5f;
                        int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + (TRI_BLUE_NOISE  [(x + 62 & 63) << 6 | (y + 66  & 63)] + adj) * strength)] & 255;
                        int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + (TRI_BLUE_NOISE_B[(x + 31 & 63) << 6 | (y + 113 & 63)] + adj) * strength)] & 255;
                        int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + (TRI_BLUE_NOISE_C[(x + 71 & 63) << 6 | (y + 41  & 63)] + adj) * strength)] & 255;

                        curLine[x] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];
                    }
                }

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBanterDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            int color;
            float strength = Math.min(Math.max(0.17f * ditherStrength * (float) Math.pow(palette.populationBias, -10f), -0.95f), 0.95f);
            for (int oy = 0; oy < h; oy++) {
                int y = flipY ? (h - oy - 1) : oy;
                for (int x = 0; x < w; x++) {
                    color = pixmap.getPixel(x, y);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[x] = 0;
                    else {
                        float adj = TRI_BAYER_MATRIX_128[(x & TBM_MASK) << TBM_BITS | (y & TBM_MASK)] * strength;
                        int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                        int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                        int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;

                        curLine[x] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];
                    }
                }

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeChaoticNoiseDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            int color, used;

            byte paletteIndex;
            double adj, strength = ditherStrength * palette.populationBias * 1.5;
            long s = 0xC13FA9A902A6328FL;
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        int rr = ((color >>> 24)       );
                        int gg = ((color >>> 16) & 0xFF);
                        int bb = ((color >>> 8)  & 0xFF);
                        paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
                        adj *= adj * adj;
                        // Complicated... This starts with a checkerboard of -0.5 and 0.5, times a tiny fraction.
                        // The next 3 lines generate 3 low-quality-random numbers based on s, which should be
                        //   different as long as the colors encountered so far were different. The numbers can
                        //   each be positive or negative, and are reduced to a manageable size, summed, and
                        //   multiplied by the earlier tiny fraction. Summing 3 random values gives us a curved
                        //   distribution, centered on about 0.0 and weighted so most results are close to 0.
                        //   Two of the random numbers use an XLCG, and the last uses an LCG.
                        adj += ((px + y & 1) - 0.5f) * 0x1.8p-49 * strength *
                                (((s ^ 0x9E3779B97F4A7C15L) * 0xC6BC279692B5CC83L >> 15) +
                                        ((~s ^ 0xDB4F0B9175AE2165L) * 0xD1B54A32D192ED03L >> 15) +
                                        ((s = (s ^ rr + gg + bb) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                        rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                        gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                        bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];
                    }
                }

//                lineOut[0] = (byte)(curLine[0] - prevLine[0]);
//
//                //Paeth
//                for (int x = 1; x < w; x++) {
//                    int a = curLine[x - 1] & 0xff;
//                    int b = prevLine[x] & 0xff;
//                    int c = prevLine[x - 1] & 0xff;
//                    int p = a + b - c;
//                    int pa = p - a;
//                    if (pa < 0) pa = -pa;
//                    int pb = p - b;
//                    if (pb < 0) pb = -pb;
//                    int pc = p - c;
//                    if (pc < 0) pc = -pc;
//                    if (pa <= pb && pa <= pc)
//                        c = a;
//                    else if (pb <= pc)
//                        c = b;
//                    lineOut[x] = (byte)(curLine[x] - c);
//                }
//
//                deflaterOutput.write(FILTER_PAETH);
//                deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeDiffusionDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            float rdiff, gdiff, bdiff;
            byte paletteIndex;
            float w1 = ditherStrength * 32 / palette.populationBias, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + curErrorRed[px]  , 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + curErrorGreen[px], 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + curErrorBlue[px] , 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = Math.min(Math.max(0x1p-8f * ((color>>>24)-    (used>>>24))    , -1), 1);
                        gdiff = Math.min(Math.max(0x1p-8f * ((color>>>16&255)-(used>>>16&255)), -1), 1);
                        bdiff = Math.min(Math.max(0x1p-8f * ((color>>>8&255)- (used>>>8&255)) , -1), 1);
                        // this alternate code used a sigmoid function to smoothly limit error.
//                    rdiff = (0x1.8p-8f * ((color>>>24)-    (used>>>24))    );
//                    gdiff = (0x1.8p-8f * ((color>>>16&255)-(used>>>16&255)));
//                    bdiff = (0x1.8p-8f * ((color>>>8&255)- (used>>>8&255)) );
//                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
//                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
//                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                        if(px < w - 1)
                        {
                            curErrorRed[px+1]   += rdiff * w7;
                            curErrorGreen[px+1] += gdiff * w7;
                            curErrorBlue[px+1]  += bdiff * w7;
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                nextErrorRed[px-1]   += rdiff * w3;
                                nextErrorGreen[px-1] += gdiff * w3;
                                nextErrorBlue[px-1]  += bdiff * w3;
                            }
                            if(px < w - 1)
                            {
                                nextErrorRed[px+1]   += rdiff * w1;
                                nextErrorGreen[px+1] += gdiff * w1;
                                nextErrorBlue[px+1]  += bdiff * w1;
                            }
                            nextErrorRed[px]   += rdiff * w5;
                            nextErrorGreen[px] += gdiff * w5;
                            nextErrorBlue[px]  += bdiff * w5;
                        }
                    }
                }
//                lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                //Paeth
//                for (int x = 1; x < w; x++) {
//                    int a = curLine[x - 1] & 0xff;
//                    int b = prevLine[x] & 0xff;
//                    int c = prevLine[x - 1] & 0xff;
//                    int p = a + b - c;
//                    int pa = p - a;
//                    if (pa < 0) pa = -pa;
//                    int pb = p - b;
//                    if (pb < 0) pb = -pb;
//                    int pc = p - c;
//                    if (pc < 0) pc = -pc;
//                    if (pa <= pb && pa <= pc)
//                        c = a;
//                    else if (pb <= pc)
//                        c = b;
//                    lineOut[x] = (byte) (curLine[x] - c);
//                }
//
//                deflaterOutput.write(FILTER_PAETH);
//                deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writePatternDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
        dataOutput.write(SIGNATURE);

        buffer.writeInt(IHDR);
        buffer.writeInt(pixmap.getWidth());
        buffer.writeInt(pixmap.getHeight());
        buffer.writeByte(8); // 8 bits per component.
        buffer.writeByte(COLOR_INDEXED);
        buffer.writeByte(COMPRESSION_DEFLATE);
        buffer.writeByte(FILTER_NONE);
        buffer.writeByte(INTERLACE_NONE);
        buffer.endChunk(dataOutput);

        buffer.writeInt(PLTE);
        for (int i = 0; i < paletteArray.length; i++) {
            int p = paletteArray[i];
            buffer.write(p>>>24);
            buffer.write(p>>>16);
            buffer.write(p>>>8);
        }
        buffer.endChunk(dataOutput);

        boolean hasTransparent = false;
        if(paletteArray[0] == 0) {
            hasTransparent = true;
            buffer.writeInt(TRNS);
            buffer.write(0);
            buffer.endChunk(dataOutput);
        }
        buffer.writeInt(IDAT);
        deflater.reset();

        final int w = pixmap.getWidth(), h = pixmap.getHeight();
//        byte[] lineOut, curLine, prevLine;
        byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            int color, used;
        int cr, cg, cb,  usedIndex;
        final float errorMul = ditherStrength * 0.5f / palette.populationBias;
        for (int y = 0; y < h; y++) {
            int py = flipY ? (h - y - 1) : y;
            for (int px = 0; px < w; px++) {
                color = pixmap.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    curLine[px] = 0;
                else {
                    int er = 0, eg = 0, eb = 0;
                    cr = (color >>> 24);
                    cg = (color >>> 16 & 0xFF);
                    cb = (color >>> 8 & 0xFF);
                    for (int i = 0; i < 16; i++) {
                        int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                        int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                        int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        palette.candidates[i | 16] = shrink(used = paletteArray[palette.candidates[i] = usedIndex]);
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    PaletteReducer.sort16(palette.candidates);
                    curLine[px] = (byte)palette.candidates[PaletteReducer.thresholdMatrix16[((px & 3) | (y & 3) << 2)]];
                }
            }

//            lineOut[0] = (byte)(curLine[0] - prevLine[0]);
//
//            //Paeth
//            for (int x = 1; x < w; x++) {
//                int a = curLine[x - 1] & 0xff;
//                int b = prevLine[x] & 0xff;
//                int c = prevLine[x - 1] & 0xff;
//                int p = a + b - c;
//                int pa = p - a;
//                if (pa < 0) pa = -pa;
//                int pb = p - b;
//                if (pb < 0) pb = -pb;
//                int pc = p - c;
//                if (pc < 0) pc = -pc;
//                if (pa <= pb && pa <= pc)
//                    c = a;
//                else if (pb <= pc)
//                    c = b;
//                lineOut[x] = (byte)(curLine[x] - c);
//            }
//
//            deflaterOutput.write(FILTER_PAETH);
//            deflaterOutput.write(lineOut, 0, w);

            deflaterOutput.write(FILTER_NONE);
            deflaterOutput.write(curLine, 0, w);
        }
        deflaterOutput.finish();
        buffer.endChunk(dataOutput);

        buffer.writeInt(IEND);
        buffer.endChunk(dataOutput);

        output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeScatterDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float w1 = Math.min(ditherStrength * 5.5f / (palette.populationBias * palette.populationBias), 16f),
                    w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                        er = curErrorRed[px] * tbn;
                        eg = curErrorGreen[px] * tbn;
                        eb = curErrorBlue[px] * tbn;
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = (0x2.1p-8f * ((color>>>24)-    (used>>>24))    );
                        gdiff = (0x2.1p-8f * ((color>>>16&255)-(used>>>16&255)));
                        bdiff = (0x2.1p-8f * ((color>>>8&255)- (used>>>8&255)) );
                        rdiff /= (0.125f + Math.abs(rdiff));
                        gdiff /= (0.125f + Math.abs(gdiff));
                        bdiff /= (0.125f + Math.abs(bdiff));
                        if(px < w - 1)
                        {
                            curErrorRed[px+1]   += rdiff * w7;
                            curErrorGreen[px+1] += gdiff * w7;
                            curErrorBlue[px+1]  += bdiff * w7;
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                nextErrorRed[px-1]   += rdiff * w3;
                                nextErrorGreen[px-1] += gdiff * w3;
                                nextErrorBlue[px-1]  += bdiff * w3;
                            }
                            if(px < w - 1)
                            {
                                nextErrorRed[px+1]   += rdiff * w1;
                                nextErrorGreen[px+1] += gdiff * w1;
                                nextErrorBlue[px+1]  += bdiff * w1;
                            }
                            nextErrorRed[px]   += rdiff * w5;
                            nextErrorGreen[px] += gdiff * w5;
                            nextErrorBlue[px]  += bdiff * w5;
                        }
                    }
                }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeNeueDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb, adj;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            final float w1 = ditherStrength * 8f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = (70f * ditherStrength / (populationBias * populationBias * populationBias)),
                    limit = Math.min(127, (float) Math.pow(80, 1.635 - populationBias));
            byte[] curLine;
        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (py & 63) << 6] + 0.5f) * 0.005f); // plus or minus 255/400
                        adj = Math.min(Math.max(adj * strength, -limit), limit);
                        er = adj + (curErrorRed[px]);
                        eg = adj + (curErrorGreen[px]);
                        eb = adj + (curErrorBlue[px]);
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = (0x2.Ep-8f * ((color>>>24)-    (used>>>24))    );
                        gdiff = (0x2.Ep-8f * ((color>>>16&255)-(used>>>16&255)));
                        bdiff = (0x2.Ep-8f * ((color>>>8&255)- (used>>>8&255)) );
                        rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                        gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                        bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                        if(px < w - 1)
                        {
                            curErrorRed[px+1]   += rdiff * w7;
                            curErrorGreen[px+1] += gdiff * w7;
                            curErrorBlue[px+1]  += bdiff * w7;
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                nextErrorRed[px-1]   += rdiff * w3;
                                nextErrorGreen[px-1] += gdiff * w3;
                                nextErrorBlue[px-1]  += bdiff * w3;
                            }
                            if(px < w - 1)
                            {
                                nextErrorRed[px+1]   += rdiff * w1;
                                nextErrorGreen[px+1] += gdiff * w1;
                                nextErrorBlue[px+1]  += bdiff * w1;
                            }
                            nextErrorRed[px]   += rdiff * w5;
                            nextErrorGreen[px] += gdiff * w5;
                            nextErrorBlue[px]  += bdiff * w5;
                        }
                    }
                }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeDodgyDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            float populationBias = palette.populationBias;
            final float w1 = 8f * ditherStrength,
                    w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = 0.35f * ditherStrength / (populationBias * populationBias * populationBias),
                    limit = 90f;
            byte[] curLine;
        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        er = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorRed[px]);
                        eg = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorGreen[px]);
                        eb = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorBlue[px]);

                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];

                        rdiff = (0x5p-8f * ((color>>>24)-    (used>>>24))    );
                        gdiff = (0x5p-8f * ((color>>>16&255)-(used>>>16&255)));
                        bdiff = (0x5p-8f * ((color>>>8&255)- (used>>>8&255)) );
                        rdiff /= (0.5f + Math.abs(rdiff));
                        gdiff /= (0.5f + Math.abs(gdiff));
                        bdiff /= (0.5f + Math.abs(bdiff));

                        if(px < w - 1)
                        {
                            curErrorRed[px+1]   += rdiff * w7;
                            curErrorGreen[px+1] += gdiff * w7;
                            curErrorBlue[px+1]  += bdiff * w7;
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                nextErrorRed[px-1]   += rdiff * w3;
                                nextErrorGreen[px-1] += gdiff * w3;
                                nextErrorBlue[px-1]  += bdiff * w3;
                            }
                            if(px < w - 1)
                            {
                                nextErrorRed[px+1]   += rdiff * w1;
                                nextErrorGreen[px+1] += gdiff * w1;
                                nextErrorBlue[px+1]  += bdiff * w1;
                            }
                            nextErrorRed[px]   += rdiff * w5;
                            nextErrorGreen[px] += gdiff * w5;
                            nextErrorBlue[px]  += bdiff * w5;
                        }
                    }
                }
                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }


    public void writeWovenDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            final float w1 = (float) (10f * Math.sqrt(ditherStrength) / (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = 100f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                    limit = 5f + 250f / (float)Math.sqrt(palette.colorCount+1.5f);
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        er = Math.min(Math.max(((((px+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorRed[px]);
                        eg = Math.min(Math.max(((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorGreen[px]);
                        eb = Math.min(Math.max(((((px+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorBlue[px]);

                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = (0x5p-10f * ((color>>>24)-    (used>>>24))    );
                        gdiff = (0x5p-10f * ((color>>>16&255)-(used>>>16&255)));
                        bdiff = (0x5p-10f * ((color>>>8&255)- (used>>>8&255)) );
                        if(px < w - 1)
                        {
                            curErrorRed[px+1]   += rdiff * w7;
                            curErrorGreen[px+1] += gdiff * w7;
                            curErrorBlue[px+1]  += bdiff * w7;
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                nextErrorRed[px-1]   += rdiff * w3;
                                nextErrorGreen[px-1] += gdiff * w3;
                                nextErrorBlue[px-1]  += bdiff * w3;
                            }
                            if(px < w - 1)
                            {
                                nextErrorRed[px+1]   += rdiff * w1;
                                nextErrorGreen[px+1] += gdiff * w1;
                                nextErrorBlue[px+1]  += bdiff * w1;
                            }
                            nextErrorRed[px]   += rdiff * w5;
                            nextErrorGreen[px] += gdiff * w5;
                            nextErrorBlue[px]  += bdiff * w5;
                        }
                    }
                }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }


    public void writeWrenOriginalDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            final float w1 = (float) (32.0 * ditherStrength * (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = (0.2f * ditherStrength / (populationBias * populationBias * populationBias * populationBias)),
                    limit = 5f + 125f / (float)Math.sqrt(palette.colorCount+1.5),
                    dmul = 0x1p-8f;
            byte[] curLine;
            if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+1) * 0xC13FA9A902A6328FL + (y +1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorRed[px]), -limit), limit);
                        eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+3) * 0xC13FA9A902A6328FL + (y -1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorGreen[px]), -limit), limit);
                        eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+2) * 0xC13FA9A902A6328FL + (y -4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorBlue[px]), -limit), limit);

                        int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                        int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                        int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = (dmul * ((color>>>24)-    (used>>>24))    );
                        gdiff = (dmul * ((color>>>16&255)-(used>>>16&255)));
                        bdiff = (dmul * ((color>>>8&255)- (used>>>8&255)) );
                        if(px < w - 1)
                        {
                            curErrorRed[px+1]   += rdiff * w7;
                            curErrorGreen[px+1] += gdiff * w7;
                            curErrorBlue[px+1]  += bdiff * w7;
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                nextErrorRed[px-1]   += rdiff * w3;
                                nextErrorGreen[px-1] += gdiff * w3;
                                nextErrorBlue[px-1]  += bdiff * w3;
                            }
                            if(px < w - 1)
                            {
                                nextErrorRed[px+1]   += rdiff * w1;
                                nextErrorGreen[px+1] += gdiff * w1;
                                nextErrorBlue[px+1]  += bdiff * w1;
                            }
                            nextErrorRed[px]   += rdiff * w5;
                            nextErrorGreen[px] += gdiff * w5;
                            nextErrorBlue[px]  += bdiff * w5;
                        }
                    }
                }

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeWrenDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            float partialDitherStrength = (0.5f * ditherStrength / (populationBias * populationBias)),
                    strength = (80f * ditherStrength / (populationBias * populationBias)),
                    blueStrength = (0.3f * ditherStrength / (populationBias * populationBias)),
                    limit = 5f + 200f / (float)Math.sqrt(palette.colorCount+1.5f),
                    r1, g1, b1, r2, g2, b2, r4, g4, b4;
            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            for (int by = 0; by < h; by++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int y = flipY ? (h - by - 1) : by;
                for (int x = 0; x < w; x++) {
                    color = pixmap.getPixel(x, y);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[x] = 0;
                    else {
                        er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(x & 63) | (by & 63) << 6] + 0.5f) * blueStrength + ((((x+1) * 0xC13FA9A902A6328FL + (by+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorRed[x]);
                        eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (by & 63) << 6] + 0.5f) * blueStrength + ((((x+3) * 0xC13FA9A902A6328FL + (by-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorGreen[x]);
                        eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (by & 63) << 6] + 0.5f) * blueStrength + ((((x+2) * 0xC13FA9A902A6328FL + (by-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorBlue[x]);

                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[x] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = ((color>>>24)-    (used>>>24))     * partialDitherStrength;
                        gdiff = ((color>>>16&255)-(used>>>16&255)) * partialDitherStrength;
                        bdiff = ((color>>>8&255)- (used>>>8&255))  * partialDitherStrength;

                        r1 = rdiff * 16f / (float)Math.sqrt(2048f + rdiff * rdiff);
                        g1 = gdiff * 16f / (float)Math.sqrt(2048f + gdiff * gdiff);
                        b1 = bdiff * 16f / (float)Math.sqrt(2048f + bdiff * bdiff);
                        r2 = r1 + r1;
                        g2 = g1 + g1;
                        b2 = b1 + b1;
                        r4 = r2 + r2;
                        g4 = g2 + g2;
                        b4 = b2 + b2;
                        if(x < w - 1)
                        {
                            curErrorRed[x+1]   += r4;
                            curErrorGreen[x+1] += g4;
                            curErrorBlue[x+1]  += b4;
                            if(x < w - 2)
                            {

                                curErrorRed[x+2]   += r2;
                                curErrorGreen[x+2] += g2;
                                curErrorBlue[x+2]  += b2;
                            }
                        }
                        if(by+1 < h)
                        {
                            if(x > 0)
                            {
                                nextErrorRed[x-1]   += r2;
                                nextErrorGreen[x-1] += g2;
                                nextErrorBlue[x-1]  += b2;
                                if(x > 1)
                                {
                                    nextErrorRed[x-2]   += r1;
                                    nextErrorGreen[x-2] += g1;
                                    nextErrorBlue[x-2]  += b1;
                                }
                            }
                            nextErrorRed[x]   += r4;
                            nextErrorGreen[x] += g4;
                            nextErrorBlue[x]  += b4;
                            if(x < w - 1)
                            {
                                nextErrorRed[x+1]   += r2;
                                nextErrorGreen[x+1] += g2;
                                nextErrorBlue[x+1]  += b2;
                                if(x < w - 2)
                                {

                                    nextErrorRed[x+2]   += r1;
                                    nextErrorGreen[x+2] += g1;
                                    nextErrorBlue[x+2]  += b1;
                                }
                            }
                        }
                    }
                }

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeOverboardDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            final int flipDir = flipY ? -1 : 1;
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final float populationBias = palette.populationBias;
            final float strength = ditherStrength * 1.5f * (populationBias * populationBias),
                    noiseStrength = 4f / (populationBias * populationBias),
                    limit = 110f;
            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            for (int by = 0, y = flipY ? h - 1 : 0; by < h; by++, y += flipDir) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                for (int x = 0; x < w; x++) {
                    int color = pixmap.getPixel(x, y);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[x] = 0;
                    else {
                        float er = 0f, eg = 0f, eb = 0f;
                        switch ((x << 1 & 2) | (y & 1)){
                            case 0:
                                er += ((x ^ y) % 9 - 4);
                                er += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                eg += (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                                eg += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                eb += (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                                eb += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                                break;
                            case 1:
                                er += (PaletteReducer.TRI_BLUE_NOISE[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                                er += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                eg += (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                                eg += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                                eb += ((x ^ y) % 11 - 5);
                                eb += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                                break;
                            case 2:
                                er += (PaletteReducer.TRI_BLUE_NOISE[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                                er += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                                eg += ((x ^ y) % 11 - 5);
                                eg += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                                eb += ((x ^ y) % 9 - 4);
                                eb += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                break;
                            default: // case 3:
                                er += ((x ^ y) % 11 - 5);
                                er += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                                eg += ((x ^ y) % 9 - 4);
                                eg += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                eb += (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                                eb += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                break;
                        }
                        er = er * noiseStrength + curErrorRed[x];
                        eg = eg * noiseStrength + curErrorGreen[x];
                        eb = eb * noiseStrength + curErrorBlue[x];
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + Math.min(Math.max(er, -limit), limit), 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + Math.min(Math.max(eg, -limit), limit), 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + Math.min(Math.max(eb, -limit), limit), 0), 1023)] & 255;
                        byte paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                               | ((gg << 2) & 0x3E0)
                                               | ((bb >>> 3))];
                        curLine[x] = paletteIndex;
                        int used = paletteArray[paletteIndex & 0xFF];
                        float rdiff = ((color >>> 24) - (used >>> 24)) * strength;
                        float gdiff = ((color >>> 16 & 255) - (used >>> 16 & 255)) * strength;
                        float bdiff = ((color >>> 8 & 255) - (used >>> 8 & 255)) * strength;
                        float r1 = rdiff * 16f / (45f + Math.abs(rdiff));
                        float g1 = gdiff * 16f / (45f + Math.abs(gdiff));
                        float b1 = bdiff * 16f / (45f + Math.abs(bdiff));
                        float r2 = r1 + r1;
                        float g2 = g1 + g1;
                        float b2 = b1 + b1;
                        float r4 = r2 + r2;
                        float g4 = g2 + g2;
                        float b4 = b2 + b2;
                        if(x < w - 1)
                        {
                            curErrorRed[x+1]   += r4;
                            curErrorGreen[x+1] += g4;
                            curErrorBlue[x+1]  += b4;
                            if(x < w - 2)
                            {

                                curErrorRed[x+2]   += r2;
                                curErrorGreen[x+2] += g2;
                                curErrorBlue[x+2]  += b2;
                            }
                        }
                        if(by+1 < h)
                        {
                            if(x > 0)
                            {
                                nextErrorRed[x-1]   += r2;
                                nextErrorGreen[x-1] += g2;
                                nextErrorBlue[x-1]  += b2;
                                if(x > 1)
                                {
                                    nextErrorRed[x-2]   += r1;
                                    nextErrorGreen[x-2] += g1;
                                    nextErrorBlue[x-2]  += b1;
                                }
                            }
                            nextErrorRed[x]   += r4;
                            nextErrorGreen[x] += g4;
                            nextErrorBlue[x]  += b4;
                            if(x < w - 1)
                            {
                                nextErrorRed[x+1]   += r2;
                                nextErrorGreen[x+1] += g2;
                                nextErrorBlue[x+1]  += b2;
                                if(x < w - 2)
                                {

                                    nextErrorRed[x+2]   += r1;
                                    nextErrorGreen[x+2] += g1;
                                    nextErrorBlue[x+2]  += b1;
                                }
                            }
                        }
                    }
                }

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBurkesDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float r4, r2, r1, g4, g2, g1, b4, b2, b1;
            final float populationBias = palette.populationBias;
            final float s = (0.13f * ditherStrength / (populationBias * populationBias)),
                    strength = s * 0.58f / (0.3f + s);
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            byte paletteIndex;

            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        float er = curErrorRed[px];
                        float eg = curErrorGreen[px];
                        float eb = curErrorBlue[px];
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        int rdiff = (color >>> 24) - (used >>> 24);
                        int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                        int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                        r1 = rdiff * strength;
                        g1 = gdiff * strength;
                        b1 = bdiff * strength;
                        r2 = r1 + r1;
                        g2 = g1 + g1;
                        b2 = b1 + b1;
                        r4 = r2 + r2;
                        g4 = g2 + g2;
                        b4 = b2 + b2;
                        if(px < w - 1)
                        {
                            curErrorRed[px+1]   += r4;
                            curErrorGreen[px+1] += g4;
                            curErrorBlue[px+1]  += b4;
                            if(px < w - 2)
                            {
                                curErrorRed[px+2]   += r2;
                                curErrorGreen[px+2] += g2;
                                curErrorBlue[px+2]  += b2;
                            }
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                nextErrorRed[px-1]   += r2;
                                nextErrorGreen[px-1] += g2;
                                nextErrorBlue[px-1]  += b2;
                                if(px > 1)
                                {
                                    nextErrorRed[px-2]   += r1;
                                    nextErrorGreen[px-2] += g1;
                                    nextErrorBlue[px-2]  += b1;
                                }
                            }
                            nextErrorRed[px]   += r4;
                            nextErrorGreen[px] += g4;
                            nextErrorBlue[px]  += b4;
                            if(px < w - 1)
                            {
                                nextErrorRed[px+1]   += r2;
                                nextErrorGreen[px+1] += g2;
                                nextErrorBlue[px+1]  += b2;
                                if(px < w - 2)
                                {
                                    nextErrorRed[px+2]   += r1;
                                    nextErrorGreen[px+2] += g1;
                                    nextErrorBlue[px+2]  += b1;
                                }
                            }
                        }
                    }
                }
                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeOceanicDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        final float[] noise = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float r4, r2, r1, g4, g2, g1, b4, b2, b1;
            final float populationBias = palette.populationBias;
            final float s = 0.175f * ditherStrength / (populationBias * populationBias * populationBias),
                    strength = s * 0.59f / (0.4f + s);
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            byte paletteIndex;

            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        float er = curErrorRed[px];
                        float eg = curErrorGreen[px];
                        float eb = curErrorBlue[px];
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        int rdiff = (color >>> 24) - (used >>> 24);
                        int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                        int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                        r1 = rdiff * strength;
                        g1 = gdiff * strength;
                        b1 = bdiff * strength;
                        r2 = r1 + r1;
                        g2 = g1 + g1;
                        b2 = b1 + b1;
                        r4 = r2 + r2;
                        g4 = g2 + g2;
                        b4 = b2 + b2;
                        float modifier;
                        if(px < w - 1)
                        {
                            modifier = noise[(px + 1 & 63) | ((py << 6) & 0xFC0)];
                            curErrorRed[px+1]   += r4 * modifier;
                            curErrorGreen[px+1] += g4 * modifier;
                            curErrorBlue[px+1]  += b4 * modifier;
                            if(px < w - 2)
                            {
                                modifier = noise[(px + 2 & 63) | ((py << 6) & 0xFC0)];
                                curErrorRed[px+2]   += r2 * modifier;
                                curErrorGreen[px+2] += g2 * modifier;
                                curErrorBlue[px+2]  += b2 * modifier;
                            }
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                modifier = noise[(px - 1 & 63) | ((ny << 6) & 0xFC0)];
                                nextErrorRed[px-1]   += r2 * modifier;
                                nextErrorGreen[px-1] += g2 * modifier;
                                nextErrorBlue[px-1]  += b2 * modifier;
                                if(px > 1)
                                {
                                    modifier = noise[(px - 2 & 63) | ((ny << 6) & 0xFC0)];
                                    nextErrorRed[px-2]   += r1 * modifier;
                                    nextErrorGreen[px-2] += g1 * modifier;
                                    nextErrorBlue[px-2]  += b1 * modifier;
                                }
                            }
                            modifier = noise[(px & 63) | ((ny << 6) & 0xFC0)];
                            nextErrorRed[px]   += r4 * modifier;
                            nextErrorGreen[px] += g4 * modifier;
                            nextErrorBlue[px]  += b4 * modifier;
                            if(px < w - 1)
                            {
                                modifier = noise[(px + 1 & 63) | ((ny << 6) & 0xFC0)];
                                nextErrorRed[px+1]   += r2 * modifier;
                                nextErrorGreen[px+1] += g2 * modifier;
                                nextErrorBlue[px+1]  += b2 * modifier;
                                if(px < w - 2)
                                {
                                    modifier = noise[(px + 2 & 63) | ((ny << 6) & 0xFC0)];
                                    nextErrorRed[px+2]   += r1 * modifier;
                                    nextErrorGreen[px+2] += g1 * modifier;
                                    nextErrorBlue[px+2]  += b1 * modifier;
                                }
                            }
                        }
                    }
                }
                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeSeasideDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        final float[] noiseA = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS;
        final float[] noiseB = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_B;
        final float[] noiseC = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_C;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            final float populationBias = palette.populationBias;
            final float s = 0.15f * populationBias * ditherStrength,
                    strength = s * 0.6f / (0.35f + s);
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            int color, used;
            byte paletteIndex;

            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            for (int y = 0; y < h; y++) {
                System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);

                int py = flipY ? (h - y - 1) : y,
                        ny = y + 1;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        float er = curErrorRed[px];
                        float eg = curErrorGreen[px];
                        float eb = curErrorBlue[px];
                        int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                        int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                        int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        int rdiff = (color >>> 24) - (used >>> 24);
                        int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                        int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                        int modifier = ((px & 63) | (py << 6 & 0xFC0));
                        final float r1 = rdiff * strength * noiseA[modifier];
                        final float g1 = gdiff * strength * noiseB[modifier];
                        final float b1 = bdiff * strength * noiseC[modifier];
                        final float r2 = r1 + r1;
                        final float g2 = g1 + g1;
                        final float b2 = b1 + b1;
                        final float r4 = r2 + r2;
                        final float g4 = g2 + g2;
                        final float b4 = b2 + b2;

                        if(px < w - 1)
                        {
                            modifier = ((px + 1 & 63) | (py << 6 & 0xFC0));
                            curErrorRed[px+1]   += r4 * noiseA[modifier];
                            curErrorGreen[px+1] += g4 * noiseB[modifier];
                            curErrorBlue[px+1]  += b4 * noiseC[modifier];
                            if(px < w - 2)
                            {
                                modifier = ((px + 2 & 63) | ((py << 6) & 0xFC0));
                                curErrorRed[px+2]   += r2 * noiseA[modifier];
                                curErrorGreen[px+2] += g2 * noiseB[modifier];
                                curErrorBlue[px+2]  += b2 * noiseC[modifier];
                            }
                        }
                        if(ny < h)
                        {
                            if(px > 0)
                            {
                                modifier = (px - 1 & 63) | ((ny << 6) & 0xFC0);
                                nextErrorRed[px-1]   += r2 * noiseA[modifier];
                                nextErrorGreen[px-1] += g2 * noiseB[modifier];
                                nextErrorBlue[px-1]  += b2 * noiseC[modifier];
                                if(px > 1)
                                {
                                    modifier = (px - 2 & 63) | ((ny << 6) & 0xFC0);
                                    nextErrorRed[px-2]   += r1 * noiseA[modifier];
                                    nextErrorGreen[px-2] += g1 * noiseB[modifier];
                                    nextErrorBlue[px-2]  += b1 * noiseC[modifier];
                                }
                            }
                            modifier = (px & 63) | ((ny << 6) & 0xFC0);
                            nextErrorRed[px]   += r4 * noiseA[modifier];
                            nextErrorGreen[px] += g4 * noiseB[modifier];
                            nextErrorBlue[px]  += b4 * noiseC[modifier];
                            if(px < w - 1)
                            {
                                modifier = (px + 1 & 63) | ((ny << 6) & 0xFC0);
                                nextErrorRed[px+1]   += r2 * noiseA[modifier];
                                nextErrorGreen[px+1] += g2 * noiseB[modifier];
                                nextErrorBlue[px+1]  += b2 * noiseC[modifier];
                                if(px < w - 2)
                                {
                                    modifier = (px + 2 & 63) | ((ny << 6) & 0xFC0);
                                    nextErrorRed[px+2]   += r1 * noiseA[modifier];
                                    nextErrorGreen[px+2] += g1 * noiseB[modifier];
                                    nextErrorBlue[px+2]  += b1 * noiseC[modifier];
                                }
                            }
                        }
                    }
                }
                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeMartenDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);
            buffer.writeInt(pixmap.getWidth());
            buffer.writeInt(pixmap.getHeight());
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p>>>24);
                buffer.write(p>>>16);
                buffer.write(p>>>8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if(paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(IDAT);
            deflater.reset();

            final int w = pixmap.getWidth(), h = pixmap.getHeight();
//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            if (curLineBytes == null) {
                curLine = (curLineBytes = new ByteArray(w)).items;
            } else {
                curLine = curLineBytes.ensureCapacity(w);
            }

            final float populationBias = palette.populationBias;
            final float str = Math.min(1100f * (ditherStrength / (float) Math.sqrt(palette.colorCount) * (1f / (populationBias * populationBias * populationBias) - 0.7f)), 127f);
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    int color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        // We get a sub-random value from 0-1 using interleaved gradient noise.
                        // Offsetting this value by different values and feeding into triangleWave()
                        // gives 3 different values for r, g, and b, without much bias toward high or low values.
                        // There is correlation between r, g, and b in certain patterns.
                        final float theta = ((px * 142 + y * 79 & 255) * 0x1p-8f);
                        int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str)] & 255;
                        int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.382f) * str)] & 255;
                        int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.618f) * str)] & 255;
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];

                    }
                }

//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, w);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    /**
     * Writes the given Pixmaps to the requested FileHandle at 30 frames per second.
     * If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from all the frames given. Otherwise, this uses the colors already in {@link #palette}.
     * Uses {@link #getDitherAlgorithm()} to determine how to dither.
     *
     * @param file   a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param frames a Pixmap Array to write as a sequence of frames to the given output stream
     */
    @Override
    public void write(FileHandle file, Array<Pixmap> frames) {
        write(file, frames, 30, true);
    }

    /**
     * Writes the given Pixmaps to the requested FileHandle at the requested frames per second.
     * If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from all of the frames given. Otherwise, this uses the colors already in {@link #palette}.
     * Uses {@link #getDitherAlgorithm()} to determine how to dither.
     *
     * @param file   a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param frames a Pixmap Array to write as a sequence of frames to the given output stream
     * @param fps    how many frames per second the animation should run at
     */
    @Override
    public void write(FileHandle file, Array<Pixmap> frames, int fps) {
        write(file, frames, fps, true);
    }

    /**
     * Writes the Pixmaps to the stream without closing the stream, optionally computing an 8-bit palette from the given
     * Pixmap. If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from all of the frames given. Otherwise, this uses the colors already in {@link #palette}.
     * Optionally dithers the result if {@code dither} is true, using the dither algorithm selected with
     * {@link #setDitherAlgorithm(DitherAlgorithm)} (or {@link DitherAlgorithm#PATTERN} if not set).
     *
     * @param file   a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param frames a Pixmap Array to write as a sequence of frames to the given output stream
     * @param fps    how many frames per second the animation should run at
     * @param dither true if this should use {@link #getDitherAlgorithm()} to dither; false to not dither
     */
    public void write(FileHandle file, Array<Pixmap> frames, int fps, boolean dither) {
        OutputStream output = file.write(false);
        try {
            write(output, frames, fps, dither);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * Writes the Pixmaps to the stream without closing the stream, optionally computing an 8-bit palette from the given
     * Pixmaps. If {@link #palette} is null (the default unless it has been assigned a PaletteReducer value), this will
     * compute a palette from all of the frames given. Otherwise, this uses the colors already in {@link #palette}.
     * Optionally dithers the result if {@code dither} is true, using the dither algorithm selected with
     * {@link #setDitherAlgorithm(DitherAlgorithm)} (or {@link DitherAlgorithm#PATTERN} if not set). This does not
     * consider the ditherStrength set in the palette, if non-null, but does use the {@link #getDitherStrength()} here.
     *
     * @param output an OutputStream that will not be closed
     * @param frames a Pixmap Array to write as a sequence of frames to the given output stream
     * @param fps    how many frames per second the animation should run at
     * @param dither true if this should use {@link #getDitherAlgorithm()} to dither; false to not dither
     */
    public void write(OutputStream output, Array<Pixmap> frames, int fps, boolean dither) {
        boolean clearPalette;
        if(clearPalette = (palette == null))
            palette = new PaletteReducer(frames);
        palette.setDitherStrength(ditherStrength);
        if (dither)
            write(output, frames, fps);
        else
            writeSolid(output, frames, fps);
        if(clearPalette) palette = null;
    }

    public void writeSolid(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
        dataOutput.write(SIGNATURE);

        final int width = pixmap.getWidth();
        final int height = pixmap.getHeight();

        buffer.writeInt(IHDR);
        buffer.writeInt(width);
        buffer.writeInt(height);
        buffer.writeByte(8); // 8 bits per component.
        buffer.writeByte(COLOR_INDEXED);
        buffer.writeByte(COMPRESSION_DEFLATE);
        buffer.writeByte(FILTER_NONE);
        buffer.writeByte(INTERLACE_NONE);
        buffer.endChunk(dataOutput);

        buffer.writeInt(PLTE);
        for (int i = 0; i < paletteArray.length; i++) {
            int p = paletteArray[i];
            buffer.write(p >>> 24);
            buffer.write(p >>> 16);
            buffer.write(p >>> 8);
        }
        buffer.endChunk(dataOutput);

        boolean hasTransparent = false;
        if (paletteArray[0] == 0) {
            hasTransparent = true;
            buffer.writeInt(TRNS);
            buffer.write(0);
            buffer.endChunk(dataOutput);
        }
        buffer.writeInt(acTL);
        buffer.writeInt(frames.size);
        buffer.writeInt(0);
        buffer.endChunk(dataOutput);

//        byte[] lineOut, curLine, prevLine;
        byte[] curLine;
            int color;
        int seq = 0;
        for (int i = 0; i < frames.size; i++) {

            buffer.writeInt(fcTL);
            buffer.writeInt(seq++);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeInt(0);
            buffer.writeInt(0);
            buffer.writeShort(1);
            buffer.writeShort(fps);
            buffer.writeByte(0);
            buffer.writeByte(0);
            buffer.endChunk(dataOutput);

            if (i == 0) {
                buffer.writeInt(IDAT);
            } else {
                pixmap = frames.get(i);
                buffer.writeInt(fdAT);
                buffer.writeInt(seq++);
            }
            deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }

            for (int y = 0; y < height; y++) {
                int py = flipY ? (height - y - 1) : y;
                for (int px = 0; px < width; px++) {
                    color = pixmap.getPixel(px, py);
                    if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                        curLine[px] = 0;
                    else {
                        int rr = ((color >>> 24));
                        int gg = ((color >>> 16) & 0xFF);
                        int bb = ((color >>> 8) & 0xFF);
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];
                    }
                }

//                lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                //Paeth
//                for (int x = 1; x < width; x++) {
//                    int a = curLine[x - 1] & 0xff;
//                    int b = prevLine[x] & 0xff;
//                    int c = prevLine[x - 1] & 0xff;
//                    int p = a + b - c;
//                    int pa = p - a;
//                    if (pa < 0) pa = -pa;
//                    int pb = p - b;
//                    if (pb < 0) pb = -pb;
//                    int pc = p - c;
//                    if (pc < 0) pc = -pc;
//                    if (pa <= pb && pa <= pc)
//                        c = a;
//                    else if (pb <= pc)
//                        c = b;
//                    lineOut[x] = (byte) (curLine[x] - c);
//                }
//
//                deflaterOutput.write(FILTER_PAETH);
//                deflaterOutput.write(lineOut, 0, width);

                deflaterOutput.write(FILTER_NONE);
                deflaterOutput.write(curLine, 0, width);
            }
            deflaterOutput.finish();
            buffer.endChunk(dataOutput);
        }

        buffer.writeInt(IEND);
        buffer.endChunk(dataOutput);

        output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    @Override
    public void write(OutputStream output, Array<Pixmap> frames, int fps) {
        if(ditherAlgorithm == null) {
            writeSolid(output, frames, fps);
            return;
        }
        switch (ditherAlgorithm){
            case NONE:
                writeSolid(output, frames, fps);
                break;
            case GRADIENT_NOISE:
                writeGradientDithered(output, frames, fps);
                break;
            case ROBERTS:
                writeRobertsDithered(output, frames, fps);
                break;
            case PATTERN:
                writePatternDithered(output, frames, fps);
                break;
            case CHAOTIC_NOISE:
                writeChaoticNoiseDithered(output, frames, fps);
                break;
            case DIFFUSION:
                writeDiffusionDithered(output, frames, fps);
                break;
            case SCATTER:
                writeScatterDithered(output, frames, fps);
                break;
            case BLUE_NOISE:
                writeBlueNoiseDithered(output, frames, fps);
                break;
            case BLUNT:
                writeBluntDithered(output, frames, fps);
                break;
            case BANTER:
                writeBanterDithered(output, frames, fps);
                break;
            case WOVEN:
                writeWovenDithered(output, frames, fps);
                break;
            case DODGY:
                writeDodgyDithered(output, frames, fps);
                break;
            case LOAF:
                writeLoafDithered(output, frames, fps);
                break;
            case NEUE:
                writeNeueDithered(output, frames, fps);
                break;
            case BURKES:
                writeBurkesDithered(output, frames, fps);
                break;
            case OCEANIC:
                writeOceanicDithered(output, frames, fps);
                break;
            case SEASIDE:
                writeSeasideDithered(output, frames, fps);
                break;
            case GOURD:
                writeGourdDithered(output, frames, fps);
                break;
            case OVERBOARD:
                writeOverboardDithered(output, frames, fps);
                break;
            case MARTEN:
                writeMartenDithered(output, frames, fps);
                break;
            case WREN:
            default:
                writeWrenDithered(output, frames, fps);
                break;
        }
    }

    public void writeGradientDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            int color;

            final float populationBias = palette.populationBias;
            final float strength = 0.9f * (float) Math.tanh(0.16f * ditherStrength * Math.pow(populationBias, -7.00));

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + ((142 * (px + 0x5F) + 79 * (y - 0x96) & 255) - 127.5f) * strength)] & 255;
                            int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + ((142 * (px + 0xFA) + 79 * (y - 0xA3) & 255) - 127.5f) * strength)] & 255;
                            int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + ((142 * (px + 0xA5) + 79 * (y - 0xC9) & 255) - 127.5f) * strength)] & 255;
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < width; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, width);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeRobertsDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            int color;

            final float populationBias = palette.populationBias;
            final float str = Math.min(48 * ditherStrength / (populationBias * populationBias * populationBias * populationBias), 127);

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            // We get a sub-random value from 0-1 using the R2 sequence.
                            // Offsetting this value by different values and feeding into triangleWave()
                            // gives 3 different values for r, g, and b, without much bias toward high or low values.
                            // There is correlation between r, g, and b in certain patterns.
                            final float theta = ((px * 0xC13FA9A9 + y * 0x91E10DA5 >>> 9) * 0x1p-23f);
                            int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str)] & 255;
                            int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.209f) * str)] & 255;
                            int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.518f) * str)] & 255;
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < width; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, width);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeLoafDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;
            int color;

            final float strength = Math.min(Math.max(2.5f + 5f * ditherStrength - 5.5f * palette.populationBias, 0f), 7.9f);

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            int adj = (int)((((px + y & 1) << 5) - 16) * strength); // either + 16 * strength or - 16 * strength
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + adj, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + adj, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + adj, 0), 1023)] & 255;
                            int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                            curLine[px] = paletteMapping[rgb555];
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeGourdDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;
            int color;

            final float strength = (float)(ditherStrength * 0.7 * Math.pow(palette.populationBias, -5.50));
            for (int i = 0; i < 64; i++) {
                PaletteReducer.tempThresholdMatrix[i] = Math.min(Math.max((PaletteReducer.thresholdMatrix64[i] - 31.5f) * strength, -127), 127);
            }
            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();


                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(width)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(width);
                }

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            float adj = PaletteReducer.tempThresholdMatrix[(px & 7) | (y & 7) << 3];
                            int rr = PaletteReducer.fromLinearLUT[(int)(PaletteReducer.toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                            int gg = PaletteReducer.fromLinearLUT[(int)(PaletteReducer.toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                            int bb = PaletteReducer.fromLinearLUT[(int)(PaletteReducer.toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3))];
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBlueNoiseDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;

            final float strength = 0.21875f * ditherStrength / (palette.populationBias * palette.populationBias);

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }
                for (int oy = 0; oy < height; oy++) {
                    int y = flipY ? (height - oy - 1) : oy;
                    for (int x = 0; x < width; x++) {
                        int color = pixmap.getPixel(x, y);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[x] = 0;
                        else {
                            float adj = Math.min(Math.max(((TRI_BLUE_NOISE  [(x & 63) | (y & 63) << 6] + ((x + y & 1) << 8) - 127.5f) * strength), -100.5f), 101.5f);
                            int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                            int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                            int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;

                            curLine[x] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < width; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, width);
                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBluntDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            final float populationBias = palette.populationBias;
            final float strength = Math.min(Math.max(0.35f * ditherStrength / (populationBias * populationBias * populationBias), -0.6f), 0.6f);

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }
                for (int oy = 0; oy < height; oy++) {
                    int y = flipY ? (height - oy - 1) : oy;
                    for (int x = 0; x < width; x++) {
                        int color = pixmap.getPixel(x, y);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[x] = 0;
                        else {
                            float adj = (x+y<<7&128)-63.5f;
                            int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + (TRI_BLUE_NOISE  [(x + 62 & 63) << 6 | (y + 66  & 63)] + adj) * strength)] & 255;
                            int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + (TRI_BLUE_NOISE_B[(x + 31 & 63) << 6 | (y + 113 & 63)] + adj) * strength)] & 255;
                            int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + (TRI_BLUE_NOISE_C[(x + 71 & 63) << 6 | (y + 41  & 63)] + adj) * strength)] & 255;

                            curLine[x] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < width; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, width);
                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBanterDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            float strength = Math.min(Math.max(0.17f * ditherStrength * (float) Math.pow(palette.populationBias, -10f), -0.95f), 0.95f);

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(width)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(width);
                }
                for (int oy = 0; oy < height; oy++) {
                    int y = flipY ? (height - oy - 1) : oy;
                    for (int x = 0; x < width; x++) {
                        int color = pixmap.getPixel(x, y);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[x] = 0;
                        else {
                            float adj = TRI_BAYER_MATRIX_128[(x & TBM_MASK) << TBM_BITS | (y & TBM_MASK)] * strength;
                            int rr = fromLinearLUT[(int) (toLinearLUT[(color >>> 24)] + adj)] & 255;
                            int gg = fromLinearLUT[(int) (toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                            int bb = fromLinearLUT[(int) (toLinearLUT[(color >>> 8) & 0xFF] + adj)] & 255;

                            curLine[x] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < width; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, width);
                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeChaoticNoiseDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;
            int color, used;

            byte paletteIndex;
            double adj, strength = ditherStrength * palette.populationBias * 1.5;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }
                long s = 0xC13FA9A902A6328FL * seq;
                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            int rr = ((color >>> 24)       );
                            int gg = ((color >>> 16) & 0xFF);
                            int bb = ((color >>> 8)  & 0xFF);
                            paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
                            adj *= adj * adj;
                            // Complicated... This starts with a checkerboard of -0.5 and 0.5, times a tiny fraction.
                            // The next 3 lines generate 3 low-quality-random numbers based on s, which should be
                            //   different as long as the colors encountered so far were different. The numbers can
                            //   each be positive or negative, and are reduced to a manageable size, summed, and
                            //   multiplied by the earlier tiny fraction. Summing 3 random values gives us a curved
                            //   distribution, centered on about 0.0 and weighted so most results are close to 0.
                            //   Two of the random numbers use an XLCG, and the last uses an LCG.
                            adj += ((px + y & 1) - 0.5f) * 0x1.8p-49 * strength *
                                    (((s ^ 0x9E3779B97F4A7C15L) * 0xC6BC279692B5CC83L >> 15) +
                                            ((~s ^ 0xDB4F0B9175AE2165L) * 0xD1B54A32D192ED03L >> 15) +
                                            ((s = (s ^ rr + gg + bb) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                            rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                            gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                            bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < width; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, width);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeDiffusionDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;

            int color, used;
            float rdiff, gdiff, bdiff;
            byte paletteIndex;
            float w1 = ditherStrength * 32 / palette.populationBias, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

                for (int y = 0; y < h; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipY ? (h - y - 1) : y,
                            ny = y + 1;
                    for (int px = 0; px < w; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + curErrorRed[px]  , 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + curErrorGreen[px], 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + curErrorBlue[px] , 0), 1023)] & 255;
                            curLine[px] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = Math.min(Math.max(0x1p-8f * ((color>>>24)-    (used>>>24))    , -1), 1);
                            gdiff = Math.min(Math.max(0x1p-8f * ((color>>>16&255)-(used>>>16&255)), -1), 1);
                            bdiff = Math.min(Math.max(0x1p-8f * ((color>>>8&255)- (used>>>8&255)) , -1), 1);
                            // this alternate code used a sigmoid function to smoothly limit error.
//                    rdiff = (0x1.8p-8f * ((color>>>24)-    (used>>>24))    );
//                    gdiff = (0x1.8p-8f * ((color>>>16&255)-(used>>>16&255)));
//                    bdiff = (0x1.8p-8f * ((color>>>8&255)- (used>>>8&255)) );
//                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
//                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
//                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                            if(px < w - 1)
                            {
                                curErrorRed[px+1]   += rdiff * w7;
                                curErrorGreen[px+1] += gdiff * w7;
                                curErrorBlue[px+1]  += bdiff * w7;
                            }
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    nextErrorRed[px-1]   += rdiff * w3;
                                    nextErrorGreen[px-1] += gdiff * w3;
                                    nextErrorBlue[px-1]  += bdiff * w3;
                                }
                                if(px < w - 1)
                                {
                                    nextErrorRed[px+1]   += rdiff * w1;
                                    nextErrorGreen[px+1] += gdiff * w1;
                                    nextErrorBlue[px+1]  += bdiff * w1;
                                }
                                nextErrorRed[px]   += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px]  += bdiff * w5;
                            }
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writePatternDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;

            int color, used;
            int cr, cg, cb,  usedIndex;
            final float errorMul = ditherStrength * 0.5f / palette.populationBias;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(width)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(width);
        }

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            int er = 0, eg = 0, eb = 0;
                            cr = (color >>> 24);
                            cg = (color >>> 16 & 0xFF);
                            cb = (color >>> 8 & 0xFF);
                            for (int c = 0; c < 16; c++) {
                                int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                                int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                                int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                                usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))] & 0xFF;
                                palette.candidates[c | 16] = shrink(used = paletteArray[palette.candidates[c] = usedIndex]);
                                er += cr - (used >>> 24);
                                eg += cg - (used >>> 16 & 0xFF);
                                eb += cb - (used >>> 8 & 0xFF);
                            }
                            PaletteReducer.sort16(palette.candidates);
                            curLine[px] = (byte)palette.candidates[PaletteReducer.thresholdMatrix16[((px & 3) | (y & 3) << 2)]];
                        }
                    }

//            lineOut[0] = (byte)(curLine[0] - prevLine[0]);
//
//            //Paeth
//            for (int x = 1; x < lineLen; x++) {
//                int a = curLine[x - 1] & 0xff;
//                int b = prevLine[x] & 0xff;
//                int c = prevLine[x - 1] & 0xff;
//                int p = a + b - c;
//                int pa = p - a;
//                if (pa < 0) pa = -pa;
//                int pb = p - b;
//                if (pb < 0) pb = -pb;
//                int pc = p - c;
//                if (pc < 0) pc = -pc;
//                if (pa <= pb && pa <= pc)
//                    c = a;
//                else if (pb <= pc)
//                    c = b;
//                lineOut[x] = (byte)(curLine[x] - c);
//            }
//
//            deflaterOutput.write(FILTER_PAETH);
//            deflaterOutput.write(lineOut, 0, lineLen);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeScatterDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float w1 = Math.min(ditherStrength * 5.5f / (palette.populationBias * palette.populationBias), 16f),
                    w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

                for (int y = 0; y < h; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipY ? (h - y - 1) : y,
                            ny = y + 1;
                    for (int px = 0; px < w; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                            er = curErrorRed[px] * tbn;
                            eg = curErrorGreen[px] * tbn;
                            eb = curErrorBlue[px] * tbn;
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            curLine[px] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (0x2.1p-8f * ((color>>>24)-    (used>>>24))    );
                            gdiff = (0x2.1p-8f * ((color>>>16&255)-(used>>>16&255)));
                            bdiff = (0x2.1p-8f * ((color>>>8&255)- (used>>>8&255)) );
                            rdiff /= (0.125f + Math.abs(rdiff));
                            gdiff /= (0.125f + Math.abs(gdiff));
                            bdiff /= (0.125f + Math.abs(bdiff));
                            if(px < w - 1)
                            {
                                curErrorRed[px+1]   += rdiff * w7;
                                curErrorGreen[px+1] += gdiff * w7;
                                curErrorBlue[px+1]  += bdiff * w7;
                            }
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    nextErrorRed[px-1]   += rdiff * w3;
                                    nextErrorGreen[px-1] += gdiff * w3;
                                    nextErrorBlue[px-1]  += bdiff * w3;
                                }
                                if(px < w - 1)
                                {
                                    nextErrorRed[px+1]   += rdiff * w1;
                                    nextErrorGreen[px+1] += gdiff * w1;
                                    nextErrorBlue[px+1]  += bdiff * w1;
                                }
                                nextErrorRed[px]   += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px]  += bdiff * w5;
                            }
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeNeueDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb, adj;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            final float w1 = ditherStrength * 8f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = (70f * ditherStrength / (populationBias * populationBias * populationBias)),
                    limit = Math.min(127, (float) Math.pow(80, 1.635 - populationBias));

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

                for (int y = 0; y < h; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipY ? (h - y - 1) : y,
                            ny = y + 1;
                    for (int px = 0; px < w; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (py & 63) << 6] + 0.5f) * 0.005f); // plus or minus 255/400
                            adj = Math.min(Math.max(adj * strength, -limit), limit);
                            er = adj + (curErrorRed[px]);
                            eg = adj + (curErrorGreen[px]);
                            eb = adj + (curErrorBlue[px]);
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            curLine[px] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (0x2.Ep-8f * ((color>>>24)-    (used>>>24))    );
                            gdiff = (0x2.Ep-8f * ((color>>>16&255)-(used>>>16&255)));
                            bdiff = (0x2.Ep-8f * ((color>>>8&255)- (used>>>8&255)) );
                            rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                            gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                            bdiff *= 1.25f / (0.25f + Math.abs(bdiff));

                            if(px < w - 1)
                            {
                                curErrorRed[px+1]   += rdiff * w7;
                                curErrorGreen[px+1] += gdiff * w7;
                                curErrorBlue[px+1]  += bdiff * w7;
                            }
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    nextErrorRed[px-1]   += rdiff * w3;
                                    nextErrorGreen[px-1] += gdiff * w3;
                                    nextErrorBlue[px-1]  += bdiff * w3;
                                }
                                if(px < w - 1)
                                {
                                    nextErrorRed[px+1]   += rdiff * w1;
                                    nextErrorGreen[px+1] += gdiff * w1;
                                    nextErrorBlue[px+1]  += bdiff * w1;
                                }
                                nextErrorRed[px]   += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px]  += bdiff * w5;
                            }
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeDodgyDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            float populationBias = palette.populationBias;
            final float w1 = 8f * ditherStrength,
                    w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = 0.35f * ditherStrength / (populationBias * populationBias * populationBias),
                    limit = 90f;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

                for (int y = 0; y < h; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipY ? (h - y - 1) : y,
                            ny = y + 1;
                    for (int px = 0; px < w; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            er = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorRed[px]);
                            eg = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorGreen[px]);
                            eb = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorBlue[px]);

                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            curLine[px] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];

                            rdiff = (0x5p-8f * ((color>>>24)-    (used>>>24))    );
                            gdiff = (0x5p-8f * ((color>>>16&255)-(used>>>16&255)));
                            bdiff = (0x5p-8f * ((color>>>8&255)- (used>>>8&255)) );
                            rdiff /= (0.5f + Math.abs(rdiff));
                            gdiff /= (0.5f + Math.abs(gdiff));
                            bdiff /= (0.5f + Math.abs(bdiff));

                            if(px < w - 1)
                            {
                                curErrorRed[px+1]   += rdiff * w7;
                                curErrorGreen[px+1] += gdiff * w7;
                                curErrorBlue[px+1]  += bdiff * w7;
                            }
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    nextErrorRed[px-1]   += rdiff * w3;
                                    nextErrorGreen[px-1] += gdiff * w3;
                                    nextErrorBlue[px-1]  += bdiff * w3;
                                }
                                if(px < w - 1)
                                {
                                    nextErrorRed[px+1]   += rdiff * w1;
                                    nextErrorGreen[px+1] += gdiff * w1;
                                    nextErrorBlue[px+1]  += bdiff * w1;
                                }
                                nextErrorRed[px]   += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px]  += bdiff * w5;
                            }
                        }
                    }
                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeWovenDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            final float w1 = (float) (10f * Math.sqrt(ditherStrength) / (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = 100f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                    limit = 5f + 250f / (float)Math.sqrt(palette.colorCount+1.5f);

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

                for (int y = 0; y < h; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipY ? (h - y - 1) : y,
                            ny = y + 1;
                    for (int px = 0; px < w; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            er = Math.min(Math.max(((((px+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorRed[px]);
                            eg = Math.min(Math.max(((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorGreen[px]);
                            eb = Math.min(Math.max(((((px+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorBlue[px]);

                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            curLine[px] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (0x5p-10f * ((color>>>24)-    (used>>>24))    );
                            gdiff = (0x5p-10f * ((color>>>16&255)-(used>>>16&255)));
                            bdiff = (0x5p-10f * ((color>>>8&255)- (used>>>8&255)) );
                            if(px < w - 1)
                            {
                                curErrorRed[px+1]   += rdiff * w7;
                                curErrorGreen[px+1] += gdiff * w7;
                                curErrorBlue[px+1]  += bdiff * w7;
                            }
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    nextErrorRed[px-1]   += rdiff * w3;
                                    nextErrorGreen[px-1] += gdiff * w3;
                                    nextErrorBlue[px-1]  += bdiff * w3;
                                }
                                if(px < w - 1)
                                {
                                    nextErrorRed[px+1]   += rdiff * w1;
                                    nextErrorGreen[px+1] += gdiff * w1;
                                    nextErrorBlue[px+1]  += bdiff * w1;
                                }
                                nextErrorRed[px]   += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px]  += bdiff * w5;
                            }
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < w; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, w);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }


    public void writeWrenOriginalDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            final float w1 = (float) (32.0 * ditherStrength * (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                    strength = (0.2f * ditherStrength / (populationBias * populationBias * populationBias * populationBias)),
                    limit = 5f + 125f / (float)Math.sqrt(palette.colorCount+1.5),
                    dmul = 0x1p-8f;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

                for (int y = 0; y < h; y++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int py = flipY ? (h - y - 1) : y,
                            ny = y + 1;
                    for (int px = 0; px < w; px++) {
                        color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+1) * 0xC13FA9A902A6328FL + (y +1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorRed[px]), -limit), limit);
                            eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+3) * 0xC13FA9A902A6328FL + (y -1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorGreen[px]), -limit), limit);
                            eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+2) * 0xC13FA9A902A6328FL + (y -4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorBlue[px]), -limit), limit);

                            int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                            int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                            int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                            curLine[px] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (dmul * ((color>>>24)-    (used>>>24))    );
                            gdiff = (dmul * ((color>>>16&255)-(used>>>16&255)));
                            bdiff = (dmul * ((color>>>8&255)- (used>>>8&255)) );
                            if(px < w - 1)
                            {
                                curErrorRed[px+1]   += rdiff * w7;
                                curErrorGreen[px+1] += gdiff * w7;
                                curErrorBlue[px+1]  += bdiff * w7;
                            }
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    nextErrorRed[px-1]   += rdiff * w3;
                                    nextErrorGreen[px-1] += gdiff * w3;
                                    nextErrorBlue[px-1]  += bdiff * w3;
                                }
                                if(px < w - 1)
                                {
                                    nextErrorRed[px+1]   += rdiff * w1;
                                    nextErrorGreen[px+1] += gdiff * w1;
                                    nextErrorBlue[px+1]  += bdiff * w1;
                                }
                                nextErrorRed[px]   += rdiff * w5;
                                nextErrorGreen[px] += gdiff * w5;
                                nextErrorBlue[px]  += bdiff * w5;
                            }
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeWrenDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            int color, used;
            float rdiff, gdiff, bdiff;
            float er, eg, eb;
            byte paletteIndex;
            final float populationBias = palette.populationBias;
            float partialDitherStrength = (0.5f * ditherStrength / (populationBias * populationBias)),
                    strength = (80f * ditherStrength / (populationBias * populationBias)),
                    blueStrength = (0.3f * ditherStrength / (populationBias * populationBias)),
                    limit = 5f + 200f / (float)Math.sqrt(palette.colorCount+1.5f),
                    r1, g1, b1, r2, g2, b2, r4, g4, b4;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

        if (curLineBytes == null) {
            curLine = (curLineBytes = new ByteArray(w)).items;
        } else {
            curLine = curLineBytes.ensureCapacity(w);
        }

                for (int by = 0; by < h; by++) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    int y = flipY ? (h - by - 1) : by;
                    for (int x = 0; x < w; x++) {
                        color = pixmap.getPixel(x, y);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[x] = 0;
                        else {
                            er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(x & 63) | (by & 63) << 6] + 0.5f) * blueStrength + ((((x+1) * 0xC13FA9A902A6328FL + (by+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorRed[x]);
                            eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (by & 63) << 6] + 0.5f) * blueStrength + ((((x+3) * 0xC13FA9A902A6328FL + (by-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorGreen[x]);
                            eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (by & 63) << 6] + 0.5f) * blueStrength + ((((x+2) * 0xC13FA9A902A6328FL + (by-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorBlue[x]);

                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            curLine[x] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = ((color>>>24)-    (used>>>24))     * partialDitherStrength;
                            gdiff = ((color>>>16&255)-(used>>>16&255)) * partialDitherStrength;
                            bdiff = ((color>>>8&255)- (used>>>8&255))  * partialDitherStrength;

                            r1 = rdiff * 16f / (float)Math.sqrt(2048f + rdiff * rdiff);
                            g1 = gdiff * 16f / (float)Math.sqrt(2048f + gdiff * gdiff);
                            b1 = bdiff * 16f / (float)Math.sqrt(2048f + bdiff * bdiff);
                            r2 = r1 + r1;
                            g2 = g1 + g1;
                            b2 = b1 + b1;
                            r4 = r2 + r2;
                            g4 = g2 + g2;
                            b4 = b2 + b2;
                            if(x < w - 1)
                            {
                                curErrorRed[x+1]   += r4;
                                curErrorGreen[x+1] += g4;
                                curErrorBlue[x+1]  += b4;
                                if(x < w - 2)
                                {

                                    curErrorRed[x+2]   += r2;
                                    curErrorGreen[x+2] += g2;
                                    curErrorBlue[x+2]  += b2;
                                }
                            }
                            if(by+1 < h)
                            {
                                if(x > 0)
                                {
                                    nextErrorRed[x-1]   += r2;
                                    nextErrorGreen[x-1] += g2;
                                    nextErrorBlue[x-1]  += b2;
                                    if(x > 1)
                                    {
                                        nextErrorRed[x-2]   += r1;
                                        nextErrorGreen[x-2] += g1;
                                        nextErrorBlue[x-2]  += b1;
                                    }
                                }
                                nextErrorRed[x]   += r4;
                                nextErrorGreen[x] += g4;
                                nextErrorBlue[x]  += b4;
                                if(x < w - 1)
                                {
                                    nextErrorRed[x+1]   += r2;
                                    nextErrorGreen[x+1] += g2;
                                    nextErrorBlue[x+1]  += b2;
                                    if(x < w - 2)
                                    {

                                        nextErrorRed[x+2]   += r1;
                                        nextErrorGreen[x+2] += g1;
                                        nextErrorBlue[x+2]  += b1;
                                    }
                                }
                            }
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeOverboardDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            final int flipDir = flipY ? -1 : 1;
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            final float populationBias = palette.populationBias;
            final float strength = ditherStrength * 1.5f * (populationBias * populationBias),
                    noiseStrength = 4f / (populationBias * populationBias),
                    limit = 110f;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(w)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(w);
                }

                for (int by = 0, y = flipY ? h - 1 : 0; by < h; by++, y += flipDir) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    for (int x = 0; x < w; x++) {
                        int color = pixmap.getPixel(x, y);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[x] = 0;
                        else {
                            float er = 0f, eg = 0f, eb = 0f;
                            switch ((x << 1 & 2) | (y & 1)) {
                                case 0:
                                    er += ((x ^ y) % 9 - 4);
                                    er += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                    eg += (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                                    eg += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                    eb += (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                                    eb += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                                    break;
                                case 1:
                                    er += (PaletteReducer.TRI_BLUE_NOISE[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                                    er += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                    eg += (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                                    eg += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                                    eb += ((x ^ y) % 11 - 5);
                                    eb += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                                    break;
                                case 2:
                                    er += (PaletteReducer.TRI_BLUE_NOISE[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-6f;
                                    er += ((y * 0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-20f;
                                    eg += ((x ^ y) % 11 - 5);
                                    eg += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                                    eb += ((x ^ y) % 9 - 4);
                                    eb += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                    break;
                                default: // case 3:
                                    er += ((x ^ y) % 11 - 5);
                                    er += ((y * -0xC13FA9A902A6328FL + x * -0x91E10DA5C79E7B1DL) >> 41) * 0x1.8p-21f;
                                    eg += ((x ^ y) % 9 - 4);
                                    eg += ((x * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                    eb += (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * 0x1p-5f;
                                    eb += ((x * -0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL) >> 41) * 0x1p-20f;
                                    break;
                            }
                            er = er * noiseStrength + curErrorRed[x];
                            eg = eg * noiseStrength + curErrorGreen[x];
                            eb = eb * noiseStrength + curErrorBlue[x];
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + Math.min(Math.max(er, -limit), limit), 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + Math.min(Math.max(eg, -limit), limit), 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + Math.min(Math.max(eb, -limit), limit), 0), 1023)] & 255;
                            byte paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            curLine[x] = paletteIndex;
                            int used = paletteArray[paletteIndex & 0xFF];
                            float rdiff = ((color >>> 24) - (used >>> 24)) * strength;
                            float gdiff = ((color >>> 16 & 255) - (used >>> 16 & 255)) * strength;
                            float bdiff = ((color >>> 8 & 255) - (used >>> 8 & 255)) * strength;
                            float r1 = rdiff * 16f / (45f + Math.abs(rdiff));
                            float g1 = gdiff * 16f / (45f + Math.abs(gdiff));
                            float b1 = bdiff * 16f / (45f + Math.abs(bdiff));
                            float r2 = r1 + r1;
                            float g2 = g1 + g1;
                            float b2 = b1 + b1;
                            float r4 = r2 + r2;
                            float g4 = g2 + g2;
                            float b4 = b2 + b2;
                            if (x < w - 1) {
                                curErrorRed[x + 1] += r4;
                                curErrorGreen[x + 1] += g4;
                                curErrorBlue[x + 1] += b4;
                                if (x < w - 2) {

                                    curErrorRed[x + 2] += r2;
                                    curErrorGreen[x + 2] += g2;
                                    curErrorBlue[x + 2] += b2;
                                }
                            }
                            if (by + 1 < h) {
                                if (x > 0) {
                                    nextErrorRed[x - 1] += r2;
                                    nextErrorGreen[x - 1] += g2;
                                    nextErrorBlue[x - 1] += b2;
                                    if (x > 1) {
                                        nextErrorRed[x - 2] += r1;
                                        nextErrorGreen[x - 2] += g1;
                                        nextErrorBlue[x - 2] += b1;
                                    }
                                }
                                nextErrorRed[x] += r4;
                                nextErrorGreen[x] += g4;
                                nextErrorBlue[x] += b4;
                                if (x < w - 1) {
                                    nextErrorRed[x + 1] += r2;
                                    nextErrorGreen[x + 1] += g2;
                                    nextErrorBlue[x + 1] += b2;
                                    if (x < w - 2) {

                                        nextErrorRed[x + 2] += r1;
                                        nextErrorGreen[x + 2] += g1;
                                        nextErrorBlue[x + 2] += b1;
                                    }
                                }
                            }
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeBurkesDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            final int flipDir = flipY ? -1 : 1;
            float r4, r2, r1, g4, g2, g1, b4, b2, b1;
            final float populationBias = palette.populationBias;
            final float s = (0.13f * ditherStrength / (populationBias * populationBias)),
                    strength = s * 0.58f / (0.3f + s);
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(w)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(w);
                }

                for (int by = 0, py = flipY ? h - 1 : 0; by < h; by++, py += flipDir) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    for (int px = 0; px < w; px++) {
                        int color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            float er = curErrorRed[px];
                            float eg = curErrorGreen[px];
                            float eb = curErrorBlue[px];
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            byte paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            curLine[px] = paletteIndex;
                            int used = paletteArray[paletteIndex & 0xFF];
                            int rdiff = (color >>> 24) - (used >>> 24);
                            int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                            int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                            r1 = rdiff * strength;
                            g1 = gdiff * strength;
                            b1 = bdiff * strength;
                            r2 = r1 + r1;
                            g2 = g1 + g1;
                            b2 = b1 + b1;
                            r4 = r2 + r2;
                            g4 = g2 + g2;
                            b4 = b2 + b2;
                            if (px < w - 1) {
                                curErrorRed[px + 1] += r4;
                                curErrorGreen[px + 1] += g4;
                                curErrorBlue[px + 1] += b4;
                                if (px < w - 2) {

                                    curErrorRed[px + 2] += r2;
                                    curErrorGreen[px + 2] += g2;
                                    curErrorBlue[px + 2] += b2;
                                }
                            }
                            if (by + 1 < h) {
                                if (px > 0) {
                                    nextErrorRed[px - 1] += r2;
                                    nextErrorGreen[px - 1] += g2;
                                    nextErrorBlue[px - 1] += b2;
                                    if (px > 1) {
                                        nextErrorRed[px - 2] += r1;
                                        nextErrorGreen[px - 2] += g1;
                                        nextErrorBlue[px - 2] += b1;
                                    }
                                }
                                nextErrorRed[px] += r4;
                                nextErrorGreen[px] += g4;
                                nextErrorBlue[px] += b4;
                                if (px < w - 1) {
                                    nextErrorRed[px + 1] += r2;
                                    nextErrorGreen[px + 1] += g2;
                                    nextErrorBlue[px + 1] += b2;
                                    if (px < w - 2) {

                                        nextErrorRed[px + 2] += r1;
                                        nextErrorGreen[px + 2] += g1;
                                        nextErrorBlue[px + 2] += b1;
                                    }
                                }
                            }
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeOceanicDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        final float[] noise = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            final int flipDir = flipY ? -1 : 1;
            float r4, r2, r1, g4, g2, g1, b4, b2, b1;
            final float populationBias = palette.populationBias;
            final float s = (0.13f * ditherStrength / (populationBias * populationBias)),
                    strength = s * 0.58f / (0.3f + s);
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(w)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(w);
                }

                for (int by = 0, py = flipY ? h - 1 : 0; by < h; by++, py += flipDir) {
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    for (int px = 0; px < w; px++) {
                        int color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            float er = curErrorRed[px];
                            float eg = curErrorGreen[px];
                            float eb = curErrorBlue[px];
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            byte paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            curLine[px] = paletteIndex;
                            int used = paletteArray[paletteIndex & 0xFF];
                            int rdiff = (color >>> 24) - (used >>> 24);
                            int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                            int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                            r1 = rdiff * strength;
                            g1 = gdiff * strength;
                            b1 = bdiff * strength;
                            r2 = r1 + r1;
                            g2 = g1 + g1;
                            b2 = b1 + b1;
                            r4 = r2 + r2;
                            g4 = g2 + g2;
                            b4 = b2 + b2;
                            float modifier;
                            if(px < w - 1)
                            {
                                modifier = noise[(px + 1 & 63) | ((py << 6) & 0xFC0)];
                                curErrorRed[px+1]   += r4 * modifier;
                                curErrorGreen[px+1] += g4 * modifier;
                                curErrorBlue[px+1]  += b4 * modifier;
                                if(px < w - 2)
                                {
                                    modifier = noise[(px + 2 & 63) | ((py << 6) & 0xFC0)];
                                    curErrorRed[px+2]   += r2 * modifier;
                                    curErrorGreen[px+2] += g2 * modifier;
                                    curErrorBlue[px+2]  += b2 * modifier;
                                }
                            }
                            final int ny = by + 1;
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    modifier = noise[(px - 1 & 63) | ((ny << 6) & 0xFC0)];
                                    nextErrorRed[px-1]   += r2 * modifier;
                                    nextErrorGreen[px-1] += g2 * modifier;
                                    nextErrorBlue[px-1]  += b2 * modifier;
                                    if(px > 1)
                                    {
                                        modifier = noise[(px - 2 & 63) | ((ny << 6) & 0xFC0)];
                                        nextErrorRed[px-2]   += r1 * modifier;
                                        nextErrorGreen[px-2] += g1 * modifier;
                                        nextErrorBlue[px-2]  += b1 * modifier;
                                    }
                                }
                                modifier = noise[(px & 63) | ((ny << 6) & 0xFC0)];
                                nextErrorRed[px]   += r4 * modifier;
                                nextErrorGreen[px] += g4 * modifier;
                                nextErrorBlue[px]  += b4 * modifier;
                                if(px < w - 1)
                                {
                                    modifier = noise[(px + 1 & 63) | ((ny << 6) & 0xFC0)];
                                    nextErrorRed[px+1]   += r2 * modifier;
                                    nextErrorGreen[px+1] += g2 * modifier;
                                    nextErrorBlue[px+1]  += b2 * modifier;
                                    if(px < w - 2)
                                    {
                                        modifier = noise[(px + 2 & 63) | ((ny << 6) & 0xFC0)];
                                        nextErrorRed[px+2]   += r1 * modifier;
                                        nextErrorGreen[px+2] += g1 * modifier;
                                        nextErrorBlue[px+2]  += b1 * modifier;
                                    }
                                }
                            }
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeSeasideDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        final float[] noiseA = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS;
        final float[] noiseB = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_B;
        final float[] noiseC = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_C;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            final int flipDir = flipY ? -1 : 1;
            final float populationBias = palette.populationBias;
            final float s = 0.15f * populationBias * ditherStrength,
                    strength = s * 0.6f / (0.35f + s);
            float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedFloats == null) {
                curErrorRed = (palette.curErrorRedFloats = new FloatArray(w)).items;
                nextErrorRed = (palette.nextErrorRedFloats = new FloatArray(w)).items;
                curErrorGreen = (palette.curErrorGreenFloats = new FloatArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenFloats = new FloatArray(w)).items;
                curErrorBlue = (palette.curErrorBlueFloats = new FloatArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueFloats = new FloatArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedFloats.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedFloats.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenFloats.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenFloats.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueFloats.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueFloats.ensureCapacity(w);
                Arrays.fill(nextErrorRed, 0, w, 0);
                Arrays.fill(nextErrorGreen, 0, w, 0);
                Arrays.fill(nextErrorBlue, 0, w, 0);
            }

            buffer.writeInt(IHDR);
            buffer.writeInt(w);
            buffer.writeInt(h);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

            byte[] curLine;

            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(w);
                buffer.writeInt(h);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);
                }
                deflater.reset();

                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(w)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(w);
                }

                for (int by = 0, py = flipY ? h - 1 : 0; by < h; by++, py += flipDir) {
                    final int ny = by + 1;
                    System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
                    System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
                    System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

                    Arrays.fill(nextErrorRed, (byte) 0);
                    Arrays.fill(nextErrorGreen, (byte) 0);
                    Arrays.fill(nextErrorBlue, (byte) 0);

                    for (int px = 0; px < w; px++) {
                        int color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            float er = curErrorRed[px];
                            float eg = curErrorGreen[px];
                            float eb = curErrorBlue[px];
                            int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                            int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                            int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                            byte paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            curLine[px] = paletteIndex;
                            int used = paletteArray[paletteIndex & 0xFF];
                            int rdiff = (color >>> 24) - (used >>> 24);
                            int gdiff = (color >>> 16 & 255) - (used >>> 16 & 255);
                            int bdiff = (color >>> 8 & 255) - (used >>> 8 & 255);
                            int modifier = ((px & 63) | (py << 6 & 0xFC0));
                            final float r1 = rdiff * strength * noiseA[modifier];
                            final float g1 = gdiff * strength * noiseB[modifier];
                            final float b1 = bdiff * strength * noiseC[modifier];
                            final float r2 = r1 + r1;
                            final float g2 = g1 + g1;
                            final float b2 = b1 + b1;
                            final float r4 = r2 + r2;
                            final float g4 = g2 + g2;
                            final float b4 = b2 + b2;

                            if(px < w - 1)
                            {
                                modifier = ((px + 1 & 63) | (py << 6 & 0xFC0));
                                curErrorRed[px+1]   += r4 * noiseA[modifier];
                                curErrorGreen[px+1] += g4 * noiseB[modifier];
                                curErrorBlue[px+1]  += b4 * noiseC[modifier];
                                if(px < w - 2)
                                {
                                    modifier = ((px + 2 & 63) | ((py << 6) & 0xFC0));
                                    curErrorRed[px+2]   += r2 * noiseA[modifier];
                                    curErrorGreen[px+2] += g2 * noiseB[modifier];
                                    curErrorBlue[px+2]  += b2 * noiseC[modifier];
                                }
                            }
                            if(ny < h)
                            {
                                if(px > 0)
                                {
                                    modifier = (px - 1 & 63) | ((ny << 6) & 0xFC0);
                                    nextErrorRed[px-1]   += r2 * noiseA[modifier];
                                    nextErrorGreen[px-1] += g2 * noiseB[modifier];
                                    nextErrorBlue[px-1]  += b2 * noiseC[modifier];
                                    if(px > 1)
                                    {
                                        modifier = (px - 2 & 63) | ((ny << 6) & 0xFC0);
                                        nextErrorRed[px-2]   += r1 * noiseA[modifier];
                                        nextErrorGreen[px-2] += g1 * noiseB[modifier];
                                        nextErrorBlue[px-2]  += b1 * noiseC[modifier];
                                    }
                                }
                                modifier = (px & 63) | ((ny << 6) & 0xFC0);
                                nextErrorRed[px]   += r4 * noiseA[modifier];
                                nextErrorGreen[px] += g4 * noiseB[modifier];
                                nextErrorBlue[px]  += b4 * noiseC[modifier];
                                if(px < w - 1)
                                {
                                    modifier = (px + 1 & 63) | ((ny << 6) & 0xFC0);
                                    nextErrorRed[px+1]   += r2 * noiseA[modifier];
                                    nextErrorGreen[px+1] += g2 * noiseB[modifier];
                                    nextErrorBlue[px+1]  += b2 * noiseC[modifier];
                                    if(px < w - 2)
                                    {
                                        modifier = (px + 2 & 63) | ((ny << 6) & 0xFC0);
                                        nextErrorRed[px+2]   += r1 * noiseA[modifier];
                                        nextErrorGreen[px+2] += g1 * noiseB[modifier];
                                        nextErrorBlue[px+2]  += b1 * noiseC[modifier];
                                    }
                                }
                            }
                        }
                    }

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, w);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    public void writeMartenDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int width = pixmap.getWidth();
            final int height = pixmap.getHeight();

            buffer.writeInt(IHDR);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeByte(8); // 8 bits per component.
            buffer.writeByte(COLOR_INDEXED);
            buffer.writeByte(COMPRESSION_DEFLATE);
            buffer.writeByte(FILTER_NONE);
            buffer.writeByte(INTERLACE_NONE);
            buffer.endChunk(dataOutput);

            buffer.writeInt(PLTE);
            for (int i = 0; i < paletteArray.length; i++) {
                int p = paletteArray[i];
                buffer.write(p >>> 24);
                buffer.write(p >>> 16);
                buffer.write(p >>> 8);
            }
            buffer.endChunk(dataOutput);

            boolean hasTransparent = false;
            if (paletteArray[0] == 0) {
                hasTransparent = true;
                buffer.writeInt(TRNS);
                buffer.write(0);
                buffer.endChunk(dataOutput);
            }
            buffer.writeInt(acTL);
            buffer.writeInt(frames.size);
            buffer.writeInt(0);
            buffer.endChunk(dataOutput);

//            byte[] lineOut, curLine, prevLine;
            byte[] curLine;

            final float populationBias = palette.populationBias;
            final float str = Math.min(1100f * (ditherStrength / (float) Math.sqrt(palette.colorCount) * (1f / (populationBias * populationBias * populationBias) - 0.7f)), 127f);
            int seq = 0;
            for (int i = 0; i < frames.size; i++) {

                buffer.writeInt(fcTL);
                buffer.writeInt(seq++);
                buffer.writeInt(width);
                buffer.writeInt(height);
                buffer.writeInt(0);
                buffer.writeInt(0);
                buffer.writeShort(1);
                buffer.writeShort(fps);
                buffer.writeByte(0);
                buffer.writeByte(0);
                buffer.endChunk(dataOutput);

                if (i == 0) {
                    buffer.writeInt(IDAT);
                } else {
                    pixmap = frames.get(i);
                    buffer.writeInt(fdAT);
                    buffer.writeInt(seq++);
                }
                deflater.reset();

                if (curLineBytes == null) {
                    curLine = (curLineBytes = new ByteArray(width)).items;
                } else {
                    curLine = curLineBytes.ensureCapacity(width);
                }

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        int color = pixmap.getPixel(px, py);
                        if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                            curLine[px] = 0;
                        else {
                            // We get a sub-random value from 0-1 using interleaved gradient noise.
                            // Offsetting this value by different values and feeding into triangleWave()
                            // gives 3 different values for r, g, and b, without much bias toward high or low values.
                            // There is correlation between r, g, and b in certain patterns.
                            final float theta = ((px * 142 + y * 79 & 255) * 0x1p-8f);
                            int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str)] & 255;
                            int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.382f) * str)] & 255;
                            int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.618f) * str)] & 255;
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
//                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);
//
//                    //Paeth
//                    for (int x = 1; x < width; x++) {
//                        int a = curLine[x - 1] & 0xff;
//                        int b = prevLine[x] & 0xff;
//                        int c = prevLine[x - 1] & 0xff;
//                        int p = a + b - c;
//                        int pa = p - a;
//                        if (pa < 0) pa = -pa;
//                        int pb = p - b;
//                        if (pb < 0) pb = -pb;
//                        int pc = p - c;
//                        if (pc < 0) pc = -pc;
//                        if (pa <= pb && pa <= pc)
//                            c = a;
//                        else if (pb <= pc)
//                            c = b;
//                        lineOut[x] = (byte) (curLine[x] - c);
//                    }
//
//                    deflaterOutput.write(FILTER_PAETH);
//                    deflaterOutput.write(lineOut, 0, width);

                    deflaterOutput.write(FILTER_NONE);
                    deflaterOutput.write(curLine, 0, width);
                }
                deflaterOutput.finish();
                buffer.endChunk(dataOutput);
            }

            buffer.writeInt(IEND);
            buffer.endChunk(dataOutput);

            output.flush();
        } catch (IOException e) {
            Gdx.app.error("anim8", e.getMessage());
        }
    }

    /** Should probably be done explicitly; finalize() has been scheduled for removal from the JVM. */
    public void dispose () {
        deflater.end();
    }

    /**
     * Simple PNG IO from <a href="https://www.java-tips.org/java-se-tips-100019/23-java-awt-image/2283-png-file-format-decoder-in-java.html">Java-Tips.org</a> .
     * @param inStream an input stream to read from; will be closed at the end of this method
     * @return an {@link OrderedMap} of chunk names to chunk contents
     * @throws IOException if the file is not a PNG or is extremely long
     */
    protected static OrderedMap<String, byte[]> readChunks(InputStream inStream) throws IOException {
        DataInputStream in = new DataInputStream(inStream);
        if(in.readLong() != 0x89504e470d0a1a0aL)
            throw  new IOException("PNG signature not found!");
        OrderedMap<String, byte[]> chunks = new OrderedMap<>(10);
        boolean trucking = true;
        while (trucking) {
            try {
                // Read the length.
                int length = in.readInt();
                if (length < 0)
                    throw new IOException("Sorry, that file is too long.");
                // Read the type.
                byte[] typeBytes = new byte[4];
                in.readFully(typeBytes);
                // Read the data.
                byte[] data = new byte[length];
                in.readFully(data);
                // Read the CRC, discard it.
                int crc = in.readInt();
                String type = new String(typeBytes, StandardCharsets.UTF_8);
                chunks.put(type, data);
            } catch (EOFException eofe) {
                trucking = false;
            }
        }
        in.close();
        return chunks;
    }

    /**
     * Simple PNG IO from <a href="https://www.java-tips.org/java-se-tips-100019/23-java-awt-image/2283-png-file-format-decoder-in-java.html">Java-Tips.org</a> .
     * @param outStream an output stream; will be closed when this method ends
     * @param chunks an OrderedMap of chunks, almost always produced by {@link #readChunks(InputStream)}
     */
    protected static void writeChunks(OutputStream outStream, OrderedMap<String, byte[]> chunks) {
        CRC32 crc = new CRC32();
        DataOutputStream out = new DataOutputStream(outStream);
        try {
            out.writeLong(0x89504e470d0a1a0aL);
            byte[] k;
            for (ObjectMap.Entry<String, byte[]> ent : chunks.entries()) {
                out.writeInt(ent.value.length);
                k = ent.key.getBytes(StandardCharsets.UTF_8);
                out.write(k);
                crc.update(k, 0, k.length);
                out.write(ent.value);
                crc.update(ent.value, 0, ent.value.length);
                out.writeInt((int) crc.getValue());
                crc.reset();
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and changes its
     * palette (in full and in order) to exactly match {@code palette}. This is only likely to work if the input file
     * was written with the same palette order, such as by specifying an {@code exactPalette} in
     * {@link #writePrecisely(FileHandle, Pixmap, int[], boolean, int)} where that exactPalette has similar colors at
     * each palette index to {@code palette}.
     * @param input FileHandle to read from that should have a similar palette (and very similar order) to {@code palette}
     * @param output FileHandle that should be writable and empty
     * @param palette RGBA8888 color array
     */
    public static void swapPalette(FileHandle input, FileHandle output, int[] palette)
    {
        try {
            InputStream inputStream = input.read();
            OrderedMap<String, byte[]> chunks = readChunks(inputStream);
            byte[] pal = chunks.get("PLTE");
            if(pal == null)
            {
                output.write(inputStream, false);
                return;
            }
            for (int i = 0, p = 0; i < palette.length && p < pal.length - 2; i++) {
                int rgba = palette[i];
                pal[p++] = (byte) (rgba >>> 24);
                pal[p++] = (byte) (rgba >>> 16);
                pal[p++] = (byte) (rgba >>> 8);
            }
            writeChunks(output.write(false), chunks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits the red,
     * green, and blue channels of each color in its palette (which is all colors in the image) by converting them to a
     * 0.0-1.0 range and giving that to {@code editor}. <a href="https://github.com/libgdx/libgdx/wiki/Interpolation">The
     * libGDX wiki page on Interpolation</a> has valuable info.
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     * @param editor an Interpolation, such as {@link Interpolation#circleOut} (which brightens all but the darkest areas)
     */
    public static void editPalette(FileHandle input, FileHandle output, Interpolation editor)
    {
        try {
            InputStream inputStream = input.read();
            OrderedMap<String, byte[]> chunks = readChunks(inputStream);
            byte[] pal = chunks.get("PLTE");
            if(pal == null)
            {
                output.write(inputStream, false);
                return;
            }
            for (int p = 0; p < pal.length - 2;) {
                pal[p  ] = (byte)editor.apply(0f, 255.999f, (pal[p  ] & 255) / 255f);
                pal[p+1] = (byte)editor.apply(0f, 255.999f, (pal[p+1] & 255) / 255f);
                pal[p+2] = (byte)editor.apply(0f, 255.999f, (pal[p+2] & 255) / 255f);
                p+=3;
            }
            writeChunks(output.write(false), chunks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits its
     * palette by changing each used color so lighter colors lean towards warmer, more golden hues,
     * while darker colors lean toward cooler or more bluish hues. Extremely red or green colors are intensified
     * slightly to contrast them with the light and dark colors' changes.
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     */
    public static void hueShift(FileHandle input, FileHandle output) {
        hueShift(input, output, 1f);
    }
    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits its
     * palette by changing each used color so lighter colors lean towards warmer, more golden hues,
     * while darker colors lean toward cooler or more bluish hues. Extremely red or green colors are intensified
     * slightly to contrast them with the light and dark colors' changes.
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     * @param strengthMultiplier typically 1.0f or near it; higher values make the effect stronger
     */
    public static void hueShift(FileHandle input, FileHandle output, float strengthMultiplier) {
        try {
            InputStream inputStream = input.read();
            OrderedMap<String, byte[]> chunks = readChunks(inputStream);
            byte[] pal = chunks.get("PLTE");
            if (pal == null) {
                output.write(inputStream, false);
                return;
            }
            float aMul = 1.1f * strengthMultiplier, bMul = 0.125f * strengthMultiplier;
            for (int p = 0; p < pal.length - 2; ) {
                int rgb = (pal[p] & 255) << 24 | (pal[p + 1] & 255) << 16 | (pal[p + 2] & 255) << 8 | 255;

                int s = shrink(rgb);
                float L = OKLAB[0][s];
                float A = OKLAB[1][s] * aMul;
                float B = OKLAB[2][s] + OtherMath.asin(L - 0.6f) * bMul * (1f - A * A);
                rgb = oklabToRGB(L, A, B, 1f);
                pal[p] = (byte) (rgb >>> 24);
                pal[p + 1] = (byte) (rgb >>> 16);
                pal[p + 2] = (byte) (rgb >>> 8);
                p += 3;
            }
            writeChunks(output.write(false), chunks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits its
     * palette by changing each used color's Oklab L component (lightness), running it through the
     * Interpolation given here. The L component is limited to the 0 to 1 floating-point range.
     *
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     * @param changeL an Interpolation that will apply to the L component, with L=0 representing minimum lightness (black) and L=1 representing maximum lightness (white)
     */
    public static void editPaletteLightness(FileHandle input, FileHandle output, Interpolation changeL) {
        try {
            InputStream inputStream = input.read();
            OrderedMap<String, byte[]> chunks = readChunks(inputStream);
            byte[] pal = chunks.get("PLTE");
            if (pal == null) {
                output.write(inputStream, false);
                return;
            }
            for (int p = 0; p < pal.length - 2; ) {
                int rgb = (pal[p] & 255) << 24 | (pal[p + 1] & 255) << 16 | (pal[p + 2] & 255) << 8 | 255;

                int s = shrink(rgb);
                float L = Math.min(Math.max(changeL.apply(OKLAB[0][s]),  0f), 1f);
                float A = OKLAB[1][s];
                float B = OKLAB[2][s];
                rgb = oklabToRGB(L, A, B, 1f);
                pal[p] = (byte) (rgb >>> 24);
                pal[p + 1] = (byte) (rgb >>> 16);
                pal[p + 2] = (byte) (rgb >>> 8);
                p += 3;
            }
            writeChunks(output.write(false), chunks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits its
     * palette by changing each used color's Oklab chroma components. How this works internally doesn't matter as much,
     * but it leaves the lightness untouched, and only affects how much closer (or further) a color should be to gray at
     * its lightness. If {@code saturationMultiplier} is 0, this makes the image grayscale. If it is 1, the image will
     * have no change (other than possibly some minor shifts due to the loss of the 3 least-significant bits from each
     * channel). If it is between 0 and 1, the image will be less colorful, and more grayish. If it is greater than 1,
     * the image will have any colors that aren't pure gray become more colorful (saturated), clamped to a maximum
     * saturation in extreme cases.
     *
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     * @param saturationMultiplier if 0, will make the image grayscale; if 1, will make no change; if greater than 1, will oversaturate the image
     */
    public static void editPaletteSaturation(FileHandle input, FileHandle output, float saturationMultiplier) {
        try {
            InputStream inputStream = input.read();
            OrderedMap<String, byte[]> chunks = readChunks(inputStream);
            byte[] pal = chunks.get("PLTE");
            if (pal == null) {
                output.write(inputStream, false);
                return;
            }
            for (int p = 0; p < pal.length - 2; ) {
                int rgb = (pal[p] & 255) << 24 | (pal[p + 1] & 255) << 16 | (pal[p + 2] & 255) << 8 | 255;

                int s = shrink(rgb);
                float L = OKLAB[0][s];
                float A = Math.min(Math.max(OKLAB[1][s] * saturationMultiplier, -1f), 1f);
                float B = Math.min(Math.max(OKLAB[2][s] * saturationMultiplier, -1f), 1f);
                rgb = oklabToRGB(L, A, B, 1f);
                pal[p] = (byte) (rgb >>> 24);
                pal[p + 1] = (byte) (rgb >>> 16);
                pal[p + 2] = (byte) (rgb >>> 8);
                p += 3;
            }
            writeChunks(output.write(false), chunks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits its
     * palette by changing each used color's Oklab components, running L, A, and B through each corresponding
     * Interpolation given here. If an Interpolation normally operates on the 0 to 1 range, it still will do that for
     * the L channel, but A and B run from -1 to 1, and those Interpolations will use
     * {@link Interpolation#apply(float, float, float)} to interpolate between -1 and 1, instead of 0 to 1.
     *
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     * @param changeL an Interpolation that will apply to the L component, with L=0 representing minimum lightness (black) and L=1 representing maximum lightness (white)
     * @param changeA an Interpolation that will apply to the A component (in the -1 to 1 range), with A=-1 representing the most-green hue, A=1 representing the most-red hue, and A=0 representing neither green nor red
     * @param changeB an Interpolation that will apply to the B component (in the -1 to 1 range), with B=-1 representing the most-blue hue, B=1 representing the most-yellow hue, and B=0 representing neither blue nor yellow
     */
    public static void editPaletteOklab(FileHandle input, FileHandle output,
                                        Interpolation changeL, Interpolation changeA, Interpolation changeB) {
        try {
            InputStream inputStream = input.read();
            OrderedMap<String, byte[]> chunks = readChunks(inputStream);
            byte[] pal = chunks.get("PLTE");
            if (pal == null) {
                output.write(inputStream, false);
                return;
            }
            for (int p = 0; p < pal.length - 2; ) {
                int rgb = (pal[p] & 255) << 24 | (pal[p + 1] & 255) << 16 | (pal[p + 2] & 255) << 8 | 255;

                int s = shrink(rgb);
                float L = Math.min(Math.max(changeL.apply( 0f, 1f, OKLAB[0][s]),  0f), 1f);
                float A = Math.min(Math.max(changeA.apply(-1f, 1f, OKLAB[1][s] * 0.5f + 0.5f), -1f), 1f);
                float B = Math.min(Math.max(changeB.apply(-1f, 1f, OKLAB[2][s] * 0.5f + 0.5f), -1f), 1f);
                rgb = oklabToRGB(L, A, B, 1f);
                pal[p] = (byte) (rgb >>> 24);
                pal[p + 1] = (byte) (rgb >>> 16);
                pal[p + 2] = (byte) (rgb >>> 8);
                p += 3;
            }
            writeChunks(output.write(false), chunks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits the red,
     * green, and blue channels of each color in its palette (which is all colors in the image) by running each channel
     * through a function ({@link OtherMath#centralize(byte)}) that biases any channel values
     * that aren't extreme toward the center of their range, and keeps extreme values (such as max green, or black) as
     * they are.
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     */
    public static void centralizePalette(FileHandle input, FileHandle output)
    {
        centralizePalette(input, output, 1f);
    }

    /**
     * Given a FileHandle to read from and a FileHandle to write to, duplicates the input FileHandle and edits the red,
     * green, and blue channels of each color in its palette (which is all colors in the image) by running each channel
     * through a function ({@link OtherMath#centralize(byte)}) that biases any channel values
     * that aren't extreme toward the center of their range, and keeps extreme values (such as max green, or black) as
     * they are. This takes an {@code amount} parameter, between 0.0 and 1.0, that controls how much of the centralized
     * effect to use (higher amount means more centralized colors).
     * @param input FileHandle to read from that should contain an indexed-mode PNG (such as one this class wrote)
     * @param output FileHandle that should be writable and empty
     * @param amount how much this should use of the centralizing effect, from 0.0 (no centralization) to 1.0 (full)
     */
    public static void centralizePalette(FileHandle input, FileHandle output, float amount)
    {
        try {
            InputStream inputStream = input.read();
            OrderedMap<String, byte[]> chunks = readChunks(inputStream);
            byte[] pal = chunks.get("PLTE");
            if(pal == null)
            {
                output.write(inputStream, false);
                return;
            }
            for (int p = 0; p < pal.length; p++) {
                pal[p] = (byte) MathUtils.lerp(pal[p] & 255, OtherMath.centralize(pal[p]) & 255, amount);
            }
            writeChunks(output.write(false), chunks);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}

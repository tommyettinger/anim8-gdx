package com.github.tommyettinger.anim8;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.*;

import java.io.*;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

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
 * reduce (or increase) dither strength, typically between 0 and 2; the dithering algorithm used here by default is
 *  * based on blue noise and a quasi-random pattern ({@link DitherAlgorithm#BLUE_NOISE}), but you can select alternatives
 *  * with {@link #setDitherAlgorithm(DitherAlgorithm)}, like a modified version of Jorge Jimenez' Gradient Interleaved
 *  * Noise using {@link DitherAlgorithm#GRADIENT_NOISE}, or no dither at all with {@link DitherAlgorithm#NONE}.
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
    static private final byte[] SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    static private final int IHDR = 0x49484452, IDAT = 0x49444154, IEND = 0x49454E44,
            PLTE = 0x504C5445, TRNS = 0x74524E53,
            acTL = 0x6163544C, fcTL = 0x6663544C, fdAT = 0x66644154;
    static private final byte COLOR_INDEXED = 3;
    static private final byte COMPRESSION_DEFLATE = 0;
    static private final byte FILTER_NONE = 0;
    static private final byte INTERLACE_NONE = 0;
    static private final byte PAETH = 4;

    private final ChunkBuffer buffer;
    private final Deflater deflater;
    private ByteArray lineOutBytes, curLineBytes, prevLineBytes;
    private boolean flipY = true;
    private int lastLineLen;

    public PaletteReducer palette;
    
    protected DitherAlgorithm ditherAlgorithm = DitherAlgorithm.BLUE_NOISE;

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
     * computePalette is false, this uses the last palette this had computed, or the 256-color "Haltonic" palette if no
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
     * compute a palette from the given Pixmap regardless of computePalette.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param computePalette if true, this will analyze the Pixmap and use the most common colors
     * @param dither true if this should dither colors that can't be represented exactly
     * @param threshold the analysis threshold to use if computePalette is true (min 0, practical max is over 100000)
     */
    public void write (OutputStream output, Pixmap pixmap, boolean computePalette, boolean dither, int threshold) {
        boolean clearPalette;
        if(clearPalette = (palette == null))
        {
            palette = new PaletteReducer(pixmap, threshold);
        }
        else if(computePalette)
        {
            palette.analyze(pixmap, threshold);
        }

        if(dither) {
            switch (ditherAlgorithm) {
                case NONE:
                    writeSolid(output, pixmap);
                    break;
                case GRADIENT_NOISE:
                    writeGradientDithered(output, pixmap);
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
                default:
                case SCATTER:
                    writeScatterDithered(output, pixmap);
                    break;
            }
        }
        else writeSolid(output, pixmap);
        if(clearPalette) palette = null;
    }

    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to file; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this falls back to calling {@link #write(FileHandle, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette. The write() methods in this class that don't have
     * "Precise" in the name will reduce the color depth somewhat, but this will keep the non-alpha components of colors
     * exactly. For full precision on any color count, use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or
     * {@link AnimatedPNG} for animations.
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
     * exactly. For full precision on any color count, use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or
     * {@link AnimatedPNG} for animations.
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
     * somewhat, but this will keep the non-alpha components of colors exactly. For full precision on any color count,
     * use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
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
     * exactly. For full precision on any color count, use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or
     * {@link AnimatedPNG} for animations.
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
     * exactly. For full precision on any color count, use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or
     * {@link AnimatedPNG} for animations.
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
     * somewhat, but this will keep the non-alpha components of colors exactly. For full precision on any color count,
     * use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}, or {@link AnimatedPNG} for animations.
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
            byte[] lineOut, curLine, prevLine;
            if (lineOutBytes == null) {
                lineOut = (lineOutBytes = new ByteArray(lineLen)).items;
                curLine = (curLineBytes = new ByteArray(lineLen)).items;
                prevLine = (prevLineBytes = new ByteArray(lineLen)).items;
            } else {
                lineOut = lineOutBytes.ensureCapacity(lineLen);
                curLine = curLineBytes.ensureCapacity(lineLen);
                prevLine = prevLineBytes.ensureCapacity(lineLen);
                for (int i = 0, n = lastLineLen; i < n; i++) {
                    prevLine[i] = 0;
                }
            }

            lastLineLen = lineLen;

            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py);
                    curLine[px] = (byte) colorToIndex.get(color, 0);
                }

                lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                //Paeth
                for (int x = 1; x < lineLen; x++) {
                    int a = curLine[x - 1] & 0xff;
                    int b = prevLine[x] & 0xff;
                    int c = prevLine[x - 1] & 0xff;
                    int p = a + b - c;
                    int pa = p - a;
                    if (pa < 0) pa = -pa;
                    int pb = p - b;
                    if (pb < 0) pb = -pb;
                    int pc = p - c;
                    if (pc < 0) pc = -pc;
                    if (pa <= pb && pa <= pc)
                        c = a;
                    else if (pb <= pc)
                        c = b;
                    lineOut[x] = (byte) (curLine[x] - c);
                }

                deflaterOutput.write(PAETH);
                deflaterOutput.write(lineOut, 0, lineLen);

                byte[] temp = curLine;
                curLine = prevLine;
                prevLine = temp;
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
     * non-alpha components of colors exactly. For full precision on any color count, use
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
     * non-alpha components of colors exactly. For full precision on any color count, use
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

        int lineLen = width;
        byte[] lineOut, curLine, prevLine;
        if (lineOutBytes == null) {
            lineOut = (lineOutBytes = new ByteArray(lineLen)).items;
            curLine = (curLineBytes = new ByteArray(lineLen)).items;
            prevLine = (prevLineBytes = new ByteArray(lineLen)).items;
        } else {
            lineOut = lineOutBytes.ensureCapacity(lineLen);
            curLine = curLineBytes.ensureCapacity(lineLen);
            prevLine = prevLineBytes.ensureCapacity(lineLen);
            for (int i = 0, n = lastLineLen; i < n; i++)
            {
                prevLine[i] = 0;
            }
        }

        lastLineLen = lineLen;

        for (int y = startY; y < h; y++) {
            int py = flipY ? (pixmap.getHeight() - y - 1) : y;
            for (int px = startX; px < w; px++) {
                color = pixmap.getPixel(px, py);
                curLine[px - startX] = (byte) colorToIndex.get(color, 0);
            }

            lineOut[0] = (byte)(curLine[0] - prevLine[0]);

            //Paeth
            for (int x = 1; x < lineLen; x++) {
                int a = curLine[x - 1] & 0xff;
                int b = prevLine[x] & 0xff;
                int c = prevLine[x - 1] & 0xff;
                int p = a + b - c;
                int pa = p - a;
                if (pa < 0) pa = -pa;
                int pb = p - b;
                if (pb < 0) pb = -pb;
                int pc = p - c;
                if (pc < 0) pc = -pc;
                if (pa <= pb && pa <= pc)
                    c = a;
                else if (pb <= pc)
                    c = b;
                lineOut[x] = (byte)(curLine[x] - c);
            }

            deflaterOutput.write(PAETH);
            deflaterOutput.write(lineOut, 0, lineLen);

            byte[] temp = curLine;
            curLine = prevLine;
            prevLine = temp;
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
    private void writeSolid (OutputStream output, Pixmap pixmap){
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
        byte[] lineOut, curLine, prevLine;
        if (lineOutBytes == null) {
            lineOut = (lineOutBytes = new ByteArray(lineLen)).items;
            curLine = (curLineBytes = new ByteArray(lineLen)).items;
            prevLine = (prevLineBytes = new ByteArray(lineLen)).items;
        } else {
            lineOut = lineOutBytes.ensureCapacity(lineLen);
            curLine = curLineBytes.ensureCapacity(lineLen);
            prevLine = prevLineBytes.ensureCapacity(lineLen);
            for (int i = 0, n = lastLineLen; i < n; i++)
            {
                prevLine[i] = 0;
            }
        }

        lastLineLen = lineLen;

        int color;
        final int w = pixmap.getWidth(), h = pixmap.getHeight();
        for (int y = 0; y < h; y++) {
            int py = flipY ? (h - y - 1) : y;
            for (int px = 0; px < w; px++) {
                color = pixmap.getPixel(px, py);
                if ((color & 0x80) == 0 && hasTransparent)
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

            lineOut[0] = (byte)(curLine[0] - prevLine[0]);

            //Paeth
            for (int x = 1; x < lineLen; x++) {
                int a = curLine[x - 1] & 0xff;
                int b = prevLine[x] & 0xff;
                int c = prevLine[x - 1] & 0xff;
                int p = a + b - c;
                int pa = p - a;
                if (pa < 0) pa = -pa;
                int pb = p - b;
                if (pb < 0) pb = -pb;
                int pc = p - c;
                if (pc < 0) pc = -pc;
                if (pa <= pb && pa <= pc)
                    c = a;
                else if (pb <= pc)
                    c = b;
                lineOut[x] = (byte)(curLine[x] - c);
            }

            deflaterOutput.write(PAETH);
            deflaterOutput.write(lineOut, 0, lineLen);

            byte[] temp = curLine;
            curLine = prevLine;
            prevLine = temp;
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

    private void writeGradientDithered(OutputStream output, Pixmap pixmap) {
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
            byte[] lineOut, curLine, prevLine;
            if (lineOutBytes == null) {
                lineOut = (lineOutBytes = new ByteArray(w)).items;
                curLine = (curLineBytes = new ByteArray(w)).items;
                prevLine = (prevLineBytes = new ByteArray(w)).items;
            } else {
                lineOut = lineOutBytes.ensureCapacity(w);
                curLine = curLineBytes.ensureCapacity(w);
                prevLine = prevLineBytes.ensureCapacity(w);
                for (int i = 0, n = lastLineLen; i < n; i++)
                {
                    prevLine[i] = 0;
                }
            }

            lastLineLen = w;

            int color, used;

            byte paletteIndex;
            float pos, adj;
            final float strength = (float) (palette.ditherStrength * palette.populationBias * 3.333);
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py) & 0xF8F8F880;
                    if ((color & 0x80) == 0 && hasTransparent)
                        curLine[px] = 0;
                    else {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        int rr = ((color >>> 24)       );
                        int gg = ((color >>> 16) & 0xFF);
                        int bb = ((color >>> 8)  & 0xFF);
                        paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        pos = (px * 0.06711056f + py * 0.00583715f);
                        pos -= (int)pos;
                        pos *= 52.9829189f;
                        pos -= (int)pos;
                        adj = MathUtils.sin(pos * 2f - 1f) * strength;
//                            adj = (pos * pos - 0.3f) * strength;
                        rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                        gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                        bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];

                    }
                }

                lineOut[0] = (byte)(curLine[0] - prevLine[0]);

                //Paeth
                for (int x = 1; x < w; x++) {
                    int a = curLine[x - 1] & 0xff;
                    int b = prevLine[x] & 0xff;
                    int c = prevLine[x - 1] & 0xff;
                    int p = a + b - c;
                    int pa = p - a;
                    if (pa < 0) pa = -pa;
                    int pb = p - b;
                    if (pb < 0) pb = -pb;
                    int pc = p - c;
                    if (pc < 0) pc = -pc;
                    if (pa <= pb && pa <= pc)
                        c = a;
                    else if (pb <= pc)
                        c = b;
                    lineOut[x] = (byte)(curLine[x] - c);
                }

                deflaterOutput.write(PAETH);
                deflaterOutput.write(lineOut, 0, w);

                byte[] temp = curLine;
                curLine = prevLine;
                prevLine = temp;
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
    private void writeBlueNoiseDithered(OutputStream output, Pixmap pixmap) {
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
            byte[] lineOut, curLine, prevLine;
            if (lineOutBytes == null) {
                lineOut = (lineOutBytes = new ByteArray(w)).items;
                curLine = (curLineBytes = new ByteArray(w)).items;
                prevLine = (prevLineBytes = new ByteArray(w)).items;
            } else {
                lineOut = lineOutBytes.ensureCapacity(w);
                curLine = curLineBytes.ensureCapacity(w);
                prevLine = prevLineBytes.ensureCapacity(w);
                for (int i = 0, n = lastLineLen; i < n; i++)
                {
                    prevLine[i] = 0;
                }
            }

            lastLineLen = w;

            int color, used;
            float adj, strength = (float) (palette.ditherStrength * palette.populationBias * 1.5);
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py) & 0xF8F8F880;
                    if ((color & 0x80) == 0 && hasTransparent)
                        curLine[px] = 0;
                    else {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        int rr = ((color >>> 24)       );
                        int gg = ((color >>> 16) & 0xFF);
                        int bb = ((color >>> 8)  & 0xFF);
                        used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF];
                        adj = ((PaletteReducer.RAW_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f); // 0.007843138f is 1f / 127.5f
                        adj += ((px + y & 1) - 0.5f) * (0.5f + PaletteReducer.RAW_BLUE_NOISE[(px * 19 & 63) | (y * 23 & 63) << 6]) * -0x1.6p-10f;
                        adj *= strength;
                        rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                        gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                        bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];
                    }
                }

                lineOut[0] = (byte)(curLine[0] - prevLine[0]);

                //Paeth
                for (int x = 1; x < w; x++) {
                    int a = curLine[x - 1] & 0xff;
                    int b = prevLine[x] & 0xff;
                    int c = prevLine[x - 1] & 0xff;
                    int p = a + b - c;
                    int pa = p - a;
                    if (pa < 0) pa = -pa;
                    int pb = p - b;
                    if (pb < 0) pb = -pb;
                    int pc = p - c;
                    if (pc < 0) pc = -pc;
                    if (pa <= pb && pa <= pc)
                        c = a;
                    else if (pb <= pc)
                        c = b;
                    lineOut[x] = (byte)(curLine[x] - c);
                }

                deflaterOutput.write(PAETH);
                deflaterOutput.write(lineOut, 0, w);

                byte[] temp = curLine;
                curLine = prevLine;
                prevLine = temp;
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
    
    private void writeChaoticNoiseDithered(OutputStream output, Pixmap pixmap) {
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
            byte[] lineOut, curLine, prevLine;
            if (lineOutBytes == null) {
                lineOut = (lineOutBytes = new ByteArray(w)).items;
                curLine = (curLineBytes = new ByteArray(w)).items;
                prevLine = (prevLineBytes = new ByteArray(w)).items;
            } else {
                lineOut = lineOutBytes.ensureCapacity(w);
                curLine = curLineBytes.ensureCapacity(w);
                prevLine = prevLineBytes.ensureCapacity(w);
                for (int i = 0, n = lastLineLen; i < n; i++)
                {
                    prevLine[i] = 0;
                }
            }

            lastLineLen = w;

            int color, used;

            byte paletteIndex;
            double adj, strength = palette.ditherStrength * palette.populationBias * 1.5;
            long s = 0xC13FA9A902A6328FL;
            for (int y = 0; y < h; y++) {
                int py = flipY ? (h - y - 1) : y;
                for (int px = 0; px < w; px++) {
                    color = pixmap.getPixel(px, py) & 0xF8F8F880;
                    if ((color & 0x80) == 0 && hasTransparent)
                        curLine[px] = 0;
                    else {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        int rr = ((color >>> 24)       );
                        int gg = ((color >>> 16) & 0xFF);
                        int bb = ((color >>> 8)  & 0xFF);
                        paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        adj = ((PaletteReducer.RAW_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
                        adj *= adj * adj;
                        //// Complicated... This starts with a checkerboard of -0.5 and 0.5, times a tiny fraction.
                        //// The next 3 lines generate 3 low-quality-random numbers based on s, which should be
                        ////   different as long as the colors encountered so far were different. The numbers can
                        ////   each be positive or negative, and are reduced to a manageable size, summed, and
                        ////   multiplied by the earlier tiny fraction. Summing 3 random values gives us a curved
                        ////   distribution, centered on about 0.0 and weighted so most results are close to 0.
                        ////   Two of the random numbers use an XLCG, and the last uses an LCG. 
                        adj += ((px + y & 1) - 0.5f) * 0x1.8p-49 * strength *
                                (((s ^ 0x9E3779B97F4A7C15L) * 0xC6BC279692B5CC83L >> 15) +
                                        ((~s ^ 0xDB4F0B9175AE2165L) * 0xD1B54A32D192ED03L >> 15) +
                                        ((s = (s ^ color) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                        rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                        gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                        bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                        curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))];
                    }
                }

                lineOut[0] = (byte)(curLine[0] - prevLine[0]);

                //Paeth
                for (int x = 1; x < w; x++) {
                    int a = curLine[x - 1] & 0xff;
                    int b = prevLine[x] & 0xff;
                    int c = prevLine[x - 1] & 0xff;
                    int p = a + b - c;
                    int pa = p - a;
                    if (pa < 0) pa = -pa;
                    int pb = p - b;
                    if (pb < 0) pb = -pb;
                    int pc = p - c;
                    if (pc < 0) pc = -pc;
                    if (pa <= pb && pa <= pc)
                        c = a;
                    else if (pb <= pc)
                        c = b;
                    lineOut[x] = (byte)(curLine[x] - c);
                }

                deflaterOutput.write(PAETH);
                deflaterOutput.write(lineOut, 0, w);

                byte[] temp = curLine;
                curLine = prevLine;
                prevLine = temp;
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
    
    private void writeDiffusionDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedBytes == null) {
                curErrorRed = (palette.curErrorRedBytes = new ByteArray(w)).items;
                nextErrorRed = (palette.nextErrorRedBytes = new ByteArray(w)).items;
                curErrorGreen = (palette.curErrorGreenBytes = new ByteArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenBytes = new ByteArray(w)).items;
                curErrorBlue = (palette.curErrorBlueBytes = new ByteArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueBytes = new ByteArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedBytes.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedBytes.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenBytes.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenBytes.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueBytes.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueBytes.ensureCapacity(w);
                Arrays.fill(nextErrorRed, (byte) 0);
                Arrays.fill(nextErrorGreen, (byte) 0);
                Arrays.fill(nextErrorBlue, (byte) 0);
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

            int color, used, rdiff, gdiff, bdiff;
            byte er, eg, eb, paletteIndex;
            float w1 = (float)(palette.ditherStrength * palette.populationBias * 0.125), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

            byte[] lineOut, curLine, prevLine;
            if (lineOutBytes == null) {
                lineOut = (lineOutBytes = new ByteArray(w)).items;
                curLine = (curLineBytes = new ByteArray(w)).items;
                prevLine = (prevLineBytes = new ByteArray(w)).items;
            } else {
                lineOut = lineOutBytes.ensureCapacity(w);
                curLine = curLineBytes.ensureCapacity(w);
                prevLine = prevLineBytes.ensureCapacity(w);
                for (int i = 0, n = lastLineLen; i < n; i++)
                {
                    prevLine[i] = 0;
                }
            }

            lastLineLen = w;

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
                    color = pixmap.getPixel(px, py) & 0xF8F8F880;
                    if ((color & 0x80) == 0 && hasTransparent)
                        curLine[px] = 0;
                    else {
                        er = curErrorRed[px];
                        eg = curErrorGreen[px];
                        eb = curErrorBlue[px];
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        int rr = Math.min(Math.max(((color >>> 24)       ) + (er), 0), 0xFF);
                        int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + (eg), 0), 0xFF);
                        int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + (eb), 0), 0xFF);
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = (color>>>24)-    (used>>>24);
                        gdiff = (color>>>16&255)-(used>>>16&255);
                        bdiff = (color>>>8&255)- (used>>>8&255);
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
                lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                //Paeth
                for (int x = 1; x < w; x++) {
                    int a = curLine[x - 1] & 0xff;
                    int b = prevLine[x] & 0xff;
                    int c = prevLine[x - 1] & 0xff;
                    int p = a + b - c;
                    int pa = p - a;
                    if (pa < 0) pa = -pa;
                    int pb = p - b;
                    if (pb < 0) pb = -pb;
                    int pc = p - c;
                    if (pc < 0) pc = -pc;
                    if (pa <= pb && pa <= pc)
                        c = a;
                    else if (pb <= pc)
                        c = b;
                    lineOut[x] = (byte) (curLine[x] - c);
                }

                deflaterOutput.write(PAETH);
                deflaterOutput.write(lineOut, 0, w);

                byte[] temp = curLine;
                curLine = prevLine;
                prevLine = temp;
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

    private void writePatternDithered(OutputStream output, Pixmap pixmap) {
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
        byte[] lineOut, curLine, prevLine;
        if (lineOutBytes == null) {
            lineOut = (lineOutBytes = new ByteArray(w)).items;
            curLine = (curLineBytes = new ByteArray(w)).items;
            prevLine = (prevLineBytes = new ByteArray(w)).items;
        } else {
            lineOut = lineOutBytes.ensureCapacity(w);
            curLine = curLineBytes.ensureCapacity(w);
            prevLine = prevLineBytes.ensureCapacity(w);
            for (int i = 0, n = lastLineLen; i < n; i++)
            {
                prevLine[i] = 0;
            }
        }

        lastLineLen = w;

        int color, used;
        int cr, cg, cb,  usedIndex;
        final float errorMul = (float) (palette.ditherStrength * palette.populationBias * 0.6);
        for (int y = 0; y < h; y++) {
            int py = flipY ? (h - y - 1) : y;
            for (int px = 0; px < w; px++) {
                color = pixmap.getPixel(px, py) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    curLine[px] = 0;
                else {
                    int er = 0, eg = 0, eb = 0;
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    cr = (color >>> 24);
                    cg = (color >>> 16 & 0xFF);
                    cb = (color >>> 8 & 0xFF);
                    for (int c = 0; c < 8; c++) {
                        int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                        int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                        int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        palette.candidates[c] = paletteArray[usedIndex];
                        used = palette.gammaArray[usedIndex];
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    palette.sort8(palette.candidates);
                    curLine[px] = paletteMapping[
                            PaletteReducer.shrink(palette.candidates[PaletteReducer.thresholdMatrix8[
                                    ((int) (px * 0x1.C13FA9A902A6328Fp3 + y * 0x1.9E3779B97F4A7C15p-2) & 3) ^
                                            ((px & 3) | (y & 1) << 2)
                                    ]])];

                }
            }

            lineOut[0] = (byte)(curLine[0] - prevLine[0]);

            //Paeth
            for (int x = 1; x < w; x++) {
                int a = curLine[x - 1] & 0xff;
                int b = prevLine[x] & 0xff;
                int c = prevLine[x - 1] & 0xff;
                int p = a + b - c;
                int pa = p - a;
                if (pa < 0) pa = -pa;
                int pb = p - b;
                if (pb < 0) pb = -pb;
                int pc = p - c;
                if (pc < 0) pc = -pc;
                if (pa <= pb && pa <= pc)
                    c = a;
                else if (pb <= pc)
                    c = b;
                lineOut[x] = (byte)(curLine[x] - c);
            }

            deflaterOutput.write(PAETH);
            deflaterOutput.write(lineOut, 0, w);

            byte[] temp = curLine;
            curLine = prevLine;
            prevLine = temp;
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

    private void writeScatterDithered(OutputStream output, Pixmap pixmap) {
        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            buffer.writeInt(IHDR);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedBytes == null) {
                curErrorRed = (palette.curErrorRedBytes = new ByteArray(w)).items;
                nextErrorRed = (palette.nextErrorRedBytes = new ByteArray(w)).items;
                curErrorGreen = (palette.curErrorGreenBytes = new ByteArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenBytes = new ByteArray(w)).items;
                curErrorBlue = (palette.curErrorBlueBytes = new ByteArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueBytes = new ByteArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedBytes.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedBytes.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenBytes.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenBytes.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueBytes.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueBytes.ensureCapacity(w);
                Arrays.fill(nextErrorRed, (byte) 0);
                Arrays.fill(nextErrorGreen, (byte) 0);
                Arrays.fill(nextErrorBlue, (byte) 0);
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

            int color, used, rdiff, gdiff, bdiff;
            byte er, eg, eb, paletteIndex;
            float w1 = (float)(palette.ditherStrength * palette.populationBias * 0.125), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

            byte[] lineOut, curLine, prevLine;
            if (lineOutBytes == null) {
                lineOut = (lineOutBytes = new ByteArray(w)).items;
                curLine = (curLineBytes = new ByteArray(w)).items;
                prevLine = (prevLineBytes = new ByteArray(w)).items;
            } else {
                lineOut = lineOutBytes.ensureCapacity(w);
                curLine = curLineBytes.ensureCapacity(w);
                prevLine = prevLineBytes.ensureCapacity(w);
                for (int i = 0, n = lastLineLen; i < n; i++)
                {
                    prevLine[i] = 0;
                }
            }

            lastLineLen = w;

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
                    color = pixmap.getPixel(px, py) & 0xF8F8F880;
                    if ((color & 0x80) == 0 && hasTransparent)
                        curLine[px] = 0;
                    else {
                        double tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                        er = (byte) (curErrorRed[px] * tbn);
                        eg = (byte) (curErrorGreen[px] * tbn);
                        eb = (byte) (curErrorBlue[px] * tbn);
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        int rr = Math.min(Math.max(((color >>> 24)       ) + (er), 0), 0xFF);
                        int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + (eg), 0), 0xFF);
                        int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + (eb), 0), 0xFF);
                        curLine[px] = paletteIndex =
                                paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))];
                        used = paletteArray[paletteIndex & 0xFF];
                        rdiff = (color>>>24)-    (used>>>24);
                        gdiff = (color>>>16&255)-(used>>>16&255);
                        bdiff = (color>>>8&255)- (used>>>8&255);
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
                lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                //Paeth
                for (int x = 1; x < w; x++) {
                    int a = curLine[x - 1] & 0xff;
                    int b = prevLine[x] & 0xff;
                    int c = prevLine[x - 1] & 0xff;
                    int p = a + b - c;
                    int pa = p - a;
                    if (pa < 0) pa = -pa;
                    int pb = p - b;
                    if (pb < 0) pb = -pb;
                    int pc = p - c;
                    if (pc < 0) pc = -pc;
                    if (pa <= pb && pa <= pc)
                        c = a;
                    else if (pb <= pc)
                        c = b;
                    lineOut[x] = (byte) (curLine[x] - c);
                }

                deflaterOutput.write(PAETH);
                deflaterOutput.write(lineOut, 0, w);

                byte[] temp = curLine;
                curLine = prevLine;
                prevLine = temp;
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
     * compute a palette from all of the frames given. Otherwise, this uses the colors already in {@link #palette}.
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
     * {@link #setDitherAlgorithm(DitherAlgorithm)} (or {@link DitherAlgorithm#PATTERN} if not set).
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
        if (dither)
            write(output, frames, fps);
        else
            writeSolid(output, frames, fps);
        if(clearPalette) palette = null;
    }

    private void writeSolid(OutputStream output, Array<Pixmap> frames, int fps) {
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

        byte[] lineOut, curLine, prevLine;
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

            if (lineOutBytes == null) {
                lineOut = (lineOutBytes = new ByteArray(width)).items;
                curLine = (curLineBytes = new ByteArray(width)).items;
                prevLine = (prevLineBytes = new ByteArray(width)).items;
            } else {
                lineOut = lineOutBytes.ensureCapacity(width);
                curLine = curLineBytes.ensureCapacity(width);
                prevLine = prevLineBytes.ensureCapacity(width);
                for (int ln = 0, n = lastLineLen; ln < n; ln++)
                    prevLine[ln] = 0;
            }
            lastLineLen = width;

            for (int y = 0; y < height; y++) {
                int py = flipY ? (height - y - 1) : y;
                for (int px = 0; px < width; px++) {
                    color = pixmap.getPixel(px, py);
                    if ((color & 0x80) == 0 && hasTransparent)
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

                lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                //Paeth
                for (int x = 1; x < width; x++) {
                    int a = curLine[x - 1] & 0xff;
                    int b = prevLine[x] & 0xff;
                    int c = prevLine[x - 1] & 0xff;
                    int p = a + b - c;
                    int pa = p - a;
                    if (pa < 0) pa = -pa;
                    int pb = p - b;
                    if (pb < 0) pb = -pb;
                    int pc = p - c;
                    if (pc < 0) pc = -pc;
                    if (pa <= pb && pa <= pc)
                        c = a;
                    else if (pb <= pc)
                        c = b;
                    lineOut[x] = (byte) (curLine[x] - c);
                }

                deflaterOutput.write(PAETH);
                deflaterOutput.write(lineOut, 0, width);

                byte[] temp = curLine;
                curLine = prevLine;
                prevLine = temp;
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
        switch (ditherAlgorithm){
            case NONE:
                writeSolid(output, frames, fps);
                break;
            case GRADIENT_NOISE:
                writeGradientDithered(output, frames, fps);
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
            default:
                writeBlueNoiseDithered(output, frames, fps);
        }
    }

    private void writeGradientDithered(OutputStream output, Array<Pixmap> frames, int fps) {
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

            byte[] lineOut, curLine, prevLine;
            int color, used;

            lastLineLen = width;

            byte paletteIndex;
            float pos, adj;
            final float strength = (float) (palette.ditherStrength * palette.populationBias * 3.333);

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

                if (lineOutBytes == null) {
                    lineOut = (lineOutBytes = new ByteArray(width)).items;
                    curLine = (curLineBytes = new ByteArray(width)).items;
                    prevLine = (prevLineBytes = new ByteArray(width)).items;
                } else {
                    lineOut = lineOutBytes.ensureCapacity(width);
                    curLine = curLineBytes.ensureCapacity(width);
                    prevLine = prevLineBytes.ensureCapacity(width);
                    for (int ln = 0, n = lastLineLen; ln < n; ln++)
                        prevLine[ln] = 0;
                }
                lastLineLen = width;

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py) & 0xF8F8F880;
                        if ((color & 0x80) == 0 && hasTransparent)
                            curLine[px] = 0;
                        else {
                            color |= (color >>> 5 & 0x07070700) | 0xFF;
                            int rr = ((color >>> 24)       );
                            int gg = ((color >>> 16) & 0xFF);
                            int bb = ((color >>> 8)  & 0xFF);
                            paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            pos = (px * 0.06711056f + py * 0.00583715f);
                            pos -= (int)pos;
                            pos *= 52.9829189f;
                            pos -= (int)pos;
                            adj = MathUtils.sin(pos * 2f - 1f) * strength;
//                            adj = (pos * pos - 0.3f) * strength;
                            rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                            gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                            bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];

                        }
                    }
                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                    //Paeth
                    for (int x = 1; x < width; x++) {
                        int a = curLine[x - 1] & 0xff;
                        int b = prevLine[x] & 0xff;
                        int c = prevLine[x - 1] & 0xff;
                        int p = a + b - c;
                        int pa = p - a;
                        if (pa < 0) pa = -pa;
                        int pb = p - b;
                        if (pb < 0) pb = -pb;
                        int pc = p - c;
                        if (pc < 0) pc = -pc;
                        if (pa <= pb && pa <= pc)
                            c = a;
                        else if (pb <= pc)
                            c = b;
                        lineOut[x] = (byte) (curLine[x] - c);
                    }

                    deflaterOutput.write(PAETH);
                    deflaterOutput.write(lineOut, 0, width);

                    byte[] temp = curLine;
                    curLine = prevLine;
                    prevLine = temp;
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
    private void writeBlueNoiseDithered(OutputStream output, Array<Pixmap> frames, int fps) {
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

            byte[] lineOut, curLine, prevLine;
            int color, used;

            lastLineLen = width;

            byte paletteIndex;
            float adj, strength = (float) (palette.ditherStrength * palette.populationBias * 1.5);

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

                if (lineOutBytes == null) {
                    lineOut = (lineOutBytes = new ByteArray(width)).items;
                    curLine = (curLineBytes = new ByteArray(width)).items;
                    prevLine = (prevLineBytes = new ByteArray(width)).items;
                } else {
                    lineOut = lineOutBytes.ensureCapacity(width);
                    curLine = curLineBytes.ensureCapacity(width);
                    prevLine = prevLineBytes.ensureCapacity(width);
                    for (int ln = 0, n = lastLineLen; ln < n; ln++)
                        prevLine[ln] = 0;
                }
                lastLineLen = width;
                int bn;
                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py) & 0xF8F8F880;
                        if ((color & 0x80) == 0 && hasTransparent)
                            curLine[px] = 0;
                        else {
                            color |= (color >>> 5 & 0x07070700) | 0xFF;
                            int rr = ((color >>> 24)       );
                            int gg = ((color >>> 16) & 0xFF);
                            int bb = ((color >>> 8)  & 0xFF);
                            paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            adj = ((PaletteReducer.RAW_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f); // 0.007843138f is 1f / 127.5f
                            adj += ((px + y & 1) - 0.5f) * (0.5f + PaletteReducer.RAW_BLUE_NOISE[(px * 19 & 63) | (y * 23 & 63) << 6]) * -0x1.6p-10f;
                            adj *= strength;
                            rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                            gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                            bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                    //Paeth
                    for (int x = 1; x < width; x++) {
                        int a = curLine[x - 1] & 0xff;
                        int b = prevLine[x] & 0xff;
                        int c = prevLine[x - 1] & 0xff;
                        int p = a + b - c;
                        int pa = p - a;
                        if (pa < 0) pa = -pa;
                        int pb = p - b;
                        if (pb < 0) pb = -pb;
                        int pc = p - c;
                        if (pc < 0) pc = -pc;
                        if (pa <= pb && pa <= pc)
                            c = a;
                        else if (pb <= pc)
                            c = b;
                        lineOut[x] = (byte) (curLine[x] - c);
                    }

                    deflaterOutput.write(PAETH);
                    deflaterOutput.write(lineOut, 0, width);

                    byte[] temp = curLine;
                    curLine = prevLine;
                    prevLine = temp;
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

    private void writeChaoticNoiseDithered(OutputStream output, Array<Pixmap> frames, int fps) {
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

            byte[] lineOut, curLine, prevLine;
            int color, used;

            lastLineLen = width;

            byte paletteIndex;
            double adj, strength = palette.ditherStrength * palette.populationBias * 1.5;

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

                if (lineOutBytes == null) {
                    lineOut = (lineOutBytes = new ByteArray(width)).items;
                    curLine = (curLineBytes = new ByteArray(width)).items;
                    prevLine = (prevLineBytes = new ByteArray(width)).items;
                } else {
                    lineOut = lineOutBytes.ensureCapacity(width);
                    curLine = curLineBytes.ensureCapacity(width);
                    prevLine = prevLineBytes.ensureCapacity(width);
                    for (int ln = 0, n = lastLineLen; ln < n; ln++)
                        prevLine[ln] = 0;
                }
                lastLineLen = width;
                long s = 0xC13FA9A902A6328FL * seq;
                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py) & 0xF8F8F880;
                        if ((color & 0x80) == 0 && hasTransparent)
                            curLine[px] = 0;
                        else {
                            color |= (color >>> 5 & 0x07070700) | 0xFF;
                            int rr = ((color >>> 24)       );
                            int gg = ((color >>> 16) & 0xFF);
                            int bb = ((color >>> 8)  & 0xFF);
                            paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            adj = ((PaletteReducer.RAW_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
                            adj *= adj * adj;
                            //// Complicated... This starts with a checkerboard of -0.5 and 0.5, times a tiny fraction.
                            //// The next 3 lines generate 3 low-quality-random numbers based on s, which should be
                            ////   different as long as the colors encountered so far were different. The numbers can
                            ////   each be positive or negative, and are reduced to a manageable size, summed, and
                            ////   multiplied by the earlier tiny fraction. Summing 3 random values gives us a curved
                            ////   distribution, centered on about 0.0 and weighted so most results are close to 0.
                            ////   Two of the random numbers use an XLCG, and the last uses an LCG. 
                            adj += ((px + y & 1) - 0.5f) * 0x1.8p-49 * strength *
                                    (((s ^ 0x9E3779B97F4A7C15L) * 0xC6BC279692B5CC83L >> 15) +
                                            ((~s ^ 0xDB4F0B9175AE2165L) * 0xD1B54A32D192ED03L >> 15) +
                                            ((s = (s ^ color) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                            rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                            gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                            bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
                            curLine[px] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                        }
                    }
                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                    //Paeth
                    for (int x = 1; x < width; x++) {
                        int a = curLine[x - 1] & 0xff;
                        int b = prevLine[x] & 0xff;
                        int c = prevLine[x - 1] & 0xff;
                        int p = a + b - c;
                        int pa = p - a;
                        if (pa < 0) pa = -pa;
                        int pb = p - b;
                        if (pb < 0) pb = -pb;
                        int pc = p - c;
                        if (pc < 0) pc = -pc;
                        if (pa <= pb && pa <= pc)
                            c = a;
                        else if (pb <= pc)
                            c = b;
                        lineOut[x] = (byte) (curLine[x] - c);
                    }

                    deflaterOutput.write(PAETH);
                    deflaterOutput.write(lineOut, 0, width);

                    byte[] temp = curLine;
                    curLine = prevLine;
                    prevLine = temp;
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

    private void writeDiffusionDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedBytes == null) {
                curErrorRed = (palette.curErrorRedBytes = new ByteArray(w)).items;
                nextErrorRed = (palette.nextErrorRedBytes = new ByteArray(w)).items;
                curErrorGreen = (palette.curErrorGreenBytes = new ByteArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenBytes = new ByteArray(w)).items;
                curErrorBlue = (palette.curErrorBlueBytes = new ByteArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueBytes = new ByteArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedBytes.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedBytes.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenBytes.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenBytes.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueBytes.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueBytes.ensureCapacity(w);
                Arrays.fill(nextErrorRed, (byte) 0);
                Arrays.fill(nextErrorGreen, (byte) 0);
                Arrays.fill(nextErrorBlue, (byte) 0);
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

            byte[] lineOut, curLine, prevLine;

            lastLineLen = w;

            int color, used, rdiff, gdiff, bdiff;
            byte er, eg, eb, paletteIndex;
            float w1 = (float)(palette.ditherStrength * palette.populationBias * 0.125), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

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

                if (lineOutBytes == null) {
                    lineOut = (lineOutBytes = new ByteArray(w)).items;
                    curLine = (curLineBytes = new ByteArray(w)).items;
                    prevLine = (prevLineBytes = new ByteArray(w)).items;
                } else {
                    lineOut = lineOutBytes.ensureCapacity(w);
                    curLine = curLineBytes.ensureCapacity(w);
                    prevLine = prevLineBytes.ensureCapacity(w);
                    for (int ln = 0, n = lastLineLen; ln < n; ln++)
                        prevLine[ln] = 0;
                }
                lastLineLen = w;

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
                        color = pixmap.getPixel(px, py) & 0xF8F8F880;
                        if ((color & 0x80) == 0 && hasTransparent)
                            curLine[px] = 0;
                        else {
                            er = curErrorRed[px];
                            eg = curErrorGreen[px];
                            eb = curErrorBlue[px];
                            color |= (color >>> 5 & 0x07070700) | 0xFF;
                            int rr = Math.min(Math.max(((color >>> 24)       ) + (er), 0), 0xFF);
                            int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + (eg), 0), 0xFF);
                            int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + (eb), 0), 0xFF);
                            curLine[px] = paletteIndex = 
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (color>>>24)-    (used>>>24);
                            gdiff = (color>>>16&255)-(used>>>16&255);
                            bdiff = (color>>>8&255)- (used>>>8&255);
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
                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                    //Paeth
                    for (int x = 1; x < w; x++) {
                        int a = curLine[x - 1] & 0xff;
                        int b = prevLine[x] & 0xff;
                        int c = prevLine[x - 1] & 0xff;
                        int p = a + b - c;
                        int pa = p - a;
                        if (pa < 0) pa = -pa;
                        int pb = p - b;
                        if (pb < 0) pb = -pb;
                        int pc = p - c;
                        if (pc < 0) pc = -pc;
                        if (pa <= pb && pa <= pc)
                            c = a;
                        else if (pb <= pc)
                            c = b;
                        lineOut[x] = (byte) (curLine[x] - c);
                    }

                    deflaterOutput.write(PAETH);
                    deflaterOutput.write(lineOut, 0, w);

                    byte[] temp = curLine;
                    curLine = prevLine;
                    prevLine = temp;
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

    private void writePatternDithered(OutputStream output, Array<Pixmap> frames, int fps) {
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

            byte[] lineOut, curLine, prevLine;
            
            lastLineLen = width;

            int color, used;
            int cr, cg, cb,  usedIndex;
            final float errorMul = (float) (palette.ditherStrength * palette.populationBias * 0.6);

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

                if (lineOutBytes == null) {
                    lineOut = (lineOutBytes = new ByteArray(width)).items;
                    curLine = (curLineBytes = new ByteArray(width)).items;
                    prevLine = (prevLineBytes = new ByteArray(width)).items;
                } else {
                    lineOut = lineOutBytes.ensureCapacity(width);
                    curLine = curLineBytes.ensureCapacity(width);
                    prevLine = prevLineBytes.ensureCapacity(width);
                    for (int ln = 0, n = lastLineLen; ln < n; ln++)
                        prevLine[ln] = 0;
                }
                lastLineLen = width;

                for (int y = 0; y < height; y++) {
                    int py = flipY ? (height - y - 1) : y;
                    for (int px = 0; px < width; px++) {
                        color = pixmap.getPixel(px, py) & 0xF8F8F880;
                        if ((color & 0x80) == 0 && hasTransparent)
                            curLine[px] = 0;
                        else {
                            int er = 0, eg = 0, eb = 0;
                            color |= (color >>> 5 & 0x07070700) | 0xFF;
                            cr = (color >>> 24);
                            cg = (color >>> 16 & 0xFF);
                            cb = (color >>> 8 & 0xFF);
                            for (int c = 0; c < 8; c++) {
                                int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                                int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                                int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                                usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))] & 0xFF;
                                palette.candidates[c] = paletteArray[usedIndex];
                                used = palette.gammaArray[usedIndex];
                                er += cr - (used >>> 24);
                                eg += cg - (used >>> 16 & 0xFF);
                                eb += cb - (used >>> 8 & 0xFF);
                            }
                            palette.sort8(palette.candidates);
                            curLine[px] = paletteMapping[
                                    PaletteReducer.shrink(palette.candidates[PaletteReducer.thresholdMatrix8[
                                            ((int) (px * 0x1.C13FA9A902A6328Fp3 + y * 0x1.9E3779B97F4A7C15p-2) & 3) ^
                                                    ((px & 3) | (y & 1) << 2)
                                    ]])];
                        }
                    }
                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                    //Paeth
                    for (int x = 1; x < width; x++) {
                        int a = curLine[x - 1] & 0xff;
                        int b = prevLine[x] & 0xff;
                        int c = prevLine[x - 1] & 0xff;
                        int p = a + b - c;
                        int pa = p - a;
                        if (pa < 0) pa = -pa;
                        int pb = p - b;
                        if (pb < 0) pb = -pb;
                        int pc = p - c;
                        if (pc < 0) pc = -pc;
                        if (pa <= pb && pa <= pc)
                            c = a;
                        else if (pb <= pc)
                            c = b;
                        lineOut[x] = (byte) (curLine[x] - c);
                    }

                    deflaterOutput.write(PAETH);
                    deflaterOutput.write(lineOut, 0, width);

                    byte[] temp = curLine;
                    curLine = prevLine;
                    prevLine = temp;
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
    
    private void writeScatterDithered(OutputStream output, Array<Pixmap> frames, int fps) {
        Pixmap pixmap = frames.first();
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(buffer, deflater);
        DataOutputStream dataOutput = new DataOutputStream(output);
        try {
            dataOutput.write(SIGNATURE);

            final int w = pixmap.getWidth();
            final int h = pixmap.getHeight();
            byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
            if (palette.curErrorRedBytes == null) {
                curErrorRed = (palette.curErrorRedBytes = new ByteArray(w)).items;
                nextErrorRed = (palette.nextErrorRedBytes = new ByteArray(w)).items;
                curErrorGreen = (palette.curErrorGreenBytes = new ByteArray(w)).items;
                nextErrorGreen = (palette.nextErrorGreenBytes = new ByteArray(w)).items;
                curErrorBlue = (palette.curErrorBlueBytes = new ByteArray(w)).items;
                nextErrorBlue = (palette.nextErrorBlueBytes = new ByteArray(w)).items;
            } else {
                curErrorRed = palette.curErrorRedBytes.ensureCapacity(w);
                nextErrorRed = palette.nextErrorRedBytes.ensureCapacity(w);
                curErrorGreen = palette.curErrorGreenBytes.ensureCapacity(w);
                nextErrorGreen = palette.nextErrorGreenBytes.ensureCapacity(w);
                curErrorBlue = palette.curErrorBlueBytes.ensureCapacity(w);
                nextErrorBlue = palette.nextErrorBlueBytes.ensureCapacity(w);
                Arrays.fill(nextErrorRed, (byte) 0);
                Arrays.fill(nextErrorGreen, (byte) 0);
                Arrays.fill(nextErrorBlue, (byte) 0);
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

            byte[] lineOut, curLine, prevLine;

            lastLineLen = w;

            int color, used, rdiff, gdiff, bdiff;
            byte er, eg, eb, paletteIndex;
            float w1 = (float)(palette.ditherStrength * palette.populationBias * 0.125), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

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

                if (lineOutBytes == null) {
                    lineOut = (lineOutBytes = new ByteArray(w)).items;
                    curLine = (curLineBytes = new ByteArray(w)).items;
                    prevLine = (prevLineBytes = new ByteArray(w)).items;
                } else {
                    lineOut = lineOutBytes.ensureCapacity(w);
                    curLine = curLineBytes.ensureCapacity(w);
                    prevLine = prevLineBytes.ensureCapacity(w);
                    for (int ln = 0, n = lastLineLen; ln < n; ln++)
                        prevLine[ln] = 0;
                }
                lastLineLen = w;

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
                        color = pixmap.getPixel(px, py) & 0xF8F8F880;
                        if ((color & 0x80) == 0 && hasTransparent)
                            curLine[px] = 0;
                        else {
                            double tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                            er = (byte) (curErrorRed[px] * tbn);
                            eg = (byte) (curErrorGreen[px] * tbn);
                            eb = (byte) (curErrorBlue[px] * tbn);
                            color |= (color >>> 5 & 0x07070700) | 0xFF;
                            int rr = Math.min(Math.max(((color >>> 24)       ) + (er), 0), 0xFF);
                            int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + (eg), 0), 0xFF);
                            int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + (eb), 0), 0xFF);
                            curLine[px] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))];
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (color>>>24)-    (used>>>24);
                            gdiff = (color>>>16&255)-(used>>>16&255);
                            bdiff = (color>>>8&255)- (used>>>8&255);
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
                    lineOut[0] = (byte) (curLine[0] - prevLine[0]);

                    //Paeth
                    for (int x = 1; x < w; x++) {
                        int a = curLine[x - 1] & 0xff;
                        int b = prevLine[x] & 0xff;
                        int c = prevLine[x - 1] & 0xff;
                        int p = a + b - c;
                        int pa = p - a;
                        if (pa < 0) pa = -pa;
                        int pb = p - b;
                        if (pb < 0) pb = -pb;
                        int pc = p - c;
                        if (pc < 0) pc = -pc;
                        if (pa <= pb && pa <= pc)
                            c = a;
                        else if (pb <= pc)
                            c = b;
                        lineOut[x] = (byte) (curLine[x] - c);
                    }

                    deflaterOutput.write(PAETH);
                    deflaterOutput.write(lineOut, 0, w);

                    byte[] temp = curLine;
                    curLine = prevLine;
                    prevLine = temp;
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
     * Simple PNG IO from https://www.java-tips.org/java-se-tips-100019/23-java-awt-image/2283-png-file-format-decoder-in-java.html .
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
                String type = new String(typeBytes, "UTF8");
                chunks.put(type, data);
            } catch (EOFException eofe) {
                trucking = false;
            }
        }
        in.close();
        return chunks;
    }

    /**
     * Simple PNG IO from https://www.java-tips.org/java-se-tips-100019/23-java-awt-image/2283-png-file-format-decoder-in-java.html .
     * @param outStream an output stream; will be closed when this method ends
     * @param chunks an OrderedMap of chunks, almost always produced by {@link #readChunks(InputStream)}
     */
    protected static void writeChunks(OutputStream outStream, OrderedMap<String, byte[]> chunks) {
        DataOutputStream out = new DataOutputStream(outStream);
        CRC32 crc = new CRC32();
        try {
            out.writeLong(0x89504e470d0a1a0aL);
            for (ObjectMap.Entry<String, byte[]> ent : chunks.entries()) {
                out.writeInt(ent.value.length);
                out.writeBytes(ent.key);
                crc.update(ent.key.getBytes("UTF8"));
                out.write(ent.value);
                crc.update(ent.value);
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
                pal[p] = OtherMath.centralize(pal[p]);
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

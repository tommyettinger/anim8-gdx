package com.github.tommyettinger.anim8;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** PNG-8 encoder with compression; can write animated and non-animated PNG images in indexed-mode.
 * An instance can be reused to encode multiple PNGs with minimal allocation.
 * You can configure the target palette and how this can dither colors via the {@link #palette} field, which is a
 * {@link PaletteReducer} object that defaults to null and can be reused. If you assign a PaletteReducer to palette, the
 * methods {@link PaletteReducer#exact(Color[])} or {@link PaletteReducer#analyze(Pixmap)} can be used to make the
 * target palette match a specific set of colors or the colors in an existing image. You can use
 * {@link PaletteReducer#setDitherStrength(float)} to reduce (or increase) dither strength; the dithering algorithm used
 * here is a modified version of Jorge Jimenez' Gradient Interleaved Noise. Note that for many cases where you write a
 * non-animated PNG, you will want to use {@link #writePrecisely(FileHandle, Pixmap, boolean)} instead of
 * {@link #write(FileHandle, Pixmap, boolean, boolean)}, since writePrecisely will attempt to reproduce the exact colors
 * if there are 256 colors or less in the Pixmap, and will automatically change to calling write() if there are more
 * than 256 colors.
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
    
    protected DitherAlgorithm ditherAlgorithm = DitherAlgorithm.PATTERN;

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
     * computePalette is false, this uses the last palette this had computed, or the 256-color DB Aurora palette if no
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
        if(palette == null)
        {
            palette = new PaletteReducer(pixmap, threshold);
        }
        else if(computePalette)
        {
            palette.analyze(pixmap, threshold);
        }

        if(dither) {
            switch (ditherAlgorithm){
                case NONE:
                    writeSolid(output, pixmap);
                break;
                case GRADIENT_NOISE:
                    writeGradientDithered(output, pixmap);
                break;
                default:
                    writePatternDithered(output, pixmap);
            }
        }
        else writeSolid(output, pixmap);
    }

    /**
     * Attempts to write the given Pixmap exactly as a PNG-8 image to file; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this falls back to calling {@link #write(FileHandle, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette. All other write() methods in this class will
     * reduce the color depth somewhat, but as long as the color count stays at 256 or less, this will keep the
     * non-alpha components of colors exactly.
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
     * typically between 1 and 1000, and most often near 200-400). All other write() methods in this class will
     * reduce the color depth somewhat, but as long as the color count stays at 256 or less, this will keep the
     * non-alpha components of colors exactly.
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
     * Attempts to write the given Pixmap exactly as a PNG-8 image to file; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the colors in the Pixmap can be accurately represented by some or all of {@code exactPalette} (and it is
     * non-null), then that palette will be used in full and in order.
     * If the attempt fails, this falls back to calling {@link #write(FileHandle, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette, using the given threshold for analysis (which is
     * typically between 1 and 1000, and most often near 200-400). All other write() methods in this class will
     * reduce the color depth somewhat, but as long as the color count stays at 256 or less, this will keep the
     * non-alpha components of colors exactly.
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
     * always analyze the Pixmap to get an accurate-enough palette. All other write() methods in this class will
     * reduce the color depth somewhat, but as long as the color count stays at 256 or less, this will keep the
     * non-alpha components of colors exactly.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     * @throws IOException if OutputStream things fail for any reason
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
     * typically between 1 and 1000, and most often near 200-400). All other write() methods in this class will
     * reduce the color depth somewhat, but as long as the color count stays at 256 or less, this will keep the
     * non-alpha components of colors exactly.
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
     * non-null), then that palette will be used in full and in order.
     * If the attempt fails, this falls back to calling {@link #write(OutputStream, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette, using the given threshold for analysis (which is
     * typically between 1 and 1000, and most often near 200-400). All other write() methods in this class will
     * reduce the color depth somewhat, but as long as the color count stays at 256 or less, this will keep the
     * non-alpha components of colors exactly.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @param exactPalette if non-null, will try to use this palette exactly, in order and including unused colors
     * @param ditherFallback if the Pixmap contains too many colors, this determines whether it will dither the output
     * @param threshold the analysis threshold to use if there are too many colors (min 0, practical max is over 100000)
     * @throws IOException if OutputStream things fail for any reason
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
     * Attempts to write the given Pixmap exactly as a PNG-8 image to file; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this will throw an IllegalArgumentException. This will keep the non-alpha components of
     * colors exactly.
     * @param file a FileHandle that must be writable, and will have the given Pixmap written as a PNG-8 image
     * @param pixmap a Pixmap to write to the given output stream
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
     * Attempts to write the given Pixmap exactly as a PNG-8 image to output; this attempt will only succeed if there
     * are no more than 256 colors in the Pixmap (treating all partially transparent colors as fully transparent).
     * If the attempt fails, this falls back to calling {@link #write(OutputStream, Pixmap, boolean, boolean)}, which
     * can dither the image to use no more than 255 colors (plus fully transparent) based on ditherFallback and will
     * always analyze the Pixmap to get an accurate-enough palette, using the given threshold for analysis (which is
     * typically between 1 and 1000, and most often near 200-400). All other write() methods in this class will
     * reduce the color depth somewhat, but as long as the color count stays at 256 or less, this will keep the
     * non-alpha components of colors exactly.
     * @param output an OutputStream that will not be closed
     * @param pixmap a Pixmap to write to the given output stream
     * @throws IOException if OutputStream things fail for any reason
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
        final float strength = palette.ditherStrength * 3.333f;
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
                    pos = (px * 0.06711056f + y * 0.00583715f);
                    pos -= (int)pos;
                    pos *= 52.9829189f;
                    pos -= (int)pos;
                    adj = (pos * pos - 0.3f) * strength;
                    rr = MathUtils.clamp((int) (rr + (adj * (rr - (used >>> 24       )))), 0, 0xFF);
                    gg = MathUtils.clamp((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0, 0xFF);
                    bb = MathUtils.clamp((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0, 0xFF);
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
        final float errorMul = palette.ditherStrength * 0.375f;
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
                    for (int c = 0; c < palette.candidates.length; c++) {
                        int rr = MathUtils.clamp((int) (cr + er * errorMul), 0, 255);
                        int gg = MathUtils.clamp((int) (cg + eg * errorMul), 0, 255);
                        int bb = MathUtils.clamp((int) (cb + eb * errorMul), 0, 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        palette.candidates[c] = paletteArray[usedIndex];
                        used = palette.gammaArray[usedIndex];
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    palette.sort16(palette.candidates);
                    curLine[px] = paletteMapping[
                            PaletteReducer.shrink(palette.candidates[PaletteReducer.thresholdMatrix[
                                    ((int) (px * 0x0.C13FA9A902A6328Fp3f + y * 0x0.91E10DA5C79E7B1Dp2f) & 3) ^
                                            ((px & 3) | (y & 3) << 2)
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
        if (palette == null)
            palette = new PaletteReducer(frames);
        if (dither)
            write(output, frames, fps);
        else
            writeSolid(output, frames, fps);
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
            default:
                writePatternDithered(output, frames, fps);
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
            final float strength = palette.ditherStrength * 3.333f;

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
                            pos = (px * 0.06711056f + y * 0.00583715f);
                            pos -= (int)pos;
                            pos *= 52.9829189f;
                            pos -= (int)pos;
                            adj = (pos * pos - 0.3f) * strength;
                            rr = MathUtils.clamp((int) (rr + (adj * (rr - (used >>> 24       )))), 0, 0xFF);
                            gg = MathUtils.clamp((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0, 0xFF);
                            bb = MathUtils.clamp((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0, 0xFF);
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
            final float errorMul = palette.ditherStrength * 0.375f;

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
                            for (int c = 0; c < palette.candidates.length; c++) {
                                int rr = MathUtils.clamp((int) (cr + er * errorMul), 0, 255);
                                int gg = MathUtils.clamp((int) (cg + eg * errorMul), 0, 255);
                                int bb = MathUtils.clamp((int) (cb + eb * errorMul), 0, 255);
                                usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))] & 0xFF;
                                palette.candidates[c] = paletteArray[usedIndex];
                                used = palette.gammaArray[usedIndex];
                                er += cr - (used >>> 24);
                                eg += cg - (used >>> 16 & 0xFF);
                                eb += cb - (used >>> 8 & 0xFF);
                            }
                            palette.sort16(palette.candidates);
                            curLine[px] = paletteMapping[
                                    PaletteReducer.shrink(palette.candidates[PaletteReducer.thresholdMatrix[
                                            ((int) (px * 0x0.C13FA9A902A6328Fp3f + y * 0x0.91E10DA5C79E7B1Dp2f) & 3) ^
                                                    ((px & 3) | (y & 3) << 2)
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

    /** Should probably be done explicitly; finalize() has been scheduled for removal from the JVM. */
    public void dispose () {
        deflater.end();
    }

}

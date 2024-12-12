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
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static com.github.tommyettinger.anim8.PaletteReducer.*;

/**
 * GIF encoder using standard LZW compression; can write animated and non-animated GIF images.
 * An instance can be reused to encode multiple GIFs with minimal allocation.
 * <br>
 * You can configure the target palette and how this can dither colors via the {@link #palette} field, which is a
 * {@link PaletteReducer} object that defaults to null and can be reused. If you assign a PaletteReducer to palette, the
 * methods {@link PaletteReducer#exact(Color[])} or {@link PaletteReducer#analyze(Pixmap)} can be used to make the
 * target palette match a specific set of colors or the colors in an existing image. If palette is null, this will
 * compute a palette for each GIF that closely fits its set of given animation frames. If the palette isn't an exact
 * match for the colors used in an animation (indexed mode has at most 256 colors), this will dither pixels so that from
 * a distance, they look closer to the original colors. You can us {@link PaletteReducer#setDitherStrength(float)} to
 * reduce (or increase) dither strength, typically between 0 and 2;
 * the dithering algorithm used here by default is based on Burkes error-diffusion dithering but with patterns
 * broken up using a variety of noise ({@link DitherAlgorithm#OVERBOARD}), but you can select alternatives with
 * {@link #setDitherAlgorithm(DitherAlgorithm)}. Using {@link DitherAlgorithm#LOAF} may usually look worse in still
 * frames, but it tends to look great in fast animations because it won't have "static" from error-diffusion. You could
 * just no dither at all with {@link DitherAlgorithm#NONE}, though that tends to look awful with small palettes.
 * <br>
 * You can write non-animated GIFs with this, but libGDX can't read them back in, so you may want to prefer {@link PNG8}
 * for images with 256 or fewer colors and no animation (libGDX can read in non-animated PNG files, as well as the first
 * frame of animated PNG files). If you have an animation that doesn't look good with dithering or has multiple levels
 * of transparency (GIF only supports one fully transparent color), you can use {@link AnimatedPNG} to output a
 * full-color animation. If you have a non-animated image that you want to save in lossless full-color, you can
 * use {@link FastPNG} or {@link com.badlogic.gdx.graphics.PixmapIO.PNG}; the PNG code here is based on PixmapIO, and
 * although FastPNG is faster to write files, PixmapIO's are better-compressed.
 * <br>
 * Based on <a href="https://github.com/nbadal/android-gif-encoder/blob/master/GifEncoder.java">Nick Badal's Android port</a> of
 * <a href="http://www.jappit.com/blog/2008/12/04/j2me-animated-gif-encoder/">Alessandro La Rossa's J2ME port</a> of this pure
 * Java <a href="http://www.java2s.com/Code/Java/2D-Graphics-GUI/AnimatedGifEncoder.htm">animated GIF encoder by Kevin Weiner</a>.
 * The original has no copyright asserted, so this file continues that tradition and does not assert copyright either.
 */
public class AnimatedGif implements AnimationWriter, Dithered {
    /**
     * Writes the given Pixmap values in {@code frames}, in order, to an animated GIF at {@code file}. Always writes at
     * 30 frames per second, so if frames has less than 30 items, this animation will be under a second-long.
     * @param file the FileHandle to write to; should generally not be internal because it must be writable
     * @param frames an Array of Pixmap frames that should all be the same size, to be written in order
     */
    @Override
    public void write(FileHandle file, Array<Pixmap> frames) {
        write(file, frames, 30);
    }

    /**
     * Writes the given Pixmap values in {@code frames}, in order, to an animated GIF at {@code file}. The resulting GIF
     * will play back at {@code fps} frames per second.
     * @param file the FileHandle to write to; should generally not be internal because it must be writable
     * @param frames an Array of Pixmap frames that should all be the same size, to be written in order
     * @param fps how many frames (from {@code frames}) to play back per second
     */
    @Override
    public void write(FileHandle file, Array<Pixmap> frames, int fps) {
        OutputStream output = file.write(false);
        try {
            write(output, frames, fps);
        } finally {
            StreamUtils.closeQuietly(output);
        }
    }

    /**
     * Writes the given Pixmap values in {@code frames}, in order, to an animated GIF in the OutputStream
     * {@code output}. The resulting GIF will play back at {@code fps} frames per second. If {@link #palette}
     * is null, {@link #fastAnalysis} is set to false, and frames contains 2 or more Pixmaps, then this will
     * make a palette for the first frame using {@link PaletteReducer#analyze(Pixmap)}, then reuse that PaletteReducer
     * but recompute a different analyzed palette for each subsequent frame. This results in the
     * highest-quality color quantization for any given frame, but is relatively slow; it takes over 4x as long
     * when the palette is null and fastAnalysis is false vs. when the palette was analyzed all-frames-at-once with
     * {@link PaletteReducer#analyze(Array)}. An alternative is to use a null palette and set fastAnalysis to true,
     * which is the default when frames has 2 or more Pixmaps. This does a very quick analysis of the colors in each
     * frame, which is usually good enough, and takes about the same time as analyzing all frames as one PaletteReducer.
     * Using a null palette also means the final image can use more than 256 total colors over the course of the
     * animation, regardless of fastAnalysis's setting, if there is more than one Pixmap in frames.
     * @param output the OutputStream to write to; will not be closed by this method
     * @param frames an Array of Pixmap frames that should all be the same size, to be written in order
     * @param fps how many frames (from {@code frames}) to play back per second
     */
    @Override
    public void write(OutputStream output, Array<Pixmap> frames, int fps) {
        if(frames == null || frames.isEmpty()) return;
        clearPalette = (palette == null);
        if (clearPalette) {
            if (fastAnalysis && frames.size > 1) {
                palette = new PaletteReducer();
                palette.analyzeFast(frames.first(), 300, 256);
            }
            else
                palette = new PaletteReducer(frames.first());
        }
        if(!start(output)) return;
        setFrameRate(fps);
        for (int i = 0; i < frames.size; i++) {
            addFrame(frames.get(i));
        }
        finish();
        if(clearPalette)
            palette = null;
    }

    protected DitherAlgorithm ditherAlgorithm = DitherAlgorithm.OVERBOARD;
    
    protected int width; // image size

    protected int height;

    protected int x = 0;

    protected int y = 0;

    protected boolean flipY = true;

    protected int transIndex = -1; // transparent index in color table

    protected int repeat = 0; // loop repeat

    protected int delay = 16; // frame delay (thousandths)

    protected boolean started = false; // ready to output frames

    protected OutputStream out;

    protected Pixmap image; // current frame

    protected byte[] indexedPixels; // converted frame indexed to palette

    protected int colorDepth; // number of bit planes

    protected byte[] colorTab; // RGB palette, 3 bytes per color

    protected boolean[] usedEntry = new boolean[256]; // active palette entries

    protected int palSize = 7; // color table size (bits-1)

    protected int dispose = -1; // disposal code (-1 = use default)

    protected boolean closeStream = false; // close stream when finished

    protected boolean firstFrame = true;

    protected boolean sizeSet = false; // if false, get size from first frame

    protected int seq = 0;

    protected boolean clearPalette;

    /**
     * If true (the default) and {@link #palette} is null, this uses a lower-quality but much-faster algorithm to
     * analyze the color palette in each frame; if false and palette is null, then this uses the normal algorithm for
     * still images on each frame separately. The case when this is false can be 4x to 5x slower than the case when it
     * is true, but it can produce higher-quality animations. This is ignored for single-frame GIFs, and the still-image
     * algorithm is always used there when palette is null.
     */
    public boolean fastAnalysis = true;

    /**
     * Often assigned as a field, the palette can be null (which means this may analyze each frame for its palette,
     * based on the setting for {@link #fastAnalysis}), or can be an existing PaletteReducer. You may want to create a
     * PaletteReducer with an exact palette, such as by {@link PaletteReducer#PaletteReducer(int[])}, and then assign it
     * to this field. Note that {@link PaletteReducer#getDitherStrength()} is ignored here and replaced with the value
     * of {@link #getDitherStrength()} in this class. This allows palette to be null, but dither strength to still be
     * configurable.
     */
    public PaletteReducer palette;

    /**
     * Overrides the palette's dither strength; see {@link #getDitherStrength()}.
     * @see #getDitherStrength()
     */
    protected float ditherStrength = 1f;

    /**
     * Gets this AnimatedGif's dither strength, which will override the {@link PaletteReducer#getDitherStrength()} in
     * the PaletteReducer this uses. This applies even if {@link #getPalette()} is null; in that case, when a temporary
     * PaletteReducer is created, it will use this dither strength.
     * @return the current dither strength override
     */
    public float getDitherStrength() {
        return ditherStrength;
    }

    /**
     * Sets this AnimatedGif's dither strength, which will override the {@link PaletteReducer#getDitherStrength()} in
     * the PaletteReducer this uses. This applies even if {@link #getPalette()} is null; in that case, when a temporary
     * PaletteReducer is created, it will use this dither strength.
     * @param ditherStrength the desired dither strength, usually between 0 and 2 and defaulting to 1
     */
    public void setDitherStrength(float ditherStrength) {
        this.ditherStrength = Math.max(0f, ditherStrength);
    }

    /**
     * Gets the PaletteReducer this uses to lower the color count in an image. If the PaletteReducer is null, this
     * should try to assign itself a PaletteReducer when given a new image.
     *
     * @return the PaletteReducer this uses; may be null
     */
    @Override
    public PaletteReducer getPalette() {
        return palette;
    }

    /**
     * Sets the PaletteReducer this uses to bring a high-color or different-palette image down to a smaller palette
     * size. If {@code palette} is null, this should try to assign itself a PaletteReducer when given a new image.
     *
     * @param palette a PaletteReducer that is often pre-configured with a specific palette; null is usually allowed
     */
    @Override
    public void setPalette(PaletteReducer palette) {
        this.palette = palette;
    }

    /**
     * Sets the delay time between each frame, or changes it for subsequent frames
     * (applies to last frame added).
     *
     * @param ms int delay time in milliseconds
     */
    public void setDelay(int ms) {
        delay = ms;
    }

    /**
     * Sets the GIF frame disposal code for the last added frame and any
     * subsequent frames. Default is 0 if no transparent color has been set,
     * otherwise 2.
     *
     * @param code int disposal code.
     */
    public void setDispose(int code) {
        if (code >= 0) {
            dispose = code;
        }
    }

    /**
     * Sets the number of times the set of GIF frames should be played. Default is
     * 1; 0 means play indefinitely. Must be invoked before the first image is
     * added.
     *
     * @param iter int number of iterations.
     */
    public void setRepeat(int iter) {
        if (iter >= 0) {
            repeat = iter;
        }
    }

    /**
     * Returns true if the output is flipped top-to-bottom from the inputs (the default); otherwise returns false.
     * @return true if the inputs are flipped on writing, false otherwise
     */
    public boolean isFlipY() {
        return flipY;
    }

    /**
     * Sets whether this should flip inputs top-to-bottom (true, the default setting), or leave as-is (false).
     * @param flipY true if this should flip inputs top-to-bottom when writing, false otherwise
     */
    public void setFlipY(boolean flipY) {
        this.flipY = flipY;
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
     * Adds next GIF frame. The frame is not written immediately, but is actually
     * deferred until the next frame is received so that timing data can be
     * inserted. Invoking <code>finish()</code> flushes all frames. If
     * <code>setSize</code> was not invoked, the size of the first image is used
     * for all subsequent frames.
     *
     * @param im Pixmap containing frame to write.
     * @return true if successful.
     */
    public boolean addFrame(Pixmap im) {
        if ((im == null) || !started) {
            return false;
        }
        boolean ok = true;
        try {
            if (!sizeSet) {
                // use first frame's size
                setSize(im.getWidth(), im.getHeight());
            }
            ++seq;
            image = im;
            getImagePixels(); // convert to correct format if necessary
            // build color table & map pixels
            analyzePixels();
            if (firstFrame) {
                writeLSD(); // logical screen descriptor
                writePalette(); // global color table
                if (repeat >= 0) {
                    // use NS app extension to indicate reps
                    writeNetscapeExt();
                }
            }
            writeGraphicCtrlExt(); // write graphic control extension
            writeImageDesc(); // image descriptor
            if (!firstFrame) {
                writePalette(); // local color table
            }
            writePixels(); // encode and write pixel data
            firstFrame = false;
        } catch (IOException e) {
            ok = false;
        }

        return ok;
    }

    /**
     * Flushes any pending data and closes output file. If writing to an
     * OutputStream, the stream is not closed.
     */
    public boolean finish() {
        if (!started)
            return false;
        boolean ok = true;
        started = false;
        try {
            out.write(0x3b); // gif trailer
            out.flush();
            if (closeStream) {
                out.close();
            }
        } catch (IOException e) {
            ok = false;
        }

        // reset for subsequent use
        transIndex = -1;
        out = null;
        image = null;
        indexedPixels = null;
        colorTab = null;
        closeStream = false;
        sizeSet = false;
        firstFrame = true;
        seq = 0;

        return ok;
    }

    /**
     * Sets frame rate in frames per second. Equivalent to
     * <code>setDelay(1000.0f / fps)</code>.
     *
     * @param fps float frame rate (frames per second)
     */
    public void setFrameRate(float fps) {
        if (fps != 0f) {
            delay = (int) (1000f / fps);
        }
    }

    /**
     * Sets the GIF frame size. The default size is the size of the first frame
     * added if this method is not invoked.
     *
     * @param w int frame width.
     * @param h int frame width.
     */
    public void setSize(int w, int h) {
        width = w;
        height = h;
        if (width < 1)
            width = 320;
        if (height < 1)
            height = 240;
        sizeSet = true;
    }

    /**
     * Sets the GIF frame position. The position is 0,0 by default.
     * Useful for only updating a section of the image
     *
     * @param x int frame x position.
     * @param y int frame y position.
     */
    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Initiates GIF file creation on the given stream. The stream is not closed
     * automatically.
     *
     * @param os OutputStream on which GIF images are written.
     * @return false if initial write failed.
     */
    public boolean start(OutputStream os) {
        if (os == null)
            return false;
        boolean ok = true;
        closeStream = false;
        out = os;
        try {
            writeString("GIF89a"); // header
        } catch (IOException e) {
            ok = false;
            Gdx.app.error("anim8", e.getMessage());
        }
        return started = ok;
    }

    protected void analyzeNone()
    {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    usedEntry[(indexedPixels[i] = paletteMapping[
                            (color >>> 17 & 0x7C00)
                                    | (color >>> 14 & 0x3E0)
                                    | ((color >>> 11 & 0x1F))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzePattern() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        int cr, cg, cb, usedIndex;
        final float errorMul = ditherStrength * 0.5f / palette.populationBias;
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
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
                    usedEntry[(indexedPixels[i] = (byte) palette.candidates[
                            PaletteReducer.thresholdMatrix16[((px & 3) | (y & 3) << 2)]]
                    ) & 255] = true;
                    i++;

                }
            }
        }
    }

    protected void analyzeChaotic() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        double adj, strength = ditherStrength * palette.populationBias * 1.5;
        long s = 0xC13FA9A902A6328FL * seq;
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f);
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
                                    ((s = (s ^ rr + gg + bb) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                    rr = Math.min(Math.max((int) (rr + (adj * ((rr - (used >>> 24))))), 0), 0xFF);
                    gg = Math.min(Math.max((int) (gg + (adj * ((gg - (used >>> 16 & 0xFF))))), 0), 0xFF);
                    bb = Math.min(Math.max((int) (bb + (adj * ((bb - (used >>> 8 & 0xFF))))), 0), 0xFF);
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }
    protected void analyzeGradient() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final float populationBias = palette.populationBias;
        final float strength = Math.min(ditherStrength * (2f - (populationBias * populationBias * populationBias * populationBias - 0.1598797460796939f) * ((2f * 0.875f) / 0.8188650241570136f)), 1f);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + ((142 * px + 79 * (y - 0x96) & 255) - 127.5f) * strength)] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + ((142 * px + 79 * (y - 0xA3) & 255) - 127.5f) * strength)] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + ((142 * px + 79 * (y - 0xC9) & 255) - 127.5f) * strength)] & 255;
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }
    protected void analyzeRoberts() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final float populationBias = palette.populationBias;
        final float str = Math.min(Math.max(48 * ditherStrength / (populationBias * populationBias * populationBias * populationBias), -127), 127);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    // We get a sub-random value from 0-1 using the R2 sequence.
                    // Offsetting this value by different values and feeding into triangleWave()
                    // gives 3 different values for r, g, and b, without much bias toward high or low values.
                    // There is correlation between r, g, and b in certain patterns.
                    final float theta = ((px * 0xC13FA9A9 + y * 0x91E10DA5 >>> 9) * 0x1p-23f);
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + OtherMath.triangleWave(theta         ) * str)] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + OtherMath.triangleWave(theta + 0.209f) * str)] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + OtherMath.triangleWave(theta + 0.518f) * str)] & 255;
                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }
    protected void analyzeLoaf() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final float strength = Math.min(Math.max(2.5f + 5f * ditherStrength - 5.5f * palette.populationBias, 0f), 7.9f);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    int adj = (int)((((px + y & 1) << 5) - 16) * strength); // either + 16 * strength or - 16 * strength
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + adj, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + adj, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + adj, 0), 1023)] & 255;
                    int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                    usedEntry[(indexedPixels[i] = paletteMapping[rgb555]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeGourd() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final float strength = (float)(ditherStrength * 1.25 * Math.pow(palette.populationBias, -6.0));
        for (int i = 0; i < 64; i++) {
            PaletteReducer.tempThresholdMatrix[i] = Math.min(Math.max((PaletteReducer.thresholdMatrix64[i] - 31.5f) * strength, -127), 127);
        }
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    float adj = PaletteReducer.tempThresholdMatrix[(px & 7) | (y & 7) << 3];
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + adj)] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + adj)] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + adj)] & 255;
                    int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                    usedEntry[(indexedPixels[i] = paletteMapping[rgb555]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeDiffusion() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        byte paletteIndex;
        float w1 = ditherStrength * 32 / palette.populationBias, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + curErrorRed[px]  , 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + curErrorGreen[px], 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + curErrorBlue[px] , 0), 1023)] & 255;

                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                    used = paletteArray[paletteIndex & 0xFF];
                    rdiff = (0x1p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x1p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x1p-8f * ((color>>>8&255)- (used>>>8&255)) );
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeBlue() {
        final int nPix = indexedPixels.length;
        int color;
        int flipped = flipY ? height - 1 : 0;
        int flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        float adj, strength = 0.3125f * ditherStrength / (palette.populationBias * palette.populationBias * palette.populationBias);
        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, flipped + flipDir * y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    adj = ((px + y & 1) << 8) - 127.5f;
                    int rr = fromLinearLUT[(int)(toLinearLUT[(color >>> 24)       ] + Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + adj) * strength), -100), 100))] & 255;
                    int gg = fromLinearLUT[(int)(toLinearLUT[(color >>> 16) & 0xFF] + Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + adj) * strength), -100), 100))] & 255;
                    int bb = fromLinearLUT[(int)(toLinearLUT[(color >>> 8)  & 0xFF] + Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + adj) * strength), -100), 100))] & 255;

                    usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))]) & 255] = true;
                    i++;
                }
            }
        }
    }

    protected void analyzeScatter() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float w1 = Math.min(ditherStrength * 5.5f / palette.populationBias, 16f), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                    er = curErrorRed[px] * tbn;
                    eg = curErrorGreen[px] * tbn;
                    eb = curErrorBlue[px] * tbn;
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeWoven() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        final float w1 = (float) (10f * Math.sqrt(ditherStrength) / (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 100f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                limit = 5f + 250f / (float)Math.sqrt(palette.colorCount+1.5f);

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;
            for (int px = 0; px < width && i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    er = Math.min(Math.max(((((px+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((((px+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorBlue[px]);

                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeDodgy() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float populationBias = palette.populationBias;
        final float w1 = 8f * ditherStrength,
                w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 0.35f * ditherStrength / (populationBias * populationBias * populationBias),
                limit = 90f;

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;
            for (int px = 0; px < width && i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    er = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorBlue[px]);

                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeNeue() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb, adj;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        final float w1 = ditherStrength * 8f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = (70f * ditherStrength / (populationBias * populationBias * populationBias)),
                limit = Math.min(127, (float) Math.pow(80, 1.635 - populationBias));

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;
            for (int px = 0; px < width && i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (py & 63) << 6] + 0.5f) * 0.005f); // plus or minus 255/400
                    adj = Math.min(Math.max(adj * strength, -limit), limit);
                    er = adj + (curErrorRed[px]);
                    eg = adj + (curErrorGreen[px]);
                    eb = adj + (curErrorBlue[px]);
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeWrenOriginal() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        final float w1 = (float) (32.0 * ditherStrength * (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 0.2f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                limit = 5f + 125f / (float)Math.sqrt(palette.colorCount+1.5),
                dmul = 0x1p-8f;

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;
            for (int px = 0; px < width && i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+1) * 0xC13FA9A902A6328FL + (y +1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorRed[px]), -limit), limit);
                    eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+3) * 0xC13FA9A902A6328FL + (y -1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorGreen[px]), -limit), limit);
                    eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+2) * 0xC13FA9A902A6328FL + (y -4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-15f - 0x1p+7f)) * strength) + (curErrorBlue[px]), -limit), limit);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeWren() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float populationBias = palette.populationBias;
        float partialDitherStrength = (0.5f * ditherStrength / (populationBias * populationBias)),
                strength = (80f * ditherStrength / (populationBias * populationBias)),
                blueStrength = (0.3f * ditherStrength / (populationBias * populationBias)),
                limit = 5f + 200f / (float)Math.sqrt(palette.colorCount+1.5f),
                r1, g1, b1, r2, g2, b2, r4, g4, b4;

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int by = 0, i = 0; by < height && i < nPix; by++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            int y = flipped + flipDir * by;
            for (int x = 0; x < width && i < nPix; x++) {
                color = image.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorRed[x]);
                    eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorGreen[x]);
                    eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorBlue[x]);

                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(by+1 < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeOverboard() {
        final int nPix = indexedPixels.length;
        int flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        final float populationBias = palette.populationBias;
        final float strength = ditherStrength * 1.5f * (populationBias * populationBias),
                noiseStrength = 4f / (populationBias * populationBias),
                limit = 110f;

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
            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);
        }

        for (int by = 0, y = flipped, i = 0; by < height && i < nPix; by++, y += flipDir) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, (byte) 0);
            Arrays.fill(nextErrorGreen, (byte) 0);
            Arrays.fill(nextErrorBlue, (byte) 0);

            for (int x = 0; x < width && i < nPix; x++) {
                int color = image.getPixel(x, y);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
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
                    byte paletteIndex;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(by+1 < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeBurkes() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;

        final int w = width;
        byte paletteIndex;
        float r4, r2, r1, g4, g2, g1, b4, b2, b1;
        final float populationBias = palette.populationBias;
        final float s = 0.175f * ditherStrength / (populationBias * populationBias * populationBias),
                strength = s * 0.6f / (0.19f + s);
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

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0, w, 0);
            Arrays.fill(nextErrorGreen, 0, w, 0);
            Arrays.fill(nextErrorBlue, 0, w, 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    float er = curErrorRed[px];
                    float eg = curErrorGreen[px];
                    float eb = curErrorBlue[px];
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeOceanic() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;
        final float[] noise = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS;

        final int w = width;
        byte paletteIndex;
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

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0, w, 0);
            Arrays.fill(nextErrorGreen, 0, w, 0);
            Arrays.fill(nextErrorBlue, 0, w, 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    float er = curErrorRed[px];
                    float eg = curErrorGreen[px];
                    float eb = curErrorBlue[px];
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    protected void analyzeSeaside() {
        final int nPix = indexedPixels.length;
        int color, used, flipped = flipY ? height - 1 : 0, flipDir = flipY ? -1 : 1;
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;
        boolean hasTransparent = paletteArray[0] == 0;
        final float[] noiseA = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS;
        final float[] noiseB = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_B;
        final float[] noiseC = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS_C;

        final int w = width;
        byte paletteIndex;
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

        for (int y = 0, i = 0; y < height && i < nPix; y++) {
            System.arraycopy(nextErrorRed, 0, curErrorRed, 0, w);
            System.arraycopy(nextErrorGreen, 0, curErrorGreen, 0, w);
            System.arraycopy(nextErrorBlue, 0, curErrorBlue, 0, w);

            Arrays.fill(nextErrorRed, 0, w, 0);
            Arrays.fill(nextErrorGreen, 0, w, 0);
            Arrays.fill(nextErrorBlue, 0, w, 0);

            int py = flipped + flipDir * y,
                    ny = y + 1;

            for (int px = 0; px < width & i < nPix; px++) {
                color = image.getPixel(px, py);
                if (hasTransparent && (color & 0x80) == 0) /* if this pixel is less than 50% opaque, draw a pure transparent pixel. */
                    indexedPixels[i++] = 0;
                else {
                    float er = curErrorRed[px];
                    float eg = curErrorGreen[px];
                    float eb = curErrorBlue[px];
                    int rr = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 24)       ] + er, 0), 1023)] & 255;
                    int gg = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 16) & 0xFF] + eg, 0), 1023)] & 255;
                    int bb = fromLinearLUT[(int)Math.min(Math.max(toLinearLUT[(color >>> 8)  & 0xFF] + eb, 0), 1023)] & 255;
                    usedEntry[(indexedPixels[i] = paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
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
                        if(px < w - 3)
                        {
                            modifier = ((px + 3 & 63) | ((py << 6) & 0xFC0));
                            curErrorRed[px+2]   += r1 * noiseA[modifier];
                            curErrorGreen[px+2] += g1 * noiseB[modifier];
                            curErrorBlue[px+2]  += b1 * noiseC[modifier];
                        }
                    }
                    if(ny < height)
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
                    i++;
                }
            }
        }
    }

    /**
     * Analyzes image colors and creates color map.
     */
    protected void analyzePixels() {
        int nPix = width * height;
        indexedPixels = new byte[nPix];
        palette.setDitherStrength(ditherStrength);
        if(seq > 1 && clearPalette)
        {
            if(fastAnalysis)
                palette.analyzeFast(image, 300, 256);
            else
                palette.analyze(image, 300, 256);
        }
        final int[] paletteArray = palette.paletteArray;

        colorTab = new byte[256 * 3]; // create reduced palette
        for (int i = 0, bi = 0; i < 256; i++) {
            int pa = paletteArray[i];
            colorTab[bi++] = (byte) (pa >>> 24);
            colorTab[bi++] = (byte) (pa >>> 16);
            colorTab[bi++] = (byte) (pa >>> 8);
            usedEntry[i] = false;
        }
        // map image pixels to new palette
        boolean hasTransparent = paletteArray[0] == 0;
        switch (ditherAlgorithm) {
            case NONE:
                analyzeNone();
                break;
            case PATTERN:
                analyzePattern();
                break;
            case CHAOTIC_NOISE:
                analyzeChaotic();
                break;
            case GRADIENT_NOISE:
                analyzeGradient();
                break;
            case ROBERTS:
                analyzeRoberts();
                break;
            case LOAF:
                analyzeLoaf();
                break;
            case DIFFUSION:
                analyzeDiffusion();
                break;
            case BLUE_NOISE:
                analyzeBlue();
                break;
            case SCATTER:
                analyzeScatter();
                break;
            case WOVEN:
                analyzeWoven();
                break;
            case DODGY:
                analyzeDodgy();
                break;
            case NEUE:
                analyzeNeue();
                break;
            case WREN:
                analyzeWren();
                break;
            case BURKES:
                analyzeBurkes();
                break;
            case OCEANIC:
                analyzeOceanic();
                break;
            case SEASIDE:
                analyzeSeaside();
                break;
            case GOURD:
                analyzeGourd();
                break;
            case OVERBOARD:
            default:
                analyzeOverboard();
                break;
        }
        colorDepth = 8;
        palSize = 7;
        // get the closest match to transparent color if specified
        if (hasTransparent) {
            transIndex = 0;
        }
    }

    /**
     * Extracts image pixels into byte array "pixels"
     */
    protected void getImagePixels() {
        int w = image.getWidth();
        int h = image.getHeight();
        if ((w != width) || (h != height)) {
            // create new image with right size/format
            Pixmap temp = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            temp.drawPixmap(image, 0, 0);
            image = temp;
        }
    }

    /**
     * Writes Graphic Control Extension
     */
    protected void writeGraphicCtrlExt() throws IOException {
        out.write(0x21); // extension introducer
        out.write(0xf9); // GCE label
        out.write(4); // data block size
        int transp, disp;
        if (transIndex == -1) {
            transp = 0;
            disp = 0; // dispose = no action
        } else {
            transp = 1;
            disp = 2; // force clear if using transparent color
        }
        if (dispose >= 0) {
            disp = dispose & 7; // user override
        }
        disp <<= 2;

        // packed fields
        out.write(0 | // 1:3 reserved
                disp | // 4:6 disposal
                0 | // 7 user input - 0 = none
                transp); // 8 transparency flag

        writeShort(Math.round(delay/10f)); // delay x 1/100 sec
        out.write(transIndex); // transparent color index
        out.write(0); // block terminator
    }

    /**
     * Writes Image Descriptor
     */
    protected void writeImageDesc() throws IOException {
        out.write(0x2c); // image separator
        writeShort(x); // image position x,y = 0,0
        writeShort(y);
        writeShort(width); // image size
        writeShort(height);
        // packed fields
        if (firstFrame) {
            // no LCT - GCT is used for first (or only) frame
            out.write(0);
        } else {
            // specify normal LCT
            out.write(0x80 | // 1 local color table 1=yes
                    0 | // 2 interlace - 0=no
                    0 | // 3 sorted - 0=no
                    0 | // 4-5 reserved
                    palSize); // 6-8 size of color table
        }
    }

    /**
     * Writes Logical Screen Descriptor
     */
    protected void writeLSD() throws IOException {
        // logical screen size
        writeShort(width);
        writeShort(height);
        // packed fields
        out.write((0x80 | // 1 : global color table flag = 1 (gct used)
                0x70 | // 2-4 : color resolution = 7
                0x00 | // 5 : gct sort flag = 0
                palSize)); // 6-8 : gct size

        out.write(0); // background color index
        out.write(0); // pixel aspect ratio - assume 1:1
    }

    /**
     * Writes Netscape application extension to define repeat count.
     */
    protected void writeNetscapeExt() throws IOException {
        out.write(0x21); // extension introducer
        out.write(0xff); // app extension label
        out.write(11); // block size
        writeString("NETSCAPE" + "2.0"); // app id + auth code
        out.write(3); // sub-block size
        out.write(1); // loop sub-block id
        writeShort(repeat); // loop count (extra iterations, 0=repeat forever)
        out.write(0); // block terminator
    }

    /**
     * Writes color table
     */
    protected void writePalette() throws IOException {
        out.write(colorTab, 0, colorTab.length);
        int n = (3 * 256) - colorTab.length;
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
    }

    /**
     * Encodes and writes pixel data
     */
    protected void writePixels() throws IOException {
        LZWEncoder encoder = new LZWEncoder(width, height, indexedPixels, colorDepth);
        encoder.encode(out);
    }

    /**
     * Write 16-bit value to output stream, LSB first
     */
    protected void writeShort(int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    /**
     * Writes string to output stream
     */
    protected void writeString(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            out.write((byte) s.charAt(i));
        }
    }

    /**
     * If true (the default) and {@link #palette} is null, this uses a lower-quality but much-faster algorithm to
     * analyze the color palette in each frame; if false and palette is null, then this uses the normal algorithm for
     * still images on each frame separately. The case when this is false can be 4x to 5x slower than the case when it
     * is true, but it can produce higher-quality animations. This is ignored for single-frame GIFs, and the still-image
     * algorithm is always used there when palette is null.
     */
    public boolean isFastAnalysis() {
        return fastAnalysis;
    }

    public boolean setFastAnalysis(boolean fastAnalysis) {
        return this.fastAnalysis = fastAnalysis;
    }
}


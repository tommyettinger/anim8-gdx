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
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * GIF encoder using standard LZW compression; can write animated and non-animated GIF images.
 * This is a variant on {@link AnimatedGif} that reads pixels in directly from a ByteBuffer, rather than retrieving each
 * pixel by its position. An instance can be reused to encode multiple GIFs with minimal allocation.
 * <br>
 * This class is different from {@link AnimatedGif} in a few ways. First is that this isn't GWT-compatible, but
 * AnimatedGif (probably?) is. It would be a bit of a challenge to actually test writing files on GWT, but is possible.
 * Second is that this class reads in pixels byte-by-byte from the {@link Pixmap#getPixels()} ByteBuffer, instead of
 * reading in pixels 32-bits-at-a-time using {@link Pixmap#getPixel(int, int)}, which can be slower. Third is that
 * {@link #setFlipY(boolean)} does nothing here, but works in AnimatedGif; this is a consequence of how bytes are read.
 * There may be other minor changes, but not much is different -- hopefully this class is faster on most input data, but
 * it could very well be slower for certain types of input.
 * <br>
 * You can configure the target palette and how this can dither colors via the {@link #palette} field, which is a
 * {@link PaletteReducer} object that defaults to null and can be reused. If you assign a PaletteReducer to palette, the
 * methods {@link PaletteReducer#exact(Color[])} or {@link PaletteReducer#analyze(Pixmap)} can be used to make the
 * target palette match a specific set of colors or the colors in an existing image. If palette is null, this will
 * compute a palette for each GIF that closely fits its set of given animation frames. If the palette isn't an exact
 * match for the colors used in an animation (indexed mode has at most 256 colors), this will dither pixels so that from
 * a distance, they look closer to the original colors. You can us {@link PaletteReducer#setDitherStrength(float)} to
 * reduce (or increase) dither strength, typically between 0 and 2; the dithering algorithm used here by default is
 * based on Floyd-Steinberg error-diffusion dithering but with patterns broken up using blue noise
 * ({@link DitherAlgorithm#SCATTER}), but you can select alternatives with {@link #setDitherAlgorithm(DitherAlgorithm)},
 * such as the slow but high-quality Knoll Ordered Dither using {@link DitherAlgorithm#PATTERN}, or no dither at all
 * with {@link DitherAlgorithm#NONE}.
 * <br>
 * You can write non-animated GIFs with this, but libGDX can't read them back in, so you may want to prefer {@link PNG8}
 * or {@link FastPNG} for images with 256 or fewer colors and no animation (libGDX can read in non-animated PNG files,
 * as well as the first frame of animated PNG files). If you have an animation that doesn't look good with dithering or
 * has multiple levels of transparency (GIF only supports one fully transparent color), you can use {@link AnimatedPNG}
 * or {@link FastAPNG} to output a full-color animation. If you have a non-animated image that you want to save in
 * lossless full-color, you can use {@link FastPNG}. You could use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}
 * instead; the PNG code here is based on it, and although it isn't as fast to write files, they are better-compressed.
 * <br>
 * Based on Nick Badal's Android port ( https://github.com/nbadal/android-gif-encoder/blob/master/GifEncoder.java ) of
 * Alessandro La Rossa's J2ME port ( http://www.jappit.com/blog/2008/12/04/j2me-animated-gif-encoder/ ) of this pure
 * Java animated GIF encoder by Kevin Weiner ( http://www.java2s.com/Code/Java/2D-Graphics-GUI/AnimatedGifEncoder.htm ).
 * The original has no copyright asserted, so this file continues that tradition and does not assert copyright either.
 */
public class FastGif implements AnimationWriter, Dithered {
    /**
     * Writes the given Pixmap values in {@code frames}, in order, to an animated GIF at {@code file}. Always writes at
     * 30 frames per second, so if frames has less than 30 items, this animation will be under a second long.
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
     * animation, regardless of fastAnalysis' setting, if there is more than one Pixmap in frames.
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
                palette.analyzeFast(frames.first(), 100, 256);
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

    protected DitherAlgorithm ditherAlgorithm = DitherAlgorithm.NEUE;

    protected int width; // image size

    protected int height;

    protected int x = 0;

    protected int y = 0;

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

    private boolean clearPalette;

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
     * Gets this FastGif's dither strength, which will override the {@link PaletteReducer#getDitherStrength()} in
     * the PaletteReducer this uses. This applies even if {@link #getPalette()} is null; in that case, when a temporary
     * PaletteReducer is created, it will use this dither strength.
     * @return the current dither strength override
     */
    public float getDitherStrength() {
        return ditherStrength;
    }

    /**
     * Sets this FastGif's dither strength, which will override the {@link PaletteReducer#getDitherStrength()} in
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
     * Always returns false; flipping vertically is not supported by the FastXYZ classes.
     * @return always false
     */
    public boolean isFlipY() {
        return false;
    }

    /**
     * A no-op; this class never flips the image, regardless of the setting.
     * @param flipY ignored
     */
    public void setFlipY(boolean flipY) {
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
     * @param im BufferedImage containing frame to write.
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
            analyzePixels(); // build color table & map pixels
            if (firstFrame) {
                writeLSD(); // logical screen descriptior
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
                palette.analyzeFast(image, 100, 256);
            else
                palette.analyze(image, 100, 256);
        }
        final int[] paletteArray = palette.paletteArray;
        final byte[] paletteMapping = palette.paletteMapping;

        colorTab = new byte[256 * 3]; // create reduced palette
        for (int i = 0, bi = 0; i < 256; i++) {
            int pa = paletteArray[i];
            colorTab[bi++] = (byte) (pa >>> 24);
            colorTab[bi++] = (byte) (pa >>> 16);
            colorTab[bi++] = (byte) (pa >>> 8);
            usedEntry[i] = false;
        }
        // map image pixels to new palette
        int used;
        ByteBuffer pixels = image.getPixels();
        boolean hasTransparent = image.getFormat().equals(Pixmap.Format.RGBA8888);
        switch (ditherAlgorithm) {
            case NONE: {
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            usedEntry[(indexedPixels[i] = paletteMapping[
                                      (r << 7 & 0x7C00)
                                    | (g << 2 & 0x3E0)
                                    | (b >>> 3 & 0x1F)]) & 255] = true;
                            i++;
                        }
                    }
                }
            }
            break;
            case PATTERN:
            {
                int cr, cg, cb, usedIndex;
                final float errorMul = palette.ditherStrength * palette.populationBias;
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        cr = pixels.get() & 255;
                        cg = pixels.get() & 255;
                        cb = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            int er = 0, eg = 0, eb = 0;
                            for (int c = 0; c < 16; c++) {
                                int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                                int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                                int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                                usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                        | ((gg << 2) & 0x3E0)
                                        | ((bb >>> 3))] & 0xFF;
                                palette.candidates[c | 16] = PaletteReducer.shrink(palette.candidates[c] = used = paletteArray[usedIndex]);
                                er += cr - (used >>> 24);
                                eg += cg - (used >>> 16 & 0xFF);
                                eb += cb - (used >>> 8 & 0xFF);
                            }
                            PaletteReducer.sort16(palette.candidates);
                            usedEntry[(indexedPixels[i] =  (byte) palette.reverseMap.get(palette.candidates[
                                            PaletteReducer.thresholdMatrix16[((px & 3) | (y & 3) << 2)]], 1)
                            ) & 255] = true;
                            i++;

                        }
                    }
                }
            }
            break;
            case CHAOTIC_NOISE: {
                double adj, strength = palette.ditherStrength * palette.populationBias * 1.5;
                long s = 0xC13FA9A902A6328FL * seq;
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            used = paletteArray[paletteMapping[((r << 7) & 0x7C00)
                                    | ((g << 2) & 0x3E0)
                                    | ((b >>> 3))] & 0xFF];
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
                                            ((s = (s ^ r + g + b) * 0xD1342543DE82EF95L + 0x91E10DA5C79E7B1DL) >> 15));
                            r = Math.min(Math.max((int) (r + (adj * ((r - (used >>> 24))))), 0), 0xFF);
                            g = Math.min(Math.max((int) (g + (adj * ((g - (used >>> 16 & 0xFF))))), 0), 0xFF);
                            b = Math.min(Math.max((int) (b + (adj * ((b - (used >>> 8 & 0xFF))))), 0), 0xFF);
                            usedEntry[(indexedPixels[i] = paletteMapping[((r << 7) & 0x7C00)
                                    | ((g << 2) & 0x3E0)
                                    | ((b >>> 3))]) & 255] = true;
                            i++;
                        }
                    }
                }
            }
            break;
            case GRADIENT_NOISE: {
                float adj;
                final float strength = 60f * palette.ditherStrength / (palette.populationBias * palette.populationBias);
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            adj = (px * 0.06711056f + y * 0.00583715f);
                            adj -= (int) adj;
                            adj *= 52.9829189f;
                            adj -= (int) adj;
                            adj = (adj-0.5f) * strength;
                            int rr = Math.min(Math.max((int)(r + adj), 0), 255);
                            int gg = Math.min(Math.max((int)(g + adj), 0), 255);
                            int bb = Math.min(Math.max((int)(b + adj), 0), 255);
                            usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                            i++;
                        }
                    }
                }
            }
            break;
            case ROBERTS: {
                final float populationBias = palette.populationBias;
                final float str = (20f * ditherStrength / (populationBias * populationBias * populationBias * populationBias));
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            int rr = Math.min(Math.max((int)(r + ((((px-1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);
                            int gg = Math.min(Math.max((int)(g + ((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);
                            int bb = Math.min(Math.max((int)(b + ((((px+2) * 0xC13FA9A902A6328FL + (y+3) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);
                            usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                            i++;
                        }
                    }
                }
            }
            break;
            case DIFFUSION: {
                final int w = width;
                float rdiff, gdiff, bdiff;
                float er, eg, eb;
                byte paletteIndex;
                float w1 = palette.ditherStrength * 4, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

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

                    int ny = y + 1;

                    for (int px = 0; px < width & i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            er = curErrorRed[px];
                            eg = curErrorGreen[px];
                            eb = curErrorBlue[px];
                            int rr = Math.min(Math.max((int)(r + er + 0.5f), 0), 0xFF);
                            int gg = Math.min(Math.max((int)(g + eg + 0.5f), 0), 0xFF);
                            int bb = Math.min(Math.max((int)(b + eb + 0.5f), 0), 0xFF);
                            usedEntry[(indexedPixels[i] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))]) & 255] = true;
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (0x1.8p-8f * (r - (used>>>24))    );
                            gdiff = (0x1.8p-8f * (g - (used>>>16&255)));
                            bdiff = (0x1.8p-8f * (b - (used>>>8&255)) );
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
            break;
            case BLUE_NOISE: {
                float adj, strength = 0.1375f * palette.ditherStrength / palette.populationBias;
                for (int y = 0, i = 0; y < height && i < nPix; y++) {
                    for (int px = 0; px < width & i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            float pos = (PaletteReducer.thresholdMatrix64[(px & 7) | (y & 7) << 3] - 31.5f) * 0.2f;
                            adj = ((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f) * strength) + pos;
                            int rr = Math.min(Math.max((int) (adj + r),  0),  255);
                            adj = ((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f) * strength) + pos;
                            int gg = Math.min(Math.max((int) (adj + g),  0),  255);
                            adj = ((PaletteReducer.TRI_BLUE_NOISE_D[(px & 63) | (y & 63) << 6] + 0.5f) * strength) + pos;
                            int bb = Math.min(Math.max((int) (adj + b),  0),  255);
                            usedEntry[(indexedPixels[i] = paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))]) & 255] = true;
                            i++;
                        }
                    }
                }
            }
            break;
            case SCATTER: {
                final int w = width;
                float rdiff, gdiff, bdiff;
                float er, eg, eb;
                byte paletteIndex;
                float w1 = palette.ditherStrength * 3.5f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;

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

                    int ny = y + 1;

                    for (int px = 0; px < width & i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                            er = curErrorRed[px] * tbn;
                            eg = curErrorGreen[px] * tbn;
                            eb = curErrorBlue[px] * tbn;
                            int rr = Math.min(Math.max((int)(r + er + 0.5f), 0), 0xFF);
                            int gg = Math.min(Math.max((int)(g + eg + 0.5f), 0), 0xFF);
                            int bb = Math.min(Math.max((int)(b + eb + 0.5f), 0), 0xFF);
                            usedEntry[(indexedPixels[i] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))]) & 255] = true;
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (0x2.Ep-8f * (r - (used>>>24))    );
                            gdiff = (0x2.Ep-8f * (g - (used>>>16&255)));
                            bdiff = (0x2.Ep-8f * (b - (used>>>8&255)) );
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
            break;
            case WOVEN: {
                final int w = width;
                float rdiff, gdiff, bdiff;
                float er, eg, eb;
                byte paletteIndex;
                final float populationBias = palette.populationBias;
                float w1 = (float) (20f * Math.sqrt(ditherStrength) * populationBias * populationBias * populationBias * populationBias), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                        strength = 48f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                        limit = 5f + 130f / (float)Math.sqrt(palette.colorCount+1.5f);

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

                    int ny = y + 1;
                    for (int px = 0; px < width && i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            er = Math.min(Math.max(((((px+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorRed[px]);
                            eg = Math.min(Math.max(((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorGreen[px]);
                            eb = Math.min(Math.max(((((px+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorBlue[px]);

                            int rr = Math.min(Math.max((int)(r + er + 0.5f), 0), 0xFF);
                            int gg = Math.min(Math.max((int)(g + eg + 0.5f), 0), 0xFF);
                            int bb = Math.min(Math.max((int)(b + eb + 0.5f), 0), 0xFF);
                            usedEntry[(indexedPixels[i] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))]) & 255] = true;
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (0x5p-10f * (r - (used>>>24))    );
                            gdiff = (0x5p-10f * (g - (used>>>16&255)));
                            bdiff = (0x5p-10f * (b - (used>>>8&255)) );
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
            break;
            default:
            case NEUE: {
                final int w = width;
                float rdiff, gdiff, bdiff;
                float er, eg, eb;
                byte paletteIndex;
                float w1 = palette.ditherStrength * 7f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                        adj, strength = (32f * palette.ditherStrength / (palette.populationBias * palette.populationBias)),
                        limit = (float) Math.pow(80, 1.635 - palette.populationBias);

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

                    int ny = y + 1;
                    for (int px = 0; px < width && i < nPix; px++) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        if (hasTransparent && (pixels.get() & 0x80) == 0)
                            indexedPixels[i++] = 0;
                        else {
                            adj = ((PaletteReducer.TRI_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.005f); // plus or minus 255/400
                            adj = Math.min(Math.max(adj * strength, -limit), limit);
                            er = adj + (curErrorRed[px]);
                            eg = adj + (curErrorGreen[px]);
                            eb = adj + (curErrorBlue[px]);

                            int rr = Math.min(Math.max((int)(r + er + 0.5f),  0),  0xFF);
                            int gg = Math.min(Math.max((int)(g + eg + 0.5f),  0),  0xFF);
                            int bb = Math.min(Math.max((int)(b + eb + 0.5f),  0),  0xFF);
                            usedEntry[(indexedPixels[i] = paletteIndex =
                                    paletteMapping[((rr << 7) & 0x7C00)
                                            | ((gg << 2) & 0x3E0)
                                            | ((bb >>> 3))]) & 255] = true;
                            used = paletteArray[paletteIndex & 0xFF];
                            rdiff = (0x1.7p-10f * (r - (used>>>24))    );
                            gdiff = (0x1.7p-10f * (g - (used>>>16&255)));
                            bdiff = (0x1.7p-10f * (b - (used>>>8&255)) );
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
            break;
        }
        pixels.rewind();
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
}


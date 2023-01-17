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
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * reduce (or increase) dither strength, typically between 0 and 2; the dithering algorithm used here by default is
 * based on Floyd-Steinberg error-diffusion dithering but with patterns broken up using blue noise
 * ({@link DitherAlgorithm#SCATTER}), but you can select alternatives with {@link #setDitherAlgorithm(DitherAlgorithm)},
 * such as the slow but high-quality Knoll Ordered Dither using {@link DitherAlgorithm#PATTERN}, or no dither at all
 * with {@link DitherAlgorithm#NONE}.
 * <br>
 * You can write non-animated GIFs with this, but libGDX can't read them back in, so you may want to prefer {@link PNG8}
 * for images with 256 or fewer colors and no animation (libGDX can read in non-animated PNG files, as well as the first
 * frame of animated PNG files). If you have an animation that doesn't look good with dithering or has multiple levels
 * of transparency (GIF only supports one fully transparent color), you can use {@link AnimatedPNG} to output a
 * full-color animation. If you have a non-animated image that you want to save in lossless full-color, just use
 * {@link com.badlogic.gdx.graphics.PixmapIO.PNG}; the API is slightly different, but the PNG code here is based on it.
 * <br>
 * Based on Nick Badal's Android port ( https://github.com/nbadal/android-gif-encoder/blob/master/GifEncoder.java ) of
 * Alessandro La Rossa's J2ME port ( http://www.jappit.com/blog/2008/12/04/j2me-animated-gif-encoder/ ) of this pure
 * Java animated GIF encoder by Kevin Weiner ( http://www.java2s.com/Code/Java/2D-Graphics-GUI/AnimatedGifEncoder.htm ).
 * The original has no copyright asserted, so this file continues that tradition and does not assert copyright either.
 */
public class AnimatedGif implements AnimationWriter, Dithered {
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
        addFrames(frames);
        finish();
        if(clearPalette)
            palette = null;
    }

    protected DitherAlgorithm ditherAlgorithm = DitherAlgorithm.NEUE;
    
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

    protected Array<Pixmap> images; // all frames

    protected ForkJoinPool pool = new ForkJoinPool();

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
     * Adds array of GIF. Invoking <code>finish()</code> flushes all frames. If
     * <code>setSize</code> was not invoked, the size of the first image is used
     * for all subsequent frames.
     *
     * @param ims BufferedImage containing frame to write.
     * @return true if successful.
     */

    public boolean addFrames(Array<Pixmap> ims) {
        if ((ims == null) || !started) {
            return false;
        }
        Array<AnalyzedPixmap> analyzedPixmapArray = new Array<>(ims.size);
        boolean ok = true;
        try {
            if (!sizeSet) {
                // use first frame's size
                setSize(ims.get(0).getWidth(), ims.get(0).getHeight());
            }
            images = ims;
            colorDepth = 8;
            AnalyzeTaskRecursiveOptimized analyzeTask = new AnalyzeTaskRecursiveOptimized(0, images.size, images);
            Logger.getGlobal().log(Level.INFO, "Analyze: Number of active thread before invoking: " + pool.getActiveThreadCount());
            Logger.getGlobal().log(Level.INFO, "Analyze: Available Processors: " + Runtime.getRuntime().availableProcessors());
            Logger.getGlobal().log(Level.INFO, "Analyze: Pool parallelism: " + pool.getParallelism());
            Array<AnalyzedPixmap> arr = pool.invoke(analyzeTask);
            Logger.getGlobal().log(Level.INFO, "Analyze: Number of active thread after invoking: " + pool.getActiveThreadCount());
            Logger.getGlobal().log(Level.INFO, "Analyze: Pool size: " + pool.getPoolSize());
            analyzedPixmapArray.addAll(arr);
            analyzeTask.clearAnalyzedPixmapArrayAndSeqFromTasks();
            for (int i = 0; i < analyzedPixmapArray.size; i++) {
                if (firstFrame) {
                    writeLSD(); // logical screen description !!!! write
                    writePalette(analyzedPixmapArray.get(0).getColorTab()); // global color table !!!! write
                    if (repeat >= 0) {
                        // use NS app extension to indicate reps !!!! write
                        writeNetscapeExt();
                    }
                }
                writeGraphicCtrlExt(); // write graphic control extension !!!! write
                writeImageDesc(); // image descriptor !!!! write
                if (!firstFrame) {
                    writePalette(analyzedPixmapArray.get(i).getColorTab()); // local color table !!!! write
                }
                writePixels(analyzedPixmapArray.get(i).getIndexedPixels()); // encode and write pixel data !!!! write
                firstFrame = false;
            }
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
        images = null;
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
    protected void writePalette(byte[] colorTab) throws IOException {
        out.write(colorTab, 0, colorTab.length);
        int n = (3 * 256) - colorTab.length;
        for (int i = 0; i < n; i++) {
            out.write(0);
        }
    }

    /**
     * Encodes and writes pixel data
     */
    protected void writePixels(byte[] indexedPixels) throws IOException {
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


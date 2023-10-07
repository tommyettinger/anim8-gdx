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
import com.badlogic.gdx.utils.Array;

import java.io.OutputStream;

/**
 * PNG-8 encoder with compression; can write animated and non-animated PNG images in indexed-mode.
 * An instance can be reused to encode multiple PNGs with minimal allocation.
 * This is a subclass of {@link PNG8} that defaults to using a {@link FastPalette} when possible. An instance can be
 * reused to encode multiple PNGs with minimal allocation.
 * <br>
 * You can configure the target palette and how this can dither colors via the {@link #palette} field, which is a
 * {@link PaletteReducer} object (almost always a {@link FastPalette}) that defaults to null and can be reused. If you
 * assign a PaletteReducer to palette, the methods {@link PaletteReducer#exact(Color[])} or
 * {@link PaletteReducer#analyze(Pixmap)} can be used to make the
 * target palette match a specific set of colors or the colors in an existing image. If palette is null, this will
 * compute a palette for each PNG that closely fits its set of given animation frames. If the palette isn't an exact
 * match for the colors used in an animation (indexed mode has at most 256 colors), this will dither pixels so that from
 * a distance, they look closer to the original colors. You can us {@link PaletteReducer#setDitherStrength(float)} to
 * reduce (or increase) dither strength, typically between 0 and 2; the dithering algorithm used here by default is
 * based on Burkes error-diffusion dithering but with patterns broken up using blue noise and the R2 sequence
 * ({@link DitherAlgorithm#WREN}), but you can select alternatives with {@link #setDitherAlgorithm(DitherAlgorithm)},
 * such as the slow but high-quality Knoll Ordered Dither using {@link DitherAlgorithm#PATTERN}, or no dither at all
 * with {@link DitherAlgorithm#NONE}.
 * <br>
 * This defaults to using a relatively low amount of compression, which makes the files this writes larger.
 * You can use {@link #setCompression(int)} to lower compression from the default of 2, down to 1 or 0, or raise it to
 * PNG8's default of 6 or as high as 9. Unlike PNG8, writing with compression level 0 can be much faster than even
 * compression level 1, but the files are as large as they can get. If the PNG files are intended to be recompressed
 * anyway (with the same DEFLATE algorithm), which is the case if a file will go in a JAR or ZIP archive, then using 0
 * compression may make sense, because DEFLATE applied twice is about the same as if applied once.
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
 * @author Tommy Ettinger (PNG-8 parts only)
 */
public class FastPNG8 extends PNG8 {
    public FastPNG8() {
        this(128 * 128);
    }

    public FastPNG8(int initialBufferSize) {
        super(initialBufferSize);
        setCompression(2);
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
            palette = new FastPalette(pixmap, threshold);
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
     * @param output an OutputStream that will not be closed
     * @param frames a Pixmap Array to write as a sequence of frames to the given output stream
     * @param fps    how many frames per second the animation should run at
     * @param dither true if this should use {@link #getDitherAlgorithm()} to dither; false to not dither
     */
    @Override
    public void write(OutputStream output, Array<Pixmap> frames, int fps, boolean dither) {
        boolean clearPalette;
        if(clearPalette = (palette == null))
            palette = new FastPalette(frames);
        palette.setDitherStrength(ditherStrength);
        if (dither)
            write(output, frames, fps);
        else
            writeSolid(output, frames, fps);
        if(clearPalette) palette = null;
        ;
    }
}

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

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;

import java.io.OutputStream;

/**
 * GIF encoder using standard LZW compression; can write animated and non-animated GIF images.
 * This is a subclass of {@link AnimatedGif} that defaults to using a {@link FastPalette} when possible and defaults to
 * having {@link #fastAnalysis} enabled. An instance can be reused to encode multiple GIFs with minimal allocation.
 * <br>
 * You can configure the target palette and how this can dither colors via the {@link #palette} field, which is a
 * {@link PaletteReducer} object that defaults to null and can be reused. If you assign a PaletteReducer to palette, the
 * methods {@link PaletteReducer#exact(Color[])} or {@link PaletteReducer#analyze(Pixmap)} can be used to make the
 * target palette match a specific set of colors or the colors in an existing image. If palette is null, this will use
 * a {@link FastPalette} subclass of PaletteReducer, and will compute a palette for each GIF that closely fits its set
 * of given animation frames. If the palette isn't an exact match for the colors used in an animation (indexed mode has
 * at most 256 colors), this will dither pixels so that from a distance, they look closer to the original colors. You
 * can use {@link PaletteReducer#setDitherStrength(float)} to reduce (or increase) dither strength, typically between 0
 * and 2; the dithering algorithm used here by default is based on Floyd-Steinberg error-diffusion dithering but with
 * patterns broken up using the R2 sequence and blue noise ({@link DitherAlgorithm#WREN}), but you can select
 * alternatives with {@link #setDitherAlgorithm(DitherAlgorithm)}, such as the slow but high-quality Knoll Ordered
 * Dither using {@link DitherAlgorithm#PATTERN}, or no dither at all with {@link DitherAlgorithm#NONE}.
 * <br>
 * You can write non-animated GIFs with this, but libGDX can't read them back in, so you may want to prefer {@link PNG8}
 * or {@link FastPNG} for images with 256 or fewer colors and no animation (libGDX can read in non-animated PNG files,
 * as well as the first frame of animated PNG files). If you have an animation that doesn't look good with dithering or
 * has multiple levels of transparency (GIF only supports one fully transparent color), you can use {@link AnimatedPNG}
 * to output a full-color animation. If you have a non-animated image that you want to save in
 * lossless full-color, you can use {@link FastPNG}. You could use {@link com.badlogic.gdx.graphics.PixmapIO.PNG}
 * instead; the PNG code here is based on it, and although it isn't as fast to write files, they are better-compressed.
 * <br>
 * Based on <a href="https://github.com/nbadal/android-gif-encoder/blob/master/GifEncoder.java">Nick Badal's Android port</a> of
 * <a href="http://www.jappit.com/blog/2008/12/04/j2me-animated-gif-encoder/">Alessandro La Rossa's J2ME port</a> of this pure
 * Java <a href="http://www.java2s.com/Code/Java/2D-Graphics-GUI/AnimatedGifEncoder.htm">animated GIF encoder by Kevin Weiner</a>.
 * The original has no copyright asserted, so this file continues that tradition and does not assert copyright either.
 */
public class FastGif extends AnimatedGif {
    /**
     * Writes the given Pixmap values in {@code frames}, in order, to an animated GIF in the OutputStream
     * {@code output}. The resulting GIF will play back at {@code fps} frames per second. If {@link #palette}
     * is null, {@link #fastAnalysis} is set to false, and frames contains 2 or more Pixmaps, then this will
     * make a palette for the first frame using {@link FastPalette#analyze(Pixmap)}, then reuse that FastPalette
     * but recompute a different analyzed palette for each subsequent frame. This results in the
     * highest-quality color quantization for any given frame, but is relatively slow; it takes over 4x as long
     * when the palette is null and fastAnalysis is false vs. when the palette was analyzed all-frames-at-once with
     * {@link FastPalette#analyze(Array)}. An alternative is to use a null palette and set fastAnalysis to true,
     * which is the default when frames has 2 or more Pixmaps. This does a very quick analysis of the colors in each
     * frame, which is usually good enough, and takes about the same time as analyzing all frames as one FastPalette.
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
                palette = new FastPalette();
                palette.analyzeFast(frames.first(), 100, 256);
            }
            else
                palette = new FastPalette(frames.first());
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

}


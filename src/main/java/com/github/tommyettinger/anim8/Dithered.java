package com.github.tommyettinger.anim8;

/**
 * A renderer and/or writer that allows selecting a {@link DitherAlgorithm} for its output.
 * <br>
 * Created by Tommy Ettinger on 6/6/2020.
 */
public interface Dithered {

    /**
     * Gets the PaletteReducer this uses to lower the color count in an image. If the PaletteReducer is null, this
     * should try to assign itself a PaletteReducer when given a new image.
     * @return the PaletteReducer this uses; may be null
     */
    PaletteReducer getPalette();

    /**
     * Sets the PaletteReducer this uses to bring a high-color or different-palette image down to a smaller palette
     * size. If {@code palette} is null, this should try to assign itself a PaletteReducer when given a new image.
     * @param palette a PaletteReducer that is often pre-configured with a specific palette; null is usually allowed
     */
    void setPalette(PaletteReducer palette);
    
    /**
     * Gets the {@link DitherAlgorithm} this is currently using.
     * @return which dithering algorithm this currently uses.
     */
    DitherAlgorithm getDitherAlgorithm();
    /**
     * Sets the dither algorithm (or disables it) using an enum constant from {@link DitherAlgorithm}. If this is given
     * null, it instead does nothing.
     * @param ditherAlgorithm which {@link DitherAlgorithm} to use for upcoming output
     */
    void setDitherAlgorithm(DitherAlgorithm ditherAlgorithm);

    /**
     * Represents a choice of dithering algorithm to apply when writing a high-color image with a color-limited format.
     * Options are NONE (just using solid blocks of the closest color), GRADIENT_NOISE (using an edit on Jorge Jimenez'
     * Gradient Interleaved Noise, a kind of ordered dither that adds some visual noise to break up patterns), PATTERN
     * (using a skewed variant on Thomas Knoll's Pattern Dithering, with some gamma correction applied), DIFFUSION (an
     * error-diffusing dither using Floyd-Steinberg, which isn't optimal for animations but is very good for still
     * images), BLUE_NOISE (an ordered dither that corrects mismatched colors by checking a blue noise texture with
     * no noticeable large patterns, and also using a quasi-random pattern to further break up artifacts; this looks the
     * best for realistic animations), CHAOTIC_NOISE (which is like BLUE_NOISE but makes each frame of an animation
     * dither differently, which can look busy but also trick the eye into seeing details over several frames), and
     * SCATTER (which is similar to DIFFUSION but uses blue noise to scatter overly-regular patterns around). While
     * NONE, GRADIENT_NOISE, BLUE_NOISE, DIFFUSION, CHAOTIC_NOISE, and SCATTER maintain the approximate lightness
     * balance of the original image, PATTERN may lighten mid-tones somewhat to make the gradient smoother. All of these
     * algorithms except DIFFUSION are suitable for animations; using error-diffusion makes tiny changes in some frames
     * disproportionately affect other pixels in those frames, which is compounded by how DIFFUSION can have large
     * sections of minor artifacts that become very noticeable when they suddenly change between frames. Using SCATTER
     * may be a good alternative to DIFFUSION for animations. NONE is fastest, and PATTERN is slowest. GRADIENT_NOISE,
     * BLUE_NOISE, DIFFUSION, CHAOTIC_NOISE, and SCATTER are in-between.
     * <br>
     * Created by Tommy Ettinger on 6/6/2020.
     */
    enum DitherAlgorithm {
        /**
         * Doesn't dither at all; this generally looks bad unless the palette matches the colors in the image very
         * closely or exactly.
         */
        NONE,
        /**
         * Jorge Jimenez' Gradient Interleaved Noise, modified slightly to use as an ordered dither here; this can be,
         * well, noisy, but doesn't have different amounts of noise on different frames or different parts of an image
         * (which is a potential problem for {@link #DIFFUSION}). There's a sometimes-noticeable diagonal line pattern
         * in the results this produces, and in animations, this pattern appears in the same place on every frame, which
         * can be either desirable (small changes won't snowball into big ones) or undesirable (it makes the pattern
         * appear to be part of the image). Although {@link #BLUE_NOISE} is mostly similar, it can't have its strength
         * effectively adjusted using {@link PaletteReducer#setDitherStrength(float)}, while this very much can.
         * {@link #BLUE_NOISE} does have less noticeable patterns, though.
         */
        GRADIENT_NOISE,
        /**
         * Thomas Knoll's Pattern Dither (with a skew to obscure grid artifacts), as originally described by Joel
         * Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">this dithering article</a>. Pattern
         * Dither was patented until late 2019, so Yliluoma had to use an 8x8 matrix instead of the 4x4 used here; the
         * 4x4, with appropriate skew, is much faster to compute and doesn't have as many artifacts with large enough
         * palettes. This implementation doesn't have gamma correction working quite correctly, so it tends to lighten
         * images it dithers somewhat. It's an ordered dither, like {@link #GRADIENT_NOISE}, but isn't nearly as noisy
         * (though it isn't noisy, it instead has regular triangular-grid artifacts, though they're often subtle).
         * Pattern Dither was the default for most purposes here, but the constant mismatch between input lightness and
         * output lightness, as well as the artifact-like regular lines on the dither, make it more of a specialized
         * tool. {@link #BLUE_NOISE} is the current default; it does a better job at obscuring artifacts from dither and
         * it maintains lightness well. Setting the dither strength with {@link PaletteReducer#setDitherStrength(float)}
         * can really change how strongly artifacts appear here, as well as how severe the lightness mismatch is. 
         */
        PATTERN,
        /**
         * Floyd-Steinberg error-diffusion dithering; this is the best option for still images, and it's an OK option
         * for some animated images. It doesn't lighten the image like {@link #PATTERN}, while still preserving most
         * details on shapes, but small changes in one part of an animation will affect different frames very
         * differently (which makes this less well-suited for animations). It may look better even in an animation than
         * {@link #GRADIENT_NOISE}, depending on the animation, but this isn't often. Setting the dither strength with
         * {@link PaletteReducer#setDitherStrength(float)} can improve the results with DIFFUSION tremendously, but the
         * dither strength shouldn't go above about 1.5 or maybe 2.0 (this shows artifacts at higher strength).
         */
        DIFFUSION,
        /**
         * This is an ordered dither that modifies any error in a pixel's color by using a blue-noise pattern and a
         * checkerboard pattern. If a pixel is perfectly matched by the palette, this won't change it, but otherwise the
         * position will be used for both the checkerboard and a lookup into a 64x64 blue noise texture (stored as a
         * byte array), and the resulting positive or negative value will be multiplied by the error for that pixel.
         * This yields closer results to {@link #PATTERN} than other ordered dithers like {@link #GRADIENT_NOISE};
         * though it doesn't preserve soft gradients quite as well, it keeps lightness as well as {@link #DIFFUSION} and
         * {@link #SCATTER} do, and it doesn't add as many artifacts as {@link #PATTERN} or {@link #GRADIENT_NOISE}. For
         * reference, the blue noise texture this uses looks like <a href="https://i.imgur.com/YCSKKGw.png">this small
         * image</a>; it looks different from a purely-random white noise texture because blue noise has no low
         * frequencies in any direction, while white noise has all frequencies in equal measure. This has been optimize
         * for quality on animations more so than on still images. Setting the dither strength with
         * {@link PaletteReducer#setDitherStrength(float)} does have some effect (it didn't do much in previous
         * versions), and can improve the appearance of some images when banding occurs.
         */
        BLUE_NOISE,
        /**
         * Very similar to {@link #BLUE_NOISE} for a still frame, albeit less orderly, but in an animation this will
         * change wildly from frame to frame, taking an ordered dither (one which uses the same blue noise texture that
         * {@link #BLUE_NOISE} does) and incorporating one of the qualities of an error-diffusion dither to make each
         * frame dither differently. This can look very good for moving images, but it is, true to its name, both
         * chaotic and noisy. Setting the dither strength with
         * {@link PaletteReducer#setDitherStrength(float)} won't do much here, but the result will have more of a
         * regular blue-noise pattern when dither strength is very low, and small changes will be introduced as dither
         * strength approaches 1.
         */
        CHAOTIC_NOISE,
        /**
         * The yin to to {@link #CHAOTIC_NOISE}'s yang; where chaotic noise actively scrambles pixels using uniform blue
         * noise and some slightly-error-diffusion-like qualities to act as if it had a white noise source, this tries
         * to subtly alter the more rigidly-defined error-diffusion dither of {@link #DIFFUSION} with a small amount of
         * triangular-distributed blue noise, and doesn't introduce white noise. This offers generally the best mix of
         * shape preservation, color preservation, animation-compatibility, and speed, so it's the current default.
         * Setting the dither strength to a low value makes this more bold, with higher contrast, while setting the
         * strength too high (above 1.25, or sometimes higher) can introduce artifacts.
         */
        SCATTER
    }
}

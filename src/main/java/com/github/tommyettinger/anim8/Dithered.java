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
     * (using a skewed variant on Thomas Knoll's Pattern Dithering, with some gamma correction applied), and DIFFUSION
     * (an error-diffusing dither using Floyd-Steinberg, which isn't optimal for animations but is very good for still
     * images). While NONE, GRADIENT_NOISE, and DIFFUSION maintain the approximate lightness balance of the original
     * image, PATTERN may lighten mid-tones somewhat to make the gradient smoother. All of these algorithms except
     * DIFFUSION are suitable for animations; using error-diffusion makes tiny changes in some frames disproportionately
     * affect other pixels in those frames. NONE is fastest, PATTERN is slowest, and GRADIENT_NOISE and DIFFUSION are
     * in-between.
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
         * (which is a potential problem for {@link #DIFFUSION}). {@link #PATTERN} is mostly an improvement, but doesn't
         * preserve lightness as well, and is slower to compute.
         */
        GRADIENT_NOISE,
        /**
         * Thomas Knoll's Pattern Dither (with a skew to obscure grid artifacts), as originally described by Joel
         * Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">this dithering article</a>. Pattern
         * Dither was patented until late 2019, so Yliluoma had to use an 8x8 matrix instead of the 4x4 used here; the
         * 4x4, with appropriate skew, is much faster to compute and doesn't have as many artifacts with large enough
         * palettes. This implementation doesn't have gamma correction working quite correctly, so it tends to lighten
         * images it dithers slightly. It's an ordered dither, like {@link #GRADIENT_NOISE}, but isn't nearly as noisy.
         * Pattern Dither is the default for most purposes here; you may want to prefer {@link #DIFFUSION} for still
         * images, but Pattern Dither still looks quite good for those, albeit not faithful regarding lightness.
         */
        PATTERN,
        /**
         * Floyd-Steinberg error-diffusion dithering; this is the best option for still images, and it's an OK option
         * for some animated images. It doesn't lighten the image like {@link #PATTERN}, while still preserving most
         * details on shapes, but small changes in one part of an animation will affect different frames very
         * differently (which makes this less well-suited for animations). It may look better even in an animation than
         * {@link #GRADIENT_NOISE}, depending on the animation.
         */
        DIFFUSION,
        /**
         * An ordered dither that modifies any error in a pixel's color by using a blue-noise pattern and a checkerboard
         * pattern. If a pixel is perfectly matched by the palette, this won't change it, but otherwise the position
         * will be used for both the checkerboard and a lookup into a 64x64 blue noise texture (stored as a byte array),
         * and the resulting value between -1.5 and 1.5 will be multiplied by the error for that pixel. This yields
         * closer results to {@link #PATTERN} than other ordered dithers like {@link #GRADIENT_NOISE}; though it doesn't
         * preserve soft gradients quite as well, it keeps lightness as well as {@link #DIFFUSION} does, and it doesn't
         * add as much chaotic noise as {@link #GRADIENT_NOISE}. For reference, the blue noise texture this uses looks
         * like <a href="https://i.imgur.com/YCSKKGw.png">this small image</a>; it looks different from a purely-random
         * white noise texture because blue noise has no low frequencies in any direction, while white noise has all
         * frequencies in equal measure.
         */
        BLUE_NOISE
    }
}

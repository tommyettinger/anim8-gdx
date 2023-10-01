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
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import static com.github.tommyettinger.anim8.ConstantData.ENCODED_AURORA;

/**
 * Data that can be used to limit the colors present in a Pixmap or other image, here with the goal of using 256 or less
 * colors in the image (for saving indexed-mode images). Can be used independently of classes like {@link AnimatedGif}
 * and {@link PNG8}, but it is meant to help with intelligently reducing the color count to fit under the maximum
 * palette size for those formats. You can use the {@link #exact(Color[])} method or its overloads to match a specific
 * palette exactly, or the {@link #analyze(Pixmap)} method or its overloads to analyze one or more Pixmaps and determine
 * which colors are most frequently-used. If using this class on its own, after calling exact(), analyze(), or a
 * constructor that uses them, you can use a specific dithering algorithm to reduce a Pixmap to the current palette.
 * Dithering algorithms that this supports:
 * <ul>
 *     <li>TOP TIER
 *     <ul>
 *     <li>{@link #reduceFloydSteinberg(Pixmap)} (Floyd-Steinberg is a very common error-diffusion dither; it's
 *     excellent for still images and large palette sizes, but not animations. It is great for preserving shape, but
 *     when a color isn't in the palette and it needs to try to match it, Floyd-Steinberg can leave artifacts.)</li>
 *     <li>{@link #reduceScatter(Pixmap)} (This is Floyd-Steinberg, as above, but with some of the problem artifacts
 *     that Floyd-Steinberg can produce perturbed by blue noise; it works well in still images and animations, but
 *     images with gradients will do better with Neue, below. Using blue noise to edit error somewhat-randomly would
 *     seem like it would introduce artifacts of its own, but blue noise patterns are very hard to recognize as
 *     artificial, since they show up mostly in organic forms. Scatter holds up very well to high ditherStrength, even
 *     to 2.0 or above, where most of the other dithers have problems, and looks similar to Floyd-Steinberg if using low
 *     ditherStrength.)</li>
 *     <li>{@link #reduceNeue(Pixmap)} (This is a variant on Scatter, above, that tends to be much smoother on gradients
 *     and has very little, if any, banding when using large palettes. A quirk this has is that it doesn't usually
 *     produce large flat areas of one color, instead preferring to dither softly between two similar colors if it can.
 *     This tends to look similar to Floyd-Steinberg and Scatter, because it is related to both of them, but also tends
 *     to have softer transitions between adjacent pixel colors, leading to less of a rough appearance from dithering.
 *     Neue is the default currently because it is the only dither that both handles gradients well and preserves color
 *     well. Blue Noise dither also handles gradients well, but doesn't always recognize color changes. Scatter handles
 *     color well, but can have some banding. Pattern dither usually handles gradients exceptionally well, but can have
 *     severe issues when it doesn't preserve lightness faithfully with small palettes. The list goes on. Neue can
 *     introduce error if the palette perfectly matches the image already; in that case, use Solid.)</li>
 *     <li>{@link #reduceBlueNoise(Pixmap)} (Uses a blue noise texture, which has almost no apparent patterns, to adjust
 *     the amount of color correction applied to each mismatched pixel; also uses an 8x8 Bayer matrix. This adds in
 *     a blue noise amount to each pixel, which is also what Neue does, but because this is a pure ordered dither, it
 *     doesn't take into consideration cumulative error built up over several poorly-matched pixels. It can often fill a
 *     whole region of color incorrectly if the palette isn't just right, though this is rare.)</li>
 *     <li>{@link #reduceKnoll(Pixmap)} (Thomas Knoll's Pattern Dithering, used more or less verbatim; this version has
 *     a heavy grid pattern that looks like an artifact. While the square grid here is a bit bad, it becomes very hard
 *     to see when the palette is large enough. This reduction is the slowest here, currently, and may noticeably delay
 *     processing on large images. Pattern dither handles complex gradients effortlessly, but struggles when the palette
 *     size is small -- in 4-color palettes, for instance, it doesn't match lightness correctly at all.)</li>
 *     </ul>
 *     </li>
 *     <li>OTHER TIER
 *     <ul>
 *     <li>{@link #reduceJimenez(Pixmap)} (This is a modified version of Gradient Interleaved Noise by Jorge Jimenez;
 *     it's a kind of ordered dither that introduces a subtle wave pattern to break up solid blocks. It does well on
 *     some animations and on smooth or rounded shapes, but still has issues with gradients. Also consider Neue for
 *     still images and Blue Noise for animations.)</li>
 *     <li>{@link #reduceSierraLite(Pixmap)} (Like Floyd-Steinberg, Sierra Lite is an error-diffusion dither, and it
 *     sometimes looks better than Floyd-Steinberg, but usually is similar or worse unless the palette is small. Sierra
 *     Lite tends to look comparable to Floyd-Steinberg if the Floyd-Steinberg dither was done with a lower
 *     ditherStrength.If Floyd-Steinberg has unexpected artifacts, you can try Sierra Lite, and it may avoid those
 *     issues. Using Scatter or Neue should be tried first, though.)</li>
 *     <li>{@link #reduceChaoticNoise(Pixmap)} (Uses blue noise and pseudo-random white noise, with a carefully chosen
 *     distribution, to disturb what would otherwise be flat bands. This does introduce chaotic or static-looking
 *     pixels, but with larger palettes they won't be far from the original. This works fine as a last resort when you
 *     can tolerate chaotic/fuzzy patches of poorly-defined shapes, but other dithers aren't doing well. It tends to
 *     still have flat bands when the palette is small, and it generally looks... rather ugly.)</li>
 *     <li>{@link #reduceKnollRoberts(Pixmap)} (This is a modified version of Thomas Knoll's Pattern Dithering; it skews
 *     a grid-based ordered dither and also handles lightness differently from the non-Knoll dithers. It preserves shape
 *     somewhat well, but is almost never 100% faithful to the original colors. This algorithm is rather slow; most of
 *     the other algorithms take comparable amounts of time to each other, but KnollRoberts and especially Knoll are
 *     sluggish.)</li>
 *     <li>{@link #reduceSolid(Pixmap)} (No dither! Solid colors! Mostly useful when you want to preserve blocky parts
 *     of a source image, or for some kinds of pixel/low-color art. If you have a palette that perfectly matches the
 *     image you are dithering, then you won't need dither, and this will be the best option.)</li>
 *     </ul>
 *     </li>
 * </ul>
 * <p>
 * Created by Tommy Ettinger on 6/23/2018.
 */
public class PaletteReducer {
    /**
     * DawnBringer's 256-color Aurora palette, modified slightly to fit one transparent color by removing one gray.
     * Aurora is available in <a href="https://pixeljoint.com/forum/forum_posts.asp?TID=26080">this set of tools</a>
     * for a pixel art editor, but it is usable for lots of high-color purposes.
     * <br>
     * These colors all have names, which can be seen <a href="https://i.imgur.com/2oChRYC.png">previewed here</a>. The
     * linked image preview also shows a nearby lighter color and two darker colors on the same sphere as the main
     * color; the second-lightest color is what has the listed name. The names here are used by a few other libraries,
     * such as <a href="https://github.com/tommyettinger/colorful-gdx">colorful-gdx</a>, but otherwise don't matter.
     * <br>
     * This replaced another palette, Haltonic, that wasn't hand-chosen and was much more "randomized." Aurora was the
     * first palette used as a default here, and it was replaced because the color metric at the time made it look bad.
     * <br>
     * While you can modify the individual items in this array, this is discouraged, because various constructors and
     * methods in this class use AURORA with a pre-made distance mapping of its colors. This mapping would become
     * incorrect if any colors in this array changed.
     */
    public static final int[] AURORA = {
            0x00000000, 0x010101FF, 0x131313FF, 0x252525FF, 0x373737FF, 0x494949FF, 0x5B5B5BFF, 0x6E6E6EFF,
            0x808080FF, 0x929292FF, 0xA4A4A4FF, 0xB6B6B6FF, 0xC9C9C9FF, 0xDBDBDBFF, 0xEDEDEDFF, 0xFFFFFFFF,
            0x007F7FFF, 0x3FBFBFFF, 0x00FFFFFF, 0xBFFFFFFF, 0x8181FFFF, 0x0000FFFF, 0x3F3FBFFF, 0x00007FFF,
            0x0F0F50FF, 0x7F007FFF, 0xBF3FBFFF, 0xF500F5FF, 0xFD81FFFF, 0xFFC0CBFF, 0xFF8181FF, 0xFF0000FF,
            0xBF3F3FFF, 0x7F0000FF, 0x551414FF, 0x7F3F00FF, 0xBF7F3FFF, 0xFF7F00FF, 0xFFBF81FF, 0xFFFFBFFF,
            0xFFFF00FF, 0xBFBF3FFF, 0x7F7F00FF, 0x007F00FF, 0x3FBF3FFF, 0x00FF00FF, 0xAFFFAFFF, 0xBCAFC0FF,
            0xCBAA89FF, 0xA6A090FF, 0x7E9494FF, 0x6E8287FF, 0x7E6E60FF, 0xA0695FFF, 0xC07872FF, 0xD08A74FF,
            0xE19B7DFF, 0xEBAA8CFF, 0xF5B99BFF, 0xF6C8AFFF, 0xF5E1D2FF, 0x573B3BFF, 0x73413CFF, 0x8E5555FF,
            0xAB7373FF, 0xC78F8FFF, 0xE3ABABFF, 0xF8D2DAFF, 0xE3C7ABFF, 0xC49E73FF, 0x8F7357FF, 0x73573BFF,
            0x3B2D1FFF, 0x414123FF, 0x73733BFF, 0x8F8F57FF, 0xA2A255FF, 0xB5B572FF, 0xC7C78FFF, 0xDADAABFF,
            0xEDEDC7FF, 0xC7E3ABFF, 0xABC78FFF, 0x8EBE55FF, 0x738F57FF, 0x587D3EFF, 0x465032FF, 0x191E0FFF,
            0x235037FF, 0x3B573BFF, 0x506450FF, 0x3B7349FF, 0x578F57FF, 0x73AB73FF, 0x64C082FF, 0x8FC78FFF,
            0xA2D8A2FF, 0xE1F8FAFF, 0xB4EECAFF, 0xABE3C5FF, 0x87B48EFF, 0x507D5FFF, 0x0F6946FF, 0x1E2D23FF,
            0x234146FF, 0x3B7373FF, 0x64ABABFF, 0x8FC7C7FF, 0xABE3E3FF, 0xC7F1F1FF, 0xBED2F0FF, 0xABC7E3FF,
            0xA8B9DCFF, 0x8FABC7FF, 0x578FC7FF, 0x57738FFF, 0x3B5773FF, 0x0F192DFF, 0x1F1F3BFF, 0x3B3B57FF,
            0x494973FF, 0x57578FFF, 0x736EAAFF, 0x7676CAFF, 0x8F8FC7FF, 0xABABE3FF, 0xD0DAF8FF, 0xE3E3FFFF,
            0xAB8FC7FF, 0x8F57C7FF, 0x73578FFF, 0x573B73FF, 0x3C233CFF, 0x463246FF, 0x724072FF, 0x8F578FFF,
            0xAB57ABFF, 0xAB73ABFF, 0xEBACE1FF, 0xFFDCF5FF, 0xE3C7E3FF, 0xE1B9D2FF, 0xD7A0BEFF, 0xC78FB9FF,
            0xC87DA0FF, 0xC35A91FF, 0x4B2837FF, 0x321623FF, 0x280A1EFF, 0x401811FF, 0x621800FF, 0xA5140AFF,
            0xDA2010FF, 0xD5524AFF, 0xFF3C0AFF, 0xF55A32FF, 0xFF6262FF, 0xF6BD31FF, 0xFFA53CFF, 0xD79B0FFF,
            0xDA6E0AFF, 0xB45A00FF, 0xA04B05FF, 0x5F3214FF, 0x53500AFF, 0x626200FF, 0x8C805AFF, 0xAC9400FF,
            0xB1B10AFF, 0xE6D55AFF, 0xFFD510FF, 0xFFEA4AFF, 0xC8FF41FF, 0x9BF046FF, 0x96DC19FF, 0x73C805FF,
            0x6AA805FF, 0x3C6E14FF, 0x283405FF, 0x204608FF, 0x0C5C0CFF, 0x149605FF, 0x0AD70AFF, 0x14E60AFF,
            0x7DFF73FF, 0x4BF05AFF, 0x00C514FF, 0x05B450FF, 0x1C8C4EFF, 0x123832FF, 0x129880FF, 0x06C491FF,
            0x00DE6AFF, 0x2DEBA8FF, 0x3CFEA5FF, 0x6AFFCDFF, 0x91EBFFFF, 0x55E6FFFF, 0x7DD7F0FF, 0x08DED5FF,
            0x109CDEFF, 0x055A5CFF, 0x162C52FF, 0x0F377DFF, 0x004A9CFF, 0x326496FF, 0x0052F6FF, 0x186ABDFF,
            0x2378DCFF, 0x699DC3FF, 0x4AA4FFFF, 0x90B0FFFF, 0x5AC5FFFF, 0xBEB9FAFF, 0x00BFFFFF, 0x007FFFFF,
            0x4B7DC8FF, 0x786EF0FF, 0x4A5AFFFF, 0x6241F6FF, 0x3C3CF5FF, 0x101CDAFF, 0x0010BDFF, 0x231094FF,
            0x0C2148FF, 0x5010B0FF, 0x6010D0FF, 0x8732D2FF, 0x9C41FFFF, 0x7F00FFFF, 0xBD62FFFF, 0xB991FFFF,
            0xD7A5FFFF, 0xD7C3FAFF, 0xF8C6FCFF, 0xE673FFFF, 0xFF52FFFF, 0xDA20E0FF, 0xBD29FFFF, 0xBD10C5FF,
            0x8C14BEFF, 0x5A187BFF, 0x641464FF, 0x410062FF, 0x320A46FF, 0x551937FF, 0xA01982FF, 0xC80078FF,
            0xFF50BFFF, 0xFF6AC5FF, 0xFAA0B9FF, 0xFC3A8CFF, 0xE61E78FF, 0xBD1039FF, 0x98344DFF, 0x911437FF,
    };

    /**
     * This 255-color (plus transparent) palette uses the (3,5,7) Halton sequence to get 3D points, treats those as IPT
     * channel values, and rejects out-of-gamut colors. This also rejects any color that is too similar to an existing
     * color, which in this case made this try 130958 colors before finally getting 256 that work. Using the Halton
     * sequence provides one of the stronger guarantees that removing any sequential items (after the first 9, which are
     * preset grayscale colors) will produce a similarly-distributed palette. Typically, 64 items from this are enough
     * to make pixel art look good enough with dithering, and it continues to improve with more colors. It has exactly 8
     * colors that are purely grayscale, all right at the start after transparent.
     * <br>
     * Haltonic was the default palette from a fairly early version until 0.3.9, when it was replaced with Aurora.
     */
    public static final int[] HALTONIC = new int[]{
            0x00000000, 0x010101FF, 0xFEFEFEFF, 0x7B7B7BFF, 0x555555FF, 0xAAAAAAFF, 0x333333FF, 0xE0E0E0FF,
            0xC8C8C8FF, 0xBEBB4EFF, 0x1FAE9AFF, 0xC2BBA9FF, 0xB46B58FF, 0x7C82C2FF, 0xF2825BFF, 0xD55193FF,
            0x8C525CFF, 0x6AEF59FF, 0x1F439BFF, 0x793210FF, 0x3B3962FF, 0x16D72EFF, 0xB53FC6FF, 0xB380C7FF,
            0xEDE389FF, 0x8420C6FF, 0x291710FF, 0x69D4D3FF, 0x76121CFF, 0x1FA92AFF, 0x64852CFF, 0x7A42DBFF,
            0xEA5A5EFF, 0x7E3E8CFF, 0xB8FA35FF, 0x4F15DAFF, 0xBC3E61FF, 0xA19150FF, 0x9BBD25FF, 0xF095C2FF,
            0xFFC24FFF, 0x7B7CFCFF, 0x9BE8C3FF, 0xE25EC4FF, 0x3D79ADFF, 0xC0422AFF, 0x260E5DFF, 0xF645A3FF,
            0xF8ACE4FF, 0xB0871FFF, 0x42582CFF, 0x549787FF, 0xE31BA2FF, 0x1E222AFF, 0xB39CF5FF, 0x8C135FFF,
            0x71CB92FF, 0xB767B3FF, 0x7E5030FF, 0x406697FF, 0x502B06FF, 0xDFAC73FF, 0xC21A26FF, 0xECFE65FF,
            0x7E64E4FF, 0xBFD22EFF, 0xDA938FFF, 0x8E94E8FF, 0xA0DE92FF, 0x8C6BA9FF, 0x1662FCFF, 0xCA4EECFF,
            0x8899AAFF, 0x24BC57FF, 0x680AA7FF, 0xFE6885FF, 0x2E1E6EFF, 0x875695FF, 0x981C20FF, 0x47723EFF,
            0xF4E54FFF, 0x71174CFF, 0xC5F8ABFF, 0x75BFC7FF, 0xF23C37FF, 0xFC73E9FF, 0x893A5FFF, 0x4F50C5FF,
            0xE06635FF, 0xB00D9FFF, 0xE90FCAFF, 0x1E9CFBFF, 0x3538F9FF, 0xE3971BFF, 0x500153FF, 0x2DB2CEFF,
            0xB46D86FF, 0xFE43F2FF, 0x4FF990FF, 0x434531FF, 0xE31515FF, 0xDFA24BFF, 0x4282E6FF, 0x56626FFF,
            0xF8B891FF, 0x4B0932FF, 0xD769E6FF, 0x906D1DFF, 0xD51144FF, 0x76B6F8FF, 0x4DF7ECFF, 0x169355FF,
            0xB7C87DFF, 0x650C83FF, 0x0AE930FF, 0xEDB71AFF, 0x78AE77FF, 0x081236FF, 0x25E5F4FF, 0x5A4382FF,
            0xB1FEFAFF, 0xEA7B0BFF, 0xF372C1FF, 0xA31479FF, 0x3EDB6AFF, 0xA44210FF, 0xB2C1FAFF, 0xAE9784FF,
            0xE83175FF, 0xF925DFFF, 0xAB134FFF, 0xC03E83FF, 0x117F76FF, 0xE6E21DFF, 0x6B3858FF, 0x88ED12FF,
            0x3E3486FF, 0x3DBB14FF, 0xD35521FF, 0xC2836DFF, 0x244E65FF, 0xAC29F6FF, 0xE71A58FF, 0x1127ABFF,
            0xD086E0FF, 0x496B1CFF, 0xD27E96FF, 0x87353AFF, 0xD308EDFF, 0x5D3BAAFF, 0x11560BFF, 0x469AC6FF,
            0xEDD4B9FF, 0xA4A222FF, 0x48A75CFF, 0xBB7213FF, 0xFBBAFAFF, 0x794811FF, 0x83804EFF, 0xB1FB85FF,
            0x61C56DFF, 0x9D36B1FF, 0x201693FF, 0x184BB9FF, 0x5B0606FF, 0xAB5692FF, 0x090B23FF, 0xA7593AFF,
            0x14D7ADFF, 0xAC6BF1FF, 0xCC0E7EFF, 0x1B90B4FF, 0xA5A94CFF, 0x264509FF, 0xE994FDFF, 0xC1E367FF,
            0x1D16D5FF, 0x1C5C7DFF, 0xCF794CFF, 0xF6FF95FF, 0x7B1A88FF, 0x68B69CFF, 0xAADAF7FF, 0x6625E1FF,
            0x223308FF, 0x7147FEFF, 0xDF6A7FFF, 0xF5FE22FF, 0xB6B1D2FF, 0x35E986FF, 0x2C69D4FF, 0x6D63C8FF,
            0x32042DFF, 0xF4A293FF, 0x22040DFF, 0xF2FAC2FF, 0xFFBBB2FF, 0x9D3F7CFF, 0x86694EFF, 0xD34B57FF,
            0x5B2E24FF, 0xF2CF80FF, 0x10EBAFFF, 0x7B603CFF, 0xFDE5A7FF, 0xB41808FF, 0xA83F4BFF, 0xC221B4FF,
            0x9604A4FF, 0x878287FF, 0x3F1C16FF, 0x5AA7FEFF, 0x55096CFF, 0x1E9922FF, 0x031050FF, 0xA284A1FF,
            0x2424EDFF, 0x8FD111FF, 0x480C8BFF, 0x71FE60FF, 0xFE1D02FF, 0xFF9A60FF, 0xD44ABEFF, 0xFE7B9AFF,
            0x68915EFF, 0x9EFFD1FF, 0xABAC7CFF, 0x4413BFFF, 0xF93E83FF, 0x7A9633FF, 0xA05B73FF, 0x83A3C3FF,
            0x124D4AFF, 0x397E0EFF, 0x6AFEB5FF, 0x975813FF, 0xFEC704FF, 0xBC1462FF, 0xA008E0FF, 0x418886FF,
            0x58CAFEFF, 0x4E7A53FF, 0x7A07FFFF, 0x8D4EBCFF, 0xFE3257FF, 0xA46BD5FF, 0xB079FFFF, 0x909478FF,
            0xFC6C42FF, 0x5F3342FF, 0x6A6A9DFF, 0xFF6315FF, 0x9D56D2FF, 0x6782A7FF, 0x957F24FF, 0xD08FB9FF,
    };

    /**
     * Converts an RGBA8888 int color to the RGB555 format used by {@link #OKLAB} to look up colors.
     * @param color an RGBA8888 int color
     * @return an RGB555 int color
     */
    public static int shrink(final int color)
    {
        return (color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F);
    }

    /**
     * Converts an RGB555 int color to an approximation of the closest RGBA8888 color. For each 5-bit channel in
     * {@code color}, this gets an 8-bit value by keeping the original 5 in the most significant 5 places, then copying
     * the most significant 3 bits of the RGB555 color into the least significant 3 bits of the 8-bit value. In
     * practice, this means the lowest 5-bit value produces the lowest 8-bit value (00000 to 00000000), and the highest
     * 5-bit value produces the highest 8-bit value (11111 to 11111111). This always assigns a fully-opaque value to
     * alpha (255, or 0xFF).
     * @param color an RGB555 color
     * @return an approximation of the closest RGBA8888 color; alpha is always fully opaque
     */
    public static int stretch(final int color)
    {
        return (color << 17 & 0xF8000000) | (color << 12 & 0x07000000) | (color << 14 & 0xF80000) | (color << 9 & 0x070000) | (color << 11 & 0xF800) | (color << 6 & 0x0700) | 0xFF;
    }

    /**
     * Changes the curve of a requested L value so that it matches the internally-used curve. This takes a curve with a
     * very-dark area similar to sRGB (a very small one), and makes it significantly larger. This is typically used on
     * "to Oklab" conversions.
     * <br>
     * Internally, this is similar to {@code (float)Math.pow(L, 1.5f)}. At one point it used a modified "Barron spline"
     * to get its curvature mostly right, but this now seems nearly indistinguishable from an ideal curve.
     * @param L lightness, from 0 to 1 inclusive
     * @return an adjusted L value that can be used internally
     */
    public static float forwardLight(final float L) {
        return (float) Math.sqrt(L * L * L);
    }

    /**
     * Changes the curve of the internally-used lightness when it is output to another format. This makes the very-dark
     * area smaller, matching (closely) the curve that the standard sRGB lightness uses. This is typically used on "from
     * Oklab" conversions.
     * <br>
     * Internally, this is similar to {@code (float)Math.pow(L, 2f/3f)}. At one point it used a modified "Barron spline"
     * to get its curvature mostly right, but this now seems nearly indistinguishable from an ideal curve.
     * <br>
     * This specific code uses a modified cube root approximation (based on {@link OtherMath#cbrtPositive(float)})
     * originally by Marc B. Reynolds.
     * @param L lightness, from 0 to 1 inclusive
     * @return an adjusted L value that can be fed into a conversion to RGBA or something similar
     */
    public static float reverseLight(float L) {
        int ix = NumberUtils.floatToRawIntBits(L);
        final float x0 = L;
        ix = (ix>>>2) + (ix>>>4);
        ix += (ix>>>4);
        ix += (ix>>>8) + 0x2A5137A0;
        L  = NumberUtils.intBitsToFloat(ix);
        L  = 0.33333334f * (2f * L + x0/(L*L));
        L  = 0.33333334f * (1.9999999f * L + x0/(L*L));
        return L * L;
    }

//    /**
//     * Changes the curve of a requested L value so that it matches the internally-used curve. This takes a curve with a
//     * very-dark area similar to sRGB (a very small one), and makes it significantly larger. This is typically used on
//     * "to Oklab" conversions.
//     * @param L lightness, from 0 to 1 inclusive
//     * @return an adjusted L value that can be used internally
//     */
//    public static float forwardLight(final float L) {
//        final float shape = 0.64516133f, turning = 0.95f;
//        final float d = turning - L;
//        float r;
//        if(d < 0)
//            r = ((1f - turning) * (L - 1f)) / (1f - (L + shape * d)) + 1f;
//        else
//            r = (turning * L) / (1e-20f + (L + shape * d));
//        return r * r;
//    }
//
////	public static float forwardLight(final float L) {
////		return (L - 1.004f) / (1f - L * 0.4285714f) + 1.004f;
////	}
//
//    /**
//     * Changes the curve of the internally-used lightness when it is output to another format. This makes the very-dark
//     * area smaller, matching (kind-of) the curve that the standard sRGB lightness uses. This is typically used on "from
//     * Oklab" conversions.
//     * @param L lightness, from 0 to 1 inclusive
//     * @return an adjusted L value that can be fed into a conversion to RGBA or something similar
//     */
//    public static float reverseLight(float L) {
//        L = (float) Math.sqrt(L);
//        final float shape = 1.55f, turning = 0.95f;
//        final float d = turning - L;
//        float r;
//        if(d < 0)
//            r = ((1f - turning) * (L - 1f)) / (1f - (L + shape * d)) + 1f;
//        else
//            r = (turning * L) / (1e-20f + (L + shape * d));
//        return r;
//    }
//
////	public static float reverseLight(final float L) {
////		return (L - 0.993f) / (1f + L * 0.75f) + 0.993f;
////	}

    /**
     * Stores Oklab components corresponding to RGB555 indices.
     * OKLAB[0] stores L (lightness) from 0.0 to 1.0 .
     * OKLAB[1] stores A, which is something like a green-red axis, from -0.5 (green) to 0.5 (red).
     * OKLAB[2] stores B, which is something like a blue-yellow axis, from -0.5 (blue) to 0.5 (yellow).
     * OKLAB[3] stores the hue in radians from -PI to PI, with red at 0, yellow at PI/2, and blue at -PI/2.
     * <br>
     * The indices into each of these float[] values store red in bits 10-14, green in bits 5-9, and blue in bits 0-4.
     * It's ideal to work with these indices with bitwise operations, as with {@code (r << 10 | g << 5 | b)}, where r,
     * g, and b are all in the 0-31 range inclusive. It's usually easiest to convert an RGBA8888 int color to an RGB555
     * color with {@link #shrink(int)}.
     */
    public static final float[][] OKLAB = new float[4][0x8000];

    /**
     * A 4096-element byte array as a 64x64 grid of bytes. When arranged into a grid, the bytes will follow a blue noise
     * frequency (in this case, they will have a triangular distribution for its bytes, so values near 0 are much more
     * common). This is used inside this library to create {@link #TRI_BLUE_NOISE_MULTIPLIERS}, which is used in
     * {@link #reduceScatter(Pixmap)}. It is also used directly by {@link #reduceBlueNoise(Pixmap)},
     * {@link #reduceNeue(Pixmap)}, and {@link #reduceChaoticNoise(Pixmap)}.
     * <br>
     * While, for some reason, you could change the contents to some other distribution of bytes, I don't know why this
     * would be needed.
     */
    public static final byte[] TRI_BLUE_NOISE = ConstantData.TRI_BLUE_NOISE;
    /**
     * A 4096-element byte array as a 64x64 grid of bytes. When arranged into a grid, the bytes will follow a blue noise
     * frequency (in this case, they will have a triangular distribution for its bytes, so values near 0 are much more
     * common). This is used inside this library by {@link #reduceBlueNoise(Pixmap)}.
     * <br>
     * While, for some reason, you could change the contents to some other distribution of bytes, I don't know why this
     * would be needed.
     */
    public static final byte[] TRI_BLUE_NOISE_B = ConstantData.TRI_BLUE_NOISE_B;
    /**
     * A 4096-element byte array as a 64x64 grid of bytes. When arranged into a grid, the bytes will follow a blue noise
     * frequency (in this case, they will have a triangular distribution for its bytes, so values near 0 are much more
     * common). This is used inside this library by {@link #reduceBlueNoise(Pixmap)}.
     * <br>
     * While, for some reason, you could change the contents to some other distribution of bytes, I don't know why this
     * would be needed.
     */
    public static final byte[] TRI_BLUE_NOISE_C = ConstantData.TRI_BLUE_NOISE_C;

    /**
     * A 64x64 grid of floats, with a median value of about 1.0, generated using the triangular-distributed blue noise
     * from {@link #TRI_BLUE_NOISE}. If you randomly selected two floats from this and multiplied them, the average
     * result should be 1.0; half of the items in this should be between 1 and {@link Math#E}, and the other half should
     * be the inverses of the first half (between {@code 1.0/Math.E} and 1).
     * <br>
     * While, for some reason, you could change the contents to some other distribution of bytes, I don't know why this
     * would be needed.
     */
    public static final float[] TRI_BLUE_NOISE_MULTIPLIERS = ConstantData.TRI_BLUE_NOISE_MULTIPLIERS;

    static {
        float rf, gf, bf, lf, mf, sf;
        int idx = 0;
        for (int ri = 0; ri < 32; ri++) {
            rf = (float) (ri * ri * 0.0010405827263267429); // 1.0 / 31.0 / 31.0
            for (int gi = 0; gi < 32; gi++) {
                gf = (float) (gi * gi * 0.0010405827263267429); // 1.0 / 31.0 / 31.0
                for (int bi = 0; bi < 32; bi++) {
                    bf = (float) (bi * bi * 0.0010405827263267429); // 1.0 / 31.0 / 31.0

                    lf = OtherMath.cbrtPositive(0.4121656120f * rf + 0.5362752080f * gf + 0.0514575653f * bf);
                    mf = OtherMath.cbrtPositive(0.2118591070f * rf + 0.6807189584f * gf + 0.1074065790f * bf);
                    sf = OtherMath.cbrtPositive(0.0883097947f * rf + 0.2818474174f * gf + 0.6302613616f * bf);

                    OKLAB[0][idx] = forwardLight(
                                    0.2104542553f * lf + 0.7936177850f * mf - 0.0040720468f * sf);
                    OKLAB[1][idx] = 1.9779984951f * lf - 2.4285922050f * mf + 0.4505937099f * sf;
                    OKLAB[2][idx] = 0.0259040371f * lf + 0.7827717662f * mf - 0.8086757660f * sf;
                    OKLAB[3][idx] = OtherMath.atan2(OKLAB[2][idx], OKLAB[1][idx]);

                    idx++;
                }
            }
        }
//        for (int i = 1; i < 256; i++) {
//            EXACT_LOOKUP[i] = OtherMath.barronSpline(i / 255f, 4f, 0.5f);
//            ANALYTIC_LOOKUP[i] = OtherMath.barronSpline(i / 255f, 3f, 0.5f);
//        }

//
//        double r, g, b, x, y, z;
//        int idx = 0;
//        for (int ri = 0; ri < 32; ri++) {
//            r = ri / 31.0;
//            r = ((r > 0.04045) ? Math.pow((r + 0.055) / 1.055, 2.4) : r / 12.92);
//            for (int gi = 0; gi < 32; gi++) {
//                g = gi / 31.0;
//                g = ((g > 0.04045) ? Math.pow((g + 0.055) / 1.055, 2.4) : g / 12.92);
//                for (int bi = 0; bi < 32; bi++) {
//                    b = bi / 31.0;
//                    b = ((b > 0.04045) ? Math.pow((b + 0.055) / 1.055, 2.4) : b / 12.92);
//
//                    x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.950489; // 0.96422;
//                    y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.000000; // 1.00000;
//                    z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.088840; // 0.82521;
//
//                    x = (x > 0.008856) ? Math.cbrt(x) : (7.787037037037037 * x) + 0.13793103448275862;
//                    y = (y > 0.008856) ? Math.cbrt(y) : (7.787037037037037 * y) + 0.13793103448275862;
//                    z = (z > 0.008856) ? Math.cbrt(z) : (7.787037037037037 * z) + 0.13793103448275862;
//
//                    LAB[0][idx] = (116.0 * y) - 16.0;
//                    LAB[1][idx] = 500.0 * (x - y);
//                    LAB[2][idx] = 200.0 * (y - z);
//                    idx++;
//                }
//            }
//        }
    }

    public static int oklabToRGB(float L, float A, float B, float alpha)
    {
        L = reverseLight(L);
        float l = (L + 0.3963377774f * A + 0.2158037573f * B);
        float m = (L - 0.1055613458f * A - 0.0638541728f * B);
        float s = (L - 0.0894841775f * A - 1.2914855480f * B);
        l *= l * l;
        m *= m * m;
        s *= s * s;
        final int r = (int)(Math.sqrt(Math.min(Math.max(+4.0767245293f * l - 3.3072168827f * m + 0.2307590544f * s, 0.0f), 1.0f)) * 255.999f);
        final int g = (int)(Math.sqrt(Math.min(Math.max(-1.2681437731f * l + 2.6093323231f * m - 0.3411344290f * s, 0.0f), 1.0f)) * 255.999f);
        final int b = (int)(Math.sqrt(Math.min(Math.max(-0.0041119885f * l - 0.7034763098f * m + 1.7068625689f * s, 0.0f), 1.0f)) * 255.999f);
        return r << 24 | g << 16 | b << 8 | (int)(alpha * 255.999f);
    }

    /**
     * Given a non-null Pixmap, this finds the up-to-255 most-frequently-used colors and returns them as an array of
     * RGBA8888 ints. This always reserves space in the returned array for fully-transparent, so there can be in full
     * 256 colors in the returned array. The int array this returns is useful to pass to {@link #exact(int[])} or the
     * constructors that expect an RGBA8888 palette.
     * @param pixmap a non-null Pixmap, often representing or already limited to the desired palette
     * @return an array of between 1 and 256 RGBA8888 ints, representing a palette
     */
    public static int[] colorsFrom(Pixmap pixmap) {
        return colorsFrom(pixmap, 256);
    }

    /**
     * Given a non-null Pixmap, this finds the up-to-{@code limit - 1} most-frequently-used colors and returns them as
     * an array of RGBA8888 ints. This always reserves space in the returned array for fully-transparent, so there can
     * be in full {@code limit} colors in the returned array. The int array this returns is useful to pass to
     * {@link #exact(int[])} or the constructors that expect an RGBA8888 palette.
     * @param pixmap a non-null Pixmap, often representing or already limited to the desired palette
     * @param limit how many colors this can return as an inclusive limit; the actual returned array can be smaller
     * @return an array of between 1 and {@code limit} RGBA8888 ints, representing a palette
     */
    public static int[] colorsFrom(Pixmap pixmap, int limit) {
        int color, colorCount;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(256);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for (IntIntMap.Entry e : counts) {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        colorCount = Math.min(limit, es.size + 1);
        int[] colorArray = new int[colorCount];
        int i = 1;
        for (IntIntMap.Entry e : es) {
            color = e.key;
            colorArray[i] = color;
            if(++i >= limit) break;
        }
        return colorArray;
    }

    /**
     * Stores the byte indices into {@link #paletteArray} (when treated as unsigned; mask with 255) corresponding to
     * RGB555 colors (you can get an RGB555 int from an RGBA8888 int using {@link #shrink(int)}). This is not especially
     * likely to be useful externally except to make a preload code for later usage. If you have a way to write and read
     * bytes from a file, you can calculate a frequently-used palette once using {@link #exact(int[])} or
     * {@link #analyze(Pixmap)}, write this field to file, and on later runs you can load the 32768-element byte array
     * to speed up construction using {@link #PaletteReducer(int[], byte[])}. Editing this field is strongly
     * discouraged; use {@link #exact(int[])} or {@link #analyze(Pixmap)} to set the palette as a whole.
     */
    public final byte[] paletteMapping = new byte[0x8000];
    /**
     * The RGBA8888 int colors this can reduce an image to use. This is public, and since it is an array you can modify
     * its contents, but you should only change this if you know what you are doing. It is closely related to the
     * contents of the {@link #paletteMapping} field, and paletteMapping should typically be changed by
     * {@link #exact(int[])}, {@link #analyze(Pixmap)}, or {@link #loadPreloadFile(FileHandle)}. Because paletteMapping
     * only contains indices into this paletteArray, if paletteArray changes then the closest-color consideration may be
     * altered. This field can be safely altered, usually, by {@link #alterColorsLightness(Interpolation)} or
     * {@link #alterColorsOklab(Interpolation, Interpolation, Interpolation)}.
     */
    public final int[] paletteArray = new int[256];

    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the current line's red channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray curErrorRedFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the next line's red channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray nextErrorRedFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the current line's green channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray curErrorGreenFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the next line's green channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray nextErrorGreenFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the current line's blue channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray curErrorBlueFloats;
    /**
     * A FloatArray used as a buffer to store accrued error for error-diffusion dithers.
     * This stores error for the next line's blue channel.
     * It is protected so that user code that extends PaletteReducer doesn't need to create its own buffers.
     */
    protected transient FloatArray nextErrorBlueFloats;
    /**
     * How many colors are in the palette here; this is at most 256, and typically includes one fully-transparent color.
     */
    public int colorCount;

    /**
     * Determines how strongly to apply noise or other effects during dithering. The neutral value is 1.0f .
     */
    protected float ditherStrength = 1f;
    /**
     * Typically between 0.5 and 1, this should get closer to 1 with larger palette sizes, and closer to 0.5 with
     * smaller palettes. Within anim8-gdx, this is generally calculated with {@code Math.exp(-1.375 / colorCount)}.
     */
    protected float populationBias = 0.5f;

    /**
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     * Must not be modified.
     */
    protected static final int[] thresholdMatrix8 = {
            0, 4, 2, 6,
            3, 7, 1, 5,
    };

    /**
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     * Must not be modified.
     */
    protected static final int[] thresholdMatrix16 = {
            0,  12,   3,  15,
            8,   4,  11,   7,
            2,  14,   1,  13,
            10,  6,   9,   5,
    };

    /**
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     * Must not be modified.
     */
    protected static final int[] thresholdMatrix64 = {
            0,  48,  12,  60,   3,  51,  15,  63,
            32,  16,  44,  28,  35,  19,  47,  31,
            8,  56,   4,  52,  11,  59,   7,  55,
            40,  24,  36,  20,  43,  27,  39,  23,
            2,  50,  14,  62,   1,  49,  13,  61,
            34,  18,  46,  30,  33,  17,  45,  29,
            10,  58,   6,  54,   9,  57,   5,  53,
            42,  26,  38,  22,  41,  25,  37,  21
    };

    /**
     * A temporary 32-element array typically used to store colors or palette indices along with their RGB555
     * {@link #shrink(int) shrunken} analogues, so that {@link #sort16(int[])} can sort them. Mostly for internal use.
     */
    protected transient final int[] candidates = new int[32];

    /**
     * If this PaletteReducer has already calculated a palette, you can use this to save the slightly-slow-to-compute
     * palette mapping in a preload file for later runs. Once you have the file and the same int array originally used
     * for the RGBA8888 colors (e.g. {@code intColors}), you can load it when constructing a
     * PaletteReducer with {@code new PaletteReducer(intColors, PaletteReducer.loadPreloadFile(theFile))}.
     * @param file a writable non-null FileHandle; this will overwrite a file already present if it has the same name
     */
    public void writePreloadFile(FileHandle file){
        file.writeBytes(paletteMapping, false);
    }

    /**
     * If you saved a preload file with {@link #writePreloadFile(FileHandle)}, you can load it and give it to a
     * constructor with: {@code new PaletteReducer(intColors, PaletteReducer.loadPreloadFile(theFile))}, where intColors
     * is the original int array of RGBA8888 colors and theFile is the preload file written previously.
     * @param file a readable non-null FileHandle that should have been written by
     *             {@link #writePreloadFile(FileHandle)}, or otherwise contain the bytes of {@link #paletteMapping}
     * @return a byte array that should have a length of exactly 32768, to be passed to {@link #PaletteReducer(int[], byte[])}
     */
    public static byte[] loadPreloadFile(FileHandle file) {
        return file.readBytes();
    }
    
    /**
     * Constructs a default PaletteReducer that uses the "Aurora" 255-color-plus-transparent palette.
     * Note that this uses a more-detailed and higher-quality metric than you would get by just specifying
     * {@code new PaletteReducer(PaletteReducer.AURORA)}; this metric would be too slow to calculate at
     * runtime, but as pre-calculated data it works very well.
     */
    public PaletteReducer() {
        exact(AURORA, ENCODED_AURORA);
    }

    /**
     * Constructs a PaletteReducer that uses the given array of RGBA8888 ints as a palette (see {@link #exact(int[])}
     * for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     */
    public PaletteReducer(int[] rgbaPalette) {
        if(rgbaPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(rgbaPalette);
    }
    /**
     * Constructs a PaletteReducer that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], int)} for more info).
     *
     * @param rgbaPalette an array of RGBA8888 ints to use as a palette
     * @param limit how many int items to use from rgbaPalette (this always starts at index 0)
     */
    public PaletteReducer(int[] rgbaPalette, int limit) {
        if(rgbaPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(rgbaPalette, limit);
    }

    /**
     * Constructs a PaletteReducer that uses the given array of Color objects as a palette (see {@link #exact(Color[])}
     * for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public PaletteReducer(Color[] colorPalette) {
        if(colorPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(colorPalette);
    }

    /**
     * Constructs a PaletteReducer that uses the given array of Color objects as a palette (see
     * {@link #exact(Color[], int)} for more info).
     *
     * @param colorPalette an array of Color objects to use as a palette
     */
    public PaletteReducer(Color[] colorPalette, int limit) {
        if(colorPalette == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        exact(colorPalette, limit);
    }

    /**
     * Constructs a PaletteReducer that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap)} for more info).
     *
     * @param pixmap a Pixmap to analyze in detail to produce a palette
     */
    public PaletteReducer(Pixmap pixmap) {
        if(pixmap == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        analyze(pixmap);
    }

    /**
     * Constructs a PaletteReducer that analyzes the given Pixmaps for color count and frequency to generate a palette
     * (see {@link #analyze(Array)} for more info).
     *
     * @param pixmaps an Array of Pixmap to analyze in detail to produce a palette
     */
    public PaletteReducer(Array<Pixmap> pixmaps) {
        if(pixmaps == null)
        {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        analyze(pixmaps);
    }
    /**
     * Constructs a PaletteReducer that uses the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[], byte[])} for more info) and an encoded byte array to use to look up pre-loaded color data.
     * You can use {@link #writePreloadFile(FileHandle)} to write the preload data for a given PaletteReducer, and
     * {@link #loadPreloadFile(FileHandle)} to get a byte array of preload data from a previously-written file.
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array containing preload data
     */
    public PaletteReducer(int[] palette, byte[] preload)
    {
        exact(palette, preload);
    }
    /**
     * Constructs a PaletteReducer that analyzes the given Pixmap for color count and frequency to generate a palette
     * (see {@link #analyze(Pixmap, double)} for more info).
     *
     * @param pixmap    a Pixmap to analyze in detail to produce a palette
     * @param threshold the minimum difference between colors required to put them in the palette (default 100)
     */
    public PaletteReducer(Pixmap pixmap, double threshold) {
        analyze(pixmap, threshold);
    }

//        return (RGB_POWERS[Math.abs(r1 - r2)]
//                + RGB_POWERS[256+Math.abs(g1 - g2)]
//                + RGB_POWERS[512+Math.abs(b1 - b2)]) * 0x1p-10;

//    public static double difference(int color1, int color2) {
//        if (((color1 ^ color2) & 0x80) == 0x80) return Double.POSITIVE_INFINITY;
//        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
//                indexB = (color2 >>> 17 & 0x7C00) | (color2 >>> 14 & 0x3E0) | (color2 >>> 11 & 0x1F);
//        float
//                L = OKLAB[0][indexA] - OKLAB[0][indexB],
//                A = OKLAB[1][indexA] - OKLAB[1][indexB],
//                B = OKLAB[2][indexA] - OKLAB[2][indexB];
//        L *= L;
//        A *= A;
//        B *= B;
//        return (L * L + A * A + B * B) * 0x1p+27;
//    }
//    public static double difference(int color1, int r2, int g2, int b2) {
//        if ((color1 & 0x80) == 0) return Double.POSITIVE_INFINITY;
//        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
//                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
//        float
//                L = OKLAB[0][indexA] - OKLAB[0][indexB],
//                A = OKLAB[1][indexA] - OKLAB[1][indexB],
//                B = OKLAB[2][indexA] - OKLAB[2][indexB];
//        L *= L;
//        A *= A;
//        B *= B;
//        return (L * L + A * A + B * B) * 0x1p+27;
//    }
//    public static double difference(int r1, int g1, int b1, int r2, int g2, int b2) {
//        int indexA = (r1 << 7 & 0x7C00) | (g1 << 2 & 0x3E0) | (b1 >>> 3),
//                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
//        float
//                L = OKLAB[0][indexA] - OKLAB[0][indexB],
//                A = OKLAB[1][indexA] - OKLAB[1][indexB],
//                B = OKLAB[2][indexA] - OKLAB[2][indexB];
//        L *= L;
//        A *= A;
//        B *= B;
//        return (L * L + A * A + B * B) * 0x1p+27;
//    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceMatch(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceMatch(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceAnalyzing(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceAnalyzing(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceHW(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param color2 the second color, as an RGBA8888 int
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceHW(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.MAX_VALUE;
        return differenceHW(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, color2 >>> 24, color2 >>> 16 & 0xFF, color2 >>> 8 & 0xFF);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * If you want to change this, just change {@link #differenceMatch(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceMatch(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25. If
     * you want to change this, just change {@link #differenceAnalyzing(int, int, int, int, int, int)}, which this
     * calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceAnalyzing(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25. If
     * you want to change this, just change {@link #differenceHW(int, int, int, int, int, int)}, which this calls.
     * @param color1 the first color, as an RGBA8888 int
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceHW(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.MAX_VALUE;
        return differenceHW(color1 >>> 24, color1 >>> 16 & 0xFF, color1 >>> 8 & 0xFF, r2, g2, b2);
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when matching to an existing palette, as with {@link #exact(int[])}.
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * PaletteReducer to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceMatch(int r1, int g1, int b1, int r2, int g2, int b2) {
        final int idx1 = ((r1 << 7) & 0x7C00) | ((g1 << 2) & 0x3E0) | ((b1 >>> 3));
        final int idx2 = ((r2 << 7) & 0x7C00) | ((g2 << 2) & 0x3E0) | ((b2 >>> 3));
        final double dL = (OKLAB[0][idx1] - OKLAB[0][idx2]) * 512.0;
        final double dA = (OKLAB[1][idx1] - OKLAB[1][idx2]) * 512.0;
        final double dB = (OKLAB[2][idx1] - OKLAB[2][idx2]) * 512.0;
        return (dL * dL + dA * dA + dB * dB);

//        double rf = (EXACT_LOOKUP[r1] - EXACT_LOOKUP[r2]) * 1.55;// rf *= rf;// * 0.875;
//        double gf = (EXACT_LOOKUP[g1] - EXACT_LOOKUP[g2]) * 2.05;// gf *= gf;// * 0.75;
//        double bf = (EXACT_LOOKUP[b1] - EXACT_LOOKUP[b2]) * 0.90;// bf *= bf;// * 1.375;
//
//        return  (rf * rf + gf * gf + bf * bf) * 0x1.8p17;
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when analyzing an image, as with {@link #analyze(Pixmap)}.
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * PaletteReducer to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance between colors 1 and 2
     */
    public double differenceAnalyzing(int r1, int g1, int b1, int r2, int g2, int b2) {
        final int idx1 = ((r1 << 7) & 0x7C00) | ((g1 << 2) & 0x3E0) | ((b1 >>> 3));
        final int idx2 = ((r2 << 7) & 0x7C00) | ((g2 << 2) & 0x3E0) | ((b2 >>> 3));
        final double dL = (OKLAB[0][idx1] - OKLAB[0][idx2]) * 512.0;
        final double dA = (OKLAB[1][idx1] - OKLAB[1][idx2]) * 512.0;
        final double dB = (OKLAB[2][idx1] - OKLAB[2][idx2]) * 512.0;
        return (dL * dL + dA * dA + dB * dB);

//        int rf = (r1 - r2);
//        int gf = (g1 - g2);
//        int bf = (b1 - b2);
//        return (rf * rf + gf * gf + bf * bf);

//        double rf = (ANALYTIC_LOOKUP[r1] - ANALYTIC_LOOKUP[r2]);
//        double gf = (ANALYTIC_LOOKUP[g1] - ANALYTIC_LOOKUP[g2]);
//        double bf = (ANALYTIC_LOOKUP[b1] - ANALYTIC_LOOKUP[b2]);
//
//        return (rf * rf + gf * gf + bf * bf) * 0x1.4p17;
    }

    /**
     * Gets a squared estimate of how different two colors are, with noticeable differences typically at least 25.
     * This can be changed in an extending (possibly anonymous) class to use a different squared metric. This is used
     * when analyzing an image with {@link #analyzeHueWise(Pixmap, double, int)} .
     * <br>
     * This uses Euclidean distance between the RGB colors in the 256-edge-length color cube. This does absolutely
     * nothing fancy with the colors, but this approach does well often. The same code is used by
     * {@link #differenceMatch(int, int, int, int, int, int)},
     * {@link #differenceAnalyzing(int, int, int, int, int, int)}, and
     * {@link #differenceHW(int, int, int, int, int, int)}, but classes can (potentially anonymously) subclass
     * PaletteReducer to change one, some, or all of these methods. The other difference methods call the 6-argument
     * overloads, so the override only needs to affect one method.
     *
     * @param r1 red of the first color, from 0 to 255
     * @param g1 green of the first color, from 0 to 255
     * @param b1 blue of the first color, from 0 to 255
     * @param r2 red of the second color, from 0 to 255
     * @param g2 green of the second color, from 0 to 255
     * @param b2 blue of the second color, from 0 to 255
     * @return the squared Euclidean distance, between colors 1 and 2
     */
    public double differenceHW(int r1, int g1, int b1, int r2, int g2, int b2) {
        final int idx1 = ((r1 << 7) & 0x7C00) | ((g1 << 2) & 0x3E0) | ((b1 >>> 3));
        final int idx2 = ((r2 << 7) & 0x7C00) | ((g2 << 2) & 0x3E0) | ((b2 >>> 3));
        final double dL = (OKLAB[0][idx1] - OKLAB[0][idx2]) * 512.0;
        final double dA = (OKLAB[1][idx1] - OKLAB[1][idx2]) * 512.0;
        final double dB = (OKLAB[2][idx1] - OKLAB[2][idx2]) * 512.0;
        return (dL * dL + dA * dA + dB * dB);

//        int rf = (r1 - r2);
//        int gf = (g1 - g2);
//        int bf = (b1 - b2);
//        return (rf * rf + gf * gf + bf * bf);

//        double rf = (ANALYTIC_LOOKUP[r1] - ANALYTIC_LOOKUP[r2]);
//        double gf = (ANALYTIC_LOOKUP[g1] - ANALYTIC_LOOKUP[g2]);
//        double bf = (ANALYTIC_LOOKUP[b1] - ANALYTIC_LOOKUP[b2]);

//        return (rf * rf + gf * gf + bf * bf) * 0x1.4p17;
    }

    /**
     * Resets the palette to the 256-color (including transparent) "Aurora" palette. PaletteReducer already
     * stores most of the calculated data needed to use this one palette. Note that this uses a more-detailed
     * and higher-quality metric than you would get by just specifying
     * {@code new PaletteReducer(PaletteReducer.AURORA)}; this metric would be too slow to calculate at
     * runtime, but as pre-calculated data it works very well.
     */
    public void setDefaultPalette(){
        exact(AURORA, ENCODED_AURORA);
    }
    /**
     * Builds the palette information this PNG8 stores from the RGBA8888 ints in {@code rgbaPalette}, up to 256 colors.
     * Alpha is not preserved except for the first item in rgbaPalette, and only if it is {@code 0} (fully transparent
     * black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only has one color, then
     * this defaults to the "Aurora" palette with 256 well-distributed colors (including transparent).
     *
     * @param rgbaPalette an array of RGBA8888 ints; all will be used up to 256 items or the length of the array
     */
    public void exact(int[] rgbaPalette) {
        exact(rgbaPalette, 256);
    }
    /**
     * Builds the palette information this PNG8 stores from the RGBA8888 ints in {@code rgbaPalette}, up to 256 colors
     * or {@code limit}, whichever is less.
     * Alpha is not preserved except for the first item in rgbaPalette, and only if it is {@code 0} (fully transparent
     * black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only has one color, or if
     * limit is less than 2, then this defaults to the "Aurora" palette with 256 well-distributed colors (including
     * transparent).
     *
     * @param rgbaPalette an array of RGBA8888 ints; all will be used up to 256 items or the length of the array
     * @param limit       a limit on how many int items to use from rgbaPalette; useful if rgbaPalette is from an IntArray
     */
    public void exact(int[] rgbaPalette, int limit) {
        if (rgbaPalette == null || rgbaPalette.length < 2 || limit < 2) {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, limit), rgbaPalette.length);
        colorCount = plen;
        populationBias = (float) Math.exp(-1.375/colorCount);
        int color, c2;
        double dist;
        for (int i = 0; i < plen; i++) {
            color = rgbaPalette[i];
            if ((color & 0x80) != 0) {
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
            }
        }
        int rr, gg, bb;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = 1E100;
                        for (int i = 1; i < plen; i++) {
                            if (dist > (dist = Math.min(dist, differenceMatch(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
   }

    /**
     * Builds the palette information this PaletteReducer stores from the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[])} for more info) and an encoded byte array to use to look up pre-loaded color data. The
     * encoded byte array can be copied out of the {@link #paletteMapping} of an existing PaletteReducer. There's
     * slightly more startup time spent when initially calling {@link #exact(int[])}, but it will produce the same
     * result. You can store the paletteMapping from that PaletteReducer once, however you want to store it, and send it
     * back to this on later runs.
     *
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array with exactly 32768 (or 0x8000) items, containing {@link #paletteMapping} data
     */
    public void exact(int[] palette, byte[] preload)
    {
        if(palette == null || preload == null)
        {
            System.arraycopy(AURORA, 0,  paletteArray, 0, 256);
            System.arraycopy(ENCODED_AURORA, 0,  paletteMapping, 0, 0x8000);
            colorCount = 256;
            populationBias = (float) Math.exp(-1.125 / 256.0);
            return;
        }
        colorCount = Math.min(256, palette.length);
        System.arraycopy(palette, 0,  paletteArray, 0, colorCount);
        System.arraycopy(preload, 0,  paletteMapping, 0, 0x8000);
        populationBias = (float) Math.exp(-1.375/colorCount);
    }

    /**
     * Builds the palette information this PaletteReducer stores from the Color objects in {@code colorPalette}, up to
     * 256 colors.
     * Alpha is not preserved except for the first item in colorPalette, and only if its r, g, b, and a values are all
     * 0f (fully transparent black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only
     * has one color, then this defaults to the "Aurora" palette with 256 well-distributed colors (including
     * transparent).
     *
     * @param colorPalette an array of Color objects; all will be used up to 256 items or the length of the array
     */
    public void exact(Color[] colorPalette) {
        exact(colorPalette, 256);
    }
    
    /**
     * Builds the palette information this PaletteReducer stores from the Color objects in {@code colorPalette}, up to
     * 256 colors or {@code limit}, whichever is less.
     * Alpha is not preserved except for the first item in colorPalette, and only if its r, g, b, and a values are all
     * 0f (fully transparent black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, only has
     * one color, or limit is less than 2, then this defaults to the "Aurora" palette with 256 well-distributed
     * colors (including transparent).
     *
     * @param colorPalette an array of Color objects; all will be used up to 256 items, limit, or the length of the array
     * @param limit        a limit on how many Color items to use from colorPalette; useful if colorPalette is from an Array
     */
    public void exact(Color[] colorPalette, int limit) {
        if (colorPalette == null || colorPalette.length < 2 || limit < 2) {
            exact(AURORA, ENCODED_AURORA);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, colorPalette.length), limit);
        colorCount = plen;
        populationBias = (float) Math.exp(-1.375/colorCount);
        int color, c2;
        double dist;

        for (int i = 0; i < plen; i++) {
            color = Color.rgba8888(colorPalette[i]);
            paletteArray[i] = color;
            paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
        }
        int rr, gg, bb;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = 0x7FFFFFFF;
                        for (int i = 1; i < plen; i++) {
                            if (dist > (dist = Math.min(dist, differenceMatch(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }
    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or less colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); this will always reserve a palette
     * entry for transparent (even if the image has no transparency) because it uses palette index 0 in its analysis
     * step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * uses a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a value of at least
     * 100, it is
     * allowed in the palette, otherwise it is kept out for being too similar to existing colors. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} field or can be used directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     */
    public void analyze(Pixmap pixmap) {
        analyze(pixmap, 100);
    }

    protected static final Comparator<IntIntMap.Entry> entryComparator = new Comparator<IntIntMap.Entry>() {
        @Override
        public int compare(IntIntMap.Entry o1, IntIntMap.Entry o2) {
            return o2.value - o1.value;
        }
    };

//    private static final Comparator<IntFloatMap.Entry> intFloatEntryComparator = new Comparator<IntFloatMap.Entry>() {
//        @Override
//        public int compare(IntFloatMap.Entry o1, IntFloatMap.Entry o2) {
//            return NumberUtils.floatToIntBits(o2.value - o1.value);
//        }
//    };

    /**
     * Just like Comparator, but compares primitive ints.
     */
    public interface IntComparator {
        /**
         * Compares its two primitive-type arguments for order. Returns a negative
         * integer, zero, or a positive integer as the first argument is less than,
         * equal to, or greater than the second.
         *
         * @return a negative integer, zero, or a positive integer as the first argument
         * is less than, equal to, or greater than the second.
         * @see Comparator
         */
        int compare(int k1, int k2);
    }

    /**
     * Compares shrunken indices (RGB555) by lightness as Oklab knows it.
     */
    protected static final IntComparator lightnessComparator = new IntComparator() {
        @Override
        public int compare(int k1, int k2) {
            return NumberUtils.floatToIntBits(OKLAB[0][k2] - OKLAB[0][k1]);
        }
    };

    /**
     * Compares shrunken indices (RGB555) by hue as Oklab knows it.
     */
    protected static final IntComparator hueComparator = new IntComparator() {
        @Override
        public int compare(int k1, int k2) {
            return NumberUtils.floatToIntBits(OKLAB[3][k2] - OKLAB[3][k1]);
        }
    };

    /// Start of code primarily based on FastUtil.
    /// See https://github.com/vigna/fastutil/blob/8.5.8/LICENSE-2.0 for
    // the license of this block (Apache License 2.0).

    private static void swap (int[] items, int first, int second) {
        int firstValue = items[first];
        items[first] = items[second];
        items[second] = firstValue;
    }

    /**
     * Transforms two consecutive sorted ranges into a single sorted range. The initial ranges are
     * {@code [first..middle)} and {@code [middle..last)}, and the resulting range is
     * {@code [first..last)}. Elements in the first input range will precede equal elements in
     * the second.
     */
    private static void inPlaceMerge (int[] items, final int from, int mid, final int to, final IntComparator comp) {
        if (from >= mid || mid >= to) {return;}
        if (to - from == 2) {
            if (comp.compare(items[mid], items[from]) < 0) {swap(items, from, mid);}
            return;
        }

        int firstCut;
        int secondCut;

        if (mid - from > to - mid) {
            firstCut = from + (mid - from) / 2;
            secondCut = lowerBound(items, mid, to, firstCut, comp);
        } else {
            secondCut = mid + (to - mid) / 2;
            firstCut = upperBound(items, from, mid, secondCut, comp);
        }

        int first2 = firstCut;
        int middle2 = mid;
        int last2 = secondCut;
        if (middle2 != first2 && middle2 != last2) {
            int first1 = first2;
            int last1 = middle2;
            while (first1 < --last1) {swap(items, first1++, last1);}
            first1 = middle2;
            last1 = last2;
            while (first1 < --last1) {swap(items, first1++, last1);}
            first1 = first2;
            last1 = last2;
            while (first1 < --last1) {swap(items, first1++, last1);}
        }

        mid = firstCut + secondCut - mid;
        inPlaceMerge(items, from, firstCut, mid, comp);
        inPlaceMerge(items, mid, secondCut, to, comp);
    }

    /**
     * Performs a binary search on an already-sorted range: finds the first position where an
     * element can be inserted without violating the ordering. Sorting is by a user-supplied
     * comparison function.
     *
     * @param items the int array to be sorted
     * @param from  the index of the first element (inclusive) to be included in the binary search.
     * @param to    the index of the last element (exclusive) to be included in the binary search.
     * @param pos   the position of the element to be searched for.
     * @param comp  the comparison function.
     * @return the largest index i such that, for every j in the range {@code [first..i)},
     * {@code comp.compare(get(j), get(pos))} is {@code true}.
     */
    private static int lowerBound (int[] items, int from, final int to, final int pos, final IntComparator comp) {
        int len = to - from;
        while (len > 0) {
            int half = len / 2;
            int middle = from + half;
            if (comp.compare(items[middle], items[pos]) < 0) {
                from = middle + 1;
                len -= half + 1;
            } else {
                len = half;
            }
        }
        return from;
    }

    /**
     * Performs a binary search on an already sorted range: finds the last position where an element
     * can be inserted without violating the ordering. Sorting is by a user-supplied comparison
     * function.
     *
     * @param items the int array to be sorted
     * @param from  the index of the first element (inclusive) to be included in the binary search.
     * @param to    the index of the last element (exclusive) to be included in the binary search.
     * @param pos   the position of the element to be searched for.
     * @param comp  the comparison function.
     * @return The largest index i such that, for every j in the range {@code [first..i)},
     * {@code comp.compare(get(pos), get(j))} is {@code false}.
     */
    private static int upperBound (int[] items, int from, final int to, final int pos, final IntComparator comp) {
        int len = to - from;
        while (len > 0) {
            int half = len / 2;
            int middle = from + half;
            if (comp.compare(items[pos], items[middle]) < 0) {
                len = half;
            } else {
                from = middle + 1;
                len -= half + 1;
            }
        }
        return from;
    }

    /**
     * Sorts all of {@code items} by simply calling {@link #sort(int[], int, int, IntComparator)},
     * setting {@code from} and {@code to} so the whole array is sorted.
     *
     * @param items the int array to be sorted
     * @param c     a IntComparator to alter the sort order; if null, the natural order will be used
     */
    public static void sort (int[] items, final IntComparator c) {
        sort(items, 0, items.length, c);
    }

    /**
     * Sorts the specified range of elements according to the order induced by the specified
     * comparator using mergesort.
     *
     * <p>This sort is guaranteed to be <i>stable</i>: equal elements will not be reordered as a result
     * of the sort. The sorting algorithm is an in-place mergesort that is significantly slower than a
     * standard mergesort, as its running time is <i>O</i>(<var>n</var>&nbsp;(log&nbsp;<var>n</var>)<sup>2</sup>),
     * but it does not allocate additional memory; as a result, it can be
     * used as a generic sorting algorithm.
     *
     * <p>If and only if {@code c} is null, this will delegate to {@link Arrays#sort(int[], int, int)}, which
     * does not have the same guarantees regarding allocation.
     *
     * @param items the int array to be sorted
     * @param from  the index of the first element (inclusive) to be sorted.
     * @param to    the index of the last element (exclusive) to be sorted.
     * @param c     a IntComparator to alter the sort order; if null, the natural order will be used
     */
    public static void sort (int[] items, final int from, final int to, final IntComparator c) {
        if (to <= 0) {
            return;
        }
        if (from < 0 || from >= items.length || to > items.length) {
            throw new UnsupportedOperationException("The given from/to range in IntComparators.sort() is invalid.");
        }
        if (c == null) {
            Arrays.sort(items, from, to);
            return;
        }
        /*
         * We retain the same method signature as quickSort. Given only a comparator and this list
         * do not know how to copy and move elements from/to temporary arrays. Hence, in contrast to
         * the JDK mergesorts this is an "in-place" mergesort, i.e. does not allocate any temporary
         * arrays. A non-inplace mergesort would perhaps be faster in most cases, but would require
         * non-intuitive delegate objects...
         */
        final int length = to - from;

        // Insertion sort on smallest arrays, less than 16 items
        if (length < 16) {
            for (int i = from; i < to; i++) {
                for (int j = i; j > from && c.compare(items[j - 1], items[j]) > 0; j--) {
                    swap(items, j, j - 1);
                }
            }
            return;
        }

        // Recursively sort halves
        int mid = from + to >>> 1;
        sort(items, from, mid, c);
        sort(items, mid, to, c);

        // If list is already sorted, nothing left to do. This is an
        // optimization that results in faster sorts for nearly ordered lists.
        if (c.compare(items[mid - 1], items[mid]) <= 0) {return;}

        // Merge sorted halves
        inPlaceMerge(items, from, mid, to, c);
    }

    //// End of code primarily from FastUtil.

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or less colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); this will always reserve a palette
     * entry for transparent (even if the image has no transparency) because it uses palette index 0 in its analysis
     * step. Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * takes a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least
     * {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 50 and 500, and 100 is a good default. Because this always uses the
     * maximum color limit, threshold should be lower than cases where the color limit is small. If the threshold is too
     * high, then some colors that would be useful to smooth out subtle color changes won't get considered, and colors
     * may change more abruptly. This doesn't return a value but instead stores the palette info in this object; a
     * PaletteReducer can be assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields or can be used
     * directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)} ; usually between 50 and 500, 100 is a good default
     */
    public void analyze(Pixmap pixmap, double threshold) {
        analyze(pixmap, threshold, 256);
    }
    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or fewer colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 500, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap pixmap, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (differenceAnalyzing(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or fewer colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); this will always reserve a palette entry for transparent (even if the
     * image has no transparency) because it uses palette index 0 in its analysis step. Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceHW(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 500, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     * <br>
     * The algorithm here isn't incredibly fast, but is often better at preserving colors that are used often enough to
     * be important to an image, but not often enough to appear in a small palette produced by {@link #analyze(Pixmap)}.
     * It involves sorting about 10% of the pixels in the image by hue, dividing up those pixels into evenly-sized
     * ranges, then sorting those ranges individually by lightness and dividing those into sub-ranges. The sub-ranges
     * have their chroma channels averaged (these already have similar hue, so this mostly affects saturation), and
     * their lightness averaged but pushed towards more extreme values using
     * {@link OtherMath#barronSpline(float, float, float)}. This last step works well with dithering.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by PNG8, or by AnimatedGif
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeHueWise(Pixmap pixmap, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 3), 256);
        threshold /= Math.pow(limit, 1.35) * 0.000215;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        IntArray enc = new IntArray(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                    if(((x & y) * 5 & 31) < 3)
                        enc.add(shrink(color));
                }
            }
        }
        int cs = counts.size;
        if (cs < limit) {
            Array<IntIntMap.Entry> es = new Array<>(cs);
            for(IntIntMap.Entry e : counts)
            {
                IntIntMap.Entry e2 = new IntIntMap.Entry();
                e2.key = e.key;
                e2.value = e.value;
                es.add(e2);
            }
            es.sort(entryComparator);
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // generate colors
        {
            final int[] ei = enc.items;
            sort(ei, 0, enc.size, hueComparator);
            paletteArray[1] = -1; // white
            paletteArray[2] = 255; // black
            int i = 3, encs = enc.size, segments = Math.min(encs, limit - 3) + 1 >> 1, e = 0;
            double lightPieces = Math.ceil(Math.log(limit));
            PER_BEST:
            for (int s = 0; i < limit; s++) {
                if(e > (e %= encs)){
                    segments++;
                    lightPieces++;
                    threshold *= 0.9;
                }
                s %= segments;
                int segStart = e, segEnd = Math.min(segStart + (int)Math.ceil(encs / (double)segments), encs), segLen = segEnd - segStart;
                sort(ei, segStart, segLen, lightnessComparator);
                for (int li = 0; li < lightPieces && li < segLen && i < limit; li++) {
                    int start = e, end = Math.min(encs, start + (int)Math.ceil(segLen / lightPieces)), len = end - start;

                    float totalL = 0.0f, totalA = 0.0f, totalB = 0.0f;
                    for (; e < end; e++) {
                        int index = ei[e];
                        totalL += OKLAB[0][index];
                        totalA += OKLAB[1][index];
                        totalB += OKLAB[2][index];
                    }
                    totalA /= len;
                    totalB /= len;
                    color = oklabToRGB(
                            OtherMath.barronSpline(totalL / len, 3f, 0.5f),
                            totalA,//(OtherMath.cbrt(totalA) + 31f * totalA) * 0x1p-5f,
                            totalB,//(OtherMath.cbrt(totalB) + 31f * totalB) * 0x1p-5f,
                            1f);
//                    (OtherMath.barronSpline(totalA / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,
//                    (OtherMath.barronSpline(totalB / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,

                    for (int j = 3; j < i; j++) {
                        if (differenceHW(color, paletteArray[j]) < threshold)
                            continue PER_BEST;
                    }
                    paletteArray[i] = color;
                    paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                    i++;
                }
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceHW(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or less colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); if there are more than {@code limit} colors or any colors have 50% or less
     * alpha, it will reserve a palette entry for transparent (even if the image has no transparency). Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #differenceAnalyzing(int, int)} ) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 50 and 500, and 100 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     * <br>
     * This does a faster and less accurate analysis, and is more suitable to do on each frame of a large animation when
     * time is better spent making more images than fewer images at higher quality. It should be about 5 times faster
     * than {@link #analyze(Pixmap, double, int)} with the same parameters.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeFast(Pixmap pixmap, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                }
            }
        }
        int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                ++i;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (differenceAnalyzing(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        if(colorCount <= 1)
            return;
        int c2;
        byte bt;
        int numUnassigned = 1, iterations = 0;
        byte[] buffer = Arrays.copyOf(paletteMapping, 0x8000);
        while (numUnassigned != 0) {
            numUnassigned = 0;
            for (int r = 0; r < 32; r++) {
                for (int g = 0; g < 32; g++) {
                    for (int b = 0; b < 32; b++) {
                        c2 = r << 10 | g << 5 | b;
                        if (buffer[c2] == 0) {
                            if(iterations++ != 2){
                                if (b < 31 && (bt = paletteMapping[c2 + 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && (bt = paletteMapping[c2 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r < 31 && (bt = paletteMapping[c2 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && (bt = paletteMapping[c2 - 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && (bt = paletteMapping[c2 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r > 0 && (bt = paletteMapping[c2 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else numUnassigned++;
                            }
                            else {
                                iterations = 0;
                                if (b < 31 && (bt = paletteMapping[c2 + 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && (bt = paletteMapping[c2 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r < 31 && (bt = paletteMapping[c2 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && (bt = paletteMapping[c2 - 1]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && (bt = paletteMapping[c2 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (r > 0 && (bt = paletteMapping[c2 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && g < 31 && (bt = paletteMapping[c2 + 1 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && r < 31 && (bt = paletteMapping[c2 + 1 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && r < 31 && (bt = paletteMapping[c2 + 32 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && g > 0 && (bt = paletteMapping[c2 - 1 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && r > 0 && (bt = paletteMapping[c2 - 1 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && r > 0 && (bt = paletteMapping[c2 - 32 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && g > 0 && (bt = paletteMapping[c2 + 1 - 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b < 31 && r > 0 && (bt = paletteMapping[c2 + 1 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g < 31 && r > 0 && (bt = paletteMapping[c2 + 32 - 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && g < 31 && (bt = paletteMapping[c2 - 1 + 32]) != 0)
                                    buffer[c2] = bt;
                                else if (b > 0 && r < 31 && (bt = paletteMapping[c2 - 1 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else if (g > 0 && r < 31 && (bt = paletteMapping[c2 - 32 + 1024]) != 0)
                                    buffer[c2] = bt;
                                else numUnassigned++;

                            }
                        }
                    }
                }
            }
            System.arraycopy(buffer, 0, paletteMapping, 0, 0x8000);
        }
    }

    public void analyzeMC(Pixmap pixmap, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntArray bin = new IntArray(width * height);
        IntIntMap counts = new IntIntMap(limit);
        int hasTransparent = 0;
        int rangeR, rangeG, rangeB;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y) & 0xF8F8F880;
                if ((color & 0x80) != 0) {
                    bin.add(color |= (color >>> 5 & 0x07070700) | 0xFF);
                    counts.getAndIncrement(color, 0, 1);
                } else {
                    hasTransparent = 1;
                }
            }
        }
        limit = Math.max(2 - hasTransparent, Math.min(limit - hasTransparent, 256));
        if(counts.size > limit) {
            int numCuts = 32 - Integer.numberOfLeadingZeros(limit - 1);
            int offset, end = bin.size;
            int[] in = bin.items, out = new int[end],
                    bufR = new int[32],
                    bufG = new int[32],
                    bufB = new int[32];
            for (int stage = 0; stage < numCuts; stage++) {
                int size = bin.size >>> stage;
                offset = 0;
                end = 0;
                for (int part = 1 << stage; part > 0; part--) {
                    if (part == 1)
                        end = bin.size;
                    else
                        end += size;
                    Arrays.fill(bufR, 0);
                    Arrays.fill(bufG, 0);
                    Arrays.fill(bufB, 0);
                    for (int i = offset, ii; i < end; i++) {
                        ii = in[i];
                        bufR[ii >>> 27]++;
                        bufG[ii >>> 19 & 31]++;
                        bufB[ii >>> 11 & 31]++;
                    }
                    for (rangeR = 32; rangeR > 0 && bufR[rangeR - 1] == 0; rangeR--) ;
                    for (int r = 0; r < rangeR && bufR[r] == 0; r++, rangeR--) ;
                    for (rangeG = 32; rangeG > 0 && bufG[rangeG - 1] == 0; rangeG--) ;
                    for (int r = 0; r < rangeG && bufG[r] == 0; r++, rangeG--) ;
                    for (rangeB = 32; rangeB > 0 && bufB[rangeB - 1] == 0; rangeB--) ;
                    for (int r = 0; r < rangeB && bufB[r] == 0; r++, rangeB--) ;

                    if (rangeG >= rangeR && rangeG >= rangeB)
                    {
                        for (int i = 1; i < 32; i++)
                            bufG[i] += bufG[i - 1];
                        for (int i = end - 1; i >= offset; i--)
                            out[offset + --bufG[in[i] >>> 19 & 31]] = in[i];
                    }
                    else if (rangeR >= rangeG && rangeR >= rangeB)
                    {
                        for (int i = 1; i < 32; i++)
                            bufR[i] += bufR[i - 1];
                        for (int i = end - 1; i >= offset; i--)
                            out[offset + --bufR[in[i] >>> 27]] = in[i];
                    }
                    else
                    {
                        for (int i = 1; i < 32; i++)
                            bufB[i] += bufB[i - 1];
                        for (int i = end - 1; i >= offset; i--)
                            out[offset + --bufB[in[i] >>> 11 & 31]] = in[i];
                    }
                    offset += size;
                }
            }
            int jump = out.length >>> numCuts, mid = 0, assigned = 0;
            double fr = 270.0 / (jump * 31.0);
            for (int n = (1 << numCuts) - 1; assigned < n; assigned++, mid += jump) {
                double r = 0, g = 0, b = 0;
                for (int i = mid + jump - 1; i >= mid; i--) {
                    color = out[i];
                    r += color >>> 27;
                    g += color >>> 19 & 31;
                    b += color >>> 11 & 31;
                }
                paletteArray[assigned] =
                        Math.min(Math.max((int)((r - 7.0) * fr), 0), 255) << 24 |
                                Math.min(Math.max((int)((g - 7.0) * fr), 0), 255) << 16 |
                                Math.min(Math.max((int)((b - 7.0) * fr), 0), 255) << 8 | 0xFF;
            }
            {
                int j2 = out.length - (mid - jump);
                double r = 0, g = 0, b = 0, fr2 = 270.0 / (j2 * 31.0);
                for (int i = out.length - 1; i >= mid; i--) {
                    color = out[i];
                    r += color >>> 27;
                    g += color >>> 19 & 31;
                    b += color >>> 11 & 31;
                }
                paletteArray[assigned++] =
                        Math.min(Math.max((int)((r - 7.0) * fr2), 0), 255) << 24 |
                                Math.min(Math.max((int)((g - 7.0) * fr2), 0), 255) << 16 |
                                Math.min(Math.max((int)((b - 7.0) * fr2), 0), 255) << 8 | 0xFF;
            }
//            int jump = out.length >>> numCuts, mid = jump >>> 1, assigned = 0;
//            for (int n = 1 << numCuts; assigned < n; assigned++, mid += jump) {
//                paletteArray[assigned] = out[mid];
//            }
            COLORS:
            for (int i = limit; i < assigned; i++) {
                int currentCount = counts.get(paletteArray[i], 0);
                for (int j = 0; j < limit; j++) {
                    if(counts.get(paletteArray[j], 0) < currentCount)
                    {
                        int temp = paletteArray[j];
                        paletteArray[j] = paletteArray[i];
                        paletteArray[i] = temp;
                        continue COLORS;
                    }
                }
            }
            if(hasTransparent == 1) {
                int min = Integer.MAX_VALUE, worst = 0;
                for (int i = 0; i < limit; i++) {
                    int currentCount = counts.get(paletteArray[i], 0);
                    if(currentCount < min){
                        min = currentCount;
                        worst = i;
                    }
                }
                if (worst != 0) {
                    paletteArray[worst] = paletteArray[0];
                }
                paletteArray[0] = 0;
            }
//            COLORS:
//            for (; mid < out.length; mid += jump) {
//                int currentCount = counts.get(out[mid], 0);
//                for (int i = limit - 1; i > hasTransparent; i--) {
//                    if(counts.get(paletteArray[i], 0) < currentCount)
//                    {
//                        paletteArray[i] = out[mid];
//                        continue COLORS;
//                    }
//                }
//            }
            for (int i = hasTransparent; i < limit; i++) {
                color = paletteArray[i];
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
            }
            colorCount = limit;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }
        else
        { 
            IntIntMap.Keys it = counts.keys();
            Arrays.fill(paletteArray, 0);
            for (int i = hasTransparent; i < limit && it.hasNext; i++) {
                paletteArray[i] = color = it.next();
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
            }
            colorCount = counts.size + hasTransparent;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.POSITIVE_INFINITY;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }

    }


    public int blend(int rgba1, int rgba2, float preference) {
        int a1 = rgba1 & 255, a2 = rgba2 & 255;
        if((a1 & 0x80) == 0) return rgba2;
        else if((a2 & 0x80) == 0) return rgba1;
        rgba1 = shrink(rgba1);
        rgba2 = shrink(rgba2);
        float L = OKLAB[0][rgba1] + (OKLAB[0][rgba2] - OKLAB[0][rgba1]) * preference;
        float A = OKLAB[1][rgba1] + (OKLAB[1][rgba2] - OKLAB[1][rgba1]) * preference;
        float B = OKLAB[2][rgba1] + (OKLAB[2][rgba2] - OKLAB[2][rgba1]) * preference;
        return oklabToRGB(L, A, B, (a1 + (a2 - a1) * preference) * (1f/255f));
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by
     * {@link #differenceAnalyzing(int, int, int, int)}) by a
     * value of at least 100, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. This doesn't return a value but instead stores the palette info in this object; a PaletteReducer can be
     * assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     */
    public void analyze(Array<Pixmap> pixmaps){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, 100, 256);
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 500, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     */
    public void analyze(Array<Pixmap> pixmaps, double threshold){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, 256);
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 500, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Array<Pixmap> pixmaps, double threshold, int limit){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, limit);
    }
    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceAnalyzing(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 500, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #differenceAnalyzing(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap[] pixmaps, int pixmapCount, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 2), 256);
        threshold /= Math.min(0.45, Math.pow(limit + 16, 1.45) * 0.0002);
        IntIntMap counts = new IntIntMap(limit);
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    color = pixmap.getPixel(x, y) & 0xF8F8F880;
                    if ((color & 0x80) != 0) {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        counts.getAndIncrement(color, 0, 1);
                    }
                }
            }
        }
        final int cs = counts.size;
        Array<IntIntMap.Entry> es = new Array<>(cs);
        for(IntIntMap.Entry e : counts)
        {
            IntIntMap.Entry e2 = new IntIntMap.Entry();
            e2.key = e.key;
            e2.value = e.value;
            es.add(e2);
        }
        es.sort(entryComparator);
        if (cs < limit) {
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            for (; i < limit && c < cs;) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    double diff = differenceAnalyzing(color, paletteArray[j]);
                    if (diff < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
            populationBias = (float) Math.exp(-1.375/colorCount);
        }

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceAnalyzing(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Analyzes all the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by
     * {@link #differenceHW(int, int, int, int)}) by a
     * value of at least 100, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. This doesn't return a value but instead stores the palette info in this object; a PaletteReducer can be
     * assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     */
    public void analyzeHueWise(Array<Pixmap> pixmaps){
        analyzeHueWise(pixmaps.toArray(Pixmap.class), pixmaps.size, 100, 256);
    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceHW(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 500, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceHW(int, int)}; usually between 50 and 500, 100 is a good default
     */
    public void analyzeHueWise(Array<Pixmap> pixmaps, double threshold){
        analyzeHueWise(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, 256);
    }
    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceHW(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 500, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #differenceHW(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeHueWise(Array<Pixmap> pixmaps, double threshold, int limit){
        analyzeHueWise(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, limit);
    }
    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #differenceHW(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 50 and 500, and 100 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #differenceHW(int, int)}; usually between 50 and 500, 100 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyzeHueWise(Pixmap[] pixmaps, int pixmapCount, double threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        limit = Math.min(Math.max(limit, 3), 256);
        threshold /= Math.pow(limit, 1.35) * 0.000215;
        final int w0 = pixmaps[0].getWidth(), h0 = pixmaps[0].getHeight();
        IntIntMap counts = new IntIntMap(limit);
        IntArray enc = new IntArray(w0 * h0 * pixmapCount / 10);
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    color = pixmap.getPixel(x, y) & 0xF8F8F880;
                    if ((color & 0x80) != 0) {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        counts.getAndIncrement(color, 0, 1);
                        if(((x & y) * 5 - i & 31) < 3)
                            enc.add(shrink(color));
                    }
                }
            }
        }
        final int cs = counts.size;
        if (cs < limit) {
            Array<IntIntMap.Entry> es = new Array<>(cs);
            for(IntIntMap.Entry e : counts)
            {
                IntIntMap.Entry e2 = new IntIntMap.Entry();
                e2.key = e.key;
                e2.value = e.value;
                es.add(e2);
            }
            es.sort(entryComparator);
            int i = 1;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                reds[i] = color >>> 24;
                greens[i] = color >>> 16 & 255;
                blues[i] = color >>> 8 & 255;
                i++;
            }
            colorCount = i;
        }
        else // generate colors
        {
            final int[] ei = enc.items;
            sort(ei, 0, enc.size, hueComparator);
            paletteArray[1] = -1; // white
            reds[1] = 255;
            greens[1] = 255;
            blues[1] = 255;
            paletteArray[2] = 255; // black
            reds[2] = 0;
            greens[2] = 0;
            blues[2] = 0;
            int i = 3, encs = enc.size, segments = Math.min(encs, limit - 3) + 1 >> 1, e = 0;
            double lightPieces = Math.ceil(Math.log(limit));
            PER_BEST:
            for (int s = 0; i < limit; s++) {
                if(e > (e %= encs)){
                    segments++;
                    lightPieces++;
                    threshold *= 0.9;
                }
                s %= segments;
                int segStart = e, segEnd = Math.min(segStart + (int)Math.ceil(encs / (double)segments), encs), segLen = segEnd - segStart;
                sort(ei, segStart, segLen, lightnessComparator);
                for (int li = 0; li < lightPieces && li < segLen && i < limit; li++) {
                    int start = e, end = Math.min(encs, start + (int)Math.ceil(segLen / lightPieces)), len = end - start;

                    float totalL = 0.0f, totalA = 0.0f, totalB = 0.0f;
                    for (; e < end; e++) {
                        int index = ei[e];
                        totalL += OKLAB[0][index];
                        totalA += OKLAB[1][index];
                        totalB += OKLAB[2][index];
                    }
                    totalA /= len;
                    totalB /= len;
                    color = oklabToRGB(
                            OtherMath.barronSpline(totalL / len, 3f, 0.5f),
                            totalA,//(OtherMath.cbrt(totalA) + 31f * totalA) * 0x1p-5f,
                            totalB,//(OtherMath.cbrt(totalB) + 31f * totalB) * 0x1p-5f,
                            1f);
//                    (OtherMath.barronSpline(totalA / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,
//                    (OtherMath.barronSpline(totalB / (len<<1)+0.5f, 2f, 0.5f)-0.5f)*2f,

                    for (int j = 3; j < i; j++) {
                        if (differenceHW(color, paletteArray[j]) < threshold)
                            continue PER_BEST;
                    }
                    paletteArray[i] = color;
                    paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                    reds[i] = color >>> 24;
                    greens[i] = color >>> 16 & 255;
                    blues[i] = color >>> 8 & 255;
                    i++;
                }
            }
            colorCount = i;
        }
        populationBias = (float) Math.exp(-1.375/colorCount);

        int c2;
        int rr, gg, bb;
        double dist;
        for (int r = 0; r < 32; r++) {
            rr = (r << 3 | r >>> 2);
            for (int g = 0; g < 32; g++) {
                gg = (g << 3 | g >>> 2);
                for (int b = 0; b < 32; b++) {
                    c2 = r << 10 | g << 5 | b;
                    if (paletteMapping[c2] == 0) {
                        bb = (b << 3 | b >>> 2);
                        dist = Double.MAX_VALUE;
                        for (int i = 1; i < colorCount; i++) {
                            if (dist > (dist = Math.min(dist, differenceHW(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the "strength" of the dither effect applied during {@link #reduce(Pixmap)} calls. The default is 1f,
     * and while both values higher than 1f and lower than 1f are valid, they should not be negative.
     * If ditherStrength is too high, all sorts of artifacts will appear; if it is too low, the effect of the dither to
     * smooth out changes in color will be very hard to notice.
     * @return the current dither strength; typically near 1.0f and always non-negative
     */
    public float getDitherStrength() {
        return ditherStrength;
    }

    /**
     * Changes the "strength" of the dither effect applied during {@link #reduce(Pixmap)} calls. The default is 1f,
     * and while both values higher than 1f and lower than 1f are valid, they should not be negative. If you want dither
     * to be eliminated, don't set dither strength to 0; use {@link #reduceSolid(Pixmap)} instead of reduce().
     * If ditherStrength is too high, all sorts of artifacts will appear; if it is too low, the effect of the dither to
     * smooth out changes in color will be very hard to notice.
     * @param ditherStrength dither strength as a non-negative float that should be close to 1f
     */
    public void setDitherStrength(float ditherStrength) {
        this.ditherStrength = Math.max(0f, ditherStrength);
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, dithering when it can by
     * using Wren dithering (this merely delegates to {@link #reduceWren(Pixmap)}).
     * If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduce (Pixmap pixmap) {
        return reduceWren(pixmap);
    }

    /**
     * Uses the given {@link Dithered.DitherAlgorithm} to decide how to dither {@code pixmap}.
     * @param pixmap a pixmap that will be modified in-place
     * @param ditherAlgorithm a dithering algorithm enum value; if not recognized, defaults to {@link Dithered.DitherAlgorithm#WREN}
     * @return {@code pixmap} after modifications
     */
    public Pixmap reduce(Pixmap pixmap, Dithered.DitherAlgorithm ditherAlgorithm){
        if(pixmap == null) return null;
        if(ditherAlgorithm == null) return reduceWren(pixmap);
        switch (ditherAlgorithm) {
            case NONE:
                return reduceSolid(pixmap);
            case GRADIENT_NOISE:
                return reduceJimenez(pixmap);
            case PATTERN:
                return reduceKnoll(pixmap);
            case CHAOTIC_NOISE:
                return reduceChaoticNoise(pixmap);
            case DIFFUSION:
                return reduceFloydSteinberg(pixmap);
            case BLUE_NOISE:
                return reduceBlueNoise(pixmap); 
            case SCATTER:
                return reduceScatter(pixmap);
            case ROBERTS:
                return reduceRoberts(pixmap);
            case WOVEN:
                return reduceWoven(pixmap);
            case DODGY:
                return reduceDodgy(pixmap);
            case LOAF:
                return reduceLoaf(pixmap);
            case NEUE:
                return reduceNeue(pixmap);
            case BLUBBER:
                return reduceBlubber(pixmap);
            default:
            case WREN:
                return reduceWren(pixmap);
        }
    }

    /**
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, without dithering. This produces
     * blocky solid sections of color in most images where the palette isn't exact, instead of checkerboard-like
     * dithering patterns. If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceSolid (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    pixmap.drawPixel(px, y, paletteArray[
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))] & 0xFF]);
                }
            }

        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, dithering when it can with
     * Sierra Lite dithering instead of the Floyd-Steinberg dithering that {@link #reduce(Pixmap)} uses.
     * If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * <p>
     * This method is similar to Floyd-Steinberg, since both are error-diffusion dithers. Sometimes Sierra Lite can
     * avoid unpleasant artifacts in Floyd-Steinberg, so it's better in the worst-case, but it isn't usually as good in
     * its best-case.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceSierraLite (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }

        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        float ditherStrength = this.ditherStrength * 20, halfDitherStrength = ditherStrength * 0.5f;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x2.4p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x2.4p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x2.4p-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));



                    if(px < lineLen - 1)
                    {
                        curErrorRed[px+1]   += rdiff * ditherStrength;
                        curErrorGreen[px+1] += gdiff * ditherStrength;
                        curErrorBlue[px+1]  += bdiff * ditherStrength;
                    }
                    if(ny < h)
                    {
                        if(px > 0)
                        {
                            nextErrorRed[px-1]   += rdiff * halfDitherStrength;
                            nextErrorGreen[px-1] += gdiff * halfDitherStrength;
                            nextErrorBlue[px-1]  += bdiff * halfDitherStrength;
                        }
                        nextErrorRed[px]   += rdiff * halfDitherStrength;
                        nextErrorGreen[px] += gdiff * halfDitherStrength;
                        nextErrorBlue[px]  += bdiff * halfDitherStrength;
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Modifies the given Pixmap so that it only uses colors present in this PaletteReducer, dithering when it can with
     * the commonly-used Floyd-Steinberg dithering. If you want to reduce the colors in a Pixmap based on what it
     * currently contains, call {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with
     * the same Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceFloydSteinberg (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }

        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        float w1 = ditherStrength * 4, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x1.8p-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x1.8p-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x1.8p-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                    if(px < lineLen - 1)
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
                        if(px < lineLen - 1)
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
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * It's interleaved gradient noise, by Jorge Jimenez! It's very fast! It's an ordered dither!
     * It's pretty good with gradients, though it may introduce artifacts. It has noticeable diagonal
     * lines in some places, but these tend to have mixed directions that obscure larger patterns.
     * This is very similar to {@link #reduceRoberts(Pixmap)}, but has different artifacts, and this
     * dither tends to be stronger by default.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceJimenez(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
        float adj;
        final float strength = 60f * ditherStrength / (populationBias * populationBias);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    adj = (px * 0.06711056f + y * 0.00583715f);
                    adj -= (int) adj;
                    adj *= 52.9829189f;
                    adj -= (int) adj;
                    adj -= 0.5f;
                    adj *= strength;
//                    adj *= adj * adj;
//                    adj *= Math.abs(adj);
//                    adj = Math.copySign((float) Math.sqrt(Math.abs(adj)), adj);
                    adj += 0.5f; // for rounding
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + adj), 0), 255);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + adj), 0), 255);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + adj), 0), 255);
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    public Pixmap reduceIgneous(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float w1 = (6f * ditherStrength * populationBias * populationBias), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 60f * ditherStrength / (populationBias * populationBias),
                adj;

        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    adj = (px * 0.06711056f + y * 0.00583715f);
                    adj -= (int) adj;
                    adj *= 52.9829189f;
                    adj -= (int) adj;
                    adj -= 0.5f;
                    adj *= strength;

                    er = adj + (curErrorRed[px]);
                    eg = adj + (curErrorGreen[px]);
                    eb = adj + (curErrorBlue[px]);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x3p-10f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x3p-10f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x3p-10f * ((color>>>8&255)- (used>>>8&255)) );

                    if(px < lineLen - 1)
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
                        if(px < lineLen - 1)
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
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * An ordered dither that uses a sub-random sequence by Martin Roberts to disperse lightness adjustments across the
     * image. This is very similar to {@link #reduceJimenez(Pixmap)}, but is milder by default, and has subtly different
     * artifacts. This should look excellent for animations, especially with small palettes, but the lightness
     * adjustments may be noticeable even in very large palettes.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceRoberts (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
//        float str = (32f * ditherStrength / (populationBias * populationBias));
//        float str = (float) (64 * ditherStrength / Math.log(colorCount * 0.3 + 1.5));
        float str = (32 * ditherStrength / (populationBias * populationBias * populationBias * populationBias));
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    // used in 0.3.10
//                    // Gets R2-based noise and puts it in the -0.75 to 0.75 range
//                    float adj = (px * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL >>> 41) * 0x1.8p-23f - 0.75f;
//                    adj = adj * str + 0.5f;
//                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + adj), 0), 255);
//                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + adj), 0), 255);
//                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + adj), 0), 255);

                    // other options
//                    // sign-preserving square root, emphasizes extremes
////                    adj = Math.copySign((float) Math.sqrt(Math.abs(adj)), adj);
//                    // sign-preserving square, emphasizes low-magnitude values
////                    adj *= Math.abs(adj);
                    // Used in 0.3.13, has a heavy color bias
//                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + ((((px-1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);
//                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + ((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);
//                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + ((((px-4) * 0xC13FA9A902A6328FL + (y+2) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-22f - 0x1.4p0f) * str + 0.5f), 0), 255);

                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    // We get a sub-random angle from 0-PI2 using the R2 sequence.
                    // This gets us an angle theta from anywhere on the circle, which we feed into three
                    // different cos() calls, each with a different offset to get 3 different angles.
                    final float theta = ((px * 0xC13FA9A902A6328FL + y * 0x91E10DA5C79E7B1DL >>> 41) * 0x1.921fb6p-21f); //0x1.921fb6p-21f is 0x1p-23f * MathUtils.PI2
                    rr = Math.min(Math.max((int)(rr + MathUtils.cos(theta        ) * str + 0.5f), 0), 255);
                    gg = Math.min(Math.max((int)(gg + MathUtils.cos(theta + 1.04f) * str + 0.5f), 0), 255);
                    bb = Math.min(Math.max((int)(bb + MathUtils.cos(theta + 2.09f) * str + 0.5f), 0), 255);
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }
    /**
     * An intentionally low-fidelity dither, meant for pixel art.
     * @param pixmap
     * @return
     */
    public Pixmap reduceLoaf(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
        final int strength = (int) (11f * ditherStrength / (populationBias * populationBias) + 0.5f);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    int adj = ((px & 1) + (y & 1) - 1) * strength * (2 + (((px ^ y) & 2) - 1));
                    int rr = Math.min(Math.max(((color >>> 24)       ) + adj, 0), 255);
                    int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + adj, 0), 255);
                    int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + adj, 0), 255);
                    int rgb555 = ((rr << 7) & 0x7C00) | ((gg << 2) & 0x3E0) | ((bb >>> 3));
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[rgb555] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    public Pixmap reduceWoven(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float w1 = (float) (20f * Math.sqrt(ditherStrength) * populationBias * populationBias * populationBias * populationBias), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 48f * ditherStrength / (populationBias * populationBias * populationBias * populationBias),
                limit = 5f + 110f / (float)Math.sqrt(colorCount+1.5f);

        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = Math.min(Math.max(((((px+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((((px+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((((px-4) * 0xC13FA9A902A6328FL + (y+2) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-23f - 0x1.4p-1f) * strength, -limit), limit) + (curErrorBlue[px]);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x5p-10f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x5p-10f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x5p-10f * ((color>>>8&255)- (used>>>8&255)) );

                    if(px < lineLen - 1)
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
                        if(px < lineLen - 1)
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
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Just as the wren flits restlessly from eave to branch to awning, so too does Wren dither dart to and fro between
     * dithering techniques. This is an error diffusion dither modeled closely after {@link #reduceWoven(Pixmap)}, which
     * means it uses three offset versions of the R2 sequence to introduce a structured artifact that breaks up
     * Floyd-Steinberg artifacts. It also incorporates per-channel blue-noise effects as {@link #reduceDodgy(Pixmap)}
     * uses them. The strengths of various components here have changed from the values used in Woven; the
     * error-diffusion strength is higher by default and the adjustment from blue noise and/or R2 values is
     * comparatively mild.
     * @param pixmap
     * @return
     */
    public Pixmap reduceWren(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        final float w1 = (float) (32.0 * ditherStrength * (populationBias * populationBias)), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = (0.2f * ditherStrength / (populationBias * populationBias * populationBias * populationBias)),
                limit = 5f + 125f / (float)Math.sqrt(colorCount+1.5),
                dmul = 0x1p-8f;

        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+1) * 0xC13FA9A902A6328FL + (y +1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-16f - 0x1p+6f)) * strength) + (curErrorRed[px]), -limit), limit);
                    eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+3) * 0xC13FA9A902A6328FL + (y -1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-16f - 0x1p+6f)) * strength) + (curErrorGreen[px]), -limit), limit);
                    eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f) + ((((px+2) * 0xC13FA9A902A6328FL + (y -4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1p-16f - 0x1p+6f)) * strength) + (curErrorBlue[px]), -limit), limit);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (dmul * ((color>>>24)-    (used>>>24))    );
                    gdiff = (dmul * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (dmul * ((color>>>8&255)- (used>>>8&255)) );

                    if(px < lineLen - 1)
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
                        if(px < lineLen - 1)
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
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    public Pixmap reduceBlubber(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float partialDitherStrength = (0.4f * ditherStrength * (populationBias * populationBias)),
                strength = (40f * ditherStrength / (populationBias * populationBias)),
                blueStrength = (0.15f * ditherStrength / (populationBias * populationBias)),
                limit = 5f + 125f / (float)Math.sqrt(colorCount+1.5f),
                r1, g1, b1, r2, g2, b2, r4, g4, b4;

        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int x = 0; x < lineLen; x++) {
                color = pixmap.getPixel(x, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(x, y, 0);
                else {
                    er = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE  [(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+1) * 0xC13FA9A902A6328FL + (y+1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorRed[x]);
                    eg = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_B[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+3) * 0xC13FA9A902A6328FL + (y-1) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorGreen[x]);
                    eb = Math.min(Math.max(( ( (PaletteReducer.TRI_BLUE_NOISE_C[(x & 63) | (y & 63) << 6] + 0.5f) * blueStrength + ((((x+2) * 0xC13FA9A902A6328FL + (y-4) * 0x91E10DA5C79E7B1DL) >>> 41) * 0x1.4p-24f - 0x1.4p-2f) * strength)), -limit), limit) + (curErrorBlue[x]);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(x, y, used);
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
                    if(x < lineLen - 1)
                    {
                        curErrorRed[x+1]   += r4;
                        curErrorGreen[x+1] += g4;
                        curErrorBlue[x+1]  += b4;
                        if(x < lineLen - 2)
                        {

                            curErrorRed[x+2]   += r2;
                            curErrorGreen[x+2] += g2;
                            curErrorBlue[x+2]  += b2;
                        }
                    }
                    if(ny < h)
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
                        if(x < lineLen - 1)
                        {
                            nextErrorRed[x+1]   += r2;
                            nextErrorGreen[x+1] += g2;
                            nextErrorBlue[x+1]  += b2;
                            if(x < lineLen - 2)
                            {

                                nextErrorRed[x+2]   += r1;
                                nextErrorGreen[x+2] += g1;
                                nextErrorBlue[x+2]  += b1;
                            }
                        }
                    }
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * A blue-noise-based dither; does not diffuse error, and uses a tiling blue noise pattern (which can be accessed
     * with {@link #TRI_BLUE_NOISE}, but shouldn't usually be modified) as well as a 8x8 threshold matrix (the kind
     * used by {@link #reduceKnoll(Pixmap)}, but larger). This has a tendency to look closer to a color
     * reduction with no dither (as with {@link #reduceSolid(Pixmap)} than to one with too much dither. Because it is an
     * ordered dither, it avoids "swimming" patterns in animations with large flat sections of one color; these swimming
     * effects can appear in all the error-diffusion dithers here. If you can tolerate "spongy" artifacts appearing
     * (which look worse on small palettes), you may get very good handling of lightness by raising dither strength.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceBlueNoise (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color;
        float adj, strength = 60f * ditherStrength / (populationBias * OtherMath.cbrtPositive(colorCount));
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
//                    float pos = (PaletteReducer.thresholdMatrix64[(px & 7) | (y & 7) << 3] - 31.5f) * 0.2f + 0.5f;
                    adj = ((PaletteReducer.TRI_BLUE_NOISE_B[(px & 63) | (y & 63) << 6] + 0.5f));
                    adj = adj * strength / (12f + Math.abs(adj)) + 0.5f;
                    int rr = Math.min(Math.max((int) (adj + ((color >>> 24)       )), 0), 255);
                    adj = ((PaletteReducer.TRI_BLUE_NOISE_C[(px & 63) | (y & 63) << 6] + 0.5f));
                    adj = adj * strength / (12f + Math.abs(adj)) + 0.5f;
                    int gg = Math.min(Math.max((int) (adj + ((color >>> 16) & 0xFF)), 0), 255);
                    adj = ((PaletteReducer.TRI_BLUE_NOISE  [(px & 63) | (y & 63) << 6] + 0.5f));
                    adj = adj * strength / (12f + Math.abs(adj)) + 0.5f;
                    int bb = Math.min(Math.max((int) (adj + ((color >>> 8)  & 0xFF)), 0), 255);

                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * A white-noise-based dither; uses the colors encountered so far during dithering as a sort of state for basic
     * pseudo-random number generation, while also using some blue noise from a tiling texture to offset clumping.
     * This tends to be very rough-looking, and generally only looks good with larger palettes or with animations. It
     * could be a good aesthetic choice if you want a scratchy, "distressed-looking" image.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceChaoticNoise (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        double adj, strength = ditherStrength * populationBias * 1.5;
        long s = 0xC13FA9A902A6328FL;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
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
                    pixmap.drawPixel(px, y, paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, using Floyd-Steinberg to dither
     * but modifying patterns slightly by introducing triangular-distributed blue noise. If you want to reduce the
     * colors in a Pixmap based on what it currently contains, call {@link #analyze(Pixmap)} with {@code pixmap} as its
     * argument, then call this method with the same Pixmap. You may instead want to use a known palette instead of one
     * computed from a Pixmap; {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceScatter (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }

        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float w1 = ditherStrength * 3.5f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;
        for (int y = 0; y < h; y++) {
            int ny = y + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    float tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                    er = curErrorRed[px] * tbn;
                    eg = curErrorGreen[px] * tbn;
                    eb = curErrorBlue[px] * tbn;
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (0x2.Ep-8f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x2.Ep-8f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x2.Ep-8f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                    if(px < lineLen - 1)
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
                        if(px < lineLen - 1)
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
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * An error-diffusion dither based on {@link #reduceFloydSteinberg(Pixmap)}, but adding in triangular-mapped blue
     * noise before diffusing, like {@link #reduceBlueNoise(Pixmap)}. This looks like {@link #reduceScatter(Pixmap)} in
     * many cases, but smooth gradients are much smoother with Neue than Scatter. Scatter multiplies error by a blue
     * noise value, where this adds blue noise regardless of error. This also preserves color better than TrueBlue,
     * while keeping similar gradient smoothness. The algorithm here uses a 2x2 rough checkerboard pattern to offset
     * some roughness that can appear in blue noise; the checkerboard can appear in some cases when a dithered image is
     * zoomed with certain image filters.
     * <br>
     * Neue is a German word for "new," and this is a new look at Scatter's technique.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceNeue(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float w1 = ditherStrength * 7f, w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                adj, strength = (32f * ditherStrength / (populationBias * populationBias * populationBias)),
                limit = (float) Math.pow(80, 1.635 - populationBias);

        for (int py = 0; py < h; py++) {
            int ny = py + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, py);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, py, 0);
                else {
                    adj = ((TRI_BLUE_NOISE[(px & 63) | (py & 63) << 6] + 0.5f) * 0.005f); // plus or minus 255/400
                    adj = Math.min(Math.max(adj * strength, -limit), limit);
                    er = adj + (curErrorRed[px]);
                    eg = adj + (curErrorGreen[px]);
                    eb = adj + (curErrorBlue[px]);

                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, py, used);
                    rdiff = (0x1.7p-10f * ((color>>>24)-    (used>>>24))    );
                    gdiff = (0x1.7p-10f * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (0x1.7p-10f * ((color>>>8&255)- (used>>>8&255)) );
                    rdiff *= 1.25f / (0.25f + Math.abs(rdiff));
                    gdiff *= 1.25f / (0.25f + Math.abs(gdiff));
                    bdiff *= 1.25f / (0.25f + Math.abs(bdiff));
                    if(px < lineLen - 1)
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
                        if(px < lineLen - 1)
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
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * An error-diffusion dither that adds in error based on blue noise, much like {@link #reduceNeue(Pixmap)}, but
     * unlike Neue it adds different blue noise values in for each RGB channel. This tends to improve color accuracy
     * quite a bit, but does add some random-seeming noise from how the different noise textures aren't connected. For
     * some palettes, this may very well be the best dither here. It has different color quality when compared to
     * {@link #reduceWoven(Pixmap)}, and is sometimes better, while this dither lacks the repetitive artifacts in Woven.
     * <br>
     * This dither uses blue noise, and it's baseball season in America; my local LA Dodgers have blue as their color.
     *
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceDodgy(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        float[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedFloats == null) {
            curErrorRed = (curErrorRedFloats = new FloatArray(lineLen)).items;
            nextErrorRed = (nextErrorRedFloats = new FloatArray(lineLen)).items;
            curErrorGreen = (curErrorGreenFloats = new FloatArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenFloats = new FloatArray(lineLen)).items;
            curErrorBlue = (curErrorBlueFloats = new FloatArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueFloats = new FloatArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedFloats.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedFloats.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenFloats.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenFloats.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueFloats.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueFloats.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float rdiff, gdiff, bdiff;
        float er, eg, eb;
        byte paletteIndex;
        float w1 = 25f * ditherStrength * populationBias * populationBias,
                w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f,
                strength = 0.25f * ditherStrength / (populationBias * populationBias),
                limit = 5f + 90f / (float)Math.sqrt(colorCount+1.5f),
                dmul = 0x1.8p-9f;

        for (int py = 0; py < h; py++) {
            int ny = py + 1;
            for (int i = 0; i < lineLen; i++) {
                curErrorRed[i] = nextErrorRed[i];
                curErrorGreen[i] = nextErrorGreen[i];
                curErrorBlue[i] = nextErrorBlue[i];
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, py);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, py, 0);
                else {
                    er = Math.min(Math.max(((TRI_BLUE_NOISE  [(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorRed[px]);
                    eg = Math.min(Math.max(((TRI_BLUE_NOISE_B[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorGreen[px]);
                    eb = Math.min(Math.max(((TRI_BLUE_NOISE_C[(px & 63) | (py & 63) << 6] + 0.5f) * strength), -limit), limit) + (curErrorBlue[px]);
                    int rr = Math.min(Math.max((int)(((color >>> 24)       ) + er + 0.5f), 0), 0xFF);
                    int gg = Math.min(Math.max((int)(((color >>> 16) & 0xFF) + eg + 0.5f), 0), 0xFF);
                    int bb = Math.min(Math.max((int)(((color >>> 8)  & 0xFF) + eb + 0.5f), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, py, used);

                    rdiff = (dmul * ((color>>>24)-    (used>>>24))    );
                    gdiff = (dmul * ((color>>>16&255)-(used>>>16&255)));
                    bdiff = (dmul * ((color>>>8&255)- (used>>>8&255)) );
//                    rdiff /= (0.2f + Math.abs(rdiff));
//                    gdiff /= (0.2f + Math.abs(gdiff));
//                    bdiff /= (0.2f + Math.abs(bdiff));

                    if(px < lineLen - 1)
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
                        if(px < lineLen - 1)
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
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Compares items in ints by their luma, looking up items by the indices a and b, and swaps the two given indices if
     * the item at a has higher luma than the item at b. This requires items to be present as two ints per item: the
     * earlier int, at an index less than 16, is what gets sorted, while the later int, at an index 16 higher than the
     * earlier one, is an RGB555 int.
     * <br>
     * This is protected rather than private because it's more likely
     * that this would be desirable to override than a method that uses it, like {@link #reduceKnoll(Pixmap)}. Uses
     * {@link #OKLAB} to look up accurate luma for the given RGB555 colors in the later half of {@code ints}.
     * @param ints an int array than must be able to take a, b, a+16, and b+16 as indices; may be modified in place
     * @param a an index into ints
     * @param b an index into ints
     */
    protected static void compareSwap(final int[] ints, final int a, final int b) {
        if(OKLAB[0][ints[a|16]] > OKLAB[0][ints[b|16]]) {
            final int t = ints[a], st = ints[a|16];
            ints[a] = ints[b];
            ints[a|16] = ints[b|16];
            ints[b] = t;
            ints[b|16] = st;
        }
    }
    
    /**
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 8.
     * @param i8 an 8-or-more-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    static void sort8(final int[] i8) {
        compareSwap(i8, 0, 1);
        compareSwap(i8, 2, 3);
        compareSwap(i8, 0, 2);
        compareSwap(i8, 1, 3);
        compareSwap(i8, 1, 2);
        compareSwap(i8, 4, 5);
        compareSwap(i8, 6, 7);
        compareSwap(i8, 4, 6);
        compareSwap(i8, 5, 7);
        compareSwap(i8, 5, 6);
        compareSwap(i8, 0, 4);
        compareSwap(i8, 1, 5);
        compareSwap(i8, 1, 4);
        compareSwap(i8, 2, 6);
        compareSwap(i8, 3, 7);
        compareSwap(i8, 3, 6);
        compareSwap(i8, 2, 4);
        compareSwap(i8, 3, 5);
        compareSwap(i8, 3, 4);
    }
    /**
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 16.
     * @param i16 a 16-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    static void sort16(final int[] i16)
    {
        compareSwap(i16, 0, 1);
        compareSwap(i16, 2, 3);
        compareSwap(i16, 4, 5);
        compareSwap(i16, 6, 7);
        compareSwap(i16, 8, 9);
        compareSwap(i16, 10, 11);
        compareSwap(i16, 12, 13);
        compareSwap(i16, 14, 15);
        compareSwap(i16, 0, 2);
        compareSwap(i16, 4, 6);
        compareSwap(i16, 8, 10);
        compareSwap(i16, 12, 14);
        compareSwap(i16, 1, 3);
        compareSwap(i16, 5, 7);
        compareSwap(i16, 9, 11);
        compareSwap(i16, 13, 15);
        compareSwap(i16, 0, 4);
        compareSwap(i16, 8, 12);
        compareSwap(i16, 1, 5);
        compareSwap(i16, 9, 13);
        compareSwap(i16, 2, 6);
        compareSwap(i16, 10, 14);
        compareSwap(i16, 3, 7);
        compareSwap(i16, 11, 15);
        compareSwap(i16, 0, 8);
        compareSwap(i16, 1, 9);
        compareSwap(i16, 2, 10);
        compareSwap(i16, 3, 11);
        compareSwap(i16, 4, 12);
        compareSwap(i16, 5, 13);
        compareSwap(i16, 6, 14);
        compareSwap(i16, 7, 15);
        compareSwap(i16, 5, 10);
        compareSwap(i16, 6, 9);
        compareSwap(i16, 3, 12);
        compareSwap(i16, 13, 14);
        compareSwap(i16, 7, 11);
        compareSwap(i16, 1, 2);
        compareSwap(i16, 4, 8);
        compareSwap(i16, 1, 4);
        compareSwap(i16, 7, 13);
        compareSwap(i16, 2, 8);
        compareSwap(i16, 11, 14);
        compareSwap(i16, 2, 4);
        compareSwap(i16, 5, 6);
        compareSwap(i16, 9, 10);
        compareSwap(i16, 11, 13);
        compareSwap(i16, 3, 8);
        compareSwap(i16, 7, 12);
        compareSwap(i16, 6, 8);
        compareSwap(i16, 10, 12);
        compareSwap(i16, 3, 5);
        compareSwap(i16, 7, 9);
        compareSwap(i16, 3, 4);
        compareSwap(i16, 5, 6);
        compareSwap(i16, 7, 8);
        compareSwap(i16, 9, 10);
        compareSwap(i16, 11, 12);
        compareSwap(i16, 6, 7);
        compareSwap(i16, 8, 9);
    }

    /**
     * Reduces a Pixmap to the palette this knows by using Thomas Knoll's pattern dither, which is out-of-patent since
     * late 2019. The output this produces is very dependent on the palette and this PaletteReducer's dither strength,
     * which can be set with {@link #setDitherStrength(float)}. At close-up zooms, a strong grid pattern will be visible
     * on most dithered output (like needlepoint). The algorithm was described in detail by Joel Yliluoma in
     * <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">this dithering article</a>. Yliluoma used an 8x8
     * threshold matrix because at the time 4x4 was still covered by the patent, but using 4x4 allows a much faster
     * sorting step (this uses a sorting network, which works well for small input sizes like 16 items). This is still
     * very significantly slower than the other dithers here (although {@link #reduceKnollRoberts(Pixmap)} isn't at all
     * fast, it still takes less than half the time this method does).
     * <br>
     * Using pattern dither tends to produce some of the best results for lightness-based gradients, but when viewed
     * close-up the "needlepoint" pattern can be jarring for images that should look natural.
     * @see #reduceKnollRoberts(Pixmap) An alternative that uses a similar pattern but skews it to obscure the grid
     * @param pixmap a Pixmap that will be modified
     * @return {@code pixmap}, after modifications
     */
    public Pixmap reduceKnoll (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, cr, cg, cb, usedIndex;
        final float errorMul = (ditherStrength * 0.5f / populationBias);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
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
                        candidates[i | 16] = shrink(candidates[i] = used = paletteArray[usedIndex]);
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    sort16(candidates);
                    pixmap.drawPixel(px, y, candidates[thresholdMatrix16[((px & 3) | (y & 3) << 2)]]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }

    /**
     * Reduces a Pixmap to the palette this knows by using a skewed version of Thomas Knoll's pattern dither, which is
     * out-of-patent since late 2019, using the harmonious numbers rediscovered by Martin Roberts to handle the skew.
     * The output this produces is very dependent on the palette and this PaletteReducer's dither strength, which can be
     * set with {@link #setDitherStrength(float)}. A hexagonal pattern can be visible on many outputs this produces;
     * this artifact can be mitigated by lowering dither strength. The algorithm was described in detail by Joel
     * Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">this dithering article</a>. Yliluoma used an
     * 8x8 threshold matrix because at the time 4x4 was still covered by the patent, but using 4x4 allows a much faster
     * sorting step (this uses a sorting network, which works well for small input sizes like 16 items). This is stil
     * very significantly slower than the other dithers here (except for {@link #reduceKnoll(Pixmap)}.
     * <br>
     * While the original Knoll pattern dither has square-shaped "needlepoint" artifacts, this has a varying-size
     * hexagonal or triangular pattern of dots that it uses to dither. Much like how Simplex noise uses a triangular
     * lattice to improve the natural feeling of noise relative to earlier Perlin noise and its square lattice, the
     * skew here makes the artifacts usually less-noticeable.
     * @see #reduceKnoll(Pixmap) An alternative that uses a similar pattern but has a more obvious grid
     * @param pixmap a Pixmap that will be modified
     * @return {@code pixmap}, after modifications
     */
    public Pixmap reduceKnollRoberts (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, cr, cg, cb, usedIndex;
        final float errorMul = ditherStrength * populationBias * 1.25f;
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y);
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    int er = 0, eg = 0, eb = 0;
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
                        candidates[c | 16] = shrink(candidates[c] = used = paletteArray[usedIndex]);
                        er += cr - (used >>> 24);
                        eg += cg - (used >>> 16 & 0xFF);
                        eb += cb - (used >>> 8 & 0xFF);
                    }
                    sort8(candidates);
                    pixmap.drawPixel(px, y, candidates[thresholdMatrix8[
                            ((int) (px * 0x1.C13FA9A902A6328Fp3 + y * 0x1.9E3779B97F4A7C15p-2) & 3) ^
                                    ((px & 3) | (y & 1) << 2)
                            ]]);
                }
            }
        }
        pixmap.setBlending(blending);
        return pixmap;
    }


    /**
     * Retrieves a random non-0 color index for the palette this would reduce to, with a higher likelihood for colors
     * that are used more often in reductions (those with few similar colors). The index is returned as a byte that,
     * when masked with 255 as with {@code (palette.randomColorIndex(random) & 255)}, can be used as an index into a
     * palette array with 256 or fewer elements that should have been used with {@link #exact(int[])} before to set the
     * palette this uses.
     * @param random a Random instance, which may be seeded
     * @return a randomly selected color index from this palette with a non-uniform distribution, can be any byte but 0
     */
    public byte randomColorIndex(Random random)
    {
        return paletteMapping[random.nextInt() >>> 17];
    }

    /**
     * Retrieves a random non-transparent color from the palette this would reduce to, with a higher likelihood for
     * colors that are used more often in reductions (those with few similar colors). The color is returned as an
     * RGBA8888 int; you can assign one of these into a Color with {@link Color#rgba8888ToColor(Color, int)} or
     * {@link Color#set(int)}.
     * @param random a Random instance, which may be seeded
     * @return a randomly selected RGBA8888 color from this palette with a non-uniform distribution
     */
    public int randomColor(Random random)
    {
        return paletteArray[paletteMapping[random.nextInt() >>> 17] & 255];
    }

    /**
     * Looks up {@code color} as if it was part of an image being color-reduced and finds the closest color to it in the
     * palette this holds. Both the parameter and the returned color are RGBA8888 ints.
     * @param color an RGBA8888 int that represents a color this should try to find a similar color for in its palette
     * @return an RGBA8888 int representing a color from this palette, or 0 if color is mostly transparent
     * (0 is often but not always in the palette)
     */
    public int reduceSingle(int color)
    {
        if((color & 0x80) == 0) // less visible than half-transparent
            return 0; // transparent
        return paletteArray[paletteMapping[
                (color >>> 17 & 0x7C00)
                        | (color >>> 14 & 0x3E0)
                        | (color >>> 11 & 0x1F)] & 0xFF];
    }

    /**
     * Looks up {@code color} as if it was part of an image being color-reduced and finds the closest color to it in the
     * palette this holds. The parameter is a RGBA8888 int, the returned color is a byte index into the
     * {@link #paletteArray} (mask it like: {@code paletteArray[reduceIndex(color) & 0xFF]}).
     * @param color an RGBA8888 int that represents a color this should try to find a similar color for in its palette
     * @return a byte index that can be used to look up a color from the {@link #paletteArray}
     */
    public byte reduceIndex(int color)
    {
        if((color & 0x80) == 0) // less visible than half-transparent
            return 0; // transparent
        return paletteMapping[
                (color >>> 17 & 0x7C00)
                        | (color >>> 14 & 0x3E0)
                        | (color >>> 11 & 0x1F)];
    }

    /**
     * Looks up {@code color} as if it was part of an image being color-reduced and finds the closest color to it in the
     * palette this holds. Both the parameter and the returned color are packed float colors, as produced by
     * {@link Color#toFloatBits()} or many methods in SColor.
     * @param packedColor a packed float color this should try to find a similar color for in its palette
     * @return a packed float color from this palette, or 0f if color is mostly transparent
     * (0f is often but not always in the palette)
     */
    public float reduceFloat(float packedColor)
    {
        final int color = NumberUtils.floatToIntBits(packedColor);
        if(color >= 0) // if color is non-negative, then alpha is less than half of opaque
            return 0f;
        return NumberUtils.intBitsToFloat(Integer.reverseBytes(paletteArray[paletteMapping[
                (color << 7 & 0x7C00)
                        | (color >>> 6 & 0x3E0)
                        | (color >>> 19)] & 0xFF] & 0xFFFFFFFE));

    }

    /**
     * Modifies {@code color} so its RGB values will match the closest color in this PaletteReducer's palette. If color
     * has {@link Color#a} less than 0.5f, this will simply set color to be fully transparent, with rgba all 0.
     * @param color a libGDX Color that will be modified in-place; do not use a Color constant, use {@link Color#cpy()}
     *              or a temporary Color
     * @return color, after modifications.
     */
    public Color reduceInPlace(Color color)
    {
        if(color.a < 0.5f)
            return color.set(0);
        return color.set(paletteArray[paletteMapping[
                ((int) (color.r * 0x1f.8p+10) & 0x7C00)
                        | ((int) (color.g * 0x1f.8p+5) & 0x3E0)
                        | ((int) (color.r * 0x1f.8p+0))] & 0xFF]);
    }

    /**
     * Edits this PaletteReducer by changing each used color in the Oklab color space with an {@link Interpolation}.
     * This allows adjusting lightness, such as for gamma correction. You could use {@link Interpolation#pow2InInverse}
     * to use the square root of a color's lightness instead of its actual lightness, or {@link Interpolation#pow2In} to
     * square the lightness instead.
     * @param lightness an Interpolation that will affect the lightness of each color
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer alterColorsLightness(Interpolation lightness) {
        int[] palette = paletteArray;
        for (int idx = 0; idx < colorCount; idx++) {
            int s = shrink(palette[idx]);
            palette[idx] = oklabToRGB(lightness.apply(OKLAB[0][s]), OKLAB[1][s], OKLAB[2][s],
                    (palette[idx] & 0xFE) / 254f);
        }
        return this;
    }

    /**
     * Edits this PaletteReducer by changing each used color in the Oklab color space with an {@link Interpolation}.
     * This allows adjusting lightness, such as for gamma correction, but also individually emphasizing or
     * de-emphasizing different aspects of the chroma. You could use {@link Interpolation#pow2InInverse} to use the
     * square root of a color's lightness instead of its actual lightness (which, because lightness is in the 0 to 1
     * range, always results in a color with the same lightness or a higher lightness), or {@link Interpolation#pow2In}
     * to square the lightness instead (this always results in a color with the same or lower lightness). You could make
     * colors more saturated by passing {@link Interpolation#circle} to greenToRed and blueToYellow, or get a
     * less-extreme version by using {@link Interpolation#smooth}. To desaturate colors is a different task; you can
     * create a {@link OtherMath.BiasGain} Interpolation with 0.5 turning and maybe 0.25 to 0.75 shape to produce
     * different strengths of desaturation. Using a shape of 1.5 to 4 with BiasGain is another way to saturate the
     * colors.
     * @param lightness an Interpolation that will affect the lightness of each color
     * @param greenToRed an Interpolation that will make colors more green if it evaluates below 0.5 or more red otherwise
     * @param blueToYellow an Interpolation that will make colors more blue if it evaluates below 0.5 or more yellow otherwise
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer alterColorsOklab(Interpolation lightness, Interpolation greenToRed, Interpolation blueToYellow) {
        int[] palette = paletteArray;
        for (int idx = 0; idx < colorCount; idx++) {
            int s = shrink(palette[idx]);
            float L = lightness.apply(OKLAB[0][s]);
            float A = greenToRed.apply(-1, 1, OKLAB[1][s] * 0.5f + 0.5f);
            float B = blueToYellow.apply(-1, 1, OKLAB[2][s] * 0.5f + 0.5f);
            palette[idx] = oklabToRGB(L, A, B, (palette[idx] & 0xFE) / 254f);
        }
        return this;
    }

    /**
     * Edits this PaletteReducer by changing each used color so lighter colors lean towards warmer hues, while darker
     * colors lean toward cooler or more purple-ish hues.
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer hueShift() {
        int[] palette = paletteArray;
        for (int idx = 0; idx < colorCount; idx++) {
            int s = shrink(palette[idx]);
            float L = OKLAB[0][s];
            float A = OKLAB[1][s] + (L - 0.5f) * 0.04f;
            float B = OKLAB[2][s] + (L - 0.5f) * 0.08f;
            palette[idx] = oklabToRGB(L, A, B, (palette[idx] & 0xFE) / 254f);
        }
        return this;
    }


}

package com.github.tommyettinger.anim8;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.*;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

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
 *     that Floyd-Steinberg can produce perturbed by blue noise; it's the default here because it works well in still
 *     images and animations. Using blue noise to edit error somewhat-randomly would seem like it would introduce
 *     artifacts of its own, but blue noise patterns are very hard to recognize as artificial, since they show up mostly
 *     in organic forms.)</li>
 *     <li>{@link #reduceJimenez(Pixmap)} (This is a modified version of Gradient Interleaved Noise by Jorge Jimenez;
 *     it's a kind of ordered dither that introduces a subtle wave pattern to break up solid blocks. It does quite well
 *     on some animations and on smooth or rounded shapes.)</li>
 *     <li>{@link #reduceKnollRoberts(Pixmap)} (This is a modified version of Thomas Knoll's Pattern Dithering; it skews
 *     a grid-based ordered dither and also does a small amount of gamma correction, so lightness may change. It
 *     preserves shape extremely well, but is almost never 100% faithful to the original colors. It has gotten much
 *     better since version 0.2.4, however. This algorithm is rather slow; most of the other algorithms take comparable
 *     amounts of time to each other, but KnollRoberts and especially Knoll are sluggish.)</li>
 *     <li>{@link #reduceChaoticNoise(Pixmap)} (Uses blue noise and pseudo-random white noise, with a carefully chosen
 *     distribution, to disturb what would otherwise be flat bands. This does introduce chaotic or static-looking
 *     pixels, but with larger palettes they won't be far from the original. This works fine as a last resort when you
 *     can tolerate chaotic/fuzzy patches of poorly-defined shapes, but other dithers aren't doing well.)</li>
 *     </ul>
 *     </li>
 *     <li>OTHER TIER
 *     <ul>
 *     <li>{@link #reduceBlueNoise(Pixmap)} (Uses a blue noise texture, which has almost no apparent patterns, to adjust
 *     the amount of color correction applied to each mismatched pixel; also uses a quasi-random pattern. This may not
 *     add enough disruption to some images, which leads to a flat-looking result.)</li>
 *     <li>{@link #reduceSierraLite(Pixmap)} (Like Floyd-Steinberg, Sierra Lite is an error-diffusion dither, and it
 *     sometimes looks better than Floyd-Steinberg, but usually is similar or worse unless the palette is small. If
 *     Floyd-Steinberg has unexpected artifacts, you can try Sierra Lite, and it may avoid those issues. Using
 *     Scatter should be tried first, though.)</li>
 *     <li>{@link #reduceKnoll(Pixmap)} (Thomas Knoll's Pattern Dithering, used more or less verbatim except for the
 *     inclusion of some gamma correction; this version has a heavy grid pattern that looks like an artifact. The skew
 *     applied to Knoll-Roberts gives it a more subtle triangular grid, while the square grid here is a bit bad. This
 *     reduction is the slowest here, currently, and may noticeably delay processing on large images.)</li>
 *     <li>{@link #reduceSolid(Pixmap)} (No dither! Solid colors! Mostly useful when you want to preserve blocky parts
 *     of a source image, or for some kinds of pixel/low-color art.)</li>
 *     </ul>
 *     </li>
 * </ul>
 * <p>
 * Created by Tommy Ettinger on 6/23/2018.
 */
public class PaletteReducer {
    /**
     * This 255-color (plus transparent) palette uses the (3,5,7) Halton sequence to get 3D points, treats those as IPT
     * channel values, and rejects out-of-gamut colors. This also rejects any color that is too similar to an existing
     * color, which in this case made this try 130958 colors before finally getting 256 that work. Using the Halton
     * sequence provides one of the stronger guarantees that removing any sequential items (after the first 9, which are
     * preset grayscale colors) will produce a similarly-distributed palette. Typically, 64 items from this are enough
     * to make pixel art look good enough with dithering, and it continues to improve with more colors. It has exactly 8
     * colors that are purely grayscale, all right at the start after transparent.
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
     * Converts an RGBA8888 int color to the RGB555 format used by {@link #IPT} to look up colors.
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
     * Stores IPT components corresponding to RGB555 indices.
     * IPT[0] stores intensity from 0.0 to 1.0 .
     * IPT[1] stores protan, which is something like a green-red axis, from -1 (green) to 1 (red).
     * IPT[2] stores tritan, which is something like a blue-yellow axis, from -1 (blue) to 1 (yellow).
     * <br>
     * The indices into each of these double[] values store red in bits 10-14, green in bits 5-9, and blue in bits 0-4.
     * It's ideal to work with these indices with bitwise operations, as with {@code (r << 10 | g << 5 | b)}, where r,
     * g, and b are all in the 0-31 range inclusive. It's usually easiest to convert an RGBA8888 int color to an RGB555
     * color with {@link #shrink(int)}.
     */
    public static final double[][] IPT = new double[3][0x8000];
//    /**
//     * Stores CIE LAB components corresponding to RGB555 indices.
//     * LAB[0] stores lightness from 0.0 to 100.0 .
//     * LAB[1] stores CIE A, which is something like a green-red axis, from roughly -128.0 (green) to 128.0 (red).
//     * LAB[2] stores CIE B, which is something like a blue-yellow axis, from roughly -128.0 (blue) to 128.0 (yellow).
//     * <br>
//     * The indices into each of these double[] values store red in bits 10-14, green in bits 5-9, and blue in bits 0-4.
//     * It's ideal to work with these indices with bitwise operations, as with {@code (r << 10 | g << 5 | b)}, where r,
//     * g, and b are all in the 0-31 range inclusive. It's usually easiest to convert an RGBA8888 int color to an RGB555
//     * color with {@link #shrink(int)}.
//     */

    /**
     * This should always be a 4096-element byte array filled with 64 sections of 64 bytes each. When arranged into a
     * grid, the bytes will follow a blue noise frequency (and in this case, will have a uniform value distribution).
     * <br>
     * This is public and non-final (blame Android), so you could change the contents to some other distribution of
     * bytes or even assign a different array here, but you should never let the length be less than 4096, or let this
     * become null.
     */
    public static byte[] RAW_BLUE_NOISE;

    /**
     * Very similar to {@link #RAW_BLUE_NOISE}, a 4096-element byte array as a 64x64 grid of bytes. Unlike RAW, this
     * uses a triangular distribution for its bytes, so values near 0 are much more common. This is only used inside
     * this library to create {@link #TRI_BLUE_NOISE_MULTIPLIERS}, which is used in {@link #reduceScatter(Pixmap)}.
     * <br>
     * This is public and non-final (blame Android), so you could change the contents to some other distribution of
     * bytes or even assign a different array here, but you should never let the length be less than 4096, or let this
     * become null.
     */
    public static byte[] TRI_BLUE_NOISE;

    /**
     * A 64x64 grid of doubles, with a median value of about 1.0, generated using the triangular-distributed blue noise
     * from {@link #TRI_BLUE_NOISE}. If you randomly selected two doubles from this and multiplied them, the average
     * result should be 1.0; half should be between 1 and {@link Math#E}, and the other half should the inverses of the
     * first half (between {@code 1.0/Math.E} and 1).
     * <br>
     * This is public and non-final (blame Android), so you could change the contents to some other distribution of
     * bytes or even assign a different array here, but you should never let the length be less than 4096, or let this
     * become null.
     */
    public static double[] TRI_BLUE_NOISE_MULTIPLIERS;

    /**
     * This stores a preload code for a PaletteReducer using {@link #HALTONIC} with a simple metric. Using
     * a preload code in the constructor {@link #PaletteReducer(int[], byte[])} eliminates the time needed to fill 32 KB
     * of palette mapping in a somewhat-intricate way that only gets more intricate with better metrics, and replaces it
     * with a straightforward load from a String into a 32KB byte array.
     */
    private static byte[] ENCODED_HALTONIC;

//    private static final double[] RGB_POWERS = new double[3 << 8];

    static {
//        for (int i = 1; i < 256; i++) {
//            RGB_POWERS[i]     = Math.pow(i, 3.7);
//            RGB_POWERS[i+256] = Math.pow(i, 4.0);
//            RGB_POWERS[i+512] = Math.pow(i, 3.3);
//        }

        try {
            RAW_BLUE_NOISE = "ÁwK1¶\025à\007ú¾íNY\030çzÎúdÓi ­rì¨ýÝI£g;~O\023×\006vE1`»Ü\004)±7\fº%LÓD\0377ÜE*\fÿí\177£RÏA2\r(Å\0026\023¯?*Â;ÌE!Â\022,è\006ºá6h\"ó¢Én\"<sZÅAt×\022\002x,aèkZõl±×\033dÅ&k°Ö÷nCÚ]%é\177ø\022S\001Øl´uÉ\036þ«À>Zß\000O®ñ\021Õæe÷¨ê^Â±\030þ®\021¹?èUªE6è\023_|¼¢!­t½P\005ÙG¥¸u.\030ò>Tÿ3nXCvíp*³\033ìÑyC¼/\031P1;òSÝÈ2KÒ\"È3r Óø·V\000\034ä4\bVê\020õgÇ\0331êÞ`¯ÅeãÓ­ò×\rÈ\034KÏ\013h5\tÃ\037T\002~Í´ kÐq@~ïc\003x\023ó»\005OxÛÃJÎeIÒ7´p]\013#J\006 $`F¿¡*³`åôS½F¤bùÝl¦Há\rû¡æ\013%º\005\035à©G[âc\020§=,mñµ=þÃ-\034å\ròM¿?Ïöq9¹\017xæ\032eù2¦\026:~Ùå-:¶ð'Ww¿KcªÕ\\¢OÀ-Ð³:¥+Éî!\\Ñ\f$qß}¦WB*«Õýz¨\025ìPÌ\0027|ÞRq\001Ä¬%ÿr¯\030Ò\016_Ç3Ö=\0260úè8\roøa\007Ù}ýAs¼áû¬Tè\024²_\007øÊxe\036µ1VØ(ª@ÚUÊ\007»Óaî\021WÆM{B\033s\005®óÉyiÍ¯\032ê%M\030±Nh\0267{Â¢K9Ö¹\026à:\tjæ¿~]÷h.µ\024J\"óC-\032KkÏ=ò\003é«Ûö»b\"ßU\b·B#ÞTpÀhèÔ2\tÊFÙ\003+Íñ lGa\000ÁQìË¢\033D\004\035Ãð¤pé®\\ Ýµ2º¡b)¿6kNëFl§\035Mÿ|1È?úª\017GZ÷£ì¶\037p[\017ä1¤&s-`û7±Òt\rYÑ9z\016Éêvü\tã\034pÖJ\007£*\017Å6×íÂ\023óµ\026.]Ì$q¹\034x-bVãø¼«wÃî³\020ÙH¸vÞP\022é3MÞ>\000Á*úeA\"ZD®û\037ÉYÔ\177µ\002t.f«\\JÖuÝ\003¡òß>Ô\f¨\0223B\002RÐ?[÷©\013#Æo[ü¹\"¬d\030á¸Q\0344ÂªÕ}Ç\017ç`xñ2¬ü`è\026XÑ9å\tïR´e4U­\003l¿<NÑhÝ#ù}\030Æm;ÐWô«)¢Í}ñoG¦Ó\003hð'V<µà\024D!Ë=÷º\037jÃ9\036AÁw\020ÈLúé\177\036´_\r¨ÜO,æ|\016?ÛjE\0076×S\nôxâT\022I6»\003Ò\031Oq¿Ûn Mà\020zFþ)§~â\013ùÖ*ë Ü7(Æ¡õ.ê¾i7·\004ë\036fF»\000Àï\027^µ&Ê1?¾,´ùÜn¦v+Å¢\0008õ±(Ã^¢Ø³VÎ¹Ni¨]z¶d\030F\005WuËL)åt¬ÂüØ4b\035T/¯æÄÿvê®b\036\brÍ\033éFöa\016ée\031W\nv\0020eô\024\001-Ë\031G»õ¢\nÐ«r×:\025\000Ñ\\B\fU\024±Ñ ygM\033\023Øí[©:dP\n±>Õ'¸Ðå;ªïÊ\034çpCÚaït2ÿn>RäZð%¸â£°y\034ò¢1Îz&îqãGû\017Ø,§BYý\177LBà\000½$Íjá»TªsI/f\026R@½4Å£\020²ãØÆ\027-ýÁ5\fHh÷V?ÄláH§[8\n·È;óÏlãÂ5Ï¹&\024{ò0\025}\005ÇòþÀØw\016\\­Py\036=X\t$¯Ak^Æ#\016¼Û(\022ù¹\005Á÷f!Uqº\t1³ ¡\006pöÇXÚ=] ù\"8Þb\035|L&µâúÒ$\006öÒ¿J}9dívÛ\022°ç\000ÔrìcI­X;n\032ÚO.¬ß¤\031_÷R^Ü/²E\013s­\003ÆêL\020C¯ê\tY£-gßl/`ýç§ÌW\004¹IÍ\037}Q/<¥\004~Õë$wÌ\023ë|\004JíyØA\025ë¨gSé\032&m³Òv½Ö3Ípð9ÄA·ì«'\024ö\032(¦ù2¾\032ß_¶Ì\0310Æ^³\000>ZÑ)Á8Ë&­iÇ:\036Ìü5¾ÔAW/¤[\002m\025¨@\003y\031M\017UÉFsÕf3ÁàQp[ïC«\007õPåüi\rIåg¼õC°ýNå\013ÿu¶\râw£cPàö\t\031äò%O_¼!Ú¯èÏ\177\034\007¹L°x;\bÅÚ\017iÏx$s8»B¢ò,\027¨4\034k`\022t£\\¾.ÖóN?)´\016{Ài>±Åúæ\016Hkaþ4Ýøá;ï\t\"Îèb®%7KÂ\021ÓY¬\037Ý|ÁPÕs\fãÙ¹ ñGV#Â]\001ð 4¬FÌ\177Ü/uÓô(<½¤%²_-Ziý\027G{¹æý+°î\006nÎ\004büÈM+ó?Ð4Ý{\027¥l®Þ\024ÏmIÇÚ]þ\035\bg;¥R¶ÇX\023gÅApÌ\023¼ÚA¥·1ò\003V\035`ÜoDeâN÷1W±à&E\177¬\007oX\003²çÍû2~;§çr\023é+Qºï\"ü\026|\006ãNð\r¡Oÿ|)ÈTuÜÒÇ§\0265'Å\017\033<£\022·í\\Á\030§Æi=c\bDí!W»*\004=¶¡Òc¨\021ÊCÝ2ªÓvýÙë\035­äù\036Kk\021<tPôÎ½\001®y@¹évôk2Ò\0369âJú-\021½&M·Õù\fgRöyHâvW²j\\ëG\036¸/©Tk1Hc9ê\006Á¬'ì¶/\f\177\\Úñ¤gÓÆHþfÔzëUóÚ\034È_r Ë­×\0360Ä\005>(ô\004¡Àqb<\024½Ò\boµ\025Ò\\ãbþAä L9\024Qÿ,[\bÝr\017´V$¯E Îw©k\020æ-M7\030ãnXì\027Ö½5\032Î+\017ôßË']@óÄ%¨}0DÌ\032m×¬f\bÌo$ß¯\035½«N5Çö\fÞ`3\0048Hþ°ÀyðCÿ¸jª[oOzÛ=S\006}çù°x6ßY\001@ºõ\nJ¼)XÄí´úÀCc7æzñ'çt@Àm\026¶\"áÃ¢#Ú\006(Å\0166IÎü\"\016è§ûi±Â \020Ú ¤MíhÜ\037uïÞ\0027\031w0^\rÊ\005T\023Ñ^¦.üïQù]}îVeµ\\¥~Ý+ä²Ç@_¸Jí\"6FnQcÀ\fs\031Ê,¡T±3[¦z\020MÔS¬ê~öiÖ?ÃlE\005\034ÖYÊuB¬Ô+\017<Í\026õKÑ\037øTwh\006(\024ÊuÓµ*þÔ²Iq\004ÕÅ\025?Íõ£#à\0265¸'¨\033ú°ßº~K®9'£\t\032¸kã¨v2æ=d¼\007:\032ñ1Ód©Ý\032\0024ÉâDïf8û¾\022ê<gû%¶ç_¿r\001FÏK\n]5$òä\020j½êÛb5È\003R¼\np¯ë\024Ë¦ÙQ¾\177ãøVNò¥_\023~«&á^O©áSl,\f=´f YÜîoÉçwV\reÈ\002OzðF\"ùBÛ\034ÄWHmaÿ®F :\b-¿x >º\007Y\027¡xÏ.\035~ÁD\002ª×íÅûv¼,E\003À£<*X\033qÏ,²\026^rÐ+b¢\001$Ø/ûå(\016!oÆ²jèÜøÏ.GÙÆö?\b·ó\rÑu\0338øR|1\017®9\025¦Ù\037MÒý¬Ùõ¶A\022üT×ç¬\r³|ð;xªÃCsÐì\027×D\f®Shêu±hÞI^5ëYÈi\025§ÕåcÑSeµ3ðmEz3â§m¿(9ÄQäHÎ^·\013\033Yº0MZþ%aÄs\033\000¥Q7\032T)Én­ú#·¢á»%@L\006$êù\017t\031â\013Â `LÉ;\nyîd\002\030.¿\020÷Lï7Üùfâ|ï6\021ã´È(\013¼å\000î\025ÙfA\021/K\001Zôm¶Ä\177>Ê¨Y¸d#ñ\005\037ë[øI\034¤×g«s ÙjÊ{\025©\b@Î¸¦L+B_ñcÐ©{:»,RÄ\004wÐîo¯Ë\035Û¤G0^Þ)\0018Îp·~.Û²Ì2ºtú@æ£=+\004³I'Às4\035\002Òõ|Ø2r!H\\\007rçª\\7\ny.ÿ\025ä¯\t¾PéI­0TÕ¤\024f\004àY;ÇS\007(ZýÀRâaÔé¡÷oXh\006»¢\020NßøÃ\021Óã¢\034Jõ'¾\033ÝÀëU:qÑg ÷{Õi\021üà\030CôÂO!n\016$®îÎ´z\022§p ôRe\fDÞÂ«8\"WÍ²> j4°ý=Í\177\rØlRúG\026a¸NïD\030¥Ä\\s\n:©ëEØ\177\0274fÞEìÌ9º\021,È³%ë\023Häuÿ\032í&Yh·5>³gªÓø\016Æ)¦6Í¶1ò@y»7ËaäþÓx·öa¾Jö\0350]\013C}«Û8wOýÇmó.¼]}ØGÂð-\000`¤îÎ\0060æ>#|ß[ÿu\013WmÛ&\004ì\035²X)\027É0O\001åp\tÄÔ°lÞûX\004\031òÑ`Ü\027­aB\nË§\001\035\020«ßÈo\022%{¾m J°\031¾ã!\000°ÐU¤h0H¿d\t¤Ñ)­9wS\001KÄ&èg¾¥3\177¸Q(ÖåS6¶kæuVC\036JàU\032Ê\005ì<×cFî Kd\024>Üó}\021áAí8v³Z@\024dè$½\027/u²Ï=o\013!@\002¡Ãyú\024NÏ )ø¿[­þBñÚ^2Ãj-\025³~ÇætÁ\013Ð¥\003¯nÝ\037ðÊûÙ¡óc¥î\017P\036ù×Vèdð0j\016\"FfÛ,õ<\005²×y\013.Ôq´*¦v\017õN©ûÔ'\n6øª*E\034lQ#ÆU\022¼fR¸3NË?Ø6`¹G­É\025Òá±ïº©wÅ\\\027ëH£ê5b\023Oü¶!Ìm=^ºPÖ`þ´5õÕ,üI\b}'j\003\023z¯\bl\000Þ{+\005K·Z<qË4]\036\fáo½4dËj\033»\006Èå:ÕUáz\b¾àñq\030yÞÃèbCz ç«;ÁäªÕ äEÉ(§ñÂ8k$ôþ\027QÔûB#ý©:öP}#nÁfB3ìW\037J¥/\005É9ïY'\n¸\031_Î!\026G÷8^¾T÷t\021Zä£\n|Å+¨ç\007|¡V°ðLÝ\021'á°AÜô®\f\033¥\001±\022Êÿ´ÚgS!¬\023Ih©Û\0013MtôÙ[rÇén¤0\031µLÍ?\035rÖW@ÚdJ*Æ6kÐ~UÁs]\rÍ+ZEé+bÙq¡e4xé¼Ô\002 Ì:nîÄ²\013.\007µ)\006Øeé\003³ü½3ì°\020tÀó³\021å\001\032¹/Ô\000\037í¹tÿÐ{KÆö%?æÃ&D\016r>{ø\036X&âj½<¦Q\027Dñ\177Ã:Û-cHj\037Sú#ÖD[a<\bóD¤m8\030¨5»\026·Ð\tQ\031]ü¦ñaä/¿PÓ\177B\030LûÑ{ßÌ¯Z\020#pS©î\027Ï§âË_;oª!wÊß©i´*L¿àU\007ðPà\n_2X{ô±ÖË*µU\025®ç\016÷¥Écì\037Xýi5ã¦öº\tÇ{\005".getBytes("ISO-8859-1");
            TRI_BLUE_NOISE = "\021ñDø\030¶àö%3\nò\020Õ°å2Ù)P\024þÄõ9\nXåÁ4Ì$ÿ\021±ÚZÊò»\034YÅé<Ôþä³\002\025òüDÈ&\bù½\000àõÒ:Ù_¬ä5\bG§\000Y\030À9K\007ð¼\016Êøâ\\,ºßEÔ\001\036\016êP\006!åý\024MØù!\r'\t.ËR4Á\f¬\024î\032ÏZ\025¡'\032Â\001\013%ÌÿÖ\037\rÑä±ê#Ëú\025c\002D\035Ò\004\035\017É!ó¯ùÜCºÈô\030F\005$á/¤ÐKûÛB\035÷Úç%d×öN³Ü+7ï´Rù4ì\024Wéò;Æ*ùA\0020Û%§è5\021ñ@èqü.\025;[-Õüp+ÀÒë´\0079ï±ã\025Áa¨\021ÿ\007á.\001;ç\013Ç\005B\016ã¹ÔKô)¾P\023\006k×\034âWìÃöÖ\b·$ù­Ø\007ì¾á\004ï\032\0138ìÝ\r>õ]\020\000\031n\0044òê\006ÑKî?\035Ã\016ô!üÚêÌ#\006<\034Ý\017\0040üÜï¢\rô´\021\b;\033ÿiÞJÌ\0307 RÒ\f)Ëÿä \024²ú2\036ÈÛè*Ê\017µ\";,\032»ùÎë$aÔI­o.¥\027ïÄý¸uÊä\035·$6ÍO-ýÐI³+Çí0\rã\001&öD ªb?Â'O\002Ë\030ãLü½?÷×SàüÄÜ\r2U¦Þþ\024¿\034ò\021ÿXÚ-Fèø6\nÓEé\026\004Áæ ßð\013\027û¾að¶\022ÄèûÚ\021÷\034Ôðc\tÕ,ò\024\"\bG\034\001¢\024ôv®\001\026\tD+î\005>ßÐ9÷\t\023Ñ\032 ò]\000­øÝ>òf\026ù6Yå\004 CÑ\0332Þ\000\0304ð·Iè\007:£#êµ\004eÑî°æ1ÍD\005é'âòÓø¸Éå\f\037¾æf%­\003A'¿\017-ÈK(\016¹Ø\005%Ìª×OóÜ\007ëYÕM\t\"Î«,àÄþE\016¦1áþ)\f_ï%ÕMÇ5¼\0328Z#ùR\003*\016úñàUìÙûà\031ð\b¤\033ÿ8Ãè\020\000\032)°\021-?ù\016\005¼+ßi\001ù\017Tô\0256ÛùÇI\021ÀÛù¸\t\033<\020ý eç\017Ùª3ÔëAÉ×8»\026È\007;PÖoåÏíS/Eî¿9ËéÿÄ«'Ë=õ\023ì7\032Ú¹ çÎX\032ñ Q\031È8ã½÷íØ\n\002ïü\027Ãó\023°\033N \000ö1ª\036ê\0023\"ûD)\024ö\tÜ_û\013õT\026ãtñ\036æýÓÃDË3\n\005½ä\t8Õ\002ô,\022ÿ[Ì-²Cà0ÌE\007'cþá\006éÐ]&\rÐ·÷\020Ã°\fÚÉã\036¸Ñ\"àBÔ\"\tÛJ¡\031V)\006åòÿs'ú@+ªûé?ßmë \004N\024ò$ÀU£ÞèºI /Â\n\022ãÚòD`\027Þ>ó\004§eþ=\003¹\0242\001\034´ú/º\020\0028±\fø\\\027¬ëÒ\022ïØe\025Ê$¬\006ÑDÚ¦äûÑ\006\030÷\020<\036Ï\f\030ô})úIÁ\004æþÊ,ëY\0354\027¾ñN&åjÂî\0057Îì÷ÆØïá#»/Ý:ÄL\"·\0032¾\016ï\035ú\f(\027Á:q«é*Óñ\0025ùÙ>\002î±\033:#6¨\t¶Òßøé,Ö\nìøÍ\017ÛW\024C^!-O\022ÊÖ\003\017\035õâ¡\röáTýIº5Æò2ë\n\036Ü6ý´hÅ&å©ÊÞ1Ó\bö\022Ö\034ùE&\016GÌ\0218²E.ô#çÿÞ«\005æý<LùG\bþ^\031Îì)Õ\026æÝO®ýVõÉ\003Nä\027\tìY\022!O\026üUëÈmîáQ\001ðÄ\006!ÿWÞ\034ü\007£\027<Å\r*\027Ñ½\034ó\bã%îÎº,Ú4A²\005;ö#\003\020ØÎ&\022B½\r\"Ø/Cý»\0077æ\016´(>¼\016Í\025<yã¹õ)ÇP×â\001ºÕhõí?\0135_³\030Âi6ç\020ðÃú \021hÉ\n¤`\034çG\000áï-¤ùÊô¬àÒõïÀ+\001\036Üû\0043#è-®Øï7\027\004ê\016 +îKû\036·¢TúÚÍë/þ\023Ö!\002S\tåÜñþ,Òî»/\b³\030]Óé@U\005\033(\rG\030ÙaÎF¢\031ÑóWÝþ÷\035\rÐ¢d½óª\\É\n3Ü\0070ãÄ\021)\004Þ¥\nö¯C\030Õ'¨G5´\030â>úÅó<û\007\035\023ÝÀ9é \001ã!\tøñã^\nÄ«\022B¿\0071KüÜ.@Òø\022æB\025\001$ò\036BðM9âZüÉ1õ\001Ï\035éL\004\022&Þj Ú5Ã)´\000ð\021cË0ü¥?±4\023*êG\002\037Ô`¦êÅ\"\023ç\007\031:°'óËêÓJ¯þ|\rÒ\032È¶*\tÞês\025À\013ùÇX¯\f\002Íí\017äôr#ÖøµQÔí\020Ê\006½Ùù/ãï\016(à\002öEÃýß\004p\033ûV¾\nà8è¼ú#\003ëó\036\017¸@à\\0Ûò\037Ôç.\025¹S\004=Ð\013Jå5\t\025Â.iæ$ý<\034\fË´=úÎP\033¸Õ\r'Rï»Ö\017©*4\023÷É\030Ù3©`\025ÐQ<ý\"î\006¡%\020A\0008ø¢Aý%¨ß-í\031Ä\003'ñH\000Û\032RïÏLóp\"\005\0279í\bl7êÌ\0362Gä\000ÜîN(\005Tôã\013.Ü¾\004Ç×,öÒý¹ìË\030ÁMð×\033Çù\024þ«?ß¯\035¡\013õ¶\003à\024­ÚéÁVÛÿ¼$âô\001³\024÷\bÄ!b\006\035Öëµ\021@Åþ÷4\031ãY°:\034Nâm\n*ß\005\016åb\tI½ØXöÎ^è;)ÐC\0174Ã*ÿ6÷\013,ò\024/È\021¦Ù-^Ñ@õ¯ÎúA\000\000\037îÓ'E\020ìJó\n\022éÄ\007\026õ±Z#Ì2óé8\037\0130\022ü\005Åâ\025úêd\bæ\035HÐªäDÓøT\034H\005àí\020)ç\0266Á\016kß.\006»gå\001¶¤)Îÿ¨FÙ'\0024Öêû<\027¶(Ò¦\002äí#×qð2¿\037×ö¸\\\021\003&\031h\006°ê4\nÁú$9ý¥P\nÞ,æõÌ;û\022\034ÙÍ$\025Ýn4 úòÍç@\036\022¼\001Üø\005\020\\ò)ÉOº\030\bO\001ª&ÍAðÆØíüÃ\017!ÿäðÍ´\032ØÂ\001ñ]¸\"\031¨Qè¬ó6\013Tù\006¹ïá\017/b¿\f©Kñ,{AÆ\033àÀ=ÿ\r5öý!Úç\023Uý\f-â<¹2ßô:Ù`&\026B\013mäE\035Ó¸ü\004×\f&ÝÈIþê?Å\033ÕQµ\027þöáÈ\bÓæ­í&Gú\026ÓÝè±ÏF\fÈ0íÞ\030ø\007W\035OÊ\022¼\003Ó1¬õ\004/ê%\024;H¿ï\001\0260\007!¿à*ô8\003%\tíGÓ+W\0317 \r\000×\bµe\035J+á7ò\032¶\004:À#K­\022Ôþ\ní.øJàþì\023Ê\r½÷Þé2VödÔì\031^Ó\r£åüË5Ý\036\002ëú\023Põ7_å.ëô\001¨\023¾\004gøH\020Õ\000éÉð%åD®\032è Å[(Û7X\000MÉ\017\034Ñ+Ãâ¯:ø\022\000²J\025fÀ\021ô\006=ÂÙ%¹ßÍÀ\031\020Ï\"3Ç\nï'ûØ ãÌ(óyÜ1\003^À*\006Ðq\020\t:§\033üñÑâ)ô±\büB\020\002&ÉCï\037Ú÷)éZ±æ\016añC\0040ýï³\004LÚ;\\æ\025?¬\013ë³\027\037\r©ö\025Ùú=½ñØöæ\005G¸ \t\0265\003wÛç\037ñL\tç3Þ\006/Ï´=¡ü#Ï\026/¤\né\037V:øá\025þ\037Ð\007SÇ.ÿ[3\006û=ÑIâ9!ã\0012#SÐ¿\017êg¨ù×ì$<\025³ÌÙ½lõ\031ÁUì\013\003\032×1L÷ÿÉÖm\024Ý&Ó\fÁtêöµ¤ðß\021õÔCÅäî¼\032ýÈ\017ïVË\023³ÿ\027>1Ø\002Æ=S¼Ëö](û\023 Ôÿ®\021û$âHÅ\bï¶á\033>®÷Æ\001è\032@­+\016E2\002%¼\035¥Ý$\023V,\007êe/¸\013F¥ë*âø$\023ð+å\032\017\0000\006à7ì I(æ@x½ò\023Ý!S\003(î3\tF¥0ò\006×É\033øÑ9èM\003ð\f÷Ú¸&Ó\001öÛ\034ûÕh\fòËPà®þ\b¡KÔèÂ\016Fµ\003\fÈñ3ÜÒ\0309\000`4\féÂû\021ãºSüÌÞ\"ÿXã\024nüÉ\026-gÏ8\002K\022·\031Bè)\0067Ä\037I\006º\031aÏ\"Ý-ðTù\032Ïô-á[\036\001\bø,©æùÊ\026;Ú\\Ð!\026ì\017`5îº!\007Û\016@²âû¾\034åÆóà5¿ÎNðµ\000Ú.ë\f4õFú\023¸:¦$Ûd\027üÖ¨\025ÄMì\r¹(Ôô°,\bDó\002×*·ö\027Ã\rLó,®íö#Ö\bFì$\fYû\tw\024øÝ\017\031ç¡úCÖè±Æ\004\037â\n\001êÂ6î»?ä!Í[\033\007H\001kì\033ßÃ6ä:\007EçýÕÇå7\035ÂW0¬\023ÿ1¹\036Öê\003#/Ê^>%\022Â!\001\026;[ìÑo¿+?\022\006\"J\013.õ\003Ù@â£\"Ç\022ü³&\013oþ\036ÐÜ'¦=\021\001_Ð\013\002èóRÒÝBÍï*@¶äþ©ô\005Îröß\t&Ú\0165ôüÌNß÷ÐèþÛf\022+óýÁð\016åÖP?ø\024íÆJ¬ð\003\033RÞ\027ûÜ=È\033(÷\005\026\000MÆ\020ñQ\tÓ3Ýí¸0OËñ¶ÿ\032\021«ð\035µ[ \031È\007¶é­1\027W8+\005Ïç§Ù/ú\rX1¾÷$ï·A\037*\021á\t¼cç¥6â÷\033Ù8\037À\024T\f\033æü¤\036.RÞFç×3\000\r):ñ%OÔ: \fÎÛú»\034ò1\"Z\b\033»â\024Ìé\nÒ/Mô¿ëlúª4ð\020\"µ\013aÐ\003²êú(ÿH¾\024@Ô\004öÂ*!\005VÅæÕ\003¿áù\025\000ßI\bë\000e\017áÀ\000ó<Ó*ÿ¥8u²\020ä\007ÿÍ1×\004NÇÔJÜü&§-\021làAòÉ×)\006ícã\027\nÏ£ø<¯\027õs\0173BîÄlö(=ÇØC®\027ÌKêdõ\030Ú\004ýÈ:ØU\f \030$äÿ\035õ=ÀîãDôË\007¯\036ä9ö®\020Æ;ëþi\023íß-E¨ë\035Ð\013¹\036\003å\025µ\037\néú7'Þ\020\005!ÄJî\037ç+«\031#¹íõB®\r.\007\026S\fÿ\031Ø1\r\002f\031Ý3û\"L²2ÜÀ\013\036üÌ\bÛÿQó-Ù\020©ÔU.õQ\002î·ù5­à\f>Z\025ñùáH5\022ÜÁìpÍé3ÖÈ\0379YüîO½ëÎT\002ñØ\016\032óGÒU#¼>(å«]û4CñþàÏ\023 Õ\r_\032ÑEü(Î÷Û\006cÅ\004Òþ](ú\027ß\002©%øºßèÂ$Ò\023ø'\b\035â·+É\005å%5\002çð\021÷Æ\030\005Í#ëÈ\033\006²x4Ãã§+Çæð\023R\003»\rBÐ'\020è\035Ê\b;²M\016Añg\024\t,\004¤@á.HÂ§D\024dùP¯ø\026¶Ö/b\rFá»\013J%\016íø\005Ló>\t\0000´×\0322å\036îý;¸-Lòä\037Õ÷¾\034ä\000ÎLõ\030\013ñÿØ\017ôþÒç6 Î\tÝjA\034\003Þì6üò\0319øæ×Á=¬&\031ýÚº\037ZëôÅ\000°Q¿Üõ\013Ö\025ª\0031î*ËÙ<'í6Û®kÊ³Yé$=\013¾ï\000\021,ìÅ\f¢úÍ!´Õ'hÜ\002T+ý\025ÜéÏi\023÷ß\007%9j\024Ö,\b\027z\000ë5XÅ\021a\t°R\006\020·\035ýå\";\005\0261Þ¬\033BÙ\\ºôþ9äM2\023W\tÆ\022°Ïñ_\bH/\fí6ÁÑ?\017ú¢#ó8æÊ£E¼!ûÝæý\031ó§ùÕUÆ\0161íÔ\036ùÇu\003(ö¦ã3\036Ô&²\007ó¾æ\000Ií0\007\0307 âÆú´\"\001ä)Mè¶ÌáN\002«ú\034&âÏ\016ôF$Ò7HÞ0è\026A\003ø¾âP\nòæÑ\017ÄK\026\005S\017îÇ\037Ø>ö\034áûCèº\000\017AðØQÊ¦\027û\004\034F\013ÀÑ[\017?ñ\0060­ç\005\025»ì\rÅ#t½àñ(b\021\001?·!Gý9ì\nûÍèAÝ\030vü*\016°Î%¤Øn÷Ì,©\033\0228ô\n]¯òÛ.î\0262ÝìÕýQ\032>Êj-÷\003Ðþ\013\0377ÏÚ\031¨Í-Û\030µ+Öf\034¿÷\001ªÒCß3b\004\020Ã(\nÞXê\005¼þÞ Õ2Æ\"ý± \005Æ*g\022ÂíÙ þà V\030@ô£ë²\bHê%ûï\023\006`õá¢<ð0\f'6ç\n\002ð\026¼óN\032î=\037Ó1är>ê\020H\007kç".getBytes("ISO-8859-1");
            TRI_BLUE_NOISE_MULTIPLIERS = new double[4096];
            for (int i = 0; i < 4096; i++) {
                TRI_BLUE_NOISE_MULTIPLIERS[i] = Math.exp((PaletteReducer.TRI_BLUE_NOISE[i] + 0.5f) * 0x1p-7f);
            }
            ENCODED_HALTONIC = "\001\001¦¦¦uuuÖÖÖÖÖÖ¢¢¢¢°°°°°ØØØØ\001\001¦¦¦uuuÖÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ\001\001\001¦¦uuuÖÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ¸¸5555uuuÖÖÖÖÖ¢¢¢¢¢°°°°ØØØØ¸¸¸5555uuÖÖÖÖÖL¢¢¢¢°°°°ØØØØ¸¸¸¸5555uuÖÖÖLL\022\022¢¢¢°°°ØØØØ¸¸¸¸\006555èÖLL\022\022\022\022\022°°°ØØØØ­­¸¸¸\006èèèè\022\022\022\022\022\022£££°°ØØØØ­­­­­èèèèè±±\022\022\022\022\022££££££°\\\\\\\\­­­èèèèè±±±\022\022\022\022££££££FFF\\\\22èèèèè±±±±\022\022\022\022£££££FFFFF22èèèè±±±±±;\022\022\022££££¾¾FFFFOOñèèè±±±±±;;,\022¾¾¾¾¾¾FFFFéééOOOOñ±±±,,,,,¾¾¾¾¾¾FFFéééééOOOOññ±,,,,,¾¾¾¾¾¾¾FFééééééOOOñññ,,,,,,ff¾¾¾¾FFééééééoooooo«««,,,,fffff[[ÕÕÕÕÕÕoooooooïïï««««««ffff[[[ÕÕÕÕÕÕÕooooooo3ïïïï««««««f[[[[ÕÕÕÕÕÕÕÕooooooo3\n\n\n\n««««[[[[[\035\035\035\035\035ÕÕÕIoooo\n\n\n\n\n\n\n««_Ó[[[\035\035\035\035\035\035\035\035III\n\n\n\n\n\n\n_______Ó[[\035\035\035\035IIIII\n\n\n\n\n\n\n______ðÓ[\035\035IIIIII 88¨¨\n\n\n\n______ððð\035IIIIII  8¨¨¨¨¨¨\n_____ððð\025\025\025\025\025\025\025\025\025III||| 8¨¨¨¨¨¨¨\033\033_vvððð\025\025\025\025\025\025\025\025\025\025||||||½ÊÊ¨¨¨¨¨¨\033vvvvvðrrr\025\025\025\025\025\025\025|||||½½½ÊÊÊ¨¨¨¨nnvvvvvrrrrrrrr\025\025\021||||½½½ÊÊÊÊÊ¨¨nnnvvvvrrrrrrrrrr\021\021||½½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrr\021\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛ½½bbbbbÊÊÊÊÊnnnnnn\001\001¦¦¦uuuÖÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ\001\001\001¦¦uuuÖÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ\001\001\001¦¦uuuuÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ¸¸5555uuuÖÖÖÖÖÖ¢¢¢¢°°°°ØØØØ¸¸¸5555uuÖÖÖÖÖL¢¢¢¢°°°°ØØØØ¸¸¸¸5555uuÖÖÖLL\022\022¢¢¢°°°ØØØØ¸¸¸¸\006555èÖLL\022\022\022\022\022°°°ØØØØ­­¸¸¸\006èèèè\022\022\022\022\022£££°°ØØØØ­­­­­èèèèè±±\022\022\022\022\022££££££°\\\\\\\\­­­èèèèè±±±\022\022\022\022££££££FFF\\\\22èèèèè±±±±\022\022\022\022£££££FFFFF22èèèè±±±±±;\022\022\022££££¾¾FFFFOOñèèè±±±±±;;,\022¾¾¾¾¾¾FFFFéééOOOOñ±±±,,,,,¾¾¾¾¾¾FFFéééééOOOOññ±,,,,,¾¾¾¾¾¾¾FFééééééOOOñññ,,,,,,ff¾¾¾¾FFééééééoooooñ«««,,,,fffff[[ÕÕÕÕÕÕoooooooïïïï««««««ffff[[[ÕÕÕÕÕÕÕooooooo3ïïïï««««««f[[[[ÕÕÕÕÕÕÕÕooooooo3\n\n\n\n««««[[[[\035\035\035\035\035ÕÕÕIoooo\n\n\n\n\n\n\n««Ó[[[\035\035\035\035\035\035\035\035III\n\n\n\n\n\n\n_______Ó[[\035\035\035\035IIIII\n\n\n\n\n\n\n______ðÓ[\035\035IIIIII 88¨¨\n\n\n\n______ððð\035IIIIII  8¨¨¨¨¨¨\n_____ððð\025\025\025\025\025\025\025\025\025III||| 8¨¨¨¨¨¨¨\033\033__vððð\025\025\025\025\025\025\025\025\025\025||||||½ÊÊ¨¨¨¨¨¨\033vvvvvðrrr\025\025\025\025\025\025\025|||||½½½ÊÊÊ¨¨¨¨nnvvvvvrrrrrrrr\025\025\021||||½½½ÊÊÊÊÊ¨¨nnnvvvvrrrrrrrrrr\021\021||½½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrr\021\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛ½½bbbbbÊÊÊÊÊnnnnnnÂÂ\001¦¦¦uuÖÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ\001\001\001¦¦¦uuuÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ\001\00155¦¦uuuÖÖÖÖÖ¢¢¢¢¢°°°°°ØØØØ¸¸5555uuuÖÖÖÖÖL¢¢¢¢°°°°ØØØØ¸¸¸5555uuuÖÖÖLL¢¢¢¢¢°°°°ØØØØ¸¸¸¸5555uuÖÖÖLLL\022¢¢¢°°°ØØØØ¸¸¸¸\006\006555\024\024L\022\022\022\022°°°ØØØØ­­¸¸¸\006èèèè\022\022\022\022\022£££°°ØØØ\\­­­­­cèèèè±±\022\022\022\022\022££££££°\\\\\\\\­­­­èèèèè±±±\022\022\022\022\022£££££FFF\\\\22èèèèè±±±±\022\022\022\022£££££FFFFF22èèèè±±±±±;\022\022\022££££¾¾FFFFOOñèèè±±±±±;;,\022¾¾¾¾¾¾FFFFéééOOOOñ±±±;,,,,¾¾¾¾¾¾FFFéééééOOOOññ±,,,,,¾¾¾¾¾¾¾FFééééééOOOñññ,,,,,,fff¾¾¾¾Fééééééoooooñ«««,,,,fffff[[ÕÕÕÕÕÕoooooooïïïï««««««ffff[[[ÕÕÕÕÕÕÕooooooo3ïïïï««««««f[[[[ÕÕÕÕÕÕÕÕooooooo3\n\n\n\n««««[[[[\035\035\035\035\035ÕÕÕIoooo\n\n\n\n\n\n\n««Ó[[[\035\035\035\035\035\035\035\035III\n\n\n\n\n\n\n_______Ó[[\035\035\035\035IIIII\n\n\n\n\n\n\n______ðÓ[\035\035IIIIII 88¨¨\n\n\n\n______ððð\035IIIIII  8¨¨¨¨¨¨\n_____ððð\025\025\025\025\025\025\025\025\025III||| 8¨¨¨¨¨¨¨\033\033__vððð\025\025\025\025\025\025\025\025\025\025||||||½ÊÊ¨¨¨¨¨¨\033\033vvvvðrrr\025\025\025\025\025\025\025|||||½½½ÊÊÊ¨¨¨¨nnvvvvvrrrrrrrr\025\025\021||||½½½ÊÊÊÊÊ¨¨nnnvvvvrrrrrrrrrr\021\021||½½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrr\021\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛÛ½bbbbbÊÊÊÊÊnnnnnnÂÂÂÀ¦¦uuu....L¢¢¢¢¢°°°°°ØØØØÂÂ\032\032¦¦uuu....LL¢¢¢¢°°°°°ØØØØ\032\032\03255¦uuu....LL¢¢¢¢¢°°°°ØØØØ¸\032\032555uuu...LLL¢¢¢¢¢°°°°ØØØØ¸¸¸55555uu..LLLL¢¢¢¢°°°°ØØØØ¸¸¸¸55555\024\024ÖLLLL\022¢¢¢°°°ØØØØ¸¸¸¸\006\006\00655\024\024\024\024L\022\022\022\022¢°°°ØØØØ­­¸¸¸\006\006èèè\022\022\022\022\022££££°°Ø\\\\\\­­­­­cèèèè\022\022\022\022\022££££££°\\\\\\\\­­­­èèèèè±±±\022\022\022\022\022£££££FFF\\\\22èèèèè±±±±\022\022\022\022£££££FFFFF222èèèè±±±±±;\022\022\022£££¾¾¾FFFFOOOñèèè±±±±±;;,\022¾¾¾¾¾¾¾FFFéééOOOOOññ±±±;,,,,¾¾¾¾¾¾FFFéééééOOOOññ±,,,,,,¾¾¾¾¾¾FFééééééOOOñññ,,,,,,ffff¾¾¾Fééééééoooooñ«««,,,,fffff[[ÕÕÕÕÕÕéooooooïïïïï««««««fff[[[ÕÕÕÕÕÕÕooooooo3ïïïï«««««f[[[[ÕÕÕÕÕÕÕÕooooooo3\n\n\n\n««««[[[[\035\035\035\035\035ÕÕÕIooo\n\n\n\n\n\n\n««Ó[[[\035\035\035\035\035\035\035\035III\n\n\n\n\n\n\n_______Ó[[\035\035\035\035IIIII\n\n\n\n\n\n\n______ðÓ[\035\035IIIIII 88¨¨\n\n\n\n______ðððIIIIII  8¨¨¨¨¨¨\033_____ððð\025\025\025\025\025\025\025\025\025III||| 8¨¨¨¨¨¨¨\033\033__vððð\025\025\025\025\025\025\025\025\025\025||||||½ÊÊ¨¨¨¨¨¨\033\033vvvvðrrr\025\025\025\025\025\025\025|||||½½½ÊÊÊ¨¨¨¨\033nvvvvvrrrrrrrr\025\025\021||||½½½ÊÊÊÊÊ¨¨nnnvvvvrrrrrrrrrr\021\021\021|½½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrr\021\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛÛ½bbbbbÊÊÊÊÊnnnnnnÂÂÂÀÀÀ¦uu....LL¢¢¢¢¢°°°°ØØØØÂÂÂ\032ÀÀ¦uu....LL¢¢¢¢¢°°°°ØØØØ\032\032\032\03255¦uu....LL¢¢¢¢¢°°°°ØØØØ\032\032\032\032555uu...LLLL¢¢¢¢°°°°ØØØØ¸¸¸55555uu..LLLL¢¢¢¢¢°°°ØØØØ¸¸¸¸\0065555\024\024\024LLL\022¢¢¢°°°ØØØØ¸¸¸¸\006\006\006\0065\024\024\024\024L\022\022\022\022°°ØØØØ­­¸¸¸\006\006\006èè\024\024\022\022\022\022£££££°°\\\\\\\\­­­­­ccèèè\022\022\022\022\022£££££F\\\\\\\\­­­­­­cèèèè±±±\022\022\022\022\022£££££FFF\\\\222èèèèè±±±;\022\022\022\022££££££FFFF222èèèè±±±±;;\022\022£££¾¾¾FFFFOOOññèè±±±±±;;,,¾¾¾¾¾¾¾FFFééOOOOññ±±±;,,,,¾¾¾¾¾¾FFFéééééOOOOññ±,,,,,,¾¾¾¾¾¾FFééééééOOOñññ,,,,,,ffffff¾Féééééééooooñ««,,,,,fffff[[ÕÕÕÕÕÕéoooooo3ïïïïï«««««ffff[[ÕÕÕÕÕÕÕooooooo3ïïïï«««««ff[[[ÕÕÕÕÕÕÕÕooooo33\n\n\n\n««««[[[[\035\035\035\035\035ÕÕÕÕooo\n\n\n\n\n\n\n««ÓÓ[[\035\035\035\035\035\035\035\035III\n\n\n\n\n\n\n_______ÓÓ[\035\035\035\035IIII\n\n\n\n\n\n\n______ðÓÓ\035\035IIIII  88¨\n\n\n\n\n______ðððIIIIII  8¨¨¨¨¨¨\033_____ððð\025\025\025\025\025\025\025\025\025III||  8¨¨¨¨¨¨¨\033\033__vððð\025\025\025\025\025\025\025\025\025\025||||||½ÊÊ¨¨¨¨¨¨\033\033vvvvðrrr\025\025\025\025\025\025\025|||||½½½ÊÊÊ¨¨¨¨\033nvvvvvrrrrrrrr\025\025\021||||½½½ÊÊÊÊÊ¨¨nnnvvvvrrrrrrrrrr\021\021\021|½½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrr\021\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛÛ½bbbbbÊÊÊÊÊnnnnnnÂÂÂÀÀÀÀÀu....LLL¢¢¢¢¢°°°°ØØØØÂÂÂ\032ÀÀÀÀu....LLL¢¢¢¢¢°°°°ØØØØ\032\032\032\032ÀÀÀÀu...LLLL¢¢¢¢¢°°°°ØØØØÒ\032\032\032\032555u...LLLL¢¢¢¢¢°°°ØØØØ¸¸\032\032\0065555\024\024.LLLL¢¢¢¢°°°ØØØØ¸¸¸\006\006\006\00655\024\024\024LLL¢¢¢°°ØØØØ¸¸¸¸\006\006\006\006\006\024\024\024\024\024\022\022\022£°°\\\\\\\\­¸¸¸¸c\006\006\006è\024\024\024\024\022\022\022£££££#°\\\\\\\\­­­­¸ccèèè\022\022\022\022\022£££££F\\\\\\\\­­­­­2cèèèè±±\022\022\022\022\022££££££FF\\\\222èèèèè±±±;;\022\022\022££££££FFFF2222èèèè±±±;;;\022\022W££¾¾¾FFFF22Oññèègg±±±;;;;,¾¾¾¾¾¾¾FFFééOOOOññ±±±;,,,,¾¾¾¾¾¾¾FFééééOOOOñññ±,,,,,,¾¾¾¾¾¾FFééééééOOOñññ,,,,,,fffffffFéééééééoooñññïïï«,,,,,ffffff[ÕÕÕÕéééoooooo3ïïïïï«««««ffff[[ÕÕÕÕÕÕÕooooooo33ïïïï««««ff[[[ÕÕÕÕÕÕÕÕoooo333\n\n\n««««Ó[[[\035\035\035\035\035ÕÕÕÕoo3\n\n\n\n\n\n««ÓÓ[[\035\035\035\035\035\035\035III\n\n\n\n\n\n_______ÓÓ[\035\035\035\035IIII\n\n\n\n\n\n\n______ðÓÓ\035\035IIIII  88¨\n\n\n\n\n______ðððIIIIII  88¨¨¨¨¨\033_____ððð\025\025\025\025\025\025\025\025\025III||  88¨¨¨¨¨¨\033\033__vððð\025\025\025\025\025\025\025\025\025\025||||||½ÊÊ¨¨¨¨¨¨\033\033vvvvðrr\025\025\025\025\025\025\025\025|||||½½½ÊÊÊ¨¨¨¨\033\033vvvvvrrrrrrrr\025\025\021\021|||½½½ÊÊÊÊÊ¨¨nnnvvvvrrrrrrrrrr\021\021\021|½½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrr\021\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛÛbbbbbbÊÊÊÊÊnnnnnnÒÂÂÀÀÀÀÀÀÔ..LLLLL¢¢¢¢ããã#°°°ØØØØÒÒÂ\032ÀÀÀÀÀÔ..LLLLL¢¢¢¢ããã##°°ØØØØÒÒ\032\032ÀÀÀÀÀ...LLLLL¢¢¢¢ãããã#°°ØØØØÒÒÒ\032\032ÀÀÀÀ\024..LLLL¢¢¢¢ãããã#°°ØØØØÒÒÒÒ\032\006\00655\024\024\024LLLL¢¢¢ãããã##°ØØØØ¸¸¸\006\006\006\006\006\006\024\024\024\024L\022¢¢ãããã###Ø\\\\\\¸¸¸¸\006\006\006\006\006\024\024\024\024\024\022\022\022££ãã###\\\\\\\\¸¸¸¸cc\006\006\006\006\024\024\024\024\022\022\022\022££££##\\\\\\\\­­­­cccc\006è\024\024\024\022\022\022\022£££££#\\\\\\\\­­­­22ccèèè±;\022\022\022\022££££££FF\\\\2222cèèèè±±;;\022\022\022WW££££FFFF22222èèèg±±±;;;\022\022WWW¾¾¾FFFF2222ññèggg±±±;;;;,W¾¾¾¾¾¾FFFOOOOññ±±;;;,,,¾¾¾¾¾¾¾FFééééOOOOñññ±,,,,,,¾¾¾¾¾¾FFééééééOOOñññ,,,,,,,ffffffFéééééééOOoñññïïïï,,,,,,ffffff[ÕÕÕééééoooooñ3ïïïïï«««««ffff[[ÕÕÕÕÕÕÕooooooo33ïïïï««««ff[[[ÕÕÕÕÕÕÕÕooo3333\n\n\n««ÓÓ[[\035\035\035\035\035ÕÕÕÕo3\n\n\n\n\n\n«ÓÓÓ[\035\035\035\035\035\035II\n\n\n\n\n\n______ÓÓÓ\035\035\035\035IIIIµ\n\n\n\n\n\n______ðÓÓ\035\035IIIII  888\n\n\n\n\n______ðððIIIII   88¨¨¨¨\n\033\033____ððð\025\025\025\025\025\025\025\025\025III||  88¨¨¨¨¨¨\033\033\033_vððð\025\025\025\025\025\025\025\025\025\025||||||½8Ê¨¨¨¨¨\033\033\033vvvvðrr\025\025\025\025\025\025\025\025|||||½½½ÊÊÊ¨¨¨¨\033\033vvvvvrrrrrrrr\025\021\021\021|||½½½ÊÊÊÊÊ¨¨nnnnvvvrrrrrrrrrr\021\021\021|½½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrrÛ\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛÛbbbbbbêÊÊÊÊnnnnnnÒÒÒiÀÀÀÀ^^ÔÔÔLLÚÚÚ¢¢ããããã###ØØØØÒÒÒÒÀÀÀÀÀ^ÔÔÔLLÚÚÚ¢¢ããããã###ØØØØÒÒÒÒÀÀÀÀÀ^ÔÔLLLÚÚÚÚ¢ããããã###ØØØØÒÒÒÒ\032ÀÀÀÀ^ÔÔLLLLÚÚÚ¢ããããã####Ø\\\\ÒÒÒÒÒ\032\006ÀÀ\024\024\024LLL¢¢ãããã####\\\\\\<<ÒÒ\006\006\006\006\006\024\024\024\024L¢¢ããããã###\\\\\\¸¸¸cc\006\006\006\006\024\024\024\024\024\022\022ããããã###\\\\\\¸¸¸¸ccc\006\006\006\024\024\024\024\024\022\022\022W££ã###\\\\\\­­­­cccc\006\004\024\024\024\024\024\022\022\022WW£££##\\\\\\­­­­2cccc\004\004\024\024;\022\022\022WWW£££FF\\\\22222c\004\004\004g±;;;;\022\022WWWW££FFFF222222\004\004ggg±;;;;;\022WWWW¾¾¾FFF2222ñggggg±±;;;;;WW¾¾¾¾¾FFFOOOOñññggg±±;;;,,,¾¾¾¾¾¾¾FFééééOOOOñññ;;,,,,,¾¾¾¾¾¾¾FééééééOOOññññïý,,,,,,ffffffféééééééOOññññïïïïïïý,,,,,ffffffféééééééoooooñ33ïïïï««««fffff[ÕÕÕÕÕÕÕooooo333ïïïï«««fff[[ÕÕÕÕÕÕÕÕoo3333ï\n\n««ÓÓ[[\035\035\035\035ÕÕÕÕ33\n\n\n\n\n«ÓÓÓ[\035\035\035\035\035\035IIµ\n\n\n\n\n_____mÓÓÓ\035\035\035IIIIµµ\n\n\n\n\n______ðmÓ\035\035IIIII  888µ\n\n\n\n______ðððIIIII   88¨¨¨¨\n\033\033____ððð\025\025\025\025\025\025\025\025\025III||  88¨¨¨¨¨¨\033\033\033_ðððð\025\025\025\025\025\025\025\025\025\025||||||½88¨¨¨¨¨\033\033\033\033vvððrr\025\025\025\025\025\025\025\021\021|||||½½ÊÊÊ¨¨¨¨\033\033\033vvvvrrrrrrrr\025\021\021\021|||½½½ÊÊÊÊÊ¨¨nnnnvvvrrrrrrrrrr\021\021\021||½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrÛÛ\021\021½½½½bbÊÊÊÊÊÊnnnnnvrrrrrrrrrrrÛÛÛÛbbbbbbêêÊÊÊnnnnnn¤¤Òiiiii^^^ÔÔÔÚÚÚÚÚÚããããã#####\\\\¤ÒÒÒiiii^^^ÔÔÔÚÚÚÚÚÚããããã#####\\\\ÒÒÒÒiiiÀ^^^ÔÔÔÚÚÚÚÚÚããããã#####\\\\ÒÒÒÒÒÀÀÀÀ^^ÔÔÔÚÚÚÚÚÚããããã#####\\\\<<ÒÒÒÒùùù^\024\024ÔLÚÚããããã####\\\\<<<ÒÒ\006ùùùù\024\024\024\024ããããã####\\\\<<<ccc\006\006\006\006\024\024\024\024ãããã####\\\\¸¸¸cccc\006\006\006\024\024\024\024\024WWWãã####\\\\­­­­ccccc\004\004\024\024\024\024wWWWWW£###\\\\­­­22ccc\004\004\004\004\024\024\024\024;\022WWWWWW£#F\\\\2222222c\004\004\004\004g;;;;;\022WWWWWWW¾FFF222222\004\004gggg±;;;;;WWWWWW¾¾FFF2222ñ\004gggg±;;;;;;WWW¾¾¾¾¾FFOOOOññggggg;;;;;,,,¾¾¾¾¾¾FFéééOOOññññý;,,,,,f¾¾¾¾¾¾FéééééOOOññññïïýý,,,,,,ffffffféééééé\036OOññññ3ïïïïýý,,,,,ffffffféééééé\036\036oooññ33ïïïïý«««fffff[ÕÕÕÕÕÕÕooo3333ïïï«««fff[[ÕÕÕÕÕÕÕÕo3333ïï\n««ÓÓÓ[\035\035\035\035ÕÕÕÕ333\n\n\n\n«ÓÓÓÓ\035\035\035\035\035\035IIµµ\n\n\n\n_____mÓÓÓ\035\035\035IIIIµµµ\n\n\n\n______ðmÓ\035\035IIII   888µµ\n\n\nS_____ðððIIIII   888¨¨¨\n\033\033___ðððð\025\025\025\025\025\025\025\025\025II||   888¨¨¨¨\033\033\033\033_ðððð\025\025\025\025\025\025\025\025\025\021|||||| 88¨¨¨¨¨\033\033\033\033vvððr\025\025\025\025\025\025\025\025\021\021|||||½½ÊÊÊ¨¨¨¨\033\033\033vvvvrrrrrrrr\025\021\021\021|||½½½½ÊÊÊÊ¨¨nnnnvvvrrrrrrrrr\021\021\021\021||½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrÛÛ\021\021½½½bbbêÊÊÊÊÊnnnnnvrrrrrrrrrrÛÛÛÛÛbbbbbbêêêÊÊnnnnnn¤¤¤iiiii^^^ÔÔÔÔÚÚÚÚÚÚããããã#####\\¤¤¤Èiiii^^^ÔÔÔÔÚÚÚÚÚÚããããã#####\\¤¤ÈÈiiii^^^ÔÔÔÔÚÚÚÚÚÚããããã#####\\<<ÒÈÈiiii^^ÔÔÔÔÚÚÚÚÚããããã#####\\<<ÒÈÈùùùù^^ÔÔÔÔÚÚÚÚÚãããã#####\\<<<ÈÈÈùùùù\024\024wwÚãããã#####\\<<<<Ècùùùù\024\024\024\024wwããã#####\\<<<ccccc\006ù\024\024\024\024wwwWWãã###\\\\­­­ccccc\004\004\004\024\024\024\024wwWWWWW###\\\\22222ccc\004\004\004\004\024\024\024wwwWWWWWWW##\\\\2222222c\004\004\004\004gg\024;;;;;;WWWWWWWWFFF22222\004\004\004\004ggg;;;;;;WWWWWWW¾FFF2222ñ\004ggggg;;;;;;¿WWWW¾¾¾FFOOOññggggg;;;;;,,¿W¾¾¾¾¾¾FééOOOññññggggýý;,,,,,ffff¾¾Féééé\036\036OOOññññïïïïýýý,,,,,fffffffééééé\036\036OOññññ3ïïïïýýý,,,,fffffffééééé\036\036\036oààññ333ïïïýýý,ffffffÕÕÕÕÕÕ\036\036àà3333ïïïý«ffff[ÕÕÕÕÕÕÕÕ33333ïï«ÓÓÓÓ\035\035\035\035ÕÕÕÕ333\n\n\n\nÓÓÓÓ\035\035\035\035\035IIµµµ\n\n\nS___mmÓÓ\035\035\035III µµµµ\n\nSS_____mmÓ\035IIII   888µµµ\n\nSS___ððððIIII    888¨¨¨\n\033\033\033__ðððð\025\025\025\025II||   888¨¨¨¨\033\033\033\033\033ðððð\025\025\025\025\025\025\025\025\025\021|||||  888¨¨¨¨\033\033\033\033vvðð\025\025\025\025\025\025\025\025\025\021\021|||||½½ÊÊÊ¨¨¨¨\033\033\033vvvvrrrrrrrr\025\021\021\021|||½½½½ÊÊÊÊ¨¨nnnnvvvrrrrrrrrr\021\021\021\021||½½½½ÊÊÊÊÊÊnnnnnvvrrrrrrrrrrÛÛ\021\021½½½bbbêêÊÊÊÊnnnnnnrrrrrrrrrrÛÛÛÛÛbbbbbbêêêÊÊnnnnnn¤¤¤¤iiiii^^^ÔÔÔqÚÚÚÚÚÚãããã######¤¤¤¤iiiii^^^ÔÔÔqÚÚÚÚÚãããã######¤¤¤Èiiiii^^^ÔÔÔqÚÚÚÚÚãããã######<¤ÈÈÈiiii^^^ÔÔÔÔÚÚÚÚÚãããã·#####<<ÈÈÈÈùùù^^^ÔÔÔÔÚÚÚÚããã·#####<<<ÈÈÈùùùùÔÔwwwÚÚããã·#####<<<<ÈÈùùùù\024wwwwãã··###\\<<<<<ccùùùù\024wwwwwWW··###\\<<<<cccc\004\004\004\004\024\024wwwwwWWWW··##\\22222ccc\004\004\004\004\004\024\024wwwwWWWWW¹¹¹¹222222c\004\004\004\004\004ggwww;;WWWWWWW¹¹F22222\004\004\004\004gggg;;;;;¿¿WWWWWW¾FF2222\004\004gggggú;;;;;¿¿WWWW¾¾FFOOOññgggggg;;;;;,¿¿¿W¾¾¾¾FOOOññññggggýýý;,,,¿¿fffff)ééé\036\036\036\036OOñññññïïïýýýý,,,,ffffffféééé\036\036\036\036Oñññññ3ïïïýýýý,,,,fffffféééé\036\036\036\036àààñññ33ïïïýýý,,ffffffÕÕÕÕÕ\036\036\036àààà3333ïïýýffÓÓÓÕÕÕÕÕÕÕÕà33333ïï«ÓÓÓÓ\035\035\035ÕÕÕÕÕt3333\n\nSmÓÓÓ\035\035\035\035\035Itµµµµ\n\nSS_mmÓÓ\035\035\035III 88µµµµ\nSS____ðmmm\035III    888µµµµSSS___ððððIIII    8888µµS\033\033\033__ððððII|    8888¨¨¨\033\033\033\033\033ðððð\025\025\025\025\025\025\025\025\025\021|||||  888¨¨¨¨\033\033\033\033\033vðð\025\025\025\025\025\025\025\025\021\021\021|||||½½88Ê¨¨¨¨\033\033\033\033vvvrrrrrrrr\021\021\021\021|||½½½½ÊÊÊÊ¨¨\033nnnvvvrrrrrrrrr\021\021\021\021\021|½½½½êêÊÊÊÊnnnnnnvrrrrrrrrrrÛÛ\021\021\021½½bbbêêêÊÊÊnnnnnnrrrrrrrrrrÛÛÛÛÛbbbbbbêêêêÊnnnnnn¤¤¤¤\034iiii^^^^ÔÔqqqJJJJãã····###¤¤¤¤\034iiii^^^^ÔÔqqqJJJJãã····###¤¤¤¤Èiiii^^^^ÔÔqqqÚÚÚãã····###¤¤¤ÈÈÈiii^^^^ÔÔqqqÚÚÚãã····###<<ÈÈÈÈùii^^^ÔÔÔqÚÚÚã·····##<<<ÈÈÈùùùù^ÔÔÔqÚÚã·····##<<<<ÈÈÈùùùwwwww······#<<<<ÈÈÈùùùwwwwwwW····¹¹<<:cccùùùwwwwwwWWW··¹¹¹::ccc\004\004\004\004\004\024wwwwwwWWWW¹¹¹¹22222cc\004\004\004\004\004ggwwwww¿¿WWWWW¹¹¹22222\004\004\004\004ggggúúúú;¿¿¿¿WWWW¹¹¹2222\004\004\004ggggúúúú;;¿¿¿¿WWW))¹22Oññggggggúúúú;;¿¿¿¿¿))))OOOññññggggýýýú,,\r¿¿¿ff)))\036\036\036\036\036\036\036OOñññññ\003ïïýýýýý,,\rffffff)éé\036\036\036\036\036\036Oàññññ3ïïïýýýý,,,\rffffffééé\036\036\036\036\036ààààññ333ïïýýýý,ffffffÕÕÕåå\036\036\036\036ààààà3333ïïýýýÓÓÓÓÓÕÕÕÕÕÕååàà333333ïýÓÓÓÓ\035\035\035ÕÕÕÕÕtt333µµSSmÓÓÓ\035\035\035\035\035IttµµµµµSSS_mmmÓ\035\035\035II   t8µµµµµSSS___mmmm\035III    888µµµµSSSS__ðððmIII     8888µµSSS\033\033_ððððI||    88888¨¨\033\033\033\033\033ðððð\025\025\025\025\025\025\025\025\021\021||||   8888¨¨¨\033\033\033\033\033ððð\025\025\025\025\025\025\025\021\021\021\021||||½½888¨¨¨\033\033\033\033\033vvðrrrr\025\021\021\021\021\021|||½½½ÊÊÊÊ¨¨\033\033\033nvvvrrrrrr\021\021\021\021\021|½½½½êêêÊÊÊnnnnnnvrrrrrrÛÛÛ\021\021\021½½bbbêêêêÊánnnnnnrrrrrÛÛÛÛÛÛbbbbbbêêêêêánnnnn¤¤¤\034\034\034QQQQ^^^ÔÔqqqJJJJJJ········¤¤¤\034\034\034QQQQ^^^ÔÔqqqJJJJJJ\037·······¤¤¤\034\034\034QQQQ^^^ÔÔqqqJJJJJ\037\037·······¤¤¤¤\034iiQQ^^^ÔÔqqqJJJJJ\037·······\023¤¤ÈÈÈÈiii^^^ÔÔqqqJJJJ\037·······<<<ÈÈÈÈùùùÔÔÔqqqJ\037\037······<<<<ÈÈÈùùù!!www\037\037·····¹<<<<ÈÈÈùùùwwwww\037\037···¹¹::::ùùùwwwwww\037\037··¹¹¹:::c\004\004\004\004wwwwwwWW\037¹¹¹¹ËËËËË\004\004\004\004\004\004gwwwwww¿¿¿¿W@@¹¹¹ËËËËËË\004\004\004\004gggúúúúú¿¿¿¿¿¿@@@¹¹2ËËËÆ\004\004ggggúúúúúú¿¿¿¿¿@@)))222Æñ\004gggggúúúúú\r¿¿¿¿¿))))\036\036\036\036OOñññ\003\003\003ggúúúúú\r\r¿¿¿¿))))\036\036\036\036\036\036\036OOññññ\003\003\003\003ýýýýý\r\r\r\r¿ff)))\036\036\036\036\036\036\036\036àààñññ33ïïýýýýý\r\r\r\rffff)\036\036\036\036\036\036\036\036\036ààààñ3333ïýýýýý\r\r\rfffffååååååå\036\036ààààà33333ïýýýCÓÓÓÓÕÕÕååååååààààt33333HçççÓÓÓÓ\035\035ÕÕÕÕåååttt3µµµµSççmmÓÓ\035\035\035\035ItttµµµµµSSSSmmmÓ\035\035II   tttµµµµµSSSS__mmmm\035II     888µµµµSSSSS_ððmmII     8888µµµSSSS_ððððÙÙÙ\021|     88888¨¨\033\033\033\033\033\033ðððÙÙÙÙÙ\025\025\025\021\021\021|||   8888¨¨\033\033\033\033\033\033ðððÙ\025\025\025\025\021\021\021\021|||| 8888¨¨¨\033\033\033\033\033\033vðrr\021\021\021\021\021\021|||½½½êêÊÊ¨¨\033\033\033\033nvvrrr\021\021\021\021\021\021|½½½½êêêêÊáánnnnnvrrrrÛÛÛ\021\021\021½bbbbêêêêêánnnnnnrrrÛÛÛÛÛÛÛbbbbêêêêêêánnnnn¤\034\034\034\034\034QQQQQ^^^´´qqqJJJJ\031\031\037····òò¤\034\034\034\034\034QQQQQ^^^´´qqqJJJJ\031\031\037·····ò¤¤\034\034\034\034QQQQQ^^^´´qqqJJJJ\031\037\037·····ò¤¤¤\034\034QQQQ^^^´´qqqJJJJ\031\037\037·····ò\023\023\023\034\034QQQ^^^ÔqqqqJJJJ\031\037\037\037·····\023\023\023\023ÈÈù^!!!qqJJJJ\037\037\037····¹\023\023\023\023ÈÈÈùùù!!!!!JJ\037\037\037···¹¹<ÈÈÈùùù!!!!!w\037\037\037\037·¹¹¹:::Èùùwwwww\037\037\037¹¹¹¹:::::ùùwwwww\037\037\037\037¹¹¹:::::\004\004\004\004Mwwwww¿¿¿@@@¹¹¹kËËËËËËÆ\004\004\004gggúúúúúú¿¿¿¿¿@@@¹¹ËËËËËÆ\004\004\004gggúúúúúú¿¿¿¿¿@@@))ËËËÆÆ\003\003\003gggúúúúú\r¿¿¿¿¿@)))\036\036\036\036\036\036ññ\003\003\003\003\003úúúúú\r\r\r¿¿¿))))\036\036\036\036\036\036\036ñññ\003\003\003\003\003ýýýý\r\r\r\r¿¿))))\036\036\036\036\036\036\036\036àààñ\003\003\003\003Ñýýýýý\r\r\r\rCf)))ååååå\036\036\036\036ààààà3333ýýýýý\r\r\r\rCCff)åååååååååààààà33333Hýýýç\r\r\rCCÓÓÓåååååååååàààààt33333HççççmÓÓÓ&&&ååååååttttµµµµµSççççmmÓÓ&\035\035\035ååttttµµµµSSSççmmmm\035\035I    ttttµµµµµSSSSS_mmmm\035I      888µµµµSSSSS_ððmmÙI      8888µµµSSSSSððððÙÙÙÙÙ\021      88888µµ\033\033\033\033\033\033ðððÙÙÙÙÙÙÙ\021\021\021\021||    88888¨\033\033\033\033\033\033\033ððÙÙÙÙÙ\021\021\021\021\021|||| 88888¨¨\033\033\033\033\033\033vð\021\021\021\021\021\021|||½½êêêê***\033\033\033\033\033vvr\021\021\021\021\021\021|½½½bêêêêê*ánnnnnvrÛÛÛÛ\021\021\021½bbbbêêêêêáánnnnnrrÛÛÛÛÛÛÛbbbbêêêêêááánnnn\034\034\034\034\034\034QQQQQQQ´´´´´´JJJJ\031\031\031\037·òòòò\034\034\034\034\034\034QQQQQQQ´´´´´´JJJJ\031\031\031\037·òòòò\034\034\034\034\034\034QQQQQQ^´´´´qJJJJ\031\031\031\037·òòòò\034\034\034\034\034\034QQQQQQ^´´´´qJJJJ\031\031\037\037\037·òòò\023\023\023\034\034QQQQ^^´´´´qJJJJ\031\031\037\037\037··òò\023\023\023\023\023VV!!!!qJJJJ\031\031\037\037\037··¹ò\023\023\023\023\023ÈV!!!!!!JJJ\031\037\037\037\037\037¹¹¹\023\023\023ÈÈùù!!!!!!óóó\037\037\037\037\037¹¹¹::::\020\020!!!!!óóó\037\037\037\037\037¹¹¹::::\020\020\020MMMMMóóó\037\037\037\037¹¹¹:::::\020\020\020MMMMMEóó¿¿¿@@@@¹¹kkËËËËËÆÆ\004\004\004gMMMMEEE¿¿¿¿@@@@@¹kkkkËËËËËÆÆ\004\004\003\003gúúúúúúE¿¿¿¿@@@@)þþþþþËËËËÆÆÆ\003\003\003\003\003úúúúú\r\r¿¿¿¿@@))þþþþ\036\036ÆÆÆ\003\003\003\003\003úúúúú\r\r\r¿¿¿@)))\036\036\036\036\036\036\036\003\003\003\003\003\003ýýúú\r\r\r\r¿¿))))ååå\036\036\036\036\036à÷÷\003\003ÑÑýýýý\r\r\r\r\rCC)))ååååååå\036\036àààà÷÷ÑÑÑHHýýý\r\r\r\rCCC))åååååååååààààà÷333HHHýçç\r\r\rCCCCÓååååååååååààààtt333HHHçççççCCCÓÓ&&&&ååååååàttttµµµµHHçççççmmmÓ&&&&&&ååååtttttµµµµSSSççççmmmm&&&&&&&&     ttttµµµµµSSSSSçmmmm&&&&\035      t88µµµµSSSSSSðmmmÙÙÙÙÙ       8888µµµSSSSSððððÙÙÙÙÙÙÙ\021\021      88888µµ\033\033\033\033\033\033ðððÙÙÙÙÙÙÙÙ\021\021\021|     88888*\033\033\033\033\033\033\033ððÙÙÙÙÙÙÙ\021\021\021\021\021\021||  88888**\033\033\033\033\033\033ðð\021\021\021\021\021\021\021||½½888ê***\033\033\033\033\033vv\021\021\021\021\021\021\021|½½bêêêê**áánnnnvÛÛÛÛ\021\021\021\021bbbbêêêêááánnnnnÛÛÛÛÛÛÛÛbbbbêêêêêááánnnn\034\034\034\034\034\034\034QQQQ777´´´´´´JJ\031\031\031\031\031\037òòòò\034\034\034\034\034\034\034QQQQ777´´´´´´JJ\031\031\031\031\031\037òòòò\034\034\034\034\034\034\034QQQQQ77´´´´´´JJ\031\031\031\031\031\037òòòò\034\034\034\034\034\034QQQQQÅ´´´´´´JJ\031\031\031\031\031\037òòòò\023\023\023\034\034\034QQQQQÅ´´´´´´JJ\031\031\031\031\037\037\037òòò\023\023\023\023\034VVVVÅ!!´´´´JJ\031\031\031\031\037\037\037òòò\023\023\023\023\023VVVV!!!!!!óó\031\031\031\037\037\037\037\037¹¹\023\023\023\023\023\023VVV!!!!!óóóó\031\037\037\037\037\037¹¹::\020\020\020!!!!!óóóóó\037\037\037\037\037¹¹:::\020\020\020\020MMMMMóóóóó\037\037\037\037\037¹¹::::\020\020\020\020MMMMMMóóóó¿@\037\037\037¹¹kkËË::Æ\020\020\020\020MMMMMEEóó¿¿@@@@@¹kkkkkËËËËÆÆÆ\003\003\003\003MMEEEEE¿¿¿¿@@@@)þþþþþËËËËÆÆÆ\003\003\003\003\003úúúúEE\r¿¿¿@@@))þþþþþþÆÆÆ\003\003\003\003\003\003úúúú\r\r\r¿¿¿@)))þþþþþþÆ\003\003\003\003\003\003Ñúúú\r\r\r\r\rC))))åååååå\036÷÷\003ÑÑÑÑýýý\r\r\r\r\rCCC))ååååååååàà÷÷ÑÑÑÑHHýý\r\r\r\rCCCC)åååååååååààààà÷÷÷ÑHHHHçç\r\r\rCCCCCååååååååààààt÷÷33HHHçççççCCCCÓ&&&&ååååååààtttttµµµHHççççççmmmm&&&&&&åååååttttttµµµµHSçççççmmmm&&&&&&&&¬¬  tttttµµµµµSSSSççmmmm&&&&&&&&&     ttt8µµµµSSSSSSðmmmÙÙÙÙÙ&&&&       8888µµµSSSSSSððmÙÙÙÙÙÙÙÙ&\021      88888µµ\033\033\033\033\033\033ðððÙÙÙÙÙÙÙÙ\021\021\021\021     88888*\033\033\033\033\033\033\033ððÙÙÙÙÙÙÙÙ\021\021\021\021\021|   D8888***\033\033\033\033\033\033ðÙÙ\021\021\021\021\021\021||½DD88*****\033\033\033\033\033v\021\021\021\021\021\021\021|½½bêêêê**áá\033nnnnÛÛÛÛ\021\021\021\021bbbêêêêêáááánnnnÛÛÛÛÛÛÛbbbbêêêêêááánnnnNNN\034\034\034\034Q777777{´´´´ÐÐ¡\031\031\031\031\031\031òòòòNNN\034\034\034\034Q777777{´´´´ÐÐ¡\031\031\031\031\031\031òòòòNN\034\034\034\034\034QQ77777Å´´´´´Ð¡\031\031\031\031\031\031\037òòò\034\034\034\034\034\034\034QQ7777Å´´´´´¡¡\031\031\031\031\031\031\037òòò\023\023\034\034\034\034QQQ7ÅÅ´´´´´¡¡\031\031\031\031\031\037\037òòò\023\023\023\023\034VVVVÅÅÅ´´´´¡¡\031\031\031\031\031\037\037\037òò\023\023\023\023\023VVVVÅÅ!!!!´¡óó\031\031\031\031\037\037\037òò\023\023\023\023\023\023VVVVVÅ!!!!!óóóó\031\031\037\037\037\037¹¹\023\023\023\020\020VV!!!!!óóóóó\037\037\037\037\037\037¹:::\020\020\020\020\020MMMMMMóóóóü\037\037\037\037\037¹ë::::\020\020\020\020\020MMMMMMóóóóó\037\037\037\037\037¹ëëë::::Æ\020\020\020\020\020MMMMMEEóóó¿@@@@@¹kkkkkËËËÆÆÆÆ\020\020\003\003MMMEEEEEõ¿¿@@@@@kkkkkkËËÆÆÆÆÆ\003\003\003\003×EEEEEE¿¿¿@@@@)þþþþþþÆÆÆÆÆ\003\003\003\003\003×úEEE\r\r\r¿¿@@))þþþþþþþÆÆ\003\003\003\003ÑÑ×úú\r\r\r\r\rCC)))þþþþþþþ÷÷ÑÑÑÑÑHýý\r\r\r\r\rCCC))åååååååå÷÷÷÷ÑÑÑHHHý\r\r\r\rCCCC)åååååååàà÷÷÷÷ÑÑHHHHçç\r\rCCCCCååååååàààà÷÷÷÷HHHHHççççCCCCC&&ååååååàttttt÷µHHHHçççççCCCC&&&&&&ååå¬¬ttttttµµµµHHçççççmmmm&&&&&&&&¬¬¬ ttttttµµµµSSSççççmmm&&&&&&&&&¬    ttt8µµµµSSSSSSçmmmÙÙÙÙ&&&&&&      8888µµµSSSSSSððmÙÙÙÙÙÙÙ&&&      88888µµSSS\033\033\033¶ððÙÙÙÙÙÙÙÙÙ\021\021\021    D88888**\033\033\033\033\033¶¶ðÙÙÙÙÙÙÙÙ\021\021\021\021\021|  DDD888***\033\033\033\033\033¶ðÙÙ\021\021\021\021\021\021||DDDD8*****\033\033\033\033\033¶\021\021\021\021\021\021\021½bDêêê****á\033\033\033nnÛÛÛ\021\021\021bbêêêêêáááánnnnÛÛÛÛÛÛÛbbbêêêêêáááánnnNNNNNN777777{{´ÐÐÐÐÐ¡\031\031\031\031\031òòNNNNNNN777777{{´ÐÐÐÐÐ¡\031\031\031\031\031òòNNNNNNN777777{{´´ÐÐÐÐ¡\031\031\031\031\031òòNNNNNN\034Î777777ÅÅ´´´ÐÐ¡¡\031\031\031\031\031\031òòNNNN\034\03477777ÅÅ´´´´¡¡¡\031\031\031\031\031\031\037òò\023\023\023\023\034\034VVV7ÅÅÅ´´´´¡¡¡\031\031\031\031\031\037\037òò\023\023\023\023\023VVVÅÅÅÅ´´´¡¡¡\031\031\031\031\031\037\037\037ò\023\023\023\023\023\023VVVVÅÅÅ!!!¡¡óó\031\031\031\037\037\037\037ò\023\023\023\023\023\023VVVVÅÅ!!!!!óóóóüü\037\037\037\037¹ëë§§§\020\020\020\020\020ææ¥MMMM!óóóóüü\037\037\037\037\037ëëë::::\020\020\020\020\020ææMMMMMóóóóüü\037\037\037\037\037ëëëëë::::\020\020\020\020ææMMMMMEóóóüü@@@@@kkkkkkËËÆÆÆ\020\020\020\020æMMMMEEEEõõõ@@@@@kkkkkkËËÆÆÆÆÆ\020\003\003××EEEEEEõõõ@@@@@þþþþþþþËÆÆÆÆÆ\003\003\003\003×××EEEEEõõ¿@@@)þþþþþþþÆÆÆ\003\003\003ÑÑ×××EEE\r\r\rCC@))þþþþþþþ÷÷÷ÑÑÑÑ×××\r\r\r\r\rCCC))åååååå÷÷÷÷ÑÑÑHHHH\r\r\r\rCCCC)ååååå÷÷÷÷ÑÑHHHHçç\r\rCCCCCååååå%ààà÷÷÷÷ÑHHHHççççCCCCCåå¬¬¬ttttt÷÷÷\005HHHçççççCCCC&&&&&&¬¬¬¬¬ttttttµµ\005HHççççççmmm&&&&&&&&¬¬¬¬ttttttµµµµSSSççççmmm&&&&&&&&&¬¬¬ tttttµµµµµSSSSç~~mmÙÙÙ&&&&&&&     D8888µµµSSSSS¶~~mÙÙÙÙÙÙÙ&&&&    DD88888µSSSSS¶¶¶ðÙÙÙÙÙÙÙÙÙ\021\021\021   DDD8888**\033\033\033\033¶¶¶¶ÙÙÙÙÙÙÙÙÙ\021\021\021\021  DDDDD8****\033\033\033\033¶¶¶ÙÙÙÙ\021\021\021\021\021DDDDD*****\033\033\033\033¶¶\021\021\021\021\021DDDêê****áá\033xx¶ÛÛÛ\021\021\021bêêêê*áááááxxxÛÛÛÛÛÛbbêêêêáááááxxxNNNNNN777{{{{ÐÐÐÐÐÐ¡\031îîîîòNNNNNNN777{{{{ÐÐÐÐÐÐ¡\031îîîîòNNNNNNN77777{{{ÐÐÐÐÐÐ¡\031\031îîîòNNNNNNNÎ77777{{{ÐÐÐÐÐ¡¡\031\031îîîòNNNNNNNÎÎ77777ÅÅÅ´ÐÐÐ¡¡¡\031\031\031îò}}}NNNÎÎÎÎ777ÅÅÅÅ´´Ð¡¡¡¡\031\031\031\031ò}}}}\023VVVÅÅÅÅÅ´´¡¡¡¡\031\031\031\031ò}}}}\023\023VVVÅÅÅÅÅ!!¡¡¡¡ó\031\031\031\037\037\037\037}}}}\023\023§VVVVÅÅÅ¥!!!¡óóóüüü\037\037\037\037ëëë}§§§§\020\020\020VææÅ¥¥M!!óóóóüüü\037\037\037\037ëëëëë§§§§\020\020\020\020æææ¥MMMMMóóóüüüü\037\037\037ëëëëëë§§§\020\020\020\020\020æææMMMMMóóóüüü©©\037\037kkkëëë§::ÆÆ\020\020\020æææMMMEEEõõõõü©©@@kkkkkkkËÆÆÆÆ\020\020\020`×××EEEEEõõõõ©@@@þþþþkkkËÆÆÆÆÆÆ\003\003\003×××EEEEEõõõ@@@@þþþþþþþþÆÆÆ\177\003ÑÑ××××EEEE\rõõC@@)þþþþþþþþ÷÷÷ÑÑÑ××××EE\r\r\rCCCC)þþþþ%%%÷÷÷ÑÑÑ××××\r\r\r\rCCCCCååå%%%%÷÷÷÷÷ÑHHHHHç\r\rCCCCCåå¬¬%%ââ÷÷÷÷÷HHHHHçççCCCCC¬¬¬¬¬ââââ÷÷\005\005HHHHççççCCCC&&&&&¬¬¬¬¬ttttt÷\005\005\005\005HHçççççCCC&&&&&&&&¬¬¬¬¬tttttµµ\005\005\005Hçççç~~~m&&&&&&&&&¬¬¬pptttttµµµµSSSSç~~~m&&&&&&&&&&¬¬ppppD888µµµSSSSS¶~~~ÙÙÙÙÙÙ&&&&¯¯pppDDD8888µSSSS¶¶¶¶~ÙÙÙÙÙÙÙÙÙ&¯¯¯ pDDDD888***\033\033¶¶¶¶¶ÙÙÙÙÙÙÙÙÙ\021\021¯¯DDDDDDD*****\033\033¶¶¶¶ÙÙÙÙ\021\021\021\021DDDDD******\033\033¶¶¶\021\021\021\021DDDD******áxxx¶ÛÛÛÛ\021Rêêê**áááxxxxÛÛÛÛÛÛbêêêêáááááxxxÍÍNNNNN77{{{{YYÐÐÐÐÐ¡îîîîÍÍNNNNN77{{{{YYÐÐÐÐÐ¡îîîîÍÍNNNNN77{{{{YÐÐÐÐÐ¡¡îîîîÍNNNNNN7777{{{{ÐÐÐÐÐ¡¡îîîîNNNNNNNÎÎ7777{{{{ÐÐÐÐ¡¡¡îîîî}}NNNNNÎÎÎ$777ÅÅÅÅÐÐÐ¡¡¡¡\031îî}}}}NNÎÎÎÎÎVVÅÅÅÅÅÅ¡¡¡¡¡¡\031\031î}}}}}}ÎÎÎÎÎVVÅÅÅÅÅ¥¡¡¡¡¡¡üü\031}}}}}}§VVVVÅÅÅ¥¥¥¡¡¡¡üüüüü}}}}}§§§§\020VVææÅ¥¥¥¥9¡¡óóüüüüü\037\037ëëëë§§§§§\f\020\020ææææ¥¥¥¥99óóóüüüü©©\037ëëëëë§§§§\f\f\020\020ææææ¥¥M999óõüüüü©©©ëëëëëë§§§\f\f\020\020\020æææ`MMMEEõõõõü©©©©kkkkkkk§§ÆÆ\f\020\020æ``×××EEEEõõõõ©©©©kkkkkkkkÆÆÆÆÆÆ``×××××EEEEõõõõ©©©þþþþþþþþ%ÆÆÆÆ\177\177\177Ñ×××××EEEõõõõöööþþþþþþþþ%%%Æ\177\177\177ÑÑ×××××\027\027\r\r6CCööþþþþþ%%%%%÷÷\177\177ÑÑ××××\027\027\r\r6CCCCþ%%%%%%÷÷÷÷÷ÑÑHHHHç\r\r6CCCC¬¬%%%%â÷÷÷÷\005\005HHHHççç6CCCC¬¬¬¬%ââââ÷÷\005\005\005HHHççççCCCC&&&¬¬¬¬¬¬âââââ\005\005\005\005\005H¼ççç~~CC&&&&&&&¬¬¬¬¬¬pâââââ\005\005\005\005\005¼¼¼ç~~~~&&&&&&&&&¬¬¬pppptttµ\005\005\005\005S¼¼¼~~~~&&&&&&&&&&\t¬pppppD88µµµSSSS¶¶~~~ÙÙÙÙÙ&&&&&¯¯ppppDDD888**SSS¶¶¶¶~ÙÙÙÙÙÙÙÙ&¯¯¯¯ppDDDDDD****\033¶¶¶¶¶¶ÙÙÙÙÙÙÙÙÙ¯¯¯¯¯pDDDDDD*****\033¶¶¶¶¶ÙÙÙÙÙÙ\021¯¯DDDDD******x¶¶¶¶Ù\021\021\021\021DDDDD*****áxxx¶ÛÛÛRRR***áááxxxx\"\"ÛÛÛÛRRRêêáááááxxxÍÍÍNNNNíí{{{{YYYÐÐÐÐ\026îîîîîÍÍÍNNNNí7{{{{YYYÐÐÐÐ\026îîîîîÍÍÍNNNNí7{{{{YYYÐÐÐÐ\026îîîîîÍÍÍNNNN7{{{{{YYÐÐÐ\026\026îîîîÍÍÍNNNNÎ$77{{{{YYÐÐÐ¡\026\026îîîÍÍNNNNNÎÎÎ$$77{{{{YÐÐÐ¡¡¡¡îîî}}}}NNNÎÎÎÎ$$7ÅÅÅÅÅÐ¡¡¡¡¡¡îî}}}}}}ÎÎÎÎÎÎVÅÅÅÅÅÅ¥¡¡¡¡¡¡üî}}}}}}§ÎÎÎÎÎVVÅÅÅÅ¥¥¡¡¡¡¡üüüü}}}}}}§§§ÎÎVVææÅ¥¥¥¥99¡¡¡üüüüü}}}}}§§§§§\f\020ææææ¥¥¥¥999óóüüüü©©©ëëëëë§§§§§\f\fæææææ¥¥¥9999õüüüü©©©ëëëëëë§§§\f\f\f\fæææ``¥¥9999õõõõ©©©©kkkkkëë§§\f\f\f\fæ`````×999Eõõõõ©©©©11kkkkkk§Æ\f\f\f\f````×××\027\027\027\027õõõõ©©©1111þþþk%%ÆÆ\177\177\177\177×××××\027\027\027\027õõõööö1111þþþþ%%%%\177\177\177\177\177\177×××××\027\027\027\02766ööö1111þþþþ%%%%%\177\177\177\177\177×××××\027\027\02766666öþ%%%%%%÷\177\177\177\177\177\005××××\027\02766666C¬¬%%%%ââ÷÷\177\177\005\005\005HH¼¼¼66666¬¬¬¬%%ââââ÷\005\005\005\005\005H¼¼¼¼6666¬¬¬¬¬¬âââââ\005\005\005\005\005\005¼¼¼¼~~66&&&&&&\t¬¬¬¬¬pâââââ\013\005\005\005\005¼¼¼¼~~~~&&&&&&&&\t\t¬¬ppppâââ\013\013\005\005\005¼¼¼¼~~~~&&&&&&&&&\t\t¬pppppDD\013\013\013\005\b\b¼¼¼¶~~~AAAAA&&&&&¯¯pppppDDDD\013\b\b\b\b¶¶¶¶~~ÙÙÙÙÙAAA&¯¯¯¯pppDDDDD****\b¶¶¶¶¶¶ÙÙÙÙÙÙÙÙA¯¯¯¯¯pDDDDDD******¶¶¶¶¶ÙÙÙÙÙ¯¯¯¯¯DDDDDD*****x¶¶¶¶\"\"Ù¯¯DDDDD******xxx¶\"\"\"\"\"ÛÛRRR****ááxxxx\"\"\"\"\"\"\"\"ÛÛRRRR*áááááxxxÍÍÍÍNNNííí{{{YYYYÏÐ\026\026\026îîîîÍÍÍÍNNNííí{{{YYYYÏÐ\026\026\026îîîîÍÍÍÍNNNííí{{{{YYYÏÐ\026\026\026îîîîÍÍÍÍNNNííí{{{{YYYÏÐ\026\026\026îîîîÍÍÍÍNNNNí{{{{YYYÐ\026\026\026\026îîîîÍÍÍÍNNNÎÎÎ$$${{{YYY\026\026\026\026\026\026îî}}---NNÎÎÎÎ$$${{{YY\026\026\026\026\026\026îî}}}}--NÎÎÎÎ$$$ÅÅÅÅÅ¥\026\026\026\026\026\026\026î}}}}}}-ÎÎÎÎÎ$$ÅÅÅÅ¥¥¥¡\026\026\026\026\026ü}}}}}}§§ÎÎÎÎæææÅÅ¥¥¥¥9¡¡\026\026üüü}}}}}}§§§§\f\fææææ¥¥¥¥9999¡üüüüü©©ëëëë}§§§§§\f\fæææææ¥¥¥99999õüüü©©©ëëë§§§\f\f\f\fææ```¥¥99999õõõ©©©©ë§§§\f\f\f\f\f`````¥9999õõõõõ©©©kk²²\f\f\f\f\f`````××\027\027\027\027õõõõö©©111111kk²²```××××\027\027\027\027õõõööö111111þþ%%%\177\177\177\177××××\027\027\027\027\027õ6ööö1111111þ%%%%%\177\177\177\177\177\177××××\027\027\027\0276666ö111%%%%%%%\177\177\177\177\177×××××\027\027\02766666¬¬%%%%ââ\177\177\177\177\005\005\005×¼¼¼¼66666¬¬¬¬%%ââââ\177\177\005\005\005\005¼¼¼¼¼6666¬¬¬¬¬âââââ\013\005\005\005\005\005¼¼¼¼¼~66&&&&&\t\t¬¬¬¬ââââââ\013\005\005\005\005¼¼¼¼¼~~~&&&&&&&\t\t\t\t¬¬pppâââ\013\013\013\005\005¼¼¼¼¼~~~AA&&&&&&\t\t\t\tppppppâ\013\013\013\013\b\b\b¼¼¼~~~AAAAAAA&&¯¯\tppppppDDD\013\013\b\b\b\b¶¶¶~~AAAAAAAAA¯¯¯¯ppppDDDDD**\b\b¶¶¶¶¶¶ÙÙÙAAAAAA¯¯¯¯¯ppDDDDDD*****¶¶¶¶¶\"\"\"ÙÙÙÙAA¯¯¯¯¯DDDDDD*****x¶¶¶¶\"\"\"\"\"\"\"\"\"\"¯¯¯DDDD******xxx¶\"\"\"\"\"\"\"\"\"\"\"¯RRRRR****áxxxx\"\"\"\"\"\"\"\"\"\"\"ÛÛRRRRR*ááááxxxÍÍÍ>>>líííª{{YYYYÏÏÏ\026\026îîîîÍÍÍ>>>líííª{{YYYYÏÏÏ\026\026îîîîÍÍÍ>>>Níííª{{{YYYÏÏÏ\026\026\026îîîÍÍÍÍ>>Nííí{{{YYYÏÏ\026\026\026\026îîîÍÍÍÍ>NNNíí{{{YYYÏÏ\026\026\026\026îîîÍÍÍÍÍNNN$$${{YYYÏ\026\026\026\026\026GîîÍÍ----NÎÎÎ$$$${{YYÏ\026\026\026\026\026Gî}------ÎÎÎÎ$$$ÅÞÞ\026\026\026\026\026\026GG}}}}---ÎÎÎÎÎ$$$ÅÅ¥¥ÞÞ\026\026\026\026\026GG}}}}}}--ÎÎÎÎ$$$ÅÅ¥¥¥¥9\026\026\026\026\026GG}}}}}}§§§ÎÎÎÎæææ¥¥¥¥¥999\026\026\026üüü©}}}}§§§§\f\f\fææææ¥¥¥¥9999jüüü©©©§§§§§\f\f\fææ```¥¥99999õõõü©©©²²§\f\f\f\f\f`````¥99999õõõõ©©©²²²²\f\f\f\f``````×99\027\027õõõõöö©1111111²²²````×××\027\027\027\027õõõööö11111111%%\177\177\177××××\027\027\027\027\027õõööö11111111%%%%\177\177\177\177\177××××\027\027\027\027\027666ö11111111%%%%%%\177\177\177\177\177\177××××\027\027\02766666¬%%%%%%â\177\177\177\177\177\005\005××¼¼\02766666¬¬%%%ââââ\177\177\005\005\005\005\005¼¼¼¼6666\t¬¬¬¬%âââââ\013\013\005\005\005\005¼¼¼¼¼666&&&\t\t\t¬¬¬ââââââ\013\013\005\005\005¼¼¼¼¼~~~&&&&&&&\t\t\t\t\t¬ppââââ\013\013\013\005\005\b¼¼¼¼~~~AAAAA&&&\t\t\t\tppppppâ\013\013\013\013\b\b\b¼¼¼~~~AAAAAAAA&\t\t\tppppppDD\013\013\013\b\b\b\b¶¶¶~~AAAAAAAAA¯¯¯¯pppppDDDD\013\b\b\b\b¶¶¶¶¶AAAAAAAAA¯¯¯¯¯¯ppDDDDD***\b\b¶¶¶¶¶\"\"\"\"AAAAA¯¯¯¯¯¯DDDDDD*****x¶¶¶¶\"\"\"\"\"\"\"\"\"A¯¯¯¯DDRR******xxx¶\"\"\"\"\"\"\"\"\"\"\"¯¯RRRRR****áxxxx\"\"\"\"\"\"\"\"\"\"\"\"RRRRR*ááááxxxÍÍ>>>>lllíííªªª{YYYÏÏÏÏîîÍÍ>>>>llíííªªª{YYYÏÏÏÏîîÍÍÍ>>>>líííªª{{YYYÏÏÏÏ\026îîÍÍÍ>>>>lííííª{{YYYÏÏÏÏ\026îîÍÍÍÍ>>>líííí{YYYÏÏÏ\026\026\026GGîÍÍÍÍÍ>>N$ííYYYÏÏÏ\026\026\026GGîÍÍÍ----ÇÇÎ$$$${YYÏÏ\026\026\026\026GGG-------ÇÇÎÎ$$$ÞÞÞÞ\026\026\026\026GGG-----ÇÎÎ$$$$ÞÞÞÞ\026\026\026\026GGG----ÇÎÎÎ$$$\017\017\017¥¥¥ÞÞ\026\026\026\026GGGG}}}}}}--§ÎÎÎÎ$$\017\017¥¥¥¥999\026\026\026GGGG}}}}§§§§\f\fººææ`¥¥¥¥9999jjjjG©©²²§§§\f\f\f\f````¥¥¥9999jjjj©©©²²²²\f\f\f\f`````¥¥99999jõõ©©©²²²²\f\f\f\f\f`````ÿ99999õõööö²²²²````ÿÿ\027\027\027\027õööö11111111²²²B``××ÿÿ\027\027\027\027ööö111111111%%\177\177\177××××\027\027\027\027\027666ö11111111e%%%%\177\177\177\177\177××××\027\027\027\027666611ee%%%%%â\177\177\177\177\177\005\005××\027\027\027\0276666¬¬%%%%ââ\177\177\177\177\005\005\005\005¼¼¼¼6666\t\t¬¬¬%âââââ\013\013\013\005\005\005¼¼¼¼¼666\t\t\t\t\t¬¬âââââ\013\013\013\013\005\005¼¼¼¼¼~~~AAA&&&\t\t\t\t\t\t¬ppââââ\013\013\013\013\b\b¼¼¼¼~~~AAAAAAA\t\t\t\t\t\tppppââ\013\013\013\013\b\b\b¼¼¼~~~AAAAAAAAA\t\t\t\tppppppD\013\013\013\b\b\b\b\b¼¶~~AAAAAAAAA¯¯¯¯ppppppDD\013\013\b\b\b\b\b¶¶¶¶AAAAAAAAA¯¯¯¯¯¯pppDDDD**\b\b\b\b¶¶¶¶\"\"\"AAAAAA¯¯¯¯¯¯¯pDDDDD*****\007¶¶¶¶\"\"\"\"\"\"\"\"AA¯¯¯¯¯RRRRR*****xxx¶\"\"\"\"\"\"\"\"\"\"\"¯¯¯RRRRRR****xxxx\"\"\"\"\"\"\"\"\"\"\"\"¯RRRRR**áááxxxÍ>>>>>llllíííªªªªYYYÏÏÏÏÍ>>>>>llllíííªªªªYYYÏÏÏÏÍÍ>>>>llllíííªªªªYYYÏÏÏÏÏÍÍ>>>>>lllíííªªªªYYYÏÏÏÏÏÍÍÍ>>>>llííííªªYYYÏÏÏÏÏÍÍÍÍ>>>>líííYYÏÏÏÏ\026\026GGGGÍÍÍÍ-->ÇÇÇ$$$YYÏÏÏÏ\026\026GGGG-----ÇÇÇÇ$$$$ÞÞÞÞ\026\026\026GGGG-----ÇÇÇ$$$$\017ÞÞÞÞ\026\026\026GGGGG---ÇÇÇÎ$$$\017\017\017\017\017ÞÞÞÞ\026\026\026GGGGG---ÇÇÎº$$\017\017\017\017\017¥ÞÞÞ\026\026\026GGGGG-§ÇÇººººº\017\017\017¥¥¥999jjjjGGG²²²²§\f\f\fººº``¥¥¥9999jjjjjG©²²²²\f\f\f\fº`````¥99999jjjj©©²²²²²\f\f\f\f``ÿÿ999jöö²²²²ÿÿÿÿ\027öö11111²²²²Bÿÿÿÿ\027\027öö11111111e²²BBBÿÿÿÿ\027\027\027\0276ö1111111eee%==\177\177\177\177×ÿÿÿ\027\027\027\0276666111111eeeee====\177\177\177\177\177\177××ÿÿ\027\027\0276666eeeee=====\177\177\177\177\005\005\005\005¼¼¼¼6666\t\t\t\t¬¬===âââ\013\013\013\013\005\005¼¼¼¼¼666\t\t\t\t\t\t¬=ââââ\013\013\013\013\013\005\005¼¼¼¼¼~~AAAAA\t\t\t\t\t\t\t\tppââââ\013\013\013\013\b\b\b¼¼¼¼~~AAAAAAA\t\t\t\t\t\tppppââ\013\013\013\013\b\b\b\b¼¼¼~~AAAAAAAA\t\t\t\t\tpppppp\013\013\013\013\b\b\b\b\b¼¼~~AAAAAAAAA¯¯¯¯¯pppppDD\013\013\b\b\b\b\b\b¶¶~AAAAAAAAA¯¯¯¯¯¯ppppDDD\b\b\b\b\b\007¶¶¶AAAAAAA¯¯¯¯¯¯ppDDDDD**\007\007\007\007¶¶¶\"\"\"\"\"\"\"\"AA¯¯¯¯¯¯RRRRRR****\007\007xx¶\"\"\"\"\"\"\"\"\"\"?¯¯¯¯RRRRRR****xxxx\"\"\"\"\"\"\"\"\"\"\"?¯¯RRRRRR*áááxxxdd>>>>llllííªªªª44YÏÏÏÏÏdd>>>>llllííªªªª44YÏÏÏÏÏdd>>>>llllíííªªªª44YÏÏÏÏÏÍ>>>>>>lllíííªªªª44YÏÏÏÏÏÍÍ>>>>>llllíííªªªª44YÏÏÏÏÏÍÍÍ>>>>>llííííªªYYÏÏÏÏÏÍÍÍÍ>>>>ÇÇÇ$$ííYÏÏÏÏÏ\026GGGGG----ÇÇÇÇ$$$$ÞÞÞÞÏ\026\026GGGGG----ÇÇÇÇ$$$\017ÞÞÞÞÞ\026\026GGGGG---ÇÇÇÇ$$$$\017\017\017\017ÞÞÞÞÞ\026\026GGGGG---ÇÇÇÇ$$\017\017\017\017\017\017ÞÞÞÞÞjGGGGG--ÇÇÇºººº\017\017\017\017\017+ÞÞÞjjjjGGGyyXXX²²\fºººººº\017\017¥¥999jjjjjGG²²²²²\f\fººº¥¥999jjjjjjj²²²²²\f\f\fºÿÿ99jjjö²²²²²Bÿÿÿÿö]²²²²²Bÿÿÿÿÿ\027ö]]]]111ee²²²BBBÿÿÿÿÿ\027\027ö]]]]11eeeee==BBBBÿÿÿÿÿÿ\027\0276ss]]1eeeeee====BBBBBÿÿÿÿÿ\027\027\027666ssssseeeeee======\177\177\177\013\005\005ÿÿ¼¼\0276666ssssss\teeeee=====â\013\013\013\013\005\005¼¼¼¼¼666sssss\t\t\t\t\t\t\t====ââ\013\013\013\013\013\013\b¼¼¼¼¼~6AAAss\t\t\t\t\t\t\t\tÉ==âââ\013\013\013\013\013\b\b¼¼¼¼~~AAAAAAA\t\t\t\t\t\t\tpppââ\013\013\013\013\013\b\b\b¼¼¼~~AAAAAAAA\t\t\t\t\t\tppppp\013\013\013\013\b\b\b\b\b\b¼~~AAAAAAAAA¯¯¯¯¯pppppp\013\b\b\b\b\b\b¶¶~AAAAAA¯¯¯¯¯¯pppppD\b\b\b\007\007\007¶¶AAAAA¯¯¯¯¯¯\030ppDDDÃÃ\007\007\007\007\007\007¶¶\"\"\"\"AA?¯¯¯¯¯¯¯³RRRRRÃÃ*\007\007\007\007x¶\"\"\"\"\"\"\"\"\"???¯¯¯¯RRRRRRÃ**\007\007xxx\"\"\"\"\"\"\"\"\"\"\"??¯¯RRRRRR**ááxxxdddd>>llllªªªª4444ÏÏÏÏdddd>>llllªªªª4444ÏÏÏÏdddd>>llllªªªª4444ÏÏÏÏdddd>>>lllªªªª4444ÏÏÏÏddd>>>>lllªªªª444ÏÏÏÏÏdd>>>>>llllíªªª444ÏÏÏÏÏÏÍÍÍ>>>>>lllíªª44ÏÏÏÏÏÏ-->>ÇÇÇÇ$$$ÞÞÞÞÏÏÏGGGGG---ÇÇÇÇÇ$$\017ÞÞÞÞÞÞÞGGGGG--ÇÇÇÇÇ$$$\017\017\017\017\017ÞÞÞÞÞÞGGGGG--ÇÇÇÇ$$$\017\017\017\017\017+ÞÞÞÞÞGGGGGXX-ÇÇÇÇººº\017\017\017\017\017+++ÞÞjjjGGGyyXXXXXXÇÇººººº\017\017\017\017z+++jjjjjGGyyyyXXXX²²²\fººººº\017zzz9jjjjjjjyyyy²²²²²²\fºººÿÿ99jjjjj²²²²²ºÿÿÿÿjj]]]]²²²²²BBÿÿÿÿÿ]]]]]]ee²²²²BBBBÿÿÿÿÿ®]]]]]]eeeee²=BBBBBÿÿÿÿÿÿ®]]]]]]eeeee====BBBBBÿÿÿÿÿÿ\027®®ssssseeeeeee=====BBBBÄÿÿÿÿÿ066ssssssseeeee======B\013\013\013\013\005ÿ¼¼¼66sssssss\t\teee======\013\013\013\013\013\013\b¼¼¼¼¼6sssssss\t\t\t\t\t\tÉ====â\013\013\013\013\013\b\b\b¼¼¼¼~AAAAAA\t\t\t\t\t\t\tÉÉÉÉÉâ\013\013\013\013\013\b\b\b\b¼¼~~AAAAAA\t\t\t\t\t\t\030\030ppÌ\013\013\b\b\b\b\b¼~~AAAAPP¯¯\t\030\030\030\030\030pÌ\b\b\b\b\b\007¶~AAAP¯¯¯¯\030\030\030\030\030ÌÌ\b\b\007\007\007\007\007¶AAA¯¯¯¯¯\030\030\030\030\030ÃÃÃÃ\007\007\007\007\007\007¶A??¯¯¯¯¯³\030\030RRRÃÃÃ\007\007\007\007\007\007¶»»»»\"\"\"\"\"????¯¯¯³³³RRRRÃÃÃ\007\007\007\007\007x\"\"\"\"\"\"\"\"\"\"????¯³RRRRRÃÃÃ*\007xxxddddddllllªªª4444ZZZZZddddd>llllªªª4444ZZZZZddddd>llllªªª4444ZZZZZddddd>llllªªª4444ZZZZZddddd>>lllªªª44444ZZZZddddTT>llllªªª4444ÏÏÏÏÏdddTTT>>lllªª//44ÏÏÏÏÏaTTT>>ÇÇÇÇ///ÞÞÞÏÏÏa---ÇÇÇÇÇ$//ÞÞÞÞÞÞaGGGG-- ÇÇÇÇÇ$$\017\017\017\017\017ÞÞÞÞÞÞGGGGG-  ÇÇÇÇK$\017\017\017\017\017+++ÞÞÞÞGGGGXXX  ÇÇºººº\017\017\017\017\017+++++jjGGGyyXXXXXXX   ººººº\017\017\017zz+++jjjjjGyyyyXXXXX\016\016\016ºººººº\017\017zzz++jjjjjjyyyyyyXX\016\016\016\016\016ººººzzzzzjjjjjjyyyyyy²²²²²²\016ºººÿÿÿjjj]]]]]]²²²²²²BBÿÿÿÿ®]]]]]]]e²²²²²BBBBÿÿÿÿÿÿ®®]]]]]]]eeee²²BBBBBÿÿÿÿÿÿ®®]]]]]]eeeeee===BBBBBBBÿÿÿÿÿ0®®sssss]eeeeee=====BBBBÄÿÿÿÿ00®®ssssssseeeee======ÁÄÄÄÄÄÿ000sssssss(eeee======hÄÄÄ\013\013\b\b¼sssssss(((\teÉÉÉ===h\013\013\013\013\b\b\b¼¼ìììssss((\t\t\t\tÉÉÉÉÉÉ\013\b\b\b\b¼¼PPPPP\t\t\t\t\030ÉÉÉÉÌ\b\b\b\b\b¼¼~PPPPP\t\t\030\030\030\030\030ÌÌ\b\b\b\007\007\007~PPPP¯¯¯\030\030\030\030\030ÌÌ\007\007\007\007\007\007\007PPP¯¯¯¯\030\030\030\030\030ÌÃÃ\007\007\007\007\007\007\007????¯¯¯³³\030\030\030ÃÃÃÃÃÃ\007\007\007\007\007\007»»»»»»??????¯¯³³³³RRÃÃÃÃÃ\007\007\007\007\002»»»»»»»\"\"??????¯³³³³RRRÃÃÃÃÃ\002\002\002\002ddddddllllªªª44444ZZZZZddddddTlllªªª44444ZZZZZddddddTlllªª44444ZZZZZdddddTTlllªª44444ZZZZZadddddTTlllªª44444ZZZZaaddddTTTTllªª/4444ZZZZaaddddTTTTlllª///444ZZZZaaddTTTTTTTll/////4ÞÞÏaaaaûûTTTTT  ÇÇÇ/////ÞÞÞÞÞaaaûû-   ÇÇÇ\017\017\017///++ÞÞÞaaGGGûûX    ÇÇKK\017\017\017\017\017\017+++++ÞUGGGûûûXXXX     KKK\017\017\017\017\017+++++UUGGGûûûXXXXXX    ººººº\017\017\017zz++++UUjjGyyyyXXXXXX\016  ºººººß\017\017zzzz++UjjjjyyyyyyXXX\016\016\016\016ºººººßßzzzzzUjjjjjyyyyyyy\016\016\016\016\016\016\016ºººº''zzzjjjjyyyyyyy²²\016\016\016\016\016BB''''ÿ®®]]]]]]]]ÝÝÝ²\016\016BBBB'''ÿÿÿ®®]]]]]]]]eeÝÝÝÝBBBBB'''ÿÿÿ®®®]]]]]]]eeeeÝÝ==ÁÁBBBBB'ÿÿÿ000®®®ssss]]]eeeee====ÁÁÁBBÄÄÿÿ0000®®ssssssseeeee=====hÁÁÄÄÄÄ0000sssssss((eeee====hhÄÄÄÄÄÄ000sssssss((((eeÉÉ==hhhÄÄÄÄÄ\b\bììììsss(((((ÉÉÉÉÉÉh\b\b\bìììììPPPP(((\tÉÉÉÉÉÉÌ\b\b\b\b\007PPPPPPP\t\030\030\030\030\030ÌÌÌ\b\007\007\007\007\007PPPPPP¯\030\030\030\030\030\030ÌÌ\007\007\007\007\007\007PPPPP¯¯\030\030\030\030\030\030ÌÌÃ\007\007\007\007\007\007PP???¯¯³³\030\030\030ÌÃÃÃÃÃ\007\007\007\007\007\007»»»»»»??????³³³³³\030ÃÃÃÃÃÃ\007\007\007\002\002»»»»»»»»»???????³³³³³RÃÃÃÃÃÃ\002\002\002\002ddddddTôôôääª44444ZZZZaddddddTôôôääª44444ZZZZaddddddTôôôääª44444ZZZZaaddddddTôôôääª44444ZZZZaadddddTTôôôääª/4444ZZZZaaadddddTTTôôôää/4444ZZZZaaaddddTTTTôôôää///444ZZZaaaadddTTTTTTôôää////44ZZZaaaaûûTTTTTTTôô //////+ÞÞaaaaaaûûûûTTTTT    //////++++aaaaaûûûûøø      KKK\017\017///++++++aaaGûûûûXXXøø     KKKK\017\017\017\017+++++UUUGGûûûûXXXXøø    KKKKß\017\017\017zz+++UUUUGyyyûXXXXXø    KKºßßß\017zzzz++UUUUjyyyyyXXXX\016\016\016\016ººººßßßßzzzzzzUUUjjyyyyyyyX\016\016\016\016\016\016ººººßßß'zzzzzUUUjjyyyyyyyy\016\016\016\016\016\016\016ººº''''zz®®®]]]]]]yyÝÝÝ\016\016\016\016ÁBBB''''''®®®]]]]]]]]ÝÝÝÝÝÝÝÁÁBBB''''''0®®®®]]]]]]]]eeÝÝÝÝÝÁÁÁÁBBB''''000®®®sss]]]]]eeeeÝ===ÁÁÁÁÁÄÄ''00000®®sssssss(eeeee===hhÁÁÄÄÄÄ00000sssssss(((eee===hhhhÄÄÄÄÄ0000sssssss((((eeÉÉÉ=hhhÄÄÄÄÄÄ00ìììììss(((((ÉÉÉÉÉÉhh\b\b\bììììììPP(((((ÉÉÉÉÉÉÌ\b\b\007PPPPPP((\030\030ÉÉÉÌÌÌ\007\007\007\007\007PPPPPPP\030\030\030\030\030ÌÌÌÌ\007\007\007\007\007\007PPPPPPP\030\030\030\030\030ÌÌÌÌ\007\007\007\007\007\007PPPP??³³\030\030\030\030ÌÌÃÃÃÃ\007\007\007\007\007\007»»»»»»»??????³³³³³\030ÃÃÃÃÃÃ\007\007\002\002\002»»»»»»»»»???????³³³³³ÃÃÃÃÃÃÃ\002\002\002\002ÜÜÜÜddTôôôôääää44444ZZZaaÜÜÜÜddTôôôôääää44444ZZZaaÜÜÜdddTôôôôäää444444ZZaaaÜÜÜdddTôôôôäää444444ZZaaaÜÜÜddTTôôôôäää/44444ZZaaaÜÜdddTTTôôôôäää//4444ZZaaaÜÜddTTTTôôôôäää///444ZZaaaadddTTTTTTôôôää////44ZZaaaaûûTTTTTTTôôôôää//////ZZaaaaaûûûûTTTTTT   ää//////+++aaaaaûûûûûøøøT     KKKä/////+++++aaaaûûûûûøøøø     KKKKK\017///++++UUUaaûûûûûXøøøø    KKKKßß\017\017zz+++UUUUUûûûûûXXøøøø   KKKKßßßzzzzz+UUUUUyyyyyXXXøø\016\016  KKKßßßßßzzzzzUUUUUyyyyyyyX\016\016\016\016\016\016ºººßßßßßzzzzzUUUUUyyyyyyyy\016\016\016\016\016\016\016ººßßßßß''zzzzUU®®yyyyyyyyÝÝ\016\016\016\016\016ÁÁÁßßß'''''''®®®®]]]]]]]]ÝÝÝÝÝÝÝÁÁÁÁB''''''00®®®]]]]]]]]]ÝÝÝÝÝÝÁÁÁÁÁÁB'''''000®®]]]]]]]]eeeÝÝÝÝÁÁÁÁÁÁÄÄ'''0000®®sssssss(eeeeeÝ=hhÁÁÁÁÄÄÄ'00000sssssss(((eee===hhhhÄÄÄÄÄ0000ìììssss(((((eÉÉÉhhhhÄÄÄÄÄÄ000ììììììs((((((ÉÉÉÉÉhhhÄÄÄÄÄÄ0ììììììì((((((ÉÉÉÉÉÉhh\b\007ììììììPPPP(((ÉÉÉÉÉÉÌÌ\007\007\007\007PPPPPPPP\030\030\030\030\030ÌÌÌÌ\007\007\007\007\007PPPPPPP\030\030\030\030\030ÌÌÌÌ\007\007\007\007\007PPPPPP³³\030\030\030\030ÌÌÌÃÃ\007\007\007\007\002»»»»»»»P?????³³³³³\030ÌÃÃÃÃÃ\007\002\002\002\002»»»»»»»»»???????³³³³³³ÃÃÃÃÃÃ\002\002\002\002ÜÜÜÜÜddôôôôôäääää44444ZZaaÜÜÜÜÜddôôôôôäääää44444ZZaaÜÜÜÜÜdTôôôôôäääää44444ZZaaÜÜÜÜÜdTôôôôôäääää44444ZZaaÜÜÜÜÜdTôôôôôäääää/4444ZZaaÜÜÜÜÜTTTôôôôääää/4444ZZaaÜÜÜÜdTTTôôôôôääää///44ZZaaaÜÜÜÜTTTTTôôôôääää////44ZaaaÜÜÜTTTTTTôôôôääää/////4ZaaaaûûûûTTTTTTôôôäää//////+aaaaaûûûûûTTTTT    Käää/////+++aaaaaûûûûûûøøøø    KKKKK/////++++Uaaaûûûûûûøøøø    KKKKKß//zzz++UUUUUûûûûûûøøøøø   KKKKKßßßzzzz+UUUUUûûûûûûøøøøøø  KKKKßßßßzzzzzUUUUUyyyyyyyøøø\016\016\016\016KKKßßßßßzzzzzUUUUUyyyyyyyy\016\016\016\016\016\016\016\016KßßßßßßzzzzzUUUUyyyyyyyyÝ\016\016\016\016\016\016\016Áßßßßß'''''zUU®®]]]]]yyyÝÝÝÝÝÝ\016\016ÁÁÁÁßß''''''0®®®]]]]]]]]]ÝÝÝÝÝÝÝÁÁÁÁÁÁ'''''000®®]]]]]]]]]eÝÝÝÝÝÝÁÁÁÁÁÁÄ'''00000®ssssss]](eeÝÝÝÝhhÁÁÁÁÄÄÄ''00000sssssss((((eeÝ=hhhhhÄÄÄÄÄ00000ììììsss(((((eÉÉÉhhhhhÄÄÄÄÄ000ììììììs((((((ÉÉÉÉhhhhÄÄÄÄÄÄ00ììììììì((((((ÉÉÉÉÉÉhhhÄÄÄÄÄÄìììììììPP(((((ÉÉÉÉÉÌÌ\007\007\007PPPPPPP((\030\030ÉÉÌÌÌÌ\007\007\007\007\007PPPPPPPP\030\030\030\030\030ÌÌÌÌ\007\007\007\007\007PPPPPPP³³\030\030\030\030ÌÌÌÃÃ\007\007\007\002\002»»»»»»»PPPP???³³³³\030ÌÌÃÃÃÃÃ\002\002\002\002\002»»»»»»»»»???????³³³³³³ÃÃÃÃÃÃ\002\002\002\002".getBytes("ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        double r, g, b, l, m, s;
        int idx = 0;
        for (int ri = 0; ri < 32; ri++) {
            r = ri * ri * 0.0010405827263267429; // 1.0 / 31.0 / 31.0
            for (int gi = 0; gi < 32; gi++) {
                g = gi * gi * 0.0010405827263267429; // 1.0 / 31.0 / 31.0
                for (int bi = 0; bi < 32; bi++) {
                    b = bi * bi * 0.0010405827263267429; // 1.0 / 31.0 / 31.0

                    l = Math.pow(0.313921 * r + 0.639468 * g + 0.0465970 * b, 0.43);
                    m = Math.pow(0.151693 * r + 0.748209 * g + 0.1000044 * b, 0.43);
                    s = Math.pow(0.017753 * r + 0.109468 * g + 0.8729690 * b, 0.43);

                    IPT[0][idx] = 0.4000 * l + 0.4000 * m + 0.2000 * s;
                    IPT[1][idx] = 4.4550 * l - 4.8510 * m + 0.3960 * s;
                    IPT[2][idx] = 0.8056 * l + 0.3572 * m - 1.1628 * s;

                    idx++;
                }
            }
        }
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

    public int iptToRgb(double i, double p, double t, double a) {
        final double lPrime = i + 0.06503950 * p + 0.15391950 * t;
        final double mPrime = i - 0.07591241 * p + 0.09991275 * t;
        final double sPrime = i + 0.02174116 * p - 0.50766750 * t;
        final double l = Math.copySign(Math.pow(Math.abs(lPrime), 2.3256), lPrime);
        final double m = Math.copySign(Math.pow(Math.abs(mPrime), 2.3256), mPrime);
        final double s = Math.copySign(Math.pow(Math.abs(sPrime), 2.3256), sPrime);
        final int r = (int)(Math.sqrt(Math.min(Math.max(5.432622 * l - 4.679100 * m + 0.246257 * s, 0.0), 1.0)) * 255.99999);
        final int g = (int)(Math.sqrt(Math.min(Math.max(-1.10517 * l + 2.311198 * m - 0.205880 * s, 0.0), 1.0)) * 255.99999);
        final int b = (int)(Math.sqrt(Math.min(Math.max(0.028104 * l - 0.194660 * m + 1.166325 * s, 0.0), 1.0)) * 255.99999);

//        final double l = i + 0.097569 * p + 0.205226 * t;
//        final double m = i - 0.113880 * p + 0.133217 * t;
//        final double s = i + 0.032615 * p - 0.676890 * t;
//        final int r = Math.min(Math.max((int) ((5.432622 * l - 4.679100 * m + 0.246257 * s) * 256.0), 0), 255);
//        final int g = Math.min(Math.max((int) ((-1.10517 * l + 2.311198 * m - 0.205880 * s) * 256.0), 0), 255);
//        final int b = Math.min(Math.max((int) ((0.028104 * l - 0.194660 * m + 1.166325 * s) * 256.0), 0), 255);
        return r << 24 | g << 16 | b << 8 | (int)(a * 255.999);
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
     * it, but it is strongly encouraged that you instead call {@link #exact(int[])} when you want to change the colors
     * available, and treat this array as read-only. If you don't call exact() or {@link #analyze(Pixmap)} to set the
     * values in this, then the reductions that use gamma correction ({@link #reduceKnoll(Pixmap)},
     * {@link #reduceKnollRoberts(Pixmap)}, and any {@link Dithered} using
     * {@link Dithered.DitherAlgorithm#PATTERN}) will be incorrect.
     */
    public final int[] paletteArray = new int[256];
    final int[] gammaArray = new int[256];
    ByteArray curErrorRedBytes, nextErrorRedBytes, curErrorGreenBytes, nextErrorGreenBytes, curErrorBlueBytes, nextErrorBlueBytes;
    public int colorCount;
    double ditherStrength = 0.5f, populationBias = 0.5;


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
     * Constructs a default PaletteReducer that uses the "Haltonic" 255-color-plus-transparent palette.
     */
    public PaletteReducer() {
        exact(HALTONIC, ENCODED_HALTONIC);
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
            exact(HALTONIC, ENCODED_HALTONIC);
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
            exact(HALTONIC, ENCODED_HALTONIC);
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
            exact(HALTONIC, ENCODED_HALTONIC);
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
            exact(HALTONIC, ENCODED_HALTONIC);
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
            exact(HALTONIC, ENCODED_HALTONIC);
            return;
        }
        analyze(pixmap);
    }

    /**
     * Constructs a PaletteReducer that analyzes the given Pixmaps for color count and frequency to generate a palette
     * (see {@link #analyze(Array)} )} for more info).
     *
     * @param pixmaps an Array of Pixmap to analyze in detail to produce a palette
     */
    public PaletteReducer(Array<Pixmap> pixmaps) {
        if(pixmaps == null)
        {
            exact(HALTONIC, ENCODED_HALTONIC);
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
     * (see {@link #analyze(Pixmap, int)} for more info).
     *
     * @param pixmap    a Pixmap to analyze in detail to produce a palette
     * @param threshold the minimum difference between colors required to put them in the palette (default 150)
     */
    public PaletteReducer(Pixmap pixmap, int threshold) {
        analyze(pixmap, threshold);
    }
    
    public static double difference(int color1, int color2) {
        if (((color1 ^ color2) & 0x80) == 0x80) return Double.POSITIVE_INFINITY;
        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
                indexB = (color2 >>> 17 & 0x7C00) | (color2 >>> 14 & 0x3E0) | (color2 >>> 11 & 0x1F);
        final double
                i = IPT[0][indexA] - IPT[0][indexB],
                p = IPT[1][indexA] - IPT[1][indexB],
                t = IPT[2][indexA] - IPT[2][indexB];
        return (i * i + p * p + t * t) * 0x1p13;
//        if (((color1 ^ color2) & 0x80) == 0x80) return Double.POSITIVE_INFINITY;
//        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
//                indexB = (color2 >>> 17 & 0x7C00) | (color2 >>> 14 & 0x3E0) | (color2 >>> 11 & 0x1F);
//        final double
//                L = IPT[0][indexA] - IPT[0][indexB],
//                A = IPT[1][indexA] - IPT[1][indexB],
//                B = IPT[2][indexA] - IPT[2][indexB];
//        return (L * L * 3.0 + A * A + B * B) * 0x1p13;//return L * L * 11.0 + A * A * 1.6 + B * B;
//        if(((color1 ^ color2) & 0x80) == 0x80) return Double.POSITIVE_INFINITY;
//        return (RGB_POWERS[Math.abs((color1 >>> 24) - (color2 >>> 24))]
//                + RGB_POWERS[256+Math.abs((color1 >>> 16 & 0xFF) - (color2 >>> 16 & 0xFF))]
//                + RGB_POWERS[512+Math.abs((color1 >>> 8 & 0xFF) - (color2 >>> 8 & 0xFF))]) * 0x1p-10;
    }

    public static double difference(int color1, int r2, int g2, int b2) {
        if((color1 & 0x80) == 0) return Double.POSITIVE_INFINITY;
        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
        final double
                i = IPT[0][indexA] - IPT[0][indexB],
                p = IPT[1][indexA] - IPT[1][indexB],
                t = IPT[2][indexA] - IPT[2][indexB];
        return (i * i + p * p + t * t) * 0x1p13;
//        if((color1 & 0x80) == 0) return Double.POSITIVE_INFINITY;
//        final int indexA = (color1 >>> 17 & 0x7C00) | (color1 >>> 14 & 0x3E0) | (color1 >>> 11 & 0x1F),
//                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
//        final double
//                L = IPT[0][indexA] - IPT[0][indexB],
//                A = IPT[1][indexA] - IPT[1][indexB],
//                B = IPT[2][indexA] - IPT[2][indexB];
//        return (L * L * 3.0 + A * A + B * B) * 0x1p13;//return L * L * 11.0 + A * A * 1.6 + B * B;
//        if((color1 & 0x80) == 0) return Double.POSITIVE_INFINITY;
//        return (RGB_POWERS[Math.abs((color1 >>> 24) - r2)]
//                + RGB_POWERS[256+Math.abs((color1 >>> 16 & 0xFF) - g2)]
//                + RGB_POWERS[512+Math.abs((color1 >>> 8 & 0xFF) - b2)]) * 0x1p-10;
    }

    public static double difference(int r1, int g1, int b1, int r2, int g2, int b2) {
//        final int indexA = (r1 << 7 & 0x7C00) | (g1 << 2 & 0x3E0) | (b1 >>> 3),
//                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
//        final double
//                L = IPT[0][indexA] - IPT[0][indexB],
//                A = IPT[1][indexA] - IPT[1][indexB],
//                B = IPT[2][indexA] - IPT[2][indexB];
//        return (L * L * 3.0 + A * A + B * B) * 0x1p13;//return L * L * 11.0 + A * A * 1.6 + B * B;
//
        int indexA = (r1 << 7 & 0x7C00) | (g1 << 2 & 0x3E0) | (b1 >>> 3),
                indexB = (r2 << 7 & 0x7C00) | (g2 << 2 & 0x3E0) | (b2 >>> 3);
        final double
                i = IPT[0][indexA] - IPT[0][indexB],
                p = IPT[1][indexA] - IPT[1][indexB],
                t = IPT[2][indexA] - IPT[2][indexB];
        return (i * i + p * p + t * t) * 0x1p13;

//        return (RGB_POWERS[Math.abs(r1 - r2)]
//                + RGB_POWERS[256+Math.abs(g1 - g2)]
//                + RGB_POWERS[512+Math.abs(b1 - b2)]) * 0x1p-10;

    }

    /**
     * Resets the palette to the 256-color (including transparent) "Haltonic" palette. PaletteReducer already
     * stores most of the calculated data needed to use this one palette.
     */
    public void setDefaultPalette(){
        exact(HALTONIC, ENCODED_HALTONIC);
    }
    /**
     * Builds the palette information this PNG8 stores from the RGBA8888 ints in {@code rgbaPalette}, up to 256 colors.
     * Alpha is not preserved except for the first item in rgbaPalette, and only if it is {@code 0} (fully transparent
     * black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only has one color, then
     * this defaults to the "Haltonic" palette with 256 well-distributed colors (including transparent).
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
     * limit is less than 2, then this defaults to the "Haltonic" palette with 256 well-distributed colors (including
     * transparent).
     *
     * @param rgbaPalette an array of RGBA8888 ints; all will be used up to 256 items or the length of the array
     * @param limit       a limit on how many int items to use from rgbaPalette; useful if rgbaPalette is from an IntArray
     */
    public void exact(int[] rgbaPalette, int limit) {
        if (rgbaPalette == null || rgbaPalette.length < 2 || limit < 2) {
            exact(HALTONIC, ENCODED_HALTONIC);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, limit), rgbaPalette.length);
        colorCount = plen;
        populationBias = Math.exp(-1.375/colorCount);
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
                        dist = 0x7FFFFFFF;
                        for (int i = 1; i < plen; i++) {
                            if (dist > (dist = Math.min(dist, difference(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
    }

    /**
     * Builds the palette information this PaletteReducer stores from the given array of RGBA8888 ints as a palette (see
     * {@link #exact(int[])} for more info) and an encoded byte array to use to look up pre-loaded color data. The
     * encoded byte array can be copied out of the {@link #paletteMapping} of an existing PaletteReducer, or just as
     * likely you can use {@link #ENCODED_HALTONIC} as a nice default. There's slightly more startup time spent when
     * initially calling {@link #exact(int[])}, but it will produce the same result. You can store the paletteMapping
     * from that PaletteReducer once, however you want to store it, and send it back to this on later runs.
     *
     * @param palette an array of RGBA8888 ints to use as a palette
     * @param preload a byte array with exactly 32768 (or 0x8000) items, containing {@link #paletteMapping} data
     */
    public void exact(int[] palette, byte[] preload)
    {
        if(palette == null || preload == null)
        {
            System.arraycopy(HALTONIC, 0,  paletteArray, 0, 256);
            System.arraycopy(ENCODED_HALTONIC, 0,  paletteMapping, 0, 0x8000);
            colorCount = 256;
            populationBias = Math.exp(-0.00537109375);
            calculateGamma();
            return;
        }
        colorCount = Math.min(256, palette.length);
        System.arraycopy(palette, 0,  paletteArray, 0, colorCount);
        System.arraycopy(preload, 0,  paletteMapping, 0, 0x8000);

        populationBias = Math.exp(-1.375/colorCount);

        calculateGamma();
    }

    /**
     * Builds the palette information this PaletteReducer stores from the Color objects in {@code colorPalette}, up to
     * 256 colors.
     * Alpha is not preserved except for the first item in colorPalette, and only if its r, g, b, and a values are all
     * 0f (fully transparent black); otherwise all items are treated as opaque. If rgbaPalette is null, empty, or only
     * has one color, then this defaults to the "Haltonic" palette with 256 well-distributed colors (including
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
     * one color, or limit is less than 2, then this defaults to the "Haltonic" palette with 256 well-distributed
     * colors (including transparent).
     *
     * @param colorPalette an array of Color objects; all will be used up to 256 items, limit, or the length of the array
     * @param limit        a limit on how many Color items to use from colorPalette; useful if colorPalette is from an Array
     */
    public void exact(Color[] colorPalette, int limit) {
        if (colorPalette == null || colorPalette.length < 2 || limit < 2) {
            exact(HALTONIC, ENCODED_HALTONIC);
            return;
        }
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        final int plen = Math.min(Math.min(256, colorPalette.length), limit);
        colorCount = plen;
        populationBias = Math.exp(-1.375/colorCount);
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
                            if (dist > (dist = Math.min(dist, difference(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
    }
    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or less colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); if there are more than 256 colors
     * or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even if the image has no
     * transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * uses a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #difference(int, int)}) by a value of at least 150, it is
     * allowed in the palette, otherwise it is kept out for being too similar to existing colors. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} field or can be used directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     */
    public void analyze(Pixmap pixmap) {
        analyze(pixmap, 150);
    }

    private static final Comparator<IntIntMap.Entry> entryComparator = new Comparator<IntIntMap.Entry>() {
        @Override
        public int compare(IntIntMap.Entry o1, IntIntMap.Entry o2) {
            return o2.value - o1.value;
        }
    };


    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most 256 colors if there are
     * too many colors to store in a PNG-8 palette. If there are 256 or less colors, this uses the exact colors
     * (although with at most one transparent color, and no alpha for other colors); if there are more than 256 colors
     * or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even if the image has no
     * transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that
     * aren't exact, and dithering works better when the palette can choose colors that are sufficiently different, this
     * takes a threshold value to determine whether it should permit a less-common color into the palette, and if the
     * second color is different enough (as measured by {@link #difference(int, int)}) by a value of at least
     * {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. The threshold is usually between 100 and 1000, and 150 is a good default. Because this always uses the
     * maximum color limit, threshold should be lower than cases where the color limit is small. If the threshold is too
     * high, then some colors that would be useful to smooth out subtle color changes won't get considered, and colors
     * may change more abruptly. This doesn't return a value but instead stores the palette info in this object; a
     * PaletteReducer can be assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields or can be used
     * directly to {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 100 and 1000, 150 is a good default
     */
    public void analyze(Pixmap pixmap, int threshold) {
        analyze(pixmap, threshold, 256);
    }
    /**
     * Analyzes {@code pixmap} for color count and frequency, building a palette with at most {@code limit} colors.
     * If there are {@code limit} or less colors, this uses the exact colors (although with at most one transparent
     * color, and no alpha for other colors); if there are more than {@code limit} colors or any colors have 50% or less
     * alpha, it will reserve a palette entry for transparent (even if the image has no transparency). Because calling
     * {@link #reduce(Pixmap)} (or any of PNG8's write methods) will dither colors that aren't exact, and dithering
     * works better when the palette can choose colors that are sufficiently different, this takes a threshold value to
     * determine whether it should permit a less-common color into the palette, and if the second color is different
     * enough (as measured by {@link #difference(int, int)}) by a value of at least {@code threshold}, it is allowed in
     * the palette, otherwise it is kept out for being too similar to existing colors. The threshold is usually between
     * 100 and 1000, and 150 is a good default. If the threshold is too high, then some colors that would be useful to
     * smooth out subtle color changes won't get considered, and colors may change more abruptly. This doesn't return a
     * value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to {@link #reduce(Pixmap)} a
     * Pixmap.
     *
     * @param pixmap    a Pixmap to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)} or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 100 and 1000, 150 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap pixmap, int threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        threshold >>>= 2;
        final int width = pixmap.getWidth(), height = pixmap.getHeight();
        IntIntMap counts = new IntIntMap(limit);
        int hasTransparent = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                color = pixmap.getPixel(x, y);
                if ((color & 0x80) != 0) {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    counts.getAndIncrement(color, 0, 1);
                } else {
                    hasTransparent = 1;
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
        if (cs + hasTransparent <= limit) {
            int i = hasTransparent;
            for(IntIntMap.Entry e : es) {
                color = e.key;
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            while (i < limit && c < cs) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (difference(color, paletteArray[j]) < threshold)
                        continue PER_BEST;
                }
                paletteArray[i] = color;
                paletteMapping[(color >>> 17 & 0x7C00) | (color >>> 14 & 0x3E0) | (color >>> 11 & 0x1F)] = (byte) i;
                i++;
            }
            colorCount = i;
            populationBias = Math.exp(-1.375/colorCount);
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
                        for (int i = 1; i < limit; i++) {
                            if (dist > (dist = Math.min(dist, difference(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
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
                color = pixmap.getPixel(x, y);
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
                        currentCount = paletteArray[j];
                        paletteArray[j] = paletteArray[i];
                        paletteArray[i] = currentCount;
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
                if(worst == 0) paletteArray[0] = 0;
                else {
                    paletteArray[worst] = paletteArray[0];
                    paletteArray[0] = 0;
                }
            }
            //TODO: sort paletteArray here by count frequency
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
            populationBias = Math.exp(-1.375/colorCount);
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
            populationBias = Math.exp(-1.375/colorCount);
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
                        for (int i = 1; i < limit; i++) {
                            if (dist > (dist = Math.min(dist, difference(paletteArray[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();

    }


    public int blend(int rgba1, int rgba2, double preference) {
        int a1 = rgba1 & 255, a2 = rgba2 & 255;
        if((a1 & 0x80) == 0) return rgba2;
        else if((a2 & 0x80) == 0) return rgba1;
        rgba1 = shrink(rgba1);
        rgba2 = shrink(rgba2);
        double i = IPT[0][rgba1] + (IPT[0][rgba2] - IPT[0][rgba1]) * preference;
        double p = IPT[1][rgba1] + (IPT[1][rgba2] - IPT[1][rgba1]) * preference;
        double t = IPT[2][rgba1] + (IPT[2][rgba2] - IPT[2][rgba1]) * preference;
        double lPrime = i + 0.06503950 * p + 0.15391950 * t;
        double mPrime = i - 0.07591241 * p + 0.09991275 * t;
        double sPrime = i + 0.02174116 * p - 0.50766750 * t;
        double l = Math.copySign(Math.pow(Math.abs(lPrime), 2.3256), lPrime);
        double m = Math.copySign(Math.pow(Math.abs(mPrime), 2.3256), mPrime);
        double s = Math.copySign(Math.pow(Math.abs(sPrime), 2.3256), sPrime);
        int r = (int)(Math.sqrt(Math.min(Math.max(5.432622 * l - 4.679100 * m + 0.246257 * s, 0.0), 1.0)) * 255.99999);
        int g = (int)(Math.sqrt(Math.min(Math.max(-1.10517 * l + 2.311198 * m - 0.205880 * s, 0.0), 1.0)) * 255.99999);
        int b = (int)(Math.sqrt(Math.min(Math.max(0.028104 * l - 0.194660 * m + 1.166325 * s, 0.0), 1.0)) * 255.99999);
        int a = a1 + a2 + 1 >>> 1;
        return r << 24 | g << 16 | b << 8 | a;

    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least 150, it is allowed in the palette, otherwise it is kept out for being too similar to existing
     * colors. This doesn't return a value but instead stores the palette info in this object; a PaletteReducer can be
     * assigned to the {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     */
    public void analyze(Array<Pixmap> pixmaps){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, 150, 256);
    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most 256 colors. If there are 256 or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * 256 colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 100 and 1000, and 150 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 100 and 1000, 150 is a good default
     */
    public void analyze(Array<Pixmap> pixmaps, int threshold){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, 256);
    }

    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 100 and 1000, and 150 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap Array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 100 and 1000, 150 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Array<Pixmap> pixmaps, int threshold, int limit){
        analyze(pixmaps.toArray(Pixmap.class), pixmaps.size, threshold, limit);
    }
    /**
     * Analyzes all of the Pixmap items in {@code pixmaps} for color count and frequency (as if they are one image),
     * building a palette with at most {@code limit} colors. If there are {@code limit} or less colors, this uses the
     * exact colors (although with at most one transparent color, and no alpha for other colors); if there are more than
     * {@code limit} colors or any colors have 50% or less alpha, it will reserve a palette entry for transparent (even
     * if the image has no transparency). Because calling {@link #reduce(Pixmap)} (or any of PNG8's write methods) will
     * dither colors that aren't exact, and dithering works better when the palette can choose colors that are
     * sufficiently different, this takes a threshold value to determine whether it should permit a less-common color
     * into the palette, and if the second color is different enough (as measured by {@link #difference(int, int)}) by a
     * value of at least {@code threshold}, it is allowed in the palette, otherwise it is kept out for being too similar
     * to existing colors. The threshold is usually between 100 and 1000, and 150 is a good default. This doesn't return
     * a value but instead stores the palette info in this object; a PaletteReducer can be assigned to the
     * {@link PNG8#palette} or {@link AnimatedGif#palette} fields, or can be used directly to
     * {@link #reduce(Pixmap)} a Pixmap.
     *
     * @param pixmaps   a Pixmap array to analyze, making a palette which can be used by this to {@link #reduce(Pixmap)}, by AnimatedGif, or by PNG8
     * @param pixmapCount the maximum number of Pixmap entries in pixmaps to use
     * @param threshold a minimum color difference as produced by {@link #difference(int, int)}; usually between 100 and 1000, 150 is a good default
     * @param limit     the maximum number of colors to allow in the resulting palette; typically no more than 256
     */
    public void analyze(Pixmap[] pixmaps, int pixmapCount, int threshold, int limit) {
        Arrays.fill(paletteArray, 0);
        Arrays.fill(paletteMapping, (byte) 0);
        int color;
        threshold >>>= 2;
        IntIntMap counts = new IntIntMap(limit);
        int hasTransparent = 0;
        int[] reds = new int[limit], greens = new int[limit], blues = new int[limit];
        for (int i = 0; i < pixmapCount && i < pixmaps.length; i++) {
            Pixmap pixmap = pixmaps[i];
            final int width = pixmap.getWidth(), height = pixmap.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    color = pixmap.getPixel(x, y);
                    if ((color & 0x80) != 0) {
                        color |= (color >>> 5 & 0x07070700) | 0xFF;
                        counts.getAndIncrement(color, 0, 1);
                    } else {
                        hasTransparent = 1;
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
        if (cs + hasTransparent <= limit) {
            int i = hasTransparent;
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
            populationBias = Math.exp(-1.375/colorCount);
        } else // reduce color count
        {
            int i = 1, c = 0;
            PER_BEST:
            for (; i < limit && c < cs;) {
                color = es.get(c++).key;
                for (int j = 1; j < i; j++) {
                    if (difference(color, paletteArray[j]) < threshold)
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
            populationBias = Math.exp(-1.375/colorCount);
        }
//        for (int i = 0; i < 256; i++) {
//            System.out.printf("0x%08X, ", paletteArray[i]);
//            if((i& 7) == 7) System.out.println();
//        }
//        System.out.println("\n");
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
                        for (int i = 1; i < limit; i++) {
                            if (dist > (dist = Math.min(dist, difference(reds[i], greens[i], blues[i], rr, gg, bb))))
                                paletteMapping[c2] = (byte) i;
                        }
                    }
                }
            }
        }
        calculateGamma();
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
        this.ditherStrength = Math.max(0f, 0.5f * ditherStrength);
        calculateGamma();
    }
    
    void calculateGamma(){
//        double gamma = 2.2;
        double gamma = 1.8 - this.ditherStrength * 1.8;
        for (int i = 0; i < 256; i++) {
            int color = paletteArray[i];
            double r = Math.pow((color >>> 24) / 255.0, gamma);
            double g = Math.pow((color >>> 16 & 0xFF) / 255.0, gamma);
            double b = Math.pow((color >>>  8 & 0xFF) / 255.0, gamma);
            int a = color & 0xFF;
            gammaArray[i] = (int)(r * 255.999) << 24 | (int)(g * 255.999) << 16 | (int)(b * 255.999) << 8 | a;
        }
    }

    /**
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, dithering when it can
     * using Scatter dithering (this merely delegates to {@link #reduceScatter(Pixmap)}).
     * If you want to reduce the colors in a Pixmap based on what it currently contains, call
     * {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduce (Pixmap pixmap) {
        return reduceScatter(pixmap);
    }

    /**
     * Uses the given {@link Dithered.DitherAlgorithm} to decide how to dither {@code pixmap}.
     * @param pixmap a pixmap that will be modified in-place
     * @param ditherAlgorithm a dithering algorithm enum value; if not recognized, defaults to {@link Dithered.DitherAlgorithm#SCATTER}
     * @return {@code pixmap} after modifications
     */
    public Pixmap reduce(Pixmap pixmap, Dithered.DitherAlgorithm ditherAlgorithm){
        if(pixmap == null) return null;
        if(ditherAlgorithm == null) return reduceScatter(pixmap);
        switch (ditherAlgorithm) {
            case NONE:
                return reduceSolid(pixmap);
            case GRADIENT_NOISE:
                return reduceJimenez(pixmap);
            case PATTERN:
                return reduceKnollRoberts(pixmap);
            case CHAOTIC_NOISE:
                return reduceChaoticNoise(pixmap);
            case DIFFUSION:
                return reduceFloydSteinberg(pixmap);
            case BLUE_NOISE:
                return reduceBlueNoise(pixmap); 
                default:
            case SCATTER:
                return reduceScatter(pixmap);
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
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, dithering when it can using
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
        byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedBytes == null) {
            curErrorRed = (curErrorRedBytes = new ByteArray(lineLen)).items;
            nextErrorRed = (nextErrorRedBytes = new ByteArray(lineLen)).items;
            curErrorGreen = (curErrorGreenBytes = new ByteArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenBytes = new ByteArray(lineLen)).items;
            curErrorBlue = (curErrorBlueBytes = new ByteArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueBytes = new ByteArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedBytes.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedBytes.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenBytes.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenBytes.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueBytes.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueBytes.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }

        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, rdiff, gdiff, bdiff;
        byte er, eg, eb, paletteIndex;
        double ditherStrength = this.ditherStrength * this.populationBias, halfDitherStrength = ditherStrength * 0.5;
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
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = Math.min(Math.max(((color >>> 24)       ) + (er), 0), 0xFF);
                    int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + (eg), 0), 0xFF);
                    int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + (eb), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (color>>>24)-    (used>>>24);
                    gdiff = (color>>>16&255)-(used>>>16&255);
                    bdiff = (color>>>8&255)- (used>>>8&255);
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
     * Modifies the given Pixmap so it only uses colors present in this PaletteReducer, dithering when it can using the
     * commonly-used Floyd-Steinberg dithering. If you want to reduce the colors in a Pixmap based on what it currently
     * contains, call {@link #analyze(Pixmap)} with {@code pixmap} as its argument, then call this method with the same
     * Pixmap. You may instead want to use a known palette instead of one computed from a Pixmap;
     * {@link #exact(int[])} is the tool for that job.
     * @param pixmap a Pixmap that will be modified in place
     * @return the given Pixmap, for chaining
     */
    public Pixmap reduceFloydSteinberg (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedBytes == null) {
            curErrorRed = (curErrorRedBytes = new ByteArray(lineLen)).items;
            nextErrorRed = (nextErrorRedBytes = new ByteArray(lineLen)).items;
            curErrorGreen = (curErrorGreenBytes = new ByteArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenBytes = new ByteArray(lineLen)).items;
            curErrorBlue = (curErrorBlueBytes = new ByteArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueBytes = new ByteArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedBytes.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedBytes.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenBytes.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenBytes.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueBytes.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueBytes.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }

        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, rdiff, gdiff, bdiff;
        byte er, eg, eb, paletteIndex;
        float w1 = (float)(ditherStrength * populationBias * 0.125), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;
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
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    er = curErrorRed[px];
                    eg = curErrorGreen[px];
                    eb = curErrorBlue[px];
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = Math.min(Math.max(((color >>> 24)       ) + (er), 0), 0xFF);
                    int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + (eg), 0), 0xFF);
                    int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + (eb), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (color>>>24)-    (used>>>24);
                    gdiff = (color>>>16&255)-(used>>>16&255);
                    bdiff = (color>>>8&255)- (used>>>8&255);
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
    
    public Pixmap reduceJimenez(Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float pos, adj;
        final float strength = (float) (ditherStrength * populationBias * 3.333);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    pos = (px * 0.06711056f + y * 0.00583715f);
                    pos -= (int) pos;
                    pos *= 52.9829189f;
                    pos -= (int) pos;
                    adj = MathUtils.sin(pos * 2f - 1f) * strength;
//                            adj = (pos * pos - 0.3f) * strength;
                    rr = Math.min(Math.max((int) (rr + (adj * (rr - (used >>> 24       )))), 0), 0xFF);
                    gg = Math.min(Math.max((int) (gg + (adj * (gg - (used >>> 16 & 0xFF)))), 0), 0xFF);
                    bb = Math.min(Math.max((int) (bb + (adj * (bb - (used >>> 8  & 0xFF)))), 0), 0xFF);
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
     * A blue-noise-based dither; does not diffuse error, and uses a tiling blue noise pattern (which can be accessed
     * with {@link #RAW_BLUE_NOISE}, but shouldn't usually be modified) as well as a fine-grained checkerboard pattern
     * and a roughly-white-noise pattern obtained by distorting th blue noise, but only applies these noisy pattern
     * when there's error matching a color from the image to a color in the palette.
     * @param pixmap will be modified in-place and returned
     * @return pixmap, after modifications
     */
    public Pixmap reduceBlueNoise (Pixmap pixmap) {
        boolean hasTransparent = (paletteArray[0] == 0);
        final int lineLen = pixmap.getWidth(), h = pixmap.getHeight();
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used;
        float adj, strength = (float) (ditherStrength * populationBias * 1.5);
        for (int y = 0; y < h; y++) {
            for (int px = 0; px < lineLen; px++) {
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
                    adj = ((PaletteReducer.RAW_BLUE_NOISE[(px & 63) | (y & 63) << 6] + 0.5f) * 0.007843138f); // 0.007843138f is 1f / 127.5f
                    adj += ((px + y & 1) - 0.5f) * (0.5f + PaletteReducer.RAW_BLUE_NOISE[(px * 19 & 63) | (y * 23 & 63) << 6])
                            * -0.0013427734f;//-0.0013427734f is -0x1.6p-10f
                    adj *= strength;
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
     * A white-noise-based dither; uses the colors encountered so far during dithering as a sort of state for basic
     * pseudo-random number generation, while also using some blue noise from a tiling texture to offset clumping.
     * This tends to be less "flat" than {@link #reduceBlueNoise(Pixmap)}, permitting more pixels to be different from
     * what {@link #reduceSolid(Pixmap)} would produce, but this generally looks good, especially with larger palettes.
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
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = ((color >>> 24)       );
                    int gg = ((color >>> 16) & 0xFF);
                    int bb = ((color >>> 8)  & 0xFF);
                    used = paletteArray[paletteMapping[((rr << 7) & 0x7C00)
                            | ((gg << 2) & 0x3E0)
                            | ((bb >>> 3))] & 0xFF];
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
        byte[] curErrorRed, nextErrorRed, curErrorGreen, nextErrorGreen, curErrorBlue, nextErrorBlue;
        if (curErrorRedBytes == null) {
            curErrorRed = (curErrorRedBytes = new ByteArray(lineLen)).items;
            nextErrorRed = (nextErrorRedBytes = new ByteArray(lineLen)).items;
            curErrorGreen = (curErrorGreenBytes = new ByteArray(lineLen)).items;
            nextErrorGreen = (nextErrorGreenBytes = new ByteArray(lineLen)).items;
            curErrorBlue = (curErrorBlueBytes = new ByteArray(lineLen)).items;
            nextErrorBlue = (nextErrorBlueBytes = new ByteArray(lineLen)).items;
        } else {
            curErrorRed = curErrorRedBytes.ensureCapacity(lineLen);
            nextErrorRed = nextErrorRedBytes.ensureCapacity(lineLen);
            curErrorGreen = curErrorGreenBytes.ensureCapacity(lineLen);
            nextErrorGreen = nextErrorGreenBytes.ensureCapacity(lineLen);
            curErrorBlue = curErrorBlueBytes.ensureCapacity(lineLen);
            nextErrorBlue = nextErrorBlueBytes.ensureCapacity(lineLen);
            for (int i = 0; i < lineLen; i++) {
                nextErrorRed[i] = 0;
                nextErrorGreen[i] = 0;
                nextErrorBlue[i] = 0;
            }

        }
        Pixmap.Blending blending = pixmap.getBlending();
        pixmap.setBlending(Pixmap.Blending.None);
        int color, used, rdiff, gdiff, bdiff;
        byte er, eg, eb, paletteIndex;
        float w1 = (float)(ditherStrength * populationBias * 0.09375), w3 = w1 * 3f, w5 = w1 * 5f, w7 = w1 * 7f;
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
                color = pixmap.getPixel(px, y) & 0xF8F8F880;
                if ((color & 0x80) == 0 && hasTransparent)
                    pixmap.drawPixel(px, y, 0);
                else {
                    double tbn = PaletteReducer.TRI_BLUE_NOISE_MULTIPLIERS[(px & 63) | ((y << 6) & 0xFC0)];
                    er = (byte) (curErrorRed[px] * tbn);
                    eg = (byte) (curErrorGreen[px] * tbn);
                    eb = (byte) (curErrorBlue[px] * tbn);
                    color |= (color >>> 5 & 0x07070700) | 0xFF;
                    int rr = Math.min(Math.max(((color >>> 24)       ) + (er), 0), 0xFF);
                    int gg = Math.min(Math.max(((color >>> 16) & 0xFF) + (eg), 0), 0xFF);
                    int bb = Math.min(Math.max(((color >>> 8)  & 0xFF) + (eb), 0), 0xFF);
                    paletteIndex =
                            paletteMapping[((rr << 7) & 0x7C00)
                                    | ((gg << 2) & 0x3E0)
                                    | ((bb >>> 3))];
                    used = paletteArray[paletteIndex & 0xFF];
                    pixmap.drawPixel(px, y, used);
                    rdiff = (color>>>24)-    (used>>>24);
                    gdiff = (color>>>16&255)-(used>>>16&255);
                    bdiff = (color>>>8&255)- (used>>>8&255);
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
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     */
    static final int[] thresholdMatrix8 = {
            0, 4, 2, 6,
            3, 7, 1, 5,
    };

    /**
     * Given by Joel Yliluoma in <a href="https://bisqwit.iki.fi/story/howto/dither/jy/">a dithering article</a>.
     */
    static final int[] thresholdMatrix16 = {
            0,  12,   3,  15,
            8,   4,  11,   7,
            2,  14,   1,  13,
            10,  6,   9,   5,
    };

    final int[] candidates = new int[16];

    /**
     * Compares items in ints by their luma, looking up items by the indices a and b, and swaps the two given indices if
     * the item at a has higher luma than the item at b. This is protected rather than private because it's more likely
     * that this would be desirable to override than a method that uses it, like {@link #reduceKnoll(Pixmap)}. Uses
     * {@link #IPT} to look up fairly-accurate luma for the given colors in {@code ints} (that contains RGBA8888 colors
     * while labs uses RGB555, so {@link #shrink(int)} is used to convert).
     * @param ints an int array than must be able to take a and b as indices; may be modified in place
     * @param a an index into ints
     * @param b an index into ints
     */
    protected void compareSwap(final int[] ints, final int a, final int b) {
        if(IPT[0][shrink(ints[a])] > IPT[0][shrink(ints[b])]) {
            final int t = ints[a];
            ints[a] = ints[b];
            ints[b] = t;
        }
    }
    
    /**
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 8.
     * @param i8 an 8-or-more-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    void sort8(final int[] i8) {
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
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 9.
     * @param i9 an 8-or-more-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    void sort9(final int[] i9) {
        compareSwap(i9, 0, 1);
        compareSwap(i9, 3, 4);
        compareSwap(i9, 6, 7);
        compareSwap(i9, 1, 2);
        compareSwap(i9, 4, 5);
        compareSwap(i9, 7, 8);
        compareSwap(i9, 0, 1);
        compareSwap(i9, 3, 4);
        compareSwap(i9, 6, 7);
        compareSwap(i9, 0, 3);
        compareSwap(i9, 3, 6);
        compareSwap(i9, 0, 3);
        compareSwap(i9, 1, 4);
        compareSwap(i9, 4, 7);
        compareSwap(i9, 1, 4);
        compareSwap(i9, 2, 5);
        compareSwap(i9, 5, 8);
        compareSwap(i9, 2, 5);
        compareSwap(i9, 1, 3);
        compareSwap(i9, 5, 7);
        compareSwap(i9, 2, 6);
        compareSwap(i9, 4, 6);
        compareSwap(i9, 2, 4);
        compareSwap(i9, 2, 3);
        compareSwap(i9, 5, 6);
    }
    /**
     * Sorting network, found by http://pages.ripco.net/~jgamble/nw.html , considered the best known for length 16.
     * @param i16 a 16-element array that will be sorted in-place by {@link #compareSwap(int[], int, int)}
     */
    void sort16(final int[] i16)
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
     * very significantly slower than the other dithers here (except for {@link #reduceKnollRoberts(Pixmap)}.
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
        final float errorMul = (float) (ditherStrength * populationBias);
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
                    for (int i = 0; i < candidates.length; i++) {
                        int rr = Math.min(Math.max((int) (cr + er * errorMul), 0), 255);
                        int gg = Math.min(Math.max((int) (cg + eg * errorMul), 0), 255);
                        int bb = Math.min(Math.max((int) (cb + eb * errorMul), 0), 255);
                        usedIndex = paletteMapping[((rr << 7) & 0x7C00)
                                | ((gg << 2) & 0x3E0)
                                | ((bb >>> 3))] & 0xFF;
                        candidates[i] = paletteArray[usedIndex];
                        used = gammaArray[usedIndex];
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
        final float errorMul = (float) (ditherStrength * populationBias * 0.6);
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
                        candidates[c] = paletteArray[usedIndex];
                        used = gammaArray[usedIndex];
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
     * palette array with 256 or less elements that should have been used with {@link #exact(int[])} before to set the
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
     * Edits this PaletteReducer by changing each used color in the IPT color space with an {@link Interpolation}.
     * This allows adjusting lightness, such as for gamma correction. You could use {@link Interpolation#pow2InInverse}
     * to use the square root of a color's lightness instead of its actual lightness, or {@link Interpolation#pow2In} to
     * square the lightness instead.
     * @param lightness an Interpolation that will affect the lightness of each color
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer alterColorsLightness(Interpolation lightness) {
        return alterColorsIPT(lightness, Interpolation.linear, Interpolation.linear);
    }

    /**
     * Edits this PaletteReducer by changing each used color in the IPT color space with an {@link Interpolation}.
     * This allows adjusting lightness, such as for gamma correction, but also individually emphasizing or
     * de-emphasizing different aspects of the chroma. You could use {@link Interpolation#pow2InInverse} to use the
     * square root of a color's lightness instead of its actual lightness, or {@link Interpolation#pow2In} to square the
     * lightness instead. You could make colors more saturated by passing {@link Interpolation#circle} to greenToRed and
     * blueToYellow, or get a less-extreme version by using {@link Interpolation#smooth}. To desaturate colors is a
     * different task; you can create a {@link OtherMath.BiasGain} Interpolation with 0.5 turning and maybe 0.25 to 0.75 shape to
     * produce different strengths of desaturation. Using a shape of 1.5 to 4 with BiasGain is another way to saturate
     * the colors.
     * @param lightness an Interpolation that will affect the lightness of each color
     * @param greenToRed an Interpolation that will make colors more green if it evaluates below 0.5 or more red otherwise
     * @param blueToYellow an Interpolation that will make colors more blue if it evaluates below 0.5 or more yellow otherwise
     * @return this PaletteReducer, for chaining
     */
    public PaletteReducer alterColorsIPT(Interpolation lightness, Interpolation greenToRed, Interpolation blueToYellow) {
        int[] palette = paletteArray;
        for (int idx = 0; idx < colorCount; idx++) {
            int s = shrink(palette[idx]);
            double i = lightness.apply((float) IPT[0][s]);
            double p = greenToRed.apply(-1, 1, (float) IPT[1][s] * 0.5f + 0.5f);
            double t = blueToYellow.apply(-1, 1, (float) IPT[2][s] * 0.5f + 0.5f);
            palette[idx] = iptToRgb(i, p, t, (palette[idx] >>> 1 & 0x7F) / 127f);
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
            double i = IPT[0][s];
            double p = IPT[1][s] + (i - 0.5) * 0.3;
            double t = IPT[2][s] + (i - 0.5) * 0.25;
            palette[idx] = iptToRgb(i, p, t, (palette[idx] >>> 1 & 0x7F) / 127f);
        }
        return this;
    }


}

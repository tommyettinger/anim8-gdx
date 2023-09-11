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

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.utils.NumberUtils;

import java.util.Random;

/**
 * Various math functions that don't fit anywhere else, mostly relating to the shape of a distribution.
 * These include the parameterizable 0-1 curve produced by {@link #barronSpline(float, float, float)}, the
 * bell curve produced from a 0-1 input but with a larger output range by {@link #probit(double)}, and both
 * an accurate approximation of the cube root, {@link #cbrt(float)} and an inaccurate but similarly-shaped
 * method, {@link #cbrtShape(float)}.
 */
public final class OtherMath {
    private OtherMath(){}

    /**
     * Given a byte, pushes any value that isn't extreme toward the center of the 0 to 255 range, and keeps extreme
     * values (such as the channel values in the colors max green or black) as they are.
     * @param v a byte value that will be treated as if in the 0-255 range (as if unsigned)
     * @return a modified version of {@code v} that is more often near the center of the range than the extremes
     */
    public static byte centralize(byte v) {
        return (byte)(barronSpline((v & 255) * 0.003921569f, 0.5f, 0.5f) * 255.999f);
//        return (byte) (255.99 * (probit((v & 255) * 0x1p-8 + 0x1p-9) * 0.17327209222987916 + 0.5));
}

    /**
     * A way of taking a double in the (0.0, 1.0) range and mapping it to a Gaussian or normal distribution, so high
     * inputs correspond to high outputs, and similarly for the low range. This is centered on 0.0 and its standard
     * deviation seems to be 1.0 (the same as {@link Random#nextGaussian()}). If this is given an input of 0.0
     * or less, it returns -38.5, which is slightly less than the result when given {@link Double#MIN_VALUE}. If it is
     * given an input of 1.0 or more, it returns 38.5, which is significantly larger than the result when given the
     * largest double less than 1.0 (this value is further from 1.0 than {@link Double#MIN_VALUE} is from 0.0). If
     * given {@link Double#NaN}, it returns whatever {@link Math#copySign(double, double)} returns for the arguments
     * {@code 38.5, Double.NaN}, which is implementation-dependent. It uses an algorithm by Peter John Acklam, as
     * implemented by Sherali Karimov.
     * <a href="https://web.archive.org/web/20150910002142/http://home.online.no/~pjacklam/notes/invnorm/impl/karimov/StatUtil.java">Original source</a>.
     * <a href="https://web.archive.org/web/20151030215612/http://home.online.no/~pjacklam/notes/invnorm/">Information on the algorithm</a>.
     * <a href="https://en.wikipedia.org/wiki/Probit_function">Wikipedia's page on the probit function</a> may help, but
     * is more likely to just be confusing.
     * <br>
     * Acklam's algorithm and Karimov's implementation are both quite fast. This appears faster than generating
     * Gaussian-distributed numbers using either the Box-Muller Transform or Marsaglia's Polar Method, though it isn't
     * as precise and can't produce as extreme min and max results in the extreme cases they should appear. If given
     * a typical uniform random {@code double} that's exclusive on 1.0, it won't produce a result higher than
     * {@code 8.209536145151493}, and will only produce results of at least {@code -8.209536145151493} if 0.0 is
     * excluded from the inputs (if 0.0 is an input, the result is {@code 38.5}). A chief advantage of using this with
     * a random number generator is that it only requires one random double to obtain one Gaussian value;
     * {@link Random#nextGaussian()} generates at least two random doubles for each two Gaussian values, but may rarely
     * require much more random generation.
     * @param d should be between 0 and 1, exclusive, but other values are tolerated
     * @return a normal-distributed double centered on 0.0; all results will be between -38.5 and 38.5, both inclusive
     */
    public static double probit(final double d) {
        if (d <= 0 || d >= 1) {
            return Math.copySign(38.5, d - 0.5);
        }
        else if (d < 0.02425) {
            final double q = Math.sqrt(-2.0 * Math.log(d));
            return (((((-7.784894002430293e-03 * q + -3.223964580411365e-01) * q + -2.400758277161838e+00) * q + -2.549732539343734e+00) * q + 4.374664141464968e+00) * q + 2.938163982698783e+00)
                    / ((((7.784695709041462e-03 * q + 3.224671290700398e-01) * q + 2.445134137142996e+00) * q + 3.754408661907416e+00) * q + 1.0);
        }
        else if (0.97575 < d) {
            final double q = Math.sqrt(-2.0 * Math.log(1 - d));
            return -(((((-7.784894002430293e-03 * q + -3.223964580411365e-01) * q + -2.400758277161838e+00) * q + -2.549732539343734e+00) * q + 4.374664141464968e+00) * q + 2.938163982698783e+00)
                    / ((((7.784695709041462e-03 * q + 3.224671290700398e-01) * q + 2.445134137142996e+00) * q + 3.754408661907416e+00) * q + 1.0);
        }
        else {
            final double q = d - 0.5;
            final double r = q * q;
            return (((((-3.969683028665376e+01 * r + 2.209460984245205e+02) * r + -2.759285104469687e+02) * r + 1.383577518672690e+02) * r + -3.066479806614716e+01) * r + 2.506628277459239e+00) * q
                    / (((((-5.447609879822406e+01 * r + 1.615858368580409e+02) * r + -1.556989798598866e+02) * r + 6.680131188771972e+01) * r + -1.328068155288572e+01) * r + 1.0);
        }
    }

    /**
     * An approximation of the cube-root function for float inputs and outputs.
     * This can be about twice as fast as {@link Math#cbrt(double)}. It
     * correctly returns negative results when given negative inputs.
     * <br>
     * Has very low relative error (less than 1E-9) when inputs are uniformly
     * distributed between -512 and 512, and absolute mean error of less than
     * 1E-6 in the same scenario. Uses a bit-twiddling method similar to one
     * presented in Hacker's Delight and also used in early 3D graphics (see
     * https://en.wikipedia.org/wiki/Fast_inverse_square_root for more, but
     * this code approximates cbrt(x) and not 1/sqrt(x)). This specific code
     * was originally by Marc B. Reynolds, posted in his "Stand-alone-junk"
     * repo: https://github.com/Marc-B-Reynolds/Stand-alone-junk/blob/master/src/Posts/ballcube.c#L182-L197 .
     * @param x any finite float to find the cube root of
     * @return the cube root of x, approximated
     */
    public static float cbrt(float x) {
        int ix = NumberUtils.floatToIntBits(x);
        final int sign = ix & 0x80000000;
        ix &= 0x7FFFFFFF;
        final float x0 = x;
        ix = (ix>>>2) + (ix>>>4);
        ix += (ix>>>4);
        ix = ix + (ix>>>8) + 0x2A5137A0 | sign;
        x  = NumberUtils.intBitsToFloat(ix);
        x  = 0.33333334f*(2f * x + x0/(x*x));
        x  = 0.33333334f*(2f * x + x0/(x*x));
        return x;
    }
    /**
     * An approximation of the cube-root function for float inputs and outputs.
     * This can be about twice as fast as {@link Math#cbrt(double)}. This
     * version does not tolerate negative inputs, because in the narrow use
     * case it has in this class, it is never given negative inputs.
     * <br>
     * Has very low relative error (less than 1E-9) when inputs are uniformly
     * distributed between 0 and 512, and absolute mean error of less than
     * 1E-6 in the same scenario. Uses a bit-twiddling method similar to one
     * presented in Hacker's Delight and also used in early 3D graphics (see
     * https://en.wikipedia.org/wiki/Fast_inverse_square_root for more, but
     * this code approximates cbrt(x) and not 1/sqrt(x)). This specific code
     * was originally by Marc B. Reynolds, posted in his "Stand-alone-junk"
     * repo: https://github.com/Marc-B-Reynolds/Stand-alone-junk/blob/master/src/Posts/ballcube.c#L182-L197 .
     * It's worth noting that while hardware instructions for finding the
     * square root of a float have gotten extremely fast, the same is not
     * true for the cube root (which has to allow negative inputs), so while
     * the bit-twiddling inverse square root is no longer a beneficial
     * optimization on current hardware, this does seem to help.
     * <br>
     * This is used when converting from RGB to Oklab, as an intermediate step.
     * @param x any non-negative finite float to find the cube root of
     * @return the cube root of x, approximated
     */
    public static float cbrtPositive(float x) {
        int ix = NumberUtils.floatToIntBits(x);
        final float x0 = x;
        ix = (ix>>>2) + (ix>>>4);
        ix += (ix>>>4);
        ix += (ix>>>8) + 0x2A5137A0;
        x  = NumberUtils.intBitsToFloat(ix);
        x  = 0.33333334f*(2f * x + x0/(x*x));
        x  = 0.33333334f*(1.9999999f * x + x0/(x*x));
        return x;
    }

    /**
     * A function that loosely approximates the cube root of {@code x}, but is much smaller and probably faster than
     * {@link OtherMath#cbrt(float)}. This is meant to be used when you want the shape of a cbrt() function, but don't
     * actually care about it being the accurate mathematical cube-root.
     * <br>
     * This method is small enough that it make more sense to inline it than to call this exact implementation. The code
     * is simply, given a finite float x:
     * <pre>
     *     return x * 1.25f / (0.25f + Math.abs(x));
     * </pre>
     * 1.25f is the value M that this will gradually approach (but never reach) for positive inputs; negative inputs
     * approach -M instead. 0.25f is the value N that changes the curvature of the line this forms; using N closer to
     * 0.004f results in a shape closer to the actual cube root function, while using larger N (such as 1f or 2f) makes
     * the line closer to straight, with a shallow slope.
     * @param x any finite float
     * @return a loose approximation of the cube root of x; mostly useful for its shape
     */
    public static float cbrtShape(float x){
        /*
         * <a href="https://metamerist.blogspot.com/2007/09/faster-cube-root-iii.html">Initially given here</a> by
         * metamerist; I just made it respect sign.
         */
//        final int i = NumberUtils.floatToIntBits(x);
//        return NumberUtils.intBitsToFloat(((i & 0x7FFFFFFF) - 0x3F800000) / 3 + 0x3F800000 | (i & 0x80000000));
        return x * 1.25f / (0.25f + Math.abs(x));
    }

    /**
     * A variant on {@link Math#atan(double)} that does not tolerate infinite inputs and takes/returns floats.
     * @param i any finite float
     * @return an output from the inverse tangent function, from PI/-2.0 to PI/2.0 inclusive
     */
    private static float atanUnchecked(final float i) {
        final float n = Math.abs(i);
        final float c = (n - 1f) / (n + 1f);
        final float c2 = c * c;
        final float c3 = c * c2;
        final float c5 = c3 * c2;
        final float c7 = c5 * c2;
        return Math.signum(i) * (0.7853981633974483f +
                (0.999215f * c - 0.3211819f * c3 + 0.1462766f * c5 - 0.0389929f * c7));
    }

    /**
     * Close approximation of the frequently-used trigonometric method atan2, with higher precision than libGDX's atan2
     * approximation. Maximum error is below 0.00009 radians.
     * Takes y and x (in that unusual order) as floats, and returns the angle from the origin to that point in radians.
     * It is about 5 times faster than {@link Math#atan2(double, double)} (roughly 12 ns instead of roughly 62 ns for
     * Math, on Java 8 HotSpot). It is slightly faster than libGDX' MathUtils approximation of the same method;
     * MathUtils seems to have worse average error, though.
     * <br>
     * Credit for this goes to the 1955 research study "Approximations for Digital Computers," by RAND Corporation. This
     * is sheet 9's algorithm, which is the second-fastest and second-least precise. The algorithm on sheet 8 is faster,
     * but only by a very small degree, and is considerably less precise. That study provides an atan(float)
     * method, and the small code to make that work as atan2() was worked out from Wikipedia.
     * @param y y-component of the point to find the angle towards; note the parameter order is unusual by convention
     * @param x x-component of the point to find the angle towards; note the parameter order is unusual by convention
     * @return the angle to the given point, in radians as a float; ranges from -PI to PI
     */
    public static float atan2(final float y, float x) {
        float n = y / x;
        if(n != n) n = (y == x ? 1f : -1f); // if both y and x are infinite, n would be NaN
        else if(n - n != n - n) x = 0f; // if n is infinite, y is infinitely larger than x.
        if(x > 0)
            return atanUnchecked(n);
        else if(x < 0) {
            if(y >= 0)
                return atanUnchecked(n) + 3.14159265358979323846f;
            else
                return atanUnchecked(n) - 3.14159265358979323846f;
        }
        else if(y > 0) return x + 1.5707963267948966f;
        else if(y < 0) return x - 1.5707963267948966f;
        else return x + y; // returns 0 for 0,0 or NaN if either y or x is NaN
    }

    /**
     * A generalization on bias and gain functions that can represent both; this version is branch-less.
     * This is based on <a href="https://arxiv.org/abs/2010.09714">this micro-paper</a> by Jon Barron, which
     * generalizes the earlier bias and gain rational functions by Schlick. The second and final page of the
     * paper has useful graphs of what the s (shape) and t (turning point) parameters do; shape should be 0
     * or greater, while turning must be between 0 and 1, inclusive. This effectively combines two different
     * curving functions so they continue into each other when x equals turning. The shape parameter will
     * cause this to imitate "smoothstep-like" splines when greater than 1 (where the values ease into their
     * starting and ending levels), or to be the inverse when less than 1 (where values start like square
     * root does, taking off very quickly, but also end like square does, landing abruptly at the ending
     * level). You should only give x values between 0 and 1, inclusive.
     * @param x progress through the spline, from 0 to 1, inclusive
     * @param shape must be greater than or equal to 0; values greater than 1 are "normal interpolations"
     * @param turning a value between 0.0 and 1.0, inclusive, where the shape changes
     * @return a float between 0 and 1, inclusive
     */
    public static float barronSpline(final float x, final float shape, final float turning) {
        final float d = turning - x;
        final int f = com.badlogic.gdx.utils.NumberUtils.floatToIntBits(d) >> 31, n = f | 1;
        return ((turning * n - f) * (x + f)) / (Float.MIN_NORMAL - f + (x + shape * d) * n) - f;
    }

    /**
     * A wrapper around {@link #barronSpline(float, float, float)} to use it as an Interpolation.
     * Useful because it can imitate the wide variety of symmetrical Interpolations by setting turning to 0.5 and shape
     * to some value greater than 1, while also being able to produce the inverse of those interpolations by setting
     * shape to some value between 0 and 1.
     */
    public static class BiasGain extends Interpolation {
        /**
         * The shape parameter will cause this to imitate "smoothstep-like" splines when greater than 1 (where the
         * values ease into their starting and ending levels), or to be the inverse when less than 1 (where values
         * start like square root does, taking off very quickly, but also end like square does, landing abruptly at
         * the ending level).
         */
        public final float shape;
        /**
         * A value between 0.0 and 1.0, inclusive, where the shape changes.
         */
        public final float turning;

        /**
         * Constructs a useful default BiasGain interpolation with a smoothstep-like shape.
         * This has a shape of 2.0f and a turning of 0.5f .
         */
        public BiasGain() {
            this(2f, 0.5f);
        }

        /**
         * Constructs a BiasGain interpolation with the specified (positive) shape and specified turning (between 0 and
         * 1 inclusive).
         * @param shape must be positive; similar to a straight line when near 1, becomes smoothstep-like above 1, and
         *              becomes shaped like transpose of smoothstep below 1
         * @param turning where, between 0 and 1 inclusive, this should change from the starting curve to the ending one
         */
        public BiasGain (float shape, float turning) {
            this.shape = shape;
            this.turning = turning;
        }

        public float apply (float a) {
            return barronSpline(a, shape, turning);
        }
    }
}

# anim8-gdx
Support for writing animated GIF, PNG8, and animated PNG (including full-color) from libGDX

There's been support for writing some image-file types from libGDX for a while, via its PixmapIO class.
PixmapIO can write full-color PNG files, plus the libGDX-specific CIM file format. It can't write any
animated image formats, nor can it write any indexed-mode images (which use a palette, and tend to be
smaller files). This library, anim8, allows libGDX applications to write animated GIF files, indexed-mode
PNG files, and animated PNG files (with either full-color or palette-based color). The API tries to
imitate the PixmapIO.PNG nested class, but supporting a palette needs some new methods. For a simple use
case, here's a `writeGif()` method that calls `render()` 20 times and screenshots each frame:

```java
public void writeGif() {
    final int frameCount = 20;
    Array<Pixmap> pixmaps = new Array<>(frameCount);
    for (int i = 0; i < frameCount; i++) {
        // you could set the proper state for a frame here.

        // you don't need to call render() in all cases, especially if you have Pixmaps already.
        // this assumes you're calling this from a class that uses render() to draw to the screen.
        render();
        // this gets a screenshot of the current window and adds it to the Array of Pixmap.
        pixmaps.add(ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
    }
    // AnimatedGif is from anim8; if no extra settings are specified it will calculate a 255-color palette from
    // the given pixmaps and use that for all frames, dithering any colors that don't match.
    // see Quirks below for visual things to be aware of and choices you can take.
    AnimatedGif gif = new AnimatedGif();
    // you can write to a FileHandle or an OutputStream; here, the file will be written in the current directory.
    // here, pixmaps is usually an Array of Pixmap for any of the animated image types.
    // 16 is how many frames per second the animated GIF should play back at.
    gif.write(Gdx.files.local("AnimatedGif.gif"), pixmaps, 16);
}
```

The above code uses AnimatedGif, but could also use AnimatedPNG or PNG8 to write to an animated PNG (with full-color or
palette-based color, respectively).

# Install

A typical Gradle dependency on anim8 looks like this (in the core module's dependencies for a typical libGDX project):
```groovy
dependencies {
  //... other dependencies are here, like libGDX
  api "com.github.tommyettinger:anim8-gdx:0.1.6"
}
```

You can also get a specific commit using JitPack, by following the instructions on
[JitPack's page for anim8](https://jitpack.io/#tommyettinger/anim8-gdx/e93fcd85db). 

A .gwt.xml file is present in the sources jar, and because GWT needs it, you can depend on the sources jar with
`implementation "com.github.tommyettinger:anim8-gdx:0.1.6:sources"`. The PNG-related code isn't available on GWT because
it needs `java.util.zip`, which is unavailable there, but PaletteReducer and AnimatedGif should both work. The GWT
inherits line, which is needed in `GdxDefinition.gwt.xml` if no dependencies already have it, is:
```xml
``<inherits name="anim8" />
```

# Quirks
The default dithering algorithm used here is a variant on Thomas Knoll's pattern dither, which has been out-of-patent
since November 2019. Used verbatim, pattern dither forms a square grid of lighter or darker pixels where a color isn't
matched exactly. The first change here affects **Quirk Number One**: with pattern dither, some diagonal streaks may
appear due to how the square grid has been skewed to obscure that artifact. The second change relates to **Quirk Number
Two**: some partial gamma correction seems to significantly reduce the appearance of coarse dithering artifacts, but
also tends to bias the lightness balance toward brighter colors, sometimes partly washing out some details. You can set
the dithering algorithm to an alternative ordered dither, a variant on Jorge Jimenez' Gradient Interleaved Noise, using:
`setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE)`. This still has some diagonal lines that appear in it, but
they aren't usually as noticeable; there is however **Quirk Number Three**: with gradient noise dither, some smooth
gradients in the source image have rough sections where they move briefly away from the right color before correcting
their path. This has been improved in 0.1.5, by changing how color difference is calculated, but there are still some
noticeable flaws in the smooth parts of some images. Gradient noise dither also tends to be, well, noisier. There's also
the `Dithered.DitherAlgorithm.NONE` algorithm, but it's only reasonable for some art styles that don't look good with
any dither. Using pattern dither can also be a little slow if you are writing many large images or long animations;
gradient noise dither is much faster, and not using dither offers no real performance boost over gradient noise. If you
aren't writing an animation, you can get good results with the `Dithered.DitherAlgorithm.DIFFUSION` algorithm. This
uses Floyd-Steinberg dithering and preserves lightness while accurately matching shape, but has **Quirk Number Four**,
that it causes pixels to jitter between frames of an animation due to tiny changes propagating throughout the image.
The `DIFFUSION` algorithm has been added in version 0.1.6, coinciding with an update to libGDX 1.9.11. 

# Samples
Some .gif animations, using 255 colors:

Pattern dither:

![Flashy Gif, pattern dither](images/AnimatedGif-flashy-pattern.gif)

Gradient dither:

![Flashy Gif, gradient dither](images/AnimatedGif-flashy-pattern.gif)

Pattern dither:

![Pastel Gif, pattern dither](images/AnimatedGif-pastel-pattern.gif)

Gradient dither:

![Pastel Gif, gradient dither](images/AnimatedGif-pastel-pattern.gif)

Pattern dither:

![Green Gif, pattern dither](images/AnimatedGif-green-pattern.gif)

Gradient dither:

![Green Gif, gradient dither](images/AnimatedGif-green-gradient.gif)

No dither:

![Green Gif, no dither](images/AnimatedGif-green-none.gif)

Some .gif animations that reduce the colors of the first two animations shown:

Black and white pattern dither:

![BW Gif, pattern dither](images/AnimatedGif-bw-pattern.gif)

Black and white gradient dither:

![BW Gif, gradient dither](images/AnimatedGif-bw-gradient.gif)

Black and white no dither:

![BW Gif, no dither](images/AnimatedGif-bw-none.gif)

4-color green-scale pattern dither:

![GB Gif, pattern dither](images/AnimatedGif-gb-pattern.gif)

4-color green-scale gradient dither:

![GB Gif, gradient dither](images/AnimatedGif-gb-gradient.gif)

And some .png animations, using full color:

![Flashy Full-Color PNG](images/AnimatedPNG-flashy.png)

![Pastel Full-Color PNG](images/AnimatedPNG-pastel.png)

![Green Full-Color PNG](images/AnimatedPNG-green.png)

A more intense usage is to encode a high-color video as an indexed-color GIF; why you might do this, I don't know,
but someone probably wants videos as GIFs. The images here are 90 frames from
["Video Of A Market" by Olivier Polome](https://www.pexels.com/video/video-of-a-market-4236787/), which is freely
licensed without requirements. Note that all of the following animations are limited to 255 colors, and the mp4 video
they use as a source has some block artifacts.

Market Video, gradient dither:
![Video of a Market](images/AnimatedGif-market-GradientNoise.gif)

Market Video, pattern dither:
![Video of a Market](images/AnimatedGif-market-Pattern.gif)

Market Video, Floyd-Steinberg error diffusion dither:
![Video of a Market](images/AnimatedGif-market-Diffusion.gif)

Market Video, blue noise dither:
![Video of a Market](images/AnimatedGif-market-BlueNoise.gif)

Market Video, no dither:
![Video of a Market](images/AnimatedGif-market-None.gif)

Animated PNG can support full alpha as well (though file sizes can be large):

![Full-Color PNG with Alpha](images/AnimatedPNG-alpha.png)

Anim8 also can be used to support writing non-animated GIF images and indexed-mode PNG images.
Here's a retouched version of the Mona Lisa,
[source on Wikimedia Commons here](https://commons.wikimedia.org/wiki/File:Mona_Lisa_Digitally_Restored.tif), and
various 15-color dithers using a palette derived from the most frequent and different colors in the original:

Original (full-color):

![](images/Mona_Lisa-PNG-Full.png)

Pattern:

![](images/Mona_Lisa-Gif-Pattern-16.gif)

Diffusion:

![](images/Mona_Lisa-Gif-Diffusion-16.gif)

Gradient Noise:

![](images/Mona_Lisa-Gif-Gradient-16.gif)

None (no dither):

![](images/Mona_Lisa-Gif-None-16.gif)

(If the Wikimedia Commons source file is deleted, the original is available in the history of
[this other image](https://commons.wikimedia.org/wiki/File:Leonardo_da_Vinci_-_Mona_Lisa_(Louvre,_Paris)FXD.tif)).

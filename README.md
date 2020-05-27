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
public void renderGif() {
    final int frameCount = 20;
    Array<Pixmap> pixmaps = new Array<>(frameCount);
    for (int i = 0; i < frameCount; i++) {
        // you could set the proper state for a frame here.

        // you don't need to call render() in all cases, especially if you have Pixmaps already.
        render();
        // this gets a screenshot of the current window and adds it to the Array of Pixmap.
        pixmaps.add(ScreenUtils.getFrameBufferPixmap(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
    }
    // AnimatedGif is from anim8; if no extra settings are specified it will calculate a 255-color palette from
    // the given pixmaps and use that for all frames, dithering any colors that don't match.
    AnimatedGif gif = new AnimatedGif();
    try {
        // you can write to a FileHandle or an OutputStream; here, the file will be written in the current directory.
        // here, pixmaps is usually an Array of Pixmap for any of the animated image types.
        // 16 is how many frames per second the animated GIF should play back at.
        gif.write(Gdx.files.local("AnimatedGif.gif"), pixmaps, 16);
    } catch (IOException e) {
        // not strictly necessary, but useful if there is a problem.
        e.printStackTrace();
    }
}
```

The above code uses AnimatedGif, but could also use AnimatedPNG or PNG8 to write to an animated PNG (with full-color or
palette-based color, respectively). All of these need a dependency on anim8; a typical Gradle dep uses JitPack for now,
and Maven Central should be supported soon. To depend on an early working version (this applies to the core module's
dependencies for a typical libGDX project):
```groovy
// add the JitPack repository if you don't already have it
allprojects { // note, this is NOT the repositories section in buildscript !
  repositories {
    //... other repositories are here, like: mavenCentral()
    maven { url 'https://jitpack.io' }
  }
}
dependencies {
  //... other dependencies are here, like libGDX
  api 'com.github.tommyettinger:anim8-gdx:356247230b'
}
```

Updates to the commit version are visible in the commits tab at
[JitPack's page for anim8](https://jitpack.io/#tommyettinger/anim8-gdx/356247230b); a release should happen soon. 

package com.github.tommyettinger;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.tommyettinger.anim8.*;
import com.github.tommyettinger.anim8.Dithered.DitherAlgorithm;

import static com.github.tommyettinger.anim8.Dithered.DitherAlgorithm.*;

/**
 * This takes two multiple-frame images/videos and dithers both of them in all the ways this can before writing to GIF
 * and optionally PNG files. There is a 90-frame "Video Of A Market" by Olivier Polome,
 * <a href="https://www.pexels.com/video/video-of-a-market-4236787/">available freely at Pexels</a>, a rotating
 * cartoon-style pixel-art monster animation (self-made, public domain), a procedurally-generated globe (self-made,
 * public domain), another pixel-art animation of a tank (self-made, public domain), and two animations of slices of
 * color solids (both self-made, public domain).
 */

// on September 13, 2023, running this (the quality writer demo) took: 5943629 ms.
// on September 14, 2023, running the standard writer demo took:       5738491 ms.
// on September 14, 2023, running the "fast" writer demo took:         5783939 ms.
// by September 15, 2023, AnimatedGif had been optimized, so it should perform as it did in earlier versions.
// the code for this demo was also optimized, to avoid recreating QualityPalette objects.
// on September 15, 2023, running this (the quality writer demo) took: 1028531 ms.
// on September 15, 2023, running the standard writer demo took:        979426 ms.
// on September 15, 2023, running the "fast" writer demo took:          976948 ms.
// WREN dither was added around now, adding another image to generate per palette per sample image.
// on September 30, 2023, running this (the quality writer demo) took: 1061057 ms.
// on September 30, 2023, running the standard writer demo took:       1066379 ms.
// on September 30, 2023, running the "fast" writer demo took:         1067096 ms.
// There is currently less than a 1% difference in speed between any of these; that's well within the margin of error.
// on September 30, 2023, running just the PNG8 and AnimatedPNG code took 303641 ms. PNG8 took almost all of that.
// on September 30, 2023, running just the PNG8                 code took 182646 ms. This omitted benchmarking analysis.
// on October 1, 2023, running this took     726596 ms. (This didn't write PNG or APNG files, and doesn't over-analyze.)
public class QualityVideoConvertDemo extends ApplicationAdapter {
//    private static final Dithered.DitherAlgorithm[] DITHERS = {Dithered.DitherAlgorithm.GOURD};
//    private static final Dithered.DitherAlgorithm[] DITHERS = Dithered.DitherAlgorithm.ALL;
    private static final DitherAlgorithm[] DITHERS = {

//        NONE, GRADIENT_NOISE, PATTERN, DIFFUSION, BLUE_NOISE, CHAOTIC_NOISE, SCATTER, NEUE, ROBERTS, WOVEN, DODGY, LOAF, WREN, OVERBOARD, BURKES, OCEANIC, SEASIDE, GOURD

        //Took 218108 ms
//        PATTERN

        //Took 34111 ms
//        NONE

        //Took 47694 ms
//        ROBERTS

        //Took 40174 ms
//        GRADIENT_NOISE

        //Took 39233 ms
//        LOAF

        //Took 35361 ms
        GOURD

        //
//        NONE, GRADIENT_NOISE, DIFFUSION, BLUE_NOISE, CHAOTIC_NOISE, SCATTER, NEUE, ROBERTS, WOVEN, DODGY, LOAF, WREN, OVERBOARD, BURKES, OCEANIC, SEASIDE, GOURD
};
    private boolean fastAnalysis = true;
    @Override
    public void create() {
        Gdx.app.setLogLevel(Application.LOG_DEBUG);
        long startTime = TimeUtils.millis();

        Gdx.files.local("images").mkdirs();
        String[] names = new String[]{
                "-Analyzed",
                "-Snuggly",
                "-Aurora", "-BW", "-Green", "-DB8", "-Prospecal"};
        QualityPalette[] palettes = new QualityPalette[]{
                null,
                new QualityPalette(),
                new QualityPalette(QualityPalette.AURORA),
                new QualityPalette(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}),
                new QualityPalette(new int[]{0x00000000,
                        0x000000FF, 0x081820FF, 0x132C2DFF, 0x1E403BFF, 0x295447FF, 0x346856FF, 0x497E5BFF, 0x5E9463FF,
                        0x73AA69FF, 0x88C070FF, 0x9ECE88FF, 0xB4DCA0FF, 0xCAEAB8FF, 0xE0F8D0FF, 0xEFFBE7FF, 0xFFFFFFFF}),
                new QualityPalette(new int[]{0x00000000,
                        0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF}),
                new QualityPalette(new int[]{0x00000000,
                        0x6DB5BAFF, 0x26544CFF, 0x76AA3AFF, 0xFBFDBEFF, 0xD23C4FFF, 0x2B1328FF, 0x753D38FF, 0xEFAD5FFF})
        };
//        renderAPNG();
//        renderPNG8(names, palettes);

        renderVideoGif(names, palettes);
        renderPixelGif(names, palettes);
        renderWorldGif(names, palettes);
        renderOklabGif(names, palettes);
        renderTankGif(names, palettes);
        renderSolidsGif(names, palettes);

        fastAnalysis = false;
        names = new String[]{"-Analyzed"};
        palettes = new QualityPalette[]{null};

        renderVideoGif(names, palettes);
        renderPixelGif(names, palettes);
        renderWorldGif(names, palettes);
        renderOklabGif(names, palettes);
        renderTankGif(names, palettes);
        renderSolidsGif(names, palettes);

        System.out.println("Took " + (TimeUtils.millis() - startTime) + " ms");
        Gdx.app.exit();
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void render() { 
        
    }

    public void renderAPNG() {
        System.out.println("Rendering video APNG");
        String name = "market";
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        AnimatedPNG apng = new AnimatedPNG();
        apng.setCompression(7);
        apng.setFlipY(false);
        apng.write(Gdx.files.local("images/apng/animated/AnimatedPNG-" + name + ".png"), pixmaps, 20);
    }
    
    public void renderPNG8(String[] names, QualityPalette[] palettes) {
        System.out.println("Rendering video PNG8");
        String name = "market";
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        PNG8 png8 = new PNG8();
        png8.setFlipY(false);
        png8.setCompression(7);
        String namePalette;
        String prefix = "images/png/animated/PNG8-";
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            if(palettes[i] == null)
                png8.setPalette(new QualityPalette(pixmaps));
            else
                png8.setPalette(palettes[i]);

            for (DitherAlgorithm d : DITHERS) {
                png8.setDitherAlgorithm(d);
                png8.write(Gdx.files.local(prefix + namePalette + "-" + d + "-Q.png"), pixmaps, 20);
            }
        }

        for (Pixmap pm : pixmaps)
            pm.dispose();

    }

    public void renderVideoGif(String[] names, QualityPalette[] palettes) {
        System.out.println("Rendering video GIF");
        String name = "market";
        Array<Pixmap> pixmaps = new Array<>(true, 90, Pixmap.class);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        AnimatedGif gif = new AnimatedGif();
        gif.fastAnalysis = fastAnalysis;
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            gif.setPalette(palettes[i]);

            gif.setFlipY(false);
            String prefix = "images/gif/animated" + (gif.palette != null ? "" : gif.fastAnalysis ? "Fast" : "Slow") + "/AnimatedGif-";
            for (DitherAlgorithm d : DITHERS) {
                gif.setDitherAlgorithm(d);
                gif.write(Gdx.files.local(prefix + namePalette + "-" + d + "-Q.gif"), pixmaps, 20);
            }
        }

        for (Pixmap pm : pixmaps)
            pm.dispose();
    }

    public void renderPixelGif(String[] names, QualityPalette[] palettes) {
        System.out.println("Rendering tyrant GIF");
        String name = "tyrant";
        Array<Pixmap> pixmaps = new Array<>(true, 64, Pixmap.class);
        for (int i = 0; i < 64; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".png")));
        }
        AnimatedGif gif = new AnimatedGif();
        gif.fastAnalysis = fastAnalysis;
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            gif.setPalette(palettes[i]);

            gif.setFlipY(false);
            String prefix = "images/gif/animated" + (gif.palette != null ? "" : gif.fastAnalysis ? "Fast" : "Slow") + "/AnimatedGif-";
            for (DitherAlgorithm d : DITHERS) {
                gif.setDitherAlgorithm(d);
                gif.write(Gdx.files.local(prefix + namePalette + "-" + d + "-Q.gif"), pixmaps, 12);
            }
        }
    }

    public void renderTankGif(String[] names, QualityPalette[] palettes) {
        System.out.println("Rendering pixel tank GIF");
        String name = "tank";
        Array<Pixmap> pixmaps = new Array<>(true, 16, Pixmap.class);
        for (int i = 0; i < 16; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".png")));
        }
        AnimatedGif gif = new AnimatedGif();
        gif.fastAnalysis = fastAnalysis;
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            gif.setPalette(palettes[i]);

            gif.setFlipY(false);
            String prefix = "images/gif/animated"+(gif.palette != null ? "" : gif.fastAnalysis ? "Fast" : "Slow")+"/AnimatedGif-";
            for (DitherAlgorithm d : DITHERS) {
                gif.setDitherAlgorithm(d);
                gif.write(Gdx.files.local(prefix + namePalette + "-" + d + "-Q.gif"), pixmaps, 12);
            }
        }
    }

    public void renderWorldGif(String[] names, QualityPalette[] palettes) {
        System.out.println("Rendering world GIF");
        String name = "world";
        Array<Pixmap> pixmaps = new Array<>(true, 180, Pixmap.class);
        for (int i = 0; i < 180; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".png")));
        }
        AnimatedGif gif = new AnimatedGif();
        gif.fastAnalysis = fastAnalysis;
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            gif.setPalette(palettes[i]);

            gif.setFlipY(false);
            String prefix = "images/gif/animated"+(gif.palette != null ? "" : gif.fastAnalysis ? "Fast" : "Slow")+"/AnimatedGif-";
            for (DitherAlgorithm d : DITHERS) {
                gif.setDitherAlgorithm(d);
                gif.write(Gdx.files.local(prefix + namePalette + "-" + d + "-Q.gif"), pixmaps, 20);
            }
        }
    }

    public void renderOklabGif(String[] names, QualityPalette[] palettes) {
        System.out.println("Rendering Oklab GIF");
        String name = "oklab";
        Array<Pixmap> pixmaps = new Array<>(true, 120, Pixmap.class);
        for (int i = 0; i < 120; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".png")));
        }
        AnimatedGif gif = new AnimatedGif();
        gif.fastAnalysis = fastAnalysis;
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            gif.setPalette(palettes[i]);

            gif.setFlipY(false);
            String prefix = "images/gif/animated"+(gif.palette != null ? "" : gif.fastAnalysis ? "Fast" : "Slow")+"/AnimatedGif-";
            for (DitherAlgorithm d : DITHERS) {
                gif.setDitherAlgorithm(d);
                gif.write(Gdx.files.local(prefix + namePalette + "-" + d + "-Q.gif"), pixmaps, 20);
            }
        }
    }


    public void renderSolidsGif(String[] names, QualityPalette[] palettes) {
        System.out.println("Rendering solids GIF");
        String name = "solids";
        Array<Pixmap> pixmaps = new Array<>(true, 256, Pixmap.class);
        for (int i = 0; i < 256; i++) {
//        for(int i : new int[]{0, 63, 64, 65, 127, 128, 129, 190, 191, 192, 255}) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".png")));
        }
        AnimatedGif gif = new AnimatedGif();
        gif.fastAnalysis = fastAnalysis;
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            gif.setPalette(palettes[i]);

            gif.setFlipY(false);
            String prefix = "images/gif/animated"+(gif.palette != null ? "" : gif.fastAnalysis ? "Fast" : "Slow")+"/AnimatedGif-";
            for (DitherAlgorithm d : DITHERS) {
                gif.setDitherAlgorithm(d);
                gif.write(Gdx.files.local(prefix + namePalette + "-" + d + "-Q.gif"), pixmaps, 20);
            }
        }
    }

	public static void main(String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication() {
		return new Lwjgl3Application(new QualityVideoConvertDemo(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setTitle("Anim8-GDX QualityPalette Video Convert Writer");
		configuration.setWindowedMode(256, 256);
		configuration.useVsync(true);
		configuration.setIdleFPS(20);
		return configuration;
	}
}

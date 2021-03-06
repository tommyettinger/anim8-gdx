package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.TimeUtils;
import com.github.tommyettinger.anim8.*;

/**
 * This takes two multiple-frame images/videos and dithers both of them in all the ways this can before writing to GIF
 * and optionally PNG files. There is a 90-frame "Video Of A Market" by Olivier Polome,
 * <a href="https://www.pexels.com/video/video-of-a-market-4236787/">available freely at Pexels</a>, and a rotating
 * cartoon-style pixel-art monster animation (self-made, public domain).
 */
public class VideoConvertDemo extends ApplicationAdapter {
    private long startTime;
    private static final String name = "market";
    @Override
    public void create() {
        startTime = TimeUtils.millis();

        Gdx.files.local("images").mkdirs();
//		renderAPNG(); // comment this out if you aren't using the full-color animated PNGs, because this is slow.
//		renderPNG8();
        String[] names = new String[]{"-Analyzed", "-Haltonic", "-BW", "-Green"};
        int[][] palettes = new int[][]{
                null,
                PaletteReducer.HALTONIC,
                new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF},
                new int[]{0x00000000,
                        0x000000FF, 0x081820FF, 0x132C2DFF, 0x1E403BFF, 0x295447FF, 0x346856FF, 0x497E5BFF, 0x5E9463FF,
                        0x73AA69FF, 0x88C070FF, 0x9ECE88FF, 0xB4DCA0FF, 0xCAEAB8FF, 0xE0F8D0FF, 0xEFFBE7FF, 0xFFFFFFFF}
        };

        renderVideoGif(names, palettes);
        renderPixelGif(names, palettes);
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
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        AnimatedPNG apng = new AnimatedPNG();
        apng.setCompression(7);
        apng.setFlipY(false);
        apng.write(Gdx.files.local("images/AnimatedPNG-" + name + ".png"), pixmaps, 20);
    }
    
    public void renderPNG8() {
        Array<Pixmap> pixmaps = new Array<>(90);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        PNG8 png8 = new PNG8();
        png8.setPalette(new PaletteReducer());
        png8.palette.analyze(pixmaps, 144, 256);
        String namePalette = name + "-analyzed";
        // Haltonic palette
//        png8.palette = new PaletteReducer(); namePalette = name + "-Haltonic";
        //// BW
//        png8.palette = new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}); namePalette = name + "-BW";
        //// GB-16 Green
//        png8.palette = new PaletteReducer(new int[]{0x00000000, 
//                0x000000FF, 0x081820FF, 0x132C2DFF, 0x1E403BFF, 0x295447FF, 0x346856FF, 0x497E5BFF, 0x5E9463FF, 
//                0x73AA69FF, 0x88C070FF, 0x9ECE88FF, 0xB4DCA0FF, 0xCAEAB8FF, 0xE0F8D0FF, 0xEFFBE7FF, 0xFFFFFFFF});
//        namePalette = name + "-Green";
        png8.setFlipY(false);
        png8.setCompression(7);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + namePalette + "-None.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + namePalette + "-Diffusion.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + namePalette + "-Pattern.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + namePalette + "-GradientNoise.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + namePalette + "-BlueNoise.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + namePalette + "-ChaoticNoise.png"), pixmaps, 20);
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
        png8.write(Gdx.files.local("images/" + name + "/PNG8-" + namePalette + "-Scatter.png"), pixmaps, 20);
    }

    public void renderVideoGif(String[] names, int[][] palettes) {
        String name = "market";
        Array<Pixmap> pixmaps = new Array<>(true, 90, Pixmap.class);
        for (int i = 1; i <= 90; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".jpg")));
        }
        AnimatedGif gif = new AnimatedGif();
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            if(palettes[i] == null)
                gif.setPalette(new PaletteReducer(pixmaps));
            else
                gif.setPalette(new PaletteReducer(palettes[i]));

            gif.setFlipY(false);
            /*
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-None.gif"), pixmaps, 20);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-Diffusion.gif"), pixmaps, 20);
            */
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-Pattern.gif"), pixmaps, 20);
            /*
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-GradientNoise.gif"), pixmaps, 20);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-BlueNoise.gif"), pixmaps, 20);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-ChaoticNoise.gif"), pixmaps, 20);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-Scatter.gif"), pixmaps, 20);
             */
        }
//        String[] ns = new String[]{"-Analyzed", "-Haltonic", "-BW", "-Green"};
//        int[][] is = new int[][]{
//                null,
//                PaletteReducer.HALTONIC,
//                new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF},
//                new int[]{0x00000000,
//                0x000000FF, 0x081820FF, 0x132C2DFF, 0x1E403BFF, 0x295447FF, 0x346856FF, 0x497E5BFF, 0x5E9463FF,
//                0x73AA69FF, 0x88C070FF, 0x9ECE88FF, 0xB4DCA0FF, 0xCAEAB8FF, 0xE0F8D0FF, 0xEFFBE7FF, 0xFFFFFFFF}
//        };
//        namePalette = name + "-Analyzed";
        // Haltonic palette
//        gif.palette = new PaletteReducer(); namePalette = name + "-Haltonic";
        //// BW
//        gif.palette = new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}); namePalette = name + "-BW";
        //// GB-16 Green
//        gif.palette = new PaletteReducer(new int[]{0x00000000,
//                0x000000FF, 0x081820FF, 0x132C2DFF, 0x1E403BFF, 0x295447FF, 0x346856FF, 0x497E5BFF, 0x5E9463FF,
//                0x73AA69FF, 0x88C070FF, 0x9ECE88FF, 0xB4DCA0FF, 0xCAEAB8FF, 0xE0F8D0FF, 0xEFFBE7FF, 0xFFFFFFFF});
//        namePalette = name + "-Green";
    }

    public void renderPixelGif(String[] names, int[][] palettes) {
        String name = "tyrant";
        Array<Pixmap> pixmaps = new Array<>(true, 64, Pixmap.class);
        for (int i = 0; i < 64; i++) {
            pixmaps.add(new Pixmap(Gdx.files.internal(name + "/" + name + "_" + i + ".png")));
        }
        AnimatedGif gif = new AnimatedGif();
        String namePalette;
        for (int i = 0; i < names.length; i++) {
            namePalette = name + names[i];
            if(palettes[i] == null)
                gif.setPalette(new PaletteReducer(pixmaps));
            else
                gif.setPalette(new PaletteReducer(palettes[i]));

            gif.setFlipY(false);
            /*
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-None.gif"), pixmaps, 12);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-Diffusion.gif"), pixmaps, 12);
            */
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-Pattern.gif"), pixmaps, 12);
            /*
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-GradientNoise.gif"), pixmaps, 12);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-BlueNoise.gif"), pixmaps, 12);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-ChaoticNoise.gif"), pixmaps, 12);
            gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
            gif.write(Gdx.files.local("images/" + name + "/AnimatedGif-" + namePalette + "-Scatter.gif"), pixmaps, 12);
            */
        }
    }

	public static void main(String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication() {
		return new Lwjgl3Application(new VideoConvertDemo(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setTitle("Anim8-GDX Video Convert Writer");
		configuration.setWindowedMode(256, 256);
		configuration.useVsync(true);
		configuration.setIdleFPS(20);
		return configuration;
	}

}

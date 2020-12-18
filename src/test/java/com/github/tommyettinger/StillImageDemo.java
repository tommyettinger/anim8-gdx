package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.anim8.AnimatedGif;
import com.github.tommyettinger.anim8.Dithered;
import com.github.tommyettinger.anim8.PNG8;
import com.github.tommyettinger.anim8.PaletteReducer;

import java.io.IOException;

/**
 * Currently just dithers a few pictures (my cat, then from Wikimedia Commons, a tropical frog, a public domain
 * landscape painting, and a remaster of the Mona Lisa) as a still GIF, PNG8, and full-color PNG.
 */
public class StillImageDemo extends ApplicationAdapter {
    private long startTime, total = 0;
    @Override
    public void create() {
        //Gdx.app.setLogLevel(Application.LOG_DEBUG);
        startTime = System.currentTimeMillis();

        Gdx.files.local("images").mkdirs();
        for(String name : new String[]{"Mona_Lisa", "Cat", "Frog", "Landscape",}) {
			renderPNG8(name);
			renderGif(name);
//			renderGifHS(name);
//			renderPNG(name);
		}
		System.out.println("Analyzed all " + total + " images in " + (System.currentTimeMillis() - startTime) + " ms");
        Gdx.app.exit();
    }
    
    @Override
    public void render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(Gdx.gl.GL_COLOR_BUFFER_BIT);
    }

    public void renderPNG8(String name) {
		PNG8 png8 = new PNG8();
		png8.setFlipY(false);
		png8.setCompression(7);
		Pixmap pixmap = new Pixmap(Gdx.files.classpath(name + ".jpg"));
		// black and white
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}));
		// gb palette
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF}));
		PaletteReducer reducer = new PaletteReducer();
		for (int count : new int[]{3, 5, 8, 16, 32, 64, 256}) {
//			reducer.analyzeNQ(pixmap, count); //Dithered all 392 images in 42784 ms
			reducer.analyze(pixmap, 400 - count, count); //Dithered all 392 images in 42237 ms
			
//			reducer.analyze(pixmap, 10000 / count + count * 4, count);
			png8.setPalette(reducer);
			png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
			png8.write(Gdx.files.local("images/" + name + "-PNG8-Pattern-" + count + ".png"), pixmap, false, true);
			png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
			png8.write(Gdx.files.local("images/" + name + "-PNG8-None-" + count + ".png"), pixmap, false, true);
			png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
			png8.write(Gdx.files.local("images/" + name + "-PNG8-Gradient-" + count + ".png"), pixmap, false, true);
			png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
			png8.write(Gdx.files.local("images/" + name + "-PNG8-Diffusion-" + count + ".png"), pixmap, false, true);
			png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
			png8.write(Gdx.files.local("images/" + name + "-PNG8-BlueNoise-" + count + ".png"), pixmap, false, true);
			png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
			png8.write(Gdx.files.local("images/" + name + "-PNG8-ChaoticNoise-" + count + ".png"), pixmap, false, true);
			png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
			png8.write(Gdx.files.local("images/" + name + "-PNG8-Scatter-" + count + ".png"), pixmap, false, true);
			total += 1;
		}
	}

    public void renderPNG(String name) {
        PixmapIO.PNG png = new PixmapIO.PNG();
        png.setFlipY(false);
        png.setCompression(7);
		try {
			png.write(Gdx.files.local("images/"+name+"-PNG-" + startTime + ".png"), new Pixmap(Gdx.files.classpath(name+".jpg")));
		} catch (IOException e) {
			Gdx.app.error("anim8", e.getMessage());
		}
	}

    public void renderGif(String name) {
        Array<Pixmap> pixmaps = Array.with(new Pixmap(Gdx.files.classpath(name+".jpg")));
        AnimatedGif gif = new AnimatedGif();
        gif.setFlipY(false);
        PaletteReducer reducer = new PaletteReducer();
		for (int count : new int[]{3, 5, 8, 16, 32, 64, 256}) {
//			reducer.analyzeNQ(pixmaps, count); //Dithered all 392 images in 42784 ms
			reducer.analyze(pixmaps, 400 - count, count); //Dithered all 392 images in 42237 ms

			gif.setPalette(reducer);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
			gif.write(Gdx.files.local("images/" + name + "-Gif-Pattern-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-None-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-Gradient-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
			gif.write(Gdx.files.local("images/" + name + "-Gif-Diffusion-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-BlueNoise-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-ChaoticNoise-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
			gif.write(Gdx.files.local("images/" + name + "-Gif-Scatter-" + count + ".gif"), pixmaps, 1);
			total += 1;
		}
    }
    public void renderGifHS(String name) {
        Array<Pixmap> pixmaps = Array.with(new Pixmap(Gdx.files.classpath(name+".jpg")));
        AnimatedGif gif = new AnimatedGif();
        gif.setFlipY(false);
        PaletteReducer reducer = new PaletteReducer();
		for (int count : new int[]{3, 5, 8, 16, 32, 64, 256}) {
//			reducer.analyzeNQ(pixmaps, count); //Dithered all 392 images in 42784 ms
			reducer.analyze(pixmaps, 400 - count, count); //Dithered all 392 images in 42237 ms

			gif.setPalette(reducer.hueShift());
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
			gif.write(Gdx.files.local("images/" + name + "-Gif-HS-Pattern-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-HS-None-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-HS-Gradient-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
			gif.write(Gdx.files.local("images/" + name + "-Gif-HS-Diffusion-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-HS-BlueNoise-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
			gif.write(Gdx.files.local("images/" + name + "-Gif-HS-ChaoticNoise-" + count + ".gif"), pixmaps, 1);
			gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
			gif.write(Gdx.files.local("images/" + name + "-Gif-HS-Scatter-" + count + ".gif"), pixmaps, 1);
			total += 1;
		}
    }

	public static void main(String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication() {
		return new Lwjgl3Application(new StillImageDemo(), getDefaultConfiguration());
	}

	private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
		Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
		configuration.setTitle("Anim8-GDX Still Image Demo");
		configuration.setWindowedMode(256, 256);
		configuration.useVsync(true);
		configuration.setIdleFPS(20);
		return configuration;
	}

}

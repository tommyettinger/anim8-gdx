package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.anim8.*;

/**
 * Currently just dithers a few pictures (my cat, then from Wikimedia Commons, a tropical frog, a public domain
 * landscape painting, a remastered Mona Lisa, and a public domain baroque painting) as a still GIF, PNG8, and
 * full-color PNG.
 * <br>
 * Analyzed all 84 images in   59690 ms
 * (later, with more and different images/dithers)
 * Analyzed all 120 images in 103791 ms
 */
public class StillImageDemo extends ApplicationAdapter {
	private long total = 0;
	public Dithered.DitherAlgorithm[] ALGORITHMS =
//			new Dithered.DitherAlgorithm[]{
//					Dithered.DitherAlgorithm.DODGY
//			};
			Dithered.DitherAlgorithm.ALL;
    @Override
    public void create() {
        //Gdx.app.setLogLevel(Application.LOG_DEBUG);
		long startTime = System.currentTimeMillis();

        Gdx.files.local("images").mkdirs();
//        for(String name : new String[]{"Mona_Lisa.jpg"}) {
        for(String name : new String[]{"Mona_Lisa.jpg", "Earring.jpg", "Cat.jpg", "Frog.jpg", "Landscape.jpg", "Pixel_Art.png",}) {
//        for(String name : new String[]{"Mona_Lisa.jpg", "Earring.jpg", "Cat.jpg", "Frog.jpg", "Landscape.jpg", "Pixel_Art.png", "Anemone.png",}) {
			System.out.println("Rendering PNG8 for " + name);
			renderPNG8(name);
			System.out.println("Rendering GIF for " + name);
			renderGif(name);
			System.out.println("Rendering PNG for " + name);
			renderPNG(name);
		}
		System.out.println("Analyzed all " + total + " images in " + (System.currentTimeMillis() - startTime) + " ms");
        Gdx.app.exit();
    }
    
    @Override
    public void render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(Gdx.gl.GL_COLOR_BUFFER_BIT);
    }

    public void renderPNG8(String filename) {
		PNG8 png8 = new PNG8();
		png8.setFlipY(false);
		png8.setCompression(2);
		FileHandle file = Gdx.files.classpath(filename);
		String name = file.nameWithoutExtension();
		Pixmap pixmap = new Pixmap(file);
		// black and white
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}));
		// gb palette
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF}));
		PaletteReducer regular = new PaletteReducer(), quality = new QualityPalette(), reducer;
		final String[] types = {"", "H", "Q", "R"};
		for(String type : types) {
			reducer = (type.isEmpty()) ? regular : quality;
			for (int count : new int[]{16, 31, 255}) {
				if ("H".equals(type)) {
					reducer.analyzeHueWise(pixmap, 100, count + 1);
				} else if ("R".equals(type)) {
					reducer.analyzeReductive(pixmap, 300, count + 1);
				} else {
					reducer.analyze(pixmap, 100, count + 1);
				}

				png8.setPalette(reducer);
				for(Dithered.DitherAlgorithm d : ALGORITHMS){
					png8.setDitherAlgorithm(d);
					png8.write(Gdx.files.local("images/png/" + name + "-PNG8-" + d + "-" + type + count + ".png"), pixmap, false, true);
				}
				total++;
			}
		}
		quality.exact(new int[]{0, 255, -1});
		png8.setPalette(quality);
		for(Dithered.DitherAlgorithm d : ALGORITHMS){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-" + d + "-BW.png"), pixmap, false, true);
		}

		quality.exact(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF});
		for(Dithered.DitherAlgorithm d : ALGORITHMS){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-" + d + "-GB.png"), pixmap, false, true);
		}

		quality.exact(new int[]{0x00000000, 0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF});
		for(Dithered.DitherAlgorithm d : ALGORITHMS){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-" + d + "-DB8.png"), pixmap, false, true);
		}

		quality.exact(new int[]{0x00000000, 0x6DB5BAFF, 0x26544CFF, 0x76AA3AFF, 0xFBFDBEFF, 0xD23C4FFF, 0x2B1328FF, 0x753D38FF, 0xEFAD5FFF});
		for(Dithered.DitherAlgorithm d : ALGORITHMS){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-" + d + "-Prospecal.png"), pixmap, false, true);
		}

		quality.setDefaultPalette();
		for(Dithered.DitherAlgorithm d : ALGORITHMS){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-" + d + "-Default.png"), pixmap, false, true);
		}
		total++;
	}

    public void renderPNG(String name) {
        FastPNG png = new FastPNG();
        png.setFlipY(false);
        png.setCompression(2);
		FileHandle handle = Gdx.files.classpath(name);
        png.write(Gdx.files.local("images/png/"+handle.nameWithoutExtension()+"-PNG.png"), new Pixmap(handle));
    }

    public void renderGif(String filename) {
		FileHandle file = Gdx.files.classpath(filename);
		String name = file.nameWithoutExtension();
		Array<Pixmap> pixmaps = Array.with(new Pixmap(file));
        AnimatedGif gif = new AnimatedGif();
        gif.setFlipY(false);
		PaletteReducer regular = new PaletteReducer(), quality = new QualityPalette(), reducer;
		final String[] types = {"", "H", "Q", "R"};
		for(String type : types) {
			reducer = (type.isEmpty()) ? regular : quality;
			for (int count : new int[]{16, 31, 255}) {
				if ("H".equals(type)) {
					reducer.analyzeHueWise(pixmaps, 100, count + 1);
				} else if ("R".equals(type)) {
					reducer.analyzeReductive(pixmaps, 300, count + 1);
				} else {
					reducer.analyze(pixmaps, 100, count + 1);
				}
				gif.setPalette(reducer);
				for(Dithered.DitherAlgorithm d : ALGORITHMS) {
					gif.setDitherAlgorithm(d);
					gif.write(Gdx.files.local("images/gif/" + name + "-Gif-" + d + "-" + type + count + ".gif"), pixmaps, 1);
				}
				total++;
			}
		}
		quality.exact(new int[]{0, 255, -1});
		gif.setPalette(quality);
		for(Dithered.DitherAlgorithm d : ALGORITHMS) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-" + d + "-BW.gif"), pixmaps, 1);
		}

		quality.exact(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF});
		for(Dithered.DitherAlgorithm d : ALGORITHMS) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-" + d + "-GB.gif"), pixmaps, 1);
		}

		quality.exact(new int[]{0x00000000, 0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF});
		for(Dithered.DitherAlgorithm d : ALGORITHMS) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-" + d + "-DB8.gif"), pixmaps, 1);
		}

		quality.exact(new int[]{0x00000000, 0x6DB5BAFF, 0x26544CFF, 0x76AA3AFF, 0xFBFDBEFF, 0xD23C4FFF, 0x2B1328FF, 0x753D38FF, 0xEFAD5FFF});
		for(Dithered.DitherAlgorithm d : ALGORITHMS) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-" + d + "-Prospecal.gif"), pixmaps, 1);
		}

		quality.setDefaultPalette();
		for(Dithered.DitherAlgorithm d : ALGORITHMS) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-" + d + "-Default.gif"), pixmaps, 1);
		}

		total++;
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

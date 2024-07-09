package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.anim8.Dithered;
import com.github.tommyettinger.anim8.FastGif;
import com.github.tommyettinger.anim8.FastPNG;
import com.github.tommyettinger.anim8.FastPNG8;
import com.github.tommyettinger.anim8.FastPalette;

/**
 * Currently just dithers a few pictures (my cat, then from Wikimedia Commons, a tropical frog, a public domain
 * landscape painting, a remastered Mona Lisa, and some sprites I made) as a still GIF, PNG8, and full-color PNG.
 * <br>
 * Analyzed all 48 images in 36415 ms
 */
public class FastStillImageDemo extends ApplicationAdapter {
	private long total = 0;
    @Override
    public void create() {
        //Gdx.app.setLogLevel(Application.LOG_DEBUG);
		long startTime = System.currentTimeMillis();

        Gdx.files.local("images").mkdirs();
//        for(String name : new String[]{"Mona_Lisa.jpg", "Cat.jpg", "Frog.jpg", "Landscape.jpg", "Pixel_Art.png",}) {
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
		FastPNG8 png8 = new FastPNG8();
		png8.setFlipY(false);
		png8.setCompression(2);
		FileHandle file = Gdx.files.classpath(filename);
		String name = file.nameWithoutExtension();
		Pixmap pixmap = new Pixmap(file);
		// black and white
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}));
		// gb palette
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF}));
		FastPalette reducer = new FastPalette();
//		final String[] types = {"", "H"};
//		for(String type : types)
		String type = "";
		{
			for (int count : new int[]{16, 31, 255}) {
				reducer.analyze(pixmap, 100, count + 1);

				png8.setPalette(reducer);
				for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL){
					png8.setDitherAlgorithm(d);
					png8.write(Gdx.files.local("images/png/" + name + "-PNG8-F-" + d + "-" + type + count + ".png"), pixmap, false, true);
				}
				total++;
			}
		}
		reducer.exact(new int[]{0, 255, -1});
		png8.setPalette(reducer);
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-F-" + d + "-BW.png"), pixmap, false, true);
		}

		reducer.exact(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF});
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-F-" + d + "-GB.png"), pixmap, false, true);
		}

		reducer.exact(new int[]{0x00000000, 0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF});
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-F-" + d + "-DB8.png"), pixmap, false, true);
		}

		reducer.exact(new int[]{0x00000000, 0x6DB5BAFF, 0x26544CFF, 0x76AA3AFF, 0xFBFDBEFF, 0xD23C4FFF, 0x2B1328FF, 0x753D38FF, 0xEFAD5FFF});
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-F-" + d + "-Prospecal.png"), pixmap, false, true);
		}

		reducer.exact(FastPalette.AURORA);
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-F-" + d + "-Aurora.png"), pixmap, false, true);
		}

		reducer.setDefaultPalette();
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL){
			png8.setDitherAlgorithm(d);
			png8.write(Gdx.files.local("images/png/" + name + "-PNG8-F-" + d + "-Default.png"), pixmap, false, true);
		}

		total++;
	}

    public void renderPNG(String name) {
        FastPNG png = new FastPNG();
        png.setFlipY(false);
        png.setCompression(2);
		FileHandle handle = Gdx.files.classpath(name);
		png.write(Gdx.files.local("images/png/"+handle.nameWithoutExtension()+"-PNG-Fast.png"), new Pixmap(handle));
	}

    public void renderGif(String filename) {
		FileHandle file = Gdx.files.classpath(filename);
		String name = file.nameWithoutExtension();
		Array<Pixmap> pixmaps = Array.with(new Pixmap(file));
		FastGif gif = new FastGif();
        gif.setFlipY(false);
        FastPalette reducer = new FastPalette();
		String type = "";
		{
			for (int count : new int[]{16, 31, 255}) {
				reducer.analyze(pixmaps, 300, count + 1);

				gif.setPalette(reducer);
				for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL) {
					gif.setDitherAlgorithm(d);
					gif.write(Gdx.files.local("images/gif/" + name + "-Gif-F-" + d + "-" + type + count + ".gif"), pixmaps, 1);
				}
				total++;
			}
		}
		reducer.exact(new int[]{0, 255, -1});
		gif.setPalette(reducer);
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-F-" + d + "-BW.gif"), pixmaps, 1);
		}

		reducer.exact(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF});
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-F-" + d + "-GB.gif"), pixmaps, 1);
		}

		reducer.exact(new int[]{0x00000000, 0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF});
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-F-" + d + "-DB8.gif"), pixmaps, 1);
		}

		reducer.exact(new int[]{0x00000000, 0x6DB5BAFF, 0x26544CFF, 0x76AA3AFF, 0xFBFDBEFF, 0xD23C4FFF, 0x2B1328FF, 0x753D38FF, 0xEFAD5FFF});
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-F-" + d + "-Prospecal.gif"), pixmaps, 1);
		}

		reducer.exact(FastPalette.AURORA);
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-F-" + d + "-Aurora.gif"), pixmaps, 1);
		}

		reducer.setDefaultPalette();
		for(Dithered.DitherAlgorithm d : Dithered.DitherAlgorithm.ALL) {
			gif.setDitherAlgorithm(d);
			gif.write(Gdx.files.local("images/gif/" + name + "-Gif-F-" + d + "-Default.gif"), pixmaps, 1);
		}

		total++;
	}

	public static void main(String[] args) {
		createApplication();
	}

	private static Lwjgl3Application createApplication() {
		return new Lwjgl3Application(new FastStillImageDemo(), getDefaultConfiguration());
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

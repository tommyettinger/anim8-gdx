package com.github.tommyettinger;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
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
 * landscape painting, a remastered Mona Lisa, and a smooth noise texture) as a still GIF, PNG8, and full-color PNG.
 */
public class StillImageDemo extends ApplicationAdapter {
    private long startTime, total = 0;
    @Override
    public void create() {
        //Gdx.app.setLogLevel(Application.LOG_DEBUG);
        startTime = System.currentTimeMillis();

        Gdx.files.local("images").mkdirs();
//        for(String name : new String[]{"Mona_Lisa.jpg", "Cat.jpg", "Frog.jpg", "Landscape.jpg", "Pixel_Art.png",}) {
        for(String name : new String[]{"Mona_Lisa.jpg", "Earring.jpg", "Cat.jpg", "Frog.jpg", "Landscape.jpg", "Pixel_Art.png",}) {
//        for(String name : new String[]{"Mona_Lisa.jpg", "Earring.jpg", "Cat.jpg", "Frog.jpg", "Landscape.jpg", "Pixel_Art.png", "Anemone.png",}) {
			System.out.println("Rendering PNG8 for " + name);
			renderPNG8(name);
			System.out.println("Rendering GIF for " + name);
			renderGif(name);
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

    public void renderPNG8(String filename) {
		PNG8 png8 = new PNG8();
		png8.setFlipY(false);
		png8.setCompression(7);
		FileHandle file = Gdx.files.classpath(filename);
		String name = file.nameWithoutExtension();
		Pixmap pixmap = new Pixmap(file);
		// black and white
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}));
		// gb palette
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF}));
		PaletteReducer reducer = new PaletteReducer();
		final String[] types = {"", "H"};
		for(String type : types) {
			for (int count : new int[]{16, 31, 255}) {
				if(type.isEmpty())
					reducer.analyze(pixmap, 100, count + 1);
				else
					reducer.analyzeHueWise(pixmap, 100, count + 1);

				png8.setPalette(reducer);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Pattern-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-None-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Gradient-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Roberts-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Diffusion-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-BlueNoise-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-ChaoticNoise-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Scatter-" + type + count + ".png"), pixmap, false, true);
				png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
				png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Neue-" + type + count + ".png"), pixmap, false, true);
				total += 1;
			}
		}
		reducer.exact(new int[]{0, 255, -1});
		png8.setPalette(reducer);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Pattern-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-None-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Gradient-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Roberts-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Diffusion-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-BlueNoise-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-ChaoticNoise-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Scatter-BW.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Neue-BW.png"), pixmap, false, true);

		reducer.exact(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF});
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Pattern-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-None-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Gradient-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Roberts-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Diffusion-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-BlueNoise-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-ChaoticNoise-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Scatter-GB.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Neue-GB.png"), pixmap, false, true);

		reducer.exact(new int[]{0x00000000, 0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF});
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Pattern-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-None-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Gradient-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Roberts-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Diffusion-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-BlueNoise-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-ChaoticNoise-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Scatter-DB8.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Neue-DB8.png"), pixmap, false, true);

		reducer.setDefaultPalette();
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Pattern-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-None-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Gradient-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Roberts-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Diffusion-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-BlueNoise-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-ChaoticNoise-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Scatter-Default.png"), pixmap, false, true);
		png8.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		png8.write(Gdx.files.local("images/png/" + name + "-PNG8-Neue-Default.png"), pixmap, false, true);

		total += 1;
	}

    public void renderPNG(String name) {
        PixmapIO.PNG png = new PixmapIO.PNG();
        png.setFlipY(false);
        png.setCompression(7);
		FileHandle handle = Gdx.files.classpath(name);
		try {
			png.write(Gdx.files.local("images/png/"+handle.nameWithoutExtension()+"-PNG.png"), new Pixmap(handle));
		} catch (IOException e) {
			Gdx.app.error("anim8", e.getMessage());
		}
	}

    public void renderGif(String filename) {
		FileHandle file = Gdx.files.classpath(filename);
		String name = file.nameWithoutExtension();
		Array<Pixmap> pixmaps = Array.with(new Pixmap(file));
        AnimatedGif gif = new AnimatedGif();
        gif.setFlipY(false);
        PaletteReducer reducer = new PaletteReducer();
		final String[] types = {"", "H"};
		for(String type : types) {
			for (int count : new int[]{16, 31, 255}) {
				if(type.isEmpty())
					reducer.analyze(pixmaps, 100, count + 1);
				else
					reducer.analyzeHueWise(pixmaps, 100, count + 1);

				gif.setPalette(reducer);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Pattern-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-None-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Gradient-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Roberts-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Diffusion-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-BlueNoise-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-ChaoticNoise-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Scatter-" + type + count + ".gif"), pixmaps, 1);
				gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
				gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Neue-" + type + count + ".gif"), pixmaps, 1);
				total += 1;
			}
		}
		reducer.exact(new int[]{0, 255, -1});
		gif.setPalette(reducer);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Pattern-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-None-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Gradient-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Roberts-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Diffusion-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-BlueNoise-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-ChaoticNoise-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Scatter-BW.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Neue-BW.gif"), pixmaps, 1);

		reducer.exact(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF});
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Pattern-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-None-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Gradient-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Roberts-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Diffusion-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-BlueNoise-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-ChaoticNoise-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Scatter-GB.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Neue-GB.gif"), pixmaps, 1);

		reducer.exact(new int[]{0x00000000, 0x000000FF, 0x55415FFF, 0x646964FF, 0xD77355FF, 0x508CD7FF, 0x64B964FF, 0xE6C86EFF, 0xDCF5FFFF});
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Pattern-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-None-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Gradient-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Roberts-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Diffusion-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-BlueNoise-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-ChaoticNoise-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Scatter-DB8.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Neue-DB8.gif"), pixmaps, 1);

		reducer.setDefaultPalette();
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Pattern-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NONE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-None-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.GRADIENT_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Gradient-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.ROBERTS);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Roberts-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.DIFFUSION);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Diffusion-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.BLUE_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-BlueNoise-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.CHAOTIC_NOISE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-ChaoticNoise-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.SCATTER);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Scatter-Default.gif"), pixmaps, 1);
		gif.setDitherAlgorithm(Dithered.DitherAlgorithm.NEUE);
		gif.write(Gdx.files.local("images/gif/" + name + "-Gif-Neue-Default.gif"), pixmaps, 1);

		total += 1;
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

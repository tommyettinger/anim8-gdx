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
 * Currently just dithers a picture of my cat (Satchmo) as a still GIF, PNG8, and full-color PNG.
 */
public class StillImageDemo extends ApplicationAdapter {
    private long startTime;
    private int[] palette = new int[64];
    @Override
    public void create() {
        //Gdx.app.setLogLevel(Application.LOG_DEBUG);
        startTime = System.currentTimeMillis();
        
        long state = 0x123456789L;

        PaletteReducer reducer = new PaletteReducer();
        for (int i = 1; i < palette.length; i++) {
            // SquidLib's DiverRNG.randomize()
            state = (state = ((state = (state ^ (state << 41 | state >>> 23) ^ (state << 17 | state >>> 47) ^ 0xD1B54A32D192ED03L) * 0xAEF17502108EF2D9L) ^ state >>> 43 ^ state >>> 31 ^ state >>> 23) * 0xDB4F0B9175AE2165L) ^ state >>> 28;
            
            int idx = (int) ((state>>>1) % 255) + 1;
            palette[i] = reducer.paletteArray[idx];
        }

        Gdx.files.local("images").mkdirs();
        for(String name : new String[]{"Cat", "Frog", "Landscape", "Mona_Lisa"}) {
			renderPNG8(name);
			renderGif(name);
			renderPNG(name);
		}
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
        png8.setPalette(new PaletteReducer(palette));
        png8.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        // black and white
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}));
        // gb palette
//        png8.setPalette(new PaletteReducer(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF}));
        png8.setCompression(7);
        png8.write(Gdx.files.local("images/"+name+"-PNG8-" + startTime + ".png"), new Pixmap(Gdx.files.classpath(name+".jpg")), false, true);
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
        gif.setPalette(new PaletteReducer(palette));
        gif.setDitherAlgorithm(Dithered.DitherAlgorithm.PATTERN);
        // black and white
//        gif.setPalette(new PaletteReducer(new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF}));
        // gb palette
//        gif.setPalette(new PaletteReducer(new int[]{0x00000000, 0x081820FF, 0x346856FF, 0x88C070FF, 0xE0F8D0FF}));
        gif.write(Gdx.files.local("images/"+name+"-Gif-" + startTime + ".gif"), pixmaps, 1);
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

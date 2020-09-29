package com.github.tommyettinger;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.github.tommyettinger.anim8.PNG8;

public class PaletteTwister extends ApplicationAdapter {
    public static final int SCREEN_WIDTH = 1280;
    public static final int SCREEN_HEIGHT = 720;
    protected SpriteBatch batch;
    protected Viewport screenView;
    protected Texture screenTexture;
    private int index = 1;
    private final int modes = 10;
    private float strength = 0.5f;
    public static void main(String[] arg) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Palette Twister");
        config.setWindowedMode(SCREEN_WIDTH, SCREEN_HEIGHT);
        config.setIdleFPS(10);
        config.useVsync(true);
        config.setResizable(true);
        final PaletteTwister app = new PaletteTwister();
        config.setWindowListener(new Lwjgl3WindowAdapter() {
            @Override
            public void filesDropped(String[] files) {
                if (files != null && files.length > 0) {
                    if (files[0].endsWith(".png"))
                        app.load(files[0]);
                }
            }
        });

        new Lwjgl3Application(app, config);
    }

    public void load(String name) {
        FileHandle out = Gdx.files.local(System.currentTimeMillis() + ".png"), in;
        //// loads a file by its full path, which we get via drag+drop
        if (Gdx.files.internal(name).exists())
            in = Gdx.files.internal(name);
        else
            in = Gdx.files.absolute(name);
        switch (index){
            case 0:
                PNG8.centralizePalette(in, out, strength);
                break;
            case 1:
                PNG8.editPalette(in, out, Interpolation.circle);
                break;
            case 2:
                PNG8.editPalette(in, out, Interpolation.pow2);
                break;
            case 3:
                PNG8.editPalette(in, out, Interpolation.pow3);
                break;
            case 4:
                PNG8.editPalette(in, out, Interpolation.pow4);
                break;
            case 5:
                PNG8.editPalette(in, out, Interpolation.pow5);
                break;
            case 6:
                PNG8.editPalette(in, out, Interpolation.exp5);
                break;
            case 7:
                PNG8.editPalette(in, out, Interpolation.exp10);
                break;
            case 8:
                PNG8.editPalette(in, out, Interpolation.smooth);
                break;
            case 9:
                PNG8.editPalette(in, out, Interpolation.smoother);
                break;
        }
        screenTexture = new Texture(out);
    }

    @Override
    public void create() {
        batch = new SpriteBatch();
        screenView = new ScreenViewport();
        screenView.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        batch.enableBlending();
        Gdx.input.setInputProcessor(inputProcessor());

        load("images/Cat-PNG8-Scatter-256.png");
    }


    @Override
    public void render() {
        Gdx.gl.glClearColor(0.4f, 0.4f, 0.4f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        batch.setProjectionMatrix(screenView.getCamera().combined);
        batch.begin();
        if(screenTexture != null)
            batch.draw(screenTexture, 0, 0);
        batch.end();
    }

    @Override
    public void resize(int width, int height) {
        screenView.update(width, height, true);
    }

    public InputProcessor inputProcessor() {
        return new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                switch (keycode) {
                    case Input.Keys.C:
                        load("images/Cat-PNG8-Scatter-256.png");
                        break;
                    case Input.Keys.M:
                        load("images/Mona_Lisa-PNG8-Scatter-256.png");
                        break;
                    case Input.Keys.L:
                        load("images/Landscape-PNG8-Scatter-256.png");
                        break;
                    case Input.Keys.F:
                        load("images/Frog-PNG8-Scatter-256.png");
                        break;
                    case Input.Keys.G:
                        load("images/PNG8-green-pattern.png");
                        break;
                    case Input.Keys.P:
                        load("Pixel_Art.png");
                        break;
                    case Input.Keys.S:
                        System.out.println(index + ", " + strength);
                        break;
                    case Input.Keys.ENTER:
                    case Input.Keys.PLUS:
                    case Input.Keys.EQUALS:
                        index = (index + 1) % modes;
                        break;
                    case Input.Keys.MINUS:
                        index = (index + modes - 1) % modes;
                        break;
                    case Input.Keys.UP:
                        strength = Math.min(strength + 0.05f, 1f);
                        break;
                    case Input.Keys.DOWN:
                        strength = Math.max(strength - 0.05f, 0f);
                        break;
                    case Input.Keys.Q:
                    case Input.Keys.ESCAPE:
                        Gdx.app.exit();
                        break;
                }
                return true;
            }
        };
    }
}

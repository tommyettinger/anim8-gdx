package com.github.tommyettinger;

import com.badlogic.gdx.*;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.utils.UIUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.github.tommyettinger.anim8.PNG8;
import com.github.tommyettinger.anim8.PaletteReducer;

public class InteractiveReducer extends ApplicationAdapter {
    public static final int SCREEN_WIDTH = 1280;
    public static final int SCREEN_HEIGHT = 720;
    protected SpriteBatch batch;
    protected Viewport screenView;
    protected Texture screenTexture;
    protected BitmapFont font;
    protected PaletteReducer reducer;
    protected PNG8 png8;
    private int[] palette;
    private Pixmap p0, p;
    private int index = 1;
    private float strength = 1f;

    public static void main(String[] arg) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Palette Reducer");
        config.setWindowedMode(SCREEN_WIDTH, SCREEN_HEIGHT);
        config.setIdleFPS(10);
        config.useVsync(true);
        config.setResizable(true);
        final InteractiveReducer app = new InteractiveReducer();
        config.setWindowListener(new Lwjgl3WindowAdapter() {
            @Override
            public void filesDropped(String[] files) {
                if (files != null && files.length > 0) {
                    if (files[0].endsWith(".png") || files[0].endsWith(".jpg") || files[0].endsWith(".jpeg"))
                        app.load(files[0]);
                    else if(files[0].endsWith("hex"))
                        app.loadPalette(files[0]);
                }
            }
        });

        new Lwjgl3Application(app, config);
    }

    public void load(String name) {
        if(p0 != null) p0.dispose();
        //// loads a file by its full path, which we get via drag+drop
        if (Gdx.files.internal(name).exists())
            p0 = new Pixmap(Gdx.files.internal(name));
        else
            p0 = new Pixmap(Gdx.files.absolute(name));
        if(p != null) p.dispose();
        p = new Pixmap(this.p0.getWidth(), this.p0.getHeight(), Pixmap.Format.RGBA8888);
        screenTexture = new Texture(p);
        refresh();
    }
    public void loadPalette(String name) {
        try {
            if(name == null || name.isEmpty()) return;
            FileHandle fh = Gdx.files.absolute(name);
            String text;
            if(fh.exists() && "hex".equals(fh.extension()))
                text = fh.readString();
            else
                return;
            int start = 0, end = 6, len = text.length();
            int gap = (text.charAt(7) == '\n') ? 8 : 7;
            int sz = ((len + 2) / gap);
            palette = new int[sz + 1];
            for (int i = 1; i <= sz; i++) {
                palette[i] = Integer.parseInt(text.substring(start, end), 16) << 8 | 0xFF;
                start += gap;
                end += gap;
            }
            reducer.exact(palette);
        } catch (GdxRuntimeException e) {
            e.printStackTrace();
        }
    }


    public void refresh(){
        p.drawPixmap(this.p0, 0, 0);
        switch (index) {
            case 0:
                reducer.reduceSolid(p);
                break;
            case 1:
                reducer.reduceBlueNoise(p);
                break;
            case 2:
                reducer.reduceChaoticNoise(p);
                break;
            case 3:
                reducer.reduceJimenez(p);
                break;
            case 4:
                reducer.reduceKnollRoberts(p);
                break;
            case 5:
                reducer.reduceKnoll(p);
                break;
            case 6:
                reducer.reduceSierraLite(p);
                break;
            case 7:
                reducer.reduceFloydSteinberg(p);
                break;
            case 8:
                reducer.reduceScatter(p);
                break;
            default:
                reducer.reduceNeue(p);
                break;
        }
        screenTexture.draw(p, 0, 0);
    }

    @Override
    public void create() {
        font = new BitmapFont();
        batch = new SpriteBatch();
        palette = new int[]{0x00000000, 0x000000FF, 0xFFFFFFFF};
        reducer = new PaletteReducer(palette);
//        reducer = new PaletteReducer(PaletteReducer.HALTONIC);
//        reducer.writePreloadFile(Gdx.files.local("haltonic.txt"));
        reducer.setDitherStrength(strength);
        png8 = new PNG8();
        png8.palette = reducer;
        png8.setFlipY(false);
        screenView = new ScreenViewport();
        screenView.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        batch.enableBlending();
        Gdx.input.setInputProcessor(inputProcessor());

        load("Cat.jpg");
    }


    @Override
    public void render() {
        Gdx.gl.glClearColor(0.4f, 0.4f, 0.4f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        refresh();
        batch.setProjectionMatrix(screenView.getCamera().combined);
        batch.begin();
        if(screenTexture != null)
            batch.draw(screenTexture, 0, 0);
        else {
            font.draw(batch, "Drag and drop an image file onto this window;", 20, 150);
            font.draw(batch, "a palette-reduced copy will be shown here.", 20, 120);
        }
        batch.end();
        Gdx.graphics.setTitle(Gdx.graphics.getFramesPerSecond() + " FPS");
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
                        load("Cat.jpg");
                        break;
                    case Input.Keys.M:
                        load("Mona_Lisa.jpg");
                        break;
                    case Input.Keys.L:
                        load("Landscape.jpg");
                        break;
                    case Input.Keys.F:
                        load("Frog.jpg");
                        break;
                    case Input.Keys.P:
                        load("Pixel_Art.png");
                        break;
                    case Input.Keys.A:
                        reducer.analyze(p0);
                        refresh();
                        break;
                    case Input.Keys.NUM_1:
                    case Input.Keys.NUM_2:
                    case Input.Keys.NUM_3:
                    case Input.Keys.NUM_4:
                    case Input.Keys.NUM_5:
                    case Input.Keys.NUM_6:
                    case Input.Keys.NUM_7:
                    case Input.Keys.NUM_8:
                    case Input.Keys.NUM_9:
                        if(UIUtils.ctrl()) {
                            if (UIUtils.shift()) {
                                int kc = (keycode - 6) * keycode;
                                reducer.analyzeHueWise(p0, 95 + kc, kc);
                            }
                            else {
                                int kc = keycode - 5;
                                reducer.analyzeHueWise(p0, 95 + kc, kc);
                            }

//                            if (UIUtils.shift())
//                                reducer.exact(PaletteReducer.HALTONIC, (keycode - 6) * keycode);
//                            else
//                                reducer.exact(PaletteReducer.HALTONIC, keycode - 5);

//                            if (UIUtils.shift())
//                                reducer.analyzeMC(p0, (keycode - 6) * keycode);
//                            else
//                                reducer.analyzeMC(p0, keycode - 5);
                        }
                        else {
                            if (UIUtils.shift()) {
                                int kc = (keycode - 6) * keycode;
                                reducer.analyze(p0, 25 + kc, kc);
                            }
                            else {
                                int kc = keycode - 5;
                                reducer.analyze(p0, 25 + kc, kc);
                            }
                        }
                        refresh();
                        break;
                    case Input.Keys.NUM_0:
                        if(UIUtils.ctrl())
                            reducer.exact(PaletteReducer.HALTONIC, 256);
                        else
                            reducer.analyze(p0, 150);
                        refresh();
                        break;
                    case Input.Keys.B:
                        reducer.exact(palette);
                        refresh();
                        break;
                    case Input.Keys.D:
                        reducer.setDefaultPalette();
                        refresh();
                        break;
                    case Input.Keys.LEFT:
                    case Input.Keys.LEFT_BRACKET:
                        index = (index + 9) % 10;
                        refresh();
                        break;
                    case Input.Keys.RIGHT:
                    case Input.Keys.RIGHT_BRACKET:
                        index = (index + 1) % 10;
                        refresh();
                        break;
                    case Input.Keys.UP:
                        reducer.setDitherStrength(strength = Math.min(strength + 0.05f, 2f));
                        refresh();
                        break;
                    case Input.Keys.DOWN:
                        reducer.setDitherStrength(strength = Math.max(strength - 0.05f, 0f));
                        refresh();
                        break;
                    case Input.Keys.S:
                        System.out.println("Algorithm selected: " + index + ", strength: " + strength
                                + ", colors: " + reducer.colorCount);
//                        System.out.println(Gdx.app.getJavaHeap() + " bytes in the Java heap.");
//                        System.out.println(Gdx.app.getNativeHeap() + " bytes in the native heap.");
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

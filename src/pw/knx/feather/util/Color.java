package pw.knx.feather.util;

/**
 * A simple objectual representation of a Color.
 * <p>
 * This particular implementation aims for total flexibility
 * and readability during usage. "Flexibility" refers to the wide
 * range of color formats accepted, such as RGB Floats, RGB Ints, HSB, and
 * hexadecimal (all with and without alpha).
 * <p>
 * This class was designed to assist in two different use cases:
 * 1 - As a convenient way to store a Color constant that could be
 * effortlessly converted into any format. (See: Builder methods)
 * 2 - As a static utility enabling quick conversion from any color
 * format into another. (See: this.convert())
 * <p>
 * The conversion algorithms found in this class are
 * relied on whenever possible in the rest of Feather, but Feather
 * will never require that the user directly uses this class to
 * interface with other features.
 *
 * @author KNOXDEV
 * @since 9/8/2016 21:41
 */
public class Color implements BufferUtils {

    /**
     * "Why is there a Singleton in here?", I hear you asking.
     * This Singleton exists to enable use case #2 of this class.
     * See class description and this.convert() for more info.
     */
    private static final Color INSTANCE = new Color();

    /**
     * Our color format for Hue, Saturation, & Brightness.
     * This representation is identical to the format supported
     * by java.awt.Color.
     */
    private float hue, saturation, brightness;

    /**
     * Our color format representing Red, Green, & Blue.
     * Represented by standard floats from 0.0 to 1.0.
     * <p>
     * "But why floats, when both HSB and Hex conversions both
     * require RGB as integers?", I once again hear you ask.
     * <p>
     * Because OpenGL will never require integer RGB unless it's
     * in the form of Hex anyways. In any case, one could argue
     * that floats are far more configuration and user-friendly.
     */
    private float red, green, blue;

    /**
     * In this class, we store alpha separately and persistently,
     * and it is not subject to being updated by conversions
     * (with the minor exception of SOME Hex values: See this.hex())
     */
    private float alpha = 1;

    /**
     * Our color stored as a simple 32-bit integer, with 8-bits
     * dedicated to each value in 'ARGB'. Please see this.hex()
     * to see how possibly passed alpha values are handled.
     */
    private int hex;

    /**
     * These booleans are used to keep track of what color
     * formats still need to be updated via conversion before
     * they can be used. This is tracked so that any given
     * conversion will not be performed until it is absolutely
     * necessary.
     */
    private boolean updateHSB, updateRGB, updateHex;


    /**
     * Restricting usage to this class requires the user to
     * utilize our builder functions at the bottom of this class
     */
    private Color() {
    }


    /**
     * Group Setters - Methods for setting color formats in bulk.
     * Mostly just calls the Individual Setters below
     */


    /**
     * Sets this Color object's Hue, Saturation, & Brightness
     * as floats from 0.0 to 1.0
     *
     * @param hue        The Color's hue
     * @param saturation The Color's saturation
     * @param brightness The Color's brightness
     * @return This color object, freshly set
     */
    public Color hsb(float hue, float saturation, float brightness) {
        return hue(hue).saturation(saturation).brightness(brightness);
    }

    /**
     * Sets this Color object's Hue, Saturation, Brightness, & Alpha
     * as floats from 0.0 to 1.0
     *
     * @param hue        The Color's hue
     * @param saturation The Color's saturation
     * @param brightness The Color's brightness
     * @param alpha      The Color's alpha
     * @return This color object, freshly set
     */
    public Color hsba(float hue, float saturation, float brightness, float alpha) {
        return hsb(hue, saturation, brightness).alpha(alpha);
    }

    /**
     * Sets this Color object's Red, Green, & Blue
     * as floats from 0.0 to 1.0
     *
     * @param red   The Color's red value
     * @param green The Color's green value
     * @param blue  The Color's blue value
     * @return This color object, freshly set
     */
    public Color rgb(float red, float green, float blue) {
        return red(red).green(green).blue(blue);
    }

    /**
     * Sets this Color object's Red, Green, Blue, & Alpha
     * as floats from 0.0 to 1.0
     *
     * @param red   The Color's red value
     * @param green The Color's green value
     * @param blue  The Color's blue value
     * @param alpha The Color's alpha value
     * @return This color object, freshly set
     */
    public Color rgba(float red, float green, float blue, float alpha) {
        return rgb(red, green, blue).alpha(alpha);
    }

    /**
     * Sets this Color object's Red, Green, & Blue
     * as integers from 0 to 255
     *
     * @param red   The Color's red value
     * @param green The Color's green value
     * @param blue  The Color's blue value
     * @return This color object, freshly set
     */
    public Color rgb(int red, int green, int blue) {
        return red(red).green(green).blue(blue);
    }

    /**
     * Sets this Color object's Red, Green, Blue, & Alpha
     * as integers from 0 to 255
     *
     * @param red   The Color's red value
     * @param green The Color's green value
     * @param blue  The Color's blue value
     * @param alpha The Color's alpha value
     * @return This color object, freshly set
     */
    public Color rgba(int red, int green, int blue, int alpha) {
        return rgb(red, green, blue).alpha(alpha);
    }

    /**
     * Sets this Color via Hex and Alpha
     *
     * @param hex   The Color as a 32-bit integer
     * @param alpha The Color's alpha between 0.0F and 1.0F
     * @return This color object, freshly set
     */
    public Color hexa(int hex, float alpha) {
        return hex(hex).alpha(alpha);
    }


    /**
     * Individual Setters - sets each individual color format value
     */


    /**
     * Sets the Color's Hue as a float
     *
     * @param hue The Hue between 0.0 and 1.0
     * @return This color object, freshly set
     */
    public Color hue(float hue) {
        onSetHSB();
        this.hue = clamp(hue, 0, 1);
        return this;
    }

    /**
     * Sets the Color's Saturation as a float
     *
     * @param saturation The Saturation between 0.0 and 1.0
     * @return This color object, freshly set
     */
    public Color saturation(float saturation) {
        onSetHSB();
        this.saturation = clamp(saturation, 0, 1);
        return this;
    }

    /**
     * Sets the Color's Brightness as a float
     *
     * @param brightness The Brightness between 0.0 and 1.0
     * @return This color object, freshly set
     */
    public Color brightness(float brightness) {
        onSetHSB();
        this.brightness = clamp(brightness, 0, 1);
        return this;
    }

    /**
     * Sets the Color's Red as a float
     *
     * @param red The Red between 0.0 and 1.0
     * @return This color object, freshly set
     */
    public Color red(float red) {
        onSetRGB();
        this.red = clamp(red, 0, 1);
        return this;
    }

    /**
     * Sets the Color's Green as a float
     *
     * @param green The Green between 0.0 and 1.0
     * @return This color object, freshly set
     */
    public Color green(float green) {
        onSetRGB();
        this.green = clamp(green, 0, 1);
        return this;
    }

    /**
     * Sets the Color's Blue as a float
     *
     * @param blue The Blue between 0.0 and 1.0
     * @return This color object, freshly set
     */
    public Color blue(float blue) {
        onSetRGB();
        this.blue = clamp(blue, 0, 1);
        return this;
    }

    /**
     * Sets the Color's Red as an integer
     *
     * @param red The Red between 0 and 255
     * @return This color object, freshly set
     */
    public Color red(int red) {
        return red((float) red / 255F);
    }

    /**
     * Sets the Color's Green as an integer
     *
     * @param green The Green between 0 and 255
     * @return This color object, freshly set
     */
    public Color green(int green) {
        return green((float) green / 255F);
    }

    /**
     * Sets the Color's Blue as an integer
     *
     * @param blue The Blue between 0 and 255
     * @return This color object, freshly set
     */
    public Color blue(int blue) {
        return blue((float) blue / 255F);
    }

    /**
     * Sets the Color's Alpha as a float
     *
     * @param alpha The Alpha between 0.0 and 1.0
     * @return This color object, freshly set
     */
    public Color alpha(float alpha) {
        this.alpha = clamp(alpha, 0, 1);
        return this;
    }

    /**
     * Sets the Color's Alpha as an integer
     *
     * @param alpha The Alpha between 0 and 255
     * @return This color object, freshly set
     */
    public Color alpha(int alpha) {
        return alpha((float) alpha / 255F);
    }

    /**
     * Sets this Color via a 32-bit ARGB integer.
     * <p>
     * This function will change your current Alpha
     * value to bits 24-32 if and ONLY if those bits
     * do not equal zero.
     *
     * @param hex ARGB color as a 32-bit integer
     * @return This color object, freshly set
     */
    public Color hex(int hex) {
        onSetHex();
        final int hexa = (hex >> 24 & 0xFF);
        if (hexa != 0)
            alpha(hexa);
        this.hex = hex;
        return this;
    }

    /**
     * Private shorthand for quick clamping of color input. Normally,
     * OpenGL will do this automatically with glColor, but because
     * this class may do conversions to other color formats later,
     * we have to do it now to prevent overflow/underflow from
     * corrupting the converted values.
     *
     * @param toClamp The value to clamp
     * @param low     The lower bound of our clamp operation
     * @param high    The upper bound of our clamp operation
     * @return the clamped float
     */
    private float clamp(float toClamp, float low, float high) {
        return Math.max(Math.min(toClamp, high), low);
    }


    /**
     * Cache Invalidators - These methods keep track of the constant,
     * "one-step-behind" color formats and ensure that at least one
     * color format is up-to-date at any given time.
     */

    private Color onSetHSB() {
        this.updateRGB = true;
        this.updateHex = true;
        return updateHSB();
    }

    private Color onSetRGB() {
        this.updateHSB = true;
        this.updateHex = true;
        return updateRGB();
    }

    private Color onSetHex() {
        this.updateHSB = true;
        this.updateRGB = true;
        return updateHex();
    }


    /**
     * Individual Getters - returns actual values of this Color in a
     * variety of formats. Each call checks to make sure the requested
     * Color format is available, and if not, converts it
     */


    /**
     * @return The Hue component of this Color between 0.0 and 1.0
     */
    public float toHue() {
        updateHSB();
        return this.hue;
    }

    /**
     * @return The Saturation component of this Color between 0.0 and 1.0
     */
    public float toSaturation() {
        updateHSB();
        return this.saturation;
    }

    /**
     * @return The Brightness component of this Color between 0.0 and 1.0
     */
    public float toBrightness() {
        updateHSB();
        return this.brightness;
    }

    /**
     * @return The Red component of this Color between 0.0 and 1.0
     */
    public float toRed() {
        updateRGB();
        return this.red;
    }

    /**
     * @return The Green component of this Color between 0.0 and 1.0
     */
    public float toGreen() {
        updateRGB();
        return this.green;
    }

    /**
     * @return The Blue component of this Color between 0.0 and 1.0
     */
    public float toBlue() {
        updateRGB();
        return this.blue;
    }

    /**
     * @return The Red component of this Color between 0 and 255
     */
    public int toRedInt() {
        return (int) (toRed() * 255F);
    }

    /**
     * @return The Green component of this Color between 0 and 255
     */
    public int toGreenInt() {
        return (int) (toGreen() * 255F);
    }

    /**
     * @return The Blue component of this Color between 0 and 255
     */
    public int toBlueInt() {
        return (int) (toBlue() * 255F);
    }

    /**
     * @return The Alpha component of this Color between 0.0 and 1.0
     */
    public float toAlpha() {
        return this.alpha;
    }

    /**
     * @return The Alpha component of this Color between 0 and 255
     */
    public int toAlphaInt() {
        return (int) (toAlpha() * 255F);
    }

    /**
     * @return This Color as a 32-bit integer, with alpha
     */
    public int toHex() {
        updateHex();
        return this.hex | (toAlphaInt() << 24);
    }


    /**
     * Cache Updaters - These functions handle the actual conversions between values.
     * These methods are not called when the color is set, but rather when a given
     * color format is requested (to decrease overhead)
     */

    private Color updateHSB() {
        if (this.updateHSB) {
            final float[] hsb = new float[3];
            java.awt.Color.RGBtoHSB(toRedInt(), toGreenInt(), toBlueInt(), hsb);
            this.hue = hsb[0];
            this.saturation = hsb[1];
            this.brightness = hsb[2];

            this.updateHSB = false;
        }
        return this;
    }

    private Color updateRGB() {
        if (this.updateRGB) {
            this.hex = this.updateHex ? java.awt.Color.HSBtoRGB(toHue(), toSaturation(), toBrightness()) : toHex();
            this.updateHex = false; // might as well update our hex while we have it

            this.red = (hex >> 16 & 0xFF) / 255F;
            this.green = (hex >> 8 & 0xFF) / 255F;
            this.blue = (hex & 0xFF) / 255F;
            this.updateRGB = false;
        }
        return this;
    }

    private Color updateHex() {
        if (this.updateHex) {
            if (buff.isBigEndian())
                this.hex = (toRedInt() << 24 | toGreenInt() << 16 | toBlueInt() << 8 | toAlphaInt());
            else
                this.hex = (toAlphaInt() << 24 | toBlueInt() << 16 | toGreenInt() << 8 | toRedInt());

            this.updateHex = false;
        }
        return this;
    }


    /**
     * This method simply returns a Color singleton that
     * is stored global just in case a developer needs a
     * fast, in-line color conversion with no instantiation
     * overhead. It shouldn't matter that this singleton is
     * not thread-safe because LWJGL isn't either.
     *
     * @return The Color singleton to enable conversions
     */
    public static Color convert() {
        return INSTANCE;
    }


    /**
     * Builder functions - These ensure that the given color is initialized
     * with SOMETHING before you start calling getters
     */


    /**
     * Creates a Color object with an initial Hue, Saturation, and Brightness.
     * This Color's Alpha will default to 1F (fully opaque)
     *
     * @param hue        The Hue component between 0.0 and 1.0
     * @param saturation The Saturation component between 0.0 and 1.0
     * @param brightness The Brightness component between 0.0 and 1.0
     * @return The newly created Color object
     */
    public static Color fromHSB(float hue, float saturation, float brightness) {
        return new Color().hsb(hue, saturation, brightness);
    }

    /**
     * Creates a Color object with an initial Hue, Saturation, Brightness, & Alpha
     *
     * @param hue        The Hue component between 0.0 and 1.0
     * @param saturation The Saturation component between 0.0 and 1.0
     * @param brightness The Brightness component between 0.0 and 1.0
     * @param alpha      The Alpha component between 0.0 and 1.0
     * @return The newly created Color object
     */
    public static Color fromHSBA(float hue, float saturation, float brightness, float alpha) {
        return new Color().hsba(hue, saturation, brightness, alpha);
    }

    /**
     * Creates a Color object with an initial Red, Green, & Blue
     * This Color's Alpha will default to 1F (fully opaque)
     *
     * @param red   The Red component between 0.0 and 1.0
     * @param green The Green component between 0.0 and 1.0
     * @param blue  The Blue component between 0.0 and 1.0
     * @return The newly created Color object
     */
    public static Color fromRGB(float red, float green, float blue) {
        return new Color().rgb(red, green, blue);
    }

    /**
     * Creates a Color object with an initial Red, Green, Blue, & Alpha
     *
     * @param red   The Red component between 0.0 and 1.0
     * @param green The Green component between 0.0 and 1.0
     * @param blue  The Blue component between 0.0 and 1.0
     * @param alpha The Alpha component between 0.0 and 1.0
     * @return The newly created Color object
     */
    public static Color fromRGBA(float red, float green, float blue, float alpha) {
        return new Color().rgba(red, green, blue, alpha);
    }

    /**
     * Creates a Color object with an initial Red, Green, & Blue
     * This Color's Alpha will default to 255 (fully opaque)
     *
     * @param red   The Red component between 0 and 255
     * @param green The Green component between 0 and 255
     * @param blue  The Blue component between 0 and 255
     * @return The newly created Color object
     */
    public static Color fromRGB(int red, int green, int blue) {
        return new Color().rgb(red, green, blue);
    }

    /**
     * Creates a Color object with an initial Red, Green, Blue & Alpha
     *
     * @param red   The Red component between 0 and 255
     * @param green The Green component between 0 and 255
     * @param blue  The Blue component between 0 and 255
     * @param alpha The Alpha component between 0 and 255
     * @return The newly created Color object
     */
    public static Color fromRGBA(int red, int green, int blue, int alpha) {
        return new Color().rgba(red, green, blue, alpha);
    }

    /**
     * Creates a Color object initialized with a hex value.
     * Alpha will default to 1 (fully opaque) IF AND ONLY IF
     * hex bits 24-32 are not set
     *
     * @param color 32-bit integer in the format 'ARGB'
     * @return The newly created Color object
     */
    public static Color fromHex(int color) {
        return new Color().hex(color);
    }

    /**
     * Creates a Color object initialized with a hex value.
     * Color bits 24-32 are overwritten with the alpha parameter
     *
     * @param color 32-bit integer in the format 'ARGB'
     * @param alpha The Alpha component between 0.0 and 1.0
     * @return The newly created Color object
     */
    public static Color fromHexA(int color, float alpha) {
        return new Color().hexa(color, alpha);
    }
}

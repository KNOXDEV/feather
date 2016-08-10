package me.aarow.feather.render;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.WeakHashMap;

import me.aarow.feather.tessellate.GrowingTess;
import me.aarow.feather.texture.base.Texture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * FontRend is a simple, one-class library for the rendering of all Unicode Strings using OpenType fonts.
 * It is adapted from thvortex's BetterFonts, found here: https://github.com/user/thvortex/BetterFonts
 *
 * There are three key ways in which this implementation differs from thvortex's:
 * 1 - This version has been generalized for LWJGL rather than purely Minecraft.
 *     As such, all color-code related information or digit optimizations are gone.
 * 2 - This version does not attempt to search the system to find fonts for missing glyphs.
 * 3 - This version no longer implements the Bi-Directional Algorithm.
 *
 * Regardless of these changes, as much of the model remains the same, plenty of documentation will
 * be copied directly from thvortex's original repository.
 *
 * This aforementioned processing model is as follows: FontRend caches the glyph layout of individual strings,
 * and it also caches the pre-rendered images for individual glyphs. Once a string and its glyph images are cached,
 * the critical path in renderString() will draw the glyphs as fast as if using a bitmap font. Strings are cached
 * using weak references through a two layer string cache. Strings that are no longer in use by LWJGL will be
 * silently evicted from the cache, while the pre-rendered images of individual glyphs remains cached forever.
 *
 * This class is also responsible for selecting the proper fonts to render each glyph, since Java's own "SansSerif"
 * logical font does not always select the proper physical font to use (especially on less common Linux distributions).
 * Once a pre-rendered glyph image is cached, it will remain stored in an OpenGL texture for the entire lifetime of the application.
 *
 * @author KNOXDEV, thvortex
 * @since 8/9/2016 04:18
 */
public class FontRend {

	/**
	 * The width in pixels of every texture used for caching pre-rendered glyph images. Used when calculating
	 * floating point 0.0-1.0 texture coordinates. Must be a power of two for mip-mapping to work.
	 */
	private static final int TEXTURE_WIDTH = 256;

	/**
	 * The height in pixels of every texture used for caching pre-rendered glyph images. Used when calculating
	 * floating point 0.0-1.0 texture coordinates. Must be a power of two for mip-mapping to work.
	 */
	private static final int TEXTURE_HEIGHT = 256;

	/** Transparent (alpha zero) white background color for use with BufferedImage.clearRect(). */
	private static Color CLEAR = new Color(255, 255, 255, 1);

	/** The simple Feather Tessellator we've designated to render our glyphs. */
	private final GrowingTess tess;

	/**
	 * Every String passed to the public renderString() function is added to this WeakHashMap. As long as an application
	 * continues to hold a strong reference to the String object (i.e. from TileEntitySign and ChatLine) passed here, the
	 * weakRefCache map will continue to hold a strong reference to the Entry object that said strings all map to.
	 *
	 * By having these caches in a literal list, we're able to hotswap fonts with one FontRend, allowing us to render
	 * different sizes of fonts as well with little to no detriment to our performance.
	 */
	private final List<WeakHashMap<String, Entry>> caches = new ArrayList<WeakHashMap<String, Entry>>();

	/**

	 * A cache of all fonts that have at least one glyph pre-rendered in a texture. Each font maps to an integer (monotonically
	 * increasing) which forms the upper 32 bits of the key into the glyphCache map. This font cache can include different styles
	 * of the same font family like bold or italic.
	 */
	private final LinkedHashMap<Font, Integer> fontCache = new LinkedHashMap<Font, Integer>();

	/**
	 * A cache of pre-rendered glyphs mapping each glyph by its glyphcode to the position of its pre-rendered image within
	 * the cache texture. The key is a 64 bit number such that the lower 32 bits are the glyphcode and the upper 32 are the
	 * index of the font in the fontCache. This makes for a single globally unique number to identify any glyph from any font.
	 */
	private final LinkedHashMap<Long, Texture> glyphCache = new LinkedHashMap<Long, Texture>();

	/** All font glyphs are packed inside this image and are then loaded from here into an OpenGL texture. */
	private final BufferedImage glyphImage = new BufferedImage(TEXTURE_WIDTH, TEXTURE_HEIGHT, BufferedImage.TYPE_INT_ARGB);

	/** The Graphics2D associated with stringImage and used for string drawing to extract the individual glyph shapes. */
	private final Graphics2D glyphGraphics = glyphImage.createGraphics();

	/** Needed for all text layout operations that create GlyphVectors (maps point size to pixel size). */
	private final FontRenderContext fontContext = this.glyphGraphics.getFontRenderContext();

	/** Intermediate data array for use with textureImage.getRgb(). */
	private final int[] imageData = new int[TEXTURE_WIDTH * TEXTURE_HEIGHT];

	/**
	 * A big-endian direct int buffer used with glTexSubImage2D() and glTexImage2D(). Used for loading the pre-rendered glyph
	 * images from the glyphCacheImage BufferedImage into OpenGL textures. This buffer uses big-endian byte ordering to ensure
	 * that the integers holding packed RGBA colors are stored into memory in a predictable order.
	 */
	private IntBuffer imageBuffer = ByteBuffer.allocateDirect(4 * TEXTURE_WIDTH * TEXTURE_HEIGHT).order(ByteOrder.BIG_ENDIAN).asIntBuffer();

	/** Temporary image for rendering a string to and then extracting the glyph images from. */
	private BufferedImage stringImage;

	/** The Graphics2D associated with stringImage and used for string drawing to extract the individual glyph shapes. */
	private Graphics2D stringGraphics;

	/**
	 * The X coordinate of the upper=left corner in glyphCacheImage where the next glyph image should be stored. Glyphs are
	 * always added left-to-right on the curren tline until it fills up, at which point they continue filling the texture on
	 * the next line.
	 */
	private int cacheX = 1;

	/**
	 * The Y coordinate of the upper-left corner in glyphCacheImage where the next glyph image should be stored. Glyphs are
	 * stored left-to-right in horizontal lines, and top-to-bottom until the entire texture fills up. At that point, a new
	 * texture is allocated to keep storing additional glyphs, and the original texture remains allocated for the lifetime of
	 * the application.
	 */
	private int cacheY = 1;

	/**
	 * The height in pixels of the current line of glyphs getting written into the texture. This value determines by how much
	 * cachePosY will get incremented when the current horizontal line in the texture fills up.
	 */
	private int cacheLineHeight = 0;

	/** ID of current OpenGL cache texture being used by cacheGlyphs() to store pre-rendered glyph images. */
	private int texture;

	/** A font object representing our currently configured Font. */
	private Font font;

	/** Our starting font size in pixels. This can be reset at any time. */
	private int size = 18;

	/** The Font.Style of the current font being rendered */
	private int style;

	/** If true, then enable anti-aliasing when rendering the font glyph */
	private boolean antialias;

	/** This boolean is true if there are pending changes to our current font object */
	private boolean pending;

	/** The current X coordinate to render fonts at */
	public float posX;

	/** The current Y coordinate to render fonts at */
	public float posY;

	/** The width of the last string rendered */
	public int advance;

	/**
	 * Allocates font rendering dependencies.
	 * @param font The initial Font to use
	 * @param fontStyle The initial Font.Style to use
	 * @param fontSize The initial font size in pixels
	 * @param antialias Initial font boolean if anti-aliasing is enabled
     */
	public FontRend(String font, int fontStyle, int fontSize, boolean antialias) {
		this();
		this.setFont(font, fontStyle, fontSize, antialias);
	}

	/**
	 * Allocates font rendering dependencies.
	 * Uses Java's logical font as the initial default font.
	 */
	public FontRend() {
		/* We'll start with 4 characters, and expand exponentially! */
		this.tess = new GrowingTess(4 * 4);

		/* Set background color for use with clearRect() */
		this.glyphGraphics.setBackground(CLEAR);

		/* The drawImage() to this buffer will copy all source pixels instead of alpha blending them into the current image */
		this.glyphGraphics.setComposite(AlphaComposite.Src);

		this.allocateTexture();
		this.allocateStringImage(256, 64);

		/* Use Java's logical font as the default initial font */
		GraphicsEnvironment.getLocalGraphicsEnvironment().preferLocaleFonts();
		setFont(new Font(Font.SANS_SERIF, 0, this.size));
	}

	/**
	 * @param fontSize The size in pixels to set the current font to.
     */
	public void setSize(int fontSize) {
		this.size = fontSize;
		this.pending = true;
	}

	/**
	 * @param fontStyle The Font.Style to set the current font to.
	 */
	public void setStyle(int fontStyle) {
		this.style = fontStyle;
		this.pending = true;
	}

	/**
	 * @param antialias Set to true to use anti-aliasing with the current Font.
     */
	public void setAntialias(boolean antialias) {
		this.antialias = antialias;
		this.setRenderingHints();
		this.pending = true;
	}

	/**
	 * @param font The current Font object to use.
     */
	public void setFont(Font font) {
		this.font = font;
		this.pending = true;
	}

	/**
	 * Sets the current Font given the settings.
	 * @param font The current Font object to use
	 * @param fontStyle The Font.Style to set the current font to.
	 * @param fontSize The size in pixels to set the current font to.
	 * @param antialias Set to true to use anti-aliasing with the current Font.
     */
	public void setFont(String font, int fontStyle, int fontSize, boolean antialias) {
		this.setSize(fontSize);
		this.setStyle(fontStyle);
		this.setAntialias(antialias);
		this.setFont(new Font(font, 0, fontSize));
	}

	/**
	 * Checks if there is a pending font change and recaches your font if there is.
	 * @return The current Font Object being used to render.
     */
	public Font getFont() {
		return this.pending ? this.cacheFont() : this.font;
	}

	/**
	 * @return A freshly-derived font given your current font settings
     */
	private Font cacheFont() {
		this.pending = false;
		return this.font = this.font.deriveFont(this.style, this.size);
	}

	/**
	 * Render a single-line string to the screen using the current OpenGL color. The (x,y) coordinates are of the uppet-left
	 * corner of the string's bounding box, rather than the baseline position as is typical with fonts. This function will also
	 * add the string to the cache so the next renderString() call with the same string is faster.
	 *
	 * @param str the string being rendered
	 * @param x the x coordinate to draw at
	 * @param y the y coordinate to draw at
	 * @return the current FontRend instance
	 */
	public FontRend renderString(String str, float x, float y) {

		/* Make sure the entire string is cached before rendering and return its glyph representation */
		final Entry entry = this.cacheString(str);

		/* Track which texture is currently bound to minimize the number of glBindTexture() and Tessellator.draw() calls needed */
		int boundTex = 0;

		/* Cycle through the Glyphs to be rendered */
		for(Glyph glyph : entry.glyphs) {
			final Texture texture = glyph.texture;

			/*
			* Make sure the OpenGL texture storing this glyph's image is bound (if not already bound). All pending glyphs in the
			* Tessellator's vertex array must be drawn before switching textures, otherwise they would erroneously use the new
			* texture as well.
			*/
			if(boundTex != texture.getID()) {
				if(boundTex != 0)
					tess.draw(GL11.GL_QUADS);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getID());
				boundTex = texture.getID();
			}
			final float x1 = x + glyph.x;
			final float x2 = x1 + texture.getWidth();
			final float y1 = y + glyph.y;
			final float y2 = y1 + texture.getHeight();
			tess.texture(texture.getU(), texture.getV()).vertex(x1, y1, 0);
			tess.texture(texture.getU(), texture.getV1()).vertex(x1, y2, 0);
			tess.texture(texture.getU1(), texture.getV1()).vertex(x2, y2, 0);
			tess.texture(texture.getU1(), texture.getV()).vertex(x2, y1, 0);
		}

		/* Draw any remaining glyphs in the Tessellator's vertex array (there should be at least one glyph pending) */
		tess.draw(GL11.GL_QUADS);
		this.posX = x;
		this.posY = y;

		/* Return total horizontal advance (slightly wider than the bounding box, but close enough for centering strings) */
		this.advance = entry.width;
		return this;
	}

	/**
	 * Return the width of a string in pixels.
	 *
	 * @param str compute the width of this string
	 * @return the width in pixels
	 */
	public int getStringWidth(String str) {
		return this.cacheString(str).width;
	}

	/**
	 * Add a string to the string cache by performing full layout on it, remembering its glyph positions, and making sure that
	 * every font glyph used by the string is pre-rendered. If this string has already been cached, then simply return its
	 * existing Entry from the cache.
	 *
	 * @param str this String will be laid out and added to the cache (or looked up, if already cached)
	 * @return the string's cache entry containing all the glyph positions
	 */
	protected Entry cacheString(String str) {

		/* First, retrieve the current font. */
		Integer font = this.fontCache.get(this.getFont());

		/* If font is not cached, derive the font */
		if(font == null) {
			font = this.fontCache.size();
			this.fontCache.put(this.font, font);
			this.caches.add(new WeakHashMap<String, Entry>());
		}

		/* Get the corresponding StringCache for this Font */
		final WeakHashMap<String, Entry> stringCache = this.caches.get(font);

		/* If this string is already in the cache, simply return the cached Entry object */
		Entry entry = stringCache.get(str);

		/* If string is not cached (or not on main thread) then layout the string */
		if(entry == null) {
			entry = new Entry(new String(str));

			/* layoutGlyphVector() requires a char[] so create it here and pass it around to avoid duplication later on */
			final char[] text = entry.str.toCharArray();

			/* Layout the entire string, storing as an array for efficiency */
			entry.glyphs = new Glyph[text.length];
			entry.width = this.cacheGlyphs(entry.glyphs, text);

			Arrays.sort(entry.glyphs);
			
			stringCache.put(entry.str, entry);
		}
		return entry;
	}

	/**
	 * Given an OpenType font and a string, make sure that every glyph used by that string is pre-rendered into an OpenGL texture and cached
	 * in the glyphCache map for later retrieval
	 *
	 * @param glyphs the array of glyphs to layout
	 * @param text the string from which to cache glyph images
	 * @return width of the string cached
	 */
	private int cacheGlyphs(Glyph[] glyphs, char[] text) {
		/* Create new GlyphVector so glyphs can be moved around (kerning workaround; see below) without affecting caller */
		final GlyphVector vector = this.font.layoutGlyphVector(this.fontContext, text, 0, text.length, Font.LAYOUT_LEFT_TO_RIGHT);

		/* A second vector of the same specifications to correctly calculate total string width */
		final GlyphVector vector2 = this.font.layoutGlyphVector(this.fontContext, text, 0, text.length, Font.LAYOUT_LEFT_TO_RIGHT);

		/* Pixel aligned bounding box for the entire vector; only set if the vector has to be drawn to cache a glyph image */
		Rectangle vectorBounds = null;

		Rectangle dirty = null;         /* Total area within texture that needs to be updated with glTexSubImage2D() */
		boolean vectorRendered = false; /* True if entire GlyphVector was rendered into stringImage */

		/* This forms the upper 32 bits of the fontCache key to make every font/glyph code point unique */
		final long fontKey = (long) fontCache.get(this.font) << 32;

		/* Length of the GlyphVector */
		final int numGlyphs = vector.getNumGlyphs();


		for(int index = 0; index < numGlyphs; index++) {
			final int glyphCode = vector.getGlyphCode(index);
			Texture tex = this.glyphCache.get(fontKey | glyphCode);

			/* If this glyph code is already in glyphCache, then there is no reason to pre-render it again */
			if(tex == null) {

				/*
             	* The only way to get glyph shapes with font hinting is to draw the entire glyph vector into a
             	* temporary BufferedImage, and then bit blit the individual glyphs based on their bounding boxes
             	* returned by the glyph vector. Although it is possible to call font.createGlyphVector() with an
             	* array of glyphcodes (and therefore render only a few glyphs at a time), this produces corrupted
             	* Davengari glyphs under Windows 7. The vectorRendered flag will draw the string at most one time.
             	*/
				if(!vectorRendered) {
					vectorRendered = true;

					/*
                 	* Kerning can make it impossible to cleanly separate adjacent glyphs. To work around this,
                 	* each glyph is manually advanced by 2 pixels to the right of its neighbor before rendering
                 	* the entire string. The getGlyphPixelBounds() later on will return the new adjusted bounds
                 	* for the glyph.
                 	*/
					for(int i = 0; i < numGlyphs; i++) {
						final Point2D pos = vector.getGlyphPosition(i);
	                    pos.setLocation(pos.getX() + 2 * i, pos.getY());
	                    vector.setGlyphPosition(i, pos);
					}

					/*
                 	* Compute the exact area that the rendered string will take up in the image buffer. Note that
                 	* the string will actually be drawn at a positive (x,y) offset from (0,0) to leave enough room
                 	* for the ascent above the baseline and to correct for a few glyphs that appear to have negative
                 	* horizontal bearing (e.g. U+0423 Cyrillic uppercase letter U on Windows 7).
                 	*/
					vectorBounds = vector.getPixelBounds(this.fontContext, 0, 0);

					/* Enlage the stringImage if it is too small to store the entire rendered string */
					if(this.stringImage == null || vectorBounds.width > this.stringImage.getWidth() || vectorBounds.height > this.stringImage.getHeight())
	                    this.allocateStringImage(Math.max(vectorBounds.width, this.stringImage.getWidth()), Math.max(vectorBounds.height, this.stringImage.getHeight()));

					/* Erase the upper-left corner where the string will get drawn*/
					this.stringGraphics.clearRect(0, 0, vectorBounds.width, vectorBounds.height);

					/* Draw string with opaque white color and baseline adjustment so the upper-left corner of the image is at (0,0) */
					this.stringGraphics.drawGlyphVector(vector, -vectorBounds.x, -vectorBounds.y);
				}

				/*
             	* Get the glyph's pixel-aligned bounding box. The JavaDoc claims that the "The outline returned
             	* by this method is positioned around the origin of each individual glyph." However, the actual
             	* bounds are all relative to the start of the entire GlyphVector, which is actually more useful
             	* for extracting the glyph's image from the rendered string.
             	*/
				final Rectangle rect = vector.getGlyphPixelBounds(index, null, -vectorBounds.x, -vectorBounds.y);

				/* If the current line in cache image is full, then advance to the next line */
				if(this.cacheX + rect.width + 1 > 256) {
	                this.cacheX = 1;
	                this.cacheY += cacheLineHeight + 1;
	                this.cacheLineHeight = 0;
	            }

				/*
             	* If the entire image is full, update the current OpenGL texture with everything changed so far in the image
             	* (i.e. the dirty rectangle), allocate a new cache texture, and then continue storing glyph images to the
             	* upper-left corner of the new texture.
             	*/
				if(this.cacheY + rect.height + 1 > 256) {
	                this.updateTexture(dirty);
	                dirty = null;

					/* Note that allocateAndSetupTexture() will leave the GL texture already bound */
	                this.allocateTexture();
	                this.cacheY = this.cacheX = 1;
	                cacheLineHeight = 0;
	            }

				/* The tallest glyph on this line determines the total vertical advance in the texture */
	            this.cacheLineHeight = Math.max(rect.height, this.cacheLineHeight);

				/*
             	* Blit the individual glyph from it's position in the temporary string buffer to its (cachePosX,
             	* cachePosY) position in the texture. NOTE: We don't have to erase the area in the texture image
             	* first because the composite method in the Graphics object is always set to AlphaComposite.Src.
             	*/
	            this.glyphGraphics.drawImage(this.stringImage, this.cacheX, this.cacheY, this.cacheX + rect.width, this.cacheY + rect.height, rect.x, rect.y, rect.x + rect.width, rect.y + rect.height, null);

				/*
             	* Store this glyph's position in texture and its origin offset. Note that "rect" will not be modified after
             	* this point, and getGlyphPixelBounds() always returns a new Rectangle.
             	*/
				rect.setLocation(this.cacheX, this.cacheY);

				/*
             	* Create new cache entry to record both the texture used by the glyph and its position within that texture.
             	* Texture coordinates are normalized to 0.0-1.0 by dividing with TEXTURE_WIDTH and TEXTURE_HEIGHT.
             	*/
	            tex = Texture.tex.createTexture(this.texture, rect.x, rect.y, rect.width, rect.height, TEXTURE_WIDTH);

				/*
             	* The lower 32 bits of the glyphCache key are the glyph codepoint. The upper 64 bits are the font number
            	 * stored in the fontCache. This creates a unique numerical id for every font/glyph combination.
            	 */
	            this.glyphCache.put(fontKey | glyphCode, tex);

				/*
             	* Track the overall modified region in the texture by performing a union of this glyph's texture position
             	* with the update region created so far. Reusing "rect" here makes it easier to extend the dirty rectangle
             	* region than using the add(x, y) method to extend by a single point. Also note that creating the first
             	* dirty rectangle here avoids having to deal with the special rules for empty/non-existent rectangles.
             	*/
	            if(dirty == null)
	                dirty = new Rectangle(this.cacheX, this.cacheY, rect.width, rect.height);
	            else 
	            	dirty.add(rect);

				/* Advance cachePosX so the next glyph can be stored immediately to the right of this one */
	            this.cacheX += rect.width + 1;
			}
			final Point point = vector2.getGlyphPixelBounds(index, null, 0, 0).getLocation();
			glyphs[index] = new Glyph(tex, point.x, point.y);
		}

		/* Update OpenGL texture if any part of the glyphCacheImage has changed */
		updateTexture(dirty);

		/* return total string width from the second vector */
		return (int)vector2.getGlyphPosition(numGlyphs).getX();
	}

	/**
	 * Update a portion of the current glyph cache texture using the contents of the glyphImage with glTexSubImage2D().
	 *
	 * @param dirty The rectangular region in glyphImage that has changed and needs to be copied into the texture
	 *
	 * @todo Add mip-mapping support here
	 * @todo Test with bilinear texture interpolation and possibly add a 1 pixel transparent border around each glyph to avoid
	 */
	private void updateTexture(Rectangle dirty){
        if(dirty != null) {
            this.updateBuffer(dirty.x, dirty.y, dirty.width, dirty.height);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, dirty.x, dirty.y, dirty.width, dirty.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, this.imageBuffer);
        }
    }

	/**
	 * Allocte and initialize a new BufferedImage and Graphics2D context for rendering strings into. May need to be called
	 * at runtime to re-allocate a bigger BufferedImage if cacheGlyphs() is called with a very long string.
	 */
	private void allocateStringImage(int width, int height) {
		this.stringImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		this.stringGraphics = this.stringImage.createGraphics();
		this.setRenderingHints();

		/* Set background color for use with clearRect() */
		this.stringGraphics.setBackground(CLEAR);

		/*
         * Full white (1.0, 1.0, 1.0, 1.0) can be modulated by vertex color to produce a full gamut of text colors, although with
         * a GL_ALPHA8 texture, only the alpha component of the color will actually get loaded into the texture.
         */
		this.stringGraphics.setPaint(Color.WHITE);
	}

	/**
	 * Set rendering hints on stringGraphics object. Uses current antiAliasEnabled settings and is therefore called both from
	 * allocateStringImage() when expanding the size of the BufferedImage and from setDefaultFont() when changing current
	 * configuration.
	 */
	private void setRenderingHints() {
		stringGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, this.antialias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
	    stringGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, this.antialias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
	    stringGraphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
	}

	/**
	 * Allocate a new OpenGL texture for caching pre-rendered glyph images. The new texture is initialized to fully transparent
	 * white so the individual glyphs images within can have a transparent border between them. The new texture remains bound
	 * after returning from the function.
	 */
	private void allocateTexture() {

		/* Initialize the background to all white but fully transparent. */
		this.glyphGraphics.clearRect(0, 0, 256, 256);

		/* Allocate new OpenGL texure */
		this.texture = GL11.glGenTextures();

		/* Load imageBuffer with pixel data ready for transfer to OpenGL texture */
		this.updateBuffer(0, 0, 256, 256);

		/*
         * Initialize texture with the now cleared BufferedImage. Using a texture with GL_ALPHA8 internal format may result in
         * faster rendering since the GPU has to only fetch 1 byte per texel instead of 4 with a regular RGBA texture.
         */
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);

		// Had to change to RGBA format from ALPHA8 for compatibility with Minecraft's shitty rendering engine.
		//GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_ALPHA8, 256, 256, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, this.imageBuffer);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 256, 256, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, this.imageBuffer);

		/* Explicitely disable mipmap support becuase updateTexture() will only update the base level 0 */
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
	}

	/**
	 * Copy pixel data from a region in glyphCacheImage into imageBuffer and prepare it for use with glText(Sub)Image2D(). This
	 * function takes care of converting the ARGB format used with BufferedImage into the RGBA format used by OpenGL.
	 *
	 * @param x the horizontal coordinate of the region's upper-left corner
	 * @param y the vertical coordinate of the region's upper-left corner
	 * @param width the width of the pixel region that will be copied into the buffer
	 * @param height the height of the pixel region that will be copied into the buffer
	 */
	private void updateBuffer(int x, int y, int width, int height) {
		this.glyphImage.getRGB(x, y, width, height, this.imageData, 0, width);
		this.imageBuffer.clear();
		this.imageBuffer.put(this.imageData);
		this.imageBuffer.flip();
	}
	
	// Here are some inner classes


	/** This entry holds the layed out glyph positions for the cached string along with some relevant metadata. */
	protected static class Entry {

		/**
		 * A copy of the String which this Entry is indexing. A copy is used to avoid creating a strong reference to the original
		 * passed into renderString(). When the original String is no longer needed by an application, it will be garbage collected
		 * and the WeakHashMaps in StringCache will allow this Entry object to be collected as well.
		 */
		public final String str;

		/** Array of fully layed out glyphs for the string. Sorted by logical order of characters (i.e. glyph.stringIndex) */
		public Glyph[] glyphs;

		/** The total horizontal advance (i.e. width) for this string in pixels. */
		public int width;

		public Entry(String str) {
			this.str = str;
		}
	}

	/**
	 * Identifies a single glyph in the layed-out string. Includes a reference to a Texture Object with the OpenGL texture ID
	 * and position of the pre-rendered glyph image, and includes the x/y pixel coordinates of where this glyph occurs within
	 * the string to which this Glyph object belongs.
	 */
	protected static class Glyph implements Comparable<Glyph> {

		/** Glyph's horizontal position (in pixels) relative to the entire string's baseline */
		final int x;

		/** Glyph's vertical position (in pixels) relative to the entire string's baseline */
		final int y;

		/** Texture ID and position/size of the glyph's pre-rendered image within the cache texture. */
		public final Texture texture;

		/**
		 * Your standard constructor. See class documentation for details.
		 * @param texture Texture ID and position/size of the glyph's pre-rendered image within the cache texture
		 * @param x Glyph's horizontal position (in pixels) relative to the entire string's baseline
         * @param y Glyph's vertical position (in pixels) relative to the entire string's baseline
         */
		public Glyph(Texture texture, int x, int y) {
			this.texture = texture;
			this.x = x;
			this.y = y;
		}

		/**
		 * Allows arrays of Glyph objects to be sorted. Performs numeric comparison on stringIndex.
		 *
		 * @param o the other Glyph object being compared with this one
		 * @return either -1, 0, or 1 if this < other, this == other, or this > other
		 */
		@Override public int compareTo(Glyph o) {
            return (this.texture.getID() == o.texture.getID()) ? 0 : 1;
        }
	}
}
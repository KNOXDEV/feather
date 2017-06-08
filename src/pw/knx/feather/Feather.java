package pw.knx.feather;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;

/**
 * The central class of the Feather library.
 *
 * Basically functions as a convenient state manager for OpenGL,
 * wrapping the most commonly used functions in intuitive, intelligent,
 * chainable methods. In this way, Feather reduces your required knowledge of
 * obscure OpenGL constants and compacts otherwise painfully verbose state management code.
 *
 * Probably going to use the enum singleton pattern for thread safety and global access.
 * This should not pose an inconvenience since LWJGL only allows once thread to interact
 * with OpenGL anyways.
 *
 * WIP
 *
 * @author KNOXDEV
 * @since 6/7/2017 6:54 PM
 */
public enum Feather {
	feather;

	/**
	 * Binds an existing buffer object to the working buffer
	 *
	 * @param id The OpenGL ID of the buffer object to be bound
	 * @return the Feather manager, for additional chaining
	 */
	public Feather bindBuffer(int id) {
		glBindBuffer(GL_ARRAY_BUFFER, id);
		return this;
	}
}

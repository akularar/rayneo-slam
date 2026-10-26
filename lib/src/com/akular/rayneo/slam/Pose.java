package com.akular.rayneo.slam;

/**
 * Head pose as reported by the RayNeo runtime, in the runtime's raw frame.
 * Hand it to {@link StereoCamera#update} rather than interpreting it directly.
 *
 * <p>In StereoCamera's world frame (+X right, +Y up, +Z away from the start
 * position) the head sits at {@code (position[0], position[1], -position[2])}.
 */
public final class Pose {

    /** Head position in metres, runtime frame. */
    public final float[] position = new float[3];

    /** Head orientation quaternion {x, y, z, w}, runtime frame. */
    public final float[] rotation = new float[4];
}

package dev.erhai.kbremap;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Motion helpers built on the Material 3 EXPRESSIVE spring specifications.
 *
 * These are the published Expressive motion tokens, as (damping ratio, stiffness):
 *
 *     Spatial   default (0.8, 380)    fast (0.6, 800)    slow (0.8, 200)
 *     Effects   default (1.0, 1600)   fast (1.0, 3800)   slow (1.0, 800)
 *
 * For comparison the STANDARD scheme uses Spatial (0.9, 700) / (0.9, 1400) / (0.9, 300).
 * The Expressive spatial springs are deliberately more underdamped - that softer overshoot is
 * exactly what gives Expressive its springy character, so the numbers are used verbatim rather
 * than approximated.
 *
 * Springs are used instead of fixed-duration interpolators because that is what the Expressive
 * system specifies: motion is described by physics, not by a duration and an easing curve.
 */
public final class Motion {

    private Motion() {
    }

    // Expressive spatial
    private static final float SPATIAL_DAMPING_DEFAULT = 0.8f;
    private static final float SPATIAL_STIFFNESS_DEFAULT = 380f;
    private static final float SPATIAL_DAMPING_FAST = 0.6f;
    private static final float SPATIAL_STIFFNESS_FAST = 800f;
    private static final float SPATIAL_DAMPING_SLOW = 0.8f;
    private static final float SPATIAL_STIFFNESS_SLOW = 200f;

    // Expressive effects (used for opacity/colour-like changes; critically damped, no overshoot)
    private static final float EFFECTS_DAMPING = 1.0f;
    private static final float EFFECTS_STIFFNESS_DEFAULT = 1600f;
    private static final float EFFECTS_STIFFNESS_FAST = 3800f;

    private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty prop,
                                          float damping, float stiffness, float target) {
        SpringAnimation a = new SpringAnimation(v, prop);
        a.setSpring(new SpringForce(target)
                .setDampingRatio(damping)
                .setStiffness(stiffness));
        return a;
    }

    /** Position/size change. The default spatial spring. */
    public static void spatialDefault(View v, DynamicAnimation.ViewProperty prop, float target) {
        spring(v, prop, SPATIAL_DAMPING_DEFAULT, SPATIAL_STIFFNESS_DEFAULT, target).start();
    }

    /** Snappy position change, for direct responses to a touch. */
    public static void spatialFast(View v, DynamicAnimation.ViewProperty prop, float target) {
        spring(v, prop, SPATIAL_DAMPING_FAST, SPATIAL_STIFFNESS_FAST, target).start();
    }

    /** Opacity change. Critically damped so it fades cleanly with no bounce. */
    public static void fade(View v, float target) {
        spring(v, DynamicAnimation.ALPHA, EFFECTS_DAMPING, EFFECTS_STIFFNESS_DEFAULT, target).start();
    }

    /**
     * The little squash a row makes when you tick it: scale down on the fast spatial spring,
     * then release back to 1.
     */
    public static void press(final View v) {
        SpringAnimation down = spring(v, DynamicAnimation.SCALE_X,
                SPATIAL_DAMPING_FAST, SPATIAL_STIFFNESS_FAST, 0.97f);
        spring(v, DynamicAnimation.SCALE_Y, SPATIAL_DAMPING_FAST, SPATIAL_STIFFNESS_FAST, 0.97f)
                .start();
        down.addEndListener(new DynamicAnimation.OnAnimationEndListener() {
            @Override
            public void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value,
                                       float velocity) {
                if (!canceled) {
                    spatialDefault(v, DynamicAnimation.SCALE_X, 1f);
                    spatialDefault(v, DynamicAnimation.SCALE_Y, 1f);
                }
            }
        });
        down.start();
    }

    /**
     * Staggered entrance for a list row: slides up into place and fades in, offset by position so
     * the list assembles rather than appearing all at once. Stagger is capped so a long list does
     * not keep the user waiting for the last row.
     */
    public static void enterRow(View v, int position) {
        if (v == null) {
            return;
        }
        final float distance = v.getResources().getDisplayMetrics().density * 12f;
        int capped = Math.min(position, 8);
        v.setAlpha(0f);
        v.setTranslationY(distance);
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                spatialDefault(v, DynamicAnimation.TRANSLATION_Y, 0f);
                fade(v, 1f);
            }
        }, capped * 28L);
    }

    /** A short, quiet emphasis - used to confirm a status line just changed. */
    public static void emphasise(final View v) {
        SpringAnimation up = spring(v, DynamicAnimation.SCALE_X,
                SPATIAL_DAMPING_FAST, SPATIAL_STIFFNESS_FAST, 1.03f);
        up.addEndListener(new DynamicAnimation.OnAnimationEndListener() {
            @Override
            public void onAnimationEnd(DynamicAnimation a, boolean canceled, float value,
                                       float velocity) {
                if (!canceled) {
                    spatialDefault(v, DynamicAnimation.SCALE_X, 1f);
                }
            }
        });
        up.start();
    }
}

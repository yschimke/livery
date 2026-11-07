package android.app;

/**
 * Not Android's: only so Robolectric's {@code Config}, which names this class in its defaults, can
 * load on the plain JVM class path `livery play` starts with. Robolectric's sandbox plays the real
 * one from the Android SDK it downloads.
 */
public class Application {}
